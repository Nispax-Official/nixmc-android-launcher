package `in`.nispax.nixmc.install

import android.system.Os
import android.system.OsConstants
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class InstalledJavaRuntime(
    val home: File,
    val javaExecutable: File,
    val majorVersion: Int
)

/**
 * Downloads and extracts the Android-specific OpenJDK 21 runtime published for Amethyst.
 * It does not include the native Minecraft graphics bridge or custom LWJGL stack.
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

    suspend fun install(
        filesDir: File,
        onStatus: (String) -> Unit = {}
    ): InstalledJavaRuntime = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { assets.containsKey(it) }
            ?: error("No supported Android Java 21 runtime is available for this device.")
        if (abi == "x86") error("Java 21 runtime is not published for 32-bit x86 devices.")
        val asset = assets.getValue(abi)
        val fileName = "jre21-android-${asset.arch}.tar.xz"
        val archive = File(File(filesDir, "cache"), fileName)
        val runtimeRoot = File(filesDir, "minecraft/runtime/java-21")

        val existingJava = findJava(runtimeRoot)
        if (existingJava != null) {
            val smokeTest = testJava(existingJava)
            if (smokeTest) return@withContext InstalledJavaRuntime(runtimeRoot, existingJava, RUNTIME_VERSION)
        }

        onStatus("Downloading Android Java 21 runtime for ${asset.arch}…")
        check(archive.parentFile?.let { it.exists() || it.mkdirs() } == true) {
            "Could not create runtime download directory."
        }
        download(RELEASE_BASE + fileName, archive, onStatus)
        check(sha256(archive).equals(asset.sha256, ignoreCase = true)) {
            archive.delete()
            "Java runtime checksum failed. The archive was discarded."
        }

        onStatus("Extracting Java 21 runtime…")
        if (runtimeRoot.exists()) runtimeRoot.deleteRecursively()
        check(runtimeRoot.mkdirs()) { "Could not create Java runtime directory." }
        extractTarXz(archive, runtimeRoot, onStatus)
        archive.delete()

        val java = findJava(runtimeRoot)
            ?: error("Runtime archive extracted, but bin/java was not found.")
        java.setExecutable(true, true)
        val smokeTest = testJava(java)
        check(smokeTest) {
            "Java runtime was extracted but could not start on this device. The native launcher bridge is not integrated yet."
        }
        onStatus("Java 21 runtime installed and passed java -version.")
        InstalledJavaRuntime(runtimeRoot, java, RUNTIME_VERSION)
    }

    private fun download(url: String, target: File, onStatus: (String) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("User-Agent", "NixMC-Android-Launcher/0.3.0")
        }
        val temporary = File(target.parentFile, target.name + ".part")
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("Runtime download returned HTTP $code.")
            val length = connection.contentLengthLong
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var previous = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                        if (length > 0) {
                            val percent = (total * 100 / length).toInt()
                            if (percent != previous) {
                                previous = percent
                                onStatus("Downloading Java 21 runtime… $percent%")
                            }
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) { "Could not save Java runtime archive." }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTarXz(archive: File, destination: File, onStatus: (String) -> Unit) {
        val canonicalRoot = destination.canonicalFile
        TarArchiveInputStream(XZCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            var entry = tar.nextTarEntry
            var count = 0
            while (entry != null) {
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
                        output.parentFile?.mkdirs()
                        output.outputStream().buffered().use { out ->
                            tar.copyTo(out, 64 * 1024)
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

    private fun testJava(java: File): Boolean = try {
        val process = ProcessBuilder(java.absolutePath, "-version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() == 0 && output.contains("21")
    } catch (_: Exception) {
        false
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
