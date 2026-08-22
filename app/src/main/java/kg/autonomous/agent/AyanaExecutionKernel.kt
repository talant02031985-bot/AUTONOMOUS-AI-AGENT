package kg.autonomous.agent

import java.net.HttpURLConnection
import java.util.UUID

/**
 * AYANA Execution Kernel v1.1 â€” unified execution/cancellation/terminal contract.
 *
 * Design goals:
 * - every long-running lane is represented by one execution session;
 * - STOP is lane-agnostic: Agent Core, multimodal, Android executor and future
 *   integrations can bind their thread/connection to the same session;
 * - cancellation request and semantic terminal acknowledgement are distinct;
 * - terminal state is explicit and fail-closed;
 * - evidence is bounded and structured.
 *
 * This class deliberately has no Android framework dependency.
 */
class AyanaExecutionKernel {

    enum class TerminalStatus {
        RUNNING,
        SUCCESS,
        BLOCKED,
        UNSUPPORTED,
        CANCELLED,
        ERROR
    }

    data class Evidence(
        val type: String,
        val source: String,
        val detail: String,
        val confidence: Int,
        val atMs: Long = System.currentTimeMillis()
    )

    data class SessionSnapshot(
        val id: String,
        val objective: String,
        val source: String,
        val lane: String,
        val executor: String,
        val startedAtMs: Long,
        val phase: String,
        val terminalStatus: TerminalStatus,
        val reason: String,
        val cancelled: Boolean,
        val evidence: List<Evidence>
    )

    private data class MutableSession(
        val id: String,
        val objective: String,
        val source: String,
        val lane: String,
        var executor: String,
        val startedAtMs: Long,
        var phase: String = "created",
        var terminalStatus: TerminalStatus = TerminalStatus.RUNNING,
        var reason: String = "",
        var cancelled: Boolean = false,
        var thread: Thread? = null,
        var connection: HttpURLConnection? = null,
        val evidence: MutableList<Evidence> = mutableListOf()
    )

    private val lock = Any()
    private var active: MutableSession? = null

    fun begin(
        objective: String,
        source: String,
        lane: String,
        executor: String = lane
    ): SessionSnapshot = synchronized(lock) {
        active?.let { previous ->
            if (previous.terminalStatus == TerminalStatus.RUNNING) {
                previous.terminalStatus = TerminalStatus.ERROR
                previous.reason = "superseded_by_new_execution"
                cancelResourcesLocked(previous)
            }
        }

        val session = MutableSession(
            id = "exec-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
            objective = objective.trim().take(MAX_OBJECTIVE_CHARS),
            source = source.trim().take(32),
            lane = lane.trim().take(64),
            executor = executor.trim().take(96),
            startedAtMs = System.currentTimeMillis()
        )
        active = session
        snapshotLocked(session)
    }

    fun setPhase(phase: String) = synchronized(lock) {
        active?.takeIf { it.terminalStatus == TerminalStatus.RUNNING }?.phase =
            phase.trim().take(64)
    }

    fun setExecutor(executor: String) = synchronized(lock) {
        active?.takeIf { it.terminalStatus == TerminalStatus.RUNNING }?.let {
            it.executor = executor.trim().take(96)
            addEvidenceLocked(
                it,
                Evidence(
                    type = "executor_selected",
                    source = "execution_kernel",
                    detail = executor.trim().take(MAX_EVIDENCE_DETAIL_CHARS),
                    confidence = 100
                )
            )
        }
    }

    fun bindThread(thread: Thread?) = synchronized(lock) {
        active?.takeIf { it.terminalStatus == TerminalStatus.RUNNING }?.thread = thread
    }

    fun bindConnection(connection: HttpURLConnection?) = synchronized(lock) {
        active?.takeIf { it.terminalStatus == TerminalStatus.RUNNING }?.connection = connection
    }

    fun clearConnection(connection: HttpURLConnection?) = synchronized(lock) {
        active?.let { session ->
            if (session.connection === connection) {
                session.connection = null
            }
        }
    }

    fun addEvidence(
        type: String,
        source: String,
        detail: String,
        confidence: Int = 100
    ) = synchronized(lock) {
        active?.takeIf { it.terminalStatus == TerminalStatus.RUNNING }?.let { session ->
            addEvidenceLocked(
                session,
                Evidence(
                    type = type.trim().take(64),
                    source = source.trim().take(64),
                    detail = detail.trim().take(MAX_EVIDENCE_DETAIL_CHARS),
                    confidence = confidence.coerceIn(0, 100)
                )
            )
        }
    }

