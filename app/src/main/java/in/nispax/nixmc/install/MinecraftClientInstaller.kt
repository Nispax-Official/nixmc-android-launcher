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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs official Minecraft 1.21.11 files from Mojang.
 * Android Java runtime and renderer integration are separate requirements.
 */
object MinecraftClientInstaller {
    private const val VERSION = "1.21.11"
    private const val MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private const val RESOURCES_URL = "https://resources.download.minecraft.net"

    suspend fun downloadClient(
        context: Context,
        onStatus: (String) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        onStatus("Fetching official version manifest…")
        val manifest = getJson(MANIFEST_URL)
        val versions = manifest.getJSONArray("versions")
        var metadataUrl: String? = null
        for (i in 0 until versions.length()) {
            val item = versions.getJSONObject(i)
            if (item.optString("id") == VERSION && item.optString("type") == "release") {
                metadataUrl = item.optString("url").takeIf { it.startsWith("https://") }
                break
            }
        }
        val resolvedMetadataUrl = metadataUrl
            ?: error("Minecraft $VERSION was not found in Mojang's release manifest.")

        onStatus("Fetching Minecraft $VERSION metadata…")
        val metadata = getJson(resolvedMetadataUrl)
        val versionDir = File(context.filesDir, "minecraft/versions/$VERSION")
        check(versionDir.exists() || versionDir.mkdirs()) {
            "Could not create the Minecraft version directory."
        }
        File(versionDir, "$VERSION.json").writeText(metadata.toString())

        val client = metadata.getJSONObject("downloads").getJSONObject("client")
        val clientJar = downloadVerified(
            client.getString("url"),
            File(versionDir, "$VERSION.jar"),
            client.optString("sha1"),
            client.optLong("size", -1L),
            onStatus,
            "Minecraft client"
        )

        val gameDir = File(context.filesDir, "minecraft")
        val librariesDir = File(gameDir, "libraries")
        val libraries = metadata.optJSONArray("libraries")
        if (libraries != null) {
            data class LibraryArtifact(val url: String, val path: String, val sha1: String, val size: Long)
            val artifacts = mutableListOf<LibraryArtifact>()
            for (i in 0 until libraries.length()) {
                val library = libraries.getJSONObject(i)
                // Only regular JVM artifacts are downloaded here. Native classifiers
                // are platform-specific and Linux binaries cannot be used directly on Android.
                val artifact = library.optJSONObject("downloads")?.optJSONObject("artifact") ?: continue
                val url = artifact.optString("url")
                val path = artifact.optString("path")
                if (!url.startsWith("https://") || path.isBlank() || path.contains("..")) continue
                artifacts += LibraryArtifact(url, path, artifact.optString("sha1"), artifact.optLong("size", -1L))
            }
            onStatus("Installing ${artifacts.size} library files…")
            val gate = Semaphore(4)
            coroutineScope {
                artifacts.mapIndexed { index, item ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val target = File(librariesDir, item.path)
                            downloadVerified(
                                item.url, target, item.sha1, item.size, {},
                                "Library ${index + 1}/${artifacts.size}"
                            )
                        }
                    }
                }.awaitAll()
            }
        }

        val assetIndexInfo = metadata.optJSONObject("assetIndex")
            ?: error("Minecraft metadata does not include an asset index.")
        val assetIndexId = assetIndexInfo.getString("id")
        val assetIndexUrl = assetIndexInfo.getString("url")
        val assetIndexSha1 = assetIndexInfo.optString("sha1")
        val assetIndexFile = File(gameDir, "assets/indexes/$assetIndexId.json")
        downloadVerified(
            assetIndexUrl,
            assetIndexFile,
            assetIndexSha1,
            assetIndexInfo.optLong("size", -1L),
            onStatus,
            "Asset index"
        )
        val assetIndex = JSONObject(assetIndexFile.readText())
        val objects = assetIndex.getJSONObject("objects")
        val assetEntries = mutableListOf<Pair<String, String>>()
        val keys = objects.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val hash = objects.getJSONObject(key).optString("hash")
            if (hash.matches(Regex("[0-9a-fA-F]{40}"))) assetEntries += key to hash.lowercase()
        }
        val assetObjectsDir = File(gameDir, "assets/objects")
        onStatus("Installing ${assetEntries.size} asset objects…")
        val assetGate = Semaphore(6)
        var completed = 0
        coroutineScope {
            assetEntries.map { (_, hash) ->
                async(Dispatchers.IO) {
                    assetGate.withPermit {
                        val target = File(assetObjectsDir, "${hash.take(2)}/$hash")
                        if (!target.isFile || sha1(target) != hash) {
                            downloadVerified(
                                "$RESOURCES_URL/${hash.take(2)}/$hash",
                                target,
                                hash,
                                -1L,
                                {},
                                "Asset"
                            )
                        }
                        val done = synchronized(assetEntries) { ++completed }
                        if (done % 50 == 0 || done == assetEntries.size) {
                            onStatus("Installing asset objects… $done/${assetEntries.size}")
                        }
                    }
                }
            }.awaitAll()
        }
        onStatus("Minecraft 1.21.11 client, libraries, and assets are installed. Android Java 21 runtime and renderer are still required to launch.")
        clientJar
    }

    private fun downloadVerified(
        url: String,
        target: File,
        expectedSha1: String,
        expectedSize: Long,
        onStatus: (String) -> Unit,
        label: String
    ): File {
        require(url.startsWith("https://")) { "Refusing non-HTTPS download URL." }
        if (target.isFile &&
            (expectedSize < 0L || target.length() == expectedSize) &&
            (expectedSha1.isBlank() || sha1(target).equals(expectedSha1, ignoreCase = true))
        ) return target

        val parent = target.parentFile
        check(parent != null && (parent.exists() || parent.mkdirs())) {
            "Could not create install directory."
        }
        val temporary = File(parent, target.name + ".part")
        onStatus("Downloading $label…")
        val connection = openConnection(url)
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("Download returned HTTP $status.")
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (expectedSize >= 0L) check(temporary.length() == expectedSize) {
                "$label size mismatch; retry the download."
            }
            if (expectedSha1.isNotBlank()) check(
                sha1(temporary).equals(expectedSha1, ignoreCase = true)
            ) { "$label SHA-1 verification failed; retry the download." }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) { "Could not finalize $label download." }
            return target
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String): JSONObject {
        val connection = openConnection(url)
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("Mojang returned HTTP $status.")
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("User-Agent", "NixMC-Android-Launcher/0.3.0")
            setRequestProperty("Accept", "application/json, application/octet-stream")
        }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
