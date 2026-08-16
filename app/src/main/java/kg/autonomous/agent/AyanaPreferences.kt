package kg.autonomous.agent

import android.content.Context

class AyanaPreferences(
    context: Context
) {

    enum class AutonomyLevel(
        val value: String
    ) {
        SAFE("safe"),
        BALANCED("balanced"),
        HIGH("high")
    }

    private val prefs =
        context
            .applicationContext
            .getSharedPreferences(
                FILE_NAME,
                Context.MODE_PRIVATE
            )

    var miniOrbEnabled: Boolean
        get() =
            prefs.getBoolean(
                KEY_MINI_ORB,
                false
            )
        set(value) {
            prefs.edit()
                .putBoolean(
                    KEY_MINI_ORB,
                    value
                )
                .apply()
        }

    var silentMode: Boolean
        get() =
            prefs.getBoolean(
                KEY_SILENT_MODE,
                false
            )
        set(value) {
            prefs.edit()
                .putBoolean(
                    KEY_SILENT_MODE,
                    value
                )
                .apply()
        }

    var proactiveMode: Boolean
        get() =
            prefs.getBoolean(
                KEY_PROACTIVE_MODE,
                true
            )
        set(value) {
            prefs.edit()
                .putBoolean(
                    KEY_PROACTIVE_MODE,
                    value
                )
                .apply()
        }

    var bootActivationPromptEnabled: Boolean
        get() =
            prefs.getBoolean(
                KEY_BOOT_PROMPT,
                true
            )
        set(value) {
            prefs.edit()
                .putBoolean(
                    KEY_BOOT_PROMPT,
                    value
                )
                .apply()
        }

    var autonomyLevel: AutonomyLevel
        get() {

            val raw =
                prefs.getString(
                    KEY_AUTONOMY_LEVEL,
                    AutonomyLevel.BALANCED.value
                )
                    ?: AutonomyLevel.BALANCED.value

            return AutonomyLevel
                .entries
                .firstOrNull {
                    it.value == raw
                }
                ?: AutonomyLevel.BALANCED
        }
        set(value) {
            prefs.edit()
                .putString(
                    KEY_AUTONOMY_LEVEL,
                    value.value
                )
                .apply()
        }

    companion object {

        private const val FILE_NAME =
            "ayana_preferences"

        private const val KEY_MINI_ORB =
            "mini_orb_enabled"

        private const val KEY_SILENT_MODE =
            "silent_mode"

        private const val KEY_PROACTIVE_MODE =
            "proactive_mode"

        private const val KEY_BOOT_PROMPT =
            "boot_activation_prompt"

        private const val KEY_AUTONOMY_LEVEL =
            "autonomy_level"
    }
}
