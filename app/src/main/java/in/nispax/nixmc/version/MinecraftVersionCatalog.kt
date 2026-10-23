package `in`.nispax.nixmc.version

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MinecraftVersion(
    val id: String,
    val type: String,
    val releaseTime: String,
    val metadataUrl: String
)

/**
 * Reads the public version manifest published by Mojang.
 *
 * This is catalog discovery only. The version metadata, client jar, libraries,
 * and assets are downloaded by a separate installer milestone.
 */
object MinecraftVersionCatalog {
    private const val MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    suspend fun fetchLatestReleases(limit: Int = 20): List<MinecraftVersion> =
        withContext(Dispatchers.IO) {
            require(limit in 1..100) { "Limit must be between 1 and 100." }
            val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "NixMC-Android-Launcher/0.1.0")
            }

            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IllegalStateException("Mojang version catalog returned HTTP $status.")
                }

                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val versions = JSONObject(body).getJSONArray("versions")
                buildList {
                    for (index in 0 until versions.length()) {
                        val item = versions.getJSONObject(index)
                        if (item.optString("type") != "release") continue
                        val id = item.optString("id")
                        val metadataUrl = item.optString("url")
                        if (id.isBlank() || metadataUrl.isBlank()) continue
                        add(
                            MinecraftVersion(
                                id = id,
                                type = item.optString("type"),
                                releaseTime = item.optString("releaseTime"),
                                metadataUrl = metadataUrl
                            )
                        )
                        if (size >= limit) break
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
}
