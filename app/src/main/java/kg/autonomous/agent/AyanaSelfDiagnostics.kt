package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * AYANA Self-Diagnostics v4.0 — VERIFIED HEALTH.
 *
 * Health states are explicit:
 * PASS     = fresh/observable evidence is healthy;
 * WARNING  = usable, but a degradation/regression is visible;
 * UNKNOWN  = telemetry/evidence is absent or stale;
 * FAIL     = a required component has a fresh confirmed failure.
 *
 * UNKNOWN is never silently converted to PASS.
 */
class AyanaSelfDiagnostics(
    context: Context,
    private val appResolver: AyanaAppResolver,
    private val capabilityRegistry: AyanaCapabilityRegistry
) {

    private val appContext =
        context.applicationContext

    fun run(
        focus: String = "all",
        appName: String = ""
    ): JSONObject {

        val snapshot =
            capabilityRegistry
                .snapshot()

        val runtime =
            snapshot
                .optJSONObject(
                    "runtime"
                )
                ?: JSONObject()

        val checks =
            JSONArray()

        addCheck(
            checks,
            "microphone",
            if (
                runtime.optBoolean(
                    "microphone_permission",
                    false
                )
            ) {
                STATUS_PASS
            } else {
                STATUS_FAIL
            },
            "Разрешение микрофона",
            if (
                runtime.optBoolean(
                    "microphone_permission",
                    false
                )
            ) {
                "Доступ к микрофону разрешён"
            } else {
                "Нет разрешения RECORD_AUDIO"
            }
        )

        val sttAt =
            runtime.optLong(
                "stt_ready_at",
                0L
            )

        val sttReady =
            runtime.optBoolean(
                "stt_ready_last",
                false
            )

        addCheck(
            checks,
            "stt",
            when {
                sttAt <= 0L ->
                    STATUS_UNKNOWN

                sttReady ->
                    STATUS_PASS

                else ->
                    STATUS_FAIL
            },
            "Локальное распознавание речи",
            when {
                sttAt <= 0L ->
                    "Свежая STT-телеметрия ещё не записана"

                sttReady ->
                    "Локальная модель распознавания загружена"

                else ->
                    "Последняя записанная инициализация STT неуспешна"
            }
        )

        val accessibility =
            runtime.optBoolean(
                "accessibility_connected",
                false
            )

        addCheck(
            checks,
            "accessibility",
            if (accessibility) {
                STATUS_PASS
            } else {
                STATUS_FAIL
            },
            "Управление экраном AYANA",
            if (accessibility) {
                "Сервис подключён"
            } else {
                "Сервис специальных возможностей не подключён"
            }
        )

        val screenSnapshotOk =
            runtime.optBoolean(
                "screen_snapshot_ok",
                false
            )

        val screenWindows =
            runtime.optInt(
                "screen_window_count",
                -1
            )

        val screenText =
            runtime.optInt(
                "screen_visible_text_count",
                -1
            )

        val screenContentState =
            runtime.optString(
                "screen_primary_content_state",
                "unknown"
            )

        val screenPrimaryText =
            runtime.optInt(
                "screen_primary_readable_text_count",
                -1
            )

        val screenLatency =
            runtime.optLong(
                "screen_snapshot_latency_ms",
                -1L
            )

        val externalScreenFresh =
            runtime.optBoolean(
                "external_screen_evidence_fresh",
                false
            )

        val externalScreenPackage =
            runtime.optString(
                "external_screen_last_package"
            )

        val externalScreenContentState =
            runtime.optString(
                "external_screen_last_content_state",
                "unknown"
            )

        // Running diagnostics inside AYANA must not hide a recent confirmed
        // external-app readability problem. Prefer recent external evidence when
        // the current snapshot is AYANA's own UI.
        val effectiveScreenContentState =
            if (
                externalScreenFresh &&
                externalScreenPackage.isNotBlank() &&
                externalScreenPackage != appContext.packageName &&
                externalScreenContentState in
                    setOf(
                        "partial",
                        "structure_only",
                        "unavailable",
                        "unknown"
                    )
            ) {
                externalScreenContentState
            } else {
                screenContentState
            }

        addCheck(
            checks,
            "screen_intelligence",
            when {
                !accessibility ->
                    STATUS_UNKNOWN

                !screenSnapshotOk ||
                    screenWindows <= 0 ->
                    STATUS_UNKNOWN

                effectiveScreenContentState == "readable" &&
                    (
                        screenLatency < 0L ||
                            screenLatency <
                            SCREEN_LATENCY_WARNING_MS
                        ) ->
                    STATUS_PASS

                effectiveScreenContentState == "readable" ||
                    effectiveScreenContentState == "partial" ||
                    effectiveScreenContentState == "structure_only" ->
                    STATUS_WARNING

                effectiveScreenContentState == "unavailable" &&
                    externalScreenFresh ->
                    STATUS_WARNING

                else ->
                    STATUS_UNKNOWN
            },
            "Экран и окна",
            when {
                !accessibility ->
                    "Нельзя проверить чтение экрана без сервиса специальных возможностей"

                !screenSnapshotOk ->
                    "В момент проверки Android не отдал пригодный snapshot экрана"

                screenWindows <= 0 ->
                    "Активные окна не обнаружены в момент проверки"

                externalScreenFresh &&
                    externalScreenPackage.isNotBlank() &&
                    externalScreenContentState == "unavailable" ->
                    "Последняя внешняя проверка: $externalScreenPackage — окно определено, но содержимое недоступно для надёжного чтения"

                externalScreenFresh &&
                    externalScreenPackage.isNotBlank() &&
                    externalScreenContentState == "structure_only" ->
                    "Последняя внешняя проверка: $externalScreenPackage — структура окна доступна, но читаемый текст не подтверждён"

                externalScreenFresh &&
                    externalScreenPackage.isNotBlank() &&
                    externalScreenContentState == "partial" ->
                    "Последняя внешняя проверка: $externalScreenPackage — содержимое читается только частично"

                screenContentState == "readable" &&
                    screenLatency >= SCREEN_LATENCY_WARNING_MS ->
                    "Текущий экран читается, но snapshot медленный: $screenLatency мс"

                screenContentState == "readable" ->
                    "Окна: $screenWindows; основной экран читается; элементов текста: $screenPrimaryText; snapshot=${screenLatency}мс; режим=${runtime.optString("screen_context_mode")}"

                screenContentState == "partial" ->
                    "Основное окно определено, но содержимое читается только частично; элементов текста: $screenPrimaryText"

                screenContentState == "structure_only" ->
                    "Основное окно определено и структура доступна, но читаемый текст не подтверждён"

                else ->
                    "Основное окно определено, но его содержимое сейчас недоступно для надёжного чтения"
            }
        )

        val overlay =
            runtime.optBoolean(
                "overlay_permission",
                false
            )

        addCheck(
            checks,
            "overlay",
            if (overlay) {
                STATUS_PASS
            } else {
                STATUS_WARNING
            },
            "Плавающий Orb",
            if (overlay) {
                "Overlay разрешён"
            } else {
                "Overlay не разрешён; голосовой контур может работать, но глобальный Orb недоступен"
            }
        )

        val notifications =
            runtime.optBoolean(
                "notification_permission",
                false
            )

        addCheck(
            checks,
            "notifications",
            if (notifications) {
                STATUS_PASS
            } else {
                STATUS_WARNING
            },
            "Уведомления",
            if (notifications) {
                "Разрешены"
            } else {
                "Нет разрешения уведомлений; напоминания могут быть менее заметны"
            }
        )

        val exactAlarm =
            runtime.optBoolean(
                "exact_alarm_permission",
                false
            )

        addCheck(
            checks,
            "exact_alarm",
            if (exactAlarm) {
                STATUS_PASS
            } else {
                STATUS_WARNING
            },
            "Точные напоминания",
            if (exactAlarm) {
                "Точное системное расписание доступно"
            } else {
                "Android не разрешает точные будильники"
            }
        )

        val appCount =
            runtime.optInt(
                "launchable_app_count",
                -1
            )

        addCheck(
            checks,
            "app_resolver",
            if (appCount > 0) {
                STATUS_PASS
            } else {
                STATUS_FAIL
            },
            "App Resolver",
            if (appCount > 0) {
                "Доступно запускаемых приложений: $appCount"
            } else {
                "Launcher-список приложений пуст или недоступен"
            }
        )

        val agentAt =
            runtime.optLong(
                "agent_core_last_at",
                0L
            )

        val agentFresh =
            agentAt > 0L &&
                System.currentTimeMillis() -
                    agentAt <
                HEALTH_FRESH_MS

        val agentOk =
            runtime.optBoolean(
                "agent_core_last_ok",
                false
            )

        val agentLatency =
            runtime.optLong(
                "agent_core_last_latency_ms",
                -1L
            )

        addCheck(
            checks,
            "agent_core",
            when {
                agentAt <= 0L ||
                    !agentFresh ->
                    STATUS_UNKNOWN

                !agentOk ->
                    STATUS_FAIL

                agentLatency >=
                    AGENT_LATENCY_WARNING_MS ->
                    STATUS_WARNING

                else ->
                    STATUS_PASS
            },
            "Agent Core",
            when {
                agentAt <= 0L ->
                    "В этой установке ещё нет сохранённого результата Agent Core"

                !agentFresh ->
                    "Последнее измерение Agent Core устарело; оно не считается PASS"

                !agentOk ->
                    "Последний свежий запрос завершился ошибкой: ${
                        runtime.optString(
                            "agent_core_last_error"
                        )
                    }".take(420)

                agentLatency >=
                    AGENT_LATENCY_WARNING_MS ->
                    "Последний запрос успешен, но медленный: $agentLatency мс"

                else ->
                    "Последний запрос успешен: $agentLatency мс"
            }
        )

        val lastCommandSource =
            runtime.optString(
                "last_command_source"
            )

        val lastCommandTtsExpected =
            runtime.optBoolean(
                "last_command_tts_expected",
                lastCommandSource == "voice"
            )

        val ttsAt =
            runtime.optLong(
                "tts_last_at",
                0L
            )

        val ttsFresh =
            ttsAt > 0L &&
                System.currentTimeMillis() -
                    ttsAt <
                HEALTH_FRESH_MS

        val ttsOk =
            runtime.optBoolean(
                "tts_last_ok",
                false
            )

        val firstByte =
            runtime.optLong(
                "tts_first_byte_ms",
                -1L
            )

        addCheck(
            checks,
            "tts",
            when {
                ttsAt <= 0L ||
                    !ttsFresh ->
                    STATUS_UNKNOWN

                !ttsOk ->
                    STATUS_FAIL

                firstByte >=
                    TTS_FIRST_BYTE_WARNING_MS ->
                    STATUS_WARNING

                else ->
                    STATUS_PASS
            },
            "Marin TTS",
            when {
                ttsAt <= 0L ->
                    "Нет свежей TTS-телеметрии; замороженная голосовая база не переопределяется как PASS"

                !ttsFresh ->
                    "Последнее TTS-измерение устарело"

                !ttsOk ->
                    "Последний свежий TTS завершился ошибкой: ${
                        runtime.optString(
                            "tts_last_error"
                        )
                    }".take(420)

                firstByte >=
                    TTS_FIRST_BYTE_WARNING_MS ->
                    "Последний TTS успешен, но first_byte=$firstByte мс"

                else ->
                    "Последний TTS успешен; first_byte=$firstByte мс"
            }
        )

        val memoryCount =
            runtime.optInt(
                "memory_count",
                -1
            )

        addCheck(
            checks,
            "memory",
            if (memoryCount >= 0) {
                STATUS_PASS
            } else {
                STATUS_FAIL
            },
            "Долговременная память",
            if (memoryCount >= 0) {
                "Доступно записей памяти: $memoryCount"
            } else {
                "Хранилище памяти не удалось прочитать"
            }
        )

        val taskCount =
            runtime.optInt(
                "reminder_count",
                -1
            )

        addCheck(
            checks,
            "tasks",
            if (taskCount >= 0) {
                STATUS_PASS
            } else {
                STATUS_FAIL
            },
            "Задачи и напоминания",
            if (taskCount >= 0) {
                "Доступно задач/напоминаний: $taskCount"
            } else {
                "Хранилище задач не удалось прочитать"
            }
        )

        val recentCount =
            runtime.optInt(
                "recent_command_count",
                0
            )

        val recentErrors =
            runtime.optInt(
                "recent_error_count",
                0
            )

        val lastErrorCommand =
            runtime.optString(
                "last_error_command"
            )

        val lastErrorResult =
            runtime.optString(
                "last_error_result"
            )

        addCheck(
            checks,
            "recent_command_health",
            when {
                recentCount <= 0 ->
                    STATUS_UNKNOWN

                recentErrors > 0 ->
                    STATUS_WARNING

                else ->
                    STATUS_PASS
            },
            "Последние команды",
            when {
                recentCount <= 0 ->
                    "Недостаточно истории для оценки последних команд"

                recentErrors > 0 ->
                    (
                        "В последних $recentCount командах ошибок: $recentErrors. " +
                            if (lastErrorCommand.isNotBlank()) {
                                "Последняя ошибка: «${lastErrorCommand.take(160)}» — ${lastErrorResult.take(260)}"
                            } else {
                                ""
                            }
                        ).trim()

                else ->
                    "В последних $recentCount командах терминальных ERROR нет"
            }
        )

        val appDiagnostic =
            if (
                appName.isNotBlank()
            ) {
                appResolver
                    .resolve(
                        appName,
                        forceRefresh = true
                    )
                    .toJson()
            } else {
                null
            }

        val filter =
            filterChecks(
                checks,
                focus
            )

        val filteredChecks =
            filter.first

        val focusSupported =
            filter.second

        var passed = 0
        var warnings = 0
        var unknown = 0
        var failed = 0

        for (
            index in
            0 until filteredChecks.length()
        ) {
            val item =
                filteredChecks
                    .optJSONObject(
                        index
                    )
                    ?: continue

            when (
                item.optString(
                    "status"
                )
            ) {
                STATUS_PASS ->
                    passed++

                STATUS_WARNING ->
                    warnings++

                STATUS_UNKNOWN ->
                    unknown++

                STATUS_FAIL ->
                    failed++
            }
        }

        val recommendations =
            JSONArray()

        for (
            index in
            0 until filteredChecks.length()
        ) {
            val item =
                filteredChecks
                    .optJSONObject(
                        index
                    )
                    ?: continue

            if (
                item.optString(
                    "status"
                ) in
                setOf(
                    STATUS_WARNING,
                    STATUS_FAIL
                )
            ) {
                recommendationFor(
                    item.optString(
                        "id"
                    )
                )
                    ?.let {
                        recommendations.put(
                            it
                        )
                    }
            }
        }

        if (
            appDiagnostic != null &&
            !appDiagnostic.optBoolean(
                "success",
                false
            )
        ) {
            recommendations.put(
                "Для приложения «$appName» App Resolver не нашёл надёжного launcher-совпадения. Проверьте установку приложения и наличие запускаемой Activity."
            )
        }

        val overallStatus =
            when {
                failed > 0 ->
                    STATUS_FAIL

                warnings > 0 ->
                    STATUS_WARNING

                unknown > 0 ->
                    STATUS_UNKNOWN

                else ->
                    STATUS_PASS
            }

        return JSONObject()
            .put(
                "success",
                failed == 0
            )
            .put(
                "overall_status",
                overallStatus
            )
            .put(
                "focus",
                focus
            )
            .put(
                "focus_supported",
                focusSupported
            )
            .put(
                "passed",
                passed
            )
            .put(
                "warnings",
                warnings
            )
            .put(
                "unknown",
                unknown
            )
            .put(
                "failed",
                failed
            )
            .put(
                "checks",
                filteredChecks
            )
            .put(
                "app_diagnostic",
                appDiagnostic
                    ?: JSONObject.NULL
            )
            .put(
                "recommendations",
                recommendations
            )
            .put(
                "capability_snapshot",
                snapshot
            )
            .put(
                "generated_at",
                System.currentTimeMillis()
            )
    }

    fun compactReport(
        focus: String = "all",
        appName: String = ""
    ): String {

        val result =
            run(
                focus,
                appName
            )

        val base =
            "Самодиагностика: исправно=${result.optInt("passed")}, " +
                "внимание=${result.optInt("warnings")}, " +
                "нет данных=${result.optInt("unknown")}, " +
                "ошибки=${result.optInt("failed")}."

        val appPart =
            result
                .optJSONObject(
                    "app_diagnostic"
                )
                ?.takeIf {
                    it.length() >
                        0
                }
                ?.let {
                    app ->
                    if (
                        app.optBoolean(
                            "success",
                            false
                        )
                    ) {
                        " Приложение найдено: ${app.optString("label")} (${app.optString("package")}), confidence=${app.optInt("confidence")}."
                    } else {
                        " Приложение не разрешено однозначно: ${app.optString("reason")}."
                    }
                }
                .orEmpty()

        return base +
            appPart
    }

    private fun filterChecks(
        checks: JSONArray,
        focus: String
    ): Pair<JSONArray, Boolean> {

        val normalized =
            focus
                .lowercase()
                .trim()

        if (
            normalized.isBlank() ||
            normalized ==
            "all"
        ) {
            return checks to true
        }

        val acceptedIds =
            when (
                normalized
            ) {
                "android" ->
                    setOf(
                        "accessibility",
                        "screen_intelligence",
                        "overlay",
                        "app_resolver",
                        "recent_command_health"
                    )

                "audio" ->
                    setOf(
                        "microphone",
                        "stt",
                        "tts"
                    )

                "agent_core" ->
                    setOf(
                        "agent_core"
                    )

                "apps" ->
                    setOf(
                        "app_resolver",
                        "screen_intelligence"
                    )

                "memory" ->
                    setOf(
                        "memory"
                    )

                "tasks" ->
                    setOf(
                        "tasks",
                        "exact_alarm",
                        "notifications"
                    )

                else ->
                    emptySet()
            }

        if (
            acceptedIds.isEmpty()
        ) {
            return checks to false
        }

        val result =
            JSONArray()

        for (
            index in
            0 until checks.length()
        ) {
            val item =
                checks
                    .optJSONObject(
                        index
                    )
                    ?: continue

            if (
                item.optString(
                    "id"
                ) in
                acceptedIds
            ) {
                result.put(
                    item
                )
            }
        }

        return result to true
    }

    private fun addCheck(
        array: JSONArray,
        id: String,
        status: String,
        name: String,
        details: String
    ) {

        array.put(
            JSONObject()
                .put(
                    "id",
                    id
                )
                .put(
                    "status",
                    status
                )
                // Backward compatibility for older Worker/UI consumers.
                // Only a confirmed FAIL maps to ok=false.
                .put(
                    "ok",
                    status !=
                        STATUS_FAIL
                )
                .put(
                    "verified",
                    status ==
                        STATUS_PASS
                )
                .put(
                    "name",
                    name
                )
                .put(
                    "details",
                    details
                )
        )
    }

    private fun recommendationFor(
        id: String
    ): String? =
        when (
            id
        ) {
            "microphone" ->
                "Разрешите AYANA доступ к микрофону."

            "accessibility" ->
                "Включите службу AYANA в специальных возможностях Android."

            "screen_intelligence" ->
                "Если внешнее окно определяется, но текст отсутствует, Screen Perception остаётся WARNING/Нет данных; не ослабляйте strict verification ради PASS."

            "overlay" ->
                "Разрешите AYANA отображение поверх других приложений, если нужен глобальный Orb."

            "notifications" ->
                "Разрешите уведомления AYANA, чтобы напоминания были видимыми."

            "exact_alarm" ->
                "Разрешите точные будильники AYANA для напоминаний в точное время."

            "app_resolver" ->
                "Обновите карту приложений; если список остаётся пустым, проверьте launcher query/Android package visibility."

            "agent_core" ->
                "Проверьте интернет/Worker или повторите обычный запрос; медленные свежие ответы помечаются WARNING, а не PASS."

            "tts" ->
                "Проверьте Worker TTS и сеть, если Marin снова отвечает медленно или с ошибкой."

            "recent_command_health" ->
                "Посмотрите последнюю ERROR в истории команд и исправляйте класс причины, а не только конкретную фразу."

            else ->
                null
        }

    companion object {

        const val STATUS_PASS =
            "PASS"

        const val STATUS_WARNING =
            "WARNING"

        const val STATUS_UNKNOWN =
            "UNKNOWN"

        const val STATUS_FAIL =
            "FAIL"

        private const val HEALTH_FRESH_MS =
            30L *
                60L *
                1000L

        private const val AGENT_LATENCY_WARNING_MS =
            6000L

        private const val SCREEN_LATENCY_WARNING_MS =
            350L

        private const val TTS_FIRST_BYTE_WARNING_MS =
            2500L
    }
}
