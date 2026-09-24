package kg.autonomous.agent

import org.json.JSONObject
import java.util.Locale

/**
 * AYANA R9.2 Autonomous Recovery Coordinator v1.0.
 *
 * Pure recovery policy placed between a failed/unverified tool result and the next
 * autonomous step. It never executes Android actions itself. The caller remains the
 * only executor and must persist the decision before continuing.
 *
 * Safety invariants:
 * - a verified success is never retried;
 * - an action that may already have been dispatched is never blindly repeated;
 * - unresolved side-effect truth always requires reconciliation/pause;
 * - automatic restart may retry only allow-listed read-only observations;
 * - one local read-only retry per tool signature is the default budget;
 * - confirmation/safety blocks are never bypassed by recovery;
 * - replan means "choose a different path", not "repeat the same action".
 */
class AyanaAutonomousRecoveryCoordinator {

    data class Observation(
        val toolName: String,
        val success: Boolean,
        val verified: Boolean,
        val terminalStatus: String = "",
        val status: String = "",
        val reason: String = "",
        val message: String = "",
        val actionDispatched: Boolean = false,
        val actionCommitted: Boolean = false,
        val reconciliationComplete: Boolean = false,
        val requiresConfirmation: Boolean = false,
        val safetyBlocked: Boolean = false,
        val replanRecommended: Boolean = false,
        val automaticRecovery: Boolean = false,
        val sameSignatureRecoveryAttempts: Int = 0,
        val graphRecoveryCount: Int = 0
    )

    enum class Strategy {
        NONE,
        RETRY_READ_ONLY,
        REPLAN_WITH_FRESH_OBSERVATION,
        WAIT_FOR_NETWORK,
        PAUSE_RECONCILIATION,
        PAUSE_AFTER_COMMIT,
        REQUIRE_CONFIRMATION,
        PAUSE_SAFETY,
        PAUSE_USER_RESUME,
        PAUSE_BUDGET_EXHAUSTED,
        PAUSE_UNRECOVERABLE
    }

