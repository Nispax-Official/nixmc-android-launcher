package `in`.nispax.nixmc.install

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