    /**
     * Requests cancellation and releases bound resources, but intentionally
     * keeps the semantic terminal RUNNING until the executor acknowledges the
     * actual outcome. This prevents a late STOP from rewriting an already
     * committed state-changing action as CANCELLED.
     */
    fun requestCancel(reason: String): SessionSnapshot? = synchronized(lock) {
        val session = active ?: return@synchronized null
        if (session.terminalStatus != TerminalStatus.RUNNING) {
            return@synchronized snapshotLocked(session)
        }
        session.cancelled = true
        session.phase = "cancelling"
        session.reason = reason.trim().take(MAX_REASON_CHARS)
        cancelResourcesLocked(session)
        snapshotLocked(session)
    }

    /**
     * Immediate cancellation terminal for lanes whose cancellation itself is
     * authoritative (for example network/model work with no committed side
     * effect to reconcile).
     */
    fun cancel(reason: String): SessionSnapshot? = synchronized(lock) {
        val session = active ?: return@synchronized null
        if (session.terminalStatus != TerminalStatus.RUNNING) {
            return@synchronized snapshotLocked(session)
        }
        session.cancelled = true
        session.phase = "cancelling"
        session.terminalStatus = TerminalStatus.CANCELLED
        session.reason = reason.trim().take(MAX_REASON_CHARS)
        cancelResourcesLocked(session)
        snapshotLocked(session)
    }

    fun complete(
        status: TerminalStatus,
        reason: String = ""
    ): SessionSnapshot? = synchronized(lock) {
        val session = active ?: return@synchronized null
        if (session.terminalStatus != TerminalStatus.RUNNING) {
            return@synchronized snapshotLocked(session)
        }
        session.terminalStatus = status
        session.phase = "terminal"
        session.reason = reason.trim().take(MAX_REASON_CHARS)
        clearResourcesLocked(session)
        snapshotLocked(session)
    }

    fun isCancelled(): Boolean = synchronized(lock) {
        active?.let { it.cancelled || it.terminalStatus == TerminalStatus.CANCELLED } ?: false
    }

    fun current(): SessionSnapshot? = synchronized(lock) {
        active?.let(::snapshotLocked)
    }

    fun diagnosticSummary(): String = synchronized(lock) {
        val session = active ?: return@synchronized "execution_session=none"
        buildString {
            append("execution_id=")
            append(session.id)
            append("; lane=")
            append(session.lane)
            append("; executor=")
            append(session.executor)
            append("; phase=")
            append(session.phase)
            append("; terminal=")
            append(session.terminalStatus.name)
            append("; cancelled=")
            append(session.cancelled)
            append("; evidence_count=")
            append(session.evidence.size)
            if (session.reason.isNotBlank()) {
                append("; reason=")
                append(session.reason)
            }
        }.take(1200)
    }

    private fun addEvidenceLocked(session: MutableSession, evidence: Evidence) {
        if (session.evidence.size >= MAX_EVIDENCE_ITEMS) {
            session.evidence.removeAt(0)
        }
        session.evidence.add(evidence)
    }

    private fun cancelResourcesLocked(session: MutableSession) {
        try {
            session.connection?.disconnect()
        } catch (_: Exception) {
        }
        session.connection = null

        val thread = session.thread
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.interrupt()
            } catch (_: Exception) {
            }
        }
        session.thread = null
    }

    private fun clearResourcesLocked(session: MutableSession) {
        session.connection = null
        session.thread = null
    }

    private fun snapshotLocked(session: MutableSession): SessionSnapshot =
        SessionSnapshot(
            id = session.id,
            objective = session.objective,
            source = session.source,
            lane = session.lane,
            executor = session.executor,
            startedAtMs = session.startedAtMs,
            phase = session.phase,
            terminalStatus = session.terminalStatus,
            reason = session.reason,
            cancelled = session.cancelled,
            evidence = session.evidence.toList()
        )

    companion object {
        private const val MAX_OBJECTIVE_CHARS = 1200
        private const val MAX_REASON_CHARS = 600
        private const val MAX_EVIDENCE_DETAIL_CHARS = 900
        private const val MAX_EVIDENCE_ITEMS = 24
    }
}
