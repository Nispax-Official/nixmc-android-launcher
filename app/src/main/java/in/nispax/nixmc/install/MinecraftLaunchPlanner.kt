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
 * This deliberately does not start a JVM; Android needs a compatible runtime
 * and native graphics bridge before this plan can be executed.
 */
object MinecraftLaunchPlanner {
    private const val VERSION = "1.21.11"

    fun create(
        gameDirectory: File,
        playerName: String,
        architecture: String = System.getProperty("os.arch") ?: "unknown"
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

        val classpath = mutableListOf<File>()
        val libraries = metadata.optJSONArray("libraries") ?: JSONArray()
        for (index in 0 until libraries.length()) {
            val library = libraries.getJSONObject(index)
            if (!rulesAllow(library.optJSONArray("rules"), architecture)) continue
            val artifact = library.optJSONObject("downloads")?.optJSONObject("artifact") ?: continue
            val relativePath = artifact.optString("path")
            if (relativePath.isBlank() || relativePath.contains("..")) continue
            val file = File(gameDirectory, "libraries/$relativePath")
            if (file.isFile) classpath += file else missing += "Library: $relativePath"
        }
        if (client.isFile) classpath += client

        val assetIndex = metadata.optJSONObject("assetIndex")?.optString("id").orEmpty()
        if (assetIndex.isBlank()) missing += "Asset index id is absent"
        else {
            val indexFile = File(gameDirectory, "assets/indexes/$assetIndex.json")
            if (!indexFile.isFile) missing += "Asset index: ${indexFile.absolutePath}"
        }

        val offlineUuid = UUID.nameUUIDFromBytes("OfflinePlayer:$playerName".toByteArray(Charsets.UTF_8))
            .toString().replace("-", "")
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
            "launcher_version" to "0.3.0",
            "classpath" to classpath.joinToString(File.pathSeparator) { it.absolutePath },
            "library_directory" to File(gameDirectory, "libraries").absolutePath
        )
        val jvmArgs = readArguments(metadata.optJSONObject("arguments")?.optJSONArray("jvm"), architecture)
            .map { replaceVariables(it, replacements) }
        val gameArgs = readArguments(metadata.optJSONObject("arguments")?.optJSONArray("game"), architecture)
            .map { replaceVariables(it, replacements) }

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

    private fun readArguments(arguments: JSONArray?, architecture: String): List<String> {
        if (arguments == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arguments.length()) {
            when (val value = arguments.get(i)) {
                is String -> result += value
                is JSONObject -> {
                    if (!rulesAllow(value.optJSONArray("rules"), architecture)) continue
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

    private fun rulesAllow(rules: JSONArray?, architecture: String): Boolean {
        if (rules == null || rules.length() == 0) return true
        var allowed = false
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            val os = rule.optJSONObject("os")
            val osMatches = os == null ||
                (os.optString("name").let { it.isBlank() || it == "linux" } &&
                    os.optString("arch").let { it.isBlank() || it == architecture })
            if (osMatches) allowed = rule.optString("action") == "allow"
        }
        return allowed
    }

    private fun replaceVariables(value: String, replacements: Map<String, String>): String {
        var result = value
        replacements.forEach { (key, replacement) ->
            val token = "$" + "{" + key + "}"
            result = result.replace(token, replacement)
        }
        return result
    }
}
