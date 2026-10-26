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
    val nativeFiles: List<BridgeNativeFile>,
    val renderers: List<BridgeRenderer>
)

data class BridgeNativeFile(
    val relativePath: String,
    val sha256: String
)

data class BridgeRenderer(
    val id: String,
    val displayName: String
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
        if (manifest.launchEntryClass.isBlank() || manifest.launchEntryMethod.isBlank()) {
            blockers += "Bridge launch entry point is incomplete."
        }
        if (manifest.nativeFiles.isEmpty()) blockers += "Bridge manifest declares no native files."

        for (nativeFile in manifest.nativeFiles) {
            val relative = nativeFile.relativePath
            if (relative.isBlank() || File(relative).isAbsolute || relative.startsWith("/") ||
                relative.split('/', '\\\\').any { it == ".." || it.isBlank() }
            ) {
                blockers += "Unsafe native bridge file path: $relative"
                continue
            }
            val target = runCatching { File(canonicalRoot, relative).canonicalFile }.getOrNull()
            if (target == null || !isInside(canonicalRoot, target)) {
                blockers += "Native bridge file path escapes the bridge directory: $relative"
                continue
            }
            if (!sha256Pattern.matches(nativeFile.sha256)) {
                blockers += "Native bridge file has an invalid SHA-256 entry: $relative"
                continue
            }
            if (!target.isFile) {
                blockers += "Required native bridge file is missing: $relative"
                continue
            }
            val actual = runCatching { sha256(target) }.getOrElse {
                blockers += "Could not verify native bridge file $relative: ${it.message ?: "read failed"}"
                continue
            }
            if (!actual.equals(nativeFile.sha256, ignoreCase = true)) {
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
        if (selectedRendererId != null && manifest.renderers.none { it.id == selectedRendererId }) {
            blockers += "Selected renderer '$selectedRendererId' is not provided by this bridge."
        }
        warnings += "Manifest checksums validate local files only; install the manifest from a trusted, pinned bridge release."
        return report(canonicalRoot, androidAbi, manifest.bridgeVersion, manifest.renderers, blockers, warnings)
    }

    private fun parseManifest(json: JSONObject): EngineBridgeManifest {
        val abis = json.optJSONArray("androidAbis") ?: JSONArray()
        val files = json.optJSONArray("nativeFiles") ?: JSONArray()
        val renderers = json.optJSONArray("renderers") ?: JSONArray()
        val nativeFiles = (0 until files.length()).mapNotNull { index ->
            val item = files.optJSONObject(index) ?: return@mapNotNull null
            BridgeNativeFile(
                relativePath = item.optString("path"),
                sha256 = item.optString("sha256")
            )
        }
        val rendererList = (0 until renderers.length()).mapNotNull { index ->
            val item = renderers.optJSONObject(index) ?: return@mapNotNull null
            BridgeRenderer(
                id = item.optString("id"),
                displayName = item.optString("name")
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
            nativeFiles = nativeFiles,
            renderers = rendererList
        )
    }

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
