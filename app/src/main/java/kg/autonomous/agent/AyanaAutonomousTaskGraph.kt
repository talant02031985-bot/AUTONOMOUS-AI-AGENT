package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * AYANA R9.2 Autonomous Task Graph v2.0 — recovery / reconciliation ledger.
 *
 * Backward-compatible with the R9.0 v1 persistence shape. The graph still sits above
 * Durable Goal and does not replace the proven orchestrator. v2 adds enough execution
 * truth to make long-task recovery deterministic:
 * - per-node attempt/recovery counters;
 * - pre-dispatch tool identity;
 * - side-effect/reconciliation state;
 * - explicit recovery decisions;
 * - bounded replay metadata persisted across service restart;
 * - no automatic retry authority inside the graph itself.
 */
class AyanaAutonomousTaskGraph private constructor(
    private val graphId: String,
    private val goal: String,
    private val createdAtMs: Long,
    private val nodes: JSONArray,
    private val transitions: JSONArray,
    private var status: String,
    private var activeNodeId: String,
    private var agentSteps: Int,
    private var actionCount: Int,
    private var replanCount: Int,
    private var recoveryCount: Int,
    private var reconciliationCount: Int,
    private var lastEvidence: String,
    private var lastToolSignature: String,
    private var reconciliationRequired: Boolean
) {

    fun recordAgentStep(
        step: Int,
        responseType: String = ""
    ) {
        agentSteps = maxOf(agentSteps, step)
        addTransition(
            kind = "agent_step",
            detail = "step=$step; response_type=${responseType.take(80)}"
        )
    }

    /**
     * Must be called before an executor is allowed to dispatch a tool. It is evidence,
     * not permission. The orchestrator still owns Safety/confirmation/side-effect gates.
     */
    fun recordToolDispatch(
        toolName: String,
        signature: String,
        mayMutate: Boolean
    ) {
        val node = activeNode()
        val reconciliationObservation =
            !mayMutate && requiresReconciliation()

        if (node != null) {
            if (reconciliationObservation) {
                node.put(
                    "observation_attempt_count",
                    node.optInt("observation_attempt_count", 0) + 1
                )
                node.put("last_observation_tool", toolName.take(120))
                node.put("last_observation_signature", signature.take(MAX_SIGNATURE_CHARS))
                node.put("observation_in_flight", true)
                node.put("status", STATUS_WAITING_RECONCILIATION)
                node.put("reconciliation_required", true)
            } else {
                node.put(
                    "attempt_count",
                    node.optInt("attempt_count", 0) + 1
                )
                node.put("last_tool_name", toolName.take(120))
                node.put("last_tool_signature", signature.take(MAX_SIGNATURE_CHARS))
                node.put("last_tool_may_mutate", mayMutate)
                node.put("dispatch_in_flight", true)
                node.put("last_dispatch_at_ms", System.currentTimeMillis())

                if (mayMutate) {
                    // Persist uncertainty BEFORE executor dispatch. If the process
                    // dies after Android receives the action but before result
                    // persistence, restore() will still block blind replay.
                    reconciliationRequired = true
                    node.put("reconciliation_required", true)
                }

                if (node.optString("status") in setOf(STATUS_PENDING, STATUS_BLOCKED, STATUS_PAUSED)) {
                    node.put("status", STATUS_ACTIVE)
                }
            }
        }

        if (!reconciliationObservation) {
            lastToolSignature = signature.take(MAX_SIGNATURE_CHARS)
        }

        addTransition(
            kind =
                if (reconciliationObservation) {
                    "reconciliation_observation_dispatch"
                } else {
                    "tool_dispatch"
                },
            detail =
                "tool=${toolName.take(120)}; mutate=$mayMutate; " +
                    "reconciliation_observation=$reconciliationObservation; " +
                    "signature=${signature.take(260)}"
        )
    }

    /**
     * Records a read-only observation used to reconcile a previously uncertain
     * side effect. A successful observation is evidence only: it must NOT advance
     * the active subgoal or clear reconciliation_required by itself.
     */
    fun recordObservationResult(
        toolName: String,
        success: Boolean,
        verified: Boolean,
        terminalStatus: String = "",
        evidence: String = ""
    ) {
        actionCount++
        lastEvidence = evidence.take(MAX_EVIDENCE_CHARS)

        val node = activeNode()
        if (node != null) {
            node.put("last_observation_tool", toolName.take(120))
            node.put("last_observation_success", success)
            node.put("last_observation_verified", verified)
            node.put("last_observation_terminal", terminalStatus.take(40))
            node.put("last_observation_evidence", lastEvidence.take(MAX_NODE_EVIDENCE_CHARS))
            node.put("observation_in_flight", false)
            if (reconciliationRequired) {
                node.put("status", STATUS_WAITING_RECONCILIATION)
                node.put("reconciliation_required", true)
            }
        }

        addTransition(
            kind = "reconciliation_observation",
            detail =
                "tool=${toolName.take(120)}; success=$success; verified=$verified; " +
                    "terminal=${terminalStatus.take(40)}; evidence=${lastEvidence.take(300)}"
        )
    }

    fun recordToolResult(
        toolName: String,
        success: Boolean,
        verified: Boolean,
        terminalStatus: String = "",
        evidence: String = "",
        actionDispatched: Boolean? = null
    ) {
        actionCount++
        lastEvidence = evidence.take(MAX_EVIDENCE_CHARS)

        val node = activeNode()
        val lastToolMayMutate =
            node?.optBoolean("last_tool_may_mutate", false) == true
        val effectiveActionDispatched =
            actionDispatched ?: lastToolMayMutate

        if (node != null) {
            node.put("dispatch_in_flight", false)
            node.put("last_result_success", success)
            node.put("last_result_verified", verified)
            node.put("last_terminal_status", terminalStatus.take(40))
            node.put("last_action_dispatched", effectiveActionDispatched)
            node.put("last_failure_reason", if (success && verified) "" else lastEvidence.take(500))
        }

        if (!effectiveActionDispatched && lastToolMayMutate) {
            node?.put("reconciliation_required", false)
            reconciliationRequired = anyNodeRequiresReconciliation(nodes)
        }

        addTransition(
            kind = "tool_result",
            detail =
                "tool=${toolName.take(120)}; success=$success; verified=$verified; " +
                    "terminal=${terminalStatus.take(40)}; evidence=${lastEvidence.take(300)}"
        )

        if (!success || !verified) {
            if (lastToolMayMutate && effectiveActionDispatched) {
                reconciliationRequired = true
                node?.put("reconciliation_required", true)
                setActiveNodeStatus(STATUS_WAITING_RECONCILIATION)
            } else {
                setActiveNodeStatus(STATUS_BLOCKED)
            }
        } else {
            if (node != null) {
                node.put("verified_evidence", lastEvidence.take(MAX_NODE_EVIDENCE_CHARS))
                node.put("reconciliation_required", false)
                node.put("last_failure_reason", "")
            }
            reconciliationRequired = false
            advanceAfterVerifiedAction()
        }
    }

    fun recordRecoveryDecision(
        decision: JSONObject
    ) {
        recoveryCount++

        val strategy =
            decision.optString("strategy", "UNKNOWN")
                .take(80)

        val reason =
            decision.optString("reason")
                .take(MAX_NODE_EVIDENCE_CHARS)

        val node = activeNode()
        if (node != null) {
            node.put(
                "recovery_count",
                node.optInt("recovery_count", 0) + 1
            )
            node.put("last_recovery_strategy", strategy)
            node.put("last_recovery_reason", reason)
            node.put("last_recovery_at_ms", System.currentTimeMillis())

            if (decision.optBoolean("pause_required", false)) {
                node.put("status", STATUS_PAUSED)
            }

            if (decision.optBoolean("fresh_observation_required", false)) {
                node.put("fresh_observation_required", true)
            }
        }

        addTransition(
            kind = "recovery_decision",
            detail =
                "strategy=$strategy; reason=$reason; " +
                    "local_retry=${decision.optBoolean("local_retry_allowed", false)}; " +
                    "replan=${decision.optBoolean("replan_allowed", false)}"
        )
    }

    fun recordRecovery(
        kind: String,
        detail: String
    ) {
        recoveryCount++
        val node = activeNode()
        node?.put(
            "recovery_count",
            node.optInt("recovery_count", 0) + 1
        )
        addTransition(
            kind = "recovery:$kind",
            detail = detail.take(MAX_EVIDENCE_CHARS)
        )
    }

    fun recordReplan(
        reason: String
    ) {
        replanCount++
        status = STATUS_ACTIVE

        val node = activeNode()
        node?.put("last_replan_reason", reason.take(MAX_NODE_EVIDENCE_CHARS))
        node?.put("fresh_observation_required", true)

        addTransition(
            kind = "replan",
            detail = reason.take(MAX_EVIDENCE_CHARS)
        )
    }

    /**
     * Records an outcome that may have changed Android state but is not yet reconciled.
     * This state must survive process restart; callers must not blindly redispatch it.
     */
    fun markReconciliationRequired(
        toolName: String,
        detail: String
    ) {
        reconciliationRequired = true
        status = STATUS_PAUSED

        activeNode()?.apply {
            put("status", STATUS_WAITING_RECONCILIATION)
            put("reconciliation_required", true)
            put("last_tool_name", toolName.take(120))
            put("last_failure_reason", detail.take(MAX_NODE_EVIDENCE_CHARS))
        }

        addTransition(
            kind = "reconciliation_required",
            detail = "tool=${toolName.take(120)}; ${detail.take(800)}"
        )
    }

    fun recordReconciliation(
        committed: Boolean,
        verified: Boolean,
        evidence: String
    ) {
        reconciliationCount++
        reconciliationRequired = false
        lastEvidence = evidence.take(MAX_EVIDENCE_CHARS)

        val node = activeNode()
        node?.put("reconciliation_required", false)
        node?.put("reconciliation_committed", committed)
        node?.put("reconciliation_verified", verified)
        node?.put("reconciliation_evidence", lastEvidence.take(MAX_NODE_EVIDENCE_CHARS))

        if (committed && verified) {
            advanceAfterVerifiedAction()
        } else {
            status = STATUS_PAUSED
            node?.put("status", STATUS_PAUSED)
        }

        addTransition(
            kind = "reconciliation_result",
            detail = "committed=$committed; verified=$verified; evidence=${lastEvidence.take(500)}"
        )
    }

    fun activeNodeRecoveryCount(): Int =
        activeNode()?.optInt("recovery_count", 0) ?: 0

    fun activeNodeAttemptCount(): Int =
        activeNode()?.optInt("attempt_count", 0) ?: 0

    fun activeNodeSnapshot(): JSONObject =
        activeNode()
            ?.let { JSONObject(it.toString()) }
            ?: JSONObject()

    fun requiresReconciliation(): Boolean =
        reconciliationRequired ||
            (activeNode()?.optBoolean("reconciliation_required", false) == true)

    fun pause(
        reason: String
    ) {
        status = STATUS_PAUSED
        activeNode()?.put("status", STATUS_PAUSED)
        addTransition("paused", reason)
    }

    fun finish(
        success: Boolean,
        terminalStatus: String,
        finalEvidence: String = ""
    ) {
        status =
            if (success) {
                STATUS_SUCCEEDED
            } else if (terminalStatus.equals("CANCELLED", ignoreCase = true)) {
                STATUS_CANCELLED
            } else {
                STATUS_FAILED
            }

        if (finalEvidence.isNotBlank()) {
            lastEvidence = finalEvidence.take(MAX_EVIDENCE_CHARS)
        }

        if (success) {
            reconciliationRequired = false
            for (index in 0 until nodes.length()) {
                val node = nodes.optJSONObject(index) ?: continue
                if (node.optString("status") !in TERMINAL_NODE_STATUSES) {
                    node.put("status", STATUS_SUCCEEDED)
                }
                node.put("reconciliation_required", false)
            }
        }

        addTransition(
            kind = "terminal",
            detail = "success=$success; terminal=${terminalStatus.take(40)}; evidence=${lastEvidence.take(500)}"
        )
    }

    fun snapshot(): JSONObject =
        JSONObject()
            .put("version", VERSION)
            .put("graph_id", graphId)
            .put("goal", goal)
            .put("created_at_ms", createdAtMs)
            .put("status", status)
            .put("active_node_id", activeNodeId)
            .put("agent_steps", agentSteps)
            .put("action_count", actionCount)
            .put("replan_count", replanCount)
            .put("recovery_count", recoveryCount)
            .put("reconciliation_count", reconciliationCount)
            .put("last_evidence", lastEvidence)
            .put("last_tool_signature", lastToolSignature)
            .put("reconciliation_required", reconciliationRequired)
            .put("nodes", JSONArray(nodes.toString()))
            .put("transitions", JSONArray(transitions.toString()))

    fun compactSummary(): String =
        "graph=$graphId; status=$status; nodes=${nodes.length()}; " +
            "steps=$agentSteps; actions=$actionCount; replans=$replanCount; " +
            "recoveries=$recoveryCount; reconciliations=$reconciliationCount; " +
            "reconciliation_required=${requiresReconciliation()}"

    /**
     * Bounded durable representation. v2 deliberately persists recovery and
     * reconciliation truth while keeping the goal file small.
     */
    fun persistenceSnapshot(): JSONObject {
        val compactNodes = JSONArray()
        val nodeLimit = minOf(nodes.length(), MAX_PERSISTED_NODES)

        for (index in 0 until nodeLimit) {
            val node = nodes.optJSONObject(index) ?: continue

            compactNodes.put(
                JSONObject()
                    .put("id", node.optString("id").take(80))
                    .put("label", node.optString("label").take(360))
                    .put("status", node.optString("status").take(40))
                    .put("attempt_count", node.optInt("attempt_count", 0))
                    .put("recovery_count", node.optInt("recovery_count", 0))
                    .put("last_tool_name", node.optString("last_tool_name").take(120))
                    .put("last_tool_signature", node.optString("last_tool_signature").take(MAX_SIGNATURE_CHARS))
                    .put("last_tool_may_mutate", node.optBoolean("last_tool_may_mutate", false))
                    .put("dispatch_in_flight", node.optBoolean("dispatch_in_flight", false))
                    .put("observation_attempt_count", node.optInt("observation_attempt_count", 0))
                    .put("last_observation_tool", node.optString("last_observation_tool").take(120))
                    .put("last_terminal_status", node.optString("last_terminal_status").take(40))
                    .put("last_failure_reason", node.optString("last_failure_reason").take(MAX_PERSISTED_LAST_EVIDENCE_CHARS))
                    .put("verified_evidence", node.optString("verified_evidence").take(MAX_PERSISTED_LAST_EVIDENCE_CHARS))
                    .put("reconciliation_required", node.optBoolean("reconciliation_required", false))
                    .put("reconciliation_committed", node.optBoolean("reconciliation_committed", false))
                    .put("reconciliation_verified", node.optBoolean("reconciliation_verified", false))
                    .put("last_recovery_strategy", node.optString("last_recovery_strategy").take(80))
            )
        }

        val compactTransitions = JSONArray()
        val from =
            (transitions.length() - MAX_PERSISTED_TRANSITIONS)
                .coerceAtLeast(0)

        for (index in from until transitions.length()) {
            val item = transitions.optJSONObject(index) ?: continue
            compactTransitions.put(
                JSONObject()
                    .put("at_ms", item.optLong("at_ms", 0L))
                    .put("kind", item.optString("kind").take(80))
                    .put("active_node_id", item.optString("active_node_id").take(80))
                    .put("detail", item.optString("detail").take(MAX_PERSISTED_TRANSITION_DETAIL_CHARS))
            )
        }

        return JSONObject()
            .put("version", VERSION)
            .put("graph_id", graphId)
            .put("goal", goal.take(MAX_PERSISTED_GOAL_CHARS))
            .put("created_at_ms", createdAtMs)
            .put("status", status)
            .put("active_node_id", activeNodeId)
            .put("agent_steps", agentSteps)
            .put("action_count", actionCount)
            .put("replan_count", replanCount)
            .put("recovery_count", recoveryCount)
            .put("reconciliation_count", reconciliationCount)
            .put("last_evidence", lastEvidence.take(MAX_PERSISTED_LAST_EVIDENCE_CHARS))
            .put("last_tool_signature", lastToolSignature.take(MAX_SIGNATURE_CHARS))
            .put("reconciliation_required", requiresReconciliation())
            .put("nodes", compactNodes)
            .put("transitions", compactTransitions)
    }

    private fun advanceAfterVerifiedAction() {
        val activeIndex = indexOfNode(activeNodeId)

        if (activeIndex >= 0) {
            nodes.optJSONObject(activeIndex)?.apply {
                put("status", STATUS_SUCCEEDED)
                put("fresh_observation_required", false)
                put("reconciliation_required", false)
            }
        }

        val nextIndex = (activeIndex + 1).coerceAtLeast(0)
        val next =
            if (nextIndex < nodes.length()) {
                nodes.optJSONObject(nextIndex)
            } else {
                null
            }

        if (next != null) {
            activeNodeId = next.optString("id")
            next.put("status", STATUS_ACTIVE)
        }
    }

    private fun setActiveNodeStatus(
        nodeStatus: String
    ) {
        activeNode()?.put("status", nodeStatus)
    }

    private fun activeNode(): JSONObject? {
        val index = indexOfNode(activeNodeId)
        return if (index >= 0) nodes.optJSONObject(index) else null
    }

    private fun indexOfNode(
        id: String
    ): Int {
        if (id.isBlank()) return -1
        for (index in 0 until nodes.length()) {
            if (nodes.optJSONObject(index)?.optString("id") == id) {
                return index
            }
        }
        return -1
    }

    private fun addTransition(
        kind: String,
        detail: String
    ) {
        if (transitions.length() >= MAX_TRANSITIONS) {
            val compact = JSONArray()
            val keepFrom = (transitions.length() - MAX_TRANSITIONS / 2).coerceAtLeast(0)
            for (index in keepFrom until transitions.length()) {
                transitions.optJSONObject(index)?.let { compact.put(it) }
            }
            while (transitions.length() > 0) {
                transitions.remove(transitions.length() - 1)
            }
            for (index in 0 until compact.length()) {
                transitions.put(compact.optJSONObject(index))
            }
        }

        transitions.put(
            JSONObject()
                .put("at_ms", System.currentTimeMillis())
                .put("kind", kind.take(80))
                .put("active_node_id", activeNodeId)
                .put("detail", detail.take(MAX_EVIDENCE_CHARS))
        )
    }

    companion object {
        const val VERSION = "2.0"

        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_BLOCKED = "BLOCKED"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_WAITING_RECONCILIATION = "WAITING_RECONCILIATION"
        const val STATUS_CANCELLED = "CANCELLED"

        private const val MAX_TRANSITIONS = 100
        private const val MAX_EVIDENCE_CHARS = 1400
        private const val MAX_NODE_EVIDENCE_CHARS = 700
        private const val MAX_SIGNATURE_CHARS = 420

        private const val MAX_PERSISTED_NODES = 20
        private const val MAX_PERSISTED_TRANSITIONS = 20
        private const val MAX_PERSISTED_TRANSITION_DETAIL_CHARS = 420
        private const val MAX_PERSISTED_LAST_EVIDENCE_CHARS = 700
        private const val MAX_PERSISTED_GOAL_CHARS = 1400

        private val TERMINAL_NODE_STATUSES =
            setOf(
                STATUS_SUCCEEDED,
                STATUS_FAILED,
                STATUS_CANCELLED
            )

        fun create(
            goal: String,
            plannerEnvelope: JSONObject? = null
        ): AyanaAutonomousTaskGraph {
            val graphId =
                "tg-" +
                    System.currentTimeMillis() +
                    "-" +
                    UUID.randomUUID().toString().take(6)

            val nodes = nodesFromPlanner(goal, plannerEnvelope)
            val first = nodes.optJSONObject(0)
            first?.put("status", STATUS_ACTIVE)

            return AyanaAutonomousTaskGraph(
                graphId = graphId,
                goal = goal.take(4000),
                createdAtMs = System.currentTimeMillis(),
                nodes = nodes,
                transitions = JSONArray(),
                status = STATUS_ACTIVE,
                activeNodeId = first?.optString("id").orEmpty(),
                agentSteps = 0,
                actionCount = 0,
                replanCount = 0,
                recoveryCount = 0,
                reconciliationCount = 0,
                lastEvidence = "",
                lastToolSignature = "",
                reconciliationRequired = false
            ).also {
                it.addTransition("created", "nodes=${nodes.length()}; version=$VERSION")
            }
        }

        fun restore(
            snapshot: JSONObject?,
            fallbackGoal: String,
            plannerEnvelope: JSONObject? = null
        ): AyanaAutonomousTaskGraph {
            if (snapshot == null || snapshot.length() == 0) {
                return create(fallbackGoal, plannerEnvelope)
            }

            val restoredNodes =
                snapshot.optJSONArray("nodes")
                    ?.let { JSONArray(it.toString()) }
                    ?: nodesFromPlanner(fallbackGoal, plannerEnvelope)

            normalizeNodes(restoredNodes)

            val restoredTransitions =
                snapshot.optJSONArray("transitions")
                    ?.let { JSONArray(it.toString()) }
                    ?: JSONArray()

            val inferredReconciliation =
                snapshot.optBoolean("reconciliation_required", false) ||
                    anyNodeRequiresReconciliation(restoredNodes)

            return AyanaAutonomousTaskGraph(
                graphId = snapshot.optString("graph_id").ifBlank {
                    "tg-restored-${System.currentTimeMillis()}"
                },
                goal = snapshot.optString("goal", fallbackGoal).take(4000),
                createdAtMs = snapshot.optLong("created_at_ms", System.currentTimeMillis()),
                nodes = restoredNodes,
                transitions = restoredTransitions,
                status = snapshot.optString("status", STATUS_ACTIVE),
                activeNodeId =
                    snapshot.optString("active_node_id")
                        .ifBlank { restoredNodes.optJSONObject(0)?.optString("id").orEmpty() },
                agentSteps = snapshot.optInt("agent_steps", 0),
                actionCount = snapshot.optInt("action_count", 0),
                replanCount = snapshot.optInt("replan_count", 0),
                recoveryCount = snapshot.optInt("recovery_count", 0),
                reconciliationCount = snapshot.optInt("reconciliation_count", 0),
                lastEvidence = snapshot.optString("last_evidence"),
                lastToolSignature = snapshot.optString("last_tool_signature"),
                reconciliationRequired = inferredReconciliation
            ).also {
                it.addTransition(
                    "restored",
                    "snapshot_version=${snapshot.optString("version", "1.0")}; reconciliation_required=$inferredReconciliation"
                )
            }
        }

        fun selfTest(): Boolean {
            val planner =
                JSONObject()
                    .put(
                        "subgoals",
                        JSONArray()
                            .put(JSONObject().put("goal", "open app"))
                            .put(JSONObject().put("goal", "verify screen"))
                    )

            val graph = create("test goal", planner)
            graph.recordAgentStep(1, "tool_calls")
            graph.recordToolDispatch(
                toolName = "open_app",
                signature = "open_app|camera",
                mayMutate = true
            )
            graph.recordToolResult(
                toolName = "open_app",
                success = false,
                verified = false,
                terminalStatus = "ERROR",
                evidence = "foreground not verified",
                actionDispatched = false
            )
            graph.recordRecoveryDecision(
                JSONObject()
                    .put("strategy", "REPLAN_WITH_FRESH_OBSERVATION")
                    .put("reason", "pre_dispatch_perception_failure")
                    .put("replan_allowed", true)
                    .put("pause_required", false)
                    .put("fresh_observation_required", true)
            )
            graph.recordReplan("test bounded replan")
            graph.markReconciliationRequired("open_app", "dispatch outcome uncertain")
            graph.recordObservationResult(
                toolName = "get_screen_state",
                success = true,
                verified = true,
                terminalStatus = "SUCCESS",
                evidence = "fresh screen acquired while reconciliation remains required"
            )
            val observationPreservedReconciliation =
                graph.requiresReconciliation()

            val persisted = graph.persistenceSnapshot()
            val restored = restore(persisted, "fallback")
            val restoredSnap = restored.snapshot()

            val backwardV1 =
                JSONObject()
                    .put("version", "1.0")
                    .put("graph_id", "legacy")
                    .put("goal", "legacy goal")
                    .put("status", STATUS_ACTIVE)
                    .put("active_node_id", "n1")
                    .put(
                        "nodes",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", "n1")
                                    .put("label", "legacy")
                                    .put("status", STATUS_ACTIVE)
                            )
                    )
                    .put("transitions", JSONArray())

            val legacyRestored = restore(backwardV1, "legacy goal")

            val crashWindowGraph =
                create("crash window", null)
            crashWindowGraph.recordToolDispatch(
                toolName = "open_app",
                signature = "open_app|camera",
                mayMutate = true
            )
            val crashWindowRestored =
                restore(
                    crashWindowGraph.persistenceSnapshot(),
                    "crash window"
                )
            val crashWindowProtected =
                crashWindowRestored.requiresReconciliation()

            val mutationSignatureBeforeObservation =
                restored.activeNodeSnapshot()
                    .optString("last_tool_signature")
            restored.recordToolDispatch(
                toolName = "get_screen_state",
                signature = "get_screen_state|{}",
                mayMutate = false
            )
            restored.recordObservationResult(
                toolName = "get_screen_state",
                success = true,
                verified = true,
                terminalStatus = "SUCCESS",
                evidence = "fresh observation"
            )
            val observationKeptMutationIdentity =
                restored.activeNodeSnapshot()
                    .optString("last_tool_signature") ==
                    mutationSignatureBeforeObservation &&
                    restored.requiresReconciliation()

            return persisted.optString("version") == VERSION &&
                restoredSnap.optString("graph_id") == persisted.optString("graph_id") &&
                restored.activeNodeAttemptCount() == 1 &&
                restored.activeNodeRecoveryCount() == 1 &&
                observationPreservedReconciliation &&
                observationKeptMutationIdentity &&
                crashWindowProtected &&
                restored.requiresReconciliation() &&
                legacyRestored.activeNodeAttemptCount() == 0 &&
                legacyRestored.snapshot().optJSONArray("nodes")?.length() == 1
        }

        private fun nodesFromPlanner(
            goal: String,
            plannerEnvelope: JSONObject?
        ): JSONArray {
            val result = JSONArray()
            val subgoals = plannerEnvelope?.optJSONArray("subgoals")

            if (subgoals != null) {
                for (index in 0 until subgoals.length()) {
                    val item = subgoals.optJSONObject(index) ?: continue
                    val label =
                        listOf(
                            item.optString("goal"),
                            item.optString("description"),
                            item.optString("label"),
                            item.optString("name")
                        )
                            .firstOrNull { it.isNotBlank() }
                            .orEmpty()
                            .ifBlank { "subgoal ${index + 1}" }

                    result.put(
                        newNode(
                            id = item.optString("id").ifBlank { "n${index + 1}" },
                            label = label
                        )
                    )
                }
            }

            if (result.length() == 0) {
                result.put(
                    newNode(
                        id = "n1",
                        label = goal.take(600).ifBlank { "goal" }
                    )
                )
            }

            return result
        }

        private fun newNode(
            id: String,
            label: String
        ): JSONObject =
            JSONObject()
                .put("id", id.take(80))
                .put("label", label.take(600))
                .put("status", STATUS_PENDING)
                .put("attempt_count", 0)
                .put("recovery_count", 0)
                .put("observation_attempt_count", 0)
                .put("last_tool_may_mutate", false)
                .put("dispatch_in_flight", false)
                .put("observation_in_flight", false)
                .put("reconciliation_required", false)
                .put("fresh_observation_required", false)

        private fun normalizeNodes(
            nodes: JSONArray
        ) {
            for (index in 0 until nodes.length()) {
                val node = nodes.optJSONObject(index) ?: continue
                if (!node.has("attempt_count")) node.put("attempt_count", 0)
                if (!node.has("recovery_count")) node.put("recovery_count", 0)
                if (!node.has("observation_attempt_count")) node.put("observation_attempt_count", 0)
                if (!node.has("last_tool_may_mutate")) node.put("last_tool_may_mutate", false)
                if (!node.has("dispatch_in_flight")) node.put("dispatch_in_flight", false)
                if (!node.has("observation_in_flight")) node.put("observation_in_flight", false)
                if (!node.has("reconciliation_required")) node.put("reconciliation_required", false)
                if (!node.has("fresh_observation_required")) node.put("fresh_observation_required", false)
            }
        }

        private fun anyNodeRequiresReconciliation(
            nodes: JSONArray
        ): Boolean {
            for (index in 0 until nodes.length()) {
                if (nodes.optJSONObject(index)?.optBoolean("reconciliation_required", false) == true) {
                    return true
                }
            }
            return false
        }
    }
}
