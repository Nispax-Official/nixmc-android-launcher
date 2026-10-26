package `in`.nispax.nixmc.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import `in`.nispax.nixmc.install.MinecraftLaunchPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class EngineBundleImportResult(
    val bridgeRoot: File,
    val upstreamVersion: String,
    val lwjglVersion: String,
    val selectedRendererId: String,
    val fileCount: Int,
    val report: EngineBridgeReport
)

/**
 * Imports a compatible bridge from an already-installed Amethyst APK, initiated
 * by the user. This does not bundle or silently download another launcher's
 * native binaries. It copies only the files needed by NixMC, retains upstream
 * license files and writes a source/version provenance record.
 */
object AmethystEngineBundleInstaller {
    const val PACKAGE_NAME = "org.angelauramc.amethyst"
    const val SUPPORTED_VERSION = "1.1.7"
    const val DEFAULT_RENDERER_ID = "opengles2"

    private const val MAX_SINGLE_FILE_BYTES = 100L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 350L * 1024L * 1024L
    private val lwjglArtifact = Regex("""(?:^|/)org/lwjgl/lwjgl/([0-9.]+)/lwjgl-([0-9.]+)\.jar$""")

    suspend fun importInstalled(
        context: Context,
        gameDirectory: File,
        plan: MinecraftLaunchPlan,
        androidAbi: String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        javaMajorVersion: Int,
        selectedRendererId: String = DEFAULT_RENDERER_ID,
        onStatus: (String) -> Unit = {}
    ): EngineBundleImportResult = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val appInfo = try {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(PACKAGE_NAME, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            error("Amethyst is not installed. Install the official Amethyst 1.1.7 release, then retry.")
        }
        val packageInfo = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(PACKAGE_NAME, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            error("Could not read the installed Amethyst package version.")
        }
        val version = packageInfo.versionName.orEmpty()
        require(version.contains(SUPPORTED_VERSION)) {
            "NixMC bridge importer is pinned to Amethyst $SUPPORTED_VERSION; found '$version'."
        }
        val apkPaths = (listOf(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty()).distinct()
        importFromApkFiles(
            apkPaths = apkPaths,
            versionName = version,
            gameDirectory = gameDirectory,
            plan = plan,
            androidAbi = androidAbi,
            javaMajorVersion = javaMajorVersion,
            selectedRendererId = selectedRendererId,
            onStatus = onStatus
        )
    }

    internal fun importFromApkFiles(
        apkPaths: List<String>,
        versionName: String,
        gameDirectory: File,
        plan: MinecraftLaunchPlan,
        androidAbi: String,
        javaMajorVersion: Int,
        selectedRendererId: String = DEFAULT_RENDERER_ID,
        onStatus: (String) -> Unit = {}
    ): EngineBundleImportResult {
        require(versionName.contains(SUPPORTED_VERSION)) {
            "NixMC bridge importer is pinned to Amethyst $SUPPORTED_VERSION; found '$versionName'."
        }
        require(androidAbi in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
            "Unsupported Android ABI for engine import: $androidAbi"
        }
        require(apkPaths.isNotEmpty() && apkPaths.all { File(it).isFile }) {
            "The installed engine APK files could not be read."
        }

        val lwjglVersions = plan.classpath.mapNotNull { file ->
            lwjglArtifact.find(file.path.replace('\\', '/'))?.groupValues?.get(1)
        }.distinct()
        require(lwjglVersions.size == 1) {
            "Expected one Minecraft LWJGL version in the installed libraries, found: ${lwjglVersions.joinToString().ifBlank { "(none)" }}."
        }
        val lwjglVersion = lwjglVersions.single()
        require(lwjglVersion in setOf("3.3.3", "3.4.1")) {
            "Installed Amethyst bundle does not provide a supported patched LWJGL version for Minecraft: $lwjglVersion."
        }

        val destination = File(gameDirectory, "engine/bridge").absoluteFile
        val parent = destination.parentFile ?: error("Cannot determine engine installation directory.")
        check(parent.exists() || parent.mkdirs()) { "Could not create engine installation directory." }
        val staging = File(parent, "bridge.installing")
        val backup = File(parent, "bridge.previous")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not create engine staging directory." }

        val writtenFiles = linkedMapOf<String, String>()
        var totalBytes = 0L

        fun safeTarget(relative: String): File {
            require(isSafeRelative(relative)) { "Unsafe path inside Amethyst APK: $relative" }
            val target = File(staging, relative).canonicalFile
            check(target.path.startsWith(staging.canonicalPath + File.separator)) {
                "Imported engine file escaped its staging directory."
            }
            return target
        }

        fun copyEntry(zip: ZipFile, entry: ZipEntry, relative: String) {
            val target = safeTarget(relative)
            val previousHash = writtenFiles[relative]
            if (previousHash != null) {
                val duplicateHash = sha256Entry(zip, entry)
                check(previousHash == duplicateHash) { "Conflicting APK split files: $relative" }
                return
            }
            val declaredSize = entry.size
            require(declaredSize <= MAX_SINGLE_FILE_BYTES) { "Engine asset is too large: $relative" }
            target.parentFile?.let { check(it.exists() || it.mkdirs()) { "Could not create engine asset directory." } }
            var copied = 0L
            zip.getInputStream(entry).use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        totalBytes += count
                        check(copied <= MAX_SINGLE_FILE_BYTES) { "Engine asset exceeded the per-file size limit: $relative" }
                        check(totalBytes <= MAX_TOTAL_BYTES) { "Engine bundle exceeded the import size limit." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (declaredSize >= 0) check(copied == declaredSize) { "Truncated engine asset: $relative" }
            writtenFiles[relative] = sha256(target)
        }

        try {
            val lwjglPrefix = "assets/components/lwjgl3/$lwjglVersion/"
            val cacioPrefix = "assets/components/caciocavallo17/"
            val licensePrefix = "assets/licenses/"
            val nativePrefix = "lib/$androidAbi/"
            val zips = apkPaths.map { ZipFile(it) }
            try {
                onStatus("Inspecting Amethyst $versionName for $androidAbi engine files…")
                for (zip in zips) {
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val name = entry.name
                        when {
                            name.startsWith(nativePrefix) &&
                                name.removePrefix(nativePrefix).matches(Regex("[A-Za-z0-9_.+-]+\\.so")) -> {
                                val filename = name.removePrefix(nativePrefix)
                                copyEntry(zip, entry, "lib/$androidAbi/$filename")
                            }
                            name.startsWith(lwjglPrefix) && name.endsWith(".jar") -> {
                                val filename = name.removePrefix(lwjglPrefix)
                                if (isSafeRelative(filename) && '/' !in filename) {
                                    copyEntry(zip, entry, "classpath/lwjgl3/$lwjglVersion/$filename")
                                }
                            }
                            name.startsWith(cacioPrefix) && name.endsWith(".jar") -> {
                                val filename = name.removePrefix(cacioPrefix)
                                if (isSafeRelative(filename) && '/' !in filename) {
                                    copyEntry(zip, entry, "classpath/caciocavallo17/$filename")
                                }
                            }
                            name.startsWith(licensePrefix) -> {
                                val relative = name.removePrefix("assets/")
                                if (isSafeRelative(relative)) copyEntry(zip, entry, relative)
                            }
                        }
                    }
                }
            } finally {
                zips.forEach { runCatching { it.close() } }
            }

            val files = writtenFiles.keys.toList()
            val soFiles = files.filter { it.startsWith("lib/$androidAbi/") && it.endsWith(".so") }
            val lwjglJars = files.filter { it.startsWith("classpath/lwjgl3/$lwjglVersion/") && it.endsWith(".jar") }
            val requiredJars = listOf("lwjgl-$lwjglVersion-merged-modules.jar", "lwjgl.jar")
            for (jar in requiredJars) {
                check(lwjglJars.any { it.endsWith("/$jar") }) {
                    "Amethyst $SUPPORTED_VERSION is missing patched LWJGL component $jar for Minecraft LWJGL $lwjglVersion."
                }
            }
            for (required in listOf("libc++_shared.so", "libpojavexec.so", "libng_gl4es.so")) {
                check(soFiles.any { it.endsWith("/$required") }) {
                    "Amethyst $SUPPORTED_VERSION does not contain required native engine file $required for $androidAbi."
                }
            }

            val nativePriority = listOf(
                "libc++_shared.so",
                "libbytehook.so",
                "libandroidnsbypass.so",
                "liblinkerhook.so",
                "libpojavexec.so",
                "libexithook.so",
                "libpojavexec_awt.so"
            )
            val nativeLoadOrder = nativePriority.mapNotNull { name ->
                soFiles.firstOrNull { it.endsWith("/$name") }
            }
            check(nativeLoadOrder.any { it.endsWith("/libpojavexec.so") }) {
                "The JLI native entry library is not available for $androidAbi."
            }

            val rendererDefinitions = mutableListOf<JSONObject>()
            if (soFiles.any { it.endsWith("/libng_gl4es.so") }) {
                rendererDefinitions += JSONObject()
                    .put("id", "opengles2")
                    .put("name", "Compatibility (GL4ES)")
                    .put("library", soFiles.first { it.endsWith("/libng_gl4es.so") })
                    .put("environment", JSONObject()
                        .put("AMETHYST_RENDERER", "opengles2")
                        .put("LIBGL_ES", "2"))
            }
            if (soFiles.any { it.endsWith("/libmobileglues.so") }) {
                rendererDefinitions += JSONObject()
                    .put("id", "opengles_mobileglues")
                    .put("name", "MobileGlues (experimental)")
                    .put("library", soFiles.first { it.endsWith("/libmobileglues.so") })
                    .put("environment", JSONObject()
                        .put("AMETHYST_RENDERER", "opengles_mobileglues")
                        .put("LIBGL_ES", "3")
                        .put("MG_DIR_PATH", File(staging, "MobileGlues").absolutePath)
                        .put("POJAVEXEC_EGL", "libmobileglues.so"))
            }
            check(rendererDefinitions.any { it.optString("id") == selectedRendererId }) {
                "Selected renderer $selectedRendererId is not present in the imported Amethyst ABI bundle."
            }

            val classpathOrder = lwjglJars.sortedWith(
                compareBy<String> { if (it.endsWith("-merged-modules.jar")) 0 else 1 }
                    .thenBy { it }
            ) + files.filter { it.startsWith("classpath/caciocavallo17/") && it.endsWith(".jar") }.sorted()

            val fileArray = JSONArray()
            files.sorted().forEach { relative ->
                fileArray.put(JSONObject().put("path", relative).put("sha256", writtenFiles.getValue(relative)))
            }
            val manifest = JSONObject()
                .put("schemaVersion", 1)
                .put("bridgeVersion", "amethyst-$versionName")
                .put("androidAbis", JSONArray().put(androidAbi))
                .put("minimumJavaMajor", 21)
                .put("maximumJavaMajor", 21)
                .put("launchEntry", JSONObject()
                    .put("class", "com.oracle.dalvik.VMLauncher")
                    .put("method", "launchJVM")
                    .put("library", nativeLoadOrder.first { it.endsWith("/libpojavexec.so") }))
                .put("nativeLoadOrder", JSONArray().also { array -> nativeLoadOrder.forEach(array::put) })
                .put("classpathEntries", JSONArray().also { array -> classpathOrder.forEach(array::put) })
                .put("files", fileArray)
                .put("renderers", JSONArray().also { array -> rendererDefinitions.forEach(array::put) })
            File(staging, EngineBridgeVerifier.MANIFEST_FILE_NAME).writeText(manifest.toString(2))

            val sourceNotice = """
                NixMC imported this engine bundle at the user's request from an installed Amethyst Android application.
                Upstream project: https://github.com/AngelAuraMC/Amethyst-Android
                Required version: $SUPPORTED_VERSION
                Installed version: $versionName
                Native ABI: $androidAbi
                Patched LWJGL version: $lwjglVersion
                This is an on-device copy, not a redistributable NixMC bundle. Refer to licenses/ and the upstream project
                for component licenses, source code, and notices. See: https://github.com/AngelAuraMC/Amethyst-Android/tree/1.1.7
            """.trimIndent()
            File(staging, "UPSTREAM-NOTICE.txt").writeText(sourceNotice)

            onStatus("Verifying extracted engine files and native hashes…")
            val report = EngineBridgeVerifier.inspect(
                bridgeRoot = staging,
                androidAbi = androidAbi,
                javaMajorVersion = javaMajorVersion,
                selectedRendererId = selectedRendererId
            )
            check(report.isReady) {
                "Imported engine failed verification: ${report.blockers.take(6).joinToString()}"
            }

            backup.deleteRecursively()
            var oldBridgeMoved = false
            if (destination.exists()) {
                check(destination.renameTo(backup)) { "Could not move the previous engine bundle aside." }
                oldBridgeMoved = true
            }
            if (!staging.renameTo(destination)) {
                if (oldBridgeMoved) backup.renameTo(destination)
                error("Could not activate imported engine bundle.")
            }
            backup.deleteRecursively()

            // Re-inspect using the final canonical location to catch unexpected filesystem behavior.
            val finalReport = EngineBridgeVerifier.inspect(
                bridgeRoot = destination,
                androidAbi = androidAbi,
                javaMajorVersion = javaMajorVersion,
                selectedRendererId = selectedRendererId
            )
            check(finalReport.isReady) {
                "Engine bundle moved into place but did not pass final verification: ${finalReport.blockers.joinToString()}"
            }
            EngineBundleImportResult(
                bridgeRoot = destination,
                upstreamVersion = versionName,
                lwjglVersion = lwjglVersion,
                selectedRendererId = selectedRendererId,
                fileCount = writtenFiles.size,
                report = finalReport
            )
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun isSafeRelative(path: String): Boolean =
        path.isNotBlank() &&
            !File(path).isAbsolute &&
            !path.startsWith("/") &&
            !path.contains(92.toChar()) &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

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

    private fun sha256Entry(zip: ZipFile, entry: ZipEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.getInputStream(entry).buffered().use { input ->
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
