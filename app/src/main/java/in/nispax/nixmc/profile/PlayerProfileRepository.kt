package in.nispax.nixmc.profile

import android.content.Context

/**
 * Validates Java Edition-compatible player names for local offline profiles.
 *
 * A stored offline name is only a client-side profile label; it does not prove
 * account ownership or authenticate a player to an online-mode server.
 */
object PlayerNameValidator {
    fun errorFor(rawName: String): String? {
        if (rawName.isBlank()) return "Enter a player name."
        if (rawName != rawName.trim()) return "Remove spaces at the start or end."
        if (rawName.length !in 3..16) return "Player names must be 3–16 characters long."
        if (!rawName.matches(Regex("[A-Za-z0-9_]+"))) {
            return "Use only English letters, numbers, and underscores."
        }
        return null
    }
}

/**
 * Small persistence boundary for the active local profile.
 *
 * SharedPreferences keeps the first profile milestone dependency-free. The
 * repository can later be replaced by a richer profile store without coupling
 * UI components to Android storage details.
 */
class PlayerProfileRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun loadUsername(): String {
        val savedName = preferences.getString(KEY_USERNAME, null)
        return savedName?.takeIf { PlayerNameValidator.errorFor(it) == null }
            ?: DEFAULT_USERNAME
    }

    fun saveUsername(username: String): Boolean {
        if (PlayerNameValidator.errorFor(username) != null) return false
        preferences.edit().putString(KEY_USERNAME, username).apply()
        return true
    }

    private companion object {
        const val PREFERENCES_NAME = "nixmc_profile"
        const val KEY_USERNAME = "player_name"
        const val DEFAULT_USERNAME = "NixPlayer"
    }
}
