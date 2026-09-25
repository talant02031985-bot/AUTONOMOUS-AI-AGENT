package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA R9.4 Multi-App Task Orchestrator v1.0.
 *
 * Pure orchestration layer above the already device-confirmed R9.3 App Integration
 * Registry and R9.2 Autonomous Task Graph. It does not execute Android actions by
 * itself and does not create a second device-control stack.
 *
 * Contract:
 * - parse only explicit multi-step app commands (2..5 registered steps);
 * - every step must be autonomousAllowed and non-MUTATION in the registry;
 * - Task Graph is checkpointed before and after each app step;
 * - AYANA restore evidence is part of step completion;
 * - Calendar remains DRAFT_ONLY and action_committed must remain false;
 * - first failed/unverified step stops the plan; no blind continuation/replay;
 * - no automatic restart authority is granted by this class.
 */
class AyanaMultiAppTaskOrchestrator(
    private val registry: AyanaAppIntegrationRegistry
) {

    data class TaskStep(
        val key: String,
        val label: String,
        val appKey: String,
        val actionKey: String,
        val payload: String = ""
    )

    data class TaskPlan(
        val key: String,
        val originalCommand: String,
        val source: String,
        val steps: List<TaskStep>,
        val acceptanceProbe: Boolean = false
    )

    fun parse(
        command: String
    ): TaskPlan? {
        val clean =
            command
                .trim()
                .replace(Regex("\\s+"), " ")

        if (clean.isBlank()) return null

        if (isAcceptanceCommand(clean)) {
            return acceptancePlan(clean)
        }

        val clauses = splitExplicitSteps(clean)
        if (clauses.size !in 2..MAX_STEPS) return null

        val parsed =
            clauses.mapIndexed { index, clause ->
                val action = registry.parse(clause) ?: return null

                if (
                    action.actionKey == AyanaAppIntegrationRegistry.ACTION_DESCRIBE ||
                    action.actionKey == AyanaAppIntegrationRegistry.ACTION_FIND_LOCAL
                ) {
                    return null
                }

                val spec =
                    registry.action(
                        action.appKey,
                        action.actionKey
                    ) ?: return null

                if (
                    !spec.autonomousAllowed ||
                    spec.commitSemantics ==
                    AyanaAppIntegrationRegistry.CommitSemantics.MUTATION
                ) {
                    return null
                }

                TaskStep(
                    key =
                        "step-${index + 1}-${action.appKey}-${action.actionKey}",
                    label =
                        "${index + 1}. ${action.appKey}:${action.actionKey}",
                    appKey = action.appKey,
                    actionKey = action.actionKey,
                    payload = action.payload
                )
            }

        if (parsed.map { it.appKey }.toSet().size < 2) {
            return null
        }

        return TaskPlan(
            key = "multi-app-user-task",
            originalCommand = clean,
            source = "explicit_multi_app_grammar",
            steps = parsed,
            acceptanceProbe = false
        )
    }

    fun plannerEnvelope(
        plan: TaskPlan
    ): JSONObject {
        val subgoals = JSONArray()

        plan.steps.forEach { step ->
            subgoals.put(
                JSONObject()
                    .put(
                        "goal",
                        "${step.appKey}:${step.actionKey}" +
                            if (step.payload.isBlank()) {
                                ""
                            } else {
                                " | ${step.payload.take(220)}"
                            }
                    )
            )
        }

        return JSONObject()
            .put("domain", "multi_app_navigation")
            .put("complexity", "multi_step")
            .put("orchestrator_version", VERSION)
            .put("plan_key", plan.key)
            .put("step_count", plan.steps.size)
            .put("acceptance_probe", plan.acceptanceProbe)
            .put("subgoals", subgoals)
            .put(
                "terminal_criterion",
                "Every app step is verified, AYANA restore is verified after every step, and no persistent mutation is committed"
            )
            .put("safe_auto_resume", false)
            .put("blind_replay_allowed", false)
    }

    fun selfTest(): Boolean {
        val explicit =
            parse(
                "найди в интернете AYANA затем найди в YouTube AYANA"
            ) ?: return false

        if (explicit.steps.size != 2) return false
        if (explicit.steps[0].appKey != AyanaAppIntegrationRegistry.APP_BROWSER) return false
        if (explicit.steps[1].appKey != AyanaAppIntegrationRegistry.APP_YOUTUBE) return false
        if (explicit.steps.any { !isStepSafe(it) }) return false

        val filesGallery =
            parse(
                "открой мои файлы потом открой галерею"
            ) ?: return false

        if (filesGallery.steps.size != 2) return false
        if (filesGallery.steps.map { it.appKey }.toSet().size != 2) return false

        val browserCalendar =
            parse(
                "открой сайт example.com после этого подготовь событие в календаре AYANA тест"
            ) ?: return false

        val calendar = browserCalendar.steps.last()
        if (calendar.appKey != AyanaAppIntegrationRegistry.APP_CALENDAR) return false
        if (calendar.actionKey != AyanaAppIntegrationRegistry.ACTION_CREATE_EVENT_DRAFT) return false

        // Single-app input must remain owned by the existing R9.3 route.
        if (parse("найди в YouTube AYANA") != null) return false

        // Personal local search remains owned by Personal Search.
        if (parse("найди файл отчет затем открой галерею") != null) return false

        val probe = acceptancePlan("проверь многошаговую работу приложений")
        if (probe.steps.size != ACCEPTANCE_STEP_COUNT) return false
        if (!probe.acceptanceProbe) return false
        if (probe.steps.any { !isStepSafe(it) }) return false

        val envelope = plannerEnvelope(probe)
        if (envelope.optJSONArray("subgoals")?.length() != ACCEPTANCE_STEP_COUNT) return false
        if (envelope.optBoolean("safe_auto_resume", true)) return false
        if (envelope.optBoolean("blind_replay_allowed", true)) return false

        return true
    }

    fun run(
        plan: TaskPlan,
        taskGraph: AyanaAutonomousTaskGraph,
        execute: (TaskStep) -> JSONObject,
        restore: (TaskStep) -> JSONObject,
        checkpoint: (String, Int, TaskStep?, JSONObject) -> Unit = { _, _, _, _ -> },
        shouldCancel: () -> Boolean = { false }
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        val results = JSONArray()

        var passed = 0
        var failed = 0
        var cancelled = false
        var mutationCommittedDetected = false
        var restoreFailureDetected = false

        checkpoint(
            "task_started",
            -1,
            null,
            stateEvidence(
                plan = plan,
                taskGraph = taskGraph,
                extra = JSONObject().put("started", true)
            )
        )

        for ((index, step) in plan.steps.withIndex()) {
            if (shouldCancel()) {
                cancelled = true
                taskGraph.finish(
                    success = false,
                    terminalStatus = "CANCELLED",
                    finalEvidence = "multi-app task cancelled before step ${index + 1}"
                )
                break
            }

            val actionSpec =
                registry.action(
                    step.appKey,
                    step.actionKey
                )

            val safeByRegistry =
                actionSpec != null &&
                    actionSpec.autonomousAllowed &&
                    actionSpec.commitSemantics !=
                    AyanaAppIntegrationRegistry.CommitSemantics.MUTATION

            if (!safeByRegistry) {
                failed += 1
                taskGraph.finish(
                    success = false,
                    terminalStatus = "BLOCKED",
                    finalEvidence = "registry blocked ${step.appKey}:${step.actionKey}"
                )
                results.put(
                    failureResult(
                        step = step,
                        index = index,
                        reason = "registry_blocked"
                    )
                )
                break
            }

            val signature =
                buildString {
                    append("multi_app|")
                    append(step.appKey)
                    append('|')
                    append(step.actionKey)
                    if (step.payload.isNotBlank()) {
                        append('|')
                        append(step.payload.take(220))
                    }
                }

            taskGraph.recordToolDispatch(
                toolName = "app_integration:${step.appKey}:${step.actionKey}",
                signature = signature,
                mayMutate = false
            )

            checkpoint(
                "before_step",
                index,
                step,
                stateEvidence(
                    plan = plan,
                    taskGraph = taskGraph,
                    extra =
                        JSONObject()
                            .put("signature", signature)
                            .put("registry_safe", true)
                )
            )

            val stepStartedAt = System.currentTimeMillis()

            val actionResult =
                try {
                    execute(step)
                } catch (error: Exception) {
                    JSONObject()
                        .put("success", false)
                        .put("verified", false)
                        .put("terminal_status", "ERROR")
                        .put("action_dispatched", false)
                        .put("message", error.message ?: error.javaClass.simpleName)
                }

            val actionVerified =
                actionResult.optBoolean("success", false) &&
                    actionResult.optBoolean("verified", false)

            val actionCommitted =
                actionResult.optBoolean(
                    "action_committed",
                    false
                )

            if (actionCommitted) {
                mutationCommittedDetected = true
                taskGraph.markReconciliationRequired(
                    toolName = "app_integration:${step.appKey}:${step.actionKey}",
                    detail = "Unexpected persistent commit was reported by a navigation/draft-only R9.4 step"
                )
            }

            val calendarGuard =
                if (
                    step.appKey == AyanaAppIntegrationRegistry.APP_CALENDAR &&
                    step.actionKey == AyanaAppIntegrationRegistry.ACTION_CREATE_EVENT_DRAFT
                ) {
                    actionResult.optBoolean("draft_only", false) &&
                        !actionCommitted
                } else {
                    !actionCommitted
                }

            val restoreResult =
                try {
                    restore(step)
                } catch (error: Exception) {
                    JSONObject()
                        .put("success", false)
                        .put("verified", false)
                        .put("terminal_status", "ERROR")
                        .put("message", error.message ?: error.javaClass.simpleName)
                }

            val restoreVerified =
                restoreResult.optBoolean("success", false) &&
                    restoreResult.optBoolean("verified", false)

            if (!restoreVerified) {
                restoreFailureDetected = true
            }

            val stepOk =
                actionVerified &&
                    calendarGuard &&
                    restoreVerified &&
                    !actionCommitted

            val evidence =
                JSONObject()
                    .put("action", JSONObject(actionResult.toString()))
                    .put("restore", JSONObject(restoreResult.toString()))
                    .put("calendar_no_commit_guard", calendarGuard)
                    .put("action_committed", actionCommitted)
                    .put("step_ok", stepOk)

            taskGraph.recordToolResult(
                toolName = "app_integration:${step.appKey}:${step.actionKey}",
                success = stepOk,
                verified = stepOk,
                terminalStatus = if (stepOk) "SUCCESS" else "ERROR",
                evidence = evidence.toString().take(1200),
                actionDispatched = actionResult.optBoolean("action_dispatched", false)
            )

            results.put(
                JSONObject()
                    .put("index", index + 1)
                    .put("key", step.key)
                    .put("label", step.label)
                    .put("app_key", step.appKey)
                    .put("action_key", step.actionKey)
                    .put("payload_present", step.payload.isNotBlank())
                    .put("action_verified", actionVerified)
                    .put("calendar_no_commit_guard", calendarGuard)
                    .put("restore_verified", restoreVerified)
                    .put("action_committed", actionCommitted)
                    .put("success", stepOk)
                    .put(
                        "duration_ms",
                        (System.currentTimeMillis() - stepStartedAt).coerceAtLeast(0L)
                    )
                    .put("action", JSONObject(actionResult.toString()))
                    .put("restore", JSONObject(restoreResult.toString()))
            )

            checkpoint(
                "after_step",
                index,
                step,
                stateEvidence(
                    plan = plan,
                    taskGraph = taskGraph,
                    extra = evidence
                )
            )

            if (stepOk) {
                passed += 1
            } else {
                failed += 1
                if (
                    mutationCommittedDetected ||
                    taskGraph.requiresReconciliation()
                ) {
                    taskGraph.pause(
                        "multi-app step ${index + 1} requires reconciliation; blind continuation blocked"
                    )
                } else {
                    taskGraph.finish(
                        success = false,
                        terminalStatus = "ERROR",
                        finalEvidence = "multi-app step ${index + 1} failed; blind continuation blocked"
                    )
                }
                break
            }

            if (shouldCancel()) {
                cancelled = true
                taskGraph.finish(
                    success = false,
                    terminalStatus = "CANCELLED",
                    finalEvidence = "multi-app task cancelled after step ${index + 1}"
                )
                break
            }
        }

        val allVerified =
            !cancelled &&
                failed == 0 &&
                passed == plan.steps.size &&
                !mutationCommittedDetected &&
                !restoreFailureDetected &&
                !taskGraph.requiresReconciliation()

        if (allVerified) {
            taskGraph.finish(
                success = true,
                terminalStatus = "SUCCESS",
                finalEvidence = "verified multi-app task completed ${passed}/${plan.steps.size}; AYANA restored after every step"
            )
        }

        val terminal =
            when {
                cancelled -> "CANCELLED"
                allVerified -> "SUCCESS"
                mutationCommittedDetected || taskGraph.requiresReconciliation() -> "PAUSED"
                else -> "ERROR"
            }

        val report =
            JSONObject()
                .put("version", VERSION)
                .put("plan_key", plan.key)
                .put("plan_source", plan.source)
                .put("acceptance_probe", plan.acceptanceProbe)
                .put("terminal_status", terminal)
                .put("success", allVerified)
                .put("verified", allVerified)
                .put("cancelled", cancelled)
                .put("steps_total", plan.steps.size)
                .put("steps_completed", passed + failed)
                .put("passed", passed)
                .put("failed", failed)
                .put("restore_after_every_step", true)
                .put("persistent_mutation_authority", false)
                .put("mutation_committed_detected", mutationCommittedDetected)
                .put("restore_failure_detected", restoreFailureDetected)
                .put("safe_auto_resume", false)
                .put("blind_replay_allowed", false)
                .put("task_graph", taskGraph.persistenceSnapshot())
                .put(
                    "duration_ms",
                    (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                )
                .put("results", results)

        checkpoint(
            "task_terminal",
            plan.steps.lastIndex,
            plan.steps.lastOrNull(),
            JSONObject(report.toString())
        )

        return report
    }

    private fun isStepSafe(
        step: TaskStep
    ): Boolean {
        val action =
            registry.action(
                step.appKey,
                step.actionKey
            ) ?: return false

        return action.autonomousAllowed &&
            action.commitSemantics !=
            AyanaAppIntegrationRegistry.CommitSemantics.MUTATION
    }

    private fun acceptancePlan(
        command: String
    ): TaskPlan =
        TaskPlan(
            key = "r9.4-device-acceptance",
            originalCommand = command.trim(),
            source = "r9_4_acceptance_command",
            acceptanceProbe = true,
            steps =
                listOf(
                    TaskStep(
                        key = "browser-search",
                        label = "Browser verified search",
                        appKey = AyanaAppIntegrationRegistry.APP_BROWSER,
                        actionKey = AyanaAppIntegrationRegistry.ACTION_SEARCH,
                        payload = ACCEPTANCE_QUERY
                    ),
                    TaskStep(
                        key = "youtube-search",
                        label = "YouTube verified search",
                        appKey = AyanaAppIntegrationRegistry.APP_YOUTUBE,
                        actionKey = AyanaAppIntegrationRegistry.ACTION_SEARCH,
                        payload = ACCEPTANCE_QUERY
                    ),
                    TaskStep(
                        key = "calendar-draft",
                        label = "Calendar verified unsaved draft",
                        appKey = AyanaAppIntegrationRegistry.APP_CALENDAR,
                        actionKey = AyanaAppIntegrationRegistry.ACTION_CREATE_EVENT_DRAFT,
                        payload = ACCEPTANCE_CALENDAR_TITLE
                    )
                )
        )

    private fun isAcceptanceCommand(
        value: String
    ): Boolean {
        val normalized = normalize(value)
        return normalized in
            setOf(
                "проверь многошаговую работу приложений",
                "протестируй многошаговую работу приложений",
                "проверь multi app task",
                "протестируй multi app task",
                "проверь multi app orchestration",
                "протестируй multi app orchestration"
            )
    }

    private fun splitExplicitSteps(
        value: String
    ): List<String> {
        val primary =
            value
                .split(
                    Regex(
                        """\s*(?:[,;]\s*)?(?:затем|потом|после этого|а затем|а потом)\s+""",
                        RegexOption.IGNORE_CASE
                    )
                )
                .map { it.trim() }
                .filter { it.isNotBlank() }

        if (primary.size >= 2) {
            return primary.take(MAX_STEPS + 1)
        }

        return value
            .split(
                Regex(
                    """\s+и\s+(?=(?:открой|запусти|покажи|найди|поищи|поиск|создай|добавь|подготовь)\b)""",
                    RegexOption.IGNORE_CASE
                )
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(MAX_STEPS + 1)
    }

    private fun stateEvidence(
        plan: TaskPlan,
        taskGraph: AyanaAutonomousTaskGraph,
        extra: JSONObject
    ): JSONObject =
        JSONObject()
            .put("orchestrator_version", VERSION)
            .put("plan_key", plan.key)
            .put("step_count", plan.steps.size)
            .put("task_graph", taskGraph.persistenceSnapshot())
            .put("extra", JSONObject(extra.toString()))

    private fun failureResult(
        step: TaskStep,
        index: Int,
        reason: String
    ): JSONObject =
        JSONObject()
            .put("index", index + 1)
            .put("key", step.key)
            .put("label", step.label)
            .put("app_key", step.appKey)
            .put("action_key", step.actionKey)
            .put("success", false)
            .put("verified", false)
            .put("reason", reason)

    private fun normalize(
        value: String
    ): String =
        value
            .trim()
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")

    companion object {
        const val VERSION = "1.0"
        const val MAX_STEPS = 5
        const val ACCEPTANCE_STEP_COUNT = 3

        const val ACCEPTANCE_QUERY =
            "AYANA R9.4 multi-app orchestration probe"

        const val ACCEPTANCE_CALENDAR_TITLE =
            "AYANA R9.4 multi-app orchestration probe — не сохранять"
    }
}
