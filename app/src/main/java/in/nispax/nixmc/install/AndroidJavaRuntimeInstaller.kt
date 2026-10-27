package `in`.nispax.nixmc.install

import android.system.Os
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException

data class InstalledJavaRuntime(
    val home: File,
    val javaExecutable: File,
    val majorVersion: Int
)

data class JavaRuntimeStatus(
    val runtime: InstalledJavaRuntime?,
    val diagnostic: String
) {
    val isReady: Boolean get() = runtime != null
}

/**
 * Downloads and verifies a pinned Android OpenJDK 21 runtime build.
 * Runtime setup remains separate from the not-yet-integrated graphics bridge.
 */
object AndroidJavaRuntimeInstaller {
    private const val RUNTIME_VERSION = 21
    private const val RELEASE_BASE =
        "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre21/"

    private data class RuntimeAsset(val arch: String, val sha256: String)

    private val assets = mapOf(
        "arm64-v8a" to RuntimeAsset("arm64", "8d41ec401ee59f7722df60ed991f81ad146e130452804bfdd8a05d3436f7bbfe"),
        "armeabi-v7a" to RuntimeAsset("arm", "96c297487def64666e379a9a363d9955c05b1a0b091b0cf24af88359a66f394a"),
        "x86" to RuntimeAsset("x86", "9b8c7d10c5f751acb3b33506593da44ece52a0fd03e0b3c283ba08a7f285a40f"),
        "x86_64" to RuntimeAsset("x86_64", "cb88723961f5f9ad63afa1f212eb199816c27cabfd7dc66567bde1d8fb69713b")
    )

    /**
     * Re-checks a previously installed runtime without downloading anything.
     * The startup probe runs off the main thread and returns its diagnostic for
     * the launch-readiness screen.
     */
    suspend fun inspectInstalled(filesDir: File): JavaRuntimeStatus = withContext(Dispatchers.IO) {
        val runtimeRoot = File(filesDir, "minecraft/runtime/java-21")
        val java = findJava(runtimeRoot)
            ?: return@withContext JavaRuntimeStatus(null, "Java 21 runtime is not installed.")
        val probeError = testJava(java)
        if (probeError != null) {
            JavaRuntimeStatus(null, "Installed Java runtime failed its startup probe: $probeError")
        } else {
            JavaRuntimeStatus(
                InstalledJavaRuntime(runtimeRoot, java, RUNTIME_VERSION),
                "Java 21 runtime passed java -version."
            )
        }
    }

    suspend fun install(
        filesDir: File,
        onStatus: (String) -> Unit = {}
    ): InstalledJavaRuntime = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { assets.containsKey(it) }
            ?: error("No supported Android Java 21 runtime is available for this device.")
        if (abi == "x86") error("Java 21 runtime is not published for 32-bit x86 devices.")
        val asset = assets.getValue(abi)
        val fileName = "jre21-android-${asset.arch}.tar.xz"
        val gameRoot = File(filesDir, "minecraft")
        val cacheDir = File(gameRoot, "cache")
        val runtimeParent = File(gameRoot, "runtime")
        check(cacheDir.exists() || cacheDir.mkdirs()) { "Could not create private game cache directory." }
        check(runtimeParent.exists() || runtimeParent.mkdirs()) { "Could not create Java runtime directory." }
        val archive = File(cacheDir, fileName)
        val runtimeRoot = File(runtimeParent, "java-21")
        val stagingRoot = File(runtimeParent, "java-21.installing")
        val backupRoot = File(runtimeParent, "java-21.previous")
        val legacyArchive = File(File(filesDir, "cache"), fileName)
        legacyArchive.delete()
        File(legacyArchive.parentFile, legacyArchive.name + ".part").delete()

        val existingJava = findJava(runtimeRoot)
        if (existingJava != null) {
            val probeError = testJava(existingJava)
            if (probeError == null) {
                archive.delete()
                File(archive.parentFile, archive.name + ".part").delete()
                return@withContext InstalledJavaRuntime(runtimeRoot, existingJava, RUNTIME_VERSION)
            }
        }

