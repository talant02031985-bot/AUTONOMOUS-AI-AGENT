package kg.autonomous.agent

import java.net.HttpURLConnection
import java.util.UUID

/**
 * AYANA Execution Kernel v1.2.1 â€” side-effect reconciliation + terminal truth.
 *
 * Core invariant:
 * Once an irreversible/state-changing dispatch has crossed its atomic dispatch
 * boundary, user cancellation may request STOP but may not produce semantic
 * CANCELLED until the executor reconciles the real-world outcome.
 *
 * This is deliberately framework-agnostic so Android, artifact and future
 * integration executors can share the same ownership contract.
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

    enum class SideEffectState {
        NONE,
        PREPARING,
        DISPATCHING,
        DISPATCHED,
        RECONCILING,
        VERIFIED_COMMITTED,
        VERIFIED_NOT_COMMITTED
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
        val sideEffectState: SideEffectState,
        val sideEffectKind: String,
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
        var sideEffectState: SideEffectState = SideEffectState.NONE,
        var sideEffectKind: String = "",
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

    /** Marks that a state-changing operation is being prepared, but no
     * irreversible platform dispatch has happened yet. STOP still owns the
     * outcome in this state. */
    fun beginSideEffect(
        kind: String,
        detail: String = ""
    ): Boolean = synchronized(lock) {
        val session = active ?: return@synchronized false
        if (session.terminalStatus != TerminalStatus.RUNNING) {
            return@synchronized false
        }
        if (session.cancelled) {
            return@synchronized false
        }

        session.sideEffectKind = kind.trim().take(96)
        session.sideEffectState = SideEffectState.PREPARING
        session.phase = "side_effect_preparing"
        addEvidenceLocked(
            session,
            Evidence(
                type = "side_effect_preparing",
                source = "execution_kernel",
                detail = detail.trim().ifBlank { session.sideEffectKind }
                    .take(MAX_EVIDENCE_DETAIL_CHARS),
                confidence = 100
            )
        )
        true
    }

    /**
     * Atomic point-of-no-return gate.
     *
     * The executor MUST call this immediately before invoking the irreversible
     * Android/API action while STOP uses the same kernel lock via requestCancel().
     * Therefore exactly one side wins:
     * - STOP first -> false, dispatch must not happen;
     * - dispatch gate first -> true, later STOP is deferred to reconciliation.
     */
    fun tryBeginIrreversibleDispatch(
        kind: String,
        detail: String = ""
    ): Boolean = synchronized(lock) {
        val session = active ?: return@synchronized false
        if (session.terminalStatus != TerminalStatus.RUNNING) {
            return@synchronized false
        }
        if (session.cancelled) {
            addEvidenceLocked(
                session,
                Evidence(
                    type = "side_effect_dispatch_rejected",
                    source = "execution_kernel",
                    detail = "cancel_already_requested",
                    confidence = 100
                )
            )
            return@synchronized false
        }

        session.sideEffectKind = kind.trim().take(96)
        session.sideEffectState = SideEffectState.DISPATCHING
        session.phase = "side_effect_dispatching"
        addEvidenceLocked(
            session,
            Evidence(
                type = "side_effect_dispatch_boundary",
                source = "execution_kernel",
                detail = detail.trim().ifBlank { session.sideEffectKind }
                    .take(MAX_EVIDENCE_DETAIL_CHARS),
                confidence = 100
            )
        )
        true
    }

    /** Call immediately after the platform accepted the irreversible action. */
    fun markIrreversibleDispatchAccepted(
        detail: String = ""
    ): Boolean = synchronized(lock) {
        val session = active ?: return@synchronized false
        if (session.terminalStatus != TerminalStatus.RUNNING) {
            return@synchronized false
        }
        if (
            session.sideEffectState != SideEffectState.DISPATCHING &&
            session.sideEffectState != SideEffectState.DISPATCHED
        ) {
            return@synchronized false
        }

        session.sideEffectState = SideEffectState.DISPATCHED
        session.phase = "side_effect_dispatched"
        addEvidenceLocked(
            session,
            Evidence(
                type = "side_effect_dispatched",
                source = "execution_kernel",
                detail = detail.trim().ifBlank { session.sideEffectKind }
                    .take(MAX_EVIDENCE_DETAIL_CHARS),
                confidence = 100
            )
        )
        true
    }

    /** Enter bounded post-dispatch observation. User STOP must not interrupt
     * this verification path. */
    fun markSideEffectReconciliationStarted(
        detail: String = ""
    ): Boolean = synchronized(lock) {
        val session = active ?: return@synchronized false
        if (session.terminalStatus != TerminalStatus.RUNNING) {
            return@synchronized false
        }
        if (!requiresReconciliationLocked(session)) {
            return@synchronized false
        }

        session.sideEffectState = SideEffectState.RECONCILING
        session.phase =
            if (session.cancelled) {
                "side_effect_reconciling_after_cancel"
            } else {
                "side_effect_reconciling"
            }
        addEvidenceLocked(
            session,
            Evidence(
                type = "side_effect_reconciliation_started",
                source = "execution_kernel",
                detail = detail.trim().ifBlank { session.sideEffectKind }
                    .take(MAX_EVIDENCE_DETAIL_CHARS),
                confidence = 100
            )
        )
        true
    }

    /**
     * Records factual outcome after a dispatch. committed=true means the real
     * side effect is proven. committed=false means bounded verification proves
     * it did not take effect. Unknown outcomes should not call this method and
     * should finish ERROR with dispatch metadata preserved.
     */
    fun markSideEffectReconciled(
        committed: Boolean,
        detail: String = ""
    ): Boolean = synchronized(lock) {
        val session = active ?: return@synchronized false
        if (session.terminalStatus != TerminalStatus.RUNNING) {
            return@synchronized false
        }
        if (!requiresReconciliationLocked(session)) {
            return@synchronized false
        }

        session.sideEffectState =
            if (committed) {
                SideEffectState.VERIFIED_COMMITTED
            } else {
                SideEffectState.VERIFIED_NOT_COMMITTED
            }
        session.phase =
            if (committed) {
                "side_effect_verified_committed"
            } else {
                "side_effect_verified_not_committed"
            }
        addEvidenceLocked(
            session,
            Evidence(
                type =
                    if (committed) {
                        "side_effect_verified_committed"
                    } else {
                        "side_effect_verified_not_committed"
                    },
                source = "execution_kernel",
                detail = detail.trim().ifBlank { session.sideEffectKind }
                    .take(MAX_EVIDENCE_DETAIL_CHARS),
                confidence = 100
            )
        )
        true
    }

    /** True after the point-of-no-return until factual reconciliation exists. */
    fun requiresSideEffectReconciliation(): Boolean = synchronized(lock) {
        active?.let(::requiresReconciliationLocked) ?: false
    }

    fun sideEffectState(): SideEffectState = synchronized(lock) {
        active?.sideEffectState ?: SideEffectState.NONE
    }

    /**
     * Requests cancellation. If an irreversible dispatch is in flight, bound
     * execution resources are intentionally left alive so the executor can
     * reconcile terminal truth. Otherwise cancellation remains interruptive.
     */
    fun requestCancel(reason: String): SessionSnapshot? = synchronized(lock) {
        val session = active ?: return@synchronized null
        if (session.terminalStatus != TerminalStatus.RUNNING) {
            return@synchronized snapshotLocked(session)
        }

        session.cancelled = true
        session.reason = reason.trim().take(MAX_REASON_CHARS)

        if (protectsTerminalTruthLocked(session)) {
            session.phase =
                if (requiresReconciliationLocked(session)) {
                    "side_effect_reconciling_after_cancel"
                } else {
                    "side_effect_committed_cancel_deferred"
                }
            addEvidenceLocked(
                session,
                Evidence(
                    type =
                        if (requiresReconciliationLocked(session)) {
                            "cancel_deferred_for_reconciliation"
                        } else {
                            "cancel_deferred_after_verified_commit"
                        },
                    source = "execution_kernel",
                    detail = session.reason,
                    confidence = 100
                )
            )
            // Do NOT interrupt the worker/connection here. After dispatch it
            // owns factual reconciliation; after VERIFIED_COMMITTED it owns the
            // short handoff to semantic terminal so STOP cannot rewrite truth.
        } else {
            session.phase = "cancelling"
            cancelResourcesLocked(session)
        }

        snapshotLocked(session)
    }

    /**
     * Immediate cancellation for lanes with no irreversible side effect.
     * If called after the point-of-no-return, it automatically degrades to a
     * deferred cancel request instead of lying about terminal state.
     */
    fun cancel(reason: String): SessionSnapshot? = synchronized(lock) {
        val session = active ?: return@synchronized null
        if (session.terminalStatus != TerminalStatus.RUNNING) {
            return@synchronized snapshotLocked(session)
        }

        if (protectsTerminalTruthLocked(session)) {
            session.cancelled = true
            session.reason = reason.trim().take(MAX_REASON_CHARS)
            session.phase =
                if (requiresReconciliationLocked(session)) {
                    "side_effect_reconciling_after_cancel"
                } else {
                    "side_effect_committed_cancel_deferred"
                }
            addEvidenceLocked(
                session,
                Evidence(
                    type =
                        if (requiresReconciliationLocked(session)) {
                            "cancel_terminal_deferred_for_reconciliation"
                        } else {
                            "cancel_terminal_deferred_after_verified_commit"
                        },
                    source = "execution_kernel",
                    detail = session.reason,
                    confidence = 100
                )
            )
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

        // Hard terminal-truth invariant: semantic CANCELLED is impossible
        // after the point-of-no-return until reconciliation, and remains
        // impossible after a side effect has been VERIFIED_COMMITTED even if
        // semantic terminal handoff has not happened yet.
        if (
            status == TerminalStatus.CANCELLED &&
            protectsTerminalTruthLocked(session)
        ) {
            session.cancelled = true
            session.phase =
                if (requiresReconciliationLocked(session)) {
                    "side_effect_reconciliation_required"
                } else {
                    "side_effect_committed_terminal_required"
                }
            session.reason = reason.trim().take(MAX_REASON_CHARS)
            addEvidenceLocked(
                session,
                Evidence(
                    type = "cancel_terminal_rejected_after_dispatch",
                    source = "execution_kernel",
                    detail = session.reason.ifBlank { session.sideEffectKind },
                    confidence = 100
                )
            )
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
            append("; side_effect_state=")
            append(session.sideEffectState.name)
            if (session.sideEffectKind.isNotBlank()) {
                append("; side_effect_kind=")
                append(session.sideEffectKind)
            }
            append("; evidence_count=")
            append(session.evidence.size)
            if (session.reason.isNotBlank()) {
                append("; reason=")
                append(session.reason)
            }
        }.take(1400)
    }

    private fun requiresReconciliationLocked(session: MutableSession): Boolean =
        session.sideEffectState == SideEffectState.DISPATCHING ||
            session.sideEffectState == SideEffectState.DISPATCHED ||
            session.sideEffectState == SideEffectState.RECONCILING

    private fun protectsTerminalTruthLocked(session: MutableSession): Boolean =
        requiresReconciliationLocked(session) ||
            session.sideEffectState == SideEffectState.VERIFIED_COMMITTED

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
            sideEffectState = session.sideEffectState,
            sideEffectKind = session.sideEffectKind,
            evidence = session.evidence.toList()
        )

    companion object {
        private const val MAX_OBJECTIVE_CHARS = 1200
        private const val MAX_REASON_CHARS = 600
        private const val MAX_EVIDENCE_DETAIL_CHARS = 900
        private const val MAX_EVIDENCE_ITEMS = 28
    }
}
