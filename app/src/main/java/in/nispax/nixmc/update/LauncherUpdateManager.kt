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
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

data class LauncherUpdateRelease(
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val buildNumber: Long,
    val headSha: String,
    val buildPageUrl: String
)

/**
 * In-house updater backed by this repository's latest successful GitHub Actions build.
 * It requires no Git tags or release creation. Artifact digest, APK package ID, CI build
 * number, and installed signing certificate are verified before Android's installer opens.
 */
object LauncherUpdateManager {
    private const val REPO = "Nispax-Official/nixmc-android-launcher"
    private const val UPDATE_BRANCH = "engine/launch-core-phase-1"
    private const val ANDROID_WORKFLOW_PATH = ".github/workflows/android.yml"
    private const val RUNS_API =
        "https://api.github.com/repos/$REPO/actions/runs?branch=$UPDATE_BRANCH&per_page=100"
    private const val MAX_JSON_BYTES = 4 * 1024 * 1024
    private const val MAX_ARTIFACT_BYTES = 500L * 1024L * 1024L
    private const val MAX_APK_BYTES = 350L * 1024L * 1024L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    suspend fun checkForUpdate(context: Context): LauncherUpdateRelease? =
        withContext(Dispatchers.IO) {
            val runs = getJson(RUNS_API).optJSONArray("workflow_runs")
                ?: throw IOException("GitHub returned no workflow history.")
            val androidRuns = (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }
                .filter {
                    it.optString("head_branch") == UPDATE_BRANCH &&
                        it.optString("path") == ANDROID_WORKFLOW_PATH
                }
                .sortedByDescending { it.optString("created_at") }

            val latest = androidRuns.firstOrNull()
                ?: throw IOException("No NixMC Android CI builds were found on GitHub.")
            val latestSha = latest.optString("head_sha", "")
            if (latestSha.isBlank()) throw IOException("The latest CI build has no source revision.")

            // Do not fall back to an older source revision if the newest source has not built cleanly.
            val matchingSuccess = androidRuns.firstOrNull {
                it.optString("head_sha") == latestSha &&
                    it.optString("status") == "completed" &&
                    it.optString("conclusion") == "success"
            }
            if (matchingSuccess == null) {
                val state = latest.optString("status")
                val result = latest.optString("conclusion")
                val message = if (state != "completed") {
                    "The latest NixMC build is still running on GitHub. Try again after CI finishes."
                } else {
                    "The latest NixMC source revision did not pass CI ($result). For safety, no older APK is offered."
                }
                throw IOException(message)
            }

            val buildNumber = matchingSuccess.optLong("run_number", -1L)
            val currentInfo = getPackageInfo(context, null)
                ?: throw IOException("Could not read the installed NixMC version.")
            if (buildNumber <= packageVersionCode(currentInfo)) return@withContext null
            if (buildNumber <= 0L || buildNumber > Int.MAX_VALUE) {
                throw IOException("GitHub returned an invalid CI build number.")
            }

            val runId = matchingSuccess.optLong("id", -1L)
            if (runId <= 0L) throw IOException("The successful CI run has no ID.")
            val artifactsUrl =
                "https://api.github.com/repos/$REPO/actions/runs/$runId/artifacts"
            val artifacts = getJson(artifactsUrl).optJSONArray("artifacts")
                ?: throw IOException("The successful build has no downloadable artifact list.")

            var selected: JSONObject? = null
            for (index in 0 until artifacts.length()) {
                val item = artifacts.optJSONObject(index) ?: continue
                if (item.optString("name") == "nixmc-android-debug-apk" &&
                    !item.optBoolean("expired", true)
                ) {
                    selected = item
                    break
                }
            }
            val artifact = selected
                ?: throw IOException("The latest successful CI APK artifact is missing or expired.")

            val downloadUrl = artifact.optString("archive_download_url", "")
            val parsedUrl = runCatching { URL(downloadUrl) }.getOrNull()
            if (parsedUrl == null ||
                parsedUrl.protocol != "https" ||
                !parsedUrl.host.equals("api.github.com", ignoreCase = true) ||
                !parsedUrl.path.startsWith("/repos/$REPO/actions/artifacts/") ||
                !parsedUrl.path.endsWith("/zip")
            ) {
                throw IOException("GitHub returned an untrusted build-artifact URL.")
            }

            val digestField = artifact.optString("digest", "")
            val digest = digestField.removePrefix("sha256:").lowercase()
            if (!digest.matches(Regex("[0-9a-f]{64}"))) {
                throw IOException("GitHub did not provide a SHA-256 artifact digest; refusing the update.")
            }
            val size = artifact.optLong("size_in_bytes", -1L)
            if (size <= 0L || size > MAX_ARTIFACT_BYTES) {
                throw IOException("The GitHub build artifact has an invalid or excessive size.")
            }

            val buildUrl = matchingSuccess.optString(
                "html_url",
                "https://github.com/$REPO/actions"
            )
            LauncherUpdateRelease(
                versionName = "CI build #$buildNumber",
                downloadUrl = downloadUrl,
                sha256 = digest,
                sizeBytes = size,
                buildNumber = buildNumber,
                headSha = latestSha,
                buildPageUrl = buildUrl
            )
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
        val artifactTemp = File(directory, "nixmc-update.part.zip")
        val apkTemp = File(directory, "nixmc-update.part.apk")
        val verifiedApk = File(directory, "nixmc-update.apk")
        artifactTemp.delete()
        apkTemp.delete()

        val connection = URL(release.downloadUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "NixMC-Android-Updater")
        connection.setRequestProperty("Accept", "application/vnd.github+json")

        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub artifact download failed (HTTP $code). Try again.")
            }
            val finalUrl = connection.url
            if (finalUrl.protocol != "https") {
                throw IOException("The build artifact redirected to a non-HTTPS address.")
            }

