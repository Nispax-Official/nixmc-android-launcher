package `in`.nispax.nixmc.install

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CancellationException

/** Official Minecraft files use app-private filesDir/minecraft storage and publisher hashes. */
object MinecraftClientInstaller {
    private const val VERSION = "1.21.11"
    private const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private const val RESOURCES_URL = "https://resources.download.minecraft.net"
    private const val MAX_ARTIFACT_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_METADATA_BYTES = 16L * 1024L * 1024L
    private val sha1Pattern = Regex("[0-9a-fA-F]{40}")

    suspend fun downloadClient(context: Context, onStatus: (String) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            DownloadCancellation.throwIfRequested()
            val gameDir = File(context.filesDir, "minecraft").canonicalFile
            check(gameDir.exists() || gameDir.mkdirs()) { "Could not create the private game directory." }

            // Remove abandoned temporary downloads after a crash/force-stop. Complete,
            // previously verified artifacts remain in place and are reused after hash checks.
            gameDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".part") }
                .forEach { it.delete() }

            onStatus("Connecting securely to Mojang's version manifest…")
            val manifest = getJson(MANIFEST_URL, label = "Mojang version manifest", onStatus = onStatus)
            val versions = manifest.getJSONArray("versions")
            var metadataUrl: String? = null
            var metadataSha1: String? = null
            for (i in 0 until versions.length()) {
                val item = versions.getJSONObject(i)
                if (item.optString("id") == VERSION && item.optString("type") == "release") {
                    metadataUrl = item.optString("url").takeIf { it.startsWith("https://") }
                    metadataSha1 = item.optString("sha1").takeIf { sha1Pattern.matches(it) }
                    break
                }
            }
            val resolvedMetadataUrl = metadataUrl
                ?: error("Minecraft $VERSION was not found in Mojang's HTTPS release manifest.")
            val resolvedMetadataSha1 = metadataSha1
                ?: error("Mojang did not provide a valid integrity hash for Minecraft $VERSION metadata.")

            onStatus("Downloading and verifying Minecraft $VERSION metadata…")
            val metadata = getJson(resolvedMetadataUrl, resolvedMetadataSha1, "Minecraft version metadata", onStatus)
            val versionDir = File(gameDir, "versions/$VERSION")
            check(versionDir.exists() || versionDir.mkdirs()) { "Could not create the Minecraft version directory." }
            writeTextAtomically(File(versionDir, "$VERSION.json"), metadata.toString())

            val client = metadata.getJSONObject("downloads").getJSONObject("client")
            val clientJar = downloadVerified(
                client.getString("url"), File(versionDir, "$VERSION.jar"), gameDir,
                client.getString("sha1"), client.optLong("size", -1L), onStatus, "Minecraft client"
            )

            val libraries = metadata.optJSONArray("libraries")
            if (libraries != null) {
                data class LibraryArtifact(val url: String, val path: String, val sha1: String, val size: Long)
                val artifacts = mutableListOf<LibraryArtifact>()
                for (i in 0 until libraries.length()) {
                    DownloadCancellation.throwIfRequested()
                    val library = libraries.getJSONObject(i)
                    val artifact = library.optJSONObject("downloads")?.optJSONObject("artifact") ?: continue
                    val url = artifact.optString("url")
                    val path = artifact.optString("path")
                    if (!url.startsWith("https://") || !isSafeRelative(path)) {
                        error("Mojang metadata contains an unsafe or non-HTTPS library artifact.")
                    }
                    val expectedSha1 = artifact.optString("sha1")
                    val expectedSize = artifact.optLong("size", -1L)
                    require(sha1Pattern.matches(expectedSha1) && expectedSize >= 0L) {
                        "Mojang metadata did not provide valid integrity data for library $path."
                    }
                    artifacts += LibraryArtifact(url, path, expectedSha1, expectedSize)
                }
                onStatus("Installing " + artifacts.size + " verified library files…")
                val gate = Semaphore(4)
                val completedLibraries = AtomicInteger(0)
                coroutineScope {
                    artifacts.map { item ->
                        async(Dispatchers.IO) {
                            gate.withPermit {
                                DownloadCancellation.throwIfRequested()
                                val target = safeChild(gameDir, "libraries/" + item.path)
                                downloadVerified(item.url, target, gameDir, item.sha1, item.size, {}, "Library")
                                val done = completedLibraries.incrementAndGet()
                                if (done % 5 == 0 || done == artifacts.size) {
                                    onStatus("Installing library files… " + done + "/" + artifacts.size)
                                }
                            }
                        }
                    }.awaitAll()
                }
            }

            val assetIndexInfo = metadata.optJSONObject("assetIndex")
                ?: error("Minecraft metadata does not include an asset index.")
            val assetIndexId = assetIndexInfo.getString("id")
            val assetIndexFile = safeChild(gameDir, "assets/indexes/$assetIndexId.json")
            downloadVerified(
                assetIndexInfo.getString("url"), assetIndexFile, gameDir, assetIndexInfo.getString("sha1"),
                assetIndexInfo.optLong("size", -1L), onStatus, "Asset index"
            )
            val assetIndex = JSONObject(assetIndexFile.readText(Charsets.UTF_8))
            val objects = assetIndex.getJSONObject("objects")
            val assetEntries = mutableListOf<Pair<String, String>>()
            val keys = objects.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val hash = objects.getJSONObject(key).optString("hash")
                if (sha1Pattern.matches(hash)) assetEntries += key to hash.lowercase()
                else error("Minecraft asset index contains an invalid SHA-1 object key.")
            }

            onStatus("Installing " + assetEntries.size + " verified asset objects…")
            val assetGate = Semaphore(6)
            val completedAssets = AtomicInteger(0)
            coroutineScope {
                assetEntries.map { (_, hash) ->
                    async(Dispatchers.IO) {
                        assetGate.withPermit {
                            DownloadCancellation.throwIfRequested()
                            val target = safeChild(gameDir, "assets/objects/" + hash.take(2) + "/" + hash)
                            downloadVerified(
                                RESOURCES_URL + "/" + hash.take(2) + "/" + hash,
                                target, gameDir, hash, -1L, {}, "Asset"
                            )
                            val done = completedAssets.incrementAndGet()
                            if (done % 25 == 0 || done == assetEntries.size) {
                                onStatus("Installing verified asset objects… " + done + "/" + assetEntries.size)
                            }
                        }
                    }
                }.awaitAll()
            }

            onStatus("Minecraft 1.21.11 client, libraries, and assets are verified in private app storage.")
            clientJar
        }

    private fun getJson(
        url: String, expectedSha1: String? = null, label: String, onStatus: (String) -> Unit
    ): JSONObject = withRetry(label, onStatus) {
        DownloadCancellation.throwIfRequested()
        require(url.startsWith("https://")) { "Refusing a non-HTTPS metadata URL." }
        val connection = HttpsConnectionFactory.open(url)
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("$label returned HTTP $status.")
            val advertisedLength = connection.contentLengthLong
            check(advertisedLength <= MAX_METADATA_BYTES || advertisedLength < 0L) {
                "$label exceeded the permitted metadata size."
            }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    DownloadCancellation.throwIfRequested()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= MAX_METADATA_BYTES) {
                        "$label exceeded the permitted metadata size."
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (!expectedSha1.isNullOrBlank()) {
                require(sha1(bytes).equals(expectedSha1, ignoreCase = true)) { "$label SHA-1 verification failed." }
            }
            JSONObject(String(bytes, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadVerified(
        url: String, target: File, baseDirectory: File, expectedSha1: String, expectedSize: Long,
        onStatus: (String) -> Unit, label: String
    ): File {
        require(url.startsWith("https://")) { "Refusing non-HTTPS download URL." }
        require(sha1Pattern.matches(expectedSha1)) { "$label has no valid publisher SHA-1 hash." }
        require(expectedSize >= -1L) { "$label has an invalid expected size." }

        return withRetry(label, onStatus) {
            DownloadCancellation.throwIfRequested()
            val base = baseDirectory.canonicalFile
            val parent = target.parentFile ?: error("Could not determine $label destination directory.")
            check(parent.exists() || parent.mkdirs()) { "Could not create install directory." }
            val canonicalParent = parent.canonicalFile
            check(canonicalParent.path == base.path || canonicalParent.path.startsWith(base.path + File.separator)) {
                "Destination parent escaped the private game directory."
            }
            val canonicalTarget = File(canonicalParent, target.name).canonicalFile
            check(canonicalTarget.path.startsWith(base.path + File.separator)) {
                "Destination path escapes the private game directory."
            }
            if (canonicalTarget.isFile &&
                (expectedSize < 0L || canonicalTarget.length() == expectedSize) &&
                sha1(canonicalTarget).equals(expectedSha1, ignoreCase = true)
            ) return@withRetry canonicalTarget

            val temporary = File(canonicalParent, target.name + ".part")
            val connection = HttpsConnectionFactory.open(url)
            try {
                val status = connection.responseCode
                if (status !in 200..299) error("$label returned HTTP $status.")
                val contentLength = when {
                    expectedSize >= 0L -> expectedSize
                    connection.contentLengthLong > 0L -> connection.contentLengthLong
                    else -> -1L
                }
                var total = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            DownloadCancellation.throwIfRequested()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= MAX_ARTIFACT_BYTES) { "$label exceeded the permitted artifact size." }
                            if (contentLength >= 0L) check(total <= contentLength) {
                                "$label exceeded its declared download size."
                            }
                            output.write(buffer, 0, count)
                            if (contentLength > 0L) {
                                val percent = (total * 100L / contentLength).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onStatus("Downloading $label… $percent%")
                                }
                            }
                        }
                    }
                }
                if (expectedSize >= 0L) check(total == expectedSize) {
                    "$label size mismatch; the incomplete download was discarded."
                }
                check(sha1(temporary).equals(expectedSha1, ignoreCase = true)) {
                    "$label SHA-1 verification failed; the incomplete download was discarded."
                }
                atomicReplace(temporary, canonicalTarget)
                canonicalTarget
            } catch (error: Exception) {
                temporary.delete()
                throw error
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun <T> withRetry(label: String, onStatus: (String) -> Unit, action: () -> T): T {
        var lastError: Exception? = null
        for (attempt in 1..3) {
            DownloadCancellation.throwIfRequested()
            try {
                return action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                if (attempt < 3) {
                    onStatus("Connection or integrity check failed for $label; retrying (" + (attempt + 1) + "/3)…")
                    try {
                        Thread.sleep(attempt * 1_000L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw CancellationException("Download canceled by the user.")
                    }
                }
            }
        }
        throw IllegalStateException("$label failed after 3 attempts: " + (lastError?.message ?: "unknown network error"), lastError)
    }

    private fun safeChild(parent: File, relativePath: String): File {
        require(isSafeRelative(relativePath)) { "Unsafe relative installation path." }
        val root = parent.canonicalFile
        val child = File(root, relativePath).canonicalFile
        check(child.path.startsWith(root.path + File.separator)) {
            "A path escaped the private game directory."
        }
        return child
    }

    private fun isSafeRelative(path: String): Boolean =
        path.isNotBlank() && !path.startsWith("/") && !path.contains('\\') &&
            !path.contains(':') && path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    private fun writeTextAtomically(target: File, content: String) {
        val parent = target.parentFile ?: error("Could not determine metadata directory.")
        check(parent.exists() || parent.mkdirs()) { "Could not create metadata directory." }
        val temporary = File(parent, target.name + ".part")
        try {
            temporary.writeText(content, Charsets.UTF_8)
            atomicReplace(temporary, target)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun atomicReplace(temporary: File, target: File) {
        try {
            Files.move(temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
