package `in`.nispax.nixmc.engine

import java.io.File

data class JavaRuntimeLibraryResolution(
    val javaHome: File,
    val scanRoot: File?,
    val orderedLibraries: List<File>,
    val blockers: List<String>,
    val warnings: List<String>
) {
    val isReady: Boolean get() = blockers.isEmpty() && orderedLibraries.isNotEmpty()
}

/**
 * Resolves the Android-specific shared libraries inside the installed Java
 * runtime. The two JLI/JVM entry libraries and the core Java runtime libraries
 * are ordered before optional AWT/font and remaining ABI-specific libraries.
 *
 * Resolution does not map native code; AndroidNativeBridgeLauncher invokes
 * these paths via the already loaded bridge's dlopen JNI entry.
 */
object JavaRuntimeLibraryResolver {
    private val requiredOrder = listOf(
        "libjli.so",
        "libjvm.so",
        "libverify.so",
        "libjava.so",
        "libnet.so",
        "libnio.so"
    )
    private val optionalOrder = listOf(
        "libawt.so",
        "libawt_headless.so",
        "libfreetype.so",
        "libfontmanager.so"
    )

    fun resolve(javaHome: File, androidAbi: String): JavaRuntimeLibraryResolution {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val root = runCatching { javaHome.canonicalFile }.getOrElse {
            blockers += "Cannot resolve Java home: ${it.message ?: "unknown error"}"
            return JavaRuntimeLibraryResolution(javaHome, null, emptyList(), blockers, warnings)
        }
        if (!root.isDirectory) {
            blockers += "Java home directory is missing: ${root.absolutePath}"
            return JavaRuntimeLibraryResolution(root, null, emptyList(), blockers, warnings)
        }

        val libRoot = File(root, "lib").canonicalFile
        if (!isInside(root, libRoot) || !libRoot.isDirectory) {
            blockers += "Java runtime has no safe lib directory: ${libRoot.absolutePath}"
            return JavaRuntimeLibraryResolution(root, null, emptyList(), blockers, warnings)
        }

        val archDirectoryNames = architectureDirectories(androidAbi)
        val architectureRoot = archDirectoryNames
            .map { File(libRoot, it) }
            .firstOrNull { it.isDirectory }
            ?.canonicalFile
            ?.takeIf { isInside(libRoot, it) }
        val scanRoot = architectureRoot ?: libRoot
        val allLibraries = libRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".so", ignoreCase = true) }
            .mapNotNull { file ->
                runCatching { file.canonicalFile }.getOrNull()
                    ?.takeIf { isInside(libRoot, it) && it.isFile }
            }
            .distinctBy { it.absolutePath }
            .toList()

        fun choose(name: String): File? = allLibraries
            .filter { it.name.equals(name, ignoreCase = true) }
            .sortedWith(
                compareBy<File> { file ->
                    val segments = relativeSegments(libRoot, file)
                    if (segments.any { it in archDirectoryNames }) 0 else 1
                }.thenBy { file ->
                    val segments = relativeSegments(libRoot, file)
                    when (name) {
                        "libjli.so" -> if ("jli" in segments) 0 else 1
                        "libjvm.so" -> if ("server" in segments) 0 else 1
                        else -> 0
                    }
                }.thenBy { it.absolutePath.length }
            )
            .firstOrNull()

        val ordered = mutableListOf<File>()
        for (name in requiredOrder) {
            val library = choose(name)
            if (library == null) {
                blockers += "Java runtime library is missing: $name"
            } else {
                ordered += library
            }
        }
        for (name in optionalOrder) {
            val library = choose(name)
            if (library == null) {
                warnings += "Optional Java graphics/font library is absent: $name"
            } else {
                ordered += library
            }
        }

        val scanLibraries = scanRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".so", ignoreCase = true) }
            .mapNotNull { file ->
                runCatching { file.canonicalFile }.getOrNull()
                    ?.takeIf { isInside(scanRoot, it) && isInside(libRoot, it) && it.isFile }
            }
            .distinctBy { it.absolutePath }
            .sortedBy { it.absolutePath }
            .toList()
        val alreadyAdded = ordered.map { it.absolutePath }.toMutableSet()
        scanLibraries.forEach { file ->
            if (alreadyAdded.add(file.absolutePath)) ordered += file
        }

        if (ordered.isEmpty()) blockers += "Java runtime contains no native shared libraries."
        return JavaRuntimeLibraryResolution(
            javaHome = root,
            scanRoot = scanRoot,
            orderedLibraries = ordered,
            blockers = blockers.distinct(),
            warnings = warnings.distinct()
        )
    }

    private fun architectureDirectories(androidAbi: String): List<String> = when (androidAbi) {
        "arm64-v8a" -> listOf("aarch64", "arm64", "arm64-v8a")
        "armeabi-v7a" -> listOf("arm", "arm32", "armeabi-v7a")
        "x86_64" -> listOf("x86_64", "amd64")
        "x86" -> listOf("x86", "i386", "i686")
        else -> emptyList()
    }

    private fun relativeSegments(root: File, child: File): List<String> =
        child.relativeTo(root).path.replace('\\', '/').split('/').map { it.lowercase() }

    private fun isInside(root: File, child: File): Boolean =
        child == root || child.path.startsWith(root.path + File.separator)
}
