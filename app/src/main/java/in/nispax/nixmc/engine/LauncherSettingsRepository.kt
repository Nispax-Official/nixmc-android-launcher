package `in`.nispax.nixmc.engine

import android.content.Context

object MemorySettingsPolicy {
    fun normalize(requestedMiB: Int): Int = requestedMiB.coerceIn(512, 4096)
    fun defaultMaxMemoryMiB(): Int = 2048
}

class LauncherSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("nixmc_launcher_settings", Context.MODE_PRIVATE)

    fun loadMaxMemoryMiB(): Int = MemorySettingsPolicy.normalize(
        preferences.getInt("max_memory_mib", MemorySettingsPolicy.defaultMaxMemoryMiB())
    )

    fun saveMaxMemoryMiB(requestedMiB: Int): Int {
        val value = MemorySettingsPolicy.normalize(requestedMiB)
        preferences.edit().putInt("max_memory_mib", value).apply()
        return value
    }

    fun loadMemoryConfig(): MinecraftMemoryConfig = MinecraftMemoryConfig(512, loadMaxMemoryMiB())

    fun preferredRendererId(): String = preferences.getString("preferred_renderer_id", "").orEmpty()

    fun savePreferredRendererId(rendererId: String, installedIds: Set<String>): Boolean {
        if (rendererId.isNotBlank() && rendererId !in installedIds) return false
        preferences.edit().putString("preferred_renderer_id", rendererId).apply()
        return true
    }
}