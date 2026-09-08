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
 * AYANA Device Capability Registry v3.0 — AUTONOMY / PERCEPTION / LATENCY TRUTH.
 *
 * Single machine-readable source of truth for:
 * 1) what this build implements;
 * 2) what is available on this device right now;
 * 3) what has actually been confirmed on the device.
 *
 * Critical negative capabilities are deliberately explicit so Agent Core can
 * never inherit generic ChatGPT abilities such as image upload/vision when the
 * AYANA Android client does not expose them.
 */
class AyanaCapabilityRegistry(
    context: Context,
    private val appResolver: AyanaAppResolver
) {

    private val appContext =
        context.applicationContext

    private val prefs =
        appContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun recordAgentCoreResult(
        success: Boolean,
        latencyMs: Long,
        error: String = ""
    ) {
        prefs.edit()
            .putBoolean(
                KEY_AGENT_CORE_OK,
                success
            )
            .putLong(
                KEY_AGENT_CORE_AT,
                System.currentTimeMillis()
            )
            .putLong(
                KEY_AGENT_CORE_LATENCY,
                latencyMs.coerceAtLeast(0L)
            )
            .putString(
                KEY_AGENT_CORE_ERROR,
                error.take(400)
            )
            .apply()
    }

    fun recordAgentCorePerformance(
        totalMs: Long,
        prepareMs: Long,
        uploadMs: Long,
        headersWaitMs: Long,
        bodyReadMs: Long,
        jsonParseMs: Long,
        requestBytes: Int,
        responseBytes: Int,
        httpCode: Int
    ) {
        prefs.edit()
            .putLong(
                KEY_AGENT_CORE_PERF_AT,
                System.currentTimeMillis()
            )
            .putLong(
                KEY_AGENT_CORE_PERF_TOTAL,
                totalMs.coerceAtLeast(0L)
            )
            .putLong(
                KEY_AGENT_CORE_PERF_PREPARE,
                prepareMs.coerceAtLeast(0L)
            )
            .putLong(
                KEY_AGENT_CORE_PERF_UPLOAD,
                uploadMs.coerceAtLeast(0L)
            )
            .putLong(
                KEY_AGENT_CORE_PERF_HEADERS_WAIT,
                headersWaitMs.coerceAtLeast(0L)
            )
            .putLong(
                KEY_AGENT_CORE_PERF_BODY_READ,
                bodyReadMs.coerceAtLeast(0L)
            )
            .putLong(
                KEY_AGENT_CORE_PERF_JSON_PARSE,
                jsonParseMs.coerceAtLeast(0L)
            )
            .putInt(
                KEY_AGENT_CORE_PERF_REQUEST_BYTES,
                requestBytes.coerceAtLeast(0)
            )
            .putInt(
                KEY_AGENT_CORE_PERF_RESPONSE_BYTES,
                responseBytes.coerceAtLeast(0)
            )
            .putInt(
                KEY_AGENT_CORE_PERF_HTTP_CODE,
                httpCode
            )
            .apply()
    }

    fun recordTtsResult(
        success: Boolean,
        firstByteMs: Long = -1L,
        error: String = ""
    ) {
        prefs.edit()
            .putBoolean(
                KEY_TTS_OK,
                success
            )
            .putLong(
                KEY_TTS_AT,
                System.currentTimeMillis()
            )
            .putLong(
                KEY_TTS_FIRST_BYTE,
                firstByteMs
            )
            .putString(
                KEY_TTS_ERROR,
                error.take(400)
            )
            .apply()
    }

    fun recordRecognitionReady(
        ready: Boolean
    ) {
        prefs.edit()
            .putBoolean(
                KEY_STT_READY,
                ready
            )
            .putLong(
                KEY_STT_AT,
                System.currentTimeMillis()
            )
            .apply()
    }

    /**
     * Store command modality separately from TTS telemetry. A text command does
     * not expect Marin and must not turn "no fresh TTS" into a voice failure.
     */
    fun recordCommandContext(
        source: String,
        ttsExpected: Boolean
    ) {
        prefs.edit()
            .putString(
                KEY_LAST_COMMAND_SOURCE,
                if (source == "voice") {
                    "voice"
                } else {
                    "text"
                }
            )
            .putBoolean(
                KEY_LAST_COMMAND_TTS_EXPECTED,
                ttsExpected
            )
            .putLong(
                KEY_LAST_COMMAND_AT,
                System.currentTimeMillis()
            )
            .apply()
    }

    /**
     * Preserve evidence from the last EXTERNAL app screen. Diagnostics launched
     * inside AYANA itself must not erase the known Chrome/Settings readability
     * problem merely because AYANA's own UI is accessible.
     */
    fun recordScreenObservation(
        snapshot: JSONObject
    ) {
        val packageName =
            snapshot
                .optString(
                    "package"
                )
                .trim()

        val contentState =
            snapshot
                .optString(
                    "primary_content_state",
                    snapshot.optString(
                        "content_status",
                        "unknown"
                    )
                )
                .ifBlank {
                    "unknown"
                }

        val readableCount =
            snapshot.optInt(
                "primary_readable_text_count",
                -1
            )

        val durationMs =
            snapshot.optLong(
                "snapshot_duration_ms",
                -1L
            )

        val editor =
            prefs.edit()
                .putString(
                    KEY_SCREEN_LAST_PACKAGE,
                    packageName
                )
                .putString(
                    KEY_SCREEN_LAST_CONTENT_STATE,
                    contentState
                )
                .putInt(
                    KEY_SCREEN_LAST_READABLE_COUNT,
                    readableCount
                )
                .putLong(
                    KEY_SCREEN_LAST_DURATION_MS,
                    durationMs
                )
                .putLong(
                    KEY_SCREEN_LAST_AT,
                    System.currentTimeMillis()
                )

        if (
            packageName.isNotBlank() &&
            packageName != appContext.packageName
        ) {
            editor
                .putString(
                    KEY_EXTERNAL_SCREEN_PACKAGE,
                    packageName
                )
                .putString(
                    KEY_EXTERNAL_SCREEN_CONTENT_STATE,
                    contentState
                )
                .putInt(
                    KEY_EXTERNAL_SCREEN_READABLE_COUNT,
                    readableCount
                )
                .putLong(
                    KEY_EXTERNAL_SCREEN_DURATION_MS,
                    durationMs
                )
                .putLong(
                    KEY_EXTERNAL_SCREEN_AT,
                    System.currentTimeMillis()
                )
        }


        editor.apply()
    }

    /**
     * Classifies the last Agent Core transport latency from measured Android-side
     * phase telemetry. This never guesses model quality; it only identifies where
     * wall-clock time was spent in the most recent HTTP transaction.
     */
    fun agentCoreLatencySnapshot(): JSONObject {

        val totalMs =
            prefs.getLong(
                KEY_AGENT_CORE_PERF_TOTAL,
                -1L
            )

        val prepareMs =
            prefs.getLong(
                KEY_AGENT_CORE_PERF_PREPARE,
                -1L
            )

        val uploadMs =
            prefs.getLong(
                KEY_AGENT_CORE_PERF_UPLOAD,
                -1L
            )

        val headersWaitMs =
            prefs.getLong(
                KEY_AGENT_CORE_PERF_HEADERS_WAIT,
                -1L
            )

        val bodyReadMs =
            prefs.getLong(
                KEY_AGENT_CORE_PERF_BODY_READ,
                -1L
            )

        val jsonParseMs =
            prefs.getLong(
                KEY_AGENT_CORE_PERF_JSON_PARSE,
                -1L
            )

        val safeTotal =
            totalMs.coerceAtLeast(0L)

        val headersSharePct =
            if (
                safeTotal > 0L &&
                headersWaitMs >= 0L
            ) {
                (
                    headersWaitMs
                        .coerceAtMost(safeTotal)
                        .toDouble() /
                        safeTotal.toDouble() *
                        100.0
                    )
                    .toInt()
                    .coerceIn(0, 100)
            } else {
                -1
            }

        val classification =
            when {
                totalMs < 0L ->
                    "NO_DATA"

                totalMs <= 3500L ->
                    "FAST"

                headersSharePct >= 75 &&
                    headersWaitMs >= 4000L ->
                    "MODEL_OR_SERVER_WAIT"

                prepareMs >= 2500L ->
                    "ANDROID_PREPARE_SLOW"

                uploadMs >= 2500L ->
                    "UPLOAD_SLOW"

                bodyReadMs >= 2500L ->
                    "RESPONSE_BODY_SLOW"

                jsonParseMs >= 1000L ->
                    "ANDROID_PARSE_SLOW"

                totalMs >= 12000L ->
                    "HIGH_LATENCY_UNCLASSIFIED"

                else ->
                    "MODERATE"
            }

        val bottleneck =
            when (classification) {
                "MODEL_OR_SERVER_WAIT" ->
                    "headers_wait"

                "ANDROID_PREPARE_SLOW" ->
                    "prepare"

                "UPLOAD_SLOW" ->
                    "upload"

                "RESPONSE_BODY_SLOW" ->
                    "body_read"

                "ANDROID_PARSE_SLOW" ->
                    "json_parse"

                "FAST" ->
                    "none"

                "NO_DATA" ->
                    "unknown"

                else ->
                    "mixed"
            }

        return JSONObject()
            .put("classification", classification)
            .put("bottleneck", bottleneck)
            .put("total_ms", totalMs)
            .put("prepare_ms", prepareMs)
            .put("upload_ms", uploadMs)
            .put("headers_wait_ms", headersWaitMs)
            .put("body_read_ms", bodyReadMs)
            .put("json_parse_ms", jsonParseMs)
            .put("headers_share_pct", headersSharePct)
            .put(
                "server_wait_dominant",
                classification == "MODEL_OR_SERVER_WAIT"
            )
    }

    /**
     * Compact local self-review input used by VoiceService without a network
     * round-trip. It contains only machine-observed/runtime capability facts.
     */
    fun selfReviewSnapshot(): JSONObject {

        val base =
            snapshot()

        val runtime =
            base.optJSONObject("runtime")
                ?: JSONObject()

        val lastLatency =
            agentCoreLatencySnapshot()

        val priorities =
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "execution_autonomy")
                        .put(
                            "status",
                            if (
                                runtime.optInt(
                                    "recoverable_goal_count",
                                    0
                                ) >= 0
                            ) {
                                "FOUNDATION_PRESENT"
                            } else {
                                "UNKNOWN"
                            }
                        )
                        .put(
                            "next",
                            "multi-step acceptance, bounded replan and whole-goal terminal truth"
                        )
                )
                .put(
                    JSONObject()
                        .put("id", "perception_fusion")
                        .put(
                            "status",
                            if (
                                runtime.optBoolean(
                                    "accessibility_connected",
                                    false
                                )
                            ) {
                                "AVAILABLE_FOR_TEST"
                            } else {
                                "BLOCKED_BY_ACCESSIBILITY"
                            }
                        )
                        .put(
                            "next",
                            "fuse Accessibility window ownership with screen evidence; live visual fallback remains separate"
                        )
                )
                .put(
                    JSONObject()
                        .put("id", "development_agent")
                        .put("status", "FOUNDATION_ONLY")
                        .put(
                            "next",
                            "authenticated repository workspace, build runner, tests, rollback and confirmed commit/push"
                        )
                )
                .put(
                    JSONObject()
                        .put("id", "external_integrations")
                        .put("status", "NOT_IMPLEMENTED")
                        .put(
                            "next",
                            "scoped mail/calendar/files executors with explicit permissions and confirmation"
                        )
                )
                .put(
                    JSONObject()
                        .put("id", "agent_core_latency")
                        .put(
                            "status",
                            lastLatency.optString(
                                "classification",
                                "NO_DATA"
                            )
                        )
                        .put(
                            "next",
                            if (
                                lastLatency.optBoolean(
                                    "server_wait_dominant",
                                    false
                                )
                            ) {
                                "prefer deterministic/local routes for self-review and device facts; keep server delay separate from Android performance"
                            } else {
                                "continue phase telemetry and optimize the measured bottleneck"
                            }
                        )
                )

        return JSONObject()
            .put("success", true)
            .put("build", BUILD_LABEL)
            .put("runtime", runtime)
            .put("latency", lastLatency)
            .put("priorities", priorities)
            .put(
                "baseline",
                JSONArray()
                    .put("wake_tts_stop")
                    .put("app_resolver")
                    .put("safety_engine")
                    .put("durable_goals")
                    .put("planner")
                    .put("memory_v2")
                    .put("tasks_v2")
                    .put("strict_terminal_verification")
                    .put("artifact_engine")
                    .put("notification_reading")
                    .put("exact_media_volume")
            )
    }

    fun snapshot(): JSONObject {

        val appCount =
            try {
                appResolver
                    .listLaunchableApps()
                    .size
            } catch (_: Exception) {
                -1
            }

        val memoryCount =
            try {
                AyanaMemoryStore(
                    appContext
                )
                    .count()
            } catch (_: Exception) {
                -1
            }

        val reminderCount =
            try {
                AyanaTaskStore(
                    appContext
                )
                    .count()
            } catch (_: Exception) {
                -1
            }

        val activeReminderCount =
            try {
                AyanaTaskStore(
                    appContext
                )
                    .getFutureTasks()
                    .size
            } catch (_: Exception) {
                -1
            }

        val goalViews =
            try {
                AyanaDurableGoalStore(
                    appContext
                )
                    .getRecoverableViews(
                        20
                    )
            } catch (_: Exception) {
                emptyList()
            }

        val exactAlarmAllowed =
            try {
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.S
                ) {
                    val alarm =
                        appContext
                            .getSystemService(
                                AlarmManager::class.java
                            )

                    alarm
                        ?.canScheduleExactAlarms() ==
                        true
                } else {
                    true
                }
            } catch (_: Exception) {
                false
            }

        val notificationPermission =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {
                appContext
                    .checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS
                    ) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        val microphonePermission =
            appContext
                .checkSelfPermission(
                    Manifest.permission.RECORD_AUDIO
                ) ==
                PackageManager.PERMISSION_GRANTED

        val notificationListenerAccess =
            AyanaNotificationListenerService
                .isAccessGranted(
                    appContext
                )

        val notificationListenerConnected =
            AyanaNotificationListenerService
                .isConnected()

        val overlayAllowed =
            try {
                Settings.canDrawOverlays(
                    appContext
                )
            } catch (_: Exception) {
                false
            }

        val writeSettingsAllowed =
            try {
                Build.VERSION.SDK_INT <
                    Build.VERSION_CODES.M ||
                    Settings.System.canWrite(
                        appContext
                    )
            } catch (_: Exception) {
                false
            }

        val accessibilityConnected =
            AgentAccessibilityService
                .instance !=
                null

        val screenSnapshot =
            try {
                AgentAccessibilityService
                    .instance
                    ?.buildScreenSnapshot(
                        maxNodes = 36,
                        maxChars = 4200
                    )
            } catch (_: Exception) {
                null
            }

        val screenVisibleCount =
            screenSnapshot
                ?.optJSONArray(
                    "visible_text"
                )
                ?.length()
                ?: -1

        val screenPrimaryContentState =
            screenSnapshot
                ?.optString(
                    "primary_content_state",
                    "unknown"
                )
                .orEmpty()
                .ifBlank {
                    "unknown"
                }

        val screenPrimaryReadableTextCount =
            screenSnapshot
                ?.optInt(
                    "primary_readable_text_count",
                    -1
                )
                ?: -1

        val screenDurationMs =
            screenSnapshot
                ?.optLong(
                    "snapshot_duration_ms",
                    -1L
                )
                ?: -1L

        val recentHistory =
            try {
                AyanaCommandHistoryStore(
                    appContext
                )
                    .recent(
                        12
                    )
            } catch (_: Exception) {
                emptyList()
            }

        val lastRecord =
            recentHistory
                .firstOrNull()

        val lastErrorRecord =
            recentHistory
                .firstOrNull {
                    it.optString(
                        "status"
                    ) ==
                        "error"
                }

        val recentErrorCount =
            recentHistory.count {
                it.optString(
                    "status"
                ) ==
                    "error"
            }

        val now =
            System.currentTimeMillis()

        val externalScreenAt =
            prefs.getLong(
                KEY_EXTERNAL_SCREEN_AT,
                0L
            )

        val externalScreenFresh =
            externalScreenAt > 0L &&
                now -
                    externalScreenAt <=
                EXTERNAL_SCREEN_EVIDENCE_TTL_MS

        val runtime =
            JSONObject()
                .put(
                    "microphone_permission",
                    microphonePermission
                )
                .put(
                    "overlay_permission",
                    overlayAllowed
                )
                .put(
                    "write_settings_permission",
                    writeSettingsAllowed
                )
                .put(
                    "accessibility_connected",
                    accessibilityConnected
                )
                .put(
                    "notification_permission",
                    notificationPermission
                )
                .put(
                    "notification_listener_access",
                    notificationListenerAccess
                )
                .put(
                    "notification_listener_connected",
                    notificationListenerConnected
                )
                .put(
                    "exact_alarm_permission",
                    exactAlarmAllowed
                )
                .put(
                    "voice_service_running",
                    AyanaVoiceService.isRunning
                )
                .put(
                    "stt_ready_last",
                    prefs.getBoolean(
                        KEY_STT_READY,
                        false
                    )
                )
                .put(
                    "stt_ready_at",
                    prefs.getLong(
                        KEY_STT_AT,
                        0L
                    )
                )
                .put(
                    "agent_core_last_ok",
                    prefs.getBoolean(
                        KEY_AGENT_CORE_OK,
                        false
                    )
                )
                .put(
                    "agent_core_last_at",
                    prefs.getLong(
                        KEY_AGENT_CORE_AT,
                        0L
                    )
                )
                .put(
                    "agent_core_last_latency_ms",
                    prefs.getLong(
                        KEY_AGENT_CORE_LATENCY,
                        -1L
                    )
                )
                .put(
                    "agent_core_last_error",
                    prefs.getString(
                        KEY_AGENT_CORE_ERROR,
                        ""
                    ).orEmpty()
                )
                .put(
                    "agent_core_perf_at",
                    prefs.getLong(
                        KEY_AGENT_CORE_PERF_AT,
                        0L
                    )
                )
                .put(
                    "agent_core_perf_total_ms",
                    prefs.getLong(
                        KEY_AGENT_CORE_PERF_TOTAL,
                        -1L
                    )
                )
                .put(
                    "agent_core_perf_prepare_ms",
                    prefs.getLong(
                        KEY_AGENT_CORE_PERF_PREPARE,
                        -1L
                    )
                )
                .put(
                    "agent_core_perf_upload_ms",
                    prefs.getLong(
                        KEY_AGENT_CORE_PERF_UPLOAD,
                        -1L
                    )
                )
                .put(
                    "agent_core_perf_headers_wait_ms",
                    prefs.getLong(
                        KEY_AGENT_CORE_PERF_HEADERS_WAIT,
                        -1L
                    )
                )
                .put(
                    "agent_core_perf_body_read_ms",
                    prefs.getLong(
                        KEY_AGENT_CORE_PERF_BODY_READ,
                        -1L
                    )
                )
                .put(
                    "agent_core_perf_json_parse_ms",
                    prefs.getLong(
                        KEY_AGENT_CORE_PERF_JSON_PARSE,
                        -1L
                    )
                )
                .put(
                    "agent_core_perf_request_bytes",
                    prefs.getInt(
                        KEY_AGENT_CORE_PERF_REQUEST_BYTES,
                        -1
                    )
                )
                .put(
                    "agent_core_perf_response_bytes",
                    prefs.getInt(
                        KEY_AGENT_CORE_PERF_RESPONSE_BYTES,
                        -1
                    )
                )
                .put(
                    "agent_core_perf_http_code",
                    prefs.getInt(
                        KEY_AGENT_CORE_PERF_HTTP_CODE,
                        -1
                    )
                )
                .put(
                    "agent_core_latency_class",
                    agentCoreLatencySnapshot()
                        .optString(
                            "classification",
                            "NO_DATA"
                        )
                )
                .put(
                    "agent_core_latency_bottleneck",
                    agentCoreLatencySnapshot()
                        .optString(
                            "bottleneck",
                            "unknown"
                        )
                )
                .put(
                    "agent_core_headers_share_pct",
                    agentCoreLatencySnapshot()
                        .optInt(
                            "headers_share_pct",
                            -1
                        )
                )
                .put(
                    "tts_last_ok",
                    prefs.getBoolean(
                        KEY_TTS_OK,
                        false
                    )
                )
                .put(
                    "tts_last_at",
                    prefs.getLong(
                        KEY_TTS_AT,
                        0L
                    )
                )
                .put(
                    "tts_first_byte_ms",
                    prefs.getLong(
                        KEY_TTS_FIRST_BYTE,
                        -1L
                    )
                )
                .put(
                    "tts_last_error",
                    prefs.getString(
                        KEY_TTS_ERROR,
                        ""
                    ).orEmpty()
                )
                .put(
                    "last_command_source",
                    prefs.getString(
                        KEY_LAST_COMMAND_SOURCE,
                        ""
                    ).orEmpty()
                )
                .put(
                    "last_command_tts_expected",
                    prefs.getBoolean(
                        KEY_LAST_COMMAND_TTS_EXPECTED,
                        false
                    )
                )
                .put(
                    "last_command_at",
                    prefs.getLong(
                        KEY_LAST_COMMAND_AT,
                        0L
                    )
                )
                .put(
                    "launchable_app_count",
                    appCount
                )
                .put(
                    "memory_count",
                    memoryCount
                )
                .put(
                    "reminder_count",
                    reminderCount
                )
                .put(
                    "active_reminder_count",
                    activeReminderCount
                )
                .put(
                    "recoverable_goal_count",
                    goalViews.size
                )
                .put(
                    "screen_snapshot_ok",
                    screenSnapshot
                        ?.optBoolean(
                            "success",
                            false
                        ) ==
                        true
                )
                .put(
                    "screen_window_count",
                    screenSnapshot
                        ?.optInt(
                            "window_count",
                            -1
                        )
                        ?: -1
                )
                .put(
                    "screen_visible_text_count",
                    screenVisibleCount
                )
                .put(
                    "screen_primary_content_state",
                    screenPrimaryContentState
                )
                .put(
                    "screen_primary_content_available",
                    screenPrimaryContentState ==
                        "readable" ||
                        screenPrimaryContentState ==
                        "partial"
                )
                .put(
                    "screen_primary_readable_text_count",
                    screenPrimaryReadableTextCount
                )
                .put(
                    "screen_snapshot_latency_ms",
                    screenDurationMs
                )
                .put(
                    "screen_context_mode",
                    screenSnapshot
                        ?.optString(
                            "window_context_mode"
                        )
                        .orEmpty()
                )
                .put(
                    "screen_primary_package",
                    screenSnapshot
                        ?.optString(
                            "package"
                        )
                        .orEmpty()
                )
                .put(
                    "external_screen_evidence_fresh",
                    externalScreenFresh
                )
                .put(
                    "external_screen_last_at",
                    externalScreenAt
                )
                .put(
                    "external_screen_last_package",
                    prefs.getString(
                        KEY_EXTERNAL_SCREEN_PACKAGE,
                        ""
                    ).orEmpty()
                )
                .put(
                    "external_screen_last_content_state",
                    prefs.getString(
                        KEY_EXTERNAL_SCREEN_CONTENT_STATE,
                        "unknown"
                    ).orEmpty()
                )
                .put(
                    "external_screen_last_readable_text_count",
                    prefs.getInt(
                        KEY_EXTERNAL_SCREEN_READABLE_COUNT,
                        -1
                    )
                )
                .put(
                    "external_screen_last_latency_ms",
                    prefs.getLong(
                        KEY_EXTERNAL_SCREEN_DURATION_MS,
                        -1L
                    )
                )
                .put(
                    "recent_command_count",
                    recentHistory.size
                )
                .put(
                    "recent_error_count",
                    recentErrorCount
                )
                .put(
                    "last_command_status",
                    lastRecord
                        ?.optString(
                            "status"
                        )
                        .orEmpty()
                )
                .put(
                    "last_command",
                    lastRecord
                        ?.optString(
                            "command"
                        )
                        .orEmpty()
                        .take(
                            500
                        )
                )
                .put(
                    "last_command_result",
                    lastRecord
                        ?.optString(
                            "result"
                        )
                        .orEmpty()
                        .take(
                            900
                        )
                )
                .put(
                    "last_error_command",
                    lastErrorRecord
                        ?.optString(
                            "command"
                        )
                        .orEmpty()
                        .take(
                            500
                        )
                )
                .put(
                    "last_error_result",
                    lastErrorRecord
                        ?.optString(
                            "result"
                        )
                        .orEmpty()
                        .take(
                            900
                        )
                )

        val capabilities =
            JSONArray()

        capability(
            capabilities,
            "voice_wake_and_tts",
            implemented = true,
            available = microphonePermission,
            deviceConfirmed = true,
            note = "Marin streaming + wake word + voice baseline"
        )

        capability(
            capabilities,
            "voice_stop_during_speaking",
            implemented = true,
            available = microphonePermission,
            deviceConfirmed = true,
            note = "device-confirmed barge-in STOP during Marin"
        )

        capability(
            capabilities,
            "bounded_voice_follow_up",
            implemented = true,
            available = microphonePermission,
            deviceConfirmed = false,
            note = "v12.11 routes wake acknowledgement into bounded FOLLOW_UP and adds an independent 12 s generation-token watchdog; pending device acceptance"
        )

        capability(
            capabilities,
            "notification_reading",
            implemented = true,
            available = notificationListenerAccess,
            deviceConfirmed = true,
            note = "device-confirmed local NotificationListenerService read; v12.11 adds limit/app filters, empty-entry suppression and data-minimised snippets"
        )

        capability(
            capabilities,
            "exact_media_volume_set",
            implemented = true,
            available = true,
            deviceConfirmed = true,
            note = "v12.11 keeps device-confirmed exact STREAM_MUSIC set and adds side-effect reconciliation"
        )

        capability(
            capabilities,
            "relative_media_volume_delta",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "v12.11 preserves numeric delta (+N/-N) and verifies actual post-write level"
        )

        capability(
            capabilities,
            "exact_screen_brightness_set",
            implemented = true,
            available = writeSettingsAllowed,
            deviceConfirmed = false,
            note = "v12.11 requires WRITE_SETTINGS and verifies manual-mode + post-write brightness; never substitutes opening Settings"
        )

        capability(
            capabilities,
            "clipboard_write",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "v12.11 local ClipboardManager write with read-back verification"
        )

        capability(
            capabilities,
            "structured_local_queries",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "v12.11 local typed arguments for notifications, device metrics, history, memory, reminders and internal AYANA navigation"
        )

        capability(
            capabilities,
            "dynamic_app_resolver",
            implemented = true,
            available = appCount > 0,
            deviceConfirmed = true,
            note = "device-confirmed launches for Chrome, ChatGPT, Gallery, Play Store, Maps, Notes and others"
        )

        capability(
            capabilities,
            "window_detection",
            implemented = true,
            available = accessibilityConnected,
            deviceConfirmed = true,
            note = "multi-window/Recents container detection confirmed"
        )

        capability(
            capabilities,
            "app_task_removal",
            implemented = true,
            available = accessibilityConnected,
            deviceConfirmed = false,
            note = "v12.1 verified Recents task-removal executor; pending device confirmation; never claims force-stop/process kill"
        )

        capability(
            capabilities,
            "screen_content_reading",
            implemented = true,
            available =
                accessibilityConnected &&
                    (
                        screenPrimaryContentState ==
                            "readable" ||
                            screenPrimaryContentState ==
                            "partial"
                        ),
            deviceConfirmed = false,
            note = "Chrome and Samsung Settings previously exposed window identity while inner content was unavailable; v11.3 reports this explicitly"
        )

        capability(
            capabilities,
            "strict_terminal_verification",
            implemented = true,
            available = accessibilityConnected,
            deviceConfirmed = true,
            note = "fail-closed terminal verification retained"
        )

        capability(
            capabilities,
            "durable_goals",
            implemented = true,
            available = true,
            deviceConfirmed = true,
            note = "checkpoints, recovery, bounded replan and anti-cycle"
        )

        capability(
            capabilities,
            "memory_v2",
            implemented = true,
            available = memoryCount >= 0,
            deviceConfirmed = true,
            note = "local long-term memory"
        )

        capability(
            capabilities,
            "tasks_reminders_v2",
            implemented = true,
            available = reminderCount >= 0,
            deviceConfirmed = true,
            note = "local reminders/tasks"
        )

        capability(
            capabilities,
            "google_image_search",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "v11.3 direct Google Images route; requires device acceptance test"
        )

        capability(
            capabilities,
            "speed_test_launcher",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "v11.3 can open FAST.com; AYANA does not yet independently read/verify Mbps result"
        )

        capability(
            capabilities,
            "internet_speed_measurement",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "AYANA cannot yet independently measure and return verified Mbps"
        )

        capability(
            capabilities,
            "image_upload_to_ayana",
            implemented = true,
            available = true,
            deviceConfirmed = true,
            note = "device-confirmed on target tablet: v11.6+ text-mode private-cache image attachment transport"
        )

        capability(
            capabilities,
            "video_upload_to_ayana",
            implemented = true,
            available = true,
            deviceConfirmed = true,
            note = "device-confirmed on target tablet: selected video is converted to bounded sampled visual frames"
        )

        capability(
            capabilities,
            "image_vision_analysis",
            implemented = true,
            available = true,
            deviceConfirmed = true,
            note = "device-confirmed on target tablet through dedicated Responses multimodal endpoint"
        )

        capability(
            capabilities,
            "video_analysis",
            implemented = true,
            available = true,
            deviceConfirmed = true,
            note = "device-confirmed visual sampled-frame analysis only; video audio track is not analyzed"
        )

        capability(
            capabilities,
            "video_audio_analysis",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "v11.6 does not transcribe or analyze the video's audio track"
        )

        capability(
            capabilities,
            "document_understanding",
            implemented = true,
            available = true,
            deviceConfirmed = true,
            note = "device-confirmed on target tablet for PDF/DOCX; supported file input uses dedicated multimodal endpoint with 8 MB Android staging cap"
        )

        capability(
            capabilities,
            "artifact_generation",
            implemented = true,
            available = true,
            deviceConfirmed = true,
            note = "device-confirmed on target tablet for TXT/DOCX/PDF/XLSX/JPEG and graph-JPEG; local verify-before-publish to Downloads/AYANA"
        )

        capability(
            capabilities,
            "docx_style_preserving_transform",
            implemented = true,
            available = true,
            deviceConfirmed = true,
            note = "device-confirmed style-preserving DOCX translation: original OOXML package retained, validated Word text nodes replaced, output re-opened and verified"
        )

        capability(
            capabilities,
            "docx_translation",
            implemented = true,
            available = true,
            deviceConfirmed = true,
            note = "device-confirmed style-preserving DOCX translation; supported targets ru/en/ky/de/fr/es/tr with bounded validated batches"
        )

        capability(
            capabilities,
            "unified_execution_session",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "v12.0 Execution Kernel unifies cancellation/terminal/evidence contracts across long-running lanes; awaiting device test"
        )

        capability(
            capabilities,
            "multimodal_stop_during_analysis",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "v12.0 arms the same voice cancel-listener used by Agent Core before multimodal network execution; awaiting device confirmation"
        )

        capability(
            capabilities,
            "goal_compiler_execution_contract",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "Goal Compiler v2 emits executor, terminal, verification and bounded-replan policy metadata"
        )

        capability(
            capabilities,
            "settings_intent_attestation",
            implemented = true,
            available = accessibilityConnected,
            deviceConfirmed = false,
            note = "v12.0 fuses exact Settings intent target with fresh same-window semantic surface evidence; no app-specific aliases"
        )

        capability(
            capabilities,
            "app_detail_permissions_navigation",
            implemented = true,
            available = accessibilityConnected,
            deviceConfirmed = false,
            note = "Samsung App Info -> Permissions can be reached physically, but the combined terminal verifier still has a known window-list edge; do not advertise it as universally device-confirmed"
        )

        capability(
            capabilities,
            "capability_truth_grounding",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "v12.12 local self-review is generated from Capability Registry/runtime facts instead of generic model assumptions; pending full device acceptance"
        )

        capability(
            capabilities,
            "agent_core_latency_classification",
            implemented = true,
            available = prefs.getLong(KEY_AGENT_CORE_PERF_TOTAL, -1L) >= 0L,
            deviceConfirmed = false,
            note = "v12.12 classifies measured prepare/upload/headers/body/parse phases and separates server/model wait from Android-side work"
        )

        capability(
            capabilities,
            "perception_owner_fusion",
            implemented = true,
            available = accessibilityConnected,
            deviceConfirmed = false,
            note = "v12.12 exposes effective foreground package so AYANA overlay ownership cannot be confused with a verified external foreground app; pending acceptance"
        )

        capability(
            capabilities,
            "autonomous_execution_loop",
            implemented = true,
            available = true,
            deviceConfirmed = false,
            note = "existing Planner/Durable Goals/checkpoints/bounded replan/terminal verification are treated as one controlled execution loop; full multi-step acceptance still required"
        )

        capability(
            capabilities,
            "development_agent_transaction",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "analysis/source generation exists, but authenticated project workspace + build/test/rollback transaction executor is not yet implemented on Android"
        )

        capability(
            capabilities,
            "github_repository_write",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "AYANA Android can prepare source/patch text, but no authenticated GitHub repository-write executor is implemented"
        )

        capability(
            capabilities,
            "github_commit_push",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "No current Android capability can create Git commits or push them to GitHub; source generation is not commit/push evidence"
        )

        capability(
            capabilities,
            "android_apk_build",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "The installed AYANA runtime does not run the Android Gradle/GitHub Actions APK build pipeline"
        )

        capability(
            capabilities,
            "direct_apk_delivery",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "AYANA cannot claim an APK exists until an external build pipeline produces a verified build artifact"
        )

        capability(
            capabilities,
            "external_account_actions",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "No general credentialed executor for arbitrary external-account write actions is implemented"
        )

        capability(
            capabilities,
            "external_mail_calendar_files",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "planned specialized executors with scoped permissions"
        )

        capability(
            capabilities,
            "offline_llm",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "not implemented"
        )

        capability(
            capabilities,
            "controlled_proactivity",
            implemented = false,
            available = false,
            deviceConfirmed = false,
            note = "explicit reminders exist; broad autonomous initiative is not implemented"
        )

        return JSONObject()
            .put(
                "success",
                true
            )
            .put(
                "build",
                BUILD_LABEL
            )
            .put(
                "android_sdk",
                Build.VERSION.SDK_INT
            )
            .put(
                "runtime",
                runtime
            )
            .put(
                "capabilities",
                capabilities
            )
            .put(
                "generated_at",
                now
            )
    }

    /**
     * Critical truths intentionally appear FIRST. VoiceService truncates the
     * combined intelligence context, so negative capability facts must never be
     * the part that gets cut off.
     */
    fun compactContext(): String {

        val snapshot =
            snapshot()

        val runtime =
            snapshot
                .optJSONObject(
                    "runtime"
                )
                ?: JSONObject()

        return buildString {

            append(
                "AYANA CAPABILITY TRUTH v2: "
            )

            append(
                "github_repository_write=false; github_commit_push=false; android_apk_build=false; direct_apk_delivery=false; external_account_actions=false; development_agent_transaction=false; "
            )

            append(
                "local_self_review=true; capability_truth_grounding=true; perception_owner_fusion=true; autonomous_execution_loop=true; "
            )

            append(
                "Source/code/patch generation does NOT prove repository write, commit, push, APK build, signing, deployment, or delivery. "
            )

            append(
                "settings_permissions_route_implemented=true; settings_permissions_device_confirmed=false; settings_permissions_available="
            )
            append(
                runtime.optBoolean(
                    "accessibility_connected",
                    false
                )
            )
            append(
                "; Samsung App Info->Permissions has a known terminal-verifier window-list edge, so describe it as implemented/limited rather than universally confirmed. "
            )

            append(
                "notification_reading=true; notification_listener_access="
            )
            append(
                runtime.optBoolean(
                    "notification_listener_access",
                    false
                )
            )
            append(
                "; notification_reading_never_equals_open_settings; exact_media_volume_set=true; exact_volume_success_requires_post_write_verification=true; bounded_voice_follow_up=true. "
            )

            append(
                "image_upload=true; video_upload=true; image_vision=true; video_analysis=visual_sampled_frames; video_audio_analysis=false; "
            )

            append(
                "document_understanding=true; artifact_generation=true; artifact_formats=txt,docx,pdf,xlsx,jpeg,graph_jpeg; docx_style_preserving_transform=true; docx_translation=true; docx_translation_targets=ru,en,ky,de,fr,es,tr; external_mail_calendar_files=false; offline_llm=false; broad_proactivity=false; "
            )

            append(
                "internet_speed_measurement=false; speed_test_launcher=true; google_image_search=true. "
            )

            append(
                "Multimodal intake is device-confirmed on the target tablet for image, PDF/DOCX and sampled-frame visual video analysis; video audio remains unavailable. Never inherit other generic ChatGPT abilities. "
            )

            append(
                "Screen: accessibility="
            )

            append(
                runtime.optBoolean(
                    "accessibility_connected",
                    false
                )
            )

            append(
                "; current_package="
            )

            append(
                runtime.optString(
                    "screen_primary_package"
                )
            )

            append(
                "; current_content="
            )

            append(
                runtime.optString(
                    "screen_primary_content_state",
                    "unknown"
                )
            )

            append(
                "; external_evidence_fresh="
            )

            append(
                runtime.optBoolean(
                    "external_screen_evidence_fresh",
                    false
                )
            )

            append(
                "; external_package="
            )

            append(
                runtime.optString(
                    "external_screen_last_package"
                )
            )

            append(
                "; external_content="
            )

            append(
                runtime.optString(
                    "external_screen_last_content_state",
                    "unknown"
                )
            )

            append(
                ". Runtime: apps="
            )

            append(
                runtime.optInt(
                    "launchable_app_count",
                    -1
                )
            )

            append(
                "; agent_core_ok="
            )

            append(
                runtime.optBoolean(
                    "agent_core_last_ok",
                    false
                )
            )

            append(
                "; agent_core_ms="
            )

            append(
                runtime.optLong(
                    "agent_core_last_latency_ms",
                    -1L
                )
            )

            append(
                "; agent_core_prepare_ms="
            )
            append(
                runtime.optLong(
                    "agent_core_perf_prepare_ms",
                    -1L
                )
            )
            append(
                "; agent_core_upload_ms="
            )
            append(
                runtime.optLong(
                    "agent_core_perf_upload_ms",
                    -1L
                )
            )
            append(
                "; agent_core_headers_wait_ms="
            )
            append(
                runtime.optLong(
                    "agent_core_perf_headers_wait_ms",
                    -1L
                )
            )
            append(
                "; agent_core_latency_class="
            )
            append(
                runtime.optString(
                    "agent_core_latency_class",
                    "NO_DATA"
                )
            )
            append(
                "; agent_core_latency_bottleneck="
            )
            append(
                runtime.optString(
                    "agent_core_latency_bottleneck",
                    "unknown"
                )
            )
            append(
                "; agent_core_body_read_ms="
            )
            append(
                runtime.optLong(
                    "agent_core_perf_body_read_ms",
                    -1L
                )
            )
            append(
                "; agent_core_json_parse_ms="
            )
            append(
                runtime.optLong(
                    "agent_core_perf_json_parse_ms",
                    -1L
                )
            )

            append(
                "; last_command_source="
            )

            append(
                runtime.optString(
                    "last_command_source"
                )
            )

            append(
                "; last_command_tts_expected="
            )

            append(
                runtime.optBoolean(
                    "last_command_tts_expected",
                    false
                )
            )

            append(
                "; tts_last_ok="
            )

            append(
                runtime.optBoolean(
                    "tts_last_ok",
                    false
                )
            )

            append(
                "; tts_first_byte_ms="
            )

            append(
                runtime.optLong(
                    "tts_first_byte_ms",
                    -1L
                )
            )

            append(
                "; recoverable_goals="
            )

            append(
                runtime.optInt(
                    "recoverable_goal_count",
                    0
                )
            )

            append(
                "; memory="
            )

            append(
                runtime.optInt(
                    "memory_count",
                    0
                )
            )

            append(
                "; reminders="
            )

            append(
                runtime.optInt(
                    "reminder_count",
                    0
                )
            )

            append(
                "; recent_errors="
            )

            append(
                runtime.optInt(
                    "recent_error_count",
                    0
                )
            )

            if (
                runtime
                    .optString(
                        "last_error_command"
                    )
                    .isNotBlank()
            ) {
                append(
                    "; last_error_command="
                )

                append(
                    runtime
                        .optString(
                            "last_error_command"
                        )
                        .take(
                            220
                        )
                )

                append(
                    "; last_error_result="
                )

                append(
                    runtime
                        .optString(
                            "last_error_result"
                        )
                        .take(
                            320
                        )
                )
            }

            append(
                ". Implemented baseline: wake/Marin/STOP, App Resolver, Safety, Durable Goals, Planner, Memory, Tasks, Window Context and strict verification. "
            )

            append(
                "Unimplemented means unavailable now, even if a generic model could theoretically do it."
            )
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
        val truthState =
            when {
                !implemented ->
                    "UNIMPLEMENTED"

                !available &&
                    deviceConfirmed ->
                    "DEVICE_CONFIRMED_UNAVAILABLE_NOW"

                !available ->
                    "IMPLEMENTED_UNAVAILABLE_NOW"

                deviceConfirmed ->
                    "DEVICE_CONFIRMED_AVAILABLE"

                else ->
                    "IMPLEMENTED_AVAILABLE_UNCONFIRMED"
            }

        array.put(
            JSONObject()
                .put(
                    "id",
                    id
                )
                .put(
                    "implemented",
                    implemented
                )
                .put(
                    "available_now",
                    available
                )
                .put(
                    "device_confirmed",
                    deviceConfirmed
                )
                .put(
                    "truth_state",
                    truthState
                )
                .put(
                    "note",
                    note
                )
        )
    }

    companion object {

        const val BUILD_LABEL =
            "v12.12.0_autonomy_perception_truth_build_candidate"

        private const val PREFS_NAME =
            "ayana_capability_runtime_v11"

        private const val KEY_AGENT_CORE_OK =
            "agent_core_ok"

        private const val KEY_AGENT_CORE_AT =
            "agent_core_at"

        private const val KEY_AGENT_CORE_LATENCY =
            "agent_core_latency"

        private const val KEY_AGENT_CORE_ERROR =
            "agent_core_error"

        private const val KEY_AGENT_CORE_PERF_AT =
            "agent_core_perf_at"

        private const val KEY_AGENT_CORE_PERF_TOTAL =
            "agent_core_perf_total"

        private const val KEY_AGENT_CORE_PERF_PREPARE =
            "agent_core_perf_prepare"

        private const val KEY_AGENT_CORE_PERF_UPLOAD =
            "agent_core_perf_upload"

        private const val KEY_AGENT_CORE_PERF_HEADERS_WAIT =
            "agent_core_perf_headers_wait"

        private const val KEY_AGENT_CORE_PERF_BODY_READ =
            "agent_core_perf_body_read"

        private const val KEY_AGENT_CORE_PERF_JSON_PARSE =
            "agent_core_perf_json_parse"

        private const val KEY_AGENT_CORE_PERF_REQUEST_BYTES =
            "agent_core_perf_request_bytes"

        private const val KEY_AGENT_CORE_PERF_RESPONSE_BYTES =
            "agent_core_perf_response_bytes"

        private const val KEY_AGENT_CORE_PERF_HTTP_CODE =
            "agent_core_perf_http_code"

        private const val KEY_TTS_OK =
            "tts_ok"

        private const val KEY_TTS_AT =
            "tts_at"

        private const val KEY_TTS_FIRST_BYTE =
            "tts_first_byte"

        private const val KEY_TTS_ERROR =
            "tts_error"

        private const val KEY_STT_READY =
            "stt_ready"

        private const val KEY_STT_AT =
            "stt_at"

        private const val KEY_LAST_COMMAND_SOURCE =
            "last_command_source"

        private const val KEY_LAST_COMMAND_TTS_EXPECTED =
            "last_command_tts_expected"

        private const val KEY_LAST_COMMAND_AT =
            "last_command_at"

        private const val KEY_SCREEN_LAST_PACKAGE =
            "screen_last_package"

        private const val KEY_SCREEN_LAST_CONTENT_STATE =
            "screen_last_content_state"

        private const val KEY_SCREEN_LAST_READABLE_COUNT =
            "screen_last_readable_count"

        private const val KEY_SCREEN_LAST_DURATION_MS =
            "screen_last_duration_ms"

        private const val KEY_SCREEN_LAST_AT =
            "screen_last_at"

        private const val KEY_EXTERNAL_SCREEN_PACKAGE =
            "external_screen_package"

        private const val KEY_EXTERNAL_SCREEN_CONTENT_STATE =
            "external_screen_content_state"

        private const val KEY_EXTERNAL_SCREEN_READABLE_COUNT =
            "external_screen_readable_count"

        private const val KEY_EXTERNAL_SCREEN_DURATION_MS =
            "external_screen_duration_ms"

        private const val KEY_EXTERNAL_SCREEN_AT =
            "external_screen_at"

        private const val EXTERNAL_SCREEN_EVIDENCE_TTL_MS =
            2L * 60L * 60L * 1000L
    }
}
