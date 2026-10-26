package `in`.nispax.nixmc.engine

import `in`.nispax.nixmc.install.MinecraftLaunchPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AmethystEngineBundleInstallerTest {
    @Test
    fun importsAndVerifiesAnAbiPinnedEngineBundleTransactionally() {
        val root = Files.createTempDirectory("nixmc-amethyst-import").toFile()
        try {
            val apk = File(root, "amethyst.apk")
            writeFixtureApk(apk)
            val game = File(root, "minecraft").apply { mkdirs() }
            val plan = fakePlan(game)

            val result = AmethystEngineBundleInstaller.importFromApkFiles(
                apkPaths = listOf(apk.absolutePath),
                versionName = "1.1.7-release-test",
                gameDirectory = game,
                plan = plan,
                androidAbi = "arm64-v8a",
                javaMajorVersion = 21
            )

            assertTrue("Blockers: ${result.report.blockers}", result.report.isReady)
            assertEquals("3.3.3", result.lwjglVersion)
            assertEquals("opengles2", result.selectedRendererId)
            assertTrue(File(result.bridgeRoot, "lib/arm64-v8a/libpojavexec.so").isFile)
            assertTrue(File(result.bridgeRoot, "classpath/lwjgl3/3.3.3/lwjgl-3.3.3-merged-modules.jar").isFile)
            assertTrue(File(result.bridgeRoot, "licenses/ANGLE_LICENSE").isFile)
            assertTrue(File(result.bridgeRoot, "UPSTREAM-NOTICE.txt").isFile)
            assertTrue(File(result.bridgeRoot, "manifest.json").isFile)
            assertTrue(result.fileCount >= 6)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesAnUnpinnedUpstreamVersionBeforeChangingTheActiveBundle() {
        val root = Files.createTempDirectory("nixmc-amethyst-import").toFile()
        try {
            val apk = File(root, "amethyst.apk")
            writeFixtureApk(apk)
            val game = File(root, "minecraft").apply { mkdirs() }
            val active = File(game, "engine/bridge").apply {
                mkdirs()
                File(this, "old-marker").writeText("keep")
            }

            val failure = runCatching {
                AmethystEngineBundleInstaller.importFromApkFiles(
                    listOf(apk.absolutePath),
                    "1.2.0",
                    game,
                    fakePlan(game),
                    "arm64-v8a",
                    21
                )
            }

            assertTrue(failure.isFailure)
            assertTrue(File(active, "old-marker").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsIncompleteApksAndLeavesNoPartialActiveBundle() {
        val root = Files.createTempDirectory("nixmc-amethyst-import").toFile()
        try {
            val apk = File(root, "incomplete.apk")
            writeFixtureApk(apk, includeLauncherNative = false)
            val game = File(root, "minecraft").apply { mkdirs() }

            val failure = runCatching {
                AmethystEngineBundleInstaller.importFromApkFiles(
                    listOf(apk.absolutePath),
                    "1.1.7",
                    game,
                    fakePlan(game),
                    "arm64-v8a",
                    21
                )
            }

            assertTrue(failure.isFailure)
            assertFalse(File(game, "engine/bridge/manifest.json").exists())
            assertFalse(File(game, "engine/bridge.installing").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsConflictingLibrariesAcrossBaseAndSplitApks() {
        val root = Files.createTempDirectory("nixmc-amethyst-import").toFile()
        try {
            val base = File(root, "base.apk")
            val split = File(root, "split.apk")
            writeFixtureApk(base, launcherNativeBytes = byteArrayOf(1, 2, 3))
            writeFixtureApk(split, launcherNativeBytes = byteArrayOf(9, 9, 9))
            val game = File(root, "minecraft").apply { mkdirs() }

            val failure = runCatching {
                AmethystEngineBundleInstaller.importFromApkFiles(
                    listOf(base.absolutePath, split.absolutePath),
                    "1.1.7",
                    game,
                    fakePlan(game),
                    "arm64-v8a",
                    21
                )
            }

            assertTrue(failure.isFailure)
            assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("Conflicting APK split files"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun fakePlan(game: File) = MinecraftLaunchPlan(
        version = "1.21.11",
        mainClass = "net.minecraft.client.main.Main",
        classpath = listOf(
            File(game, "libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar")
        ),
        jvmArguments = emptyList(),
        gameArguments = emptyList(),
        assetIndex = "fixture",
        gameDirectory = game,
        missingFiles = emptyList()
    )

    private fun writeFixtureApk(
        apk: File,
        includeLauncherNative: Boolean = true,
        launcherNativeBytes: ByteArray = byteArrayOf(5, 4, 3, 2)
    ) {
        ZipOutputStream(apk.outputStream().buffered()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            val abi = "arm64-v8a"
            entry("lib/$abi/libc++_shared.so", byteArrayOf(1, 1, 1))
            if (includeLauncherNative) entry("lib/$abi/libpojavexec.so", launcherNativeBytes)
            entry("lib/$abi/libng_gl4es.so", byteArrayOf(2, 2, 2))
            entry("lib/$abi/libbytehook.so", byteArrayOf(3, 3, 3))
            entry(
                "assets/components/lwjgl3/3.3.3/lwjgl-3.3.3-merged-modules.jar",
                byteArrayOf(4, 4, 4)
            )
            entry("assets/components/lwjgl3/3.3.3/lwjgl.jar", byteArrayOf(5, 5, 5))
            entry("assets/components/caciocavallo17/cacio-shared.jar", byteArrayOf(6, 6, 6))
            entry("assets/licenses/ANGLE_LICENSE", "fixture license".toByteArray())
        }
    }
}
