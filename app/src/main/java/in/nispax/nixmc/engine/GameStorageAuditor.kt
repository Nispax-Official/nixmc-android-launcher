package `in`.nispax.nixmc.engine

import java.io.File
import java.nio.file.Files

/**
 * Read-only audit of the app-owned Minecraft directory. It never deletes,
 * repairs, or follows symbolic links, so it cannot clean unrelated storage.
 */
data class GameStorageAudit(
    val root: File,
    val exists: Boolean,
    val fileCount: Int,
    val byteCount: Long,
    val temporaryFileCount: Int,
    val ignoredSymlinkCount: Int,
    val warnings: List<String>
) {
    val isClean: Boolean get() = warnings.isEmpty() && temporaryFileCount == 0
}

object GameStorageAuditor {
    private val temporarySuffixes = listOf(".part", ".installing", ".previous")

    fun inspect(gameRoot: File): GameStorageAudit {
        val warnings = mutableListOf<String>()
        val absoluteRoot = gameRoot.absoluteFile.normalize()
        if (Files.isSymbolicLink(absoluteRoot.toPath())) {
            return GameStorageAudit(absoluteRoot, false, 0, 0L, 0, 1, listOf("Game-data root must not be a symbolic link."))
        }
        val root = runCatching { absoluteRoot.canonicalFile }.getOrElse {
            return GameStorageAudit(gameRoot, false, 0, 0L, 0, 0, listOf("Cannot resolve game-data directory."))
        }
        if (Files.isSymbolicLink(root.toPath())) {
            return GameStorageAudit(root, false, 0, 0L, 0, 1, listOf("Game-data root must not be a symbolic link."))
        }
        if (!root.exists()) {
            return GameStorageAudit(root, false, 0, 0L, 0, 0, emptyList())
        }
        if (!root.isDirectory) {
            return GameStorageAudit(root, false, 0, 0L, 0, 0, listOf("Game-data path exists but is not a directory."))
        }

        var fileCount = 0
        var totalBytes = 0L
        var temporaryCount = 0
        var symlinkCount = 0

        fun visit(node: File) {
            if (Files.isSymbolicLink(node.toPath())) {
                symlinkCount++
                return
            }
            val canonical = runCatching { node.canonicalFile }.getOrNull()
            if (canonical == null || (canonical != root && !canonical.path.startsWith(root.path + File.separator))) {
                warnings += "Ignored a path outside the managed game-data directory."
                return
            }
            if (node.isDirectory) {
                val children = node.listFiles()
                if (children == null) {
                    warnings += "Could not inspect one game-data directory."
                    return
                }
                children.forEach(::visit)
            } else if (node.isFile) {
                fileCount++
                val size = node.length().coerceAtLeast(0L)
                totalBytes = if (Long.MAX_VALUE - totalBytes < size) Long.MAX_VALUE else totalBytes + size
                if (temporarySuffixes.any { node.name.endsWith(it, ignoreCase = true) }) temporaryCount++
            }
        }
        visit(root)

        if (symlinkCount > 0) warnings += "Ignored $symlinkCount symbolic link(s) during the storage audit."
        if (temporaryCount > 0) warnings += "Found $temporaryCount temporary/incomplete file(s); they were not deleted."
        return GameStorageAudit(root, true, fileCount, totalBytes, temporaryCount, symlinkCount, warnings)
    }
}
