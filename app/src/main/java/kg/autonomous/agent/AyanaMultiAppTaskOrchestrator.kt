package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA R9.5 Multi-App Task Orchestrator v1.1 — VERIFIED RESULT TRANSFER.
 *
 * Extends the device-confirmed R9.4 orchestrator without creating a second
 * execution stack. Android dispatch remains in AyanaVoiceService; this class
 * owns only deterministic plan/ledger semantics.
 *
 * Contract:
 * - preserve all R9.4 2..5-step registered app orchestration;
 * - every app step remains autonomousAllowed and non-MUTATION;
 * - Task Graph + Durable Goal checkpoints remain mandatory;
 * - optional result capture happens only after a verified source action;
 * - transfer payloads are rendered only from AyanaVerifiedResultTransfer records;
 * - a capture/binding failure stops the plan fail-closed;
 * - AYANA restore evidence remains part of every dispatched step;
 * - Calendar remains DRAFT_ONLY and action_committed must remain false;
 * - no blind continuation, replay or automatic restart authority is granted.
 */
class AyanaMultiAppTaskOrchestrator(
    private val registry: AyanaAppIntegrationRegistry,
    private val resultTransfer: AyanaVerifiedResultTransfer
) {

    data class TaskStep(
        val key: String,
        val label: String,
        val appKey: String,
        val actionKey: String,
        val payload: String = "",
        val captureSpec: AyanaVerifiedResultTransfer.CaptureSpec? = null,
        val bindingSpec: AyanaVerifiedResultTransfer.BindingSpec? = null
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

        if (isResultTransferAcceptanceCommand(clean)) {
            return resultTransferAcceptancePlan(clean)
        }

        parseVerifiedTransferUserPlan(clean)
            ?.let { return it }

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
            .put(
                "result_capture_count",
                plan.steps.count { it.captureSpec != null }
            )
            .put(
                "result_binding_count",
                plan.steps.count { it.bindingSpec != null }
            )
            .put(
                "verified_result_transfer",
                plan.steps.any {
                    it.captureSpec != null ||
                        it.bindingSpec != null
                }
            )
            .put("subgoals", subgoals)
            .put(
                "terminal_criterion",
                "Every app step is verified, AYANA restore is verified after every step, and no persistent mutation is committed"
            )
            .put("safe_auto_resume", false)
            .put("blind_replay_allowed", false)
            .put("transfer_requires_verified_provenance", true)
            .put("transfer_persistent_mutation_authority", false)
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

        val transferProbe =
            resultTransferAcceptancePlan(
                "проверь перенос результата между приложениями"
            )

        if (transferProbe.steps.size != RESULT_TRANSFER_ACCEPTANCE_STEP_COUNT) return false
        if (!transferProbe.acceptanceProbe) return false
        if (transferProbe.steps.first().captureSpec == null) return false
        if (transferProbe.steps.drop(1).any { it.bindingSpec == null }) return false

        val transferEnvelope = plannerEnvelope(transferProbe)
        if (!transferEnvelope.optBoolean("verified_result_transfer", false)) return false
        if (transferEnvelope.optInt("result_capture_count", 0) != 1) return false
        if (transferEnvelope.optInt("result_binding_count", 0) != 2) return false
        if (!transferEnvelope.optBoolean("transfer_requires_verified_provenance", false)) return false

        val userTransfer =
            parse(
                "открой сайт example.com затем найди в YouTube название страницы"
            ) ?: return false

        if (userTransfer.steps.size != 2) return false
        if (userTransfer.steps[0].captureSpec?.kind != AyanaVerifiedResultTransfer.CaptureKind.SCREEN_TITLE) {
            return false
        }
        if (userTransfer.steps[1].bindingSpec == null) return false

        return resultTransfer.selfTest()
    }

    fun run(
        plan: TaskPlan,
        taskGraph: AyanaAutonomousTaskGraph,
        execute: (TaskStep) -> JSONObject,
        observe: (TaskStep, JSONObject) -> JSONObject = { _, _ -> JSONObject() },
        restore: (TaskStep) -> JSONObject,
        checkpoint: (String, Int, TaskStep?, JSONObject) -> Unit = { _, _, _, _ -> },
        shouldCancel: () -> Boolean = { false }
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        val results = JSONArray()
        val transferLedger = JSONObject()

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

            val bindingResult =
                if (step.bindingSpec != null) {
                    resultTransfer.bind(
                        spec = step.bindingSpec,
                        ledger = transferLedger
                    )
                } else {
                    JSONObject()
                        .put("success", true)
                        .put("verified", true)
                        .put("payload", step.payload)
                        .put("reason", "static_payload")
                }

            if (
                !bindingResult.optBoolean("success", false) ||
                !bindingResult.optBoolean("verified", false)
            ) {
                failed += 1

                taskGraph.recordToolDispatch(
                    toolName = "result_transfer:bind",
                    signature =
                        "result_transfer|bind|${step.bindingSpec?.transferKey.orEmpty()}",
                    mayMutate = false
                )
                taskGraph.recordToolResult(
                    toolName = "result_transfer:bind",
                    success = false,
                    verified = false,
                    terminalStatus = "ERROR",
                    evidence = bindingResult.toString().take(1200),
                    actionDispatched = false
                )
                taskGraph.finish(
                    success = false,
                    terminalStatus = "ERROR",
                    finalEvidence =
                        "result transfer binding failed before multi-app step ${index + 1}"
                )

                results.put(
                    failureResult(
                        step = step,
                        index = index,
                        reason =
                            "result_binding_failed:" +
                                bindingResult.optString("reason")
                    )
                        .put(
                            "binding",
                            JSONObject(bindingResult.toString())
                        )
                )
                break
            }

            val resolvedPayload =
                bindingResult
                    .optString("payload", step.payload)
                    .trim()

            val resolvedStep =
                step.copy(
                    payload = resolvedPayload
                )

            val signature =
                buildString {
                    append("multi_app|")
                    append(resolvedStep.appKey)
                    append('|')
                    append(resolvedStep.actionKey)
                    if (resolvedStep.payload.isNotBlank()) {
                        append('|')
                        append(resolvedStep.payload.take(220))
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
                resolvedStep,
                stateEvidence(
                    plan = plan,
                    taskGraph = taskGraph,
                    extra =
                        JSONObject()
                            .put("signature", signature)
                            .put("registry_safe", true)
                            .put(
                                "binding",
                                JSONObject(bindingResult.toString())
                            )
                            .put(
                                "transfer_ledger",
                                JSONObject(transferLedger.toString())
                            )
                )
            )

            val stepStartedAt = System.currentTimeMillis()

            val actionResult =
                try {
                    execute(resolvedStep)
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

            val observation =
                if (
                    actionVerified &&
                    resolvedStep.captureSpec != null
                ) {
                    try {
                        observe(
                            resolvedStep,
                            actionResult
                        )
                    } catch (error: Exception) {
                        JSONObject()
                            .put("success", false)
                            .put("verified", false)
                            .put(
                                "reason",
                                error.message ?: error.javaClass.simpleName
                            )
                    }
                } else {
                    JSONObject()
                        .put("success", true)
                        .put("verified", true)
                        .put("reason", "capture_not_requested")
                }

            val captureResult =
                if (resolvedStep.captureSpec != null) {
                    resultTransfer.capture(
                        spec = resolvedStep.captureSpec,
                        sourceStepKey = resolvedStep.key,
                        appKey = resolvedStep.appKey,
                        actionKey = resolvedStep.actionKey,
                        actionResult = actionResult,
                        observation = observation
                    )
                } else {
                    JSONObject()
                        .put("success", true)
                        .put("verified", true)
                        .put("reason", "capture_not_requested")
                }

            val captureVerified =
                resolvedStep.captureSpec == null ||
                    (
                        captureResult.optBoolean("success", false) &&
                            captureResult.optBoolean("verified", false) &&
                            resultTransfer.store(
                                ledger = transferLedger,
                                record = captureResult
                            )
                        )

            val restoreResult =
                try {
                    restore(resolvedStep)
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
                    captureVerified &&
                    restoreVerified &&
                    !actionCommitted

            val evidence =
                JSONObject()
                    .put("action", JSONObject(actionResult.toString()))
                    .put("observation", JSONObject(observation.toString()))
                    .put("capture", JSONObject(captureResult.toString()))
                    .put("binding", JSONObject(bindingResult.toString()))
                    .put("transfer_ledger", JSONObject(transferLedger.toString()))
                    .put("calendar_no_commit_guard", calendarGuard)
                    .put("capture_verified", captureVerified)
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
                    .put("payload_present", resolvedStep.payload.isNotBlank())
                    .put("resolved_payload", resolvedStep.payload.take(240))
                    .put("action_verified", actionVerified)
                    .put("calendar_no_commit_guard", calendarGuard)
                    .put("capture_requested", resolvedStep.captureSpec != null)
                    .put("capture_verified", captureVerified)
                    .put(
                        "binding_requested",
                        resolvedStep.bindingSpec != null
                    )
                    .put("restore_verified", restoreVerified)
                    .put("action_committed", actionCommitted)
                    .put("success", stepOk)
                    .put(
                        "duration_ms",
                        (System.currentTimeMillis() - stepStartedAt).coerceAtLeast(0L)
                    )
                    .put("action", JSONObject(actionResult.toString()))
                    .put("observation", JSONObject(observation.toString()))
                    .put("capture", JSONObject(captureResult.toString()))
                    .put("binding", JSONObject(bindingResult.toString()))
                    .put("restore", JSONObject(restoreResult.toString()))
            )

            checkpoint(
                "after_step",
                index,
                resolvedStep,
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
                .put(
                    "result_transfer_version",
                    AyanaVerifiedResultTransfer.VERSION
                )
                .put(
                    "transfer_count",
                    transferLedger.length()
                )
                .put(
                    "transfer_ledger",
                    JSONObject(transferLedger.toString())
                )
                .put(
                    "transfer_requires_verified_provenance",
                    true
                )
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

    private fun resultTransferAcceptancePlan(
        command: String
    ): TaskPlan =
        TaskPlan(
            key = "r9.5-result-transfer-device-acceptance",
            originalCommand = command.trim(),
            source = "r9_5_result_transfer_acceptance_command",
            acceptanceProbe = true,
            steps =
                listOf(
                    TaskStep(
                        key = "browser-example-domain",
                        label = "Browser verified Example Domain observation",
                        appKey = AyanaAppIntegrationRegistry.APP_BROWSER,
                        actionKey = AyanaAppIntegrationRegistry.ACTION_OPEN_URL,
                        payload = RESULT_TRANSFER_ACCEPTANCE_URL,
                        captureSpec =
                            AyanaVerifiedResultTransfer.CaptureSpec(
                                transferKey = RESULT_TRANSFER_ACCEPTANCE_KEY,
                                kind =
                                    AyanaVerifiedResultTransfer
                                        .CaptureKind
                                        .SCREEN_MARKER,
                                marker = RESULT_TRANSFER_ACCEPTANCE_MARKER
                            )
                    ),
                    TaskStep(
                        key = "youtube-transferred-result",
                        label = "YouTube search from verified transferred result",
                        appKey = AyanaAppIntegrationRegistry.APP_YOUTUBE,
                        actionKey = AyanaAppIntegrationRegistry.ACTION_SEARCH,
                        bindingSpec =
                            AyanaVerifiedResultTransfer.BindingSpec(
                                transferKey = RESULT_TRANSFER_ACCEPTANCE_KEY,
                                template =
                                    AyanaVerifiedResultTransfer.DEFAULT_PLACEHOLDER
                            )
                    ),
                    TaskStep(
                        key = "calendar-transferred-result-draft",
                        label = "Calendar draft from verified transferred result",
                        appKey = AyanaAppIntegrationRegistry.APP_CALENDAR,
                        actionKey =
                            AyanaAppIntegrationRegistry
                                .ACTION_CREATE_EVENT_DRAFT,
                        bindingSpec =
                            AyanaVerifiedResultTransfer.BindingSpec(
                                transferKey = RESULT_TRANSFER_ACCEPTANCE_KEY,
                                template =
                                    "AYANA R9.5 — " +
                                        AyanaVerifiedResultTransfer.DEFAULT_PLACEHOLDER +
                                        " — не сохранять"
                            )
                    )
                )
        )

    private fun parseVerifiedTransferUserPlan(
        clean: String
    ): TaskPlan? {
        val browserYoutube =
            Regex(
                """^(?:открой|перейди\s+на|зайди\s+на)\s+(?:сайт\s+)?(\S+)\s+(?:затем|потом|после этого)\s+(?:найди|поищи|поиск)\s+(?:в\s+)?(?:youtube|ютубе|ютьюбе|ютуб|ютьюб)\s+(?:название|заголовок)\s+(?:этой\s+)?страницы$""",
                RegexOption.IGNORE_CASE
            )
                .matchEntire(clean)

        if (browserYoutube != null) {
            val url =
                browserYoutube
                    .groupValues
                    .getOrNull(1)
                    ?.trim()
                    .orEmpty()

            if (url.isNotBlank()) {
                return pageTitleTransferPlan(
                    originalCommand = clean,
                    url = url,
                    consumer = "youtube"
                )
            }
        }

        val browserCalendar =
            Regex(
                """^(?:открой|перейди\s+на|зайди\s+на)\s+(?:сайт\s+)?(\S+)\s+(?:затем|потом|после этого)\s+(?:создай|добавь|подготовь)\s+(?:черновик\s+)?(?:событие|мероприятие)\s+(?:в\s+календар(?:е|ь)\s+)?(?:с\s+)?(?:названием|заголовком)\s+(?:этой\s+)?страницы$""",
                RegexOption.IGNORE_CASE
            )
                .matchEntire(clean)

        if (browserCalendar != null) {
            val url =
                browserCalendar
                    .groupValues
                    .getOrNull(1)
                    ?.trim()
                    .orEmpty()

            if (url.isNotBlank()) {
                return pageTitleTransferPlan(
                    originalCommand = clean,
                    url = url,
                    consumer = "calendar"
                )
            }
        }

        return null
    }

    private fun pageTitleTransferPlan(
        originalCommand: String,
        url: String,
        consumer: String
    ): TaskPlan {
        val captureKey = "page_title"

        val source =
            TaskStep(
                key = "browser-page-title-source",
                label = "Browser page title source",
                appKey = AyanaAppIntegrationRegistry.APP_BROWSER,
                actionKey = AyanaAppIntegrationRegistry.ACTION_OPEN_URL,
                payload = url,
                captureSpec =
                    AyanaVerifiedResultTransfer.CaptureSpec(
                        transferKey = captureKey,
                        kind =
                            AyanaVerifiedResultTransfer
                                .CaptureKind
                                .SCREEN_TITLE
                    )
            )

        val target =
            if (consumer == "calendar") {
                TaskStep(
                    key = "calendar-page-title-draft",
                    label = "Calendar draft from verified page title",
                    appKey = AyanaAppIntegrationRegistry.APP_CALENDAR,
                    actionKey =
                        AyanaAppIntegrationRegistry
                            .ACTION_CREATE_EVENT_DRAFT,
                    bindingSpec =
                        AyanaVerifiedResultTransfer.BindingSpec(
                            transferKey = captureKey,
                            template =
                                AyanaVerifiedResultTransfer.DEFAULT_PLACEHOLDER
                        )
                )
            } else {
                TaskStep(
                    key = "youtube-page-title-search",
                    label = "YouTube search from verified page title",
                    appKey = AyanaAppIntegrationRegistry.APP_YOUTUBE,
                    actionKey = AyanaAppIntegrationRegistry.ACTION_SEARCH,
                    bindingSpec =
                        AyanaVerifiedResultTransfer.BindingSpec(
                            transferKey = captureKey,
                            template =
                                AyanaVerifiedResultTransfer.DEFAULT_PLACEHOLDER
                        )
                )
            }

        return TaskPlan(
            key = "verified-page-title-transfer",
            originalCommand = originalCommand,
            source = "r9_5_verified_page_title_grammar",
            steps = listOf(source, target),
            acceptanceProbe = false
        )
    }

    private fun isResultTransferAcceptanceCommand(
        value: String
    ): Boolean {
        val normalized = normalize(value)

        return normalized in
            setOf(
                "проверь перенос результата между приложениями",
                "протестируй перенос результата между приложениями",
                "проверь передачу результата между приложениями",
                "протестируй передачу результата между приложениями",
                "проверь verified result transfer",
                "протестируй verified result transfer"
            )
    }

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
        const val VERSION = "1.1"
        const val MAX_STEPS = 5
        const val ACCEPTANCE_STEP_COUNT = 3
        const val RESULT_TRANSFER_ACCEPTANCE_STEP_COUNT = 3

        const val ACCEPTANCE_QUERY =
            "AYANA R9.4 multi-app orchestration probe"

        const val ACCEPTANCE_CALENDAR_TITLE =
            "AYANA R9.4 multi-app orchestration probe — не сохранять"

        const val RESULT_TRANSFER_ACCEPTANCE_URL =
            "https://example.com/"

        const val RESULT_TRANSFER_ACCEPTANCE_MARKER =
            "Example Domain"

        const val RESULT_TRANSFER_ACCEPTANCE_KEY =
            "browser_page_marker"
    }
}
