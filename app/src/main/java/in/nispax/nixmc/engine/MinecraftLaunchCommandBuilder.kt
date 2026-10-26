package `in`.nispax.nixmc.engine

import `in`.nispax.nixmc.install.InstalledJavaRuntime
import `in`.nispax.nixmc.install.MinecraftLaunchPlan
import java.io.File

/**
 * User-configurable memory bounds for one Minecraft session.
 * Android devices have very different memory budgets, so settings are explicit
 * and validated rather than silently accepting malformed JVM flags.
 */
data class MinecraftMemoryConfig(
    val minimumMiB: Int = 512,
    val maximumMiB: Int = 2048
) {
    init {
        require(minimumMiB in 128..16384) { "Minimum memory must be between 128 and 16384 MiB." }
        require(maximumMiB in minimumMiB..16384) {
            "Maximum memory must be at least the minimum and no more than 16384 MiB."
        }
    }
}

/**
 * A resolved launch request for an in-process Java launcher such as the JLI
 * bridge used by Android Minecraft launchers. javaExecutable is for runtime
 * validation/diagnostics; this spec does not spawn it as a child process.
 */
data class MinecraftJvmLaunchSpec(
    val javaHome: File,
    val javaExecutable: File,
    val jvmArguments: List<String>,
    val mainClass: String,
    val gameArguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File
) {
    /**
     * JLI expects argv[0] to be the launcher name, followed by JVM options,
     * main class, and game arguments. The bridge must apply environment values
     * and set the working directory before invoking the native launcher.
     */
    val launcherArguments: List<String>
        get() = listOf("java") + jvmArguments + mainClass + gameArguments
}

object MinecraftLaunchCommandBuilder {
    private val unresolvedVariable = Regex("""\$\{[^}]+}""")
    private val memoryFlag = Regex("""^-Xm[sx].*""")

