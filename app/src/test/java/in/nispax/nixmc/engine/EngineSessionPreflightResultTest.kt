package `in`.nispax.nixmc.engine

import `in`.nispax.nixmc.install.InstalledJavaRuntime
import `in`.nispax.nixmc.install.MinecraftLaunchPlan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineSessionPreflightResultTest {
    private val runtime = InstalledJavaRuntime(
        home = File("/tmp/java"),
        javaExecutable = File("/tmp/java/bin/java"),
        majorVersion = 21
    )
    private val completePlan = MinecraftLaunchPlan(
        version = "1.21.11",
        mainClass = "net.minecraft.client.main.Main",
        classpath = listOf(File("/tmp/client.jar")),
        jvmArguments = emptyList(),
        gameArguments = emptyList(),
        assetIndex = "31",
        gameDirectory = File("/tmp/minecraft"),
        missingFiles = emptyList()
    )
    private val renderer = BridgeRenderer("gl4es", "Compatibility", "lib/arm64-v8a/libgl4es.so", emptyMap())
    private val bridgeReport = EngineBridgeReport(
        bridgeRoot = File("/tmp/bridge"),
        androidAbi = "arm64-v8a",
        bridgeVersion = "test",
        availableRenderers = listOf(renderer),
        blockers = emptyList(),
        warnings = emptyList()
    )

    @Test
    fun readyRequiresEveryPrerequisiteAndASelectedRenderer() {
        val result = EngineSessionPreflightResult(
            runtime, completePlan, bridgeReport, renderer, emptyList()
        )
        assertTrue(result.isReady)
    }

    @Test
    fun anyBlockerPreventsLaunch() {
        val result = EngineSessionPreflightResult(
            runtime, completePlan, bridgeReport, renderer, listOf("Native bridge missing")
        )
        assertFalse(result.isReady)
    }

    @Test
    fun missingRuntimePreventsLaunch() {
        val result = EngineSessionPreflightResult(
            null, completePlan, bridgeReport, renderer, listOf("Java runtime missing")
        )
        assertFalse(result.isReady)
    }

    @Test
    fun incompletePlanPreventsLaunchEvenIfTheBlockerListWasMiscomputed() {
        val incomplete = completePlan.copy(missingFiles = listOf("client jar missing"))
        val result = EngineSessionPreflightResult(
            runtime, incomplete, bridgeReport, renderer, emptyList()
        )
        assertFalse(result.isReady)
    }

    @Test
    fun noRendererPreventsLaunch() {
        val result = EngineSessionPreflightResult(
            runtime, completePlan, bridgeReport, null, emptyList()
        )
        assertFalse(result.isReady)
    }
}
