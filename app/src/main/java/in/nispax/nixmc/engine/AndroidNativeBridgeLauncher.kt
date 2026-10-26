package `in`.nispax.nixmc.engine

import android.content.Context
import android.system.Os
import android.view.Surface
import com.oracle.dalvik.VMLauncher
import `in`.nispax.nixmc.install.InstalledJavaRuntime
import `in`.nispax.nixmc.install.MinecraftLaunchPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kdt.pojavlaunch.utils.JREUtils
import java.io.File

data class NativeBridgeLaunchResult(
    val bridgeVersion: String,
    val rendererId: String,
    val exitCode: Int,
    val elapsedMillis: Long,
    val loadedNativeLibraries: List<String>
)

/**
 * Invokes the Android-compatible Java launcher in-process through the verified
 * JNI bridge. The patched LWJGL JARs from the bridge manifest are prepended to
 * Minecraft's classpath so their GLFW wrappers take precedence over desktop
 * implementations. Call from a background coroutine, never the main thread.
 *
 * A compatible bridge package and a valid game surface are required; neither
 * is currently bundled in NixMC, so this entrypoint fails closed until those
 * dependencies are installed.
 */
class AndroidNativeBridgeLauncher(context: Context) {
    private val applicationContext = context.applicationContext

    suspend fun launch(
        bridgeRoot: File,
        androidAbi: String,
        runtime: InstalledJavaRuntime,
        plan: MinecraftLaunchPlan,
        selectedRendererId: String,
        surface: Surface,
        memory: MinecraftMemoryConfig = MinecraftMemoryConfig()
    ): NativeBridgeLaunchResult = withContext(Dispatchers.IO) {
        require(surface.isValid) { "The Android game surface is not valid." }
        require(runtime.majorVersion >= 21) { "Minecraft 1.21.11 requires Java 21 or newer." }
        require(runtime.javaExecutable.isFile) { "The installed Java executable is missing." }

        val report = EngineBridgeVerifier.inspect(
            bridgeRoot = bridgeRoot,
            androidAbi = androidAbi,
            javaMajorVersion = runtime.majorVersion,
            selectedRendererId = selectedRendererId
        )
        check(report.isReady) {
            "Native engine bridge is not ready: ${report.blockers.take(5).joinToString()}"
        }

        val root = bridgeRoot.canonicalFile
        val manifest = EngineBridgeVerifier.readManifest(root)
        check(manifest.launchEntryClass == VMLauncher::class.java.name &&
            manifest.launchEntryMethod == "launchJVM"
        ) {
            "The installed bridge entry point is not compatible with this NixMC build."
        }

        val verifiedPaths = manifest.files.map { it.relativePath }.toSet()
        check(manifest.launchEntryLibrary in manifest.nativeLoadOrder) {
            "The configured launch entry library is not in the native load order."
        }
        check(verifiedPaths.containsAll(manifest.classpathEntries)) {
            "The bridge classpath contains files which were not verified by the manifest."
        }

        val selectedRenderer = manifest.renderers.singleOrNull { it.id == selectedRendererId }
            ?: error("Selected renderer is missing or duplicated in the bridge manifest.")
        val rendererLibrary = File(root, selectedRenderer.library).canonicalFile
        check(rendererLibrary.isFile && rendererLibrary.path.startsWith(root.path + File.separator)) {
            "Selected renderer library is missing or escapes the bridge directory."
        }

        val bridgeClasspath = manifest.classpathEntries.map { relative ->
            val file = File(root, relative).canonicalFile
            check(file.isFile && file.path.startsWith(root.path + File.separator)) {
                "Bridge classpath file is missing or escapes its directory: $relative"
            }
            file
        }
        val bridgeNativeDirectory = File(root, "lib/$androidAbi").canonicalFile
        check(bridgeNativeDirectory.isDirectory && bridgeNativeDirectory.path.startsWith(root.path + File.separator)) {
            "Android-native bridge library directory is missing."
        }
        val launchSpec = MinecraftLaunchCommandBuilder.build(
            runtime = runtime,
            plan = plan,
            memory = memory,
            inheritedEnvironment = System.getenv(),
            bridgeClasspathEntries = bridgeClasspath,
            bridgeNativeLibraryDirectories = listOf(bridgeNativeDirectory),
            openglLibraryName = rendererLibrary.name
        )

        val librariesInLoadOrder = manifest.nativeLoadOrder.map { relative ->
            val file = File(root, relative).canonicalFile
            check(file.isFile && file.path.startsWith(root.path + File.separator)) {
                "Native bridge file is missing or escapes its directory: $relative"
            }
            file
        }
        val entryLibrary = File(root, manifest.launchEntryLibrary).canonicalFile
        check(librariesInLoadOrder.any { it == entryLibrary }) {
            "The launch entry library is missing from the verified native load order."
        }

        val bridgeLibraryDirectories = librariesInLoadOrder
            .mapNotNull { it.parentFile?.absolutePath }
            .distinct()
        val environment = launchSpec.environment.toMutableMap().apply {
            put("NIXMC_RENDERER", selectedRendererId)
            put("LOCAL_RENDERER", selectedRendererId)
            put("AMETHYST_RENDERER", selectedRendererId)
            selectedRenderer.environment.forEach { (key, value) -> put(key, value) }
            val existing = get("LD_LIBRARY_PATH").orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
            put(
                "LD_LIBRARY_PATH",
                (bridgeLibraryDirectories + existing).distinct().joinToString(File.pathSeparator)
            )
        }

        val invalidEnvironmentKey = environment.keys.firstOrNull {
            !it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
        }
        require(invalidEnvironmentKey == null) {
            "Invalid native environment variable name: $invalidEnvironmentKey"
        }
        require(environment.values.none { it.contains(0.toChar()) }) {
            "Native environment values must not contain NUL characters."
        }

        val originalEnvironment = environment.keys.associateWith { System.getenv(it) }
        val originalUserDir = System.getProperty("user.dir")?.takeIf { it.isNotBlank() } ?: "/"
        val loaded = mutableListOf<String>()
        var surfaceAttached = false
        val startNanos = System.nanoTime()

        try {
            environment.forEach { (key, value) -> Os.setenv(key, value, true) }
            // libpojavexec's JNI_OnLoad looks up this Android-side class and
            // caches its static method IDs before initializing the runtime JVM.
            Class.forName("org.lwjgl.glfw.CallbackBridge", true, AndroidNativeBridgeLauncher::class.java.classLoader)

            // The bridge must load dependencies in manifest order. The manifest
            // and every file hash are checked before any shared object is mapped.
            for (library in librariesInLoadOrder) {
                JREUtils.loadNativeLibrary(library.absolutePath)
                loaded += library.absolutePath
            }

            JREUtils.setLdLibraryPath(environment["LD_LIBRARY_PATH"].orEmpty())

            // Bootstrap the installed Android JRE in-process. The Android
            // runtime's libjli/libjvm must be mapped before JLI is invoked.
            val runtimeLibraries = JavaRuntimeLibraryResolver.resolve(
                launchSpec.javaHome,
                androidAbi
            )
            check(runtimeLibraries.isReady) {
                "Java runtime native libraries are incomplete: ${runtimeLibraries.blockers.joinToString()}"
            }
            for (library in runtimeLibraries.orderedLibraries) {
                check(JREUtils.dlopen(library.absolutePath)) {
                    "Android linker failed to load Java runtime library: ${library.absolutePath}"
                }
                loaded += library.absolutePath
            }

            // Renderer initialization happens only after the custom Android
            // bridge and Java runtime libraries have been mapped.
            check(JREUtils.dlopen(rendererLibrary.absolutePath)) {
                "Android linker failed to load renderer ${selectedRenderer.id} (${rendererLibrary.name})."
            }
            loaded += rendererLibrary.absolutePath

            JREUtils.setupExitMethod(applicationContext)
            JREUtils.initializeHooks()

            val chdirResult = JREUtils.chdir(launchSpec.workingDirectory.absolutePath)
            check(chdirResult == 0) {
                "The native bridge could not switch to the Minecraft game directory (code $chdirResult)."
            }

            JREUtils.setupBridgeWindow(surface)
            surfaceAttached = true

            val exitCode = VMLauncher.launchJVM(launchSpec.launcherArguments.toTypedArray())
            NativeBridgeLaunchResult(
                bridgeVersion = manifest.bridgeVersion,
                rendererId = selectedRendererId,
                exitCode = exitCode,
                elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L,
                loadedNativeLibraries = loaded.toList()
            )
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Native Minecraft bridge invocation failed after loading ${loaded.size} library/libraries: ${error.message ?: error.javaClass.simpleName}",
                error
            )
        } finally {
            if (surfaceAttached) runCatching { JREUtils.releaseBridgeWindow() }
            runCatching { JREUtils.chdir(originalUserDir) }
            originalEnvironment.forEach { (key, value) ->
                runCatching {
                    if (value == null) Os.unsetenv(key) else Os.setenv(key, value, true)
                }
            }
        }
    }
}