    fun evaluate(
        observation: Observation
    ): JSONObject {
        val tool = observation.toolName.trim()
        val normalized =
            listOf(
                observation.terminalStatus,
                observation.status,
                observation.reason,
                observation.message
            )
                .joinToString(" ")
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')

        val readOnly = isReadOnlyTool(tool)
        val networkFailure = looksLikeNetworkFailure(normalized)
        val perceptionFailure = looksLikePerceptionFailure(normalized)
        val noDispatch = !observation.actionDispatched
        val retryBudgetAvailable =
            observation.sameSignatureRecoveryAttempts < MAX_LOCAL_READ_ONLY_RETRIES
        val graphBudgetAvailable =
            observation.graphRecoveryCount < MAX_GRAPH_RECOVERIES

        val strategy: Strategy
        val reason: String
        val delayMs: Long
        val freshObservation: Boolean
        val safeAutoResume: Boolean

        when {
            observation.success && observation.verified -> {
                strategy = Strategy.NONE
                reason = "verified_success"
                delayMs = 0L
                freshObservation = false
                safeAutoResume = false
            }

            observation.requiresConfirmation -> {
                strategy = Strategy.REQUIRE_CONFIRMATION
                reason = "explicit_confirmation_required"
                delayMs = 0L
                freshObservation = true
                safeAutoResume = false
            }

            observation.safetyBlocked -> {
                strategy = Strategy.PAUSE_SAFETY
                reason = "safety_policy_blocked"
                delayMs = 0L
                freshObservation = true
                safeAutoResume = false
            }

            observation.actionCommitted -> {
                strategy = Strategy.PAUSE_AFTER_COMMIT
                reason = "side_effect_committed_terminal_not_verified"
                delayMs = 0L
                freshObservation = true
                safeAutoResume = false
            }

            observation.actionDispatched && !observation.reconciliationComplete -> {
                strategy = Strategy.PAUSE_RECONCILIATION
                reason = "side_effect_dispatched_outcome_uncertain"
                delayMs = 0L
                freshObservation = true
                safeAutoResume = false
            }

            !graphBudgetAvailable -> {
                strategy = Strategy.PAUSE_BUDGET_EXHAUSTED
                reason = "graph_recovery_budget_exhausted"
                delayMs = 0L
                freshObservation = true
                safeAutoResume = false
            }

            networkFailure && readOnly && noDispatch && retryBudgetAvailable -> {
                strategy = Strategy.RETRY_READ_ONLY
                reason = "transient_network_read_only_retry"
                delayMs = NETWORK_RETRY_SETTLE_MS
                freshObservation = false
                safeAutoResume = true
            }

            readOnly && noDispatch && retryBudgetAvailable -> {
                strategy = Strategy.RETRY_READ_ONLY
                reason =
                    if (perceptionFailure) {
                        "transient_perception_read_only_retry"
                    } else {
                        "read_only_retry"
                    }
                delayMs = READ_ONLY_RETRY_SETTLE_MS
                freshObservation = perceptionFailure
                safeAutoResume = true
            }

            observation.automaticRecovery && !readOnly -> {
                strategy = Strategy.PAUSE_USER_RESUME
                reason = "automatic_recovery_active_step_requires_user_resume"
                delayMs = 0L
                freshObservation = true
                safeAutoResume = false
            }

            networkFailure && noDispatch -> {
                strategy = Strategy.WAIT_FOR_NETWORK
                reason = "network_unavailable_or_transport_failed"
                delayMs = 0L
                freshObservation = false
                safeAutoResume = readOnly
            }

            noDispatch &&
                !observation.automaticRecovery -> {
                strategy = Strategy.REPLAN_WITH_FRESH_OBSERVATION
                reason =
                    when {
                        observation.replanRecommended ->
                            "executor_replan_recommended"

                        perceptionFailure ->
                            "pre_dispatch_perception_failure"

                        else ->
                            "pre_dispatch_failure_alternative_path"
                    }
                delayMs = REPLAN_SETTLE_MS
                freshObservation = true
                safeAutoResume = false
            }

            else -> {
                strategy = Strategy.PAUSE_UNRECOVERABLE
                reason = "no_safe_recovery_path"
                delayMs = 0L
                freshObservation = true
                safeAutoResume = false
            }
        }

        return JSONObject()
            .put("version", VERSION)
            .put("strategy", strategy.name)
            .put("reason", reason)
            .put("tool_name", tool)
            .put("tool_read_only", readOnly)
            .put("network_failure", networkFailure)
            .put("perception_failure", perceptionFailure)
            .put("action_dispatched", observation.actionDispatched)
            .put("action_committed", observation.actionCommitted)
            .put("reconciliation_complete", observation.reconciliationComplete)
            .put("same_signature_recovery_attempts", observation.sameSignatureRecoveryAttempts)
            .put("graph_recovery_count", observation.graphRecoveryCount)
            .put("local_retry_allowed", strategy == Strategy.RETRY_READ_ONLY)
            .put("replan_allowed", strategy == Strategy.REPLAN_WITH_FRESH_OBSERVATION)
            .put(
                "pause_required",
                strategy in setOf(
                    Strategy.PAUSE_RECONCILIATION,
                    Strategy.PAUSE_AFTER_COMMIT,
                    Strategy.REQUIRE_CONFIRMATION,
                    Strategy.PAUSE_SAFETY,
                    Strategy.PAUSE_USER_RESUME,
                    Strategy.PAUSE_BUDGET_EXHAUSTED,
                    Strategy.PAUSE_UNRECOVERABLE,
                    Strategy.WAIT_FOR_NETWORK
                )
            )
            .put("fresh_observation_required", freshObservation)
            .put("safe_auto_resume", safeAutoResume)
            .put("delay_ms", delayMs)
            .put("blind_side_effect_retry_allowed", false)
    }

    fun isReadOnlyTool(
        toolName: String
    ): Boolean =
        toolName.trim() in READ_ONLY_TOOLS

    fun selfTest(): Boolean {
        val verified =
            evaluate(
                Observation(
                    toolName = "get_screen_state",
                    success = true,
                    verified = true
                )
            )

        val readRetry =
            evaluate(
                Observation(
                    toolName = "get_screen_state",
                    success = false,
                    verified = false,
                    status = "snapshot_unavailable",
                    sameSignatureRecoveryAttempts = 0
                )
            )

        val dispatchedUnknown =
            evaluate(
                Observation(
                    toolName = "click_screen_element",
                    success = false,
                    verified = false,
                    actionDispatched = true,
                    reconciliationComplete = false
                )
            )

        val preDispatchReplan =
            evaluate(
                Observation(
                    toolName = "click_screen_element",
                    success = false,
                    verified = false,
                    status = "target_not_found",
                    actionDispatched = false,
                    replanRecommended = true
                )
            )

        val automaticMutation =
            evaluate(
                Observation(
                    toolName = "change_volume",
                    success = false,
                    verified = false,
                    automaticRecovery = true,
                    actionDispatched = false
                )
            )

        val committedUnknown =
            evaluate(
                Observation(
                    toolName = "create_reminder",
                    success = false,
                    verified = false,
                    actionDispatched = true,
                    actionCommitted = true,
                    reconciliationComplete = false
                )
            )

        val budgetExhausted =
            evaluate(
                Observation(
                    toolName = "get_screen_state",
                    success = false,
                    verified = false,
                    graphRecoveryCount = MAX_GRAPH_RECOVERIES
                )
            )

        return verified.optString("strategy") == Strategy.NONE.name &&
            readRetry.optString("strategy") == Strategy.RETRY_READ_ONLY.name &&
            readRetry.optBoolean("local_retry_allowed") &&
            dispatchedUnknown.optString("strategy") == Strategy.PAUSE_RECONCILIATION.name &&
            !dispatchedUnknown.optBoolean("blind_side_effect_retry_allowed", true) &&
            preDispatchReplan.optString("strategy") == Strategy.REPLAN_WITH_FRESH_OBSERVATION.name &&
            automaticMutation.optString("strategy") == Strategy.PAUSE_USER_RESUME.name &&
            committedUnknown.optString("strategy") == Strategy.PAUSE_AFTER_COMMIT.name &&
            budgetExhausted.optString("strategy") == Strategy.PAUSE_BUDGET_EXHAUSTED.name
    }

    private fun looksLikeNetworkFailure(
        normalized: String
    ): Boolean =
        NETWORK_MARKERS.any(normalized::contains)

    private fun looksLikePerceptionFailure(
        normalized: String
    ): Boolean =
        PERCEPTION_MARKERS.any(normalized::contains)

    companion object {
        const val VERSION = "1.0"

        const val MAX_LOCAL_READ_ONLY_RETRIES = 1
        const val MAX_GRAPH_RECOVERIES = 6

        private const val READ_ONLY_RETRY_SETTLE_MS = 320L
        private const val NETWORK_RETRY_SETTLE_MS = 650L
        private const val REPLAN_SETTLE_MS = 180L

        private val READ_ONLY_TOOLS =
            setOf(
                "get_device_state",
                "get_device_capabilities",
                "run_self_diagnostics",
                "list_installed_apps",
                "resolve_app",
                "list_goals",
                "recall_memory",
                "list_memory",
                "list_reminders",
                "get_screen_state"
            )

        private val NETWORK_MARKERS =
            listOf(
                "network",
                "internet",
                "timeout",
                "timed out",
                "socket",
                "connection",
                "connection failed",
                "http 5",
                "сеть недоступ",
                "интернет недоступ",
                "нет подключения",
                "не подключен"
            )

        private val PERCEPTION_MARKERS =
            listOf(
                "snapshot_unavailable",
                "content_unavailable",
                "target_not_found",
                "not_verified",
                "screen unavailable",
                "screen_not_readable",
                "экран недоступ",
                "не удалось подтвердить",
                "не найден"
            )
    }
}