        onStatus("Checking the pinned Java 21 runtime archive for " + asset.arch + "…")
        if (!archive.isFile || !sha256(archive).equals(asset.sha256, ignoreCase = true)) {
            archive.delete()
            download(RELEASE_BASE + fileName, archive, asset.sha256, onStatus)
        }
        check(sha256(archive).equals(asset.sha256, ignoreCase = true)) {
            archive.delete()
            "Java runtime SHA-256 verification failed. The archive was discarded."
        }

        onStatus("Extracting Java 21 runtime into a temporary install directory…")
        stagingRoot.deleteRecursively()
        check(stagingRoot.mkdirs()) { "Could not create Java runtime staging directory." }
        try {
            extractTarXz(archive, stagingRoot, onStatus)
            val stagedJava = findJava(stagingRoot)
                ?: error("Runtime archive extracted, but bin/java was not found.")
            stagedJava.setExecutable(true, true)
            val probeError = testJava(stagedJava)
            check(probeError == null) {
                "Java 21 files were extracted, but the runtime could not start: $probeError. A compatible Android JVM/native bridge is still required."
            }

            backupRoot.deleteRecursively()
            if (runtimeRoot.exists()) {
                check(runtimeRoot.renameTo(backupRoot)) { "Could not safely stage the previous Java runtime." }
            }
            if (!stagingRoot.renameTo(runtimeRoot)) {
                if (backupRoot.exists()) backupRoot.renameTo(runtimeRoot)
                error("Could not atomically activate the verified Java runtime.")
            }
            backupRoot.deleteRecursively()
        } catch (error: Exception) {
            stagingRoot.deleteRecursively()
            throw error
        }

