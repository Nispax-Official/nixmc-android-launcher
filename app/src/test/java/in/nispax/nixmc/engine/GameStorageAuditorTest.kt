package `in`.nispax.nixmc.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStorageAuditorTest {
    @Test
    fun countsOnlyRegularFilesAndFlagsTemporaryArtifactsWithoutDeletingThem() {
        val root = Files.createTempDirectory("nixmc-storage-audit").toFile()
        try {
            File(root, "versions/1.21.11").apply { mkdirs() }
            File(root, "versions/1.21.11/client.jar").writeBytes(ByteArray(6))
            File(root, "assets/index.bin").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(4))
            }
            val incomplete = File(root, "cache/download.part").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(5))
            }

            val report = GameStorageAuditor.inspect(root)

            assertTrue(report.exists)
            assertEquals(3, report.fileCount)
            assertEquals(15L, report.byteCount)
            assertEquals(1, report.temporaryFileCount)
            assertFalse(report.isClean)
            assertTrue("Auditing must not delete incomplete files.", incomplete.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ignoresSymbolicLinksInsteadOfFollowingThemOutsideTheRoot() {
        val parent = Files.createTempDirectory("nixmc-storage-link").toFile()
        val root = File(parent, "minecraft").apply { mkdirs() }
        val outside = File(parent, "outside-secret.bin").apply { writeBytes(ByteArray(12)) }
        try {
            Files.createSymbolicLink(File(root, "outside.bin").toPath(), outside.toPath())

            val report = GameStorageAuditor.inspect(root)

            assertEquals(0, report.fileCount)
            assertEquals(0L, report.byteCount)
            assertEquals(1, report.ignoredSymlinkCount)
            assertTrue(report.warnings.any { it.contains("symbolic link") })
            assertTrue(outside.isFile)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun missingDirectoryIsNotAnErrorAndDoesNotCreateFiles() {
        val parent = Files.createTempDirectory("nixmc-storage-missing").toFile()
        try {
            val missing = File(parent, "minecraft")
            val report = GameStorageAuditor.inspect(missing)
            assertFalse(report.exists)
            assertEquals(0, report.fileCount)
            assertFalse(missing.exists())
        } finally {
            parent.deleteRecursively()
        }
    }
}
