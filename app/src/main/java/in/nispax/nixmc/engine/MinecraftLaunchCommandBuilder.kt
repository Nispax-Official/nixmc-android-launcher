package `in`.nispax.nixmc.engine

import `in`.nispax.nixmc.install.InstalledJavaRuntime
import `in`.nispax.nixmc.install.MinecraftLaunchPlan
import java.io.File

/**
 * User-configurable memory bounds for one Minecraft session.
 * Android 8+ devices may have very different memory budgets, so settings are
 * explicit and validated rather than silently accepting malformed JVM flags.
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
 * A fully resolved JVM invocation. This is intentionally separate from process
 * creation so the launcher can validate and display the exact command before
 * handing it to an Android-native bridge.
 */
data class MinecraftProcessSpec(
    val executable: File,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File
) {
    val command: List<String>
        get() = listOf(executable.absolutePath) + arguments
}

object MinecraftLaunchCommandBuilder {
    private val unresolvedVariable = Regex("""\$\{[^}]+}""")
    private val memoryFlag = Regex("""^-Xm[sx].*""")

    /**
     * Builds the game command but does not start it. A working command line is
     * necessary, not sufficient: Minecraft 1.21.x still needs Android-compatible
     * LWJGL/GLFW natives and a renderer/input bridge before the game can run.
     */
    fun build(
        runtime: InstalledJavaRuntime,
        plan: MinecraftLaunchPlan,
        memory: MinecraftMemoryConfig = MinecraftMemoryConfig(),
        inheritedEnvironment: Map<String, String> = emptyMap()
    ): MinecraftProcessSpec {
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
        val jvmArguments = plan.jvmArguments
            .filterNot { memoryFlag.matches(it) }
            .toMutableList()

        if (!hasExplicitClasspath(jvmArguments)) {
            require(plan.classpath.isNotEmpty()) { "The launch plan has no classpath entries." }
            jvmArguments += "-cp"
            jvmArguments += plan.classpath.joinToString(File.pathSeparator) { it.absolutePath }
        }
        if (jvmArguments.none { it.startsWith("-Djava.io.tmpdir=") }) {
            jvmArguments += "-Djava.io.tmpdir=${temporaryDirectory.absolutePath}"
        }
        jvmArguments += "-Xms${memory.minimumMiB}M"
        jvmArguments += "-Xmx${memory.maximumMiB}M"

        val runtimeLibraryDirectories = listOf(
            File(javaHome, "lib"),
            File(javaHome, "lib/server"),
            File(javaHome, "lib/aarch64"),
            File(javaHome, "lib/aarch64/server"),
            File(javaHome, "lib/arm"),
            File(javaHome, "lib/arm/server"),
            File(javaHome, "lib/x86"),
            File(javaHome, "lib/x86/server"),
            File(javaHome, "lib/x86_64"),
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
            put("TMPDIR", temporaryDirectory.absolutePath)
            put("LD_LIBRARY_PATH", libraryPath)
        }

        val arguments = jvmArguments + plan.mainClass + plan.gameArguments
        return MinecraftProcessSpec(
            executable = runtime.javaExecutable.absoluteFile,
            arguments = arguments,
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
