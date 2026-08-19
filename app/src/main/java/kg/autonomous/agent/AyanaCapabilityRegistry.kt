package kg.autonomous.agent

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * AYANA Device Capability Registry v1.0.
 *
 * Machine-readable source of truth about what is implemented in this build and
 * what is actually available on the current device right now. This separates
 * "implemented" from "runtime available" and from "device-confirmed".
 */
class AyanaCapabilityRegistry(
    context: Context,
    private val appResolver: AyanaAppResolver
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordAgentCoreResult(
        success: Boolean,
        latencyMs: Long,
        error: String = ""
    ) {
        prefs.edit()
            .putBoolean(KEY_AGENT_CORE_OK, success)
            .putLong(KEY_AGENT_CORE_AT, System.currentTimeMillis())
            .putLong(KEY_AGENT_CORE_LATENCY, latencyMs.coerceAtLeast(0L))
            .putString(KEY_AGENT_CORE_ERROR, error.take(400))
            .apply()
    }

    fun recordTtsResult(
        success: Boolean,
        firstByteMs: Long = -1L,
        error: String = ""
    ) {
        prefs.edit()
            .putBoolean(KEY_TTS_OK, success)
            .putLong(KEY_TTS_AT, System.currentTimeMillis())
            .putLong(KEY_TTS_FIRST_BYTE, firstByteMs)
            .putString(KEY_TTS_ERROR, error.take(400))
            .apply()
    }

    fun recordRecognitionReady(
        ready: Boolean
    ) {
        prefs.edit()
            .putBoolean(KEY_STT_READY, ready)
            .putLong(KEY_STT_AT, System.currentTimeMillis())
            .apply()
    }

    fun snapshot(): JSONObject {
        val appCount = try {
            appResolver.listLaunchableApps().size
        } catch (_: Exception) {
            -1
        }

        val memoryCount = try {
            AyanaMemoryStore(appContext).count()
        } catch (_: Exception) {
            -1
        }

        val reminderCount = try {
            AyanaTaskStore(appContext).count()
        } catch (_: Exception) {
            -1
        }

        val goalViews = try {
            AyanaDurableGoalStore(appContext).getRecoverableViews(20)
        } catch (_: Exception) {
            emptyList()
        }

        val exactAlarmAllowed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarm = appContext.getSystemService(AlarmManager::class.java)
                alarm?.canScheduleExactAlarms() == true
            } else {
                true
            }
        } catch (_: Exception) {
            false
        }

        val notificationPermission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        val microphonePermission =
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        val overlayAllowed = try {
            Settings.canDrawOverlays(appContext)
        } catch (_: Exception) {
            false
        }

        val accessibilityConnected = AgentAccessibilityService.instance != null

        val runtime = JSONObject()
            .put("microphone_permission", microphonePermission)
            .put("overlay_permission", overlayAllowed)
            .put("accessibility_connected", accessibilityConnected)
            .put("notification_permission", notificationPermission)
            .put("exact_alarm_permission", exactAlarmAllowed)
            .put("voice_service_running", AyanaVoiceService.isRunning)
            .put("stt_ready_last", prefs.getBoolean(KEY_STT_READY, false))
            .put("stt_ready_at", prefs.getLong(KEY_STT_AT, 0L))
            .put("agent_core_last_ok", prefs.getBoolean(KEY_AGENT_CORE_OK, false))
            .put("agent_core_last_at", prefs.getLong(KEY_AGENT_CORE_AT, 0L))
            .put("agent_core_last_latency_ms", prefs.getLong(KEY_AGENT_CORE_LATENCY, -1L))
            .put("agent_core_last_error", prefs.getString(KEY_AGENT_CORE_ERROR, "").orEmpty())
            .put("tts_last_ok", prefs.getBoolean(KEY_TTS_OK, false))
            .put("tts_last_at", prefs.getLong(KEY_TTS_AT, 0L))
            .put("tts_first_byte_ms", prefs.getLong(KEY_TTS_FIRST_BYTE, -1L))
            .put("tts_last_error", prefs.getString(KEY_TTS_ERROR, "").orEmpty())
            .put("launchable_app_count", appCount)
            .put("memory_count", memoryCount)
            .put("reminder_count", reminderCount)
            .put("recoverable_goal_count", goalViews.size)

        val capabilities = JSONArray()
        capability(
            capabilities,
            "voice_wake_and_tts",
            implemented = true,
            available = microphonePermission,
            deviceConfirmed = true,
            note = "Marin streaming + voice STOP baseline retained"
        )
        capability(
            capabilities,
            "voice_stop_during_speaking",
            implemented = true,
            available = microphonePermission,
            deviceConfirmed = true,
            note = "VOICE_COMMUNICATION/AEC/NS"
        )
        capability(
            capabilities,
            "dynamic_app_resolver",
            implemented = true,
            available = appCount > 0,
            deviceConfirmed = false,
            note = "v11 App Resolver v2; device test required"
        )
        capability(
            capabilities,
            "screen_intelligence",
            implemented = true,
            available = accessibilityConnected,
            deviceConfirmed = true,
            note = "Accessibility semantic screen model"
        )
        capability(
            capabilities,
            "durable_goals",
            implemented = true,
            available = true,
            deviceConfirmed = true,
            note = "checkpoints/recovery/strict verification"
        )
        capability(
            capabilities,
            "multi_goal_management",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "v11 multiple recoverable goals; device test required"
        )
        capability(
            capabilities,
            "planner_v2",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "local planning envelope + explicit subgoal/terminal context"
        )
        capability(
            capabilities,
            "local_safety_engine",
            implemented = true,
            available = true,
            deviceConfirmed = true,
            note = "fail-closed v1.1 base device-confirmed; retained in v1.2"
        )
        capability(
            capabilities,
            "safe_memory_write_v1_2",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "v11 blocks secrets/payment data before memory persistence; device test required"
        )
        capability(
            capabilities,
            "memory_v2",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "provenance/edit/conflict candidates"
        )
        capability(
            capabilities,
            "task_management_v2",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "edit/enable/disable/reschedule existing reminders"
        )
        capability(
            capabilities,
            "self_diagnostics_v2",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "runtime capability and health registry"
        )
        capability(
            capabilities,
            "vision",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "planned after Agent Intelligence Core"
        )
        capability(
            capabilities,
            "external_mail_calendar_files",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "planned later with scoped permissions"
        )
        capability(
            capabilities,
            "offline_llm",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "planned later"
        )
        capability(
            capabilities,
            "controlled_proactivity",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "planned later"
        )

        return JSONObject()
            .put("success", true)
            .put("build", BUILD_LABEL)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("runtime", runtime)
            .put("capabilities", capabilities)
            .put("generated_at", System.currentTimeMillis())
    }

    fun compactContext(): String {
        val snapshot = snapshot()
        val runtime = snapshot.optJSONObject("runtime") ?: JSONObject()
        return buildString {
            append("AYANA Device Capability Registry: build=")
            append(BUILD_LABEL)
            append("; apps=")
            append(runtime.optInt("launchable_app_count", -1))
            append("; accessibility=")
            append(runtime.optBoolean("accessibility_connected", false))
            append("; microphone=")
            append(runtime.optBoolean("microphone_permission", false))
            append("; overlay=")
            append(runtime.optBoolean("overlay_permission", false))
            append("; agent_core_last_ok=")
            append(runtime.optBoolean("agent_core_last_ok", false))
            append("; agent_core_ms=")
            append(runtime.optLong("agent_core_last_latency_ms", -1L))
            append("; tts_first_byte_ms=")
            append(runtime.optLong("tts_first_byte_ms", -1L))
            append("; recoverable_goals=")
            append(runtime.optInt("recoverable_goal_count", 0))
            append("; memory=")
            append(runtime.optInt("memory_count", 0))
            append("; reminders=")
            append(runtime.optInt("reminder_count", 0))
            append(". Implemented v11: dynamic_app_resolver, planner_v2, multi_goal_management, memory_v2, task_management_v2, self_diagnostics_v2. Not implemented yet: vision, external integrations, offline_llm, controlled_proactivity.")
        }
    }

    private fun capability(
        array: JSONArray,
        id: String,
        implemented: Boolean,
        available: Boolean,
        deviceConfirmed: Boolean,
        note: String
    ) {
        array.put(
            JSONObject()
                .put("id", id)
                .put("implemented", implemented)
                .put("available_now", available)
                .put("device_confirmed", deviceConfirmed)
                .put("note", note)
        )
    }

    companion object {
        const val BUILD_LABEL = "AYANA v11.0 AGENT INTELLIGENCE CORE"

        private const val PREFS_NAME = "ayana_capability_runtime_v11"
        private const val KEY_AGENT_CORE_OK = "agent_core_ok"
        private const val KEY_AGENT_CORE_AT = "agent_core_at"
        private const val KEY_AGENT_CORE_LATENCY = "agent_core_latency"
        private const val KEY_AGENT_CORE_ERROR = "agent_core_error"
        private const val KEY_TTS_OK = "tts_ok"
        private const val KEY_TTS_AT = "tts_at"
        private const val KEY_TTS_FIRST_BYTE = "tts_first_byte"
        private const val KEY_TTS_ERROR = "tts_error"
        private const val KEY_STT_READY = "stt_ready"
        private const val KEY_STT_AT = "stt_at"
    }
}
