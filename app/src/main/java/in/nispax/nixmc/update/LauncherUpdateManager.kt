package `in`.nispax.nixmc.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class LauncherUpdateRelease(
    val versionName: String,
    val tagName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releasePageUrl: String
)

/**
 * GitHub Releases updater. Never installs an APK until its GitHub SHA-256 digest,
 * application ID, and installed signing certificate all match.
 */
object LauncherUpdateManager {
    private const val RELEASE_API =
        "https://api.github.com/repos/Nispax-Official/nixmc-android-launcher/releases/latest"
    private const val RELEASE_DOWNLOAD_PREFIX =
        "/Nispax-Official/nixmc-android-launcher/releases/download/"
    private const val APK_ASSET_NAME = "nixmc-android.apk"
    private const val MAX_RELEASE_JSON_BYTES = 2 * 1024 * 1024
    private const val MAX_APK_BYTES = 350L * 1024L * 1024L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    suspend fun checkForUpdate(context: Context): LauncherUpdateRelease? =
        withContext(Dispatchers.IO) {
            val connection = URL(RELEASE_API).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "NixMC-Android-Updater")

            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    throw IOException(
                        "No stable GitHub Release is published yet. The updater is installed, " +
                            "but it needs a release containing the asset $APK_ASSET_NAME."
                    )
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IOException("GitHub update check failed (HTTP $code). Try again later.")
                }
                val releaseJson = JSONObject(
                    readCapped(connection.inputStream, MAX_RELEASE_JSON_BYTES).toString(Charsets.UTF_8)
                )
                if (releaseJson.optBoolean("draft", false) ||
                    releaseJson.optBoolean("prerelease", false)
                ) {
                    throw IOException("GitHub did not return a stable release.")
                }

                val tag = releaseJson.optString("tag_name", "").trim()
                if (tag.isBlank()) throw IOException("The GitHub release has no version tag.")
                val remoteVersion = tag.removePrefix("v").removePrefix("V").substringBefore('-')
                val currentVersion = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
                if (compareVersions(remoteVersion, currentVersion) <= 0) return@withContext null

                val assets = releaseJson.optJSONArray("assets")
                    ?: throw IOException("The GitHub release has no downloadable assets.")
                var apkAsset: JSONObject? = null
                for (index in 0 until assets.length()) {
                    val candidate = assets.optJSONObject(index) ?: continue
                    if (candidate.optString("name") == APK_ASSET_NAME) {
                        apkAsset = candidate
                        break
                    }
                }
                val asset = apkAsset
                    ?: throw IOException("Release $tag is missing the required $APK_ASSET_NAME asset.")

                val downloadUrl = asset.optString("browser_download_url", "")
                val parsedUrl = runCatching { URL(downloadUrl) }.getOrNull()
                if (parsedUrl == null ||
                    parsedUrl.protocol != "https" ||
                    !parsedUrl.host.equals("github.com", ignoreCase = true) ||
                    !parsedUrl.path.startsWith(RELEASE_DOWNLOAD_PREFIX)
                ) {
                    throw IOException("The release APK URL is not an approved HTTPS GitHub Release URL.")
                }

                val digestField = asset.optString("digest", "")
                val digest = digestField.removePrefix("sha256:").lowercase()
                if (!digest.matches(Regex("[0-9a-f]{64}"))) {
                    throw IOException(
                        "GitHub did not provide a valid SHA-256 digest for the release APK. " +
                            "For safety, NixMC will not download it."
                    )
                }

                val size = asset.optLong("size", -1L)
                if (size <= 0L || size > MAX_APK_BYTES) {
                    throw IOException("The release APK has an invalid or excessive file size.")
                }

                val pageUrl = releaseJson.optString(
                    "html_url",
                    "https://github.com/Nispax-Official/nixmc-android-launcher/releases"
                )
                LauncherUpdateRelease(
                    versionName = remoteVersion,
                    tagName = tag,
                    downloadUrl = downloadUrl,
                    sha256 = digest,
                    sizeBytes = size,
                    releasePageUrl = pageUrl
                )
            } finally {
                connection.disconnect()
            }
        }

    suspend fun downloadAndVerify(
        context: Context,
        release: LauncherUpdateRelease,
        onProgress: suspend (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "updates")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create NixMC's private update directory.")
        }
        val partial = File(directory, "nixmc-update.part.apk")
        val verified = File(directory, "nixmc-update.apk")
        partial.delete()

        val connection = URL(release.downloadUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "NixMC-Android-Updater")
        connection.setRequestProperty("Accept", "application/octet-stream")

        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("APK download failed (HTTP $code). Try again.")
            }
            val finalUrl = connection.url
            val finalHost = finalUrl.host.lowercase()
            val allowedHost = finalHost == "github.com" ||
                finalHost == "release-assets.githubusercontent.com" ||
                finalHost == "objects.githubusercontent.com"
            if (finalUrl.protocol != "https" || !allowedHost) {
                throw IOException("The APK download redirected to an untrusted host.")
            }

            val hasher = MessageDigest.getInstance("SHA-256")
            var bytesRead = 0L
            var lastProgress = -1
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytesRead += count.toLong()
                        if (bytesRead > MAX_APK_BYTES || bytesRead > release.sizeBytes) {
                            throw IOException("The downloaded APK exceeded its published size.")
                        }
                        hasher.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        val progress = ((bytesRead * 100L) / release.sizeBytes).toInt().coerceIn(0, 100)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            withContext(Dispatchers.Main) { onProgress(progress) }
                        }
                    }
                    output.fd.sync()
                }
            }

            if (bytesRead != release.sizeBytes) {
                throw IOException("The APK download is incomplete. Please retry.")
            }
            val actualDigest = toHex(hasher.digest())
            if (!MessageDigest.isEqual(
                    actualDigest.toByteArray(Charsets.US_ASCII),
                    release.sha256.toByteArray(Charsets.US_ASCII)
                )
            ) {
                throw IOException("APK SHA-256 verification failed. The file was rejected.")
            }

            val archiveInfo = getPackageInfo(context, partial)
                ?: throw IOException("The downloaded file is not a valid Android APK.")
            if (archiveInfo.packageName != context.packageName) {
                throw IOException("The APK package ID does not match NixMC.")
            }
            val archiveSigners = signerDigests(archiveInfo)
            val installedInfo = getPackageInfo(context, null)
                ?: throw IOException("Could not read the installed NixMC package identity.")
            val installedSigners = signerDigests(installedInfo)
            if (archiveSigners.isEmpty() || archiveSigners != installedSigners) {
                throw IOException(
                    "This APK was signed with a different certificate. It was rejected to protect " +
                        "your installation and game files."
                )
            }

            try {
                Files.move(
                    partial.toPath(),
                    verified.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partial.toPath(), verified.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            verified
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    /**
     * Opens Android's package installer after user-controlled unknown-source permission.
     * A successful return means the installer was opened, not that installation is complete.
     */
    fun openInstaller(context: Context, verifiedApk: File): String {
        if (!verifiedApk.isFile || verifiedApk.length() <= 0L) {
            throw IOException("The verified update file is no longer available. Download it again.")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(permissionIntent)
            return "Allow installs from NixMC in Android settings, then return here and tap Install update again."
        }

        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            verifiedApk
        )
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(contentUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(installIntent)
        return "Android's package installer opened. Confirm the update there to finish installation."
    }

    private fun readCapped(input: java.io.InputStream, limit: Int): ByteArray {
        input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw IOException("GitHub returned an oversized update response.")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val numberPattern = Regex("\\d+")
        val leftParts = numberPattern.findAll(left).map { it.value.toIntOrNull() ?: 0 }.toList()
        val rightParts = numberPattern.findAll(right).map { it.value.toIntOrNull() ?: 0 }.toList()
        if (leftParts.isEmpty()) throw IOException("GitHub returned an invalid version number.")
        if (rightParts.isEmpty()) return 1
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val a = leftParts.getOrElse(index) { 0 }
            val b = rightParts.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    private fun getPackageInfo(context: Context, archive: File?): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        return if (archive == null) {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, flags)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(archive.absolutePath, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        if (signers.isNullOrEmpty()) throw IOException("Could not verify the APK signing certificate.")
        return signers.map { toHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) append("%02x".format(byte.toInt() and 0xff))
    }
}
