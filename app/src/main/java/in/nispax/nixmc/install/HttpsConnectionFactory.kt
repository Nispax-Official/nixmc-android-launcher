package `in`.nispax.nixmc.install

import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens HTTPS-only connections and validates redirects before following them.
 * Remote servers cannot silently downgrade artifact transfers to HTTP.
 */
object HttpsConnectionFactory {
    fun open(url: String, userAgent: String = "NixMC-Android-Launcher/0.5.0"): HttpURLConnection {
        var current = URL(url)
        repeat(6) {
            require(current.protocol.equals("https", ignoreCase = true) && current.userInfo == null) {
                "Refusing a non-HTTPS or credential-bearing download URL."
            }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 90_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json, application/octet-stream")
            }
            val status = try {
                connection.responseCode
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank()) { "HTTPS server returned a redirect without a Location header." }
                val redirected = URL(current, location)
                require(redirected.protocol.equals("https", ignoreCase = true) && redirected.userInfo == null) {
                    "Refusing an insecure download redirect."
                }
                current = redirected
            } else {
                return connection
            }
        }
        error("Too many HTTPS redirects while downloading an artifact.")
    }
}
