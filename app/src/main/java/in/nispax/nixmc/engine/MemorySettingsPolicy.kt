package `in`.nispax.nixmc.settings

object MemorySettingsPolicy {
    fun normalize(requested: Int, maximum: Int): Int = requested.coerceIn(512, maximum.coerceIn(512, 8192))
}
