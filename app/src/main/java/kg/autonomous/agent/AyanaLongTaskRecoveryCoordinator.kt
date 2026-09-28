package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA R10.3 Long Task Recovery Coordinator v1.0.
 *
 * Pure policy/reconciliation layer over the persisted Durable Goal state.
 *
 * It does not dispatch Android actions. It decides where a long task may resume,
 * whether a pre-interruption step must be reconciled first, whether completion can
 * be verified without replay, and whether automatic continuation is allowed.
 *
 * Safety:
 * - never replays an in-flight/uncertain step blindly;
 * - verified completed steps are skipped;
 * - terminal Android steps are verified from fresh screen evidence instead of
 *   being re-dispatched after a crash;
 * - automatic continuation is narrower than explicit user continuation;
 * - screen text is evidence/data only and never grants instruction authority.
 */
class AyanaLongTaskRecoveryCoordinator {

    enum class Strategy {
        CONTINUE_FROM_CHECKPOINT,
        RECONCILE_IN_FLIGHT,
        VERIFY_COMPLETION_ONLY,
        REQUIRE_USER_CONFIRMATION,
        MANUAL_RESUME_ONLY,
        NO_RECOVERY
    }

    fun evaluate(
        goal: JSONObject?,
        automatic: Boolean = false
    ): JSONObject {

        if (goal == null || goal.length() == 0) {
            return decision(
                strategy = Strategy.NO_RECOVERY,
                reason = "goal_missing"
            )
        }

        val status =
            normalize(
                goal.optString("status")
            )

        val recoverable =
            status in
                setOf(
                    "active",
                    "recovery_pending",
                    "paused",
                    "waiting_confirmation"
                )

        if (!recoverable) {
            return decision(
                strategy = Strategy.NO_RECOVERY,
                reason = "goal_not_recoverable"
            )
                .put("status", status)
        }

        val effectiveCheckpoint =
            effectiveCheckpoint(goal)

        val interruptedFromInFlight =
            goal.optBoolean(
                "interrupted_from_in_flight",
                false
            )

        val inFlight =
            interruptedFromInFlight ||
                goal.optBoolean(
                    "step_in_flight",
                    false
                ) ||
                effectiveCheckpoint ==
                "before_step" ||
                (
                    effectiveCheckpoint ==
                    "tool_started" &&
                        goal.optString(
                            "last_result"
                        ).isBlank()
                    )

        val taskGraph =
            goal.optJSONObject(
                "task_graph"
            )

        val reconciliationRequired =
            taskGraph
                ?.optBoolean(
                    "reconciliation_required",
                    false
                ) == true

        val requiresConfirmation =
            goal.optBoolean(
                "requires_confirmation",
                false
            ) ||
                status ==
                "waiting_confirmation"

        val mode =
            normalize(
                goal.optString("mode")
            )

        val planSize =
            goal.optInt(
                "plan_size",
                goal.optJSONObject("compiled_plan")
                    ?.optJSONArray("steps")
                    ?.length()
                    ?: 0
            )
                .coerceAtLeast(0)

        val nextPlanStep =
            goal.optInt(
                "next_plan_step",
                0
            )
                .coerceAtLeast(0)

        val strategy =
            when {
                requiresConfirmation ->
                    Strategy.REQUIRE_USER_CONFIRMATION

                reconciliationRequired ||
                    inFlight ->
                    Strategy.RECONCILE_IN_FLIGHT

                mode ==
                    "android_goal" &&
                    planSize >
                    0 &&
                    nextPlanStep >=
                    planSize ->
                    Strategy.VERIFY_COMPLETION_ONLY

                status ==
                    "paused" ->
                    Strategy.MANUAL_RESUME_ONLY

                else ->
                    Strategy.CONTINUE_FROM_CHECKPOINT
            }

        val autoSafeStep =
            when {
                strategy ==
                    Strategy.VERIFY_COMPLETION_ONLY ->
                    true

                strategy ==
                    Strategy.RECONCILE_IN_FLIGHT ->
                    true

                mode !=
                    "android_goal" ->
                    true

                else ->
                    isAutomaticAndroidStepSafe(
                        goal = goal,
                        stepIndex = nextPlanStep
                    )
            }

        val recoveryReason =
            normalize(
                goal.optString(
                    "recovery_reason"
                )
            )

        val autoAllowed =
            goal.optBoolean(
                "safe_auto_resume",
                false
            ) &&
                !requiresConfirmation &&
                strategy !in
                setOf(
                    Strategy.MANUAL_RESUME_ONLY,
                    Strategy.NO_RECOVERY
                ) &&
                autoSafeStep &&
                recoveryReason !in
                setOf(
                    "device_boot",
                    "package_replaced"
                )

        return decision(
            strategy = strategy,
            reason =
                when (strategy) {
                    Strategy.CONTINUE_FROM_CHECKPOINT ->
                        "verified_checkpoint_available"
                    Strategy.RECONCILE_IN_FLIGHT ->
                        "uncertain_or_in_flight_step_requires_reconciliation"
                    Strategy.VERIFY_COMPLETION_ONLY ->
                        "all_plan_steps_checkpointed_verify_terminal_state_without_replay"
                    Strategy.REQUIRE_USER_CONFIRMATION ->
                        "fresh_user_confirmation_required"
                    Strategy.MANUAL_RESUME_ONLY ->
                        "goal_is_paused"
                    Strategy.NO_RECOVERY ->
                        "goal_not_recoverable"
                }
        )
            .put(
                "status",
                status
            )
            .put(
                "mode",
                mode
            )
            .put(
                "effective_checkpoint",
                effectiveCheckpoint
            )
            .put(
                "interrupted_from_checkpoint",
                goal.optString(
                    "interrupted_from_checkpoint"
                )
            )
            .put(
                "interrupted_from_in_flight",
                interruptedFromInFlight
            )
            .put(
                "reconciliation_required",
                reconciliationRequired
            )
            .put(
                "plan_size",
                planSize
            )
            .put(
                "resume_index",
                nextPlanStep
            )
            .put(
                "automatic_android_step_safe",
                autoSafeStep
            )
            .put(
                "automatic_resume_allowed",
                autoAllowed
            )
            .put(
                "explicit_resume_allowed",
                strategy !in
                    setOf(
                        Strategy.NO_RECOVERY,
                        Strategy.REQUIRE_USER_CONFIRMATION
                    )
            )
            .put(
                "blind_replay_allowed",
                false
            )
            .put(
                "completed_steps_preserved",
                nextPlanStep.coerceAtMost(
                    planSize
                )
            )
            .put(
                "automatic_requested",
                automatic
            )
    }

