package `in`.nispax.nixmc.install

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MinecraftLaunchPlannerTest {
    @Test
    fun resolvesModernArgumentsAndOfflinePlayerName() {
        val root = Files.createTempDirectory("nixmc-launch-plan").toFile()
        try {
            val versionDir = File(root, "versions/1.21.11").apply { mkdirs() }
            File(versionDir, "1.21.11.jar").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "assets/indexes").mkdirs()
            File(root, "assets/indexes/test-index.json").writeText("{\"objects\":{}}")

            val jvmArgs = JSONArray()
                .put("-cp")
                .put("$" + "{classpath}")
                .put("-Dgame.dir=$" + "{game_directory}")
            val gameArgs = JSONArray()
                .put("--username")
                .put("$" + "{auth_player_name}")
            val metadata = JSONObject()
                .put("mainClass", "net.minecraft.client.main.Main")
                .put("libraries", JSONArray())
                .put("assetIndex", JSONObject().put("id", "test-index"))
                .put("arguments", JSONObject().put("jvm", jvmArgs).put("game", gameArgs))
            File(versionDir, "1.21.11.json").writeText(metadata.toString())

            val plan = MinecraftLaunchPlanner.create(root, "NixPlayer")

            assertTrue("Expected all fixture files to be present: ${plan.missingFiles}", plan.isComplete)
            assertEquals("net.minecraft.client.main.Main", plan.mainClass)
            assertEquals("-cp", plan.jvmArguments[0])
            assertEquals(File(versionDir, "1.21.11.jar").absolutePath, plan.jvmArguments[1])
            assertEquals("-Dgame.dir=${root.absolutePath}", plan.jvmArguments[2])
            assertEquals(listOf("--username", "NixPlayer"), plan.gameArguments)
            assertEquals("1.21.11", plan.version)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun appliesArchitectureAndFeatureRulesToLibrariesAndArguments() {
        val root = createFixtureRoot()
        try {
            val librariesRoot = File(root, "libraries")
            val armArtifact = File(librariesRoot, "com/example/arm.jar").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            val featureArtifact = File(librariesRoot, "com/example/feature.jar").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(2))
            }

            val armLibrary = JSONObject()
                .put("name", "com.example:arm:1")
                .put("rules", JSONArray().put(
                    JSONObject()
                        .put("action", "allow")
                        .put("os", JSONObject().put("name", "linux").put("arch", "aarch64"))
                ))
                .put("downloads", JSONObject().put(
                    "artifact",
                    JSONObject().put("path", "com/example/arm.jar")
                ))
            val featureLibrary = JSONObject()
                .put("name", "com.example:feature:1")
                .put("rules", JSONArray().put(
                    JSONObject()
                        .put("action", "allow")
                        .put("features", JSONObject().put("has_custom_resolution", true))
                ))
                .put("downloads", JSONObject().put(
                    "artifact",
                    JSONObject().put("path", "com/example/feature.jar")
                ))

            val versionDirectory = File(root, "versions/1.21.11")
            val metadata = JSONObject()
                .put("mainClass", "example.Main")
                .put("libraries", JSONArray().put(armLibrary).put(featureLibrary))
                .put("assetIndex", JSONObject().put("id", "fixture"))
                .put("arguments", JSONObject().put(
                    "game",
                    JSONArray().put(
                        JSONObject()
                            .put("rules", JSONArray().put(
                                JSONObject()
                                    .put("action", "allow")
                                    .put("features", JSONObject().put("has_custom_resolution", true))
                            ))
                            .put("value", JSONArray().put("--width").put("1280"))
                    )
                ))
            File(versionDirectory, "1.21.11.json").writeText(metadata.toString())

            val defaultPlan = MinecraftLaunchPlanner.create(root, "NixPlayer", architecture = "arm64-v8a")
            assertTrue(defaultPlan.classpath.contains(armArtifact))
            assertFalse(defaultPlan.classpath.contains(featureArtifact))
            assertTrue(defaultPlan.gameArguments.isEmpty())

            val featurePlan = MinecraftLaunchPlanner.create(
                root,
                "NixPlayer",
                architecture = "aarch64",
                featureFlags = mapOf("has_custom_resolution" to true)
            )
            assertTrue(featurePlan.classpath.contains(featureArtifact))
            assertEquals(listOf("--width", "1280"), featurePlan.gameArguments)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsLibraryPathsThatEscapeTheLibrariesDirectory() {
        val root = createFixtureRoot()
        try {
            val outside = File(root, "escape.jar").apply { writeBytes(byteArrayOf(9)) }
            val maliciousLibrary = JSONObject()
                .put("name", "example:escape:1")
                .put("downloads", JSONObject().put(
                    "artifact",
                    JSONObject().put("path", "../escape.jar")
                ))
            val metadataFile = File(root, "versions/1.21.11/1.21.11.json")
            val metadata = JSONObject(metadataFile.readText())
                .put("libraries", JSONArray().put(maliciousLibrary))
            metadataFile.writeText(metadata.toString())

            val plan = MinecraftLaunchPlanner.create(root, "NixPlayer")
            assertFalse(plan.isComplete)
            assertTrue(plan.missingFiles.any { it.contains("Unsafe library artifact path") })
            assertFalse(plan.classpath.contains(outside))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createFixtureRoot(): File {
        val root = Files.createTempDirectory("nixmc-launch-plan").toFile()
        val versionDirectory = File(root, "versions/1.21.11").apply { mkdirs() }
        File(versionDirectory, "1.21.11.jar").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "assets/indexes").mkdirs()
        File(root, "assets/indexes/fixture.json").writeText("{\"objects\":{}}")
        File(versionDirectory, "1.21.11.json").writeText(
            JSONObject()
                .put("mainClass", "example.Main")
                .put("libraries", JSONArray())
                .put("assetIndex", JSONObject().put("id", "fixture"))
                .put("arguments", JSONObject())
                .toString()
        )
        return root
    }
}
