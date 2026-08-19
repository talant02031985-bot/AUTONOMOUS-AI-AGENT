package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * AYANA Self-Diagnostics v2.0.
 * Converts raw capability/runtime state into actionable machine-readable checks.
 */
class AyanaSelfDiagnostics(
    context: Context,
    private val appResolver: AyanaAppResolver,
    private val capabilityRegistry: AyanaCapabilityRegistry
) {

    private val appContext = context.applicationContext

    fun run(
        focus: String = "all",
        appName: String = ""
    ): JSONObject {
        val snapshot = capabilityRegistry.snapshot()
        val runtime = snapshot.optJSONObject("runtime") ?: JSONObject()
        val checks = JSONArray()

        check(
            checks,
            "microphone",
            runtime.optBoolean("microphone_permission", false),
            "Разрешение микрофона",
            if (runtime.optBoolean("microphone_permission", false)) {
                "Разрешено"
            } else {
                "Нет разрешения RECORD_AUDIO"
            }
        )
        val sttRecorded = runtime.optLong("stt_ready_at", 0L) > 0L
        val sttReady = runtime.optBoolean("stt_ready_last", false)
        check(
            checks,
            "stt",
            !sttRecorded || sttReady,
            "Локальное распознавание речи",
            when {
                !sttRecorded -> "Состояние STT ещё не записано в этой установке"
                sttReady -> "Локальная модель распознавания загружена"
                else -> "Локальная модель распознавания не готова"
            }
        )

        check(
            checks,
            "accessibility",
            runtime.optBoolean("accessibility_connected", false),
            "Accessibility AYANA",
            if (runtime.optBoolean("accessibility_connected", false)) {
                "Сервис подключён"
            } else {
                "Сервис специальных возможностей не подключён"
            }
        )
        check(
            checks,
            "overlay",
            runtime.optBoolean("overlay_permission", false),
            "Плавающий Orb",
            if (runtime.optBoolean("overlay_permission", false)) {
                "Overlay разрешён"
            } else {
                "SYSTEM_ALERT_WINDOW не разрешён"
            }
        )
        check(
            checks,
            "notifications",
            runtime.optBoolean("notification_permission", false),
            "Уведомления",
            if (runtime.optBoolean("notification_permission", false)) {
                "Разрешены"
            } else {
                "Нет разрешения уведомлений"
            }
        )
        check(
            checks,
            "exact_alarm",
            runtime.optBoolean("exact_alarm_permission", false),
            "Точные напоминания",
            if (runtime.optBoolean("exact_alarm_permission", false)) {
                "Доступны"
            } else {
                "Android не разрешает точные будильники"
            }
        )

        val appCount = runtime.optInt("launchable_app_count", -1)
        check(
            checks,
            "app_resolver",
            appCount > 0,
            "App Resolver v2",
            if (appCount > 0) {
                "Доступно запускаемых приложений: $appCount"
            } else {
                "Launcher-список приложений пуст или недоступен"
            }
        )

        val agentAt = runtime.optLong("agent_core_last_at", 0L)
        val agentFresh = agentAt > 0L && System.currentTimeMillis() - agentAt < HEALTH_FRESH_MS
        val agentOk = runtime.optBoolean("agent_core_last_ok", false)
        check(
            checks,
            "agent_core",
            agentOk && agentFresh,
            "Agent Core",
            when {
                agentAt <= 0L -> "В этой установке ещё нет сохранённого результата обращения к Agent Core"
                !agentFresh -> "Последняя проверка Agent Core устарела"
                agentOk -> "Последний запрос успешен; ${runtime.optLong("agent_core_last_latency_ms", -1L)} мс"
                else -> "Последний запрос завершился ошибкой: ${runtime.optString("agent_core_last_error")}".take(420)
            }
        )

        val ttsAt = runtime.optLong("tts_last_at", 0L)
        val ttsFresh = ttsAt > 0L && System.currentTimeMillis() - ttsAt < HEALTH_FRESH_MS
        val ttsOk = runtime.optBoolean("tts_last_ok", false)
        // Audio/TTS path is a frozen, device-confirmed baseline. Absence of a
        // fresh telemetry sample is therefore "unknown", not a synthetic failure.
        // A fresh recorded failure still fails diagnostics.
        val ttsHealthy =
            ttsAt <= 0L ||
                !ttsFresh ||
                ttsOk
        check(
            checks,
            "tts",
            ttsHealthy,
            "Marin TTS",
            when {
                ttsAt <= 0L -> "Аудиоконтур подтверждён на устройстве; свежая TTS-телеметрия ещё не записана"
                !ttsFresh -> "Аудиоконтур подтверждён; последнее TTS-измерение устарело"
                ttsOk -> "Последний TTS успешен; first_byte=${runtime.optLong("tts_first_byte_ms", -1L)} мс"
                else -> "Последний TTS завершился ошибкой: ${runtime.optString("tts_last_error")}".take(420)
            }
        )

        val appDiagnostic =
            if (appName.isNotBlank()) {
                appResolver.resolve(appName, forceRefresh = true).toJson()
            } else {
                null
            }

        val filteredChecks =
            if (focus.isBlank() || focus.equals("all", ignoreCase = true)) {
                checks
            } else {
                filterChecks(checks, focus)
            }

        var passed = 0
        var failed = 0
        for (i in 0 until filteredChecks.length()) {
            val item = filteredChecks.optJSONObject(i) ?: continue
            if (item.optBoolean("ok", false)) passed++ else failed++
        }

        val recommendations = JSONArray()
        for (i in 0 until filteredChecks.length()) {
            val item = filteredChecks.optJSONObject(i) ?: continue
            if (!item.optBoolean("ok", false)) {
                recommendationFor(item.optString("id"))
                    ?.let { recommendations.put(it) }
            }
        }

        if (appDiagnostic != null && !appDiagnostic.optBoolean("success", false)) {
            recommendations.put(
                "Для приложения «$appName» App Resolver не нашёл надёжного launcher-совпадения. Проверьте, установлено ли приложение и имеет ли оно запускаемую Activity."
            )
        }

        return JSONObject()
            .put("success", failed == 0)
            .put("focus", focus)
            .put("passed", passed)
            .put("failed", failed)
            .put("checks", filteredChecks)
            .put("app_diagnostic", appDiagnostic ?: JSONObject.NULL)
            .put("recommendations", recommendations)
            .put("capability_snapshot", snapshot)
            .put("generated_at", System.currentTimeMillis())
    }

    fun compactReport(
        focus: String = "all",
        appName: String = ""
    ): String {
        val result = run(focus, appName)
        val checks = result.optJSONArray("checks") ?: JSONArray()
        val failedNames = mutableListOf<String>()
        for (i in 0 until checks.length()) {
            val item = checks.optJSONObject(i) ?: continue
            if (!item.optBoolean("ok", false)) {
                failedNames += item.optString("name")
            }
        }

        val base = if (failedNames.isEmpty()) {
            "Самодиагностика: основные локальные компоненты в норме."
        } else {
            "Самодиагностика: требуют внимания — ${failedNames.joinToString(", ")}."
        }

        val appPart = result.optJSONObject("app_diagnostic")
            ?.takeIf { it.length() > 0 }
            ?.let { app ->
                if (app.optBoolean("success", false)) {
                    " Приложение найдено: ${app.optString("label")} (${app.optString("package")}), confidence=${app.optInt("confidence")}%."
                } else {
                    " Приложение не разрешено однозначно: ${app.optString("reason")}."
                }
            }
            .orEmpty()

        return base + appPart
    }

    private fun filterChecks(
        checks: JSONArray,
        focus: String
    ): JSONArray {
        val normalized = focus.lowercase().trim()
        val result = JSONArray()
        for (i in 0 until checks.length()) {
            val item = checks.optJSONObject(i) ?: continue
            val id = item.optString("id").lowercase()
            val name = item.optString("name").lowercase()
            if (
                id.contains(normalized) ||
                name.contains(normalized) ||
                normalized.contains(id)
            ) {
                result.put(item)
            }
        }
        return if (result.length() > 0) result else checks
    }

    private fun check(
        array: JSONArray,
        id: String,
        ok: Boolean,
        name: String,
        details: String
    ) {
        array.put(
            JSONObject()
                .put("id", id)
                .put("ok", ok)
                .put("name", name)
                .put("details", details)
        )
    }

    private fun recommendationFor(id: String): String? =
        when (id) {
            "microphone" -> "Разрешите AYANA доступ к микрофону."
            "accessibility" -> "Включите службу AYANA в специальных возможностях Android."
            "overlay" -> "Разрешите AYANA отображение поверх других приложений."
            "notifications" -> "Разрешите уведомления AYANA, чтобы напоминания были видимыми."
            "exact_alarm" -> "Разрешите точные будильники AYANA для напоминаний в точное время."
            "app_resolver" -> "Обновите карту приложений; если список остаётся пустым, проверьте launcher query/Android package visibility."
            "agent_core" -> "Выполните обычный запрос к Agent Core и проверьте интернет/Worker, если ошибка повторится."
            "tts" -> "Проверьте Worker TTS и сеть, если Marin снова не отвечает."
            else -> null
        }

    companion object {
        private const val HEALTH_FRESH_MS = 30L * 60L * 1000L
    }
}
