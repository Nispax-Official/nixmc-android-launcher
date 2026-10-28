package `in`.nispax.nixmc.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class MemorySettingsPolicyTest {
    @Test
    fun clampsValuesToSupportedHeapRange() {
        assertEquals(512, MemorySettingsPolicy.normalize(128))
        assertEquals(512, MemorySettingsPolicy.normalize(512))
        assertEquals(4096, MemorySettingsPolicy.normalize(8192))
    }

    @Test
    fun defaultsToAConservativeTwoGigabyteHeap() {
        assertEquals(2048, MemorySettingsPolicy.defaultMaxMemoryMiB())
    }
}