    fun reconcileAndroidInFlight(
        goal: JSONObject?,
        freshScreen: JSONObject?
    ): JSONObject {

        if (
            goal == null ||
            freshScreen == null
        ) {
            return reconciliationResult(
                verified = false,
                reason = "missing_goal_or_screen"
            )
        }

        val plan =
            goal.optJSONObject(
                "compiled_plan"
            )

        val steps =
            plan
                ?.optJSONArray(
                    "steps"
                )

        if (
            steps == null ||
            steps.length() ==
            0
        ) {
            return reconciliationResult(
                verified = false,
                reason = "compiled_plan_missing"
            )
        }

        val index =
            goal.optInt(
                "next_plan_step",
                0
            )
                .coerceIn(
                    0,
                    steps.length() - 1
                )

        val step =
            steps.optJSONObject(
                index
            )
                ?: return reconciliationResult(
                    verified = false,
                    reason = "step_missing"
                )

        val expectation =
            verifyExpectedScreen(
                step = step,
                screen = freshScreen
            )

        if (expectation == null) {
            return reconciliationResult(
                verified = false,
                reason = "no_explicit_step_expectation"
            )
                .put(
                    "step_index",
                    index
                )
                .put(
                    "step_action",
                    step.optString(
                        "action"
                    )
                )
        }

        if (!expectation) {
            return reconciliationResult(
                verified = false,
                reason = "fresh_screen_does_not_prove_in_flight_step"
            )
                .put(
                    "step_index",
                    index
                )
                .put(
                    "step_action",
                    step.optString(
                        "action"
                    )
                )
        }

        return reconciliationResult(
            verified = true,
            reason = "fresh_screen_proves_in_flight_step_completed"
        )
            .put(
                "step_index",
                index
            )
            .put(
                "next_plan_step",
                index + 1
            )
            .put(
                "step_action",
                step.optString(
                    "action"
                )
            )
            .put(
                "fresh_screen_fingerprint",
                screenFingerprint(
                    freshScreen
                )
            )
            .put(
                "replay_performed",
                false
            )
    }

    fun verifyAndroidCompletion(
        goal: JSONObject?,
        freshScreen: JSONObject?
    ): JSONObject {

        if (
            goal == null ||
            freshScreen == null
        ) {
            return completionResult(
                verified = false,
                reason = "missing_goal_or_screen"
            )
        }

        val steps =
            goal.optJSONObject(
                "compiled_plan"
            )
                ?.optJSONArray(
                    "steps"
                )

        if (
            steps == null ||
            steps.length() ==
            0
        ) {
            return completionResult(
                verified = false,
                reason = "compiled_plan_missing"
            )
        }

        val terminalIndex =
            steps.length() - 1

        val terminalStep =
            steps.optJSONObject(
                terminalIndex
            )
                ?: return completionResult(
                    verified = false,
                    reason = "terminal_step_missing"
                )

        val explicitlyTerminal =
            terminalStep.optBoolean(
                "terminal",
                false
            )

        if (!explicitlyTerminal) {
            return completionResult(
                verified = false,
                reason = "last_step_not_terminal"
            )
        }

        val expectation =
            verifyExpectedScreen(
                step = terminalStep,
                screen = freshScreen
            )

        val freshFingerprint =
            screenFingerprint(
                freshScreen
            )

        val storedFingerprint =
            goal.optString(
                "screen_fingerprint"
            )
                .trim()

        val storedStepSuccess =
            goal.optBoolean(
                "last_step_success",
                false
            )

        val fingerprintProof =
            storedStepSuccess &&
                storedFingerprint.isNotBlank() &&
                freshFingerprint.isNotBlank() &&
                storedFingerprint ==
                freshFingerprint

        val verified =
            expectation == true ||
                fingerprintProof

        return completionResult(
            verified = verified,
            reason =
                when {
                    expectation == true ->
                        "terminal_expectation_verified_on_fresh_screen"
                    fingerprintProof ->
                        "fresh_screen_matches_verified_terminal_checkpoint"
                    expectation == false ->
                        "terminal_expectation_not_met"
                    else ->
                        "no_terminal_proof_after_restart"
                }
        )
            .put(
                "terminal_step_index",
                terminalIndex
            )
            .put(
                "terminal_step_action",
                terminalStep.optString(
                    "action"
                )
            )
            .put(
                "explicit_expectation_verified",
                expectation == true
            )
            .put(
                "stored_fingerprint_match",
                fingerprintProof
            )
            .put(
                "fresh_screen_fingerprint",
                freshFingerprint
            )
            .put(
                "stored_screen_fingerprint",
                storedFingerprint
            )
            .put(
                "replay_performed",
                false
            )
    }

    fun isAutomaticAndroidStepSafe(
        goal: JSONObject,
        stepIndex: Int
    ): Boolean {

        val steps =
            goal.optJSONObject(
                "compiled_plan"
            )
                ?.optJSONArray(
                    "steps"
                )
                ?: return false

        if (
            stepIndex <
            0 ||
            stepIndex >=
            steps.length()
        ) {
            return true
        }

        val action =
            normalize(
                steps.optJSONObject(
                    stepIndex
                )
                    ?.optString(
                        "action"
                    )
                    .orEmpty()
            )

        return action in
            SAFE_AUTOMATIC_ANDROID_ACTIONS
    }

    fun selfTest(): Boolean {
        val plan =
            JSONObject()
                .put(
                    "steps",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put(
                                    "id",
                                    "s1"
                                )
                                .put(
                                    "action",
                                    "open_app"
                                )
                                .put(
                                    "expect_package",
                                    "pkg.one"
                                )
                        )
                        .put(
                            JSONObject()
                                .put(
                                    "id",
                                    "s2"
                                )
                                .put(
                                    "action",
                                    "open_settings"
                                )
                                .put(
                                    "expect_package",
                                    "com.android.settings"
                                )
                        )
                        .put(
                            JSONObject()
                                .put(
                                    "id",
                                    "s3"
                                )
                                .put(
                                    "action",
                                    "open_app"
                                )
                                .put(
                                    "expect_package",
                                    "kg.autonomous.agent"
                                )
                                .put(
                                    "terminal",
                                    true
                                )
                        )
                )

        val interruptedAfterVerifiedStep =
            JSONObject()
                .put(
                    "status",
                    "recovery_pending"
                )
                .put(
                    "mode",
                    "android_goal"
                )
                .put(
                    "safe_auto_resume",
                    true
                )
                .put(
                    "compiled_plan",
                    plan
                )
                .put(
                    "plan_size",
                    3
                )
                .put(
                    "next_plan_step",
                    1
                )
                .put(
                    "last_checkpoint",
                    "interrupted"
                )
                .put(
                    "interrupted_from_checkpoint",
                    "step_completed"
                )
                .put(
                    "interrupted_from_in_flight",
                    false
                )

        val first =
            evaluate(
                interruptedAfterVerifiedStep,
                automatic = true
            )

        if (
            first.optString(
                "strategy"
            ) !=
            Strategy.CONTINUE_FROM_CHECKPOINT.name ||
            first.optInt(
                "resume_index",
                -1
            ) !=
            1 ||
            first.optBoolean(
                "blind_replay_allowed",
                true
            )
        ) {
            return false
        }

        val inFlight =
            JSONObject(
                interruptedAfterVerifiedStep.toString()
            )
                .put(
                    "next_plan_step",
                    1
                )
                .put(
                    "interrupted_from_checkpoint",
                    "before_step"
                )
                .put(
                    "interrupted_from_in_flight",
                    true
                )

        val second =
            evaluate(
                inFlight,
                automatic = true
            )

        if (
            second.optString(
                "strategy"
            ) !=
            Strategy.RECONCILE_IN_FLIGHT.name
        ) {
            return false
        }

        val reconciled =
            reconcileAndroidInFlight(
                inFlight,
                JSONObject()
                    .put(
                        "success",
                        true
                    )
                    .put(
                        "snapshot_success",
                        true
                    )
                    .put(
                        "effective_foreground_package",
                        "com.android.settings"
                    )
                    .put(
                        "package",
                        "com.android.settings"
                    )
            )

        if (
            !reconciled.optBoolean(
                "verified",
                false
            ) ||
            reconciled.optInt(
                "next_plan_step",
                -1
            ) !=
            2 ||
            reconciled.optBoolean(
                "replay_performed",
                true
            )
        ) {
            return false
        }

        val completed =
            JSONObject(
                interruptedAfterVerifiedStep.toString()
            )
                .put(
                    "next_plan_step",
                    3
                )
                .put(
                    "interrupted_from_checkpoint",
                    "step_completed"
                )
                .put(
                    "last_step_success",
                    true
                )

        val completionDecision =
            evaluate(
                completed,
                automatic = true
            )

        if (
            completionDecision.optString(
                "strategy"
            ) !=
            Strategy.VERIFY_COMPLETION_ONLY.name
        ) {
            return false
        }

        val completion =
            verifyAndroidCompletion(
                completed,
                JSONObject()
                    .put(
                        "success",
                        true
                    )
                    .put(
                        "snapshot_success",
                        true
                    )
                    .put(
                        "effective_foreground_package",
                        "kg.autonomous.agent"
                    )
                    .put(
                        "package",
                        "kg.autonomous.agent"
                    )
            )

        if (
            !completion.optBoolean(
                "verified",
                false
            ) ||
            completion.optBoolean(
                "replay_performed",
                true
            )
        ) {
            return false
        }

        val unsafeAuto =
            JSONObject(
                interruptedAfterVerifiedStep.toString()
            )
                .put(
                    "next_plan_step",
                    1
                )
                .put(
                    "compiled_plan",
                    JSONObject()
                        .put(
                            "steps",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put(
                                            "action",
                                            "open_app"
                                        )
                                )
                                .put(
                                    JSONObject()
                                        .put(
                                            "action",
                                            "input_screen_text"
                                        )
                                )
                        )
                )
                .put(
                    "plan_size",
                    2
                )

        if (
            evaluate(
                unsafeAuto,
                automatic = true
            ).optBoolean(
                "automatic_resume_allowed",
                true
            )
        ) {
            return false
        }

        return true
    }

    private fun decision(
        strategy: Strategy,
        reason: String
    ): JSONObject =
        JSONObject()
            .put(
                "coordinator_version",
                VERSION
            )
            .put(
                "strategy",
                strategy.name
            )
            .put(
                "reason",
                reason
            )
            .put(
                "automatic_resume_allowed",
                false
            )
            .put(
                "explicit_resume_allowed",
                false
            )
            .put(
                "blind_replay_allowed",
                false
            )

    private fun reconciliationResult(
        verified: Boolean,
        reason: String
    ): JSONObject =
        JSONObject()
            .put(
                "coordinator_version",
                VERSION
            )
            .put(
                "verified",
                verified
            )
            .put(
                "reason",
                reason
            )
            .put(
                "replay_performed",
                false
            )

    private fun completionResult(
        verified: Boolean,
        reason: String
    ): JSONObject =
        JSONObject()
            .put(
                "coordinator_version",
                VERSION
            )
            .put(
                "verified",
                verified
            )
            .put(
                "reason",
                reason
            )
            .put(
                "replay_performed",
                false
            )

    private fun effectiveCheckpoint(
        goal: JSONObject
    ): String {
        val current =
            normalize(
                goal.optString(
                    "last_checkpoint"
                )
            )

        if (
            current ==
            "interrupted"
        ) {
            return normalize(
                goal.optString(
                    "interrupted_from_checkpoint"
                )
            )
                .ifBlank {
                    current
                }
        }

        return current
    }

    private fun verifyExpectedScreen(
        step: JSONObject,
        screen: JSONObject
    ): Boolean? {

        val expectAny =
            stringList(
                step.optJSONArray(
                    "expect_any"
                )
            )

        val expectAll =
            stringList(
                step.optJSONArray(
                    "expect_all"
                )
            )

        val expectNone =
            stringList(
                step.optJSONArray(
                    "expect_none"
                )
            )

        val expectPackage =
            normalize(
                step.optString(
                    "expect_package"
                )
            )

        if (
            expectAny.isEmpty() &&
            expectAll.isEmpty() &&
            expectNone.isEmpty() &&
            expectPackage.isBlank()
        ) {
            return null
        }

        if (
            !screen.optBoolean(
                "snapshot_success",
                screen.optBoolean(
                    "success",
                    false
                )
            )
        ) {
            return false
        }

        val corpus =
            verificationTexts(
                screen
            )
                .joinToString(
                    " | "
                )
                .let(
                    ::normalize
                )

        val packages =
            linkedSetOf<String>()

        listOf(
            screen.optString(
                "effective_foreground_package"
            ),
            screen.optString(
                "interaction_package"
            ),
            screen.optString(
                "package"
            )
        )
            .map(
                ::normalize
            )
            .filter {
                it.isNotBlank()
            }
            .forEach(
                packages::add
            )

        val packageArray =
            screen.optJSONArray(
                "packages"
            )

        if (packageArray != null) {
            for (
                index in
                0 until packageArray.length()
            ) {
                normalize(
                    packageArray.optString(
                        index
                    )
                )
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?.let(
                        packages::add
                    )
            }
        }

        val anyOk =
            expectAny.isEmpty() ||
                expectAny.any {
                    corpus.contains(
                        normalize(it)
                    )
                }

        val allOk =
            expectAll.all {
                corpus.contains(
                    normalize(it)
                )
            }

        val noneOk =
            expectNone.none {
                corpus.contains(
                    normalize(it)
                )
            }

        val packageOk =
            expectPackage.isBlank() ||
                expectPackage in
                packages

        return anyOk &&
            allOk &&
            noneOk &&
            packageOk
    }

    private fun verificationTexts(
        screen: JSONObject
    ): List<String> {
        val result =
            mutableListOf<String>()

        val windows =
            screen.optJSONArray(
                "windows"
            )

        if (windows != null) {
            for (
                index in
                0 until windows.length()
            ) {
                val window =
                    windows.optJSONObject(
                        index
                    )
                        ?: continue

                if (
                    !window.optBoolean(
                        "interaction_context",
                        false
                    )
                ) {
                    continue
                }

                window.optString(
                    "verification_text"
                )
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?.let(
                        result::add
                    )

                appendStrings(
                    window.optJSONArray(
                        "visible_text"
                    ),
                    result
                )
            }
        }

        if (result.isNotEmpty()) {
            return result.distinct()
        }

        screen.optString(
            "verification_text"
        )
            .trim()
            .takeIf {
                it.isNotBlank()
            }
            ?.let(
                result::add
            )

        appendStrings(
            screen.optJSONArray(
                "visible_text"
            ),
            result
        )

        return result.distinct()
    }

    private fun screenFingerprint(
        screen: JSONObject
    ): String {

        // Keep this fingerprint compatible with AyanaAndroidTaskEngine's
        // persisted screen_fingerprint. Unified/effective package truth is used
        // separately by verifyExpectedScreen().
        val packageName =
            normalize(
                screen.optString(
                    "package"
                )
                    .ifBlank {
                        screen.optString(
                            "effective_foreground_package"
                        )
                    }
            )

        val rootClass =
            normalize(
                screen.optString(
                    "root_class"
                )
            )

        val contextId =
            normalize(
                screen.optString(
                    "primary_context_id"
                )
            )

        val visible =
            verificationTexts(
                screen
            )
                .joinToString(
                    "|"
                ) {
                    normalize(it)
                }

        return "$packageName::$rootClass::$contextId::$visible"
    }

    private fun stringList(
        array: JSONArray?
    ): List<String> {
        if (array == null) {
            return emptyList()
        }

        val result =
            ArrayList<String>(
                array.length()
            )

        for (
            index in
            0 until array.length()
        ) {
            array.optString(
                index
            )
                .trim()
                .takeIf {
                    it.isNotBlank()
                }
                ?.let(
                    result::add
                )
        }

        return result
    }

    private fun appendStrings(
        array: JSONArray?,
        target: MutableList<String>
    ) {
        if (array == null) {
            return
        }

        for (
            index in
            0 until array.length()
        ) {
            array.optString(
                index
            )
                .trim()
                .takeIf {
                    it.isNotBlank()
                }
                ?.let(
                    target::add
                )
        }
    }

    private fun normalize(
        value: String
    ): String =
        value
            .trim()
            .lowercase(
                Locale.ROOT
            )
            .replace(
                'ё',
                'е'
            )
            .replace(
                Regex("\\s+"),
                " "
            )

    companion object {
        const val VERSION =
            "1.0"

        private val SAFE_AUTOMATIC_ANDROID_ACTIONS =
            setOf(
                "open_app",
                "open_settings",
                "open_app_info",
                "open_app_settings",
                "press_home",
                "get_screen_state"
            )
    }
}
