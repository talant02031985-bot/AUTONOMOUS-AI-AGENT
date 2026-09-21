package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * AYANA R9.0 Autonomous Task Graph v1.0.
 *
 * A pure execution-evidence graph layered on top of the existing Durable Goal / bounded
 * replan engine. It does NOT replace the proven orchestrator. It records what the
 * orchestrator actually did so recovery/resume decisions have explicit machine state.
 *
 * The graph is JSON-serializable and may be persisted inside Durable Goal checkpoints.
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
    private var lastEvidence: String
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

    fun recordToolResult(
        toolName: String,
        success: Boolean,
        verified: Boolean,
        terminalStatus: String = "",
        evidence: String = ""
    ) {
        actionCount++
        lastEvidence = evidence.take(MAX_EVIDENCE_CHARS)

        addTransition(
            kind = "tool_result",
            detail =
                "tool=${toolName.take(120)}; success=$success; verified=$verified; " +
                    "terminal=${terminalStatus.take(40)}; evidence=${lastEvidence.take(300)}"
        )

        if (!success || !verified) {
            setActiveNodeStatus(STATUS_BLOCKED)
        } else {
            advanceAfterVerifiedAction()
        }
    }

    fun recordReplan(
        reason: String
    ) {
        replanCount++
        status = STATUS_ACTIVE
        addTransition(
            kind = "replan",
            detail = reason.take(MAX_EVIDENCE_CHARS)
        )
    }

    fun recordRecovery(
        kind: String,
        detail: String
    ) {
        recoveryCount++
        addTransition(
            kind = "recovery:$kind",
            detail = detail.take(MAX_EVIDENCE_CHARS)
        )
    }

    fun pause(
        reason: String
    ) {
        status = STATUS_PAUSED
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
            for (index in 0 until nodes.length()) {
                val node = nodes.optJSONObject(index) ?: continue
                if (node.optString("status") !in TERMINAL_NODE_STATUSES) {
                    node.put("status", STATUS_SUCCEEDED)
                }
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
            .put("last_evidence", lastEvidence)
            .put("nodes", JSONArray(nodes.toString()))
            .put("transitions", JSONArray(transitions.toString()))

    fun compactSummary(): String =
        "graph=$graphId; status=$status; nodes=${nodes.length()}; " +
            "steps=$agentSteps; actions=$actionCount; replans=$replanCount; recoveries=$recoveryCount"

    /**
     * Bounded durable representation. Runtime snapshot() keeps full in-memory evidence,
     * while persistence never lets a long tool loop inflate ayana_durable_goals.json.
     */
    fun persistenceSnapshot(): JSONObject {
        val compactNodes =
            JSONArray()

        val nodeLimit =
            minOf(
                nodes.length(),
                MAX_PERSISTED_NODES
            )

        for (index in 0 until nodeLimit) {
            val node =
                nodes.optJSONObject(index)
                    ?: continue

            compactNodes.put(
                JSONObject()
                    .put(
                        "id",
                        node.optString("id")
                            .take(80)
                    )
                    .put(
                        "label",
                        node.optString("label")
                            .take(360)
                    )
                    .put(
                        "status",
                        node.optString("status")
                            .take(40)
                    )
            )
        }

        val compactTransitions =
            JSONArray()

        val from =
            (
                transitions.length() -
                    MAX_PERSISTED_TRANSITIONS
                )
                .coerceAtLeast(
                    0
                )

        for (index in from until transitions.length()) {
            val item =
                transitions.optJSONObject(index)
                    ?: continue

            compactTransitions.put(
                JSONObject()
                    .put(
                        "at_ms",
                        item.optLong(
                            "at_ms",
                            0L
                        )
                    )
                    .put(
                        "kind",
                        item.optString("kind")
                            .take(80)
                    )
                    .put(
                        "active_node_id",
                        item.optString(
                            "active_node_id"
                        )
                            .take(80)
                    )
                    .put(
                        "detail",
                        item.optString("detail")
                            .take(
                                MAX_PERSISTED_TRANSITION_DETAIL_CHARS
                            )
                    )
            )
        }

        return JSONObject()
            .put("version", VERSION)
            .put("graph_id", graphId)
            .put(
                "goal",
                goal.take(
                    MAX_PERSISTED_GOAL_CHARS
                )
            )
            .put("created_at_ms", createdAtMs)
            .put("status", status)
            .put("active_node_id", activeNodeId)
            .put("agent_steps", agentSteps)
            .put("action_count", actionCount)
            .put("replan_count", replanCount)
            .put("recovery_count", recoveryCount)
            .put(
                "last_evidence",
                lastEvidence.take(
                    MAX_PERSISTED_LAST_EVIDENCE_CHARS
                )
            )
            .put("nodes", compactNodes)
            .put("transitions", compactTransitions)
    }

    private fun advanceAfterVerifiedAction() {
        val activeIndex =
            indexOfNode(activeNodeId)

        if (activeIndex >= 0) {
            nodes
                .optJSONObject(activeIndex)
                ?.put("status", STATUS_SUCCEEDED)
        }

        val nextIndex =
            (activeIndex + 1)
                .coerceAtLeast(0)

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
        val index = indexOfNode(activeNodeId)
        if (index >= 0) {
            nodes.optJSONObject(index)?.put("status", nodeStatus)
        }
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
        const val VERSION = "1.0"

        const val STATUS_PENDING = "PENDING"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_BLOCKED = "BLOCKED"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_CANCELLED = "CANCELLED"

        private const val MAX_TRANSITIONS = 80
        private const val MAX_EVIDENCE_CHARS = 1400

        private const val MAX_PERSISTED_NODES = 20
        private const val MAX_PERSISTED_TRANSITIONS = 16
        private const val MAX_PERSISTED_TRANSITION_DETAIL_CHARS = 360
        private const val MAX_PERSISTED_LAST_EVIDENCE_CHARS = 600
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

            val nodes =
                nodesFromPlanner(
                    goal = goal,
                    plannerEnvelope = plannerEnvelope
                )

            val first =
                nodes.optJSONObject(0)

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
                lastEvidence = ""
            ).also {
                it.addTransition("created", "nodes=${nodes.length()}")
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

            val restoredTransitions =
                snapshot.optJSONArray("transitions")
                    ?.let { JSONArray(it.toString()) }
                    ?: JSONArray()

            return AyanaAutonomousTaskGraph(
                graphId = snapshot.optString("graph_id").ifBlank {
                    "tg-restored-${System.currentTimeMillis()}"
                },
                goal = snapshot.optString("goal", fallbackGoal).take(4000),
                createdAtMs = snapshot.optLong("created_at_ms", System.currentTimeMillis()),
                nodes = restoredNodes,
                transitions = restoredTransitions,
                status = snapshot.optString("status", STATUS_ACTIVE),
                activeNodeId = snapshot.optString("active_node_id"),
                agentSteps = snapshot.optInt("agent_steps", 0),
                actionCount = snapshot.optInt("action_count", 0),
                replanCount = snapshot.optInt("replan_count", 0),
                recoveryCount = snapshot.optInt("recovery_count", 0),
                lastEvidence = snapshot.optString("last_evidence")
            ).also {
                it.addTransition("restored", "snapshot_restored=true")
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
            graph.recordToolResult(
                toolName = "open_app",
                success = true,
                verified = true,
                evidence = "foreground verified"
            )
            graph.recordReplan("test bounded replan")
            graph.recordRecovery("transport", "bounded retry")
            graph.recordAgentStep(2, "final")
            graph.finish(true, "SUCCESS", "goal verified")

            val snap = graph.snapshot()
            val restored = restore(snap, "fallback")
            val restoredSnap = restored.snapshot()

            return snap.optString("status") == STATUS_SUCCEEDED &&
                snap.optInt("action_count") == 1 &&
                snap.optInt("replan_count") == 1 &&
                snap.optInt("recovery_count") == 1 &&
                snap.optJSONArray("nodes")?.length() == 2 &&
                restoredSnap.optString("graph_id") == snap.optString("graph_id")
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
                        JSONObject()
                            .put("id", item.optString("id").ifBlank { "n${index + 1}" })
                            .put("label", label.take(600))
                            .put("status", STATUS_PENDING)
                    )
                }
            }

            if (result.length() == 0) {
                result.put(
                    JSONObject()
                        .put("id", "n1")
                        .put("label", goal.take(600).ifBlank { "goal" })
                        .put("status", STATUS_PENDING)
                )
            }

            return result
        }
    }
}
