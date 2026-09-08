package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA Acceptance Test Engine v1.0.
 *
 * Separates three different truths that were previously conflated:
 * 1) QUICK_HEALTH — current runtime health right now;
 * 2) CAPABILITY_AUDIT — what this build implements / exposes / has device evidence for;
 * 3) FULL_ACCEPTANCE — a bounded local acceptance suite that executes live safe probes,
 *    reversible round-trips and pure contract checks without Agent Core round-trips.
 *
 * A successful test RUN is not the same as an accepted agent. The returned `grade`
 * owns readiness truth, while `execution_success` only says the suite itself completed.
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
        FULL_ACCEPTANCE("full_acceptance");

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

        val selected =
            TESTS.filter {
                mode in it.modes
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

        val durationMs =
            (
                System.currentTimeMillis() -
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
            .put("mode", mode.wireName)
            .put("network_turns", 0)
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
            append("Время локального теста: $durationMs мс. Сетевых обращений Agent Core: 0.")

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
                mode == Mode.FULL_ACCEPTANCE
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
            append(" Agent Core для этой проверки не вызывался.")
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
        const val ENGINE_VERSION = "1.0"

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
        const val PROBE_KNOWN_LIMITS = "known_limits"

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
                )
            )
    }
}
