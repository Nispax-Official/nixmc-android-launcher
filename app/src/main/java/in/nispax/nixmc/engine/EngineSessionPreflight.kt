package `in`.nispax.nixmc.engine

import android.content.Context
import android.os.Build
import `in`.nispax.nixmc.install.AndroidJavaRuntimeInstaller
import `in`.nispax.nixmc.install.InstalledJavaRuntime
import `in`.nispax.nixmc.install.MinecraftLaunchPlan
import `in`.nispax.nixmc.install.MinecraftLaunchPlanner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EngineSessionPreflightResult(
    val runtime: InstalledJavaRuntime?,
    val launchPlan: MinecraftLaunchPlan?,
    val bridgeReport: EngineBridgeReport?,
    val selectedRenderer: BridgeRenderer?,
    val blockers: List<String>
) {
    val isReady: Boolean get() = blockers.isEmpty() &&
        runtime != null && launchPlan?.isComplete == true &&
        bridgeReport?.isReady == true && selectedRenderer != null
}

/**
 * Performs every read-only prerequisite check before the native launcher maps
 * an engine library. This makes launch decisions reproducible and prevents UI
 * code from maintaining separate, potentially inconsistent readiness logic.
 */
class EngineSessionPreflight(context: Context) {
    private val appContext = context.applicationContext

    suspend fun evaluate(playerName: String): EngineSessionPreflightResult = withContext(Dispatchers.IO) {
        val blockers = mutableListOf<String>()
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        if (abi.isBlank()) blockers += "Android did not report a supported CPU ABI."

        val runtimeStatus = AndroidJavaRuntimeInstaller.inspectInstalled(appContext.filesDir)
        val runtime = runtimeStatus.runtime
        if (runtime == null) blockers += runtimeStatus.diagnostic

        val gameDirectory = File(appContext.filesDir, "minecraft").canonicalFile
        val architecture = mojArchFor(abi)
        val plan = if (architecture == null) {
            blockers += "Unsupported Android ABI: $abi"
            null
        } else {
            runCatching {
                MinecraftLaunchPlanner.create(
                    gameDirectory = gameDirectory,
                    playerName = playerName,
                    architecture = architecture,
                    osName = "linux"
                )
            }.getOrElse {
                blockers += "Minecraft launch plan could not be built: " +
                    (it.message ?: it.javaClass.simpleName)
                null
            }
        }
        if (plan != null && !plan.isComplete) {
            blockers += "Minecraft files are incomplete: " +
                plan.missingFiles.take(6).joinToString()
        }

        val bridgeRoot = File(gameDirectory, "engine/bridge").canonicalFile
        val bridge = if (abi.isBlank()) {
            null
        } else {
            EngineBridgeVerifier.inspect(
                bridgeRoot = bridgeRoot,
                androidAbi = abi,
                javaMajorVersion = runtime?.majorVersion
            )
        }
        if (bridge != null && !bridge.isReady) blockers += bridge.blockers.take(6)

        val settings = LauncherSettingsRepository(appContext)
        val preferredId = settings.preferredRendererId()
        val renderer = bridge?.availableRenderers?.firstOrNull { it.id == preferredId }
            ?: bridge?.availableRenderers?.firstOrNull()
        if (bridge?.isReady == true && renderer == null) {
            blockers += "The verified engine bridge does not provide a renderer."
        }

        EngineSessionPreflightResult(
            runtime = runtime,
            launchPlan = plan,
            bridgeReport = bridge,
            selectedRenderer = renderer,
            blockers = blockers.distinct()
        )
    }

    private fun mojArchFor(androidAbi: String): String? = when (androidAbi) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> null
    }
}