            val hasher = MessageDigest.getInstance("SHA-256")
            var bytesRead = 0L
            var lastProgress = -1
            connection.inputStream.use { input ->
                FileOutputStream(artifactTemp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytesRead += count.toLong()
                        if (bytesRead > MAX_ARTIFACT_BYTES || bytesRead > release.sizeBytes) {
                            throw IOException("The build artifact exceeded GitHub's published size.")
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
                throw IOException("The build artifact download is incomplete. Please retry.")
            }
            val actualDigest = toHex(hasher.digest())
            if (!MessageDigest.isEqual(
                    actualDigest.toByteArray(Charsets.US_ASCII),
                    release.sha256.toByteArray(Charsets.US_ASCII)
                )
            ) {
                throw IOException("GitHub artifact SHA-256 verification failed. The file was rejected.")
            }

            extractApkSafely(artifactTemp, apkTemp)
            val archiveInfo = getPackageInfo(context, apkTemp)
                ?: throw IOException("The downloaded build does not contain a valid Android APK.")
            if (archiveInfo.packageName != context.packageName) {
                throw IOException("The APK package ID does not match NixMC.")
            }
            val apkBuildNumber = packageVersionCode(archiveInfo)
            if (apkBuildNumber != release.buildNumber) {
                throw IOException("The APK build number does not match its GitHub CI run.")
            }

            val installedInfo = getPackageInfo(context, null)
                ?: throw IOException("Could not read the installed NixMC package identity.")
            if (apkBuildNumber <= packageVersionCode(installedInfo)) {
                throw IOException("This APK is not newer than the installed build.")
            }
            val archiveSigners = signerDigests(archiveInfo)
            val installedSigners = signerDigests(installedInfo)
            if (archiveSigners.isEmpty() || archiveSigners != installedSigners) {
                throw IOException(
                    "This APK uses a different signing certificate. It was rejected to protect " +
                        "your installation and game files."
                )
            }

            try {
                Files.move(
                    apkTemp.toPath(),
                    verifiedApk.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(apkTemp.toPath(), verifiedApk.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            verifiedApk
        } finally {
            connection.disconnect()
            artifactTemp.delete()
            apkTemp.delete()
        }
    }

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

    private fun getJson(urlString: String): JSONObject {
        val url = URL(urlString)
        if (url.protocol != "https" || !url.host.equals("api.github.com", ignoreCase = true)) {
            throw IOException("NixMC refused a non-GitHub update endpoint.")
        }
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "NixMC-Android-Updater")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub update check failed (HTTP $code). Try again later.")
            }
            return JSONObject(readCapped(connection.inputStream, MAX_JSON_BYTES).toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private fun extractApkSafely(zipFile: File, outputApk: File) {
        var found = false
        var entryCount = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > 100) throw IOException("The build archive has too many entries.")
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == "app-debug.apk") {
                    if (found) throw IOException("The build archive contains duplicate APK entries.")
                    found = true
                    var extracted = 0L
                    FileOutputStream(outputApk).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extracted += count.toLong()
                            if (extracted > MAX_APK_BYTES) {
                                throw IOException("The APK inside the build archive is too large.")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                zip.closeEntry()
            }
        }
        if (!found || !outputApk.isFile || outputApk.length() <= 0L) {
            throw IOException("The GitHub build archive does not contain app-debug.apk.")
        }
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

    private fun packageVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
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

    private fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) append("%02x".format(byte.toInt() and 0xff))
    }
}
