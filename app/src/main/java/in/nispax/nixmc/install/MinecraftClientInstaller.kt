package `in`.nispax.nixmc.install

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads Mojang's official 1.21.11 version metadata and client JAR.
 * This is a verified first install step, not a complete playable installation.
 */
object MinecraftClientInstaller {
    private const val VERSION = "1.21.11"
    private const val MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

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
        val client = metadata.getJSONObject("downloads").getJSONObject("client")
        val downloadUrl = client.getString("url")
        val expectedSha1 = client.getString("sha1").lowercase()
        val expectedSize = client.getLong("size")

        val versionDir = File(context.filesDir, "minecraft/versions/$VERSION")
        check(versionDir.exists() || versionDir.mkdirs()) {
            "Could not create the Minecraft version directory."
        }
        val metadataFile = File(versionDir, "$VERSION.json")
        metadataFile.writeText(metadata.toString())

        val target = File(versionDir, "$VERSION.jar")
        if (target.isFile && target.length() == expectedSize && sha1(target) == expectedSha1) {
            onStatus("Minecraft $VERSION client is already downloaded and verified.")
            return@withContext target
        }

        val temporary = File(versionDir, "$VERSION.jar.part")
        onStatus("Downloading Minecraft $VERSION client…")
        try {
            downloadToFile(downloadUrl, temporary, expectedSize, onStatus)
            check(temporary.length() == expectedSize) {
                "Download size mismatch. Please retry the download."
            }
            check(sha1(temporary) == expectedSha1) {
                "SHA-1 verification failed. The downloaded file was discarded; please retry."
            }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) { "Could not finalize the downloaded client JAR." }
            onStatus("Client downloaded; verifying SHA-1…")
            target
        } catch (error: Exception) {
            temporary.delete()
            throw error
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

    private fun downloadToFile(
        url: String,
        target: File,
        expectedSize: Long,
        onStatus: (String) -> Unit
    ) {
        val connection = openConnection(url)
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("Client download returned HTTP $status.")
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    var total = 0L
                    var lastPercent = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                        val percent = if (expectedSize > 0) ((total * 100) / expectedSize).toInt() else -1
                        if (percent != lastPercent && percent in 0..100) {
                            lastPercent = percent
                            onStatus("Downloading Minecraft $VERSION client… $percent%")
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "NixMC-Android-Launcher/0.2.0")
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
