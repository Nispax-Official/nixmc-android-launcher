package `in`.nispax.nixmc.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JavaRuntimeLibraryResolverTest {
    @Test
    fun prefersDeviceArchitectureAndOrdersCoreJliLibrariesFirst() {
        val root = Files.createTempDirectory("nixmc-jre-libs").toFile()
        try {
            val archRoot = File(root, "lib/aarch64")
            listOf(
                "jli/libjli.so",
                "server/libjvm.so",
                "libverify.so",
                "libjava.so",
                "libnet.so",
                "libnio.so",
                "libawt.so",
                "libawt_headless.so",
                "libfreetype.so",
                "libfontmanager.so",
                "libextra.so"
            ).forEachIndexed { index, path ->
                File(archRoot, path).apply {
                    parentFile?.mkdirs()
                    writeBytes(byteArrayOf(index.toByte()))
                }
            }
            val genericJli = File(root, "lib/jli/libjli.so").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(99))
            }

            val resolution = JavaRuntimeLibraryResolver.resolve(root, "arm64-v8a")

            assertTrue("Blockers: ${resolution.blockers}", resolution.isReady)
            assertEquals(File(archRoot, "jli/libjli.so").canonicalFile, resolution.orderedLibraries.first())
            assertTrue(resolution.orderedLibraries.indexOf(File(archRoot, "server/libjvm.so").canonicalFile) > 0)
            assertFalse(resolution.orderedLibraries.contains(genericJli.canonicalFile))
            assertTrue(resolution.orderedLibraries.contains(File(archRoot, "libextra.so").canonicalFile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failsClearlyWhenCoreRuntimeLibrariesAreMissing() {
        val root = Files.createTempDirectory("nixmc-jre-libs").toFile()
        try {
            File(root, "lib/aarch64/jli/libjli.so").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }

            val resolution = JavaRuntimeLibraryResolver.resolve(root, "arm64-v8a")

            assertFalse(resolution.isReady)
            assertTrue(resolution.blockers.any { it.contains("libjvm.so") })
            assertTrue(resolution.blockers.any { it.contains("libjava.so") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun usesGenericRuntimeLibraryRootWhenArchitectureDirectoryIsUnavailable() {
        val root = Files.createTempDirectory("nixmc-jre-libs").toFile()
        try {
            listOf(
                "jli/libjli.so",
                "server/libjvm.so",
                "libverify.so",
                "libjava.so",
                "libnet.so",
                "libnio.so"
            ).forEach { path ->
                File(root, "lib/$path").apply {
                    parentFile?.mkdirs()
                    writeBytes(byteArrayOf(1))
                }
            }

            val resolution = JavaRuntimeLibraryResolver.resolve(root, "x86_64")

            assertTrue("Blockers: ${resolution.blockers}", resolution.isReady)
            assertEquals(File(root, "lib").canonicalFile, resolution.scanRoot)
        } finally {
            root.deleteRecursively()
        }
    }
}
