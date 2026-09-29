package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * AYANA R10.5 Adaptive Execution Loop v1.1.
 *
 * Purpose:
 * - give the existing stepwise Agent Core / local Android execution loop one explicit,
 *   persisted plan-revision ledger;
 * - preserve verified work across replans;
 * - reject replay of the same failed transition from the same verified state;
 * - block new mutating work while an earlier side effect is unresolved;
 * - require terminal verification before the loop may be considered successful.
 *
 * This class is intentionally pure. It does not call Android APIs, Agent Core, Worker,
 * Accessibility, App Integration or any executor. VoiceService remains the executor and
 * verifier. This class only records/validates execution state and replan invariants.
 */
class AyanaAdaptiveExecutionLoop private constructor(
    private val objective: String,
    private var revision: Int,
    private var status: String,
    private var currentStateFingerprint: String,
    private var currentProposal: JSONObject?,
    private val verifiedSteps: JSONArray,
    private val failedTransitions: JSONArray,
    private val replans: JSONArray,
    private val reconciliations: JSONArray,
    private var unresolvedSideEffect: Boolean,
    private var terminalVerified: Boolean,
    private var terminalEvidence: String,
    private var proposalSequence: Int,
    private var executionLane: String,
    private var authorityContext: String
) {

    fun bindExecutionContext(
        lane: String,
        authority: String
    ): JSONObject {
        executionLane =
            lane
                .trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9_\\-]+"), "_")
                .trim('_')
                .take(MAX_SHORT_CHARS)
                .ifBlank { "unknown" }

        authorityContext =
            authority
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(MAX_EVIDENCE_CHARS)

        return JSONObject()
            .put("execution_lane", executionLane)
            .put("authority_context", authorityContext)
            .put("revision", revision)
            .put("verified_prefix_count", verifiedSteps.length())
    }

    fun executionLane(): String = executionLane

    fun authorityContext(): String = authorityContext

    fun propose(
        toolName: String,
        arguments: JSONObject,
        stateFingerprint: String,
        mayMutate: Boolean,
        authoritySource: String = "existing_executor_route"
    ): JSONObject {
        val cleanTool = toolName.trim()
        val cleanState = stateFingerprint.trim()
        val signature = signature(cleanTool, arguments)

        if (cleanTool.isBlank()) {
            return decision(
                allowed = false,
                reason = "blank_tool",
                signature = signature
            )
        }

        if (status in TERMINAL_STATUSES) {
            return decision(
                allowed = false,
                reason = "loop_terminal",
                signature = signature
            )
        }

        if (unresolvedSideEffect && mayMutate) {
            return decision(
                allowed = false,
                reason = "unresolved_side_effect_blocks_mutation",
                signature = signature
            )
        }

        if (
            hasFailedTransition(
                signature = signature,
                stateFingerprint = cleanState,
                revisionAtLeast = 0
            )
        ) {
            return decision(
                allowed = false,
                reason = "failed_transition_replay_blocked",
                signature = signature
            )
        }

        if (
            hasVerifiedTransition(
                signature = signature,
                beforeStateFingerprint = cleanState
            )
        ) {
            return decision(
                allowed = false,
                reason = "verified_transition_replay_blocked",
                signature = signature
            )
        }

        proposalSequence++
        currentStateFingerprint = cleanState.ifBlank { currentStateFingerprint }

        currentProposal =
            JSONObject()
                .put("proposal_id", proposalSequence)
                .put("revision", revision)
                .put("tool", cleanTool.take(MAX_TOOL_CHARS))
                .put("signature", signature)
                .put("arguments", boundedJson(arguments, MAX_ARGUMENTS_JSON_CHARS))
                .put("before_state", cleanState.take(MAX_FINGERPRINT_CHARS))
                .put("may_mutate", mayMutate)
                .put("authority_source", authoritySource.take(MAX_SHORT_CHARS))
                .put("execution_lane", executionLane)
                .put("authority_context", authorityContext.take(MAX_EVIDENCE_CHARS))
                .put("proposed_at_ms", System.currentTimeMillis())

        status = STATUS_ACTIVE

        return decision(
            allowed = true,
            reason = "proposal_accepted",
            signature = signature
        )
            .put("revision", revision)
            .put("proposal_id", proposalSequence)
    }

    fun recordResult(
        toolName: String,
        arguments: JSONObject,
        beforeStateFingerprint: String,
        afterStateFingerprint: String,
        success: Boolean,
        verified: Boolean,
        actionDispatched: Boolean,
        actionCommitted: Boolean,
        reconciliationComplete: Boolean,
        evidence: String,
        failureLayer: String = "",
        replanRecommended: Boolean = false
    ): JSONObject {
        val cleanTool = toolName.trim()
        val signature = signature(cleanTool, arguments)
        val proposal = currentProposal
        val proposalMatches =
            proposal != null &&
                proposal.optString("signature") == signature

        val mayMutate =
            if (proposalMatches) {
                proposal?.optBoolean("may_mutate", true) ?: true
            } else {
                true
            }

        val beforeState =
            beforeStateFingerprint
                .trim()
                .ifBlank {
                    proposal?.optString("before_state").orEmpty()
                }
                .take(MAX_FINGERPRINT_CHARS)

        val afterState =
            afterStateFingerprint
                .trim()
                .ifBlank { beforeState }
                .take(MAX_FINGERPRINT_CHARS)

        val now = System.currentTimeMillis()

        if (success && verified) {
            val row =
                JSONObject()
                    .put("seq", verifiedSteps.length() + 1)
                    .put("revision", revision)
                    .put("tool", cleanTool.take(MAX_TOOL_CHARS))
                    .put("signature", signature)
                    .put("before_state", beforeState)
                    .put("after_state", afterState)
                    .put("may_mutate", mayMutate)
                    .put("execution_lane", executionLane)
                    .put(
                        "authority_source",
                        proposal?.optString("authority_source")
                            ?.take(MAX_SHORT_CHARS)
                            .orEmpty()
                    )
                    .put("authority_context", authorityContext.take(MAX_EVIDENCE_CHARS))
                    .put("action_dispatched", actionDispatched)
                    .put("action_committed", actionCommitted)
                    .put("evidence", evidence.take(MAX_EVIDENCE_CHARS))
                    .put("verified_at_ms", now)

            verifiedSteps.put(row)
            currentStateFingerprint = afterState
            currentProposal = null

            if (reconciliationComplete) {
                unresolvedSideEffect = false
            }

            status = STATUS_ACTIVE

            return JSONObject()
                .put("recorded", true)
                .put("verified", true)
                .put("revision", revision)
                .put("verified_step_count", verifiedSteps.length())
                .put("unresolved_side_effect", unresolvedSideEffect)
                .put("signature", signature)
        }

        val sideEffectUncertain =
            mayMutate &&
                actionDispatched &&
                !reconciliationComplete

        if (sideEffectUncertain) {
            unresolvedSideEffect = true
        }

        failedTransitions.put(
            JSONObject()
                .put("seq", failedTransitions.length() + 1)
                .put("revision", revision)
                .put("tool", cleanTool.take(MAX_TOOL_CHARS))
                .put("signature", signature)
                .put("before_state", beforeState)
                .put("after_state", afterState)
                .put("may_mutate", mayMutate)
                .put("execution_lane", executionLane)
                .put(
                    "authority_source",
                    proposal?.optString("authority_source")
                        ?.take(MAX_SHORT_CHARS)
                        .orEmpty()
                )
                .put("authority_context", authorityContext.take(MAX_EVIDENCE_CHARS))
                .put("action_dispatched", actionDispatched)
                .put("action_committed", actionCommitted)
                .put("reconciliation_complete", reconciliationComplete)
                .put("side_effect_uncertain", sideEffectUncertain)
                .put("failure_layer", failureLayer.take(MAX_SHORT_CHARS))
                .put("replan_recommended", replanRecommended)
                .put("evidence", evidence.take(MAX_EVIDENCE_CHARS))
                .put("failed_at_ms", now)
        )

        currentStateFingerprint = afterState
        currentProposal = null
        status = if (sideEffectUncertain) STATUS_PAUSED else STATUS_ACTIVE

        trimArray(failedTransitions, MAX_FAILED_TRANSITIONS)

        return JSONObject()
            .put("recorded", true)
            .put("verified", false)
            .put("revision", revision)
            .put("side_effect_uncertain", sideEffectUncertain)
            .put("unresolved_side_effect", unresolvedSideEffect)
            .put("replan_recommended", replanRecommended)
            .put("signature", signature)
    }

    fun beginReplan(
        reason: String,
        stateFingerprint: String
    ): JSONObject {
        if (status in TERMINAL_STATUSES) {
            return JSONObject()
                .put("allowed", false)
                .put("reason", "loop_terminal")
                .put("revision", revision)
        }

        if (unresolvedSideEffect) {
            status = STATUS_PAUSED
            return JSONObject()
                .put("allowed", false)
                .put("reason", "unresolved_side_effect_requires_reconciliation")
                .put("revision", revision)
                .put("verified_prefix_count", verifiedSteps.length())
        }

        if (revision >= MAX_REVISIONS) {
            status = STATUS_PAUSED
            return JSONObject()
                .put("allowed", false)
                .put("reason", "replan_budget_exhausted")
                .put("revision", revision)
                .put("verified_prefix_count", verifiedSteps.length())
        }

        val fromRevision = revision
        revision++
        currentProposal = null
        currentStateFingerprint =
            stateFingerprint
                .trim()
                .ifBlank { currentStateFingerprint }
                .take(MAX_FINGERPRINT_CHARS)
        status = STATUS_ACTIVE

        replans.put(
            JSONObject()
                .put("from_revision", fromRevision)
                .put("to_revision", revision)
                .put("reason", reason.take(MAX_EVIDENCE_CHARS))
                .put("state_fingerprint", currentStateFingerprint)
                .put("verified_prefix_count", verifiedSteps.length())
                .put("failed_transition_count", failedTransitions.length())
                .put("execution_lane", executionLane)
                .put("authority_context", authorityContext.take(MAX_EVIDENCE_CHARS))
                .put("at_ms", System.currentTimeMillis())
        )

        trimArray(replans, MAX_REPLANS_HISTORY)

        return JSONObject()
            .put("allowed", true)
            .put("reason", "replan_started")
            .put("from_revision", fromRevision)
            .put("revision", revision)
            .put("verified_prefix_count", verifiedSteps.length())
            .put("state_fingerprint", currentStateFingerprint)
    }

    fun recordReconciliation(
        verified: Boolean,
        stateFingerprint: String,
        evidence: String
    ): JSONObject {
        val cleanState = stateFingerprint.trim().take(MAX_FINGERPRINT_CHARS)

        reconciliations.put(
            JSONObject()
                .put("verified", verified)
                .put("state_fingerprint", cleanState)
                .put("evidence", evidence.take(MAX_EVIDENCE_CHARS))
                .put("at_ms", System.currentTimeMillis())
        )

        trimArray(reconciliations, MAX_RECONCILIATIONS)

        if (verified) {
            unresolvedSideEffect = false
            currentStateFingerprint = cleanState.ifBlank { currentStateFingerprint }
            if (status == STATUS_PAUSED) {
                status = STATUS_ACTIVE
            }
        }

        return JSONObject()
            .put("verified", verified)
            .put("unresolved_side_effect", unresolvedSideEffect)
            .put("state_fingerprint", currentStateFingerprint)
    }

    fun markTerminal(
        verified: Boolean,
        evidence: String
    ): JSONObject {
        if (!verified) {
            terminalVerified = false
            terminalEvidence = evidence.take(MAX_EVIDENCE_CHARS)
            status = STATUS_FAILED
            return terminalDecision(false, "terminal_not_verified")
        }

        if (unresolvedSideEffect) {
            status = STATUS_PAUSED
            terminalVerified = false
            terminalEvidence = evidence.take(MAX_EVIDENCE_CHARS)
            return terminalDecision(false, "unresolved_side_effect")
        }

        if (currentProposal != null) {
            status = STATUS_PAUSED
            terminalVerified = false
            terminalEvidence = evidence.take(MAX_EVIDENCE_CHARS)
            return terminalDecision(false, "proposal_still_in_flight")
        }

        terminalVerified = true
        terminalEvidence = evidence.take(MAX_EVIDENCE_CHARS)
        status = STATUS_SUCCEEDED
        return terminalDecision(true, "verified_terminal")
    }

    fun canDeclareSuccess(): Boolean =
        status == STATUS_SUCCEEDED &&
            terminalVerified &&
            !unresolvedSideEffect &&
            currentProposal == null

    fun hasUnresolvedSideEffect(): Boolean = unresolvedSideEffect

    fun currentRevision(): Int = revision

    fun verifiedStepCount(): Int = verifiedSteps.length()

    fun failedTransitionCount(): Int = failedTransitions.length()

    fun replanCount(): Int = replans.length()

    fun status(): String = status

    fun persistenceSnapshot(): JSONObject {
        val full =
            JSONObject()
                .put("version", VERSION)
                .put("objective", objective.take(MAX_OBJECTIVE_CHARS))
                .put("revision", revision)
                .put("status", status)
                .put("execution_lane", executionLane)
                .put("authority_context", authorityContext.take(MAX_EVIDENCE_CHARS))
                .put("current_state_fingerprint", currentStateFingerprint)
                .put(
                    "current_proposal",
                    currentProposal?.let { boundedJson(it, MAX_PROPOSAL_JSON_CHARS) }
                        ?: JSONObject.NULL
                )
                .put("verified_steps", boundedArray(verifiedSteps, MAX_PERSISTED_VERIFIED_STEPS))
                .put("failed_transitions", boundedArray(failedTransitions, MAX_PERSISTED_FAILED_TRANSITIONS))
                .put("replans", boundedArray(replans, MAX_PERSISTED_REPLANS))
                .put("reconciliations", boundedArray(reconciliations, MAX_PERSISTED_RECONCILIATIONS))
                .put("unresolved_side_effect", unresolvedSideEffect)
                .put("terminal_verified", terminalVerified)
                .put("terminal_evidence", terminalEvidence.take(MAX_PERSISTED_TERMINAL_EVIDENCE_CHARS))
                .put("proposal_sequence", proposalSequence)
                .put("updated_at_ms", System.currentTimeMillis())

        if (full.toString().length <= MAX_PERSISTED_SNAPSHOT_CHARS) {
            return full
        }

        // Hard persistence cap. Task Graph / Durable Goal remain the complete long-task
        // evidence sources; the adaptive ledger may compact old detail but never drops the
        // revision number, unresolved-side-effect bit or terminal truth.
        return JSONObject()
            .put("version", VERSION)
            .put("objective", objective.take(600))
            .put("revision", revision)
            .put("status", status)
            .put("execution_lane", executionLane)
            .put("authority_context", authorityContext.take(600))
            .put("current_state_fingerprint", currentStateFingerprint)
            .put("current_proposal", currentProposal?.let { boundedJson(it, 900) } ?: JSONObject.NULL)
            .put("verified_steps", boundedArray(verifiedSteps, 6))
            .put("failed_transitions", boundedArray(failedTransitions, 4))
            .put("replans", boundedArray(replans, 4))
            .put("reconciliations", boundedArray(reconciliations, 4))
            .put("unresolved_side_effect", unresolvedSideEffect)
            .put("terminal_verified", terminalVerified)
            .put("terminal_evidence", terminalEvidence.take(240))
            .put("proposal_sequence", proposalSequence)
            .put("compacted", true)
            .put("updated_at_ms", System.currentTimeMillis())
    }

    fun compactSummary(): String =
        "adaptive_loop=v$VERSION; lane=$executionLane; revision=$revision; status=$status; " +
            "verified=${verifiedSteps.length()}; failed=${failedTransitions.length()}; " +
            "replans=${replans.length()}; unresolved_side_effect=$unresolvedSideEffect; " +
            "terminal_verified=$terminalVerified"

    private fun terminalDecision(
        allowed: Boolean,
        reason: String
    ): JSONObject =
        JSONObject()
            .put("allowed", allowed)
            .put("reason", reason)
            .put("status", status)
            .put("revision", revision)
            .put("verified_step_count", verifiedSteps.length())
            .put("replan_count", replans.length())
            .put("unresolved_side_effect", unresolvedSideEffect)
            .put("terminal_verified", terminalVerified)
            .put("execution_lane", executionLane)

    private fun decision(
        allowed: Boolean,
        reason: String,
        signature: String
    ): JSONObject =
        JSONObject()
            .put("allowed", allowed)
            .put("reason", reason)
            .put("revision", revision)
            .put("signature", signature)
            .put("verified_prefix_count", verifiedSteps.length())
            .put("unresolved_side_effect", unresolvedSideEffect)
            .put("execution_lane", executionLane)

    private fun hasVerifiedTransition(
        signature: String,
        beforeStateFingerprint: String
    ): Boolean {
        for (index in 0 until verifiedSteps.length()) {
            val row = verifiedSteps.optJSONObject(index) ?: continue
            if (
                row.optString("signature") == signature &&
                row.optString("before_state") == beforeStateFingerprint
            ) {
                return true
            }
        }
        return false
    }

    private fun hasFailedTransition(
        signature: String,
        stateFingerprint: String,
        revisionAtLeast: Int
    ): Boolean {
        for (index in 0 until failedTransitions.length()) {
            val row = failedTransitions.optJSONObject(index) ?: continue
            if (
                row.optInt("revision", 0) >= revisionAtLeast &&
                row.optString("signature") == signature &&
                row.optString("before_state") == stateFingerprint
            ) {
                return true
            }
        }
        return false
    }

    companion object {
        const val VERSION = "1.1"
        const val MAX_REVISIONS = 3

        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"

        private val TERMINAL_STATUSES =
            setOf(
                STATUS_SUCCEEDED,
                STATUS_FAILED,
                STATUS_CANCELLED
            )

        private const val MAX_OBJECTIVE_CHARS = 1800
        private const val MAX_TOOL_CHARS = 120
        private const val MAX_SHORT_CHARS = 220
        private const val MAX_EVIDENCE_CHARS = 1200
        private const val MAX_ARGUMENTS_JSON_CHARS = 2600
        private const val MAX_PROPOSAL_JSON_CHARS = 3600
        private const val MAX_FINGERPRINT_CHARS = 96
        private const val MAX_VERIFIED_STEPS = 32
        private const val MAX_FAILED_TRANSITIONS = 24
        private const val MAX_REPLANS_HISTORY = 8
        private const val MAX_RECONCILIATIONS = 12

        private const val MAX_PERSISTED_VERIFIED_STEPS = 12
        private const val MAX_PERSISTED_FAILED_TRANSITIONS = 8
        private const val MAX_PERSISTED_REPLANS = 6
        private const val MAX_PERSISTED_RECONCILIATIONS = 6
        private const val MAX_PERSISTED_TERMINAL_EVIDENCE_CHARS = 420
        private const val MAX_PERSISTED_SNAPSHOT_CHARS = 15_000

        fun create(
            objective: String,
            stateFingerprint: String = "",
            executionLane: String = "agent_core",
            authorityContext: String = "registered_capability_authority"
        ): AyanaAdaptiveExecutionLoop =
            AyanaAdaptiveExecutionLoop(
                objective = objective.trim().take(MAX_OBJECTIVE_CHARS),
                revision = 0,
                status = STATUS_ACTIVE,
                currentStateFingerprint = stateFingerprint.trim().take(MAX_FINGERPRINT_CHARS),
                currentProposal = null,
                verifiedSteps = JSONArray(),
                failedTransitions = JSONArray(),
                replans = JSONArray(),
                reconciliations = JSONArray(),
                unresolvedSideEffect = false,
                terminalVerified = false,
                terminalEvidence = "",
                proposalSequence = 0,
                executionLane = executionLane.trim().ifBlank { "agent_core" }.take(MAX_SHORT_CHARS),
                authorityContext = authorityContext.trim().take(MAX_EVIDENCE_CHARS)
            )

        fun restore(
            snapshot: JSONObject?,
            fallbackObjective: String,
            fallbackExecutionLane: String = "agent_core",
            fallbackAuthorityContext: String = "registered_capability_authority"
        ): AyanaAdaptiveExecutionLoop {
            if (snapshot == null || snapshot.length() == 0) {
                return create(
                    objective = fallbackObjective,
                    executionLane = fallbackExecutionLane,
                    authorityContext = fallbackAuthorityContext
                )
            }

            val version = snapshot.optString("version")
            if (
                version.isNotBlank() &&
                version !in setOf("1.0", VERSION)
            ) {
                return create(
                    objective = fallbackObjective,
                    executionLane = fallbackExecutionLane,
                    authorityContext = fallbackAuthorityContext
                )
            }

            return AyanaAdaptiveExecutionLoop(
                objective =
                    snapshot.optString("objective", fallbackObjective)
                        .ifBlank { fallbackObjective }
                        .take(MAX_OBJECTIVE_CHARS),
                revision = snapshot.optInt("revision", 0).coerceIn(0, MAX_REVISIONS),
                status =
                    snapshot.optString("status", STATUS_ACTIVE)
                        .uppercase(Locale.ROOT)
                        .takeIf {
                            it in setOf(
                                STATUS_ACTIVE,
                                STATUS_PAUSED,
                                STATUS_SUCCEEDED,
                                STATUS_FAILED,
                                STATUS_CANCELLED
                            )
                        }
                        ?: STATUS_ACTIVE,
                currentStateFingerprint =
                    snapshot.optString("current_state_fingerprint")
                        .take(MAX_FINGERPRINT_CHARS),
                currentProposal =
                    snapshot.optJSONObject("current_proposal")
                        ?.let { boundedJson(it, MAX_PROPOSAL_JSON_CHARS) },
                verifiedSteps =
                    boundedArray(
                        snapshot.optJSONArray("verified_steps") ?: JSONArray(),
                        MAX_VERIFIED_STEPS
                    ),
                failedTransitions =
                    boundedArray(
                        snapshot.optJSONArray("failed_transitions") ?: JSONArray(),
                        MAX_FAILED_TRANSITIONS
                    ),
                replans =
                    boundedArray(
                        snapshot.optJSONArray("replans") ?: JSONArray(),
                        MAX_REPLANS_HISTORY
                    ),
                reconciliations =
                    boundedArray(
                        snapshot.optJSONArray("reconciliations") ?: JSONArray(),
                        MAX_RECONCILIATIONS
                    ),
                unresolvedSideEffect = snapshot.optBoolean("unresolved_side_effect", false),
                terminalVerified = snapshot.optBoolean("terminal_verified", false),
                terminalEvidence = snapshot.optString("terminal_evidence").take(MAX_EVIDENCE_CHARS),
                proposalSequence = snapshot.optInt("proposal_sequence", 0).coerceAtLeast(0),
                executionLane =
                    snapshot.optString("execution_lane", fallbackExecutionLane)
                        .ifBlank { fallbackExecutionLane }
                        .take(MAX_SHORT_CHARS),
                authorityContext =
                    snapshot.optString("authority_context", fallbackAuthorityContext)
                        .ifBlank { fallbackAuthorityContext }
                        .take(MAX_EVIDENCE_CHARS)
            )
        }

        fun fingerprintState(raw: String): String {
            val normalized =
                raw
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(5000)

            if (normalized.isBlank()) {
                return "state:none"
            }

            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.toByteArray(Charsets.UTF_8))
                    .joinToString("") { byte -> "%02x".format(byte) }
                "sha256:${digest.take(32)}"
            } catch (_: Exception) {
                "hash:${normalized.hashCode()}"
            }
        }

        fun selfTest(): Boolean {
            val loop = create("test objective", "state-A")
            val args1 = JSONObject().put("name", "Camera")

            val proposal1 = loop.propose(
                toolName = "open_app",
                arguments = args1,
                stateFingerprint = "state-A",
                mayMutate = false
            )
            if (!proposal1.optBoolean("allowed", false)) return false

            val first = loop.recordResult(
                toolName = "open_app",
                arguments = args1,
                beforeStateFingerprint = "state-A",
                afterStateFingerprint = "state-B",
                success = true,
                verified = true,
                actionDispatched = true,
                actionCommitted = false,
                reconciliationComplete = true,
                evidence = "foreground verified"
            )
            if (!first.optBoolean("verified", false)) return false

            val args2 = JSONObject().put("query", "marker")
            val proposal2 = loop.propose(
                toolName = "get_screen_state",
                arguments = args2,
                stateFingerprint = "state-B",
                mayMutate = false
            )
            if (!proposal2.optBoolean("allowed", false)) return false

            loop.recordResult(
                toolName = "get_screen_state",
                arguments = args2,
                beforeStateFingerprint = "state-B",
                afterStateFingerprint = "state-B",
                success = false,
                verified = false,
                actionDispatched = false,
                actionCommitted = false,
                reconciliationComplete = true,
                evidence = "marker not observed",
                failureLayer = "verification",
                replanRecommended = true
            )

            val replan = loop.beginReplan(
                reason = "use verified visual fallback",
                stateFingerprint = "state-B"
            )
            if (!replan.optBoolean("allowed", false)) return false
            if (replan.optInt("verified_prefix_count", 0) != 1) return false

            val replay = loop.propose(
                toolName = "get_screen_state",
                arguments = args2,
                stateFingerprint = "state-B",
                mayMutate = false
            )
            if (replay.optBoolean("allowed", true)) return false

            val args3 = JSONObject().put("mode", "structured_visual")
            if (
                !loop.propose(
                    toolName = "verified_structured_screen_read",
                    arguments = args3,
                    stateFingerprint = "state-B",
                    mayMutate = false
                ).optBoolean("allowed", false)
            ) return false

            loop.recordResult(
                toolName = "verified_structured_screen_read",
                arguments = args3,
                beforeStateFingerprint = "state-B",
                afterStateFingerprint = "state-B",
                success = true,
                verified = true,
                actionDispatched = false,
                actionCommitted = false,
                reconciliationComplete = true,
                evidence = "structured visual verified"
            )

            val terminal = loop.markTerminal(true, "objective verified")
            if (!terminal.optBoolean("allowed", false)) return false
            if (!loop.canDeclareSuccess()) return false

            val restored = restore(loop.persistenceSnapshot(), "fallback")
            if (!restored.canDeclareSuccess()) return false
            if (restored.verifiedStepCount() != 2) return false
            if (restored.replanCount() != 1) return false

            val uncertain = create("uncertain", "state-X")
            val unsafeArgs = JSONObject().put("target", "button")
            uncertain.propose(
                toolName = "click_screen_element",
                arguments = unsafeArgs,
                stateFingerprint = "state-X",
                mayMutate = true
            )
            uncertain.recordResult(
                toolName = "click_screen_element",
                arguments = unsafeArgs,
                beforeStateFingerprint = "state-X",
                afterStateFingerprint = "state-X",
                success = false,
                verified = false,
                actionDispatched = true,
                actionCommitted = false,
                reconciliationComplete = false,
                evidence = "outcome unknown"
            )
            if (!uncertain.hasUnresolvedSideEffect()) return false
            if (uncertain.beginReplan("try another click", "state-X").optBoolean("allowed", true)) return false
            if (uncertain.markTerminal(true, "pretend done").optBoolean("allowed", true)) return false

            val laneBound =
                create(
                    objective = "lane test",
                    stateFingerprint = "lane-state",
                    executionLane = "multi_app",
                    authorityContext = "app_integration_registry"
                )

            if (laneBound.executionLane() != "multi_app") return false

            laneBound.bindExecutionContext(
                lane = "android_goal",
                authority = "goal_compiler+registered_executor"
            )

            if (laneBound.executionLane() != "android_goal") return false

            val backwardSnapshot =
                laneBound.persistenceSnapshot()
                    .put("version", "1.0")
                    .apply {
                        remove("execution_lane")
                        remove("authority_context")
                    }

            val backwardRestored =
                restore(
                    snapshot = backwardSnapshot,
                    fallbackObjective = "lane test",
                    fallbackExecutionLane = "agent_core",
                    fallbackAuthorityContext = "registered_capability_authority"
                )

            if (backwardRestored.executionLane() != "agent_core") return false

            return true
        }

        private fun signature(
            toolName: String,
            arguments: JSONObject
        ): String {
            val canonical = canonicalJson(arguments)
            val raw = toolName.trim().lowercase(Locale.ROOT) + "|" + canonical
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.toByteArray(Charsets.UTF_8))
                    .joinToString("") { byte -> "%02x".format(byte) }
                digest.take(40)
            } catch (_: Exception) {
                raw.hashCode().toString()
            }
        }

        private fun canonicalJson(value: JSONObject): String {
            val keys = mutableListOf<String>()
            val iterator = value.keys()
            while (iterator.hasNext()) {
                keys += iterator.next()
            }
            keys.sort()

            return buildString {
                append('{')
                keys.forEachIndexed { index, key ->
                    if (index > 0) append(',')
                    append(JSONObject.quote(key))
                    append(':')
                    append(canonicalValue(value.opt(key)))
                }
                append('}')
            }
        }

        private fun canonicalArray(value: JSONArray): String =
            buildString {
                append('[')
                for (index in 0 until value.length()) {
                    if (index > 0) append(',')
                    append(canonicalValue(value.opt(index)))
                }
                append(']')
            }

        private fun canonicalValue(value: Any?): String =
            when (value) {
                null,
                JSONObject.NULL -> "null"
                is JSONObject -> canonicalJson(value)
                is JSONArray -> canonicalArray(value)
                is Number,
                is Boolean -> value.toString()
                else -> JSONObject.quote(value.toString())
            }

        private fun boundedJson(
            value: JSONObject,
            maxChars: Int
        ): JSONObject {
            val raw = value.toString()
            return if (raw.length <= maxChars) {
                JSONObject(raw)
            } else {
                JSONObject()
                    .put("truncated", true)
                    .put("fingerprint", fingerprintState(raw))
                    .put("original_chars", raw.length)
            }
        }

        private fun boundedArray(
            source: JSONArray,
            limit: Int
        ): JSONArray {
            val result = JSONArray()
            val from = (source.length() - limit).coerceAtLeast(0)
            for (index in from until source.length()) {
                val item = source.opt(index)
                when (item) {
                    is JSONObject -> result.put(JSONObject(item.toString()))
                    is JSONArray -> result.put(JSONArray(item.toString()))
                    null -> Unit
                    else -> result.put(item)
                }
            }
            return result
        }

        private fun trimArray(
            array: JSONArray,
            limit: Int
        ) {
            while (array.length() > limit) {
                array.remove(0)
            }
        }
    }
}
