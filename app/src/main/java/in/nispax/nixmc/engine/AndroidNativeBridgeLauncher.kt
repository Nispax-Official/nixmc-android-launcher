package `in`.nispax.nixmc.engine

import android.content.Context
import android.system.Os
import android.view.Surface
import `in`.nispax.nixmc.install.InstalledJavaRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kdt.pojavlaunch.utils.JREUtils
import com.oracle.dalvik.VMLauncher
import java.io.File

data class NativeBridgeLaunchResult(
    val bridgeVersion: String,
    val rendererId: String,
    val exitCode: Int,
    val elapsedMillis: Long,
    val loadedNativeLibraries: List<String>
)

/**
 * Invokes a verified Android Minecraft bridge through the in-process Java
 * launcher (JLI). This is deliberately not wired to the UI until a real,
 * trusted bridge bundle and compatible renderer have been installed.
 *
 * Native JLI runs synchronously until the game JVM exits. Invoke this from a
 * background coroutine/thread, never from the Android main thread.
 */
class AndroidNativeBridgeLauncher(context: Context) {
    private val applicationContext = context.applicationContext

    suspend fun launch(
        bridgeRoot: File,
        androidAbi: String,
        runtime: InstalledJavaRuntime,
        launchSpec: MinecraftJvmLaunchSpec,
        selectedRendererId: String,
        surface: Surface
    ): NativeBridgeLaunchResult = withContext(Dispatchers.IO) {
        require(surface.isValid) { "The Android game surface is not valid." }
        require(runtime.majorVersion >= 21) { "Minecraft 1.21.11 requires Java 21 or newer." }
        require(runtime.javaExecutable.canonicalFile == launchSpec.javaExecutable.canonicalFile) {
            "The launch specification was prepared for a different Java executable."
        }

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

        val verifiedPaths = manifest.files.associate { it.relativePath to it.sha256 }
        val librariesInLoadOrder = manifest.nativeLoadOrder.map { relative ->
            val file = File(root, relative).canonicalFile
            check(file.isFile && file.path.startsWith(root.path + File.separator)) {
                "Native bridge file is missing or escapes its directory: $relative"
            }
            file
        }
        check(librariesInLoadOrder.any { it.absolutePath == File(root, manifest.launchEntryLibrary).canonicalPath }) {
            "The configured launch entry library is not in the native load order."
        }
        check(verifiedPaths.keys.containsAll(manifest.classpathEntries)) {
            "The bridge classpath contains files which were not verified by the manifest."
        }

        val bridgeLibraryDirectories = librariesInLoadOrder
            .mapNotNull { it.parentFile?.absolutePath }
            .distinct()
        val environment = launchSpec.environment.toMutableMap().apply {
            put("NIXMC_RENDERER", selectedRendererId)
            put("LOCAL_RENDERER", selectedRendererId)
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
        require(environment.values.none { it.contains('\u0000') }) {
            "Native environment values must not contain NUL characters."
        }

        val originalEnvironment = environment.keys.associateWith { System.getenv(it) }
        val originalUserDir = System.getProperty("user.dir")?.takeIf { it.isNotBlank() } ?: "/"
        val loaded = mutableListOf<String>()
        var surfaceAttached = false
        val startNanos = System.nanoTime()

        try {
            environment.forEach { (key, value) -> Os.setenv(key, value, true) }
            JREUtils.setLdLibraryPath(environment["LD_LIBRARY_PATH"].orEmpty())

            for (library in librariesInLoadOrder) {
                JREUtils.loadNativeLibrary(library.absolutePath)
                loaded += library.absolutePath
            }

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
