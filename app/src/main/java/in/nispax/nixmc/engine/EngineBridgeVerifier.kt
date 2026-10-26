package `in`.nispax.nixmc.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class EngineBridgeManifest(
    val schemaVersion: Int,
    val bridgeVersion: String,
    val androidAbis: Set<String>,
    val minimumJavaMajor: Int,
    val maximumJavaMajor: Int,
    val launchEntryClass: String,
    val launchEntryMethod: String,
    val launchEntryLibrary: String,
    val nativeLoadOrder: List<String>,
    val files: List<BridgeFile>,
    val classpathEntries: List<String>,
    val renderers: List<BridgeRenderer>
)

data class BridgeFile(
    val relativePath: String,
    val sha256: String
)

data class BridgeRenderer(
    val id: String,
    val displayName: String,
    val library: String,
    val environment: Map<String, String>
)

data class EngineBridgeReport(
    val bridgeRoot: File,
    val androidAbi: String,
    val bridgeVersion: String?,
    val availableRenderers: List<BridgeRenderer>,
    val blockers: List<String>,
    val warnings: List<String>
) {
    val isReady: Boolean get() = blockers.isEmpty()
}

/**
 * Validates an installed Android game-engine bridge before any JVM is started.
 *
 * manifest.json describes ABI-specific native bridge files, Java compatibility,
 * entry point and installed renderers. Checksums prove local file integrity
 * against the manifest; they do not prove who published the manifest. The
 * installer must pin the manifest to a trusted release/source revision.
 */
object EngineBridgeVerifier {
    const val MANIFEST_FILE_NAME = "manifest.json"
    private const val SUPPORTED_SCHEMA_VERSION = 1
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")

    fun inspect(
        bridgeRoot: File,
        androidAbi: String,
        javaMajorVersion: Int?,
        selectedRendererId: String? = null
    ): EngineBridgeReport {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val canonicalRoot = runCatching { bridgeRoot.canonicalFile }.getOrElse {
            blockers += "Cannot resolve the engine bridge directory: ${it.message ?: "unknown error"}"
            return report(bridgeRoot, androidAbi, null, emptyList(), blockers, warnings)
        }

        val manifestFile = runCatching { File(canonicalRoot, MANIFEST_FILE_NAME).canonicalFile }
            .getOrElse {
                blockers += "Cannot resolve the engine bridge manifest path."
                return report(canonicalRoot, androidAbi, null, emptyList(), blockers, warnings)
            }
        if (!isInside(canonicalRoot, manifestFile)) {
            blockers += "Engine bridge manifest path escapes the bridge directory."
            return report(canonicalRoot, androidAbi, null, emptyList(), blockers, warnings)
        }
        if (!manifestFile.isFile) {
            blockers += "Android graphics/input bridge is not installed (manifest.json is missing)."
            if (javaMajorVersion == null) blockers += "Java runtime has not passed its startup check."
            return report(canonicalRoot, androidAbi, null, emptyList(), blockers, warnings)
        }

        val manifest = try {
            parseManifest(JSONObject(manifestFile.readText()))
        } catch (error: Exception) {
            blockers += "Engine bridge manifest is invalid: ${error.message ?: error.javaClass.simpleName}"
            if (javaMajorVersion == null) blockers += "Java runtime has not passed its startup check."
            return report(canonicalRoot, androidAbi, null, emptyList(), blockers, warnings)
        }

        if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            blockers += "Unsupported bridge manifest schema ${manifest.schemaVersion}; expected $SUPPORTED_SCHEMA_VERSION."
        }
        if (manifest.bridgeVersion.isBlank()) blockers += "Bridge version is missing."
        if (manifest.androidAbis.isEmpty()) blockers += "Manifest does not declare any Android ABI."
        if (androidAbi.isBlank()) {
            blockers += "Could not determine the active Android ABI."
        } else if (androidAbi !in manifest.androidAbis) {
            blockers += "Bridge does not support device ABI $androidAbi."
        }
        if (manifest.minimumJavaMajor < 1 ||
            manifest.maximumJavaMajor < manifest.minimumJavaMajor
        ) {
            blockers += "Bridge declares an invalid Java compatibility range."
        }
        if (javaMajorVersion == null) {
            blockers += "Java runtime has not passed its startup check."
        } else if (javaMajorVersion !in manifest.minimumJavaMajor..manifest.maximumJavaMajor) {
            blockers += "Bridge supports Java ${manifest.minimumJavaMajor}–${manifest.maximumJavaMajor}; installed runtime is Java $javaMajorVersion."
        }
        if (manifest.launchEntryClass != "com.oracle.dalvik.VMLauncher" ||
            manifest.launchEntryMethod != "launchJVM"
        ) {
            blockers += "Unsupported bridge entry point; this build expects com.oracle.dalvik.VMLauncher.launchJVM."
        }
        if (manifest.launchEntryLibrary.isBlank()) {
            blockers += "Bridge launch entry library is missing."
        }
        if (manifest.files.isEmpty()) blockers += "Bridge manifest declares no files."

