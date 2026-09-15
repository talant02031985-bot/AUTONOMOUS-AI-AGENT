package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AYANA Acceptance Test Engine v3.1 — EXHAUSTIVE AUTONOMOUS TESTING.
 *
 * Separates three different truths that were previously conflated:
 * 1) QUICK_HEALTH — current runtime health right now;
 * 2) CAPABILITY_AUDIT — what this build implements / exposes / has device evidence for;
 * 3) FULL_ACCEPTANCE — a bounded local acceptance suite that executes live safe probes,
 *    reversible round-trips and pure contract checks without Agent Core round-trips;
 * 4) EXHAUSTIVE_ACCEPTANCE — the widest safe autonomous suite. It includes all
 *    FULL_ACCEPTANCE checks plus extended device, persistence, artifact, routing and
 *    bounded online Agent Core transport/context probes.
 *
 * A successful test RUN is not the same as an accepted agent. The returned `grade`
 * owns readiness truth, while `execution_success` only says the suite itself completed.
 *
 * v3.1 preserves fail-closed grading/reporting and extends exhaustive mode:
 * - all prior local health/audit/functional probes remain;
 * - network turns are counted from the actual probes rather than hard-coded zero;
 * - extended probes cover device-state evidence, single-network routing, history
 *   persistence, artifact round-trips, routing contracts and online Agent Core;
 * - tests that cannot be safely automated must return NO_DATA/BLOCKED instead of PASS;
 * - every reversible probe owns cleanup/restore before it may return PASS.
 */
