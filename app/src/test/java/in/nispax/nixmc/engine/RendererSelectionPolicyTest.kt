package `in`.nispax.nixmc.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererSelectionPolicyTest {
    private val gl4es = BridgeRenderer("gl4es", "Compatibility renderer", "lib/arm64-v8a/libgl4es.so", emptyMap())
    private val mobile = BridgeRenderer("mobileglues", "MobileGLues", "lib/arm64-v8a/libmobileglues.so", emptyMap())

    @Test
    fun prefersConservativeKnownRendererOnlyWhenItIsInstalled() {
        val result = RendererSelectionPolicy.select(listOf(mobile, gl4es))
        assertTrue(result.isReady)
        assertEquals("gl4es", result.renderer?.id)
    }

    @Test
    fun respectsExplicitInstalledRendererChoice() {
        val result = RendererSelectionPolicy.select(listOf(gl4es, mobile), "mobileglues")
        assertTrue(result.isReady)
        assertEquals("mobileglues", result.renderer?.id)
    }

    @Test
    fun doesNotInventMissingRenderer() {
        val result = RendererSelectionPolicy.select(listOf(gl4es), "nonexistent")
        assertFalse(result.isReady)
        assertTrue(result.blockers.single().contains("not provided"))
    }

    @Test
    fun refusesDuplicateRendererIds() {
        val result = RendererSelectionPolicy.select(listOf(gl4es, gl4es))
        assertFalse(result.isReady)
        assertTrue(result.blockers.single().contains("duplicate"))
    }

    @Test
    fun emptyBridgeRendererSetIsAReadinessBlocker() {
        val result = RendererSelectionPolicy.select(emptyList())
        assertFalse(result.isReady)
    }
}