        val declaredFilePaths = manifest.files.map { it.relativePath }.toSet()
        if (manifest.launchEntryLibrary !in declaredFilePaths) {
            blockers += "The native launch entry library is not listed in files."
        }
        if (manifest.nativeLoadOrder.isEmpty()) {
            blockers += "Bridge manifest does not declare a native library load order."
        }
        if (manifest.launchEntryLibrary !in manifest.nativeLoadOrder) {
            blockers += "Native load order does not include the launch entry library."
        }
        if (manifest.nativeLoadOrder.distinct().size != manifest.nativeLoadOrder.size) {
            blockers += "Native load order contains duplicate paths."
        }
        for (path in manifest.nativeLoadOrder) {
            if (!isSafeRelativePath(path)) {
                blockers += "Unsafe native load-order path: $path"
            } else if (path !in declaredFilePaths) {
                blockers += "Native load-order entry is not verified by files: $path"
            } else if (!path.endsWith(".so", ignoreCase = true)) {
                blockers += "Native load-order entry is not a shared library: $path"
            }
        }
        if (manifest.nativeLoadOrder.isEmpty()) blockers += "Bridge manifest declares no native libraries to load."

        if (manifest.classpathEntries.isEmpty()) blockers += "Bridge manifest does not provide custom LWJGL classpath entries."
        if (manifest.classpathEntries.distinct().size != manifest.classpathEntries.size) {
            blockers += "Bridge classpath contains duplicate entries."
        }
        for (path in manifest.classpathEntries) {
            if (!isSafeRelativePath(path)) {
                blockers += "Unsafe bridge classpath path: $path"
            } else if (path !in declaredFilePaths) {
                blockers += "Bridge classpath entry is not verified by files: $path"
            } else if (!path.endsWith(".jar", ignoreCase = true)) {
                blockers += "Bridge classpath entry is not a JAR: $path"
            }
        }

        for (bridgeFile in manifest.files) {
            val relative = bridgeFile.relativePath
            if (!isSafeRelativePath(relative)) {
                blockers += "Unsafe native bridge file path: $relative"
                continue
            }
            val target = runCatching { File(canonicalRoot, relative).canonicalFile }.getOrNull()
            if (target == null || !isInside(canonicalRoot, target)) {
                blockers += "Native bridge file path escapes the bridge directory: $relative"
                continue
            }
            if (!sha256Pattern.matches(bridgeFile.sha256)) {
                blockers += "Native bridge file has an invalid SHA-256 entry: $relative"
                continue
            }
            if (!target.isFile) {
                blockers += "Required native bridge file is missing: $relative"
                continue
            }
            val actual = runCatching { sha256(target) }.getOrNull()
            if (actual == null) {
                blockers += "Could not verify native bridge file $relative: read failed"
                continue
            }
            if (!actual.equals(bridgeFile.sha256, ignoreCase = true)) {
                blockers += "Native bridge checksum mismatch: $relative"
            }
        }

