package `in`.nispax.nixmc.install

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class MinecraftLaunchPlan(
    val version: String,
    val mainClass: String,
    val classpath: List<File>,
    val jvmArguments: List<String>,
    val gameArguments: List<String>,
    val assetIndex: String,
    val gameDirectory: File,
    val missingFiles: List<String>
) {
    val isComplete: Boolean get() = missingFiles.isEmpty()
}

/**
 * Resolves Mojang's modern argument schema into a launch plan.
 *
 * Android is reported as Linux by the Android Java runtime. Native classifier
 * resolution is deliberately not faked here: desktop Linux LWJGL binaries are
 * not Android binaries, so the native bridge must provide compatible libraries.
 */
object MinecraftLaunchPlanner {
    private const val VERSION = "1.21.11"

    fun create(
        gameDirectory: File,
        playerName: String,
        architecture: String = System.getProperty("os.arch") ?: "unknown",
        featureFlags: Map<String, Boolean> = emptyMap(),
        osVersion: String = System.getProperty("os.version").orEmpty(),
        osName: String = "linux"
    ): MinecraftLaunchPlan {
        require(playerName.matches(Regex("[A-Za-z0-9_]{3,16}"))) {
            "Player name must be 3–16 letters, numbers, or underscores."
        }

        val versionDirectory = File(gameDirectory, "versions/$VERSION")
        val metadataFile = File(versionDirectory, "$VERSION.json")
        val missing = mutableListOf<String>()
        if (!metadataFile.isFile) {
            missing += "Version metadata: ${metadataFile.absolutePath}"
            return MinecraftLaunchPlan(
                VERSION, "", emptyList(), emptyList(), emptyList(), "", gameDirectory, missing
            )
        }

        val metadata = JSONObject(metadataFile.readText())
        val mainClass = metadata.optString("mainClass")
        if (mainClass.isBlank()) missing += "mainClass is absent from version metadata"

        val client = File(versionDirectory, "$VERSION.jar")
        if (!client.isFile) missing += "Client JAR: ${client.absolutePath}"

        val librariesRoot = File(gameDirectory, "libraries").canonicalFile
        val classpath = mutableListOf<File>()
        val libraries = metadata.optJSONArray("libraries") ?: JSONArray()
        for (index in 0 until libraries.length()) {
            val library = libraries.optJSONObject(index) ?: continue
            if (!rulesAllow(library.optJSONArray("rules"), architecture, featureFlags, osName, osVersion)) {
                continue
            }

            val artifact = library.optJSONObject("downloads")?.optJSONObject("artifact") ?: continue
            val relativePath = artifact.optString("path")
            if (relativePath.isBlank()) continue

            val file = File(librariesRoot, relativePath).canonicalFile
            if (!file.path.startsWith(librariesRoot.path + File.separator)) {
                missing += "Unsafe library artifact path in metadata: $relativePath"
                continue
            }
            if (file.isFile) classpath += file else missing += "Library: $relativePath"
        }
        if (client.isFile) classpath += client

        val assetIndex = metadata.optJSONObject("assetIndex")?.optString("id").orEmpty()
        if (assetIndex.isBlank()) {
            missing += "Asset index id is absent"
        } else if (!assetIndex.matches(Regex("[A-Za-z0-9._-]+"))) {
            missing += "Asset index id contains unsupported characters"
        } else {
            val indexFile = File(gameDirectory, "assets/indexes/$assetIndex.json")
            if (!indexFile.isFile) missing += "Asset index: ${indexFile.absolutePath}"
        }

        val offlineUuid = UUID.nameUUIDFromBytes(
            "OfflinePlayer:$playerName".toByteArray(Charsets.UTF_8)
        ).toString().replace("-", "")
        val replacements = mapOf(
            "auth_player_name" to playerName,
            "version_name" to VERSION,
            "game_directory" to gameDirectory.absolutePath,
            "assets_root" to File(gameDirectory, "assets").absolutePath,
            "assets_index_name" to assetIndex,
            "auth_uuid" to offlineUuid,
            "auth_access_token" to "0",
            "clientid" to "0",
            "auth_xuid" to "0",
            "user_type" to "legacy",
            "version_type" to "release",
            "natives_directory" to File(gameDirectory, "natives/$VERSION").absolutePath,
            "launcher_name" to "NixMC",
            "launcher_version" to "0.4.0",
            "classpath" to classpath.joinToString(File.pathSeparator) { it.absolutePath },
            "library_directory" to librariesRoot.absolutePath
        )

        val arguments = metadata.optJSONObject("arguments")
        val jvmArgs = readArguments(
            arguments?.optJSONArray("jvm"), architecture, featureFlags, osName, osVersion
        ).map { replaceVariables(it, replacements) }
        val gameArgs = readArguments(
            arguments?.optJSONArray("game"), architecture, featureFlags, osName, osVersion
        ).map { replaceVariables(it, replacements) }

        return MinecraftLaunchPlan(
            version = VERSION,
            mainClass = mainClass,
            classpath = classpath,
            jvmArguments = jvmArgs,
            gameArguments = gameArgs,
            assetIndex = assetIndex,
            gameDirectory = gameDirectory,
            missingFiles = missing.distinct()
        )
    }