class AyanaAcceptanceTestEngine(
    private val probeRunner: (String) -> JSONObject,
    private val shouldCancel: () -> Boolean = { false }
) {

    enum class Mode(
        val wireName: String
    ) {
        QUICK_HEALTH("quick_health"),
        CAPABILITY_AUDIT("capability_audit"),
        FULL_ACCEPTANCE("full_acceptance"),
        EXHAUSTIVE_ACCEPTANCE("exhaustive_acceptance");

        companion object {
            fun fromWireName(value: String): Mode =
                values().firstOrNull {
                    it.wireName == value
                } ?: QUICK_HEALTH
        }
    }

    private data class TestSpec(
        val id: String,
        val title: String,
        val probeId: String,
        val critical: Boolean,
        val modes: Set<Mode>
    )

    fun run(mode: Mode): JSONObject {
        val startedAt =
            System.currentTimeMillis()

        val tests =
            JSONArray()

        val probeCache =
            linkedMapOf<String, JSONObject>()

        var pass = 0
        var warning = 0
        var failed = 0
        var blocked = 0
        var unsupported = 0
        var noData = 0
        var cancelled = 0
        var criticalFailures = 0
        var networkTurns = 0

        val selected =
            TESTS.filter {
                mode in it.modes ||
                    (
                        mode == Mode.EXHAUSTIVE_ACCEPTANCE &&
                            Mode.FULL_ACCEPTANCE in it.modes
                        )
            }

        for (spec in selected) {
            if (shouldCancel()) {
                cancelled++
                tests.put(
                    JSONObject()
                        .put("id", spec.id)
                        .put("title", spec.title)
                        .put("status", STATUS_CANCELLED)
                        .put("critical", spec.critical)
                        .put("duration_ms", 0L)
                        .put("message", "Проверка отменена пользователем")
                        .put("evidence_scope", "none")
                )
                break
            }

            val testStartedAt =
                System.currentTimeMillis()

            val raw =
                try {
                    val cached =
                        probeCache[spec.probeId]

                    if (cached != null) {
                        JSONObject(cached.toString())
                    } else {
                        val fresh =
                            probeRunner(spec.probeId)

                        probeCache[spec.probeId] =
                            JSONObject(fresh.toString())

                        fresh
                    }
                } catch (error: Exception) {
                    JSONObject()
                        .put("status", STATUS_FAIL)
                        .put("success", false)
                        .put("verified", false)
                        .put("message", "Исключение проверки: ${error.message ?: error.javaClass.simpleName}")
                        .put("reason", "probe_exception")
                        .put("evidence_scope", "none")
                }

            networkTurns +=
                raw.optInt("network_turns", 0)
                    .coerceAtLeast(0)

            val status =
                normalizeStatus(raw)

            when (status) {
                STATUS_PASS -> pass++
                STATUS_WARNING -> warning++
                STATUS_FAIL -> {
                    failed++
                    if (spec.critical) {
                        criticalFailures++
                    }
                }
                STATUS_BLOCKED -> blocked++
                STATUS_UNSUPPORTED -> unsupported++
                STATUS_NO_DATA -> noData++
                STATUS_CANCELLED -> cancelled++
                else -> {
                    failed++
                    if (spec.critical) {
                        criticalFailures++
                    }
                }
            }

            val evidence =
                raw.optJSONObject("evidence")
                    ?: JSONObject()

            val testResult =
                JSONObject()
                    .put("id", spec.id)
                    .put("title", spec.title)
                    .put("status", status)
                    .put("critical", spec.critical)
                    .put(
                        "duration_ms",
                        (
                            System.currentTimeMillis() -
                                testStartedAt
                            ).coerceAtLeast(0L)
                    )
                    .put(
                        "message",
                        raw.optString("message")
                            .ifBlank {
                                raw.optString("reason")
                                    .ifBlank {
                                        status.lowercase(Locale.ROOT)
                                    }
                            }
                    )
                    .put(
                        "evidence_scope",
                        raw.optString("evidence_scope")
                            .ifBlank {
                                if (raw.optBoolean("verified", false)) {
                                    "live_verified"
                                } else {
                                    "runtime"
                                }
                            }
                    )
                    .put("verified", raw.optBoolean("verified", status == STATUS_PASS))
                    .put("evidence", evidence)

            tests.put(testResult)
        }

        val finishedAt =
            System.currentTimeMillis()

        val durationMs =
            (
                finishedAt -
                    startedAt
                ).coerceAtLeast(0L)

        val limits =
            try {
                probeRunner(PROBE_KNOWN_LIMITS)
                    .optJSONArray("limits")
                    ?: JSONArray()
            } catch (_: Exception) {
                JSONArray()
            }

        val grade =
            when {
                cancelled > 0 -> GRADE_CANCELLED
                criticalFailures > 0 -> GRADE_NOT_READY
                failed > 0 -> GRADE_NOT_READY
                blocked > 0 || unsupported > 0 || noData > 0 || warning > 0 ->
                    GRADE_READY_WITH_LIMITATIONS
                mode != Mode.QUICK_HEALTH && limits.length() > 0 ->
                    GRADE_READY_WITH_LIMITATIONS
                else -> GRADE_READY
            }

        val executionSuccess =
            cancelled == 0

        return JSONObject()
            .put("success", executionSuccess)
            .put("execution_success", executionSuccess)
            .put("engine", "AyanaAcceptanceTestEngine")
            .put("engine_version", ENGINE_VERSION)
            .put("report_schema_version", REPORT_SCHEMA_VERSION)
            .put("mode", mode.wireName)
            .put("started_at_ms", startedAt)
            .put("finished_at_ms", finishedAt)
            .put("network_turns", networkTurns)
            .put("tests_requested", selected.size)
            .put("tests_completed", tests.length())
            .put("passed", pass)
            .put("warnings", warning)
            .put("failed", failed)
            .put("blocked", blocked)
            .put("unsupported", unsupported)
            .put("no_data", noData)
            .put("cancelled", cancelled)
            .put("critical_failures", criticalFailures)
            .put("grade", grade)
            .put("duration_ms", durationMs)
            .put("tests", tests)
            .put("known_limits", limits)
            .put(
                "summary",
                buildSummary(
                    mode = mode,
                    passed = pass,
                    warnings = warning,
                    failed = failed,
                    blocked = blocked,
                    unsupported = unsupported,
                    noData = noData,
                    cancelled = cancelled,
                    grade = grade,
                    durationMs = durationMs,
                    networkTurns = networkTurns,
                    tests = tests,
                    limits = limits
                )
            )
            .put(
                "voice_summary",
                buildVoiceSummary(
                    mode = mode,
                    passed = pass,
                    warnings = warning,
                    failed = failed,
                    blocked = blocked,
                    unsupported = unsupported,
                    noData = noData,
                    grade = grade,
                    tests = tests
                )
            )
    }

    /**
     * Builds the user-facing diagnostic artifact from the exact machine result.
     * No PASS/FAIL status is re-inferred here: the report only renders the statuses
     * already produced by run(), preserving one source of truth.
     */
    fun buildDetailedReport(
        result: JSONObject
    ): String {
        val tests =
            result.optJSONArray("tests")
                ?: JSONArray()

        val limits =
            result.optJSONArray("known_limits")
                ?: JSONArray()

        val startedAt =
            result.optLong("started_at_ms", 0L)

        val finishedAt =
            result.optLong("finished_at_ms", 0L)

        val nonPass =
            mutableListOf<JSONObject>()

        for (index in 0 until tests.length()) {
            val item = tests.optJSONObject(index) ?: continue
            if (item.optString("status") != STATUS_PASS) {
                nonPass += item
            }
        }

        val priorityOrder =
            mapOf(
                STATUS_FAIL to 0,
                STATUS_BLOCKED to 1,
                STATUS_WARNING to 2,
                STATUS_UNSUPPORTED to 3,
                STATUS_NO_DATA to 4,
                STATUS_CANCELLED to 5
            )

        val prioritized =
            nonPass.sortedWith(
                compareBy<JSONObject> {
                    priorityOrder[it.optString("status")] ?: 99
                }.thenByDescending {
                    it.optBoolean("critical", false)
                }
            )

        return buildString {
            append(
                if (result.optString("mode") == Mode.EXHAUSTIVE_ACCEPTANCE.wireName) {
                    "AYANA EXHAUSTIVE AUTONOMOUS DIAGNOSTIC REPORT\n"
                } else {
                    "AYANA FULL DIAGNOSTIC REPORT\n"
                }
            )
            append("========================================\n")
            append("Engine: ${result.optString("engine", "AyanaAcceptanceTestEngine")} v${result.optString("engine_version", ENGINE_VERSION)}\n")
            append("Report schema: ${result.optString("report_schema_version", REPORT_SCHEMA_VERSION)}\n")
            append("Mode: ${result.optString("mode", "unknown")}\n")
            append("Started: ${formatReportTime(startedAt)}\n")
            append("Finished: ${formatReportTime(finishedAt)}\n")
            append("Duration: ${result.optLong("duration_ms", 0L)} ms\n")
            append("Agent Core network turns: ${result.optInt("network_turns", 0)}\n")
            append("Test execution completed: ${result.optBoolean("execution_success", false)}\n")
            append("Readiness grade: ${result.optString("grade", GRADE_NOT_READY)}\n")
            append("\n")
            append("COUNTS\n")
            append("----------------------------------------\n")
            append("PASS: ${result.optInt("passed", 0)}\n")
            append("WARNING: ${result.optInt("warnings", 0)}\n")
            append("FAIL: ${result.optInt("failed", 0)}\n")
            append("BLOCKED: ${result.optInt("blocked", 0)}\n")
            append("UNSUPPORTED: ${result.optInt("unsupported", 0)}\n")
            append("NO_DATA: ${result.optInt("no_data", 0)}\n")
            append("CANCELLED: ${result.optInt("cancelled", 0)}\n")
            append("Critical failures: ${result.optInt("critical_failures", 0)}\n")
            append("\n")
            append("IMPORTANT TRUTH CONTRACT\n")
            append("----------------------------------------\n")
            append("execution_success=true means the diagnostic suite itself finished.\n")
            append("It does NOT mean every AYANA function passed. Readiness is owned by grade and per-test statuses.\n")

            if (prioritized.isNotEmpty()) {
                append("\nPRIORITY FINDINGS\n")
                append("----------------------------------------\n")
                prioritized.forEachIndexed { index, item ->
                    append("${index + 1}. ${item.optString("status")} — ${item.optString("id")} — ${item.optString("title")}\n")
                    append("   Critical: ${item.optBoolean("critical", false)}\n")
                    append("   Message: ${item.optString("message").take(REPORT_MESSAGE_LIMIT)}\n")
                    append("   Evidence scope: ${item.optString("evidence_scope")}\n")
                }
            }

            append("\nALL TESTS\n")
            append("========================================\n")
            for (index in 0 until tests.length()) {
                val item = tests.optJSONObject(index) ?: continue
                append("[${index + 1}/${tests.length()}] ${item.optString("status")} — ${item.optString("id")} — ${item.optString("title")}\n")
                append("Critical: ${item.optBoolean("critical", false)}\n")
                append("Verified: ${item.optBoolean("verified", false)}\n")
                append("Duration: ${item.optLong("duration_ms", 0L)} ms\n")
                append("Evidence scope: ${item.optString("evidence_scope")}\n")
                append("Message: ${item.optString("message").take(REPORT_MESSAGE_LIMIT)}\n")

                val evidence = item.optJSONObject("evidence") ?: JSONObject()
                if (evidence.length() > 0) {
                    append("Evidence: ")
                    append(evidence.toString(2).take(REPORT_EVIDENCE_LIMIT))
                    append("\n")
                }
                append("----------------------------------------\n")
            }

            if (limits.length() > 0) {
                append("\nKNOWN LIMITS\n")
                append("========================================\n")
                for (index in 0 until limits.length()) {
                    val raw = limits.opt(index)
                    when (raw) {
                        is JSONObject -> {
                            append("${index + 1}. ")
                            append(
                                raw.optString("label")
                                    .ifBlank { raw.optString("id") }
                                    .ifBlank { raw.toString() }
                                    .take(REPORT_MESSAGE_LIMIT)
                            )
                            val note =
                                raw.optString("note")
                                    .ifBlank { raw.optString("next") }
                                    .ifBlank { raw.optString("message") }
                            if (note.isNotBlank()) {
                                append(" — ${note.take(REPORT_MESSAGE_LIMIT)}")
                            }
                            append("\n")
                        }

                        else ->
                            append("${index + 1}. ${raw?.toString().orEmpty().take(REPORT_MESSAGE_LIMIT)}\n")
                    }
                }
            }

            append("\nSUMMARY\n")
            append("========================================\n")
            append(result.optString("summary").take(REPORT_SUMMARY_LIMIT))
            append("\n")
        }
    }

    private fun formatReportTime(
        timestampMs: Long
    ): String {
        if (timestampMs <= 0L) {
            return "unknown"
        }

        return try {
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss Z",
                Locale.getDefault()
            ).format(Date(timestampMs))
        } catch (_: Exception) {
            timestampMs.toString()
        }
    }

    private fun normalizeStatus(
        raw: JSONObject
    ): String {
        val explicit =
            raw.optString("status")
                .uppercase(Locale.ROOT)

        if (
            explicit in setOf(
                STATUS_PASS,
                STATUS_WARNING,
                STATUS_FAIL,
                STATUS_BLOCKED,
                STATUS_UNSUPPORTED,
                STATUS_NO_DATA,
                STATUS_CANCELLED
            )
        ) {
            return explicit
        }

        val terminal =
            raw.optString("terminal_status")
                .uppercase(Locale.ROOT)

        if (terminal == "BLOCKED") {
            return STATUS_BLOCKED
        }

        if (terminal == "UNSUPPORTED") {
            return STATUS_UNSUPPORTED
        }

        if (terminal == "CANCELLED") {
            return STATUS_CANCELLED
        }

        val success =
            raw.optBoolean("success", false)

        val verified =
            raw.optBoolean("verified", success)

        return when {
            success && verified -> STATUS_PASS
            success -> STATUS_WARNING
            else -> STATUS_FAIL
        }
    }

    private fun buildSummary(
        mode: Mode,
        passed: Int,
        warnings: Int,
        failed: Int,
        blocked: Int,
        unsupported: Int,
        noData: Int,
        cancelled: Int,
        grade: String,
        durationMs: Long,
        networkTurns: Int,
        tests: JSONArray,
        limits: JSONArray
    ): String {
        val title =
            when (mode) {
                Mode.QUICK_HEALTH ->
                    "Быстрая локальная проверка AYANA завершена."

                Mode.CAPABILITY_AUDIT ->
                    "Локальный аудит возможностей AYANA завершён."

                Mode.FULL_ACCEPTANCE ->
                    "Полномасштабный локальный acceptance-test AYANA завершён."

                Mode.EXHAUSTIVE_ACCEPTANCE ->
                    "Всесторонняя автономная диагностика AYANA завершена."
            }

        val notable =
            mutableListOf<String>()

        for (index in 0 until tests.length()) {
            val item =
                tests.optJSONObject(index)
                    ?: continue

            val status =
                item.optString("status")

            if (
                status != STATUS_PASS &&
                notable.size < 5
            ) {
                notable +=
                    "${item.optString("title")}: ${russianStatus(status)} — ${item.optString("message").take(220)}"
            }
        }

        val limitsPreview =
            mutableListOf<String>()

        for (index in 0 until limits.length()) {
            if (limitsPreview.size >= 4) {
                break
            }

            val item =
                limits.optJSONObject(index)
                    ?: continue

            val label =
                item.optString("label")
                    .ifBlank {
                        item.optString("id")
                    }

            if (label.isNotBlank()) {
                limitsPreview += label
            }
        }

        return buildString {
            append(title)
            append("\n\n")
            append(
                "Итог: PASS $passed, WARNING $warnings, FAIL $failed, " +
                    "BLOCKED $blocked, UNSUPPORTED $unsupported, NO_DATA $noData"
            )

            if (cancelled > 0) {
                append(", CANCELLED $cancelled")
            }

            append(".\n")
            append("Статус готовности: ${russianGrade(grade)}.\n")
            append("Время теста: $durationMs мс. Сетевых обращений Agent Core/Worker: $networkTurns.")

            if (notable.isNotEmpty()) {
                append("\n\nТребуют внимания:\n")
                notable.forEachIndexed { index, value ->
                    append("${index + 1}. $value")
                    if (index < notable.lastIndex) {
                        append("\n")
                    }
                }
            }

            if (
                mode == Mode.CAPABILITY_AUDIT ||
                mode == Mode.FULL_ACCEPTANCE ||
                mode == Mode.EXHAUSTIVE_ACCEPTANCE
            ) {
                append("\n\nРезультаты проверок:\n")
                for (index in 0 until tests.length()) {
                    val item = tests.optJSONObject(index) ?: continue
                    append(
                        "${item.optString("status")} — ${item.optString("title")}"
                    )
                    val message = item.optString("message").trim()
                    if (message.isNotBlank()) {
                        append(": ")
                        append(message.take(260))
                    }
                    if (index < tests.length() - 1) {
                        append("\n")
                    }
                }
            }

            if (limitsPreview.isNotEmpty()) {
                append("\n\nИзвестные ограничения сборки: ")
                append(limitsPreview.joinToString("; "))
                if (limits.length() > limitsPreview.size) {
                    append("; и ещё ${limits.length() - limitsPreview.size}")
                }
                append(".")
            }
        }
    }

    private fun buildVoiceSummary(
        mode: Mode,
        passed: Int,
        warnings: Int,
        failed: Int,
        blocked: Int,
        unsupported: Int,
        noData: Int,
        grade: String,
        tests: JSONArray
    ): String {
        val firstProblem =
            (0 until tests.length())
                .mapNotNull { index -> tests.optJSONObject(index) }
                .firstOrNull { item -> item.optString("status") != STATUS_PASS }

        return buildString {
            append(
                when (mode) {
                    Mode.QUICK_HEALTH -> "Быстрая проверка завершена."
                    Mode.CAPABILITY_AUDIT -> "Аудит возможностей завершён."
                    Mode.FULL_ACCEPTANCE -> "Полномасштабный локальный тест завершён."
                    Mode.EXHAUSTIVE_ACCEPTANCE -> "Всесторонняя автономная диагностика завершена."
                }
            )
            append(
                " PASS $passed, предупреждений $warnings, ошибок $failed, " +
                    "заблокировано $blocked, не поддерживается $unsupported, нет данных $noData."
            )
            append(" Статус готовности: ${russianGrade(grade)}.")
            if (firstProblem != null) {
                append(
                    " Главное замечание: ${firstProblem.optString("title")} — " +
                        firstProblem.optString("message").take(180)
                )
            }
            if (mode == Mode.EXHAUSTIVE_ACCEPTANCE) {
                append(" Расширенный режим включает ограниченные сетевые проверки Agent Core.")
            } else {
                append(" Agent Core для этой проверки не вызывался.")
            }
        }
    }

    private fun russianStatus(status: String): String =
        when (status) {
            STATUS_PASS -> "пройдено"
            STATUS_WARNING -> "предупреждение"
            STATUS_FAIL -> "ошибка"
            STATUS_BLOCKED -> "заблокировано"
            STATUS_UNSUPPORTED -> "не поддерживается"
            STATUS_NO_DATA -> "нет данных"
            STATUS_CANCELLED -> "отменено"
            else -> status
        }

    private fun russianGrade(grade: String): String =
        when (grade) {
            GRADE_READY -> "READY"
            GRADE_READY_WITH_LIMITATIONS -> "READY WITH LIMITATIONS"
            GRADE_NOT_READY -> "NOT READY"
            GRADE_CANCELLED -> "CANCELLED"
            else -> grade
        }

    companion object {
        const val ENGINE_VERSION = "3.1"
        const val REPORT_SCHEMA_VERSION = "3.1"

        const val STATUS_PASS = "PASS"
        const val STATUS_WARNING = "WARNING"
        const val STATUS_FAIL = "FAIL"
        const val STATUS_BLOCKED = "BLOCKED"
        const val STATUS_UNSUPPORTED = "UNSUPPORTED"
        const val STATUS_NO_DATA = "NO_DATA"
        const val STATUS_CANCELLED = "CANCELLED"

        const val GRADE_READY = "READY"
        const val GRADE_READY_WITH_LIMITATIONS = "READY_WITH_LIMITATIONS"
        const val GRADE_NOT_READY = "NOT_READY"
        const val GRADE_CANCELLED = "CANCELLED"

        const val PROBE_DIAGNOSTICS_LIVE = "diagnostics_live"
        const val PROBE_RUNTIME_CAPABILITIES = "runtime_capabilities"
        const val PROBE_SCREEN_PERCEPTION = "screen_perception"
        const val PROBE_AGENT_CORE_LATENCY = "agent_core_latency"
        const val PROBE_NOTIFICATION_ACCESS = "notification_access"
        const val PROBE_CAPABILITY_REGISTRY_INTEGRITY = "capability_registry_integrity"
        const val PROBE_AUTONOMY_FOUNDATION = "autonomy_foundation"
        const val PROBE_PERCEPTION_TRUTH = "perception_truth"
        const val PROBE_ARTIFACT_DOCUMENT_TRUTH = "artifact_document_truth"
        const val PROBE_MULTIMODAL_TRUTH = "multimodal_truth"
        const val PROBE_DEVELOPMENT_TRUTH = "development_truth"
        const val PROBE_EXTERNAL_INTEGRATION_TRUTH = "external_integration_truth"
        const val PROBE_APP_RESOLVER = "app_resolver"
        const val PROBE_PLANNER = "planner"
        const val PROBE_COMPLETION_CONTRACT = "completion_contract"
        const val PROBE_SAFETY_ENGINE = "safety_engine"
        const val PROBE_DURABLE_GOALS = "durable_goals"
        const val PROBE_MEMORY_ROUNDTRIP = "memory_roundtrip"
        const val PROBE_REMINDER_ROUNDTRIP = "reminder_roundtrip"
        const val PROBE_NOTIFICATION_READ = "notification_read"
        const val PROBE_VOLUME_ROUNDTRIP = "volume_roundtrip"
        const val PROBE_BRIGHTNESS_ROUNDTRIP = "brightness_roundtrip"
        const val PROBE_SETTINGS_ROUNDTRIP = "settings_roundtrip"
        const val PROBE_FOREGROUND_FUSION = "foreground_fusion"
        const val PROBE_WHOLE_GOAL_ROUTING = "whole_goal_routing"
        const val PROBE_DIAGNOSTICS_DETAIL = "diagnostics_detail"
        const val PROBE_RELEASE_METADATA = "release_metadata"
        const val PROBE_DEVICE_STATE_SCHEMA = "device_state_schema"
        const val PROBE_BATTERY_SANITY = "battery_sanity"
        const val PROBE_STORAGE_SANITY = "storage_sanity"
        const val PROBE_ORIENTATION_SANITY = "orientation_sanity"
        const val PROBE_NETWORK_LIVE = "network_live"
        const val PROBE_NETWORK_SINGLE_ROUTING = "network_single_routing"
        const val PROBE_REQUIRED_FACT_GATE = "required_fact_gate"
        const val PROBE_CALCULATOR = "calculator_contract"
        const val PROBE_NOTIFICATION_ROUTING = "notification_routing"
        const val PROBE_EXACT_VOLUME_ROUTING = "exact_volume_routing"
        const val PROBE_HISTORY_SHORT = "history_short_roundtrip"
        const val PROBE_HISTORY_LONG = "history_long_roundtrip"
        const val PROBE_ARTIFACT_TXT = "artifact_txt_roundtrip"
        const val PROBE_ARTIFACT_DOCX = "artifact_docx_roundtrip"
        const val PROBE_ARTIFACT_PDF = "artifact_pdf_roundtrip"
        const val PROBE_ARTIFACT_XLSX = "artifact_xlsx_roundtrip"
        const val PROBE_ARTIFACT_JPEG = "artifact_jpeg_roundtrip"
        const val PROBE_ARTIFACT_GRAPH = "artifact_graph_roundtrip"
        const val PROBE_UNKNOWN_APP = "unknown_app_negative"
        const val PROBE_SPEED_MOBILE_PRECHECK = "speed_mobile_precheck"
        const val PROBE_HISTORY_LIVE_REFRESH = "history_live_refresh_coverage"
        const val PROBE_DIAGNOSTIC_ROUTING = "diagnostic_command_routing"
        const val PROBE_WAKE_GRAMMAR = "wake_word_grammar"
        const val PROBE_STOP_GRAMMAR = "stop_grammar"
        const val PROBE_SHUTDOWN_GRAMMAR = "shutdown_grammar"
        const val PROBE_AGENT_CORE_ORDINARY = "agent_core_online_ordinary"
        const val PROBE_AGENT_CORE_DEEP = "agent_core_online_deep"
        const val PROBE_AGENT_CORE_CONTEXT = "agent_core_online_context"
        const val PROBE_KNOWN_LIMITS = "known_limits"

        private const val REPORT_MESSAGE_LIMIT = 1200
        private const val REPORT_EVIDENCE_LIMIT = 5000
        private const val REPORT_SUMMARY_LIMIT = 12000

        private val TESTS =
            listOf(
                TestSpec(
                    id = "HEALTH-001",
                    title = "Self-Diagnostics runtime",
                    probeId = PROBE_DIAGNOSTICS_LIVE,
                    critical = true,
                    modes = setOf(Mode.QUICK_HEALTH, Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "HEALTH-002",
                    title = "Capability Registry runtime",
                    probeId = PROBE_RUNTIME_CAPABILITIES,
                    critical = true,
                    modes = setOf(Mode.QUICK_HEALTH, Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "HEALTH-003",
                    title = "Screen perception",
                    probeId = PROBE_SCREEN_PERCEPTION,
                    critical = false,
                    modes = setOf(Mode.QUICK_HEALTH, Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "HEALTH-004",
                    title = "Agent Core latency telemetry",
                    probeId = PROBE_AGENT_CORE_LATENCY,
                    critical = false,
                    modes = setOf(Mode.QUICK_HEALTH, Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "HEALTH-005",
                    title = "Notification listener access",
                    probeId = PROBE_NOTIFICATION_ACCESS,
                    critical = false,
                    modes = setOf(Mode.QUICK_HEALTH, Mode.FULL_ACCEPTANCE)
                ),

                TestSpec(
                    id = "AUDIT-001",
                    title = "Capability Registry schema integrity",
                    probeId = PROBE_CAPABILITY_REGISTRY_INTEGRITY,
                    critical = true,
                    modes = setOf(Mode.CAPABILITY_AUDIT, Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "AUDIT-002",
                    title = "Autonomous execution foundation",
                    probeId = PROBE_AUTONOMY_FOUNDATION,
                    critical = true,
                    modes = setOf(Mode.CAPABILITY_AUDIT, Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "AUDIT-003",
                    title = "Perception / foreground truth",
                    probeId = PROBE_PERCEPTION_TRUTH,
                    critical = false,
                    modes = setOf(Mode.CAPABILITY_AUDIT, Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "AUDIT-004",
                    title = "File & Document Engine truth",
                    probeId = PROBE_ARTIFACT_DOCUMENT_TRUTH,
                    critical = false,
                    modes = setOf(Mode.CAPABILITY_AUDIT, Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "AUDIT-005",
                    title = "Multimodal capability truth",
                    probeId = PROBE_MULTIMODAL_TRUTH,
                    critical = false,
                    modes = setOf(Mode.CAPABILITY_AUDIT, Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "AUDIT-006",
                    title = "Development Agent negative capability truth",
                    probeId = PROBE_DEVELOPMENT_TRUTH,
                    critical = true,
                    modes = setOf(Mode.CAPABILITY_AUDIT, Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "AUDIT-007",
                    title = "External integration negative capability truth",
                    probeId = PROBE_EXTERNAL_INTEGRATION_TRUTH,
                    critical = true,
                    modes = setOf(Mode.CAPABILITY_AUDIT, Mode.FULL_ACCEPTANCE)
                ),

                TestSpec(
                    id = "FUNC-001",
                    title = "App Resolver live round-trip",
                    probeId = PROBE_APP_RESOLVER,
                    critical = true,
                    modes = setOf(Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "FUNC-002",
                    title = "Planner envelope generation",
                    probeId = PROBE_PLANNER,
                    critical = true,
                    modes = setOf(Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "FUNC-003",
                    title = "Completion Contract false-SUCCESS guard",
                    probeId = PROBE_COMPLETION_CONTRACT,
                    critical = true,
                    modes = setOf(Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "FUNC-004",
                    title = "Safety Engine policy guard",
                    probeId = PROBE_SAFETY_ENGINE,
                    critical = true,
                    modes = setOf(Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "FUNC-005",
                    title = "Durable Goal store integrity",
                    probeId = PROBE_DURABLE_GOALS,
                    critical = true,
                    modes = setOf(Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "FUNC-006",
                    title = "Memory v2 write/read/delete round-trip",
                    probeId = PROBE_MEMORY_ROUNDTRIP,
                    critical = true,
                    modes = setOf(Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "FUNC-007",
                    title = "Reminder create/schedule/delete round-trip",
                    probeId = PROBE_REMINDER_ROUNDTRIP,
                    critical = true,
                    modes = setOf(Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "FUNC-008",
                    title = "Notification read-only probe",
                    probeId = PROBE_NOTIFICATION_READ,
                    critical = false,
                    modes = setOf(Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "FUNC-009",
                    title = "Media volume reversible round-trip",
                    probeId = PROBE_VOLUME_ROUNDTRIP,
                    critical = true,
                    modes = setOf(Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "FUNC-010",
                    title = "Screen brightness reversible round-trip",
                    probeId = PROBE_BRIGHTNESS_ROUNDTRIP,
                    critical = false,
                    modes = setOf(Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "FUNC-011",
                    title = "Samsung Settings verified navigation + restore",
                    probeId = PROBE_SETTINGS_ROUNDTRIP,
                    critical = true,
                    modes = setOf(Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "FUNC-012",
                    title = "Foreground owner / overlay fusion",
                    probeId = PROBE_FOREGROUND_FUSION,
                    critical = true,
                    modes = setOf(Mode.FULL_ACCEPTANCE)
                ),
                TestSpec(
                    id = "FUNC-013",
                    title = "Whole-goal routing / completion integrity",
                    probeId = PROBE_WHOLE_GOAL_ROUTING,
                    critical = true,
                    modes = setOf(Mode.FULL_ACCEPTANCE)
                ),

                // v3 exhaustive-only coverage. These probes are deliberately excluded
                // from ordinary FULL_ACCEPTANCE so the fast deterministic suite remains stable.
                TestSpec(
                    id = "EXT-001",
                    title = "Self-Diagnostics full issue disclosure",
                    probeId = PROBE_DIAGNOSTICS_DETAIL,
                    critical = false,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-002",
                    title = "Release / runtime metadata consistency",
                    probeId = PROBE_RELEASE_METADATA,
                    critical = false,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-003",
                    title = "Device-state schema completeness",
                    probeId = PROBE_DEVICE_STATE_SCHEMA,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-004",
                    title = "Battery live sanity",
                    probeId = PROBE_BATTERY_SANITY,
                    critical = false,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-005",
                    title = "Storage live sanity",
                    probeId = PROBE_STORAGE_SANITY,
                    critical = false,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-006",
                    title = "Orientation live sanity",
                    probeId = PROBE_ORIENTATION_SANITY,
                    critical = false,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-007",
                    title = "Network sensor vs direct reachability",
                    probeId = PROBE_NETWORK_LIVE,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-008",
                    title = "Single NETWORK local routing contract",
                    probeId = PROBE_NETWORK_SINGLE_ROUTING,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-009",
                    title = "Required device fact / false-SUCCESS gate",
                    probeId = PROBE_REQUIRED_FACT_GATE,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-010",
                    title = "Local calculator contract",
                    probeId = PROBE_CALCULATOR,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-011",
                    title = "Notification local routing contract",
                    probeId = PROBE_NOTIFICATION_ROUTING,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-012",
                    title = "Exact media-volume routing contract",
                    probeId = PROBE_EXACT_VOLUME_ROUTING,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-013",
                    title = "Command History short persistence round-trip",
                    probeId = PROBE_HISTORY_SHORT,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-014",
                    title = "Command History long result persistence >2500",
                    probeId = PROBE_HISTORY_LONG,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-015",
                    title = "TXT artifact real create/verify/cleanup",
                    probeId = PROBE_ARTIFACT_TXT,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-016",
                    title = "DOCX artifact real create/verify/cleanup",
                    probeId = PROBE_ARTIFACT_DOCX,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-017",
                    title = "PDF artifact real create/verify/cleanup",
                    probeId = PROBE_ARTIFACT_PDF,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-018",
                    title = "XLSX artifact real create/verify/cleanup",
                    probeId = PROBE_ARTIFACT_XLSX,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-019",
                    title = "JPEG artifact real create/verify/cleanup",
                    probeId = PROBE_ARTIFACT_JPEG,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-020",
                    title = "Graph artifact real create/verify/cleanup",
                    probeId = PROBE_ARTIFACT_GRAPH,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-021",
                    title = "Unknown app negative resolver",
                    probeId = PROBE_UNKNOWN_APP,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-022",
                    title = "Mobile speed-test transport precheck contract",
                    probeId = PROBE_SPEED_MOBILE_PRECHECK,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-023",
                    title = "History live-refresh autonomous coverage",
                    probeId = PROBE_HISTORY_LIVE_REFRESH,
                    critical = false,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-024",
                    title = "Full-diagnostics command routing",
                    probeId = PROBE_DIAGNOSTIC_ROUTING,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-025",
                    title = "Wake-word grammar contract",
                    probeId = PROBE_WAKE_GRAMMAR,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-026",
                    title = "STOP grammar contract",
                    probeId = PROBE_STOP_GRAMMAR,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "EXT-027",
                    title = "AYANA shutdown grammar contract",
                    probeId = PROBE_SHUTDOWN_GRAMMAR,
                    critical = false,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "ONLINE-001",
                    title = "Agent Core ordinary online response",
                    probeId = PROBE_AGENT_CORE_ORDINARY,
                    critical = false,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "ONLINE-002",
                    title = "Agent Core long/deep transport",
                    probeId = PROBE_AGENT_CORE_DEEP,
                    critical = true,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                ),
                TestSpec(
                    id = "ONLINE-003",
                    title = "Agent Core follow-up + topic-boundary context",
                    probeId = PROBE_AGENT_CORE_CONTEXT,
                    critical = false,
                    modes = setOf(Mode.EXHAUSTIVE_ACCEPTANCE)
                )
            )
    }
}