        if (manifest.renderers.isEmpty()) {
            blockers += "Bridge does not provide any renderer backend."
        }
        if (manifest.renderers.any { it.id.isBlank() || it.displayName.isBlank() }) {
            blockers += "Bridge manifest contains an unnamed renderer."
        }
        if (manifest.renderers.map { it.id }.distinct().size != manifest.renderers.size) {
            blockers += "Bridge manifest contains duplicate renderer IDs."
        }
        for (renderer in manifest.renderers) {
            if (!isSafeRelativePath(renderer.library) || !renderer.library.endsWith(".so", ignoreCase = true)) {
                blockers += "Renderer ${renderer.id} has an unsafe or invalid library path."
            } else if (renderer.library !in declaredFilePaths) {
                blockers += "Renderer ${renderer.id} library is not verified by the bridge manifest."
            }
            renderer.environment.forEach { (key, value) ->
                if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                    blockers += "Renderer ${renderer.id} contains an invalid environment key."
                }
                if (value.contains(0.toChar())) {
                    blockers += "Renderer ${renderer.id} contains a NUL in an environment value."
                }
            }
        }
        if (selectedRendererId != null && manifest.renderers.none { it.id == selectedRendererId }) {
            blockers += "Selected renderer '$selectedRendererId' is not provided by this bridge."
        }
        warnings += "Manifest checksums validate local files only; install the manifest from a trusted, pinned bridge release."
        return report(canonicalRoot, androidAbi, manifest.bridgeVersion, manifest.renderers, blockers, warnings)
    }

    fun readManifest(bridgeRoot: File): EngineBridgeManifest {
        val root = bridgeRoot.canonicalFile
        val file = File(root, MANIFEST_FILE_NAME).canonicalFile
        require(isInside(root, file) && file.isFile) { "Engine bridge manifest is missing or escapes its directory." }
        return parseManifest(JSONObject(file.readText()))
    }

    private fun parseManifest(json: JSONObject): EngineBridgeManifest {
        val abis = json.optJSONArray("androidAbis") ?: JSONArray()
        val files = json.optJSONArray("files") ?: JSONArray()
        val renderers = json.optJSONArray("renderers") ?: JSONArray()
        val nativeFiles = (0 until files.length()).map { index ->
            val item = files.optJSONObject(index)
            BridgeFile(
                relativePath = item?.optString("path").orEmpty(),
                sha256 = item?.optString("sha256").orEmpty()
            )
        }
        val rendererList = (0 until renderers.length()).map { index ->
            val item = renderers.optJSONObject(index)
            val environmentJson = item?.optJSONObject("environment") ?: JSONObject()
            val environment = linkedMapOf<String, String>()
            val keys = environmentJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                environment[key] = environmentJson.optString(key)
            }
            BridgeRenderer(
                id = item?.optString("id").orEmpty(),
                displayName = item?.optString("name").orEmpty(),
                library = item?.optString("library").orEmpty(),
                environment = environment
            )
        }
        return EngineBridgeManifest(
            schemaVersion = json.optInt("schemaVersion", -1),
            bridgeVersion = json.optString("bridgeVersion"),
            androidAbis = (0 until abis.length()).map { abis.optString(it) }
                .filter { it.isNotBlank() }.toSet(),
            minimumJavaMajor = json.optInt("minimumJavaMajor", -1),
            maximumJavaMajor = json.optInt("maximumJavaMajor", -1),
            launchEntryClass = json.optJSONObject("launchEntry")?.optString("class").orEmpty(),
            launchEntryMethod = json.optJSONObject("launchEntry")?.optString("method").orEmpty(),
            launchEntryLibrary = json.optJSONObject("launchEntry")?.optString("library").orEmpty(),
            nativeLoadOrder = (0 until (json.optJSONArray("nativeLoadOrder") ?: JSONArray()).length())
                .map { index -> (json.optJSONArray("nativeLoadOrder") ?: JSONArray()).optString(index) },
            files = nativeFiles,
            classpathEntries = (0 until (json.optJSONArray("classpathEntries") ?: JSONArray()).length())
                .map { index -> (json.optJSONArray("classpathEntries") ?: JSONArray()).optString(index) },
            renderers = rendererList
        )
    }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() &&
            !File(path).isAbsolute &&
            !path.startsWith("/") &&
            !path.contains(92.toChar()) &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    private fun isInside(root: File, child: File): Boolean =
        child == root || child.path.startsWith(root.path + File.separator)

    private fun report(
        root: File,
        abi: String,
        version: String?,
        renderers: List<BridgeRenderer>,
        blockers: List<String>,
        warnings: List<String>
    ) = EngineBridgeReport(
        bridgeRoot = root,
        androidAbi = abi,
        bridgeVersion = version,
        availableRenderers = renderers,
        blockers = blockers.distinct(),
        warnings = warnings.distinct()
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
