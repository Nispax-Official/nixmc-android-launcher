package `in`.nispax.nixmc.engine

import `in`.nispax.nixmc.install.InstalledJavaRuntime
import `in`.nispax.nixmc.install.MinecraftLaunchPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MinecraftLaunchCommandBuilderTest {
    @Test
    fun buildsAnInProcessJliInvocationAndAppliesUserMemorySettings() {
        val root = Files.createTempDirectory("nixmc-command").toFile()
        try {
            val runtimeHome = File(root, "runtime").apply { mkdirs() }
            val java = File(runtimeHome, "bin/java").apply {
                parentFile?.mkdirs()
                writeText("fixture executable placeholder")
            }
            val gameDirectory = File(root, "minecraft").apply { mkdirs() }
            val clientJar = File(gameDirectory, "client.jar").apply { writeBytes(byteArrayOf(1)) }
            val plan = MinecraftLaunchPlan(
                version = "1.21.11",
                mainClass = "net.minecraft.client.main.Main",
                classpath = listOf(clientJar),
                jvmArguments = listOf(
                    "-cp",
                    clientJar.absolutePath,
                    "-Xms1G",
                    "-Xmx4G",
                    "-Dexample=true"
                ),
                gameArguments = listOf("--username", "NixPlayer"),
                assetIndex = "fixture",
                gameDirectory = gameDirectory,
                missingFiles = emptyList()
            )

            val spec = MinecraftLaunchCommandBuilder.build(
                runtime = InstalledJavaRuntime(runtimeHome, java, 21),
                plan = plan,
                memory = MinecraftMemoryConfig(minimumMiB = 512, maximumMiB = 3072),
                inheritedEnvironment = mapOf("LD_LIBRARY_PATH" to "/custom/native")
            )

            assertEquals("java", spec.launcherArguments.first())
            assertEquals(java.absolutePath, spec.javaExecutable.absolutePath)
            assertEquals(gameDirectory.absolutePath, spec.workingDirectory.absolutePath)
            assertTrue(spec.jvmArguments.contains("-Xms512M"))
            assertTrue(spec.jvmArguments.contains("-Xmx3072M"))
            assertFalse(spec.jvmArguments.contains("-Xms1G"))
            assertFalse(spec.jvmArguments.contains("-Xmx4G"))
            assertEquals(1, spec.jvmArguments.count { it == "-cp" })
            assertEquals("net.minecraft.client.main.Main", spec.mainClass)
            assertEquals(listOf("--username", "NixPlayer"), spec.gameArguments)
            assertEquals(
                listOf("java") + spec.jvmArguments + spec.mainClass + spec.gameArguments,
                spec.launcherArguments
            )
            assertTrue(spec.environment["JAVA_HOME"].orEmpty().isNotBlank())
            assertTrue(spec.environment["TMPDIR"].orEmpty().contains(".nixmc/tmp"))
            assertTrue(spec.environment["LD_LIBRARY_PATH"].orEmpty().contains("/custom/native"))
            assertTrue(spec.environment["LD_LIBRARY_PATH"].orEmpty().contains("natives/1.21.11"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun addsClasspathWhenTheMetadataDoesNotSpecifyIt() {
        val root = Files.createTempDirectory("nixmc-command").toFile()
        try {
            val java = File(root, "runtime/bin/java").apply {
                parentFile?.mkdirs()
                writeText("fixture")
            }
            val jar = File(root, "client.jar").apply { writeBytes(byteArrayOf(1)) }
            val plan = MinecraftLaunchPlan(
                version = "1.21.11",
                mainClass = "example.Main",
                classpath = listOf(jar),
                jvmArguments = emptyList(),
                gameArguments = emptyList(),
                assetIndex = "fixture",
                gameDirectory = root,
                missingFiles = emptyList()
            )

            val spec = MinecraftLaunchCommandBuilder.build(
                InstalledJavaRuntime(File(root, "runtime"), java, 21),
                plan
            )

            val classpathIndex = spec.jvmArguments.indexOf("-cp")
            assertTrue(classpathIndex >= 0)
            assertEquals(jar.absolutePath, spec.jvmArguments[classpathIndex + 1])
            assertEquals("example.Main", spec.mainClass)
            assertTrue(spec.launcherArguments.indexOf("example.Main") > classpathIndex + 1)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsIncompleteLaunchPlans() {
        val root = Files.createTempDirectory("nixmc-command").toFile()
        val java = File(root, "runtime/bin/java").apply {
            parentFile?.mkdirs()
            writeText("fixture")
        }
        MinecraftLaunchCommandBuilder.build(
            InstalledJavaRuntime(root, java, 21),
            MinecraftLaunchPlan(
                version = "1.21.11",
                mainClass = "example.Main",
                classpath = emptyList(),
                jvmArguments = emptyList(),
                gameArguments = emptyList(),
                assetIndex = "fixture",
                gameDirectory = root,
                missingFiles = listOf("client.jar")
            )
        )
    }

    @Test
    fun memorySettingsRejectInvalidRanges() {
        val lowMemory = runCatching { MinecraftMemoryConfig(minimumMiB = 64, maximumMiB = 512) }
        val reversed = runCatching { MinecraftMemoryConfig(minimumMiB = 1024, maximumMiB = 512) }
        val tooLarge = runCatching { MinecraftMemoryConfig(minimumMiB = 512, maximumMiB = 32768) }
        assertTrue(lowMemory.isFailure)
        assertTrue(reversed.isFailure)
        assertTrue(tooLarge.isFailure)
    }
}