    private fun readArguments(
        arguments: JSONArray?,
        architecture: String,
        featureFlags: Map<String, Boolean>,
        osName: String,
        osVersion: String
    ): List<String> {
        if (arguments == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arguments.length()) {
            when (val value = arguments.opt(i)) {
                is String -> result += value
                is JSONObject -> {
                    if (!rulesAllow(
                            value.optJSONArray("rules"), architecture, featureFlags, osName, osVersion
                        )
                    ) continue

                    when (val argumentValue = value.opt("value")) {
                        is String -> result += argumentValue
                        is JSONArray -> for (j in 0 until argumentValue.length()) {
                            val item = argumentValue.optString(j)
                            if (item.isNotBlank()) result += item
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * Mojang rules are ordered: the last matching rule wins. Feature flags not
     * explicitly enabled by the launcher default to false.
     */
    private fun rulesAllow(
        rules: JSONArray?,
        architecture: String,
        featureFlags: Map<String, Boolean>,
        osName: String,
        osVersion: String
    ): Boolean {
        if (rules == null || rules.length() == 0) return true

        var allowed = false
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (!osMatches(rule.optJSONObject("os"), architecture, osName, osVersion)) continue
            if (!featuresMatch(rule.optJSONObject("features"), featureFlags)) continue
            allowed = rule.optString("action") == "allow"
        }
        return allowed
    }

    private fun osMatches(
        os: JSONObject?,
        architecture: String,
        osName: String,
        osVersion: String
    ): Boolean {
        if (os == null) return true

        val requiredName = os.optString("name")
        if (requiredName.isNotBlank() && normalizeOsName(requiredName) != normalizeOsName(osName)) {
            return false
        }

        val requiredArchitecture = os.optString("arch")
        if (requiredArchitecture.isNotBlank() &&
            !regexMatchesArchitecture(requiredArchitecture, architecture)
        ) return false

        val requiredVersion = os.optString("version")
        if (requiredVersion.isNotBlank() && !regexMatches(requiredVersion, osVersion)) return false
        return true
    }

    private fun featuresMatch(rules: JSONObject?, featureFlags: Map<String, Boolean>): Boolean {
        if (rules == null) return true
        val keys = rules.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val required = rules.optBoolean(key, false)
            if ((featureFlags[key] ?: false) != required) return false
        }
        return true
    }

    private fun regexMatchesArchitecture(pattern: String, architecture: String): Boolean =
        architectureAliases(architecture).any { candidate -> regexMatches(pattern, candidate) }

    private fun regexMatches(pattern: String, value: String): Boolean =
        runCatching { Regex(pattern).matches(value) }.getOrElse { pattern == value }

    private fun architectureAliases(value: String): Set<String> = when (value.lowercase()) {
        "aarch64", "arm64", "arm64-v8a" -> setOf("aarch64", "arm64", "arm64-v8a")
        "x86_64", "amd64", "x64" -> setOf("x86_64", "amd64", "x64")
        "x86", "i386", "i486", "i586", "i686" -> setOf("x86", "i386", "i486", "i586", "i686")
        "arm", "arm32", "armeabi-v7a" -> setOf("arm", "arm32", "armeabi-v7a")
        else -> setOf(value)
    }

    private fun normalizeOsName(value: String): String = when (value.lowercase()) {
        "mac", "macos", "osx", "darwin" -> "osx"
        "win", "win32", "windows" -> "windows"
        "linux", "android" -> "linux"
        else -> value.lowercase()
    }

    private fun replaceVariables(value: String, replacements: Map<String, String>): String {
        var result = value
        replacements.forEach { (key, replacement) ->
            result = result.replace("$" + "{" + key + "}", replacement)
        }
        return result
    }
}