    /**
     * Builds a Java launcher request but does not invoke it. A valid request is
     * necessary, not sufficient: Minecraft still needs Android-compatible
     * LWJGL/GLFW natives and a renderer/input bridge before the game can run.
     */
    fun build(
        runtime: InstalledJavaRuntime,
        plan: MinecraftLaunchPlan,
        memory: MinecraftMemoryConfig = MinecraftMemoryConfig(),
        inheritedEnvironment: Map<String, String> = emptyMap(),
        bridgeClasspathEntries: List<File> = emptyList(),
        bridgeNativeLibraryDirectories: List<File> = emptyList(),
        openglLibraryName: String? = null
    ): MinecraftJvmLaunchSpec {
        require(runtime.majorVersion >= 21) {
            "Minecraft 1.21.11 requires Java 21 or newer; installed runtime is Java ${runtime.majorVersion}."
        }
        require(runtime.javaExecutable.isFile) {
            "Java executable does not exist: ${runtime.javaExecutable.absolutePath}"
        }
        require(plan.isComplete) {
            "Launch plan is incomplete: ${plan.missingFiles.take(3).joinToString()}"
        }
        require(plan.mainClass.isNotBlank()) { "Minecraft mainClass is missing from version metadata." }

        val unresolved = (plan.jvmArguments + plan.gameArguments)
            .flatMap { argument -> unresolvedVariable.findAll(argument).map { it.value }.toList() }
            .distinct()
        require(unresolved.isEmpty()) {
            "Launch metadata contains unresolved variables: ${unresolved.joinToString()}"
        }

        val temporaryDirectory = File(plan.gameDirectory, ".nixmc/tmp")
        check(temporaryDirectory.exists() || temporaryDirectory.mkdirs()) {
            "Could not create the game temporary directory."
        }

        val javaHome = runtime.javaExecutable.parentFile?.parentFile ?: runtime.home
        val bridgeClasspath = bridgeClasspathEntries.map { entry ->
            val canonical = entry.canonicalFile
            require(canonical.isFile) {
                "Verified bridge classpath entry is missing: ${canonical.absolutePath}"
            }
            canonical.absolutePath
        }.distinct()
        val jvmArguments = plan.jvmArguments
            .filterNot { memoryFlag.matches(it) }
            .toMutableList()

        val classpathFlags = setOf("-cp", "-classpath", "--class-path")
        val classpathFlagIndex = jvmArguments.indexOfFirst { it in classpathFlags }
        val inlineClasspathIndex = jvmArguments.indexOfFirst { it.startsWith("--class-path=") }
        fun prependBridgeClasspath(existing: String): String {
            require(existing.isNotBlank()) { "The launch metadata has an empty classpath." }
            return (bridgeClasspath + existing.split(File.pathSeparator).filter { it.isNotBlank() })
                .distinct()
                .joinToString(File.pathSeparator)
        }

        when {
            classpathFlagIndex >= 0 -> {
                require(classpathFlagIndex + 1 < jvmArguments.size) {
                    "The launch metadata has a classpath flag without a value."
                }
                jvmArguments[classpathFlagIndex + 1] =
                    prependBridgeClasspath(jvmArguments[classpathFlagIndex + 1])
            }
            inlineClasspathIndex >= 0 -> {
                val current = jvmArguments[inlineClasspathIndex].substringAfter("--class-path=")
                jvmArguments[inlineClasspathIndex] = "--class-path=${prependBridgeClasspath(current)}"
            }
            else -> {
                val fallbackClasspath = (bridgeClasspath + plan.classpath.map { it.absolutePath }).distinct()
                require(fallbackClasspath.isNotEmpty()) {
                    "The launch plan and native bridge provide no classpath entries."
                }
                jvmArguments += "-cp"
                jvmArguments += fallbackClasspath.joinToString(File.pathSeparator)
            }
        }

        if (jvmArguments.none { it.startsWith("-Djava.io.tmpdir=") }) {
            jvmArguments += "-Djava.io.tmpdir=${temporaryDirectory.absolutePath}"
        }
        if (jvmArguments.none { it.startsWith("-Duser.dir=") }) {
            jvmArguments += "-Duser.dir=${plan.gameDirectory.absolutePath}"
        }

        val nativeDirectories = bridgeNativeLibraryDirectories.map { directory ->
            val canonical = directory.canonicalFile
            require(canonical.isDirectory) {
                "Android native library directory is missing: ${canonical.absolutePath}"
            }
            canonical.absolutePath
        }.distinct()
        if (nativeDirectories.isNotEmpty()) {
            // Mojang's metadata points at desktop Linux native jars on Android.
            // Override those paths instead of allowing LWJGL to pick up .so files
            // compiled for glibc/Linux rather than Android's linker.
            jvmArguments.removeAll {
                it.startsWith("-Djava.library.path=") ||
                    it.startsWith("-Dorg.lwjgl.librarypath=")
            }
            val nativePath = nativeDirectories.joinToString(File.pathSeparator)
            jvmArguments += "-Djava.library.path=$nativePath"
            jvmArguments += "-Dorg.lwjgl.librarypath=$nativePath"
        }
        if (!openglLibraryName.isNullOrBlank()) {
            require(File(openglLibraryName).name == openglLibraryName && openglLibraryName.endsWith(".so")) {
                "OpenGL renderer must be specified by a shared-library filename."
            }
            jvmArguments.removeAll { it.startsWith("-Dorg.lwjgl.opengl.libname=") }
            jvmArguments += "-Dorg.lwjgl.opengl.libname=$openglLibraryName"
        }

        jvmArguments += "-Xms${memory.minimumMiB}M"
        jvmArguments += "-Xmx${memory.maximumMiB}M"

        val runtimeLibraryDirectories = listOf(
            javaHome,
            File(javaHome, "lib"),
            File(javaHome, "lib/jli"),
            File(javaHome, "lib/server"),
            File(javaHome, "lib/aarch64"),
            File(javaHome, "lib/aarch64/jli"),
            File(javaHome, "lib/aarch64/server"),
            File(javaHome, "lib/arm"),
            File(javaHome, "lib/arm/jli"),
            File(javaHome, "lib/arm/server"),
            File(javaHome, "lib/x86"),
            File(javaHome, "lib/x86/jli"),
            File(javaHome, "lib/x86/server"),
            File(javaHome, "lib/x86_64"),
            File(javaHome, "lib/x86_64/jli"),
            File(javaHome, "lib/x86_64/server")
        ).filter { it.isDirectory }.map { it.absolutePath }

        val nativeDirectory = File(plan.gameDirectory, "natives/${plan.version}")
        val inheritedLibraryPath = inheritedEnvironment["LD_LIBRARY_PATH"]
            ?.takeIf { it.isNotBlank() }
        val libraryPath = (runtimeLibraryDirectories + nativeDirectory.absolutePath +
            listOfNotNull(inheritedLibraryPath))
            .distinct()
            .joinToString(File.pathSeparator)

        val environment = inheritedEnvironment.toMutableMap().apply {
            put("JAVA_HOME", javaHome.absolutePath)
            put("HOME", plan.gameDirectory.absolutePath)
            put("TMPDIR", temporaryDirectory.absolutePath)
            put("PATH", listOf(
                File(javaHome, "bin").absolutePath,
                inheritedEnvironment["PATH"].orEmpty()
            ).filter { it.isNotBlank() }.joinToString(File.pathSeparator))
            put("LD_LIBRARY_PATH", libraryPath)
        }

        return MinecraftJvmLaunchSpec(
            javaHome = javaHome.absoluteFile,
            javaExecutable = runtime.javaExecutable.absoluteFile,
            jvmArguments = jvmArguments,
            mainClass = plan.mainClass,
            gameArguments = plan.gameArguments,
            environment = environment,
            workingDirectory = plan.gameDirectory.absoluteFile
        )
    }

    private fun hasExplicitClasspath(arguments: List<String>): Boolean {
        val classpathFlags = setOf("-cp", "-classpath", "--class-path")
        return arguments.indices.any { index ->
            val argument = arguments[index]
            argument in classpathFlags || argument.startsWith("--class-path=")
        }
    }
}