        archive.delete()
        File(archive.parentFile, archive.name + ".part").delete()
        onStatus("Java 21 runtime installed and passed its startup check.")
        val installedJava = findJava(runtimeRoot)
            ?: error("Verified runtime was activated, but bin/java is missing.")
        InstalledJavaRuntime(runtimeRoot, installedJava, RUNTIME_VERSION)
    }

    private fun download(url: String, target: File, expectedSha256: String, onStatus: (String) -> Unit) {
        val temporary = File(target.parentFile, target.name + ".part")
        var lastError: Exception? = null
        for (attempt in 1..3) {
            DownloadCancellation.throwIfRequested()
            val connection = try {
                HttpsConnectionFactory.open(url)
            } catch (error: Exception) {
                lastError = error
                if (attempt == 3) break
                onStatus("Java runtime connection failed; retrying (" + (attempt + 1) + "/3)…")
                try {
                    Thread.sleep(attempt * 1_000L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("Download canceled by the user.")
                }
                continue
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) error("Runtime download returned HTTP $code.")
                val length = connection.contentLengthLong
                var total = 0L
                var previous = -1
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            DownloadCancellation.throwIfRequested()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (length > 0L) check(total <= length) { "Java runtime exceeded its declared download size." }
                            output.write(buffer, 0, count)
                            if (length > 0L) {
                                val percent = (total * 100 / length).toInt().coerceIn(0, 100)
                                if (percent != previous) {
                                    previous = percent
                                    onStatus("Downloading Java 21 runtime… " + percent + "%")
                                }
                            }
                        }
                    }
                }
                if (length > 0L) check(total == length) { "Java runtime archive size did not match the server's declared size." }
                check(sha256(temporary).equals(expectedSha256, ignoreCase = true)) {
                    "Java runtime SHA-256 verification failed."
                }
                atomicReplace(temporary, target)
                return
            } catch (error: CancellationException) {
                temporary.delete()
                throw error
            } catch (error: Exception) {
                temporary.delete()
                lastError = error
                if (attempt == 3) break
                onStatus("Java runtime download or SHA-256 check failed; retrying (" + (attempt + 1) + "/3)…")
                try {
                    Thread.sleep(attempt * 1_000L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("Download canceled by the user.")
                }
            } finally {
                connection.disconnect()
            }
        }
        temporary.delete()
        throw IllegalStateException("Java runtime download failed after 3 attempts: " +
            (lastError?.message ?: "unknown network error"), lastError)
    }

    private fun atomicReplace(temporary: File, target: File) {
        try {
            Files.move(temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun extractTarXz(archive: File, destination: File, onStatus: (String) -> Unit) {
        val canonicalRoot = destination.canonicalFile
        TarArchiveInputStream(XZCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            var entry = tar.nextTarEntry
            var count = 0
            var totalExpandedBytes = 0L
            while (entry != null) {
                DownloadCancellation.throwIfRequested()
                check(count < 100_000) { "Java runtime archive contains too many entries." }
                val output = File(destination, entry.name).canonicalFile
                check(output.path.startsWith(canonicalRoot.path + File.separator) || output == canonicalRoot) {
                    "Runtime archive contains an unsafe path."
                }
                when {
                    entry.isDirectory -> check(output.exists() || output.mkdirs()) {
                        "Could not create runtime directory."
                    }
                    entry.isSymbolicLink -> {
                        val linkTarget = File(output.parentFile, entry.linkName).canonicalFile
                        check(linkTarget.path.startsWith(canonicalRoot.path + File.separator) || linkTarget == canonicalRoot) {
                            "Runtime archive contains an unsafe symbolic link."
                        }
                        output.parentFile?.mkdirs()
                        if (!output.exists()) {
                            try {
                                Os.symlink(entry.linkName, output.absolutePath)
                            } catch (_: Exception) {
                                // Broken/unavailable symlinks are detected when resolving bin/java below.
                            }
                        }
                    }
                    entry.isFile -> {
                        require(entry.size >= 0L && entry.size <= 512L * 1024L * 1024L) {
                            "Java runtime archive contains an invalid or oversized file."
                        }
                        totalExpandedBytes += entry.size
                        check(totalExpandedBytes <= 2L * 1024L * 1024L * 1024L) {
                            "Java runtime archive expands beyond the allowed size."
                        }
                        output.parentFile?.let { check(it.exists() || it.mkdirs()) { "Could not create runtime directory." } }
                        output.outputStream().buffered().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var remaining = entry.size
                            while (remaining > 0) {
                                DownloadCancellation.throwIfRequested()
                                val read = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (read < 0) error("Unexpected end of Java runtime archive.")
                                out.write(buffer, 0, read)
                                remaining -= read
                            }
                        }
                        val executableBits = entry.mode and 0x49
                        if (executableBits != 0) output.setExecutable(true, false)
                    }
                }
                count++
                if (count % 200 == 0) onStatus("Extracting Java 21 runtime… $count files")
                entry = tar.nextTarEntry
            }
        }
    }

    private fun findJava(root: File): File? {
        if (!root.exists()) return null
        val candidates = root.walkTopDown().filter { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
        return candidates.firstOrNull()
    }

    /**
     * Probe the extracted JRE with its own library paths set. Keep the diagnostic
     * output because Android linker/ABI failures otherwise look like a generic install failure.
     */
    private fun testJava(java: File): String? {
        return try {
            val javaHome = java.parentFile?.parentFile
                ?: return "Cannot determine JAVA_HOME from ${java.absolutePath}"
            val command = ProcessBuilder(java.absolutePath, "-version").redirectErrorStream(true)
            val environment = command.environment()
            environment["JAVA_HOME"] = javaHome.absolutePath
            val runtimeLibs = listOf(
                File(javaHome, "lib"),
                File(javaHome, "lib/server"),
                File(javaHome, "lib/aarch64"),
                File(javaHome, "lib/aarch64/server"),
                File(javaHome, "lib/arm"),
                File(javaHome, "lib/arm/server"),
                File(javaHome, "lib/x86_64"),
                File(javaHome, "lib/x86_64/server")
            ).filter { it.isDirectory }.map { it.absolutePath }
            environment["LD_LIBRARY_PATH"] = (runtimeLibs +
                listOfNotNull(environment["LD_LIBRARY_PATH"]?.takeIf { it.isNotBlank() }))
                .distinct().joinToString(File.pathSeparator)
            val temp = File(javaHome, "tmp").apply { mkdirs() }
            environment["TMPDIR"] = temp.absolutePath

            val process = command.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.take(1200)
            val exitCode = process.waitFor()
            if (exitCode == 0 && Regex("""version\s+"?21""").containsMatchIn(output)) {
                null
            } else {
                "java -version exited with code $exitCode: ${output.ifBlank { "(no output)" }}"
            }
        } catch (error: Exception) {
            "${error.javaClass.simpleName}: ${error.message ?: "no details"}"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
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
}
