package kg.autonomous.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.max

class AgentAccessibilityService :
    AccessibilityService() {

    // AYANA Accessibility v7.0 — VERIFIED FOREGROUND OWNER HANDOFF + STICKY OWNER EVIDENCE CONTINUITY.
    // v7.0 preserves v6.9 generic ownership/evidence guards and adds one explicit truth handoff:
    // when an upper execution layer has already VERIFIED a package-owned external surface from
    // same-window Accessibility/semantic evidence, it can commit that proven package as the
    // foreground owner. This is not an intent guess and does not fabricate content; it prevents
    // Samsung launcher/SystemUI shell windows from becoming primary between two verified steps of
    // the same Settings transaction. A later genuine foreground ownership event still supersedes it.
    // v6.9 preserves v6.8 sticky foreground-owner truth and closes the remaining Samsung
    // window-list gap seen after a verified Settings transition: One UI can keep the real
    // Settings screen physically foreground while getWindows() temporarily exposes only
    // launcher/system shells. Foreground ownership is now updated only from a proven
    // active/focused TYPE_APPLICATION window or a substantial same-package event source;
    // transient launcher/SystemUI shell events cannot steal an established app owner. Fresh
    // Accessibility evidence from the sticky external owner may survive a temporary live-
    // window omission and is ranked active only while fresh and non-contradicted. No content
    // or package is fabricated without same-package Accessibility evidence.
    // v6.8 preserves v6.7 native Settings text-query recovery and fixes a cross-app
    // ownership lease defect exposed by long deterministic Settings chains. A verified
    // external foreground owner is now state, not a short timer: it vetoes AYANA's
    // in-process View bridge until a later high-confidence foreground-ownership event
    // supersedes it. This prevents a physically open Settings/Permissions screen from
    // reverting to own_app_main_activity merely because a 6.5 s lease expired.
    // v6.7 preserves v6.6/v6.5 truth and adds a bounded native Accessibility text-query
    // recovery for sparse Samsung Settings detail panes. When the serialized snapshot
    // omits a visibly rendered row, upper layers may request an exact semantic label and
    // this service queries only the current interaction window roots through Android's
    // own findAccessibilityNodeInfosByText API, requires an exact visible/enabled match,
    // fails closed on ambiguity, and returns success only after a verified screen change.
    // v6.6 preserves the device-confirmed v6.5 cross-app foreground-owner fix and closes
    // the remaining Samsung Settings gap where a fresh but shallow App Info action cluster
    // (Open / Disable / Force stop) incorrectly suppressed the bounded descendant prefetch
    // even while visible rows such as Permissions were absent from Accessibility evidence.
    // v6.5 preserves v6.3/v6.2/v6.1/v6.0 execution truth and hardens the own-app
    // bridge for Android large-screen multi-resume. A merely RESUMED AYANA Activity can
    // no longer preempt a focused external application window during terminal verification.
    // v6.3 added a same-process semantic bridge for AYANA MainActivity. On this
    // Samsung tablet Android may expose AYANA's own native UI as a sparse Accessibility
    // shell even when dozens of TextViews/EditTexts are visibly rendered. For the own app
    // only, v6.3 consumes a factual View-hierarchy snapshot from MainActivity and routes
    // resolver-confirmed click/text actions back to those exact live Views. External apps
    // still use Accessibility exclusively; Orb/overlay windows remain excluded. No
    // coordinate-only target is invented and v12.3 Recents terminal truth is unchanged.
    // v6.0 adds verified
    // viewport scroll fallback while retaining the device-proven Samsung One UI identity path:
    // a visible One UI Home :id/taskview whose contentDescription exactly equals the app label is the task card.
    // This path uses that concrete card bounds for dismissal and still verifies that the
    // same semantic task identity disappears before SUCCESS. Unknown identity fails closed.
    // It never uses “Close all” and never equates Home/Back with close. The v5.3 same-window
    // Settings isolation contract remains unchanged.
    // Every visible Android window is an independent context. Normal accessibility
    // events stay lightweight. Samsung/Android Settings is the one bounded exception:
    // sparse Settings roots can hide visible App Info / Notifications / Permissions
    // content from normal window snapshots, so v5.1 captures a throttled descendant
    // tree only for fresh structural com.android.settings events. Fresh event evidence
    // is still merged only into the matching window.
    // Ordinary semantic cross-window clicking stays fail-closed in split-screen,
    // freeform, PiP, popup/dialog, Recents and overlay scenarios. The only Recents
    // exception is the dedicated v5.4 task-removal routine above, with its own
    // strict label identity, topology guards and post-action verification.

    data class NodeMatch(
        val node: AccessibilityNodeInfo,
        val score: Int
    )

    private data class WindowContext(
        val contextId: String,
        val windowId: Int,
        val packageName: String,
        val className: String,
        val type: Int,
        val layer: Int,
        val active: Boolean,
        val focused: Boolean,
        val pictureInPicture: Boolean,
        val bounds: Rect,
        val root: AccessibilityNodeInfo?,
        val source: String,
        val rank: Int,
        val title: String = ""
    )

    private data class ContextNodeMatch(
        val context: WindowContext,
        val match: NodeMatch,
        val totalScore: Int
    )

    private data class EventTapTarget(
        val bounds: Rect,
        val windowId: Int,
        val packageName: String
    )

    private data class EventEvidence(
        val at: Long,
        val windowId: Int,
        val packageName: String,
        val className: String,
        val eventType: Int,
        val bounds: Rect,
        val nodes: JSONArray,
        val visibleText: List<String>,
        val origin: String
    )

    /**
     * O(1) metadata for the newest Android scroll event. This is deliberately
     * separate from EventEvidence: a Samsung sparse Settings root may emit a
     * valid TYPE_VIEW_SCROLLED event even when event.source has little or no
     * readable text. Scroll truth must therefore not depend on text capture.
     */
    private data class ScrollEventState(
        val sequence: Long,
        val atElapsed: Long,
        val windowId: Int,
        val packageName: String,
        val deltaX: Int,
        val deltaY: Int,
        val scrollX: Int,
        val scrollY: Int,
        val fromIndex: Int,
        val toIndex: Int,
        val itemCount: Int
    )

    private data class ScrollProgressEvidence(
        val verified: Boolean,
        val signatureChanged: Boolean,
        val viewportChanged: Boolean,
        val scrollEventObserved: Boolean,
        val proofLevel: String
    )

    private data class RootSemanticProbe(
        val nodeCount: Int,
        val readableTextCount: Int,
        val editableCount: Int,
        val clickableCount: Int
    )

    private data class RecentTaskCandidate(
        val labelNode: AccessibilityNodeInfo,
        val actionNode: AccessibilityNodeInfo?,
        val cardNode: AccessibilityNodeInfo,
        val bounds: Rect,
        val score: Int
    )

    private enum class RecentTaskReconciliation {
        REMOVED,
        STILL_PRESENT,
        UNKNOWN
    }

    private data class RecentsStructuralState(
        val fingerprint: String,
        val overviewChildCount: Int,
        val dismissableCount: Int,
        val strongContextCount: Int
    )

    private val eventEvidenceLock =
        Any()

    private val recentEventEvidence =
        ArrayDeque<EventEvidence>()

    @Volatile
    private var lastSettingsStructuralRecoveryAt =
        0L

    @Volatile
    private var lastOwnAppStructuralRecoveryAt =
        0L

    @Volatile
    private var lastSettingsSnapshotRecoveryAt =
        0L

    @Volatile
    private var lastForegroundSnapshotRecoveryAt =
        0L

    @Volatile
    private var lastForegroundSnapshotRecoveryKey =
        ""

    @Volatile
    private var lastScrollEventState:
        ScrollEventState? =
        null

    // AccessibilityService callbacks run on the process main thread. v4.0/v4.1
    // accidentally performed descendant-prefetch tree walks from this hot path.
    // v5.1 therefore keeps the normal path O(1). Only structural events from the
    // Android Settings package may take one bounded/throttled recovery snapshot.
    // This restores truth for Samsung sparse Settings roots without reintroducing
    // continuous descendant walks, Orb lag, or thermal/CPU regressions.

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Samsung One UI may temporarily return rootInActiveWindow == null
        // during Settings transitions / multi-window. Ask Android for the full
        // interactive-window list so we can safely fall back to the real app.
        try {

            val info =
                serviceInfo

            info.flags =
                info.flags or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS

            serviceInfo =
                info

        } catch (_: Exception) {
        }

        instance =
            this
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {

        if (event == null) {
            return
        }

        val eventPackage =
            event.packageName
                ?.toString()
                .orEmpty()

        // AYANA owns both the real MainActivity window and floating overlay surfaces.
        // Never drop the whole package: that made AYANA blind to its own UI. Admit only
        // events proven to belong to the live foreground TYPE_APPLICATION window; keep
        // Orb/overlay/background self-events out so they cannot steal foreground recency.
        if (
            eventPackage == packageName &&
            !isOwnForegroundApplicationEvent(event)
        ) {
            pruneEventEvidence()
            return
        }

        // v6.8: keep a separate, high-confidence interaction-owner signal.
        // Generic lastEventPackage is intentionally NOT used for foreground truth because
        // notification/background events from another package can arrive while AYANA owns
        // the screen. Window-state/windows/focus events are ownership transitions: the
        // newest such event supersedes the previous owner and remains authoritative until
        // another high-confidence ownership event replaces it. Ownership is not a lease.
        if (
            eventPackage.isNotBlank() &&
            shouldAcceptForegroundOwnershipEvent(
                event = event,
                eventPackage = eventPackage
            )
        ) {
            lastForegroundOwnerPackage =
                eventPackage

            lastForegroundOwnerTime =
                System.currentTimeMillis()

            lastForegroundOwnerWindowId =
                try {
                    event.windowId
                } catch (_: Exception) {
                    -1
                }

            lastForegroundOwnerSource =
                "accessibility_event"
        }

        lastEventPackage =
            eventPackage

        lastEventClass =
            event.className
                ?.toString()
                .orEmpty()

        lastEventTime =
            System.currentTimeMillis()

        lastEventWindowId =
            try {
                event.windowId
            } catch (_: Exception) {
                -1
            }

        if (
            event.eventType ==
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            val previousSequence =
                lastScrollEventState
                    ?.sequence
                    ?: 0L

            val deltaX =
                if (Build.VERSION.SDK_INT >= 28) {
                    try {
                        event.scrollDeltaX
                    } catch (_: Exception) {
                        0
                    }
                } else {
                    0
                }

            val deltaY =
                if (Build.VERSION.SDK_INT >= 28) {
                    try {
                        event.scrollDeltaY
                    } catch (_: Exception) {
                        0
                    }
                } else {
                    0
                }

            lastScrollEventState =
                ScrollEventState(
                    sequence = previousSequence + 1L,
                    atElapsed = SystemClock.elapsedRealtime(),
                    windowId = lastEventWindowId,
                    packageName = eventPackage,
                    deltaX = deltaX,
                    deltaY = deltaY,
                    scrollX = try { event.scrollX } catch (_: Exception) { -1 },
                    scrollY = try { event.scrollY } catch (_: Exception) { -1 },
                    fromIndex = try { event.fromIndex } catch (_: Exception) { -1 },
                    toIndex = try { event.toIndex } catch (_: Exception) { -1 },
                    itemCount = try { event.itemCount } catch (_: Exception) { -1 }
                )
        }

        // Text editing/selection events are deliberately metadata-only. They can
        // fire for every keystroke (including IME activity) and must never trigger
        // a descendant walk on the process main thread.
        if (
            event.eventType ==
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            event.eventType ==
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        ) {
            pruneEventEvidence()
            return
        }

        captureEventEvidence(
            event
        )
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {

        if (
            instance === this
        ) {
            instance =
                null
        }

        super.onDestroy()
    }

    fun pressBack():
        Boolean {

        return performGlobalAction(
            GLOBAL_ACTION_BACK
        )
    }

    fun pressHome():
        Boolean {

        return performGlobalAction(
            GLOBAL_ACTION_HOME
        )
    }

    fun pressRecents():
        Boolean {

        return performGlobalAction(
            GLOBAL_ACTION_RECENTS
        )
    }

    /**
     * Removes one app task from Android Recents using a strict visible app-label
     * match. This is intentionally task removal, NOT process kill / force-stop.
     * The method scans a bounded Recents carousel, never touches “Close all”, and
     * returns success only after a fresh tree no longer exposes the dismissed card.
     */
    fun removeRecentTaskByLabel(
        targetLabel: String,
        sourcePackage: String = "",
        shouldCancel: () -> Boolean = { false },
        tryBeginIrreversibleDispatch: (String) -> Boolean = { !shouldCancel() },
        onIrreversibleDispatchAccepted: (String) -> Unit = {},
        onReconciliationStarted: (String) -> Unit = {},
        onReconciled: (Boolean, String) -> Unit = { _, _ -> }
    ): JSONObject {

        val normalizedTarget =
            normalize(
                targetLabel
            )

        if (normalizedTarget.isBlank()) {
            return recentTaskResult(
                success = false,
                verified = false,
                terminalStatus = "ERROR",
                reason = "blank_target_label",
                message = "Не указано приложение для удаления из недавних"
            )
        }

        if (shouldCancel()) {
            return recentTaskResult(
                success = false,
                verified = false,
                terminalStatus = "CANCELLED",
                reason = "cancelled_before_recents",
                message = "Удаление задачи отменено",
                verifiedNotCommitted = true
            )
        }

        val before =
            screenSignature()

        if (!pressRecents()) {
            return recentTaskResult(
                success = false,
                verified = false,
                terminalStatus = "UNSUPPORTED",
                reason = "recents_global_action_rejected",
                message = "Android не принял переход в список недавних"
            )
        }

        if (
            !waitForRecentsSurface(
                beforeSignature = before,
                sourcePackage = sourcePackage,
                shouldCancel = shouldCancel
            )
        ) {
            return recentTaskResult(
                success = false,
                verified = false,
                terminalStatus =
                    if (shouldCancel()) {
                        "CANCELLED"
                    } else {
                        "UNSUPPORTED"
                    },
                reason =
                    if (shouldCancel()) {
                        "cancelled_entering_recents"
                    } else {
                        "recents_surface_not_verified"
                    },
                message =
                    if (shouldCancel()) {
                        "Удаление задачи отменено"
                    } else {
                        "Не удалось надёжно подтвердить экран недавних приложений"
                    },
                verifiedNotCommitted = shouldCancel()
            ).put(
                "recents_evidence",
                recentsEvidenceSummary()
            )
        }

        var removedCount =
            0

        var dismissMethod =
            ""

        var scanCount =
            0

        var reachedEnd =
            false

        var acceptedDispatchOutstanding =
            false

        val seenSignatures =
            linkedSetOf<String>()

        // v5.7/v5.8 safety remains unchanged: never infer task identity from
        // position. Only a concrete semantic task-card identity can be dismissed.
        var firstIdentityProbe: JSONObject? =
            null

        while (
            scanCount < RECENTS_MAX_SCAN_STEPS &&
            !shouldCancel()
        ) {
            scanCount++

            val currentSignature =
                screenSignature()

            if (
                currentSignature.isNotBlank() &&
                !seenSignatures.add(
                    currentSignature
                )
            ) {
                reachedEnd =
                    true
                break
            }

            val candidate =
                findRecentTaskCandidate(
                    normalizedTarget
                )

            if (candidate != null) {
                val beforeDismiss =
                    screenSignature()

                val dispatchDetail =
                    "target=${targetLabel.take(120)}; " +
                        "source_package=${sourcePackage.take(180)}; " +
                        "scan=$scanCount"

                // Atomic P0 gate. STOP and destructive dispatch contend in the
                // Execution Kernel under the same lock. If STOP already won,
                // Android must not receive the dismiss request.
                if (
                    !tryBeginIrreversibleDispatch(
                        dispatchDetail
                    )
                ) {
                    return recentTaskResult(
                        success = false,
                        verified = false,
                        terminalStatus = "CANCELLED",
                        reason = "cancelled_before_irreversible_dispatch",
                        message = "Удаление задачи отменено до необратимого действия",
                        removedCount = removedCount,
                        scanCount = scanCount,
                        dismissMethod = dismissMethod,
                        actionDispatched = false,
                        reconciliationComplete = true,
                        verifiedNotCommitted = true
                    )
                }

                val actionResult =
                    dismissRecentTaskCandidate(
                        candidate
                    )

                if (!actionResult.first) {
                    // Android rejected both semantic dismiss and gesture. No
                    // destructive dispatch was accepted, so this is positive
                    // no-commit evidence for the side-effect transaction.
                    onReconciled(
                        false,
                        "dispatch_rejected; $dispatchDetail"
                    )

                    return recentTaskResult(
                        success = false,
                        verified = false,
                        terminalStatus =
                            if (shouldCancel()) {
                                "CANCELLED"
                            } else {
                                "ERROR"
                            },
                        reason =
                            if (shouldCancel()) {
                                "cancelled_after_dispatch_rejected"
                            } else {
                                "task_dismiss_rejected"
                            },
                        message =
                            if (shouldCancel()) {
                                "Удаление задачи отменено; Android не принял destructive dispatch"
                            } else {
                                "Android не принял удаление найденной карточки приложения"
                            },
                        removedCount = removedCount,
                        scanCount = scanCount,
                        dismissMethod = actionResult.second,
                        actionDispatched = false,
                        reconciliationComplete = true,
                        verifiedNotCommitted = true
                    )
                }

                acceptedDispatchOutstanding =
                    true
                dismissMethod =
                    actionResult.second

                onIrreversibleDispatchAccepted(
                    "method=${actionResult.second}; $dispatchDetail"
                )

                onReconciliationStarted(
                    "method=${actionResult.second}; $dispatchDetail"
                )

                when (
                    reconcileRecentTaskAfterAcceptedDismiss(
                        normalizedTarget = normalizedTarget,
                        beforeSignature = beforeDismiss
                    )
                ) {
                    RecentTaskReconciliation.REMOVED -> {
                        removedCount++
                        acceptedDispatchOutstanding =
                            false

                        onReconciled(
                            true,
                            "verified_task_removed; method=${actionResult.second}; $dispatchDetail"
                        )

                        // STOP after accepted dispatch does not erase reality.
                        // It does stop any further duplicate-card destructive work.
                        if (shouldCancel()) {
                            return recentTaskResult(
                                success = true,
                                verified = true,
                                terminalStatus = "SUCCESS",
                                reason = "verified_task_removed",
                                message = "Задача приложения удалена из списка недавних",
                                removedCount = removedCount,
                                scanCount = scanCount,
                                dismissMethod = dismissMethod,
                                actionDispatched = true,
                                reconciliationComplete = true,
                                verifiedNotCommitted = false,
                                cancelAfterDispatch = true
                            )
                        }

                        seenSignatures.clear()
                        continue
                    }

                    RecentTaskReconciliation.STILL_PRESENT -> {
                        acceptedDispatchOutstanding =
                            false

                        onReconciled(
                            false,
                            "verified_task_still_present; method=${actionResult.second}; $dispatchDetail"
                        )

                        return recentTaskResult(
                            success = false,
                            verified = true,
                            terminalStatus =
                                if (shouldCancel()) {
                                    "CANCELLED"
                                } else {
                                    "ERROR"
                                },
                            reason =
                                if (shouldCancel()) {
                                    "cancelled_after_verified_no_effect"
                                } else {
                                    "task_dismiss_verified_no_effect"
                                },
                            message =
                                if (shouldCancel()) {
                                    "Удаление задачи отменено; карточка приложения подтверждённо осталась"
                                } else {
                                    "Android принял команду, но карточка приложения подтверждённо осталась"
                                },
                            removedCount = removedCount,
                            scanCount = scanCount,
                            dismissMethod = dismissMethod,
                            actionDispatched = true,
                            reconciliationComplete = true,
                            verifiedNotCommitted = true,
                            cancelAfterDispatch = shouldCancel()
                        )
                    }

                    RecentTaskReconciliation.UNKNOWN -> {
                        // Accepted destructive dispatch + unknown physical result
                        // must never be translated to CANCELLED. Keep fail-closed
                        // terminal truth even when STOP is pending.
                        return recentTaskResult(
                            success = false,
                            verified = false,
                            terminalStatus = "ERROR",
                            reason = "dispatch_outcome_unverified",
                            message = "Android принял удаление, но фактический результат не удалось надёжно подтвердить",
                            removedCount = removedCount,
                            scanCount = scanCount,
                            dismissMethod = dismissMethod,
                            actionDispatched = true,
                            reconciliationComplete = false,
                            verifiedNotCommitted = false,
                            cancelAfterDispatch = shouldCancel()
                        )
                    }
                }
            }

            if (
                candidate == null &&
                firstIdentityProbe == null
            ) {
                firstIdentityProbe =
                    buildRecentsTaskIdentityProbe(
                        normalizedTarget
                    )
            }

            val moved =
                moveRecentsForward()

            if (!moved) {
                reachedEnd =
                    true
                break
            }
        }

        if (shouldCancel()) {
            // This branch is reachable only when no destructive dispatch is
            // unresolved. If at least one card was already verified removed,
            // preserve factual SUCCESS instead of rewriting it to CANCELLED.
            if (removedCount > 0) {
                return recentTaskResult(
                    success = true,
                    verified = true,
                    terminalStatus = "SUCCESS",
                    reason = "verified_task_removed",
                    message = "Задача приложения удалена из списка недавних",
                    removedCount = removedCount,
                    scanCount = scanCount,
                    dismissMethod = dismissMethod,
                    actionDispatched = true,
                    reconciliationComplete = true,
                    cancelAfterDispatch = true
                )
            }

            if (acceptedDispatchOutstanding) {
                return recentTaskResult(
                    success = false,
                    verified = false,
                    terminalStatus = "ERROR",
                    reason = "dispatch_outcome_unverified",
                    message = "Destructive dispatch принят, но фактический результат не подтверждён",
                    removedCount = removedCount,
                    scanCount = scanCount,
                    dismissMethod = dismissMethod,
                    actionDispatched = true,
                    reconciliationComplete = false,
                    cancelAfterDispatch = true
                )
            }

            return recentTaskResult(
                success = false,
                verified = false,
                terminalStatus = "CANCELLED",
                reason = "cancelled_during_recents_scan",
                message = "Удаление задачи отменено",
                removedCount = removedCount,
                scanCount = scanCount,
                dismissMethod = dismissMethod,
                actionDispatched = false,
                reconciliationComplete = true,
                verifiedNotCommitted = true
            )
        }

        if (!reachedEnd) {
            return recentTaskResult(
                success = false,
                verified = false,
                terminalStatus = "ERROR",
                reason = "recents_scan_budget_exhausted",
                message = "Не удалось полностью проверить список недавних в допустимом лимите",
                removedCount = removedCount,
                scanCount = scanCount,
                dismissMethod = dismissMethod,
                actionDispatched = removedCount > 0,
                reconciliationComplete = true
            )
        }

        if (removedCount <= 0) {
            return recentTaskResult(
                success = false,
                verified = false,
                terminalStatus = "ERROR",
                reason = "target_task_not_found_unverified",
                message = "Карточка приложения не найдена; отсутствие задачи во всём списке недавних не доказано",
                removedCount = removedCount,
                scanCount = scanCount,
                dismissMethod = dismissMethod
            ).put(
                "recents_evidence",
                recentsEvidenceSummary()
            ).put(
                "task_identity_probe",
                firstIdentityProbe
                    ?: buildRecentsTaskIdentityProbe(
                        normalizedTarget
                    )
            )
        }

        return recentTaskResult(
            success = true,
            verified = true,
            terminalStatus = "SUCCESS",
            reason = "verified_task_removed",
            message = "Задача приложения удалена из списка недавних",
            removedCount = removedCount,
            scanCount = scanCount,
            dismissMethod = dismissMethod,
            actionDispatched = true,
            reconciliationComplete = true,
            verifiedNotCommitted = false
        )
    }

    private fun waitForRecentsSurface(
        beforeSignature: String,
        sourcePackage: String,
        shouldCancel: () -> Boolean
    ): Boolean {

        val deadline =
            SystemClock.elapsedRealtime() +
                RECENTS_ENTER_TIMEOUT_MS

        do {
            if (shouldCancel()) {
                return false
            }

            val changed =
                screenSignature() !=
                    beforeSignature

            if (
                changed &&
                isLikelyRecentsSurface(
                    sourcePackage
                )
            ) {
                return true
            }

            try {
                Thread.sleep(
                    RECENTS_VERIFY_POLL_MS
                )
            } catch (_: InterruptedException) {
                Thread.currentThread()
                    .interrupt()
                return false
            }
        } while (
            SystemClock.elapsedRealtime() <
            deadline
        )

        return false
    }

    private fun isLikelyRecentsSurface(
        sourcePackage: String
    ): Boolean {

        val contexts =
            resolveWindowContexts()

        if (contexts.isEmpty()) {
            return false
        }

        val normalizedSource =
            sourcePackage.trim()

        return contexts.any { context ->

            val freshEventClass =
                if (
                    lastEventPackage ==
                        context.packageName &&
                    System.currentTimeMillis() -
                        lastEventTime <=
                        RECENTS_EVENT_CLASS_FRESH_MS
                ) {
                    lastEventClass
                } else {
                    ""
                }

            val normalizedClass =
                normalize(
                    context.className +
                        " " +
                        context.title +
                        " " +
                        freshEventClass
                )

            val explicitRecentsClass =
                listOf(
                    "recentsactivity",
                    "recents",
                    "overview",
                    "recent apps",
                    "recentapp",
                    "taskview",
                    "quickstep"
                ).any { marker ->
                    normalizedClass.contains(
                        marker
                    )
                }

            val strongHost =
                isKnownRecentsHostPackage(
                    context.packageName
                )

            if (
                explicitRecentsClass &&
                (
                    strongHost ||
                        normalizedSource.isBlank() ||
                        context.packageName !=
                            normalizedSource
                    )
            ) {
                return@any true
            }

            val recentsStructure =
                hasRecentsTaskStructure(
                    context.root
                )

            recentsStructure &&
                (
                    strongHost ||
                        normalizedSource.isBlank() ||
                        context.packageName !=
                            normalizedSource
                    )
        }
    }

    private fun hasRecentsTaskStructure(
        root: AccessibilityNodeInfo?
    ): Boolean {

        if (root == null) {
            return false
        }

        val queue =
            ArrayDeque<AccessibilityNodeInfo>()

        queue.add(
            root
        )

        var visited =
            0

        var dismissableCount =
            0

        var scrollableLargeContainer =
            false

        var recentsMarkerFound =
            false

        var strongRecentsViewIdFound =
            false

        var strongRecentsClassFound =
            false

        val screenArea =
            resources.displayMetrics.widthPixels.toLong() *
                resources.displayMetrics.heightPixels.toLong()

        while (
            queue.isNotEmpty() &&
            visited < RECENTS_STRUCTURE_NODE_LIMIT
        ) {
            val node =
                queue.removeFirst()

            visited++

            val visible =
                try {
                    node.isVisibleToUser
                } catch (_: Exception) {
                    false
                }

            if (
                visible &&
                node.actionList.any { action ->
                    action.id ==
                        AccessibilityNodeInfo.ACTION_DISMISS
                }
            ) {
                dismissableCount++
            }

            val rawViewId =
                node.viewIdResourceName
                    .orEmpty()
                    .lowercase(
                        Locale.ROOT
                    )

            if (
                visible &&
                (
                    rawViewId.endsWith(
                        ":id/overview_panel"
                    ) ||
                        rawViewId.endsWith(
                            ":id/clear_all"
                        ) ||
                        rawViewId.endsWith(
                            ":id/clear_all_button"
                        )
                    )
            ) {
                strongRecentsViewIdFound =
                    true
            }

            val nodeClass =
                normalize(
                    node.className
                        ?.toString()
                        .orEmpty()
                )

            if (
                visible &&
                (
                    nodeClass.contains(
                        "launcherrecentsview"
                    ) ||
                        nodeClass.contains(
                            "recentsview"
                        )
                    )
            ) {
                strongRecentsClassFound =
                    true
            }

            val nodeText =
                normalize(
                    node.text
                        ?.toString()
                        .orEmpty() +
                        " " +
                        node.contentDescription
                            ?.toString()
                            .orEmpty()
                )

            if (
                visible &&
                listOf(
                    "закрыть все",
                    "очистить все",
                    "недавние приложения",
                    "недавно использованные",
                    "close all",
                    "clear all",
                    "recent apps",
                    "recently used"
                ).any { marker ->
                    nodeText.contains(
                        marker
                    )
                }
            ) {
                recentsMarkerFound =
                    true
            }

            if (
                visible &&
                node.isScrollable
            ) {
                val bounds =
                    Rect()

                node.getBoundsInScreen(
                    bounds
                )

                val area =
                    bounds.width().coerceAtLeast(0).toLong() *
                        bounds.height().coerceAtLeast(0).toLong()

                if (
                    screenArea > 0L &&
                    area.toDouble() /
                    screenArea.toDouble() >=
                    0.25
                ) {
                    scrollableLargeContainer =
                        true
                }
            }

            for (
                index in
                0 until node.childCount
            ) {
                childWithPrefetch(
                    node,
                    index
                )?.let { child ->
                    queue.add(
                        child
                    )
                }
            }
        }

        // Samsung One UI exposes the Recents surface through One UI Home. On
        // Galaxy builds the user-visible "Close all" text can be absent from the
        // accessibility text stream while the Recents-only overview_panel id is
        // present. A visible overview panel / RecentsView is therefore strong,
        // OEM-specific evidence, not a generic launcher guess.
        if (
            strongRecentsViewIdFound ||
            strongRecentsClassFound
        ) {
            return true
        }

        return recentsMarkerFound &&
            (
                dismissableCount > 0 ||
                    scrollableLargeContainer
                )
    }

    private fun isKnownRecentsHostPackage(
        packageName: String
    ): Boolean {

        val normalized =
            packageName
                .trim()
                .lowercase(
                    Locale.ROOT
                )

        return normalized ==
            "com.sec.android.app.launcher" ||
            normalized ==
            "com.android.launcher3" ||
            normalized ==
            "com.google.android.apps.nexuslauncher" ||
            normalized ==
            "com.android.systemui"
    }

    private fun isKnownLauncherPackage(
        packageName: String
    ): Boolean {

        val normalized =
            packageName
                .trim()
                .lowercase(
                    Locale.ROOT
                )

        return normalized ==
            "com.sec.android.app.launcher" ||
            normalized ==
            "com.android.launcher3" ||
            normalized ==
            "com.google.android.apps.nexuslauncher"
    }

    private fun isStrongRecentsWindowContext(
        context: WindowContext
    ): Boolean {

        val freshEventClass =
            if (
                lastEventPackage ==
                    context.packageName &&
                System.currentTimeMillis() -
                    lastEventTime <=
                    RECENTS_EVENT_CLASS_FRESH_MS
            ) {
                lastEventClass
            } else {
                ""
            }

        val normalizedClass =
            normalize(
                context.className +
                    " " +
                    context.title +
                    " " +
                    freshEventClass
            )

        if (
            listOf(
                "recentsactivity",
                "recents",
                "overview",
                "quickstep"
            ).any { marker ->
                normalizedClass.contains(
                    marker
                )
            }
        ) {
            return true
        }

        return isKnownRecentsHostPackage(
            context.packageName
        ) &&
            hasRecentsTaskStructure(
                context.root
            )
    }

    private fun recentsWindowContexts(
        contexts: List<WindowContext>
    ): List<WindowContext> {

        if (contexts.isEmpty()) {
            return emptyList()
        }

        val strong =
            contexts
                .filter {
                    isStrongRecentsWindowContext(
                        it
                    )
                }
                .sortedByDescending {
                    it.rank
                }

        if (strong.isNotEmpty()) {
            return strong
        }

        val primary =
            primaryWindowContext(
                contexts
            )

        return if (primary != null) {
            listOf(primary)
        } else {
            emptyList()
        }
    }

    private fun findRecentTaskCandidate(
        normalizedTarget: String
    ): RecentTaskCandidate? {

        val contexts =
            recentsWindowContexts(
                resolveWindowContexts()
            )

        var best:
            RecentTaskCandidate? =
            null

        for (context in contexts) {
            val root =
                context.root
                    ?: continue

            val queue =
                ArrayDeque<AccessibilityNodeInfo>()

            queue.add(
                root
            )

            var visited =
                0

            while (
                queue.isNotEmpty() &&
                visited < RECENTS_CANDIDATE_NODE_LIMIT
            ) {
                val node =
                    queue.removeFirst()

                visited++

                if (
                    node.isVisibleToUser &&
                    node.isEnabled &&
                    isStrictRecentTaskLabel(
                        node,
                        normalizedTarget
                    )
                ) {
                    val directTaskView =
                        isStrongDirectRecentTaskView(
                            node = node,
                            normalizedTarget = normalizedTarget
                        )

                    val dismissable =
                        findActionableParent(
                            node,
                            AccessibilityNodeInfo.ACTION_DISMISS
                        )

                    val card =
                        when {
                            directTaskView ->
                                node

                            dismissable != null ->
                                dismissable

                            else ->
                                findRecentCardAncestor(
                                    node
                                )
                        }

                    if (card != null) {
                        val bounds =
                            Rect()

                        card.getBoundsInScreen(
                            bounds
                        )

                        val plausible =
                            if (directTaskView) {
                                isPlausibleDirectTaskViewBounds(
                                    bounds
                                )
                            } else {
                                isPlausibleRecentTaskCard(
                                    cardBounds = bounds,
                                    labelNode = node
                                )
                            }

                        if (plausible) {
                            val score =
                                (
                                    when {
                                        directTaskView -> 2500
                                        dismissable != null -> 1000
                                        else -> 500
                                    }
                                    ) +
                                    bounds.width().coerceAtLeast(0) +
                                    bounds.height().coerceAtLeast(0)

                            if (
                                best == null ||
                                score > best.score
                            ) {
                                best =
                                    RecentTaskCandidate(
                                        labelNode = node,
                                        // A Samsung :id/taskview identity is already the
                                        // concrete card. Do not climb to an ancestor
                                        // ACTION_DISMISS because OEM containers can expose
                                        // actions that are not scoped to this exact card.
                                        actionNode =
                                            if (directTaskView) {
                                                null
                                            } else {
                                                dismissable
                                            },
                                        cardNode = card,
                                        bounds = bounds,
                                        score = score
                                    )
                            }
                        }
                    }
                }

                for (
                    index in
                    0 until node.childCount
                ) {
                    childWithPrefetch(
                        node,
                        index
                    )?.let { child ->
                        queue.add(
                            child
                        )
                    }
                }
            }
        }

        return best
    }

    private fun isStrictRecentTaskLabel(
        node: AccessibilityNodeInfo,
        normalizedTarget: String
    ): Boolean {

        if (node.isPassword) {
            return false
        }

        val values =
            listOf(
                node.text
                    ?.toString()
                    .orEmpty(),
                node.contentDescription
                    ?.toString()
                    .orEmpty()
            )
                .map { value ->
                    normalize(
                        value
                    )
                }
                .filter { value ->
                    value.isNotBlank()
                }

        return values.any { value ->

            if (
                value == normalizedTarget ||
                value ==
                    "$normalizedTarget приложение" ||
                value ==
                    "$normalizedTarget app" ||
                value ==
                    "приложение $normalizedTarget" ||
                value ==
                    "app $normalizedTarget"
            ) {
                return@any true
            }

            // OEM task switchers can append a short accessibility role to the
            // app label. Arbitrary suffixes remain rejected to avoid confusing
            // snapshot content with the task-card identity.
            if (
                value.startsWith(
                    "$normalizedTarget "
                )
            ) {
                val suffix =
                    value
                        .removePrefix(
                            "$normalizedTarget "
                        )
                        .trim()

                return@any suffix in
                    setOf(
                        "приложение",
                        "app",
                        "кнопка",
                        "button",
                        "значок приложения",
                        "app icon",
                        "сведения о приложении",
                        "app info"
                    )
            }

            false
        }
    }

    private fun isStrongDirectRecentTaskView(
        node: AccessibilityNodeInfo,
        normalizedTarget: String
    ): Boolean {

        if (
            normalizedTarget.isBlank() ||
            !node.isVisibleToUser ||
            !node.isEnabled ||
            !node.isClickable
        ) {
            return false
        }

        val nodePackage =
            node.packageName
                ?.toString()
                .orEmpty()
                .trim()

        if (
            nodePackage !=
            "com.sec.android.app.launcher"
        ) {
            return false
        }

        val viewId =
            node.viewIdResourceName
                .orEmpty()
                .lowercase(
                    Locale.ROOT
                )

        if (
            !viewId.endsWith(
                ":id/taskview"
            )
        ) {
            return false
        }

        val description =
            normalize(
                node.contentDescription
                    ?.toString()
                    .orEmpty()
            )

        // Device evidence from Galaxy Tab S8 / One UI: the task card itself is a
        // clickable FrameLayout with :id/taskview and contentDescription="YouTube".
        // Require an exact canonical app-label match; snapshot text, icon menus and
        // arbitrary suffixes are deliberately not accepted by this direct lane.
        if (
            description !=
            normalizedTarget
        ) {
            return false
        }

        val nodeClass =
            normalize(
                node.className
                    ?.toString()
                    .orEmpty()
            )

        return nodeClass.contains(
            "framelayout"
        )
    }

    private fun isPlausibleDirectTaskViewBounds(
        bounds: Rect
    ): Boolean {

        if (
            bounds.width() <= 1 ||
            bounds.height() <= 1
        ) {
            return false
        }

        val screenWidth =
            resources.displayMetrics.widthPixels
                .coerceAtLeast(1)

        val screenHeight =
            resources.displayMetrics.heightPixels
                .coerceAtLeast(1)

        val widthRatio =
            bounds.width().toDouble() /
                screenWidth.toDouble()

        val heightRatio =
            bounds.height().toDouble() /
                screenHeight.toDouble()

        // Wide enough to be a task preview, but never the full Recents surface.
        return widthRatio in 0.16..0.95 &&
            heightRatio in 0.14..0.95
    }

    private fun findRecentCardAncestor(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        val screenWidth =
            resources.displayMetrics.widthPixels
                .coerceAtLeast(1)

        val screenHeight =
            resources.displayMetrics.heightPixels
                .coerceAtLeast(1)

        var current:
            AccessibilityNodeInfo? =
            node

        var hops =
            0

        var best:
            AccessibilityNodeInfo? =
            null

        var bestArea =
            Long.MAX_VALUE

        while (
            current != null &&
            hops < RECENTS_CARD_PARENT_HOPS
        ) {
            val bounds =
                Rect()

            current.getBoundsInScreen(
                bounds
            )

            val widthRatio =
                bounds.width().coerceAtLeast(0).toDouble() /
                    screenWidth.toDouble()

            val heightRatio =
                bounds.height().coerceAtLeast(0).toDouble() /
                    screenHeight.toDouble()

            if (
                widthRatio in 0.22..0.95 &&
                heightRatio in 0.22..0.95
            ) {
                val area =
                    bounds.width().coerceAtLeast(0).toLong() *
                        bounds.height().coerceAtLeast(0).toLong()

                if (area < bestArea) {
                    best =
                        current
                    bestArea =
                        area
                }
            }

            current =
                current.parent

            hops++
        }

        return best
    }

    private fun isPlausibleRecentTaskCard(
        cardBounds: Rect,
        labelNode: AccessibilityNodeInfo
    ): Boolean {

        if (
            cardBounds.width() <= 1 ||
            cardBounds.height() <= 1
        ) {
            return false
        }

        val screenWidth =
            resources.displayMetrics.widthPixels
                .coerceAtLeast(1)

        val screenHeight =
            resources.displayMetrics.heightPixels
                .coerceAtLeast(1)

        val widthRatio =
            cardBounds.width().toDouble() /
                screenWidth.toDouble()

        val heightRatio =
            cardBounds.height().toDouble() /
                screenHeight.toDouble()

        if (
            widthRatio !in 0.22..0.98 ||
            heightRatio !in 0.22..0.98
        ) {
            return false
        }

        val labelBounds =
            Rect()

        labelNode.getBoundsInScreen(
            labelBounds
        )

        if (
            labelBounds.width() <= 0 ||
            labelBounds.height() <= 0
        ) {
            return false
        }

        // A Recents app label/icon header is expected near the upper portion of
        // its task card. This prevents exact app-name text inside another app's
        // content snapshot from being treated as the card identity.
        val headerLimit =
            cardBounds.top +
                maxOf(
                    180,
                    (
                        cardBounds.height() *
                            0.40
                        ).toInt()
                )

        return labelBounds.centerY() <=
            headerLimit
    }

    private fun dismissRecentTaskCandidate(
        candidate: RecentTaskCandidate
    ): Pair<Boolean, String> {

        val actionNode =
            candidate.actionNode

        if (actionNode != null) {
            val accepted =
                try {
                    actionNode.performAction(
                        AccessibilityNodeInfo.ACTION_DISMISS
                    )
                } catch (_: Exception) {
                    false
                }

            if (accepted) {
                return true to
                    "accessibility_action_dismiss"
            }
        }

        val gestureAccepted =
            swipeBoundsUp(
                candidate.bounds
            )

        return gestureAccepted to
            "gesture_swipe_up"
    }

    private fun reconcileRecentTaskAfterAcceptedDismiss(
        normalizedTarget: String,
        beforeSignature: String
    ): RecentTaskReconciliation {

        val deadline =
            SystemClock.elapsedRealtime() +
                RECENTS_DISMISS_VERIFY_TIMEOUT_MS

        var absentSamples =
            0

        var presentSamples =
            0

        do {
            val changed =
                screenSignature() !=
                    beforeSignature

            val candidate =
                findRecentTaskCandidate(
                    normalizedTarget
                )

            val recentsStillObservable =
                recentsWindowContexts(
                    resolveWindowContexts()
                ).isNotEmpty()

            if (
                changed &&
                candidate == null &&
                recentsStillObservable
            ) {
                absentSamples++
                presentSamples = 0

                if (absentSamples >= 2) {
                    return RecentTaskReconciliation.REMOVED
                }
            } else if (
                candidate != null &&
                recentsStillObservable
            ) {
                presentSamples++
                absentSamples = 0
            } else {
                absentSamples = 0
            }

            try {
                Thread.sleep(
                    RECENTS_VERIFY_POLL_MS
                )
            } catch (_: InterruptedException) {
                // P0 terminal-truth rule:
                // once Android accepted an irreversible dismiss request, a stale
                // STOP interrupt must not abort factual reconciliation. Clear the
                // interrupted flag and continue the bounded verification window.
                Thread.interrupted()
            }
        } while (
            SystemClock.elapsedRealtime() <
            deadline
        )

        // A still-present exact semantic card is positive proof of no commit.
        // Absence without the strict two-sample changed+Recents proof is UNKNOWN,
        // never CANCELLED and never SUCCESS.
        val finalCandidate =
            findRecentTaskCandidate(
                normalizedTarget
            )

        val recentsStillObservable =
            recentsWindowContexts(
                resolveWindowContexts()
            ).isNotEmpty()

        return if (
            presentSamples > 0 &&
            finalCandidate != null &&
            recentsStillObservable
        ) {
            RecentTaskReconciliation.STILL_PRESENT
        } else {
            RecentTaskReconciliation.UNKNOWN
        }
    }

    /**
     * Read-only Recents identity diagnostic. Never performs an Accessibility
     * action or gesture. It records the small set of visible nodes most likely
     * to carry OEM task identity (text/contentDescription, task/icon/thumbnail
     * ids/classes, or ACTION_DISMISS) plus fuzzy target hits. The result is
     * intentionally bounded so it is safe to persist in command history.
     */
    private fun buildRecentsTaskIdentityProbe(
        normalizedTarget: String
    ): JSONObject {

        val contexts =
            recentsWindowContexts(
                resolveWindowContexts()
            )

        val identityNodes =
            JSONArray()

        val structuralNodes =
            JSONArray()

        val targetHits =
            JSONArray()

        var visited =
            0

        fun nodeRecord(
            node: AccessibilityNodeInfo,
            depth: Int,
            bounds: Rect,
            normalizedText: String,
            normalizedDescription: String,
            rawViewId: String,
            normalizedClass: String
        ): JSONObject {

            val hasDismiss =
                try {
                    node.actionList.any { action ->
                        action.id ==
                            AccessibilityNodeInfo.ACTION_DISMISS
                    }
                } catch (_: Exception) {
                    false
                }

            return JSONObject()
                .put(
                    "depth",
                    depth
                )
                .put(
                    "text",
                    normalizedText.take(100)
                )
                .put(
                    "description",
                    normalizedDescription.take(120)
                )
                .put(
                    "view_id",
                    rawViewId.takeLast(120)
                )
                .put(
                    "class",
                    normalizedClass.takeLast(120)
                )
                .put(
                    "bounds",
                    "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                )
                .put(
                    "dismiss",
                    hasDismiss
                )
                .put(
                    "clickable",
                    try {
                        node.isClickable
                    } catch (_: Exception) {
                        false
                    }
                )
                .put(
                    "children",
                    try {
                        node.childCount
                    } catch (_: Exception) {
                        -1
                    }
                )
        }

        for (context in contexts.take(3)) {
            val root =
                context.root
                    ?: continue

            data class ProbeNode(
                val node: AccessibilityNodeInfo,
                val depth: Int
            )

            val queue =
                ArrayDeque<ProbeNode>()

            queue.add(
                ProbeNode(
                    node = root,
                    depth = 0
                )
            )

            while (
                queue.isNotEmpty() &&
                visited < RECENTS_IDENTITY_PROBE_NODE_LIMIT
            ) {
                val item =
                    queue.removeFirst()

                val node =
                    item.node

                visited++

                val visible =
                    try {
                        node.isVisibleToUser
                    } catch (_: Exception) {
                        false
                    }

                if (!visible) {
                    continue
                }

                val bounds =
                    Rect()

                try {
                    node.getBoundsInScreen(
                        bounds
                    )
                } catch (_: Exception) {
                }

                val normalizedText =
                    normalize(
                        try {
                            node.text
                                ?.toString()
                                .orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                    )

                val normalizedDescription =
                    normalize(
                        try {
                            node.contentDescription
                                ?.toString()
                                .orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                    )

                val rawViewId =
                    try {
                        node.viewIdResourceName
                            .orEmpty()
                            .lowercase(
                                Locale.ROOT
                            )
                    } catch (_: Exception) {
                        ""
                    }

                val normalizedClass =
                    normalize(
                        try {
                            node.className
                                ?.toString()
                                .orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                    )

                val hasDismiss =
                    try {
                        node.actionList.any { action ->
                            action.id ==
                                AccessibilityNodeInfo.ACTION_DISMISS
                        }
                    } catch (_: Exception) {
                        false
                    }

                val identityMeaningful =
                    normalizedText.isNotBlank() ||
                        normalizedDescription.isNotBlank()

                val structureMarker =
                    listOf(
                        "task",
                        "recent",
                        "overview",
                        "snapshot",
                        "thumbnail",
                        "icon",
                        "card",
                        "dismiss"
                    ).any { marker ->
                        rawViewId.contains(
                            marker
                        ) ||
                            normalizedClass.contains(
                                marker
                            )
                    } ||
                        hasDismiss

                val fuzzyTargetHit =
                    normalizedTarget.isNotBlank() &&
                        (
                            normalizedText.contains(
                                normalizedTarget
                            ) ||
                                normalizedDescription.contains(
                                    normalizedTarget
                                )
                            )

                if (
                    identityMeaningful &&
                    identityNodes.length() <
                    RECENTS_IDENTITY_PROBE_RECORD_LIMIT
                ) {
                    identityNodes.put(
                        nodeRecord(
                            node = node,
                            depth = item.depth,
                            bounds = bounds,
                            normalizedText = normalizedText,
                            normalizedDescription = normalizedDescription,
                            rawViewId = rawViewId,
                            normalizedClass = normalizedClass
                        )
                    )
                }

                if (
                    structureMarker &&
                    structuralNodes.length() <
                    RECENTS_IDENTITY_PROBE_RECORD_LIMIT
                ) {
                    structuralNodes.put(
                        nodeRecord(
                            node = node,
                            depth = item.depth,
                            bounds = bounds,
                            normalizedText = normalizedText,
                            normalizedDescription = normalizedDescription,
                            rawViewId = rawViewId,
                            normalizedClass = normalizedClass
                        )
                    )
                }

                if (
                    fuzzyTargetHit &&
                    targetHits.length() <
                    RECENTS_IDENTITY_PROBE_TARGET_HIT_LIMIT
                ) {
                    targetHits.put(
                        nodeRecord(
                            node = node,
                            depth = item.depth,
                            bounds = bounds,
                            normalizedText = normalizedText,
                            normalizedDescription = normalizedDescription,
                            rawViewId = rawViewId,
                            normalizedClass = normalizedClass
                        )
                    )
                }

                for (
                    index in
                    0 until node.childCount
                ) {
                    childWithPrefetch(
                        node,
                        index
                    )?.let { child ->
                        queue.add(
                            ProbeNode(
                                node = child,
                                depth = item.depth + 1
                            )
                        )
                    }
                }
            }
        }

        return JSONObject()
            .put(
                "target",
                normalizedTarget.take(80)
            )
            .put(
                "strong_contexts",
                contexts.size
            )
            .put(
                "visited",
                visited
            )
            .put(
                "target_hits",
                targetHits
            )
            .put(
                "identity_nodes",
                identityNodes
            )
            .put(
                "structural_nodes",
                structuralNodes
            )
    }

    private fun captureRecentsStructuralState(
        contexts: List<WindowContext> =
            recentsWindowContexts(
                resolveWindowContexts()
            )
    ): RecentsStructuralState {

        var overviewChildCount =
            -1

        var dismissableCount =
            0

        val parts =
            ArrayList<String>()

        var visited =
            0

        for (context in contexts) {
            val root =
                context.root
                    ?: continue

            val queue =
                ArrayDeque<AccessibilityNodeInfo>()

            queue.add(
                root
            )

            while (
                queue.isNotEmpty() &&
                visited < RECENTS_CANDIDATE_NODE_LIMIT
            ) {
                val node =
                    queue.removeFirst()

                visited++

                val visible =
                    try {
                        node.isVisibleToUser
                    } catch (_: Exception) {
                        false
                    }

                if (!visible) {
                    continue
                }

                val bounds =
                    Rect()

                node.getBoundsInScreen(
                    bounds
                )

                val rawViewId =
                    node.viewIdResourceName
                        .orEmpty()
                        .lowercase(
                            Locale.ROOT
                        )

                if (
                    overviewChildCount < 0 &&
                    rawViewId.endsWith(
                        ":id/overview_panel"
                    )
                ) {
                    overviewChildCount =
                        try {
                            node.childCount
                        } catch (_: Exception) {
                            -1
                        }
                }

                if (
                    node.actionList.any { action ->
                        action.id ==
                            AccessibilityNodeInfo.ACTION_DISMISS
                    }
                ) {
                    dismissableCount++
                }

                if (parts.size < 140) {
                    parts.add(
                        buildString {
                            append(
                                normalize(
                                    node.className
                                        ?.toString()
                                        .orEmpty()
                                )
                            )
                            append('|')
                            append(rawViewId.takeLast(80))
                            append('|')
                            append(
                                normalize(
                                    node.text
                                        ?.toString()
                                        .orEmpty()
                                ).take(100)
                            )
                            append('|')
                            append(
                                normalize(
                                    node.contentDescription
                                        ?.toString()
                                        .orEmpty()
                                ).take(100)
                            )
                            append('|')
                            append(bounds.left)
                            append(',')
                            append(bounds.top)
                            append(',')
                            append(bounds.right)
                            append(',')
                            append(bounds.bottom)
                            append('|')
                            append(node.childCount)
                        }
                    )
                }

                for (
                    index in
                    0 until node.childCount
                ) {
                    childWithPrefetch(
                        node,
                        index
                    )?.let { child ->
                        queue.add(
                            child
                        )
                    }
                }
            }
        }

        val fingerprint =
            if (parts.isEmpty()) {
                ""
            } else {
                parts
                    .joinToString("\n")
                    .hashCode()
                    .toString()
            }

        return RecentsStructuralState(
            fingerprint = fingerprint,
            overviewChildCount = overviewChildCount,
            dismissableCount = dismissableCount,
            strongContextCount = contexts.size
        )
    }

    private fun moveRecentsForward(): Boolean {

        if (
            scroll(
                "forward"
            )
        ) {
            return true
        }

        val width =
            resources.displayMetrics.widthPixels
                .coerceAtLeast(1)

        val height =
            resources.displayMetrics.heightPixels
                .coerceAtLeast(1)

        val before =
            captureRecentsStructuralState()

        if (
            before.strongContextCount <= 0 ||
            before.fingerprint.isBlank()
        ) {
            return false
        }

        val accepted =
            swipeCoordinates(
                startX =
                    (width * 0.78).toInt(),
                startY =
                    (height * 0.58).toInt(),
                endX =
                    (width * 0.22).toInt(),
                endY =
                    (height * 0.58).toInt(),
                durationMs = 240L
            )

        if (!accepted) {
            return false
        }

        val deadline =
            SystemClock.elapsedRealtime() +
                RECENTS_ENTER_TIMEOUT_MS

        do {
            val after =
                captureRecentsStructuralState()

            if (
                after.strongContextCount > 0 &&
                after.fingerprint.isNotBlank() &&
                after.fingerprint !=
                    before.fingerprint
            ) {
                return true
            }

            try {
                Thread.sleep(
                    RECENTS_VERIFY_POLL_MS
                )
            } catch (_: InterruptedException) {
                Thread.currentThread()
                    .interrupt()
                return false
            }
        } while (
            SystemClock.elapsedRealtime() <
            deadline
        )

        return false
    }

    private fun swipeBoundsUp(
        bounds: Rect
    ): Boolean {

        if (
            bounds.width() <= 1 ||
            bounds.height() <= 1
        ) {
            return false
        }

        val screenHeight =
            resources.displayMetrics.heightPixels
                .coerceAtLeast(1)

        val startY =
            bounds.centerY()
                .coerceIn(
                    1,
                    screenHeight -
                        1
                )

        val endY =
            maxOf(
                1,
                bounds.top -
                    maxOf(
                        bounds.height() /
                            2,
                        220
                    )
            )

        return swipeCoordinates(
            startX = bounds.centerX(),
            startY = startY,
            endX = bounds.centerX(),
            endY = endY,
            durationMs = 260L
        )
    }

    private fun swipeCoordinates(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long
    ): Boolean {

        if (
            startX < 0 ||
            startY < 0 ||
            endX < 0 ||
            endY < 0
        ) {
            return false
        }

        val path =
            Path().apply {
                moveTo(
                    startX.toFloat(),
                    startY.toFloat()
                )
                lineTo(
                    endX.toFloat(),
                    endY.toFloat()
                )
            }

        val gesture =
            GestureDescription
                .Builder()
                .addStroke(
                    GestureDescription
                        .StrokeDescription(
                            path,
                            0L,
                            durationMs.coerceIn(
                                120L,
                                600L
                            )
                        )
                )
                .build()

        return dispatchGesture(
            gesture,
            null,
            null
        )
    }

    private fun recentsEvidenceSummary(): JSONObject {

        val contexts =
            resolveWindowContexts()

        val items =
            JSONArray()

        for (
            context in
            contexts.take(6)
        ) {
            items.put(
                JSONObject()
                    .put(
                        "package",
                        context.packageName.take(90)
                    )
                    .put(
                        "class",
                        context.className.take(120)
                    )
                    .put(
                        "title",
                        context.title.take(80)
                    )
                    .put(
                        "active",
                        context.active
                    )
                    .put(
                        "focused",
                        context.focused
                    )
                    .put(
                        "rank",
                        context.rank
                    )
                    .put(
                        "recents",
                        isStrongRecentsWindowContext(
                            context
                        )
                    )
            )
        }

        return JSONObject()
            .put(
                "event_package",
                lastEventPackage.take(90)
            )
            .put(
                "event_class",
                lastEventClass.take(120)
            )
            .put(
                "windows",
                items
            )
    }

    private fun recentTaskResult(
        success: Boolean,
        verified: Boolean,
        terminalStatus: String,
        reason: String,
        message: String,
        removedCount: Int = 0,
        scanCount: Int = 0,
        dismissMethod: String = "",
        actionDispatched: Boolean = false,
        reconciliationComplete: Boolean = true,
        verifiedNotCommitted: Boolean = false,
        cancelAfterDispatch: Boolean = false
    ): JSONObject =
        JSONObject()
            .put("success", success)
            .put("verified", verified)
            .put("terminal_status", terminalStatus)
            .put("status", reason)
            .put("reason", reason)
            .put("message", message)
            .put("removed_count", removedCount)
            .put("scan_count", scanCount)
            .put("dismiss_method", dismissMethod)
            .put("action_dispatched", actionDispatched)
            .put("reconciliation_complete", reconciliationComplete)
            .put("verified_not_committed", verifiedNotCommitted)
            .put("cancel_after_dispatch", cancelAfterDispatch)

    fun clickByText(
        target: String
    ): Boolean {

        return clickElement(
            target
        )
    }

    /**
     * v6.7 Samsung Settings sparse-tree recovery.
     *
     * buildScreenSnapshot() intentionally stays fail-closed and does not invent nodes.
     * On some One UI App Info panes the normal descendant serialization exposes only
     * the persistent Open/Disable/Force-stop action cluster even while rows such as
     * Permissions are visibly rendered. Android's native text-query API can still
     * resolve that factual row from the same live Accessibility window.
     *
     * Safety/truth contract:
     * - current interaction package/window only;
     * - TYPE_APPLICATION roots only;
     * - exact normalized text/description match only;
     * - visible + enabled + non-password;
     * - ambiguous distinct matches fail closed;
     * - no coordinate guess: center tap is allowed only for the exact resolved node;
     * - true is returned only after screenSignature() changes.
     */
    fun clickElementByNativeTextQuery(
        target: String
    ): Boolean {

        val normalizedTarget =
            normalize(
                target
            )

        if (normalizedTarget.isBlank()) {
            return false
        }

        val allContexts =
            resolveWindowContexts()

        val primary =
            primaryWindowContext(
                allContexts
            )
                ?: return false

        val interactionContexts =
            interactionWindowContexts(
                allContexts
            )
                .filter { context ->
                    context.packageName ==
                        primary.packageName &&
                        context.type ==
                        AccessibilityWindowInfo.TYPE_APPLICATION
                }

        if (interactionContexts.isEmpty()) {
            return false
        }

        val candidates =
            mutableListOf<ContextNodeMatch>()

        val seen =
            linkedSetOf<String>()

        for (context in interactionContexts) {
            for (root in semanticRootsForContext(context)) {
                val matches =
                    try {
                        root.findAccessibilityNodeInfosByText(
                            target
                        )
                    } catch (_: Exception) {
                        emptyList()
                    }

                for (node in matches) {
                    val visible =
                        try { node.isVisibleToUser } catch (_: Exception) { false }

                    val enabled =
                        try { node.isEnabled } catch (_: Exception) { false }

                    val password =
                        try { node.isPassword } catch (_: Exception) { true }

                    if (!visible || !enabled || password) {
                        continue
                    }

                    val text =
                        normalize(
                            try {
                                node.text
                                    ?.toString()
                                    .orEmpty()
                            } catch (_: Exception) {
                                ""
                            }
                        )

                    val description =
                        normalize(
                            try {
                                node.contentDescription
                                    ?.toString()
                                    .orEmpty()
                            } catch (_: Exception) {
                                ""
                            }
                        )

                    val exactText =
                        text == normalizedTarget

                    val exactDescription =
                        description == normalizedTarget

                    if (!exactText && !exactDescription) {
                        continue
                    }

                    val bounds = Rect()
                    try {
                        node.getBoundsInScreen(
                            bounds
                        )
                    } catch (_: Exception) {
                        continue
                    }

                    if (
                        bounds.width() <= 1 ||
                        bounds.height() <= 1
                    ) {
                        continue
                    }

                    val key =
                        "${context.windowId}|${bounds.left}:${bounds.top}:${bounds.right}:${bounds.bottom}|$text|$description"

                    if (!seen.add(key)) {
                        continue
                    }

                    val score =
                        (if (exactText) 100 else 95) +
                            (if (try { node.isClickable } catch (_: Exception) { false }) 8 else 0)

                    candidates.add(
                        ContextNodeMatch(
                            context = context,
                            match = NodeMatch(
                                node = node,
                                score = score
                            ),
                            totalScore =
                                score * 1000 +
                                    context.rank
                        )
                    )
                }
            }
        }

        if (candidates.isEmpty()) {
            return false
        }

        val ordered =
            candidates
                .sortedByDescending { candidate ->
                    candidate.totalScore
                }

        val best =
            ordered.first()

        val bestBounds = Rect()
        try {
            best.match.node.getBoundsInScreen(
                bestBounds
            )
        } catch (_: Exception) {
            return false
        }

        val ambiguous =
            ordered
                .drop(1)
                .any { candidate ->
                    if (candidate.totalScore < best.totalScore - 3000) {
                        false
                    } else {
                        val bounds = Rect()
                        try {
                            candidate.match.node.getBoundsInScreen(
                                bounds
                            )
                        } catch (_: Exception) {
                            return@any false
                        }

                        overlapRatio(
                            bestBounds,
                            bounds
                        ) < 0.80
                    }
                }

        if (ambiguous) {
            return false
        }

        val center =
            nodeCenter(
                best.match.node
            )
                ?: return false

        if (
            isPointOccludedForContext(
                x = center.first,
                y = center.second,
                target = best.context,
                allContexts = allContexts
            )
        ) {
            return false
        }

        val before =
            screenSignature()

        val actionable =
            findActionableParent(
                best.match.node,
                AccessibilityNodeInfo.ACTION_CLICK
            )

        if (actionable != null) {
            val dispatchStartedWallAt =
                System.currentTimeMillis()

            val accepted =
                try {
                    actionable.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )
                } catch (_: Exception) {
                    false
                }

            if (accepted) {
                invalidateSemanticEvidenceAfterNodeMutation(
                    node = actionable,
                    dispatchStartedWallAt = dispatchStartedWallAt
                )

                if (waitForScreenChange(before)) {
                    return true
                }
            }
        }

        val tapStartedWallAt =
            System.currentTimeMillis()

        val tapAccepted =
            tapNodeCenter(
                best.match.node
            )

        if (tapAccepted) {
            invalidateSemanticEvidenceAfterNodeMutation(
                node = best.match.node,
                dispatchStartedWallAt = tapStartedWallAt
            )

            if (waitForScreenChange(before)) {
                return true
            }
        }

        return false
    }

    private fun semanticRootsForContext(
        context: WindowContext
    ): List<AccessibilityNodeInfo> {

        val roots =
            mutableListOf<AccessibilityNodeInfo>()

        context.root
            ?.let {
                roots.add(
                    it
                )
            }

        val recovered =
            try {
                recoveredRootForContext(
                    context
                )
            } catch (_: Exception) {
                null
            }

        if (
            recovered != null &&
            roots.none { root ->
                root === recovered
            }
        ) {
            roots.add(
                recovered
            )
        }

        return roots
            .distinctBy { root ->
                val windowId =
                    try {
                        root.windowId
                    } catch (_: Exception) {
                        -1
                    }

                "$windowId|${System.identityHashCode(root)}"
            }
    }

    fun clickElement(
        target: String
    ): Boolean {

        // v6.3 own-app truth: when MainActivity is the currently resumed app, the
        // semantic resolver consumed the in-process View snapshot. Dispatch back to
        // that same factual View hierarchy instead of falling through to a sparse
        // Accessibility shell or coordinate guess.
        if (shouldUseOwnAppSemanticBridge()) {
            return MainActivity
                .performOwnAppSemanticClick(
                    target
                )
        }

        val allContexts =
            resolveWindowContexts()

        val interactionContexts =
            interactionWindowContexts(
                allContexts
            )

        if (
            interactionContexts.isEmpty()
        ) {
            return false
        }

        var best: ContextNodeMatch? = null

        for (context in interactionContexts) {
            for (
                root in
                semanticRootsForContext(
                    context
                )
            ) {
                val candidate =
                    findBestNode(
                        root = root,
                        target = target,
                        requireEditable = false,
                        requireClickable = false
                    )
                        ?: continue

                val totalScore =
                    candidate.score * 1000 +
                        context.rank

                if (
                    best == null ||
                    totalScore > best.totalScore
                ) {
                    best =
                        ContextNodeMatch(
                            context = context,
                            match = candidate,
                            totalScore = totalScore
                        )
                }
            }
        }

        val before =
            screenSignature()

        if (best != null) {
            val match =
                best.match

            val center =
                nodeCenter(
                    match.node
                )

            val safeAtPoint =
                center == null ||
                    !isPointOccludedForContext(
                        x = center.first,
                        y = center.second,
                        target = best.context,
                        allContexts = allContexts
                    )

            if (safeAtPoint) {
                val actionable =
                    findActionableParent(
                        match.node,
                        AccessibilityNodeInfo.ACTION_CLICK
                    )

                if (actionable != null) {
                    val dispatchStartedWallAt =
                        System.currentTimeMillis()

                    val accepted =
                        try {
                            actionable.performAction(
                                AccessibilityNodeInfo.ACTION_CLICK
                            )
                        } catch (_: Exception) {
                            false
                        }

                    if (accepted) {
                        invalidateSemanticEvidenceAfterNodeMutation(
                            node = actionable,
                            dispatchStartedWallAt = dispatchStartedWallAt
                        )
                    }

                    if (
                        accepted &&
                        waitForScreenChange(before)
                    ) {
                        return true
                    }
                }

                val semanticTapStartedWallAt =
                    System.currentTimeMillis()

                val semanticTapAccepted =
                    tapNodeCenter(
                        match.node
                    )

                if (semanticTapAccepted) {
                    invalidateSemanticEvidenceAfterNodeMutation(
                        node = match.node,
                        dispatchStartedWallAt = semanticTapStartedWallAt
                    )
                }

                if (
                    semanticTapAccepted &&
                    waitForScreenChange(before)
                ) {
                    return true
                }

                if (
                    actionable != null &&
                    actionable !== match.node
                ) {
                    val rowCenter =
                        nodeCenter(
                            actionable
                        )

                    if (
                        rowCenter != null &&
                        !isPointOccludedForContext(
                            x = rowCenter.first,
                            y = rowCenter.second,
                            target = best.context,
                            allContexts = allContexts
                        )
                    ) {
                        val rowTapStartedWallAt =
                            System.currentTimeMillis()

                        val rowTapAccepted =
                            tapNodeCenter(
                                actionable
                            )

                        if (rowTapAccepted) {
                            invalidateSemanticEvidenceAfterNodeMutation(
                                node = actionable,
                                dispatchStartedWallAt = rowTapStartedWallAt
                            )
                        }

                        if (
                            rowTapAccepted &&
                            waitForScreenChange(before)
                        ) {
                            return true
                        }
                    }
                }
            }
        }

        // Event-source fallback is allowed only inside the current interaction
        // package/window group. Never use stale evidence from a background app.
        val eventTarget =
            findRecentEventTapTarget(
                target = target,
                interactionContexts = interactionContexts
            )

        if (eventTarget != null) {
            val targetContext =
                contextForEventTarget(
                    eventTarget,
                    interactionContexts
                )

            val centerX =
                eventTarget.bounds.centerX()
            val centerY =
                eventTarget.bounds.centerY()

            if (
                targetContext != null &&
                !isPointOccludedForContext(
                    x = centerX,
                    y = centerY,
                    target = targetContext,
                    allContexts = allContexts
                )
            ) {
                val eventTapStartedWallAt =
                    System.currentTimeMillis()

                val eventTapAccepted =
                    tapBoundsCenter(
                        eventTarget.bounds
                    )

                if (eventTapAccepted) {
                    invalidateSemanticEvidenceAfterViewportChange(
                        context = targetContext,
                        dispatchStartedWallAt = eventTapStartedWallAt
                    )
                }

                if (
                    eventTapAccepted &&
                    waitForScreenChange(before)
                ) {
                    return true
                }
            }
        }

        return false
    }

    private fun waitForScreenChange(
        before: String
    ): Boolean {

        val deadline =
            System.currentTimeMillis() +
                CLICK_VERIFY_TIMEOUT_MS

        while (
            System.currentTimeMillis() <
            deadline
        ) {

            try {

                Thread.sleep(
                    CLICK_VERIFY_POLL_MS
                )

            } catch (_: InterruptedException) {

                Thread.currentThread()
                    .interrupt()

                return false
            }

            if (
                screenSignature() !=
                before
            ) {
                return true
            }
        }

        return false
    }

    fun setText(
        target: String?,
        text: String
    ): Boolean {

        // v6.3 own-app input truth: use the same live EditText identity exposed by
        // MainActivity's in-process semantic snapshot. Exact-value verification still
        // occurs in AyanaScreenIntelligence after this dispatch.
        if (shouldUseOwnAppSemanticBridge()) {
            return MainActivity
                .performOwnAppSemanticSetText(
                    target = target,
                    text = text
                )
        }

        val contexts =
            interactionWindowContexts(
                resolveWindowContexts()
            )

        if (contexts.isEmpty()) {
            return false
        }

        val node =
            if (target.isNullOrBlank()) {
                contexts
                    .asSequence()
                    .flatMap { context ->
                        semanticRootsForContext(
                            context
                        ).asSequence()
                    }
                    .mapNotNull { root ->
                        findFocusedEditable(
                            root
                        )
                    }
                    .firstOrNull()
                    ?: contexts
                        .asSequence()
                        .flatMap { context ->
                            semanticRootsForContext(
                                context
                            ).asSequence()
                        }
                        .mapNotNull { root ->
                            findFirstEditable(
                                root
                            )
                        }
                        .firstOrNull()
            } else {
                var best: ContextNodeMatch? = null

                for (context in contexts) {
                    for (
                        root in
                        semanticRootsForContext(
                            context
                        )
                    ) {
                        val candidate =
                            findBestNode(
                                root = root,
                                target = target,
                                requireEditable = true,
                                requireClickable = false
                            ) ?: continue

                        val totalScore =
                            candidate.score * 1000 +
                                context.rank

                        if (
                            best == null ||
                            totalScore > best.totalScore
                        ) {
                            best =
                                ContextNodeMatch(
                                    context,
                                    candidate,
                                    totalScore
                                )
                        }
                    }
                }

                best?.match?.node
            }
                ?: return false

        if (node.isPassword) {
            return false
        }

        node.performAction(
            AccessibilityNodeInfo.ACTION_FOCUS
        )

        val arguments =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }

        val dispatchStartedWallAt =
            System.currentTimeMillis()

        val accepted =
            try {
                node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    arguments
                )
            } catch (_: Exception) {
                false
            }

        if (accepted) {
            invalidateSemanticEvidenceAfterNodeMutation(
                node = node,
                dispatchStartedWallAt = dispatchStartedWallAt
            )
        }

        return accepted
    }

    /**
     * Compatibility Boolean used by older call sites (including the dedicated
     * Recents scanner). True means physical viewport progress was verified, not
     * merely that Android accepted ACTION_SCROLL / dispatchGesture.
     */
    fun scroll(
        direction: String
    ): Boolean =
        scrollDetailed(
            direction
        )
            .optBoolean(
                "verified",
                false
            )

    /**
     * AYANA Accessibility v6.0 — VERIFIED VIEWPORT SCROLL.
     *
     * Normal path: use a real Accessibility scrollable node.
     * Recovery path: when the current interaction surface is valid but Samsung
     * exposes no scrollable descendant (typical sparse App Info detail pane),
     * perform one bounded vertical gesture inside that SAME verified window.
     *
     * A dispatched gesture is never success by itself. Progress requires either
     * a post-dispatch TYPE_VIEW_SCROLLED event with factual movement metadata or
     * a changed bounded visible-node fingerprint in the same window context.
     * This preserves terminal truth at list boundaries and prevents unrelated
     * window changes from being mistaken for scroll progress.
     */
    fun scrollDetailed(
        direction: String
    ): JSONObject {

        val allContexts =
            resolveWindowContexts()

        val contexts =
            interactionWindowContexts(
                allContexts
            )

        if (contexts.isEmpty()) {
            return scrollResult(
                accepted = false,
                verified = false,
                direction = direction,
                method = "none",
                reason = "interaction_context_unavailable"
            )
        }

        val normalized =
            normalize(direction)

        val backward =
            normalized.contains("вверх") ||
                normalized.contains("up") ||
                normalized.contains("назад") ||
                normalized.contains("back")

        val preferredAction =
            if (backward) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }

        data class ScrollCandidate(
            val context: WindowContext,
            val node: AccessibilityNodeInfo,
            val bounds: Rect,
            val score: Long
        )

        var best:
            ScrollCandidate? =
            null

        for (context in contexts) {
            val root =
                context.root
                    ?: continue

            val scrollable =
                findBestScrollable(root)
                    ?: continue

            val bounds =
                Rect()

            try {
                scrollable.getBoundsInScreen(
                    bounds
                )
            } catch (_: Exception) {
                continue
            }

            if (
                !isSuitableSemanticScrollCandidate(
                    context = context,
                    bounds = bounds
                )
            ) {
                continue
            }

            val area =
                bounds.width().coerceAtLeast(0).toLong() *
                    bounds.height().coerceAtLeast(0).toLong()

            val score =
                area +
                    context.rank.toLong() *
                        100L

            if (
                best == null ||
                score > best.score
            ) {
                best =
                    ScrollCandidate(
                        context = context,
                        node = scrollable,
                        bounds = bounds,
                        score = score
                    )
            }
        }

        val semanticCandidate =
            best

        if (semanticCandidate != null) {
            val center =
                nodeCenter(
                    semanticCandidate.node
                )

            if (
                center != null &&
                isPointOccludedForContext(
                    x = center.first,
                    y = center.second,
                    target = semanticCandidate.context,
                    allContexts = allContexts
                )
            ) {
                return scrollResult(
                    accepted = false,
                    verified = false,
                    direction = direction,
                    method = "accessibility_action_scroll",
                    reason = "scroll_target_occluded",
                    context = semanticCandidate.context
                )
            }

            val beforeSignature =
                screenSignature()

            val beforeFingerprint =
                viewportFingerprint(
                    semanticCandidate.context
                )

            val beforeEvent =
                lastScrollEventState

            val dispatchStartedAt =
                SystemClock.elapsedRealtime()

            val dispatchStartedWallAt =
                System.currentTimeMillis()

            val accepted =
                try {
                    semanticCandidate.node.performAction(
                        preferredAction
                    )
                } catch (_: Exception) {
                    false
                }

            if (accepted) {
                val progress =
                    waitForScrollProgress(
                        context = semanticCandidate.context,
                        beforeSignature = beforeSignature,
                        beforeFingerprint = beforeFingerprint,
                        beforeEvent = beforeEvent,
                        dispatchStartedAt = dispatchStartedAt
                    )

                if (progress.verified) {
                    invalidateSemanticEvidenceAfterViewportChange(
                        context = semanticCandidate.context,
                        dispatchStartedWallAt = dispatchStartedWallAt
                    )
                }

                // An accepted semantic scroll with unknown physical outcome must
                // not be followed by a second blind gesture: it may already have
                // moved while Samsung withheld observable evidence. Fail closed.
                return scrollResult(
                    accepted = true,
                    verified = progress.verified,
                    direction = direction,
                    method = "accessibility_action_scroll",
                    reason =
                        if (progress.verified) {
                            "scroll_progress_verified"
                        } else {
                            "scroll_dispatch_outcome_unverified"
                        },
                    context = semanticCandidate.context,
                    progress = progress
                )
            }
        }

        val gestureContext =
            safeGestureScrollContext(
                contexts = contexts,
                allContexts = allContexts,
                preferred = semanticCandidate?.context
            )
                ?: return scrollResult(
                    accepted = false,
                    verified = false,
                    direction = direction,
                    method =
                        if (semanticCandidate == null) {
                            "none"
                        } else {
                            "accessibility_action_scroll"
                        },
                    reason =
                        if (semanticCandidate == null) {
                            "scrollable_target_unavailable"
                        } else {
                            "scroll_action_rejected_no_safe_gesture_fallback"
                        }
                )

        val beforeSignature =
            screenSignature()

        val beforeFingerprint =
            viewportFingerprint(
                gestureContext
            )

        val beforeEvent =
            lastScrollEventState

        val dispatchStartedAt =
            SystemClock.elapsedRealtime()

        val dispatchStartedWallAt =
            System.currentTimeMillis()

        val gestureAccepted =
            dispatchVerticalScrollGesture(
                context = gestureContext,
                backward = backward
            )

        if (!gestureAccepted) {
            return scrollResult(
                accepted = false,
                verified = false,
                direction = direction,
                method = "gesture_vertical_scroll",
                reason = "gesture_scroll_rejected",
                context = gestureContext
            )
        }

        val progress =
            waitForScrollProgress(
                context = gestureContext,
                beforeSignature = beforeSignature,
                beforeFingerprint = beforeFingerprint,
                beforeEvent = beforeEvent,
                dispatchStartedAt = dispatchStartedAt
            )

        if (progress.verified) {
            invalidateSemanticEvidenceAfterViewportChange(
                context = gestureContext,
                dispatchStartedWallAt = dispatchStartedWallAt
            )
        }

        return scrollResult(
            accepted = true,
            verified = progress.verified,
            direction = direction,
            method = "gesture_vertical_scroll",
            reason =
                if (progress.verified) {
                    "scroll_progress_verified"
                } else {
                    "gesture_scroll_no_verified_progress"
                },
            context = gestureContext,
            progress = progress
        )
    }

    private fun scrollResult(
        accepted: Boolean,
        verified: Boolean,
        direction: String,
        method: String,
        reason: String,
        context: WindowContext? = null,
        progress: ScrollProgressEvidence? = null
    ): JSONObject {

        val signatureChanged =
            progress?.signatureChanged
                ?: false

        val viewportChanged =
            progress?.viewportChanged
                ?: false

        val scrollEventObserved =
            progress?.scrollEventObserved
                ?: false

        return JSONObject()
            .put("success", verified)
            .put("verified", verified)
            .put("action_accepted", accepted)
            .put("terminal_status", if (verified) "SUCCESS" else "ERROR")
            .put("status", reason)
            .put("reason", reason)
            .put("direction", direction)
            .put("dispatch_method", method)
            .put("signature_changed", signatureChanged)
            .put("viewport_changed", viewportChanged)
            .put("scroll_event_observed", scrollEventObserved)
            .put(
                "proof_level",
                progress?.proofLevel
                    ?: "none"
            )
            .put(
                "target_context_id",
                context?.contextId
                    .orEmpty()
            )
            .put(
                "target_window_id",
                context?.windowId
                    ?: -1
            )
            .put(
                "target_package",
                context?.packageName
                    .orEmpty()
            )
    }

    private fun invalidateSemanticEvidenceForWindow(
        windowId: Int,
        targetPackage: String,
        recoveryKey: String,
        dispatchStartedWallAt: Long
    ) {

        synchronized(
            eventEvidenceLock
        ) {
            val retained =
                ArrayDeque<EventEvidence>()

            while (
                recentEventEvidence.isNotEmpty()
            ) {
                val evidence =
                    recentEventEvidence.removeFirst()

                val sameWindow =
                    windowId >= 0 &&
                        evidence.windowId ==
                        windowId

                val samePackageFallback =
                    windowId < 0 &&
                        evidence.windowId < 0 &&
                        evidence.packageName ==
                        targetPackage

                val staleForMutation =
                    (
                        sameWindow ||
                            samePackageFallback
                        ) &&
                        evidence.at <
                        dispatchStartedWallAt

                if (!staleForMutation) {
                    retained.addLast(
                        evidence
                    )
                }
            }

            while (
                retained.isNotEmpty()
            ) {
                recentEventEvidence.addLast(
                    retained.removeFirst()
                )
            }
        }

        if (
            targetPackage ==
            SETTINGS_PACKAGE
        ) {
            lastSettingsSnapshotRecoveryAt =
                0L
        }

        if (
            recoveryKey.isNotBlank() &&
            recoveryKey ==
            lastForegroundSnapshotRecoveryKey
        ) {
            lastForegroundSnapshotRecoveryAt =
                0L
        }
    }

    private fun invalidateSemanticEvidenceAfterViewportChange(
        context: WindowContext,
        dispatchStartedWallAt: Long
    ) {

        invalidateSemanticEvidenceForWindow(
            windowId = context.windowId,
            targetPackage = context.packageName,
            recoveryKey = "${context.contextId}|${context.packageName}",
            dispatchStartedWallAt = dispatchStartedWallAt
        )
    }

    private fun invalidateSemanticEvidenceAfterNodeMutation(
        node: AccessibilityNodeInfo,
        dispatchStartedWallAt: Long
    ) {

        val windowId =
            try {
                node.windowId
            } catch (_: Exception) {
                -1
            }

        val targetPackage =
            try {
                node.packageName
                    ?.toString()
                    .orEmpty()
            } catch (_: Exception) {
                ""
            }

        val recoveryKey =
            if (
                windowId >= 0 &&
                targetPackage.isNotBlank()
            ) {
                "w:$windowId:$targetPackage|$targetPackage"
            } else {
                ""
            }

        invalidateSemanticEvidenceForWindow(
            windowId = windowId,
            targetPackage = targetPackage,
            recoveryKey = recoveryKey,
            dispatchStartedWallAt = dispatchStartedWallAt
        )
    }

    private fun waitForScrollProgress(
        context: WindowContext,
        beforeSignature: String,
        beforeFingerprint: String,
        beforeEvent: ScrollEventState?,
        dispatchStartedAt: Long
    ): ScrollProgressEvidence {

        val deadline =
            SystemClock.elapsedRealtime() +
                SCROLL_VERIFY_TIMEOUT_MS

        var signatureChanged =
            false

        do {
            try {
                Thread.sleep(
                    SCROLL_VERIFY_POLL_MS
                )
            } catch (_: InterruptedException) {
                Thread.currentThread()
                    .interrupt()

                return ScrollProgressEvidence(
                    verified = false,
                    signatureChanged = signatureChanged,
                    viewportChanged = false,
                    scrollEventObserved = false,
                    proofLevel = "none"
                )
            }

            val currentSignature =
                screenSignature()

            if (
                beforeSignature.isNotBlank() &&
                currentSignature.isNotBlank() &&
                currentSignature != beforeSignature
            ) {
                signatureChanged =
                    true
            }

            val afterEvent =
                lastScrollEventState

            if (
                scrollEventProvesProgress(
                    before = beforeEvent,
                    after = afterEvent,
                    context = context,
                    dispatchStartedAt = dispatchStartedAt
                )
            ) {
                return ScrollProgressEvidence(
                    verified = true,
                    signatureChanged = signatureChanged,
                    viewportChanged = true,
                    scrollEventObserved = true,
                    proofLevel = "accessibility_scroll_event"
                )
            }

            val currentContext =
                findSameWindowContext(
                    original = context,
                    contexts = interactionWindowContexts(
                        resolveWindowContexts()
                    )
                )

            if (currentContext != null) {
                val currentFingerprint =
                    viewportFingerprint(
                        currentContext
                    )

                if (
                    beforeFingerprint.isNotBlank() &&
                    currentFingerprint.isNotBlank() &&
                    currentFingerprint != beforeFingerprint
                ) {
                    return ScrollProgressEvidence(
                        verified = true,
                        signatureChanged = signatureChanged,
                        viewportChanged = true,
                        scrollEventObserved = false,
                        proofLevel = "same_window_viewport_structure_change"
                    )
                }
            }
        } while (
            SystemClock.elapsedRealtime() <
            deadline
        )

        return ScrollProgressEvidence(
            verified = false,
            signatureChanged = signatureChanged,
            viewportChanged = false,
            scrollEventObserved = false,
            proofLevel =
                if (signatureChanged) {
                    "signature_change_without_scroll_proof"
                } else {
                    "none"
                }
        )
    }

    private fun scrollEventProvesProgress(
        before: ScrollEventState?,
        after: ScrollEventState?,
        context: WindowContext,
        dispatchStartedAt: Long
    ): Boolean {

        if (
            after == null ||
            after.atElapsed < dispatchStartedAt ||
            !scrollEventBelongsToContext(
                event = after,
                context = context
            )
        ) {
            return false
        }

        // beforeEvent is sampled immediately before dispatch. A non-zero delta
        // from that SAME event must never be reused as post-dispatch proof, even
        // when elapsedRealtime() happens to have the same millisecond value.
        if (
            before != null &&
            before.sequence == after.sequence
        ) {
            return false
        }

        if (
            after.deltaX != 0 ||
            after.deltaY != 0
        ) {
            return true
        }

        if (
            before == null ||
            !scrollEventBelongsToContext(
                event = before,
                context = context
            )
        ) {
            return false
        }

        val axisChanged =
            (
                before.scrollX >= 0 &&
                    after.scrollX >= 0 &&
                    before.scrollX != after.scrollX
                ) ||
                (
                    before.scrollY >= 0 &&
                        after.scrollY >= 0 &&
                        before.scrollY != after.scrollY
                    )

        if (axisChanged) {
            return true
        }

        return before.fromIndex >= 0 &&
            after.fromIndex >= 0 &&
            (
                before.fromIndex != after.fromIndex ||
                    before.toIndex != after.toIndex ||
                    before.itemCount != after.itemCount
                )
    }

    private fun scrollEventBelongsToContext(
        event: ScrollEventState,
        context: WindowContext
    ): Boolean {

        if (
            event.packageName.isBlank() ||
            context.packageName.isBlank() ||
            event.packageName != context.packageName
        ) {
            return false
        }

        return if (
            event.windowId >= 0 &&
            context.windowId >= 0
        ) {
            event.windowId == context.windowId
        } else {
            true
        }
    }

    private fun findSameWindowContext(
        original: WindowContext,
        contexts: List<WindowContext>
    ): WindowContext? {

        if (contexts.isEmpty()) {
            return null
        }

        if (original.windowId >= 0) {
            contexts
                .firstOrNull { context ->
                    context.windowId == original.windowId &&
                        context.packageName == original.packageName
                }
                ?.let {
                    return it
                }
        }

        return contexts
            .firstOrNull { context ->
                context.packageName == original.packageName &&
                    overlapRatio(
                        context.bounds,
                        original.bounds
                    ) >= 0.70
            }
    }

    /**
     * Bounded fingerprint of visible nodes in exactly one Android window. It is
     * intentionally independent from verification_text TTL, so expiring event
     * evidence cannot manufacture scroll progress.
     */
    private fun viewportFingerprint(
        context: WindowContext
    ): String {

        val root =
            context.root
                ?: return ""

        val queue =
            ArrayDeque<AccessibilityNodeInfo>()

        queue.add(
            root
        )

        val parts =
            ArrayList<String>()

        var visited =
            0

        while (
            queue.isNotEmpty() &&
            visited < SCROLL_FINGERPRINT_NODE_LIMIT
        ) {
            val node =
                queue.removeFirst()

            visited++

            val visible =
                try {
                    node.isVisibleToUser
                } catch (_: Exception) {
                    false
                }

            if (visible) {
                val bounds =
                    Rect()

                try {
                    node.getBoundsInScreen(
                        bounds
                    )
                } catch (_: Exception) {
                }

                val text =
                    normalize(
                        try {
                            node.text
                                ?.toString()
                                .orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                    )

                val description =
                    normalize(
                        try {
                            node.contentDescription
                                ?.toString()
                                .orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                    )

                val viewId =
                    try {
                        node.viewIdResourceName
                            .orEmpty()
                    } catch (_: Exception) {
                        ""
                    }

                if (
                    text.isNotBlank() ||
                    description.isNotBlank() ||
                    viewId.isNotBlank() ||
                    node.isScrollable
                ) {
                    parts.add(
                        buildString {
                            append(text.take(100))
                            append('|')
                            append(description.take(100))
                            append('|')
                            append(viewId.takeLast(100))
                            append('|')
                            append(bounds.left)
                            append(',')
                            append(bounds.top)
                            append(',')
                            append(bounds.right)
                            append(',')
                            append(bounds.bottom)
                            append('|')
                            append(node.childCount)
                        }
                    )
                }
            }

            val childCount =
                try {
                    node.childCount
                } catch (_: Exception) {
                    0
                }

            for (index in 0 until childCount) {
                childWithPrefetch(
                    node,
                    index
                )?.let { child ->
                    queue.add(
                        child
                    )
                }
            }
        }

        return if (parts.isEmpty()) {
            ""
        } else {
            parts
                .joinToString("\n")
                .hashCode()
                .toString()
        }
    }

    /**
     * Samsung tablet Settings is commonly two-pane in landscape. A sparse
     * App-Info detail tree may expose only the LEFT navigation list as
     * Accessibility-scrollable. Scrolling that list is real movement but it is
     * movement in the wrong pane. Reject such a semantic candidate so the
     * verified right-detail gesture fallback can own the scroll instead.
     *
     * Full-width/single-pane Settings and all non-Settings apps keep the normal
     * semantic scroll path.
     */
    private fun isSuitableSemanticScrollCandidate(
        context: WindowContext,
        bounds: Rect
    ): Boolean {

        if (
            context.packageName != SETTINGS_PACKAGE ||
            resources.displayMetrics.widthPixels <=
                resources.displayMetrics.heightPixels
        ) {
            return true
        }

        val contextWidth =
            context.bounds.width()

        if (
            contextWidth <= 1 ||
            bounds.width() <= 1
        ) {
            return true
        }

        val detailThresholdX =
            context.bounds.left +
                (
                    contextWidth *
                        SETTINGS_LANDSCAPE_DETAIL_MIN_CENTER_RATIO
                    )
                    .toInt()

        return bounds.centerX() >=
            detailThresholdX
    }

    private fun safeGestureScrollContext(
        contexts: List<WindowContext>,
        allContexts: List<WindowContext>,
        preferred: WindowContext?
    ): WindowContext? {

        // Recents has its own horizontal scanner and verified task identity path.
        // Never inject this generic vertical recovery gesture into that surface.
        if (
            contexts.any { context ->
                isStrongRecentsWindowContext(
                    context
                )
            }
        ) {
            return null
        }

        val candidates =
            contexts
                .filter { context ->
                    context.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        context.packageName.isNotBlank() &&
                        !context.pictureInPicture &&
                        isLargeEnoughForScrollGesture(
                            context.bounds
                        ) &&
                        contextOcclusionRatio(
                            context,
                            allContexts
                        ) < SCROLL_GESTURE_MAX_OCCLUSION_RATIO
                }

        if (candidates.isEmpty()) {
            return null
        }

        if (
            preferred != null
        ) {
            findSameWindowContext(
                original = preferred,
                contexts = candidates
            )?.let {
                return it
            }
        }

        return candidates
            .maxByOrNull { context ->
                val area =
                    context.bounds.width().coerceAtLeast(0).toLong() *
                        context.bounds.height().coerceAtLeast(0).toLong()

                area +
                    context.rank.toLong() *
                        100L
            }
    }

    private fun isLargeEnoughForScrollGesture(
        bounds: Rect
    ): Boolean {

        if (
            bounds.width() < SCROLL_GESTURE_MIN_WIDTH_PX ||
            bounds.height() < SCROLL_GESTURE_MIN_HEIGHT_PX
        ) {
            return false
        }

        val screenArea =
            resources.displayMetrics.widthPixels
                .coerceAtLeast(1)
                .toLong() *
                resources.displayMetrics.heightPixels
                    .coerceAtLeast(1)
                    .toLong()

        val area =
            bounds.width().coerceAtLeast(0).toLong() *
                bounds.height().coerceAtLeast(0).toLong()

        return screenArea > 0L &&
            area.toDouble() /
                screenArea.toDouble() >=
            SCROLL_GESTURE_MIN_AREA_RATIO
    }

    private fun dispatchVerticalScrollGesture(
        context: WindowContext,
        backward: Boolean
    ): Boolean {

        val bounds =
            Rect(
                context.bounds
            )

        if (!isLargeEnoughForScrollGesture(bounds)) {
            return false
        }

        val width =
            bounds.width()
                .coerceAtLeast(1)

        val height =
            bounds.height()
                .coerceAtLeast(1)

        val landscape =
            resources.displayMetrics.widthPixels >
                resources.displayMetrics.heightPixels

        // Samsung tablet Settings commonly renders navigation at the left and
        // the app-detail list at the right inside one full-width application
        // window. Bias the recovery gesture into the detail pane only for that
        // package/layout; normal apps remain centered.
        val xFraction =
            if (
                context.packageName == SETTINGS_PACKAGE &&
                landscape
            ) {
                0.70
            } else {
                0.50
            }

        val x =
            (
                bounds.left +
                    width * xFraction
                )
                .toInt()
                .coerceIn(
                    bounds.left + 1,
                    bounds.right - 1
                )

        val upperY =
            (
                bounds.top +
                    height * 0.32
                )
                .toInt()
                .coerceIn(
                    bounds.top + 1,
                    bounds.bottom - 1
                )

        val lowerY =
            (
                bounds.top +
                    height * 0.72
                )
                .toInt()
                .coerceIn(
                    bounds.top + 1,
                    bounds.bottom - 1
                )

        if (
            isPointOccludedForContext(
                x = x,
                y = upperY,
                target = context,
                allContexts = resolveWindowContexts()
            ) ||
            isPointOccludedForContext(
                x = x,
                y = lowerY,
                target = context,
                allContexts = resolveWindowContexts()
            )
        ) {
            return false
        }

        return if (backward) {
            // Content moves toward the beginning: finger moves downward.
            swipeCoordinates(
                startX = x,
                startY = upperY,
                endX = x,
                endY = lowerY,
                durationMs = SCROLL_GESTURE_DURATION_MS
            )
        } else {
            // Content moves toward the end: finger moves upward.
            swipeCoordinates(
                startX = x,
                startY = lowerY,
                endX = x,
                endY = upperY,
                durationMs = SCROLL_GESTURE_DURATION_MS
            )
        }
    }

    private fun tapNodeCenter(
        node: AccessibilityNodeInfo
    ): Boolean {

        if (
            !node.isVisibleToUser ||
            !node.isEnabled
        ) {
            return false
        }

        val bounds =
            Rect()

        node.getBoundsInScreen(
            bounds
        )

        if (
            bounds.width() <= 1 ||
            bounds.height() <= 1
        ) {
            return false
        }

        return tapCoordinates(
            bounds.centerX(),
            bounds.centerY()
        )
    }

    fun tapCoordinates(
        x: Int,
        y: Int
    ): Boolean {

        if (
            x < 0 ||
            y < 0
        ) {
            return false
        }

        val path =
            Path().apply {

                moveTo(
                    x.toFloat(),
                    y.toFloat()
                )
            }

        val gesture =
            GestureDescription
                .Builder()
                .addStroke(
                    GestureDescription
                        .StrokeDescription(
                            path,
                            0,
                            80
                        )
                )
                .build()

        return dispatchGesture(
            gesture,
            null,
            null
        )
    }

    fun buildScreenSnapshot(
        maxNodes: Int = 140,
        maxChars: Int = 14000
    ): JSONObject {

        val snapshotStartedAt =
            SystemClock.elapsedRealtime()

        // v6.5 cross-app foreground truth:
        // MainActivity's in-process View tree is trusted only while its window is
        // genuinely focused (MainActivity v7.2 gate) AND Accessibility does not expose
        // a different active/focused application window. This second guard is deliberate
        // defense-in-depth for Android large-screen multi-resume / lifecycle races.
        // It prevents a stale own-app snapshot from masking Settings, Chrome, YouTube,
        // or any other external foreground application during terminal verification.
        val ownAppBridgeActive =
            shouldUseOwnAppSemanticBridge()

        if (ownAppBridgeActive) {
            val ownAppSnapshot =
                try {
                    MainActivity
                        .buildOwnAppSemanticSnapshot(
                            maxNodes = maxNodes,
                            maxChars = maxChars
                        )
                } catch (_: Exception) {
                    null
                }

            if (
                ownAppSnapshot != null &&
                ownAppSnapshot.optBoolean(
                    "snapshot_success",
                    ownAppSnapshot.optBoolean("success", false)
                )
            ) {
                return ownAppSnapshot
                    .put(
                        "snapshot_duration_ms",
                        (SystemClock.elapsedRealtime() - snapshotStartedAt)
                            .coerceAtLeast(0L)
                    )
                    .put(
                        "foreground_truth_mode",
                        "own_app_window_focus_plus_event_owner_plus_accessibility_guard"
                    )
            }
        }

        val liveContexts =
            resolveWindowContexts()

        // SETTINGS PERCEPTION v5.2
        // Event-driven recovery is not always enough on Samsung One UI: an App
        // Info transition can expose the correct Settings window while delivering
        // no useful event.source at all. Snapshot callers are already outside the
        // accessibility hot event path, so when the active Settings context is
        // sparse we may do one throttled API-33 descendant prefetch and inject the
        // resulting evidence into that SAME window context.
        maybeCaptureSettingsOnDemandEvidence(
            liveContexts
        )

        // v6.1: Universal Perception must not rely on Settings-only recovery.
        // Samsung/Android may also return a sparse focused root for AYANA itself
        // or another foreground app. Recover one bounded descendant snapshot for
        // that exact focused application window only when the ordinary root is
        // semantically sparse.
        maybeCaptureForegroundOnDemandEvidence(
            liveContexts
        )

        val evidence =
            currentEventEvidenceBurst(
                liveContexts
            )

        if (
            liveContexts.isEmpty() &&
            evidence.isEmpty()
        ) {
            return JSONObject()
                .put("success", false)
                .put("snapshot_success", false)
                .put("understanding_success", false)
                .put("content_contract_version", 2)
                .put("content_status", "unknown")
                .put("primary_content_state", "unknown")
                .put("primary_content_available", false)
                .put("primary_failure_reason", "no_accessible_window")
                .put("message", "Активное окно недоступно")
                .put("window_count", 0)
                .put("raw_window_count", safeWindowCount())
                .put(
                    "snapshot_duration_ms",
                    (SystemClock.elapsedRealtime() - snapshotStartedAt)
                        .coerceAtLeast(0L)
                )
        }

        data class WindowAccumulator(
            val context: WindowContext,
            val nodes: JSONArray = JSONArray(),
            val texts: LinkedHashSet<String> = linkedSetOf(),
            val liveTexts: LinkedHashSet<String> = linkedSetOf(),
            val evidenceTexts: LinkedHashSet<String> = linkedSetOf(),
            var evidenceAgeMs: Long = -1L
        )

        val accumulators =
            linkedMapOf<String, WindowAccumulator>()

        for (context in liveContexts) {
            val accumulator =
                WindowAccumulator(context)

            // Window titles are Android-owned metadata and are useful when an
            // OEM exposes a valid window but temporarily returns a sparse root.
            // Keep the title inside the SAME context; never promote it across
            // windows or use it as proof for another package.
            val title =
                safeText(
                    context.title
                )

            if (title.isNotBlank()) {
                accumulator.texts.add(title)
                accumulator.liveTexts.add(title)
            }

            accumulators[context.contextId] =
                accumulator
        }

        val liveNodeBudget =
            if (evidence.isNotEmpty()) {
                maxOf(40, maxNodes * 2 / 3)
            } else {
                maxNodes
            }

        val liveCharBudget =
            if (evidence.isNotEmpty()) {
                maxOf(4000, maxChars * 2 / 3)
            } else {
                maxChars
            }

        data class QueueItem(
            val context: WindowContext,
            val node: AccessibilityNodeInfo,
            val depth: Int
        )

        val queue =
            ArrayDeque<QueueItem>()

        for (context in liveContexts) {
            context.root?.let { root ->
                queue.add(
                    QueueItem(context, root, 0)
                )
            }
        }

        var visited = 0
        var charCount = 0

        while (
            queue.isNotEmpty() &&
            visited < liveNodeBudget &&
            charCount < liveCharBudget
        ) {
            val current = queue.removeFirst()
            visited++

            val item =
                try {
                    nodeToJson(
                        node = current.node,
                        depth = current.depth,
                        index = visited
                    )
                        .put("context_id", current.context.contextId)
                        .put("window_id", current.context.windowId)
                        .put("window_type", current.context.type)
                        .put("window_layer", current.context.layer)
                        .put("window_active", current.context.active)
                        .put("window_focused", current.context.focused)
                        .put("node_source", "live")
                } catch (_: Exception) {
                    null
                }

            if (item != null) {
                val serialized = item.toString()
                if (charCount + serialized.length <= liveCharBudget) {
                    accumulators[current.context.contextId]
                        ?.nodes
                        ?.put(item)
                    appendNodeTexts(
                        accumulators[current.context.contextId]?.texts,
                        item
                    )
                    appendNodeTexts(
                        accumulators[current.context.contextId]?.liveTexts,
                        item
                    )
                    charCount += serialized.length
                }
            }

            val childCount =
                try {
                    current.node.childCount
                } catch (_: Exception) {
                    0
                }

            for (index in 0 until childCount) {
                val child =
                    childWithPrefetch(
                        current.node,
                        index
                    ) ?: continue

                queue.add(
                    QueueItem(
                        context = current.context,
                        node = child,
                        depth = current.depth + 1
                    )
                )
            }
        }

        val now =
            System.currentTimeMillis()

        var evidenceContextCount = 0

        for (item in evidence) {
            val matched =
                accumulators.values.firstOrNull { accumulator ->
                    item.windowId >= 0 &&
                        accumulator.context.windowId == item.windowId
                } ?: run {
                    if (item.windowId >= 0) {
                        null
                    } else {
                        accumulators.values
                            .filter { accumulator ->
                                accumulator.context.packageName == item.packageName
                            }
                            .maxByOrNull { accumulator ->
                                overlapRatio(
                                    accumulator.context.bounds,
                                    item.bounds
                                )
                            }
                            ?.takeIf { accumulator ->
                                overlapRatio(
                                    accumulator.context.bounds,
                                    item.bounds
                                ) >= 0.25
                            }
                    }
                }

            val accumulator =
                matched
                    ?: run {
                        val synthetic =
                            syntheticContextForEvidence(
                                evidence = item,
                                liveContexts = liveContexts
                            )
                        evidenceContextCount++
                        WindowAccumulator(synthetic).also { created ->
                            accumulators[synthetic.contextId] = created
                        }
                    }

            val age =
                (now - item.at).coerceAtLeast(0L)

            if (
                accumulator.evidenceAgeMs < 0L ||
                age < accumulator.evidenceAgeMs
            ) {
                accumulator.evidenceAgeMs = age
            }

            for (text in item.visibleText) {
                val clipped = safeText(text)
                if (clipped.isNotBlank()) {
                    accumulator.texts.add(clipped)
                    accumulator.evidenceTexts.add(clipped)
                }
            }

            for (index in 0 until item.nodes.length()) {
                if (visited >= maxNodes || charCount >= maxChars) {
                    break
                }

                val original =
                    item.nodes.optJSONObject(index)
                        ?: continue

                val node =
                    JSONObject(original.toString())
                        .put("context_id", accumulator.context.contextId)
                        .put("window_id", accumulator.context.windowId)
                        .put("window_type", accumulator.context.type)
                        .put("window_layer", accumulator.context.layer)
                        .put("window_active", accumulator.context.active)
                        .put("window_focused", accumulator.context.focused)
                        .put("node_source", "event")

                val serialized = node.toString()
                if (charCount + serialized.length > maxChars) {
                    break
                }

                accumulator.nodes.put(node)
                appendNodeTexts(accumulator.texts, node)
                appendNodeTexts(accumulator.evidenceTexts, node)
                visited++
                charCount += serialized.length
            }
        }

        val allContexts =
            accumulators.values
                .map { it.context }
                .sortedByDescending { it.rank }

        val interactionContexts =
            interactionWindowContexts(
                allContexts
            )

        val interactionIds =
            interactionContexts
                .map { it.contextId }
                .toSet()

        val primary =
            primaryWindowContext(
                allContexts
            )

        val windowsJson = JSONArray()
        val topNodes = JSONArray()
        val topVisible = linkedSetOf<String>()
        val verificationVisible = linkedSetOf<String>()
        val allVisible = linkedSetOf<String>()
        val packages = linkedSetOf<String>()

        val sortedAccumulators =
            accumulators.values
                .sortedByDescending { it.context.rank }

        for (accumulator in sortedAccumulators) {
            val context = accumulator.context
            if (context.packageName.isNotBlank()) {
                packages.add(context.packageName)
            }

            val visibleArray = JSONArray()
            for (text in accumulator.texts) {
                if (visibleArray.length() < WINDOW_VISIBLE_TEXT_LIMIT) {
                    visibleArray.put(text)
                }

                if (allVisible.size < ALL_VISIBLE_TEXT_LIMIT) {
                    allVisible.add(text)
                }

                if (context.contextId in interactionIds && topVisible.size < TOP_VISIBLE_TEXT_LIMIT) {
                    topVisible.add(text)
                }
            }

            val contextVerificationTexts =
                linkedSetOf<String>()
                    .apply {
                        addAll(accumulator.liveTexts)
                        if (
                            accumulator.evidenceAgeMs >= 0L &&
                            accumulator.evidenceAgeMs <= EVENT_VERIFICATION_TTL_MS
                        ) {
                            addAll(accumulator.evidenceTexts)
                        }
                    }

            val contextVerificationText =
                contextVerificationTexts
                    .joinToString(" | ")
                    .take(VERIFICATION_TEXT_MAX_CHARS)

            if (context.contextId in interactionIds) {
                verificationVisible.addAll(contextVerificationTexts)
            }

            val contextTitle =
                safeText(
                    context.title
                )

            val readableTextCount =
                accumulator.texts
                    .count { text ->
                        val clean =
                            safeText(
                                text
                            )

                        clean.isNotBlank() &&
                            (
                                contextTitle.isBlank() ||
                                    clean != contextTitle
                                )
                    }

            val liveReadableTextCount =
                accumulator.liveTexts
                    .count { text ->
                        val clean =
                            safeText(
                                text
                            )

                        clean.isNotBlank() &&
                            (
                                contextTitle.isBlank() ||
                                    clean != contextTitle
                                )
                    }

            val evidenceReadableTextCount =
                accumulator.evidenceTexts
                    .count { text ->
                        safeText(
                            text
                        )
                            .isNotBlank()
                    }

            val contextNodeCount =
                accumulator.nodes
                    .length()

            val hasFreshEventEvidence =
                accumulator.evidenceAgeMs >= 0L &&
                    accumulator.evidenceAgeMs <= EVENT_VERIFICATION_TTL_MS

            val contextContentState =
                when {
                    liveReadableTextCount >= 2 ->
                        "readable"

                    liveReadableTextCount == 1 ->
                        "partial"

                    // Fresh event evidence is useful, but it is deliberately
                    // weaker than a live Accessibility tree. It can support a
                    // cautious partial observation, never a full readable claim.
                    hasFreshEventEvidence &&
                        evidenceReadableTextCount >= 1 ->
                        "partial"

                    contextNodeCount > 1 ->
                        "structure_only"

                    else ->
                        "unavailable"
                }

            val contextFailureReason =
                when {
                    contextContentState == "readable" ->
                        ""

                    contextContentState == "partial" &&
                        liveReadableTextCount > 0 ->
                        "limited_live_text"

                    contextContentState == "partial" &&
                        hasFreshEventEvidence ->
                        "event_evidence_only"

                    contextNodeCount > 1 ->
                        "structure_without_readable_text"

                    context.root == null ->
                        "root_unavailable"

                    else ->
                        "sparse_root"
                }

            windowsJson.put(
                JSONObject()
                    .put("context_id", context.contextId)
                    .put("window_id", context.windowId)
                    .put("package", context.packageName)
                    .put("root_class", context.className)
                    .put("title", context.title)
                    .put("type", context.type)
                    .put("type_name", windowTypeName(context.type))
                    .put("layer", context.layer)
                    .put("active", context.active)
                    .put("focused", context.focused)
                    .put("picture_in_picture", context.pictureInPicture)
                    .put("source", context.source)
                    .put("acquisition_source", context.source)
                    .put("interaction_rank", context.rank)
                    .put("interaction_context", context.contextId in interactionIds)
                    .put("occlusion_ratio", contextOcclusionRatio(context, allContexts))
                    .put("bounds", rectToJson(context.bounds))
                    .put("evidence_age_ms", accumulator.evidenceAgeMs)
                    .put("node_count", contextNodeCount)
                    .put("live_readable_text_count", liveReadableTextCount)
                    .put("evidence_readable_text_count", evidenceReadableTextCount)
                    .put("readable_text_count", readableTextCount)
                    .put("content_state", contextContentState)
                    .put(
                        "content_available",
                        contextContentState == "readable" ||
                            contextContentState == "partial"
                    )
                    .put("failure_reason", contextFailureReason)
                    .put("verification_text", contextVerificationText)
                    .put(
                        "semantic_surface",
                        settingsSemanticSurface(
                            packageName = context.packageName,
                            title = context.title,
                            verificationText = contextVerificationText
                        )
                    )
                    .put(
                        "semantic_surface_confidence",
                        settingsSemanticConfidence(
                            packageName = context.packageName,
                            title = context.title,
                            verificationText = contextVerificationText
                        )
                    )
                    .put("visible_text", visibleArray)
            )

            if (context.contextId in interactionIds) {
                for (index in 0 until accumulator.nodes.length()) {
                    topNodes.put(
                        accumulator.nodes.optJSONObject(index)
                    )
                }
            }
        }

        val visibleText = JSONArray()
        for (text in topVisible) visibleText.put(text)

        val allVisibleText = JSONArray()
        for (text in allVisible) allVisibleText.put(text)

        val packageArray = JSONArray()
        for (pkg in packages) packageArray.put(pkg)

        val verificationText =
            verificationVisible
                .joinToString(" | ")
                .take(VERIFICATION_TEXT_MAX_CHARS)

        val primaryAccumulator =
            primary
                ?.let { primaryContext ->
                    accumulators[
                        primaryContext.contextId
                    ]
                }

        val primaryTitle =
            safeText(
                primary?.title
                    .orEmpty()
            )

        val primaryReadableTextCount =
            primaryAccumulator
                ?.texts
                ?.count { text ->
                    val clean =
                        safeText(
                            text
                        )

                    clean.isNotBlank() &&
                        (
                            primaryTitle.isBlank() ||
                                clean != primaryTitle
                            )
                }
                ?: 0

        val primaryLiveReadableTextCount =
            primaryAccumulator
                ?.liveTexts
                ?.count { text ->
                    val clean =
                        safeText(
                            text
                        )

                    clean.isNotBlank() &&
                        (
                            primaryTitle.isBlank() ||
                                clean != primaryTitle
                            )
                }
                ?: 0

        val primaryEvidenceReadableTextCount =
            primaryAccumulator
                ?.evidenceTexts
                ?.count { text ->
                    safeText(
                        text
                    )
                        .isNotBlank()
                }
                ?: 0

        val primaryFreshEvidence =
            primaryAccumulator != null &&
                primaryAccumulator.evidenceAgeMs >= 0L &&
                primaryAccumulator.evidenceAgeMs <= EVENT_VERIFICATION_TTL_MS

        val primaryNodeCount =
            primaryAccumulator
                ?.nodes
                ?.length()
                ?: 0

        val primaryContentState =
            when {
                primary == null ->
                    "unknown"

                primaryLiveReadableTextCount >= 2 ->
                    "readable"

                primaryLiveReadableTextCount == 1 ->
                    "partial"

                primaryFreshEvidence &&
                    primaryEvidenceReadableTextCount >= 1 ->
                    "partial"

                primaryNodeCount > 1 ->
                    "structure_only"

                else ->
                    "unavailable"
            }

        val primaryFailureReason =
            when {
                primaryContentState == "readable" ->
                    ""

                primaryContentState == "partial" &&
                    primaryLiveReadableTextCount > 0 ->
                    "limited_live_text"

                primaryContentState == "partial" &&
                    primaryFreshEvidence ->
                    "event_evidence_only"

                primaryNodeCount > 1 ->
                    "structure_without_readable_text"

                primary?.root == null ->
                    "root_unavailable"

                else ->
                    "sparse_root"
            }

        var readableWindowCount = 0
        var partialWindowCount = 0
        var structureOnlyWindowCount = 0
        var unavailableWindowCount = 0

        for (index in 0 until windowsJson.length()) {
            when (
                windowsJson
                    .optJSONObject(index)
                    ?.optString("content_state")
            ) {
                "readable" -> readableWindowCount++
                "partial" -> partialWindowCount++
                "structure_only" -> structureOnlyWindowCount++
                "unavailable" -> unavailableWindowCount++
            }
        }

        val primaryContentAvailable =
            primaryContentState == "readable" ||
                primaryContentState == "partial"

        return JSONObject()
            .put("success", true)
            .put("snapshot_success", true)
            .put("understanding_success", primaryContentAvailable)
            .put("content_contract_version", 2)
            .put("content_status", primaryContentState)
            .put("package", primary?.packageName.orEmpty())
            .put("root_class", primary?.className.orEmpty())
            .put("primary_context_id", primary?.contextId.orEmpty())
            .put("primary_window_title", primary?.title.orEmpty())
            .put("interaction_package", primary?.packageName.orEmpty())
            .put("primary_content_state", primaryContentState)
            .put("primary_content_available", primaryContentAvailable)
            .put("primary_failure_reason", primaryFailureReason)
            .put("primary_acquisition_source", primary?.source.orEmpty())
            .put("primary_readable_text_count", primaryReadableTextCount)
            .put("primary_live_readable_text_count", primaryLiveReadableTextCount)
            .put("primary_evidence_readable_text_count", primaryEvidenceReadableTextCount)
            .put("primary_node_count", primaryNodeCount)
            .put("window_context_mode", "v7_0_verified_owner_handoff")
            .put("window_count", allContexts.size)
            .put("raw_window_count", safeWindowCount())
            .put("readable_window_count", readableWindowCount)
            .put("partial_window_count", partialWindowCount)
            .put("structure_only_window_count", structureOnlyWindowCount)
            .put("unavailable_window_count", unavailableWindowCount)
            .put("evidence_context_count", evidenceContextCount)
            .put("packages", packageArray)
            .put("visible_text", visibleText)
            .put("all_visible_text", allVisibleText)
            .put("verification_text", verificationText)
            .put("node_count", topNodes.length())
            .put("nodes", topNodes)
            .put("windows", windowsJson)
            .put("event_package", lastEventPackage)
            .put("event_class", lastEventClass)
            .put("event_window_id", lastEventWindowId)
            .put("foreground_owner_package", lastForegroundOwnerPackage)
            .put("foreground_owner_window_id", lastForegroundOwnerWindowId)
            .put("foreground_owner_source", lastForegroundOwnerSource)
            .put(
                "foreground_owner_age_ms",
                if (lastForegroundOwnerTime > 0L) {
                    (System.currentTimeMillis() - lastForegroundOwnerTime)
                        .coerceAtLeast(0L)
                } else {
                    -1L
                }
            )
            .put(
                "event_age_ms",
                (System.currentTimeMillis() - lastEventTime)
                    .coerceAtLeast(0L)
            )
            .put(
                "snapshot_duration_ms",
                (SystemClock.elapsedRealtime() - snapshotStartedAt)
                    .coerceAtLeast(0L)
            )
    }

    fun screenSignature():
        String {

        val snapshot =
            buildScreenSnapshot(
                maxNodes = 80,
                maxChars = 6000
            )

        if (!snapshot.optBoolean("success", false)) {
            return "unavailable"
        }

        val ownAppState =
            snapshot
                .optString(
                    "own_app_state_fingerprint"
                )
                .trim()

        if (ownAppState.isNotBlank()) {
            return (
                snapshot.optString("package") +
                    "|own_app|" +
                    ownAppState
                )
                .take(7000)
                .hashCode()
                .toString()
        }

        return (
            snapshot.optString("primary_context_id") +
                "|" +
                snapshot.optString("package") +
                "|" +
                snapshot.optString("verification_text")
            )
            .take(7000)
            .hashCode()
            .toString()
    }

    private fun captureEventEvidence(
        event: AccessibilityEvent
    ) {

        val now =
            System.currentTimeMillis()

        val eventPackage =
            event.packageName
                ?.toString()
                .orEmpty()

        if (eventPackage.isBlank()) {
            pruneEventEvidence(
                now
            )
            return
        }

        val ownForegroundApplicationEvent =
            eventPackage == packageName &&
                isOwnForegroundApplicationEvent(event)

        if (
            eventPackage == packageName &&
            !ownForegroundApplicationEvent
        ) {
            pruneEventEvidence(
                now
            )
            return
        }

        // Normal events keep only the SOURCE node. Android Settings is a bounded
        // exception because Samsung One UI can expose a package-bearing shell while
        // the real App Info/detail text exists only below the event source/parents.
        // Recovery is structural-event-only and throttled; it never runs for AYANA,
        // Chrome, launchers, normal text changes, or every accessibility callback.
        val source =
            try {
                event.source
            } catch (_: Exception) {
                null
            }

        val nodes =
            JSONArray()

        if (source != null) {

            val settingsRecovery =
                shouldCaptureSettingsStructuralRecovery(
                    eventPackage = eventPackage,
                    eventType = event.eventType,
                    now = now
                )

            val ownAppRecovery =
                ownForegroundApplicationEvent &&
                    shouldCaptureOwnAppStructuralRecovery(
                        eventType = event.eventType,
                        now = now
                    )

            if (settingsRecovery || ownAppRecovery) {
                if (settingsRecovery) {
                    lastSettingsStructuralRecoveryAt =
                        now
                }

                if (ownAppRecovery) {
                    lastOwnAppStructuralRecoveryAt =
                        now
                }

                val recoveryRoot =
                    try {
                        highestUsableEventRoot(
                            source
                        )
                    } catch (_: Exception) {
                        source
                    }

                val recoveredNodes =
                    try {
                        snapshotEventTree(
                            root = recoveryRoot,
                            maxNodes =
                                if (ownAppRecovery) {
                                    OWN_APP_EVENT_RECOVERY_NODE_LIMIT
                                } else {
                                    SETTINGS_EVENT_RECOVERY_NODE_LIMIT
                                },
                            maxChars =
                                if (ownAppRecovery) {
                                    OWN_APP_EVENT_RECOVERY_CHAR_LIMIT
                                } else {
                                    SETTINGS_EVENT_RECOVERY_CHAR_LIMIT
                                }
                        )
                    } catch (_: Exception) {
                        JSONArray()
                    }

                val evidenceSource =
                    if (ownAppRecovery) {
                        "own_app_structural_recovery"
                    } else {
                        "settings_structural_recovery"
                    }

                for (index in 0 until recoveredNodes.length()) {
                    recoveredNodes
                        .optJSONObject(index)
                        ?.let { node ->
                            nodes.put(
                                JSONObject(node.toString())
                                    .put(
                                        "evidence_source",
                                        evidenceSource
                                    )
                            )
                        }
                }
            }

            if (nodes.length() == 0) {
                try {
                    nodes.put(
                        nodeToJson(
                            node = source,
                            depth = 0,
                            index = 1
                        )
                            .put(
                                "evidence_source",
                                "accessibility_event_source"
                            )
                    )
                } catch (_: Exception) {
                }
            }
        }

        val texts =
            linkedSetOf<String>()

        try {
            for (
                item in
                event.text
            ) {

                val value =
                    safeText(
                        item
                            ?.toString()
                            .orEmpty()
                    )

                if (
                    value.isNotBlank()
                ) {
                    texts.add(
                        value
                    )
                }
            }
        } catch (_: Exception) {
        }

        val eventDescription =
            try {
                safeText(
                    event.contentDescription
                        ?.toString()
                        .orEmpty()
                )
            } catch (_: Exception) {
                ""
            }

        if (
            eventDescription.isNotBlank()
        ) {
            texts.add(
                eventDescription
            )
        }

        val nodeTexts =
            collectVisibleTexts(
                nodes
            )

        for (
            index in
            0 until nodeTexts.length()
        ) {

            val value =
                nodeTexts
                    .optString(
                        index
                    )
                    .trim()

            if (
                value.isNotBlank()
            ) {
                texts.add(
                    value
                )
            }

            if (
                texts.size >=
                EVENT_EVIDENCE_TEXT_LIMIT
            ) {
                break
            }
        }

        if (
            texts.isEmpty() &&
            nodes.length() ==
                0
        ) {
            pruneEventEvidence(
                now
            )
            return
        }

        val eventWindowId =
            try {
                event.windowId
            } catch (_: Exception) {
                -1
            }

        val evidenceBounds =
            Rect().apply {
                if (source != null) {
                    try {
                        source.getBoundsInScreen(this)
                    } catch (_: Exception) {
                    }
                }
            }

        val evidence =
            EventEvidence(
                at = now,
                windowId = eventWindowId,
                packageName =
                    eventPackage,
                className =
                    event.className
                        ?.toString()
                        .orEmpty(),
                eventType =
                    event.eventType,
                bounds = evidenceBounds,
                nodes =
                    nodes,
                visibleText =
                    texts
                        .take(
                            EVENT_EVIDENCE_TEXT_LIMIT
                        ),
                origin =
                    "accessibility_event"
            )

        synchronized(
            eventEvidenceLock
        ) {

            pruneEventEvidenceLocked(
                now
            )

            recentEventEvidence.addFirst(
                evidence
            )

            while (
                recentEventEvidence.size >
                EVENT_EVIDENCE_MAX_RECORDS
            ) {
                recentEventEvidence.removeLast()
            }
        }
    }


    private fun isForegroundOwnershipEvent(
        eventType: Int
    ): Boolean {

        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
    }

    /**
     * v6.9 ownership proof gate. TYPE_WINDOWS_CHANGED can be emitted by One UI Home or
     * SystemUI while another application still owns the visible screen. Such shell events
     * are not ownership proof. Prefer an event-window that Android itself marks as an
     * active/focused TYPE_APPLICATION. During Samsung transition gaps where getWindows()
     * omits the real application window, accept only a same-package event source whose
     * highest usable root covers a substantial part of the display.
     */
    private fun shouldAcceptForegroundOwnershipEvent(
        event: AccessibilityEvent,
        eventPackage: String
    ): Boolean {

        if (
            eventPackage.isBlank() ||
            !isForegroundOwnershipEvent(
                event.eventType
            )
        ) {
            return false
        }

        if (
            eventPackage == packageName
        ) {
            return isOwnForegroundApplicationEvent(
                event
            )
        }

        val eventWindowId =
            try {
                event.windowId
            } catch (_: Exception) {
                -1
            }

        val matchingWindow =
            try {
                windows.firstOrNull { window ->
                    eventWindowId >= 0 &&
                        window.id == eventWindowId
                }
            } catch (_: Exception) {
                null
            }

        if (
            matchingWindow != null
        ) {
            val type =
                try {
                    matchingWindow.type
                } catch (_: Exception) {
                    -1
                }

            val active =
                try {
                    matchingWindow.isActive
                } catch (_: Exception) {
                    false
                }

            val focused =
                try {
                    matchingWindow.isFocused
                } catch (_: Exception) {
                    false
                }

            val rootPackage =
                try {
                    matchingWindow.root
                        ?.packageName
                        ?.toString()
                        .orEmpty()
                } catch (_: Exception) {
                    ""
                }

            if (
                type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                (active || focused) &&
                (
                    rootPackage.isBlank() ||
                        rootPackage == eventPackage
                    )
            ) {
                return true
            }

            // A package-bearing launcher/SystemUI TYPE_SYSTEM shell must never replace a
            // proven application owner merely because the shell emitted WINDOWS_CHANGED.
            if (
                type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                (
                    isKnownLauncherPackage(
                        eventPackage
                    ) ||
                        eventPackage == "com.android.systemui"
                    )
            ) {
                return false
            }
        }

        if (
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return false
        }

        val source =
            try {
                event.source
            } catch (_: Exception) {
                null
            }
                ?: return false

        if (
            source.packageName
                ?.toString()
                .orEmpty() != eventPackage
        ) {
            return false
        }

        val bounds =
            Rect()

        try {
            highestUsableEventRoot(
                source
            ).getBoundsInScreen(
                bounds
            )
        } catch (_: Exception) {
            try {
                source.getBoundsInScreen(
                    bounds
                )
            } catch (_: Exception) {
                return false
            }
        }

        val screenArea =
            resources.displayMetrics.widthPixels
                .coerceAtLeast(1)
                .toLong() *
                resources.displayMetrics.heightPixels
                    .coerceAtLeast(1)
                    .toLong()

        val sourceArea =
            bounds.width()
                .coerceAtLeast(0)
                .toLong() *
                bounds.height()
                    .coerceAtLeast(0)
                    .toLong()

        return sourceArea > 0L &&
            sourceArea.toDouble() /
                screenArea.toDouble() >=
                FOREGROUND_OWNER_SOURCE_MIN_AREA_RATIO
    }

    /**
     * v6.8 cross-app invariant:
     * the in-process AYANA View bridge is an acquisition optimization, never an
     * authority over Android foreground ownership. Foreground ownership is sticky
     * state, not a time-limited lease: after a high-confidence external window/focus
     * event, the bridge stays vetoed until a later high-confidence ownership event
     * explicitly returns ownership to AYANA. Live Accessibility windows remain an
     * independent second veto.
     *
     * This deliberately fails closed if Android misses a return event: AYANA can still
     * read its own UI through normal Accessibility, whereas re-enabling the in-process
     * bridge on a timer can mask a real external screen and create false terminal truth.
     */
    private fun shouldUseOwnAppSemanticBridge(): Boolean {

        if (
            !MainActivity
                .isOwnAppSemanticBridgeActive()
        ) {
            return false
        }

        val ownerPackage =
            lastForegroundOwnerPackage
                .trim()

        if (
            ownerPackage.isNotBlank() &&
            ownerPackage != packageName
        ) {
            return false
        }

        val contexts =
            try {
                resolveWindowContexts()
            } catch (_: Exception) {
                emptyList()
            }

        val externalForeground =
            contexts.any { context ->
                context.packageName.isNotBlank() &&
                    context.packageName != packageName &&
                    context.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (context.focused || context.active)
            }

        if (externalForeground) {
            return false
        }

        return true
    }

    private fun isOwnForegroundApplicationEvent(
        event: AccessibilityEvent
    ): Boolean {

        if (
            event.packageName
                ?.toString()
                .orEmpty() !=
            packageName
        ) {
            return false
        }

        val eventWindowId =
            try {
                event.windowId
            } catch (_: Exception) {
                -1
            }

        val matchingWindow =
            try {
                windows.firstOrNull { window ->
                    eventWindowId >= 0 &&
                        window.id == eventWindowId
                }
            } catch (_: Exception) {
                null
            }

        if (matchingWindow != null) {
            val type =
                try { matchingWindow.type } catch (_: Exception) { -1 }

            if (
                type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            ) {
                return false
            }

            if (type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                return false
            }

            val active =
                try { matchingWindow.isActive } catch (_: Exception) { false }

            val focused =
                try { matchingWindow.isFocused } catch (_: Exception) { false }

            if (!active && !focused) {
                return false
            }

            val rootPackage =
                try {
                    matchingWindow.root
                        ?.packageName
                        ?.toString()
                        .orEmpty()
                } catch (_: Exception) {
                    ""
                }

            return rootPackage.isBlank() ||
                rootPackage == packageName
        }

        // Very short transition fallback: allow a self event only when its source
        // itself belongs to AYANA and occupies a substantial application-sized area.
        // Small overlay-like sources remain excluded.
        val source =
            try { event.source } catch (_: Exception) { null }
                ?: return false

        if (
            source.packageName
                ?.toString()
                .orEmpty() !=
            packageName
        ) {
            return false
        }

        val bounds = Rect()
        try {
            highestUsableEventRoot(source)
                .getBoundsInScreen(bounds)
        } catch (_: Exception) {
            try {
                source.getBoundsInScreen(bounds)
            } catch (_: Exception) {
                return false
            }
        }

        val screenArea =
            resources.displayMetrics.widthPixels
                .coerceAtLeast(1)
                .toLong() *
                resources.displayMetrics.heightPixels
                    .coerceAtLeast(1)
                    .toLong()

        val area =
            bounds.width().coerceAtLeast(0).toLong() *
                bounds.height().coerceAtLeast(0).toLong()

        return area > 0L &&
            area.toDouble() / screenArea.toDouble() >=
                OWN_APP_MIN_APPLICATION_AREA_RATIO
    }

    private fun shouldCaptureOwnAppStructuralRecovery(
        eventType: Int,
        now: Long
    ): Boolean {

        val structural =
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED

        if (!structural) {
            return false
        }

        return now -
            lastOwnAppStructuralRecoveryAt >=
            OWN_APP_EVENT_RECOVERY_THROTTLE_MS
    }

    private fun shouldCaptureSettingsStructuralRecovery(
        eventPackage: String,
        eventType: Int,
        now: Long
    ): Boolean {

        if (
            eventPackage != SETTINGS_PACKAGE
        ) {
            return false
        }

        val structural =
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED

        if (!structural) {
            return false
        }

        return now -
            lastSettingsStructuralRecoveryAt >=
            SETTINGS_EVENT_RECOVERY_THROTTLE_MS
    }

    private fun highestUsableEventRoot(
        source: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {

        var current =
            source

        var best =
            source

        val sourceWindowId =
            try {
                source.windowId
            } catch (_: Exception) {
                -1
            }

        var depth =
            0

        while (
            depth <
            EVENT_EVIDENCE_PARENT_LIMIT
        ) {

            val parent =
                try {
                    current.parent
                } catch (_: Exception) {
                    null
                }
                    ?: break

            val parentWindowId =
                try {
                    parent.windowId
                } catch (_: Exception) {
                    sourceWindowId
                }

            if (
                sourceWindowId >=
                0 &&
                parentWindowId >=
                0 &&
                parentWindowId !=
                sourceWindowId
            ) {
                break
            }

            best =
                parent

            current =
                parent

            depth++
        }

        return best
    }

    private fun snapshotEventTree(
        root: AccessibilityNodeInfo,
        maxNodes: Int,
        maxChars: Int
    ): JSONArray {

        val nodes =
            JSONArray()

        val queue =
            ArrayDeque<
                Pair<
                    AccessibilityNodeInfo,
                    Int
                >
            >()

        queue.add(
            root to
                0
        )

        var visited =
            0

        var charCount =
            0

        while (
            queue.isNotEmpty() &&
            visited <
            maxNodes &&
            charCount <
            maxChars
        ) {

            val (
                node,
                depth
            ) =
                queue.removeFirst()

            visited++

            val item =
                try {
                    nodeToJson(
                        node =
                            node,
                        depth =
                            depth,
                        index =
                            visited
                    )
                        .put(
                            "evidence_source",
                            "accessibility_event"
                        )
                } catch (_: Exception) {
                    null
                }

            if (
                item !=
                null
            ) {

                val serialized =
                    item.toString()

                if (
                    charCount +
                    serialized.length <=
                    maxChars
                ) {
                    nodes.put(
                        item
                    )

                    charCount +=
                        serialized.length
                }
            }

            val childCount =
                try {
                    node.childCount
                } catch (_: Exception) {
                    0
                }

            for (
                index in
                0 until childCount
            ) {

                val child =
                    childWithPrefetch(
                        node,
                        index
                    )
                        ?: continue

                queue.add(
                    child to
                        (
                            depth +
                                1
                            )
                )
            }
        }

        return nodes
    }

    private fun tapBoundsCenter(
        bounds: Rect
    ): Boolean {

        if (
            bounds.width() <=
            0 ||
            bounds.height() <=
            0
        ) {
            return false
        }

        val x =
            bounds.centerX()

        val y =
            bounds.centerY()

        val path =
            Path()
                .apply {
                    moveTo(
                        x.toFloat(),
                        y.toFloat()
                    )
                }

        val gesture =
            GestureDescription
                .Builder()
                .addStroke(
                    GestureDescription
                        .StrokeDescription(
                            path,
                            0,
                            80
                        )
                )
                .build()

        return dispatchGesture(
            gesture,
            null,
            null
        )
    }

    private fun pruneEventEvidence(
        now: Long =
            System.currentTimeMillis()
    ) {

        synchronized(
            eventEvidenceLock
        ) {
            pruneEventEvidenceLocked(
                now
            )
        }
    }

    private fun pruneEventEvidenceLocked(
        now: Long
    ) {

        while (
            recentEventEvidence.isNotEmpty()
        ) {

            val oldest =
                recentEventEvidence.last()

            if (
                now -
                oldest.at <=
                EVENT_EVIDENCE_TTL_MS
            ) {
                break
            }

            recentEventEvidence.removeLast()
        }
    }

    /**
     * Samsung tablet Settings can expose the navigation pane and the detail pane
     * through more than one interactive accessibility root. Keep every relevant
     * application root, rank the most likely foreground roots first, and let
     * snapshot/search logic inspect them together. This is stricter than using
     * rootInActiveWindow alone and prevents false verification failures when the
     * requested detail page is visible in a sibling Settings root.
     */
    private fun resolveWindowContexts():
        List<WindowContext> {

        val contexts =
            mutableListOf<WindowContext>()

        val seen =
            linkedSetOf<String>()

        val snapshotWindows =
            try {
                windows
            } catch (_: Exception) {
                emptyList()
            }

        var maxLayer = 0

        for (window in snapshotWindows) {
            val bounds = Rect()
            try {
                window.getBoundsInScreen(bounds)
            } catch (_: Exception) {
            }

            val root =
                prefetchedWindowRoot(
                    window
                )

            val windowTitle =
                try {
                    safeText(
                        window.title
                            ?.toString()
                            .orEmpty()
                    )
                } catch (_: Exception) {
                    ""
                }

            val rootPackage =
                root?.packageName
                    ?.toString()
                    .orEmpty()

            val rootClass =
                root?.className
                    ?.toString()
                    .orEmpty()

            val windowId =
                try { window.id } catch (_: Exception) { -1 }

            val type =
                try { window.type } catch (_: Exception) { -1 }

            val layer =
                try { window.layer } catch (_: Exception) { 0 }

            maxLayer = maxOf(maxLayer, layer)

            val active =
                try { window.isActive } catch (_: Exception) { false }

            val focused =
                try { window.isFocused } catch (_: Exception) { false }

            val pip =
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        window.isInPictureInPictureMode
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }

            if (
                isLikelyOwnOverlayWindow(
                    packageName = rootPackage,
                    bounds = bounds,
                    active = active,
                    focused = focused,
                    type = type
                )
            ) {
                continue
            }

            val contextId =
                liveContextId(
                    windowId = windowId,
                    packageName = rootPackage,
                    bounds = bounds
                )

            if (!seen.add(contextId)) {
                continue
            }

            contexts.add(
                WindowContext(
                    contextId = contextId,
                    windowId = windowId,
                    packageName = rootPackage,
                    className = rootClass,
                    type = type,
                    layer = layer,
                    active = active,
                    focused = focused,
                    pictureInPicture = pip,
                    bounds = Rect(bounds),
                    root = root,
                    source = "windows",
                    rank = windowInteractionRank(
                        windowId = windowId,
                        packageName = rootPackage,
                        type = type,
                        layer = layer,
                        active = active,
                        focused = focused,
                        pictureInPicture = pip
                    ),
                    title = windowTitle
                )
            )
        }

        val activeRoot =
            prefetchedActiveWindowRoot()

        if (activeRoot != null) {
            val activeWindowId =
                try { activeRoot.windowId } catch (_: Exception) { -1 }

            val activePackage =
                activeRoot.packageName
                    ?.toString()
                    .orEmpty()

            val matchingIndex =
                contexts.indexOfFirst { context ->
                    (
                        activeWindowId >= 0 &&
                        context.windowId == activeWindowId
                    ) ||
                        context.root === activeRoot
                }

            // v4.0 treated "same window id already present" as sufficient and
            // discarded rootInActiveWindow. On Samsung/Chrome the root obtained
            // from AccessibilityWindowInfo may be only a package-bearing shell,
            // while rootInActiveWindow for the SAME window can contain the real
            // descendant tree. Upgrade the existing context when the active root
            // is measurably richer instead of throwing it away.
            if (matchingIndex >= 0) {
                val existing =
                    contexts[matchingIndex]

                val existingScore =
                    rootContentScore(
                        existing.root
                    )

                val activeScore =
                    rootContentScore(
                        activeRoot
                    )

                if (activeScore > existingScore) {
                    val upgradedPackage =
                        activePackage
                            .ifBlank {
                                existing.packageName
                            }

                    contexts[matchingIndex] =
                        existing.copy(
                            packageName = upgradedPackage,
                            className = activeRoot.className
                                ?.toString()
                                .orEmpty()
                                .ifBlank {
                                    existing.className
                                },
                            active = true,
                            focused = true,
                            root = activeRoot,
                            source = "active_root_upgrade",
                            rank = maxOf(
                                existing.rank,
                                windowInteractionRank(
                                    windowId = activeWindowId,
                                    packageName = upgradedPackage,
                                    type = existing.type,
                                    layer = existing.layer,
                                    active = true,
                                    focused = true,
                                    pictureInPicture = existing.pictureInPicture
                                )
                            )
                        )
                }

            } else {
                val bounds = Rect()
                try { activeRoot.getBoundsInScreen(bounds) } catch (_: Exception) { }

                if (
                    !isLikelyOwnOverlayWindow(
                        packageName = activePackage,
                        bounds = bounds,
                        active = true,
                        focused = true,
                        type = AccessibilityWindowInfo.TYPE_APPLICATION
                    )
                ) {
                    val contextId =
                        liveContextId(
                            windowId = activeWindowId,
                            packageName = activePackage,
                            bounds = bounds
                        )

                    contexts.add(
                        WindowContext(
                            contextId = contextId,
                            windowId = activeWindowId,
                            packageName = activePackage,
                            className = activeRoot.className
                                ?.toString()
                                .orEmpty(),
                            type = AccessibilityWindowInfo.TYPE_APPLICATION,
                            layer = maxLayer + 1,
                            active = true,
                            focused = true,
                            pictureInPicture = false,
                            bounds = Rect(bounds),
                            root = activeRoot,
                            source = "active_root",
                            rank = windowInteractionRank(
                                windowId = activeWindowId,
                                packageName = activePackage,
                                type = AccessibilityWindowInfo.TYPE_APPLICATION,
                                layer = maxLayer + 1,
                                active = true,
                                focused = true,
                                pictureInPicture = false
                            )
                        )
                    )
                }
            }
        }

        lastRootSource =
            when {
                contexts.isEmpty() -> "unavailable"
                contexts.size == 1 -> "single_window_context"
                else -> "multi_window_context"
            }

        return contexts
            .sortedByDescending { it.rank }
    }

    private fun primaryWindowContext(
        contexts: List<WindowContext>
    ): WindowContext? {

        if (contexts.isEmpty()) {
            return null
        }

        // v4.0: a blank focused shell is not allowed to outrank a real app.
        // Samsung/One UI can briefly expose an active/focused window with no
        // package/root text during fragment and multi-window transitions.
        // Prefer a package-bearing context first; fall back to a blank shell
        // only when Android exposes nothing else.
        val usable =
            contexts
                .filter {
                    it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                        it.packageName.isNotBlank()
                }

        return usable
            .filter { it.focused }
            .maxByOrNull { it.rank }
            ?: usable
                .filter { it.active }
                .maxByOrNull { it.rank }
            ?: usable
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .maxByOrNull { it.rank }
            ?: usable.maxByOrNull { it.rank }
            ?: contexts
                .filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .maxByOrNull { it.rank }
            ?: contexts.maxByOrNull { it.rank }
    }

    private fun interactionWindowContexts(
        contexts: List<WindowContext>
    ): List<WindowContext> {

        val primary =
            primaryWindowContext(contexts)
                ?: return emptyList()

        val primaryPackage =
            primary.packageName

        val result =
            contexts
                .filter { context ->
                    if (context.contextId == primary.contextId) {
                        return@filter true
                    }

                    if (primaryPackage.isBlank() || context.packageName != primaryPackage) {
                        return@filter false
                    }

                    if (context.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        return@filter false
                    }

                    // Fail closed for multiple independent windows that happen
                    // to belong to the same package (for example two Chrome
                    // freeform windows). A sibling joins the interaction group only
                    // when Android marks it active/focused or a fresh Accessibility
                    // event identifies that exact window as the current interaction
                    // surface. This still admits Samsung Settings detail panes via
                    // their event evidence without making every same-package window
                    // actionable.
                    val currentInteractionEvidence =
                        context.active ||
                            context.focused ||
                            context.source == "event_evidence" ||
                            (
                                context.windowId >= 0 &&
                                    context.windowId == lastEventWindowId &&
                                    System.currentTimeMillis() - lastEventTime <=
                                    EVENT_INTERACTION_FRESH_MS
                                )

                    if (!currentInteractionEvidence) {
                        return@filter false
                    }

                    val overlap =
                        overlapRatio(
                            context.bounds,
                            primary.bounds
                        )

                    // A lower-layer overlapping window is obscured background,
                    // even if it emitted a recent event. Adjacent/non-overlapping
                    // sibling panes may coexist in the current interaction group.
                    if (
                        overlap > 0.20 &&
                        context.layer < primary.layer
                    ) {
                        return@filter false
                    }

                    contextOcclusionRatio(
                        context,
                        contexts
                    ) < 0.80
                }
                .sortedByDescending { it.rank }

        return if (result.isNotEmpty()) {
            result
        } else {
            listOf(primary)
        }
    }

    private fun windowInteractionRank(
        windowId: Int,
        packageName: String,
        type: Int,
        layer: Int,
        active: Boolean,
        focused: Boolean,
        pictureInPicture: Boolean
    ): Int {

        var score =
            layer.coerceIn(-1000, 1000) * 10

        if (focused) score += 10000
        if (active) score += 7000
        if (type == AccessibilityWindowInfo.TYPE_APPLICATION) score += 2500
        if (packageName.isBlank()) score -= 12000
        if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) score -= 1800
        if (pictureInPicture) score -= 400
        if (windowId >= 0 && windowId == lastEventWindowId) score += 1800
        if (packageName.isNotBlank() && packageName == lastEventPackage) score += 600

        return score
    }

    private fun liveContextId(
        windowId: Int,
        packageName: String,
        bounds: Rect
    ): String {

        return if (windowId >= 0) {
            "w:$windowId:$packageName"
        } else {
            "w:x:$packageName:${bounds.left}:${bounds.top}:${bounds.right}:${bounds.bottom}"
        }
    }

    private fun isLikelyOwnOverlayWindow(
        packageName: String,
        bounds: Rect,
        active: Boolean,
        focused: Boolean,
        type: Int
    ): Boolean {

        if (packageName != this.packageName) {
            return false
        }

        if (active || focused) {
            return false
        }

        if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
            return true
        }

        val screenArea =
            resources.displayMetrics.widthPixels
                .coerceAtLeast(1)
                .toLong() *
                resources.displayMetrics.heightPixels
                    .coerceAtLeast(1)
                    .toLong()

        val area =
            bounds.width().coerceAtLeast(0).toLong() *
                bounds.height().coerceAtLeast(0).toLong()

        return area > 0L &&
            area.toDouble() / screenArea.toDouble() <= OWN_OVERLAY_MAX_AREA_RATIO
    }

    /**
     * v6.1 Universal Perception recovery.
     *
     * Some Samsung/Android windows expose a valid focused application shell while
     * ordinary Accessibility traversal contains only a title or a few structural
     * nodes. Settings already had a dedicated recovery lane; v6.1 extends the
     * same bounded idea to the current foreground application without broadening
     * evidence across packages/windows.
     *
     * This path runs only for a semantically sparse focused application root and
     * is throttled. AYANA's own overlay events remain ignored; self-package
     * evidence is admitted only when it was generated here for the exact live
     * application window.
     */
    private fun maybeCaptureForegroundOnDemandEvidence(
        liveContexts: List<WindowContext>
    ) {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }

        val target =
            primaryWindowContext(
                liveContexts
            )
                ?.takeIf { context ->
                    context.packageName.isNotBlank() &&
                        context.packageName != SETTINGS_PACKAGE &&
                        context.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        (
                            context.active ||
                                context.focused
                            )
                }
                ?: return

        val probe =
            rootSemanticProbe(
                target.root
            )

        if (
            probe.readableTextCount >= 2 &&
            (
                probe.nodeCount >= 6 ||
                    probe.editableCount > 0 ||
                    probe.clickableCount > 0
                )
        ) {
            return
        }

        val nowElapsed =
            SystemClock.elapsedRealtime()

        val recoveryKey =
            "${target.contextId}|${target.packageName}"

        if (
            recoveryKey == lastForegroundSnapshotRecoveryKey &&
            nowElapsed -
                lastForegroundSnapshotRecoveryAt <
                FOREGROUND_SNAPSHOT_RECOVERY_THROTTLE_MS
        ) {
            return
        }

        val nowWall =
            System.currentTimeMillis()

        val alreadyFresh =
            synchronized(
                eventEvidenceLock
            ) {
                recentEventEvidence.any { evidence ->
                    evidence.origin ==
                        "foreground_on_demand_prefetch" &&
                        evidence.packageName ==
                        target.packageName &&
                        evidence.windowId ==
                        target.windowId &&
                        nowWall - evidence.at <=
                        FOREGROUND_SNAPSHOT_FRESH_EVIDENCE_MS &&
                        (
                            evidence.visibleText.size >= 2 ||
                                evidence.nodes.length() >=
                                FOREGROUND_SNAPSHOT_FRESH_MIN_NODES
                            )
                }
            }

        if (alreadyFresh) {
            return
        }

        lastForegroundSnapshotRecoveryAt =
            nowElapsed

        lastForegroundSnapshotRecoveryKey =
            recoveryKey

        val strategy =
            AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
                AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE

        val candidates =
            mutableListOf<AccessibilityNodeInfo>()

        try {
            windows
                .firstOrNull { window ->
                    try {
                        window.id == target.windowId
                    } catch (_: Exception) {
                        false
                    }
                }
                ?.getRoot(
                    strategy
                )
                ?.let { root ->
                    val rootPackage =
                        root.packageName
                            ?.toString()
                            .orEmpty()

                    if (
                        rootPackage ==
                        target.packageName
                    ) {
                        candidates.add(
                            root
                        )
                    }
                }
        } catch (_: Exception) {
        }

        try {
            getRootInActiveWindow(
                strategy
            )
                ?.let { root ->
                    val rootPackage =
                        root.packageName
                            ?.toString()
                            .orEmpty()

                    val rootWindowId =
                        try {
                            root.windowId
                        } catch (_: Exception) {
                            -1
                        }

                    if (
                        rootPackage ==
                        target.packageName &&
                        (
                            target.windowId < 0 ||
                                rootWindowId < 0 ||
                                rootWindowId ==
                                target.windowId
                            )
                    ) {
                        candidates.add(
                            root
                        )
                    }
                }
        } catch (_: Exception) {
        }

        target.root
            ?.let { root ->
                if (
                    root.packageName
                        ?.toString()
                        .orEmpty() ==
                    target.packageName
                ) {
                    candidates.add(
                        root
                    )
                }
            }

        var bestNodes =
            JSONArray()

        var bestTexts =
            emptyList<String>()

        var bestScore =
            -1

        for (
            root in
            candidates.distinctBy { candidate ->
                val id =
                    try {
                        candidate.windowId
                    } catch (_: Exception) {
                        -1
                    }

                "$id|${System.identityHashCode(candidate)}"
            }
        ) {
            val nodes =
                try {
                    snapshotEventTree(
                        root = root,
                        maxNodes = FOREGROUND_SNAPSHOT_RECOVERY_NODE_LIMIT,
                        maxChars = FOREGROUND_SNAPSHOT_RECOVERY_CHAR_LIMIT
                    )
                } catch (_: Exception) {
                    JSONArray()
                }

            val textArray =
                collectVisibleTexts(
                    nodes
                )

            val texts =
                mutableListOf<String>()

            for (
                index in
                0 until textArray.length()
            ) {
                val value =
                    safeText(
                        textArray.optString(
                            index
                        )
                    )

                if (
                    value.isNotBlank() &&
                    value !in texts
                ) {
                    texts.add(
                        value
                    )
                }

                if (
                    texts.size >=
                    EVENT_EVIDENCE_TEXT_LIMIT
                ) {
                    break
                }
            }

            var editableCount =
                0

            var clickableCount =
                0

            for (
                index in
                0 until nodes.length()
            ) {
                val node =
                    nodes.optJSONObject(
                        index
                    )
                        ?: continue

                if (
                    node.optBoolean(
                        "editable",
                        false
                    )
                ) {
                    editableCount++
                }

                if (
                    node.optBoolean(
                        "clickable",
                        false
                    )
                ) {
                    clickableCount++
                }
            }

            val score =
                texts.size * 100 +
                    editableCount * 30 +
                    clickableCount * 8 +
                    nodes.length()

            if (score > bestScore) {
                bestScore =
                    score
                bestNodes =
                    nodes
                bestTexts =
                    texts
            }
        }

        if (
            bestNodes.length() == 0 &&
            bestTexts.isEmpty()
        ) {
            return
        }

        for (
            index in
            0 until bestNodes.length()
        ) {
            bestNodes
                .optJSONObject(
                    index
                )
                ?.put(
                    "evidence_source",
                    "foreground_on_demand_prefetch"
                )
        }

        val evidence =
            EventEvidence(
                at = nowWall,
                windowId = target.windowId,
                packageName = target.packageName,
                className = target.className,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                bounds = Rect(target.bounds),
                nodes = bestNodes,
                visibleText = bestTexts,
                origin = "foreground_on_demand_prefetch"
            )

        synchronized(
            eventEvidenceLock
        ) {
            pruneEventEvidenceLocked(
                nowWall
            )

            recentEventEvidence.addFirst(
                evidence
            )

            while (
                recentEventEvidence.size >
                EVENT_EVIDENCE_MAX_RECORDS
            ) {
                recentEventEvidence.removeLast()
            }
        }
    }

    /**
     * True only when fresh Settings evidence is rich enough to suppress the
     * on-demand descendant prefetch. Node/text counts alone are insufficient:
     * on Samsung App Info the persistent action bar can satisfy those counts while
     * the visible settings rows are still missing.
     *
     * The check is intentionally package-local and conservative. Non-App-Info
     * Settings surfaces keep the existing lightweight freshness shortcut. For an
     * App-Info action cluster, at least one factual detail-row marker must also be
     * present before recovery is skipped.
     */
    private fun settingsEvidenceIsDeepEnough(
        evidence: EventEvidence
    ): Boolean {

        if (
            evidence.visibleText.size < 2 ||
            evidence.nodes.length() <
            SETTINGS_SNAPSHOT_FRESH_MIN_NODES
        ) {
            return false
        }

        val normalizedText =
            normalize(
                evidence.visibleText
                    .joinToString(
                        " | "
                    )
            )

        if (
            !hasAppInfoActionCluster(
                normalizedText
            )
        ) {
            return true
        }

        val detailMarkers =
            listOf(
                "разрешения",
                "уведомления",
                "время использования экрана",
                "управление неиспольз",
                "использование по умолчанию",
                "открытие по умолчанию",
                "язык",
                "мобильные данные",
                "батарея",
                "аккумулятор",
                "хранилище",
                "память",
                "permissions",
                "notifications",
                "screen time",
                "unused app",
                "open by default",
                "defaults",
                "language",
                "mobile data",
                "battery",
                "storage"
            )

        return detailMarkers.any { marker ->
            normalizedText.contains(
                marker
            )
        }
    }

    private fun maybeCaptureSettingsOnDemandEvidence(
        liveContexts: List<WindowContext>
    ) {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }

        val target =
            liveContexts
                .filter { context ->
                    context.packageName == SETTINGS_PACKAGE &&
                        (
                            context.active ||
                                context.focused
                            ) &&
                        context.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }
                .minByOrNull { context ->
                    context.rank
                }
                ?: return

        val nowElapsed =
            SystemClock.elapsedRealtime()

        if (
            nowElapsed -
            lastSettingsSnapshotRecoveryAt <
            SETTINGS_SNAPSHOT_RECOVERY_THROTTLE_MS
        ) {
            return
        }

        // A fresh Settings event is not automatically semantically complete.
        // Samsung App Info may expose only the stable bottom action cluster
        // (Open / Disable / Force stop) while visible detail rows such as
        // Permissions remain absent from the captured tree. Treat that shallow
        // cluster as insufficient so the bounded API-33 descendant prefetch still
        // gets one chance to recover the factual detail rows from the SAME window.
        val nowWall =
            System.currentTimeMillis()

        val alreadyFresh =
            synchronized(
                eventEvidenceLock
            ) {
                recentEventEvidence.any { evidence ->
                    evidence.packageName == SETTINGS_PACKAGE &&
                        evidence.windowId == target.windowId &&
                        nowWall - evidence.at <=
                            SETTINGS_SNAPSHOT_FRESH_EVIDENCE_MS &&
                        settingsEvidenceIsDeepEnough(
                            evidence
                        )
                }
            }

        if (alreadyFresh) {
            return
        }

        lastSettingsSnapshotRecoveryAt =
            nowElapsed

        val strategy =
            AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
                AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE

        val candidates =
            mutableListOf<AccessibilityNodeInfo>()

        try {
            windows
                .firstOrNull { window ->
                    try {
                        window.id == target.windowId
                    } catch (_: Exception) {
                        false
                    }
                }
                ?.getRoot(
                    strategy
                )
                ?.let { root ->
                    if (
                        root.packageName
                            ?.toString()
                            .orEmpty() ==
                        SETTINGS_PACKAGE
                    ) {
                        candidates.add(root)
                    }
                }
        } catch (_: Exception) {
        }

        try {
            getRootInActiveWindow(
                strategy
            )
                ?.let { root ->
                    val rootPackage =
                        root.packageName
                            ?.toString()
                            .orEmpty()

                    val rootWindowId =
                        try {
                            root.windowId
                        } catch (_: Exception) {
                            -1
                        }

                    if (
                        rootPackage == SETTINGS_PACKAGE &&
                        (
                            target.windowId < 0 ||
                                rootWindowId < 0 ||
                                rootWindowId == target.windowId
                            )
                    ) {
                        candidates.add(root)
                    }
                }
        } catch (_: Exception) {
        }

        // Keep the ordinary root as a last fallback. It costs no extra prefetch and
        // still lets this path capture a transient readable tree that appeared
        // between resolveWindowContexts() and this bounded recovery attempt.
        target.root
            ?.let { root ->
                if (
                    root.packageName
                        ?.toString()
                        .orEmpty() ==
                    SETTINGS_PACKAGE
                ) {
                    candidates.add(root)
                }
            }

        var bestNodes =
            JSONArray()

        var bestTexts =
            emptyList<String>()

        var bestScore =
            -1

        for (root in candidates.distinctBy { candidate ->
            val id =
                try {
                    candidate.windowId
                } catch (_: Exception) {
                    -1
                }

            "$id|${System.identityHashCode(candidate)}"
        }) {
            val nodes =
                try {
                    snapshotEventTree(
                        root = root,
                        maxNodes = SETTINGS_SNAPSHOT_RECOVERY_NODE_LIMIT,
                        maxChars = SETTINGS_SNAPSHOT_RECOVERY_CHAR_LIMIT
                    )
                } catch (_: Exception) {
                    JSONArray()
                }

            val textArray =
                collectVisibleTexts(
                    nodes
                )

            val texts =
                mutableListOf<String>()

            for (index in 0 until textArray.length()) {
                val value =
                    safeText(
                        textArray.optString(index)
                    )

                if (
                    value.isNotBlank() &&
                    value !in texts
                ) {
                    texts.add(value)
                }

                if (
                    texts.size >=
                    EVENT_EVIDENCE_TEXT_LIMIT
                ) {
                    break
                }
            }

            val score =
                texts.size * 100 +
                    nodes.length()

            if (score > bestScore) {
                bestScore = score
                bestNodes = nodes
                bestTexts = texts
            }
        }

        if (
            bestNodes.length() == 0 &&
            bestTexts.isEmpty()
        ) {
            return
        }

        for (index in 0 until bestNodes.length()) {
            bestNodes
                .optJSONObject(index)
                ?.put(
                    "evidence_source",
                    "settings_on_demand_prefetch"
                )
        }

        val evidence =
            EventEvidence(
                at = nowWall,
                windowId = target.windowId,
                packageName = SETTINGS_PACKAGE,
                className = target.className,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                bounds = Rect(target.bounds),
                nodes = bestNodes,
                visibleText = bestTexts,
                origin = "settings_on_demand_prefetch"
            )

        synchronized(
            eventEvidenceLock
        ) {
            pruneEventEvidenceLocked(
                nowWall
            )

            recentEventEvidence.addFirst(
                evidence
            )

            while (
                recentEventEvidence.size >
                EVENT_EVIDENCE_MAX_RECORDS
            ) {
                recentEventEvidence.removeLast()
            }
        }
    }

    private fun currentEventEvidenceBurst(
        liveContexts: List<WindowContext>
    ): List<EventEvidence> {

        val now = System.currentTimeMillis()
        val liveIds = liveContexts.map { it.windowId }.filter { it >= 0 }.toSet()
        val livePackages = liveContexts.map { it.packageName }.filter { it.isNotBlank() }.toSet()

        val ownRecoveryWindowIds =
            liveContexts
                .filter { context ->
                    context.packageName == packageName &&
                        context.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        (
                            context.active ||
                                context.focused
                            )
                }
                .map { context ->
                    context.windowId
                }
                .filter { windowId ->
                    windowId >= 0
                }
                .toSet()

        val stickyOwnerPackage =
            lastForegroundOwnerPackage
                .trim()

        val contradictoryLiveExternalApplication =
            liveContexts
                .any { context ->
                    context.packageName.isNotBlank() &&
                        context.packageName != packageName &&
                        context.packageName != stickyOwnerPackage &&
                        context.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        (
                            context.active ||
                                context.focused
                            )
                }

        synchronized(eventEvidenceLock) {
            pruneEventEvidenceLocked(now)

            val eligible =
                recentEventEvidence
                    .filter { evidence ->
                        val age = now - evidence.at

                        val ownApplicationEvidence =
                            evidence.packageName == packageName &&
                                evidence.windowId >= 0 &&
                                evidence.windowId in ownRecoveryWindowIds &&
                                evidence.origin in
                                    setOf(
                                        "accessibility_event",
                                        "own_app_structural_recovery",
                                        "foreground_on_demand_prefetch"
                                    )

                        val externalEvidence =
                            evidence.packageName != packageName

                        // v6.9: a sticky external owner may temporarily disappear from
                        // getWindows() on Samsung while the real screen remains visible.
                        // Admit only FRESH evidence for that exact proven owner and only
                        // when no different focused/active external application contradicts
                        // it. This restores evidence continuity without promoting launcher
                        // or SystemUI shells and without inventing a package from a blank root.
                        val stickyOwnerContinuation =
                            externalEvidence &&
                                stickyOwnerPackage.isNotBlank() &&
                                evidence.packageName == stickyOwnerPackage &&
                                age <= EVENT_VERIFICATION_TTL_MS &&
                                !contradictoryLiveExternalApplication

                        age <= EVENT_EVIDENCE_TTL_MS &&
                            (
                                externalEvidence ||
                                    ownApplicationEvidence
                                ) &&
                            (
                                (evidence.windowId >= 0 && evidence.windowId in liveIds) ||
                                    (
                                        externalEvidence &&
                                        evidence.packageName in livePackages
                                        ) ||
                                    (
                                        externalEvidence &&
                                        liveContexts.isEmpty() &&
                                        evidence.packageName == lastEventPackage &&
                                        age <= EVENT_EVIDENCE_ORPHAN_TTL_MS
                                    ) ||
                                    stickyOwnerContinuation
                                )
                    }

            val newestByKey = mutableMapOf<String, Long>()
            val newestStructuralByKey = mutableMapOf<String, Long>()

            for (evidence in eligible) {
                val key = evidenceContextKey(evidence)
                val current = newestByKey[key] ?: Long.MIN_VALUE
                if (evidence.at > current) newestByKey[key] = evidence.at

                if (isStructuralEvidence(evidence.eventType)) {
                    val structural = newestStructuralByKey[key] ?: Long.MIN_VALUE
                    if (evidence.at > structural) newestStructuralByKey[key] = evidence.at
                }
            }

            return eligible
                .filter { evidence ->
                    val key = evidenceContextKey(evidence)
                    val newest = newestByKey[key] ?: evidence.at
                    val newestStructural = newestStructuralByKey[key]

                    val burstOk =
                        newest - evidence.at <= EVENT_EVIDENCE_BURST_MS

                    val structuralCutoffOk =
                        newestStructural == null ||
                            evidence.at >= newestStructural - EVENT_EVIDENCE_STRUCTURAL_LEEWAY_MS

                    burstOk && structuralCutoffOk
                }
                .sortedByDescending { it.at }
        }
    }

    private fun isStructuralEvidence(
        eventType: Int
    ): Boolean {

        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
    }

    private fun evidenceContextKey(
        evidence: EventEvidence
    ): String {

        return if (evidence.windowId >= 0) {
            "${evidence.packageName}|${evidence.windowId}"
        } else {
            "${evidence.packageName}|${evidence.className}|${evidence.bounds.left / 80}|${evidence.bounds.top / 80}"
        }
    }

    private fun syntheticContextForEvidence(
        evidence: EventEvidence,
        liveContexts: List<WindowContext>
    ): WindowContext {

        val samePackageLayer =
            liveContexts
                .filter { it.packageName == evidence.packageName }
                .maxOfOrNull { it.layer }
                ?: 0

        val evidenceAgeMs =
            (System.currentTimeMillis() - evidence.at)
                .coerceAtLeast(0L)

        val stickyOwnerEvidence =
            evidence.packageName.isNotBlank() &&
                evidence.packageName == lastForegroundOwnerPackage.trim() &&
                evidenceAgeMs <= EVENT_VERIFICATION_TTL_MS

        val syntheticActive =
            (evidence.windowId >= 0 && evidence.windowId == lastEventWindowId) ||
                stickyOwnerEvidence

        val rank =
            windowInteractionRank(
                windowId = evidence.windowId,
                packageName = evidence.packageName,
                type = EVIDENCE_WINDOW_TYPE,
                layer = samePackageLayer,
                active = syntheticActive,
                focused = false,
                pictureInPicture = false
            ) - EVIDENCE_CONTEXT_RANK_PENALTY

        return WindowContext(
            contextId = "e:${evidenceContextKey(evidence)}",
            windowId = evidence.windowId,
            packageName = evidence.packageName,
            className = evidence.className,
            type = EVIDENCE_WINDOW_TYPE,
            layer = samePackageLayer,
            active = syntheticActive,
            focused = false,
            pictureInPicture = false,
            bounds = Rect(evidence.bounds),
            root = null,
            source = "event_evidence",
            rank = rank
        )
    }

    private fun windowTypeName(
        type: Int
    ): String {

        return when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
            EVIDENCE_WINDOW_TYPE -> "event_evidence"
            else -> "other_$type"
        }
    }

    private fun overlapRatio(
        first: Rect,
        second: Rect
    ): Double {

        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)

        if (right <= left || bottom <= top) return 0.0

        val intersection =
            (right - left).toLong() * (bottom - top).toLong()

        val base =
            minOf(
                first.width().coerceAtLeast(0).toLong() * first.height().coerceAtLeast(0).toLong(),
                second.width().coerceAtLeast(0).toLong() * second.height().coerceAtLeast(0).toLong()
            )

        if (base <= 0L) return 0.0

        return (intersection.toDouble() / base.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun contextOcclusionRatio(
        target: WindowContext,
        contexts: List<WindowContext>
    ): Double {

        val targetArea =
            target.bounds.width().coerceAtLeast(0).toLong() *
                target.bounds.height().coerceAtLeast(0).toLong()

        if (targetArea <= 0L) return 0.0

        var covered = 0L

        for (other in contexts) {
            if (other.contextId == target.contextId || other.layer <= target.layer) continue

            val left = maxOf(target.bounds.left, other.bounds.left)
            val top = maxOf(target.bounds.top, other.bounds.top)
            val right = minOf(target.bounds.right, other.bounds.right)
            val bottom = minOf(target.bounds.bottom, other.bounds.bottom)

            if (right > left && bottom > top) {
                covered += (right - left).toLong() * (bottom - top).toLong()
            }
        }

        return (covered.toDouble() / targetArea.toDouble())
            .coerceIn(0.0, 1.0)
    }

    private fun isPointOccludedForContext(
        x: Int,
        y: Int,
        target: WindowContext,
        allContexts: List<WindowContext>
    ): Boolean {

        return allContexts.any { other ->
            other.contextId != target.contextId &&
                other.layer > target.layer &&
                other.bounds.contains(x, y)
        }
    }

    private fun nodeCenter(
        node: AccessibilityNodeInfo
    ): Pair<Int, Int>? {

        val bounds = Rect()
        return try {
            node.getBoundsInScreen(bounds)
            if (bounds.width() <= 1 || bounds.height() <= 1) null
            else bounds.centerX() to bounds.centerY()
        } catch (_: Exception) {
            null
        }
    }

    private fun rectToJson(
        bounds: Rect
    ): JSONObject {

        return JSONObject()
            .put("left", bounds.left)
            .put("top", bounds.top)
            .put("right", bounds.right)
            .put("bottom", bounds.bottom)
    }

    private fun appendNodeTexts(
        target: MutableSet<String>?,
        node: JSONObject
    ) {

        if (target == null) return

        listOf(
            node.optString("text"),
            node.optString("description")
        )
            .map { safeText(it) }
            .filter { it.isNotBlank() && it != "[PASSWORD_HIDDEN]" }
            .forEach { target.add(it) }
    }

    private fun contextForEventTarget(
        target: EventTapTarget,
        contexts: List<WindowContext>
    ): WindowContext? {

        return contexts.firstOrNull { context ->
            target.windowId >= 0 && context.windowId == target.windowId
        } ?: contexts.firstOrNull { context ->
            context.packageName == target.packageName &&
                overlapRatio(context.bounds, target.bounds) > 0.10
        } ?: contexts.firstOrNull { it.packageName == target.packageName }
    }

    private fun findRecentEventTapTarget(
        target: String,
        interactionContexts: List<WindowContext>
    ): EventTapTarget? {

        val normalizedTarget = normalize(target)
        if (normalizedTarget.isBlank()) return null

        val allowedPackages =
            interactionContexts
                .map { it.packageName }
                .filter { it.isNotBlank() }
                .toSet()

        val allowedWindowIds =
            interactionContexts
                .map { it.windowId }
                .filter { it >= 0 }
                .toSet()

        var bestScore = 0
        var best: EventTapTarget? = null
        val now = System.currentTimeMillis()

        for (evidence in currentEventEvidenceBurst(interactionContexts)) {
            if (now - evidence.at > EVENT_ACTION_TTL_MS) {
                continue
            }

            val allowed =
                (evidence.windowId >= 0 && evidence.windowId in allowedWindowIds) ||
                    evidence.packageName in allowedPackages

            if (!allowed) continue

            for (index in 0 until evidence.nodes.length()) {
                val node = evidence.nodes.optJSONObject(index) ?: continue
                if (!node.optBoolean("visible", false) || !node.optBoolean("enabled", true)) continue

                val text = normalize(node.optString("text"))
                val description = normalize(node.optString("description"))
                val viewId = normalize(node.optString("view_id"))

                var score = 0
                score = max(score, scoreValue(text, normalizedTarget, 100))
                score = max(score, scoreValue(description, normalizedTarget, 95))
                score = max(score, scoreValue(viewId, normalizedTarget, 80))
                if (score > 0 && node.optBoolean("clickable", false)) score += 8

                if (score < MIN_MATCH_SCORE || score <= bestScore) continue

                val b = node.optJSONObject("bounds") ?: continue
                val rect = Rect(
                    b.optInt("left", -1),
                    b.optInt("top", -1),
                    b.optInt("right", -1),
                    b.optInt("bottom", -1)
                )

                if (rect.left < 0 || rect.top < 0 || rect.width() <= 1 || rect.height() <= 1) continue

                bestScore = score
                best = EventTapTarget(rect, evidence.windowId, evidence.packageName)
            }
        }

        return best
    }

    private fun safeWindowCount():
        Int {

        return try {
            windows.size
        } catch (_: Exception) {
            0
        }
    }

    private fun nodeToJson(
        node: AccessibilityNodeInfo,
        depth: Int,
        index: Int
    ): JSONObject {

        val bounds =
            Rect()

        node.getBoundsInScreen(
            bounds
        )

        val password =
            node.isPassword

        val text =
            if (
                password
            ) {
                "[PASSWORD_HIDDEN]"
            } else {
                node.text
                    ?.toString()
                    .orEmpty()
            }

        val description =
            if (
                password
            ) {
                "[PASSWORD_HIDDEN]"
            } else {
                node.contentDescription
                    ?.toString()
                    .orEmpty()
            }

        return JSONObject()
            .put(
                "index",
                index
            )
            .put(
                "depth",
                depth
            )
            .put(
                "text",
                safeText(
                    text
                )
            )
            .put(
                "description",
                safeText(
                    description
                )
            )
            .put(
                "view_id",
                safeText(
                    node.viewIdResourceName
                        .orEmpty()
                )
            )
            .put(
                "class",
                safeText(
                    node.className
                        ?.toString()
                        .orEmpty()
                )
            )
            .put(
                "package",
                safeText(
                    node.packageName
                        ?.toString()
                        .orEmpty()
                )
            )
            .put(
                "clickable",
                node.isClickable
            )
            .put(
                "editable",
                node.isEditable
            )
            .put(
                "scrollable",
                node.isScrollable
            )
            .put(
                "focusable",
                node.isFocusable
            )
            .put(
                "focused",
                node.isFocused
            )
            .put(
                "enabled",
                node.isEnabled
            )
            .put(
                "visible",
                node.isVisibleToUser
            )
            .put(
                "password",
                password
            )
            .put(
                "bounds",
                JSONObject()
                    .put(
                        "left",
                        bounds.left
                    )
                    .put(
                        "top",
                        bounds.top
                    )
                    .put(
                        "right",
                        bounds.right
                    )
                    .put(
                        "bottom",
                        bounds.bottom
                    )
            )
    }

    private fun collectVisibleTexts(
        nodes: JSONArray
    ): JSONArray {

        val result =
            JSONArray()

        val seen =
            linkedSetOf<String>()

        for (
            index in
            0 until nodes.length()
        ) {

            val item =
                nodes
                    .optJSONObject(
                        index
                    )
                    ?: continue

            val text =
                item
                    .optString(
                        "text"
                    )
                    .trim()

            val description =
                item
                    .optString(
                        "description"
                    )
                    .trim()

            listOf(
                text,
                description
            )
                .filter {
                    it.isNotBlank() &&
                        it !=
                        "[PASSWORD_HIDDEN]"
                }
                .forEach {
                    value ->

                    val clipped =
                        safeText(
                            value
                        )

                    if (
                        clipped.isNotBlank() &&
                        seen.add(
                            clipped
                        )
                    ) {

                        result.put(
                            clipped
                        )
                    }
                }
        }

        return result
    }

    private fun findBestNode(
        root: AccessibilityNodeInfo,
        target: String,
        requireEditable: Boolean,
        requireClickable: Boolean
    ): NodeMatch? {

        val normalizedTarget =
            normalize(
                target
            )

        if (
            normalizedTarget.isBlank()
        ) {
            return null
        }

        var best:
            NodeMatch? = null

        val queue =
            ArrayDeque<
                AccessibilityNodeInfo
            >()

        queue.add(
            root
        )

        var visited =
            0

        while (
            queue.isNotEmpty() &&
            visited < 500
        ) {

            val node =
                queue.removeFirst()

            visited++

            if (
                node.isVisibleToUser &&
                node.isEnabled &&
                (
                    !requireEditable ||
                        node.isEditable
                    ) &&
                (
                    !requireClickable ||
                        node.isClickable
                    )
            ) {

                val score =
                    scoreNode(
                        node,
                        normalizedTarget
                    )

                if (
                    score >
                    (
                        best?.score
                            ?: 0
                        )
                ) {

                    best =
                        NodeMatch(
                            node,
                            score
                        )
                }
            }

            for (
                index in
                0 until node.childCount
            ) {

                childWithPrefetch(
                    node,
                    index
                )
                    ?.let {
                        queue.add(
                            it
                        )
                    }
            }
        }

        return best
            ?.takeIf {
                it.score >=
                    MIN_MATCH_SCORE
            }
    }

    private fun scoreNode(
        node: AccessibilityNodeInfo,
        target: String
    ): Int {

        if (
            node.isPassword
        ) {
            return 0
        }

        val text =
            normalize(
                node.text
                    ?.toString()
                    .orEmpty()
            )

        val description =
            normalize(
                node.contentDescription
                    ?.toString()
                    .orEmpty()
            )

        val viewId =
            normalize(
                node.viewIdResourceName
                    .orEmpty()
            )

        var score =
            0

        score =
            max(
                score,
                scoreValue(
                    text,
                    target,
                    100
                )
            )

        score =
            max(
                score,
                scoreValue(
                    description,
                    target,
                    95
                )
            )

        score =
            max(
                score,
                scoreValue(
                    viewId,
                    target,
                    80
                )
            )

        if (
            score > 0 &&
            node.isClickable
        ) {
            score +=
                8
        }

        if (
            score > 0 &&
            node.isEditable
        ) {
            score +=
                5
        }

        return score
    }

    private fun scoreValue(
        value: String,
        target: String,
        exactScore: Int
    ): Int {

        if (
            value.isBlank()
        ) {
            return 0
        }

        return when {

            value ==
                target ->
                exactScore

            value.contains(
                target
            ) ->
                exactScore -
                    15

            target.contains(
                value
            ) &&
                value.length >=
                3 ->
                exactScore -
                    25

            tokenOverlap(
                value,
                target
            ) >=
                0.7 ->
                exactScore -
                    35

            else ->
                0
        }
    }

    private fun tokenOverlap(
        left: String,
        right: String
    ): Double {

        val leftTokens =
            left
                .split(" ")
                .filter {
                    it.length >= 2
                }
                .toSet()

        val rightTokens =
            right
                .split(" ")
                .filter {
                    it.length >= 2
                }
                .toSet()

        if (
            leftTokens.isEmpty() ||
            rightTokens.isEmpty()
        ) {
            return 0.0
        }

        val common =
            leftTokens
                .intersect(
                    rightTokens
                )
                .size

        return common.toDouble() /
            max(
                leftTokens.size,
                rightTokens.size
            )
                .toDouble()
    }

    private fun findActionableParent(
        node: AccessibilityNodeInfo,
        action: Int
    ): AccessibilityNodeInfo? {

        var current:
            AccessibilityNodeInfo? =
            node

        var hops =
            0

        while (
            current != null &&
            hops < 7
        ) {

            if (
                current.actionList.any {
                    it.id == action
                }
            ) {
                return current
            }

            current =
                current.parent

            hops++
        }

        return null
    }

    private fun findFocusedEditable(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        val focused =
            root.findFocus(
                AccessibilityNodeInfo
                    .FOCUS_INPUT
            )

        if (
            focused != null &&
            focused.isEditable &&
            !focused.isPassword
        ) {
            return focused
        }

        return null
    }

    private fun findFirstEditable(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        val queue =
            ArrayDeque<
                AccessibilityNodeInfo
            >()

        queue.add(
            root
        )

        var visited =
            0

        while (
            queue.isNotEmpty() &&
            visited < 400
        ) {

            val node =
                queue.removeFirst()

            visited++

            if (
                node.isVisibleToUser &&
                node.isEnabled &&
                node.isEditable &&
                !node.isPassword
            ) {
                return node
            }

            for (
                index in
                0 until node.childCount
            ) {

                childWithPrefetch(
                    node,
                    index
                )
                    ?.let {
                        queue.add(
                            it
                        )
                    }
            }
        }

        return null
    }

    private fun findBestScrollable(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        val queue =
            ArrayDeque<
                AccessibilityNodeInfo
            >()

        queue.add(
            root
        )

        var best:
            AccessibilityNodeInfo? =
            null

        var bestArea =
            0L

        var visited =
            0

        while (
            queue.isNotEmpty() &&
            visited < 500
        ) {

            val node =
                queue.removeFirst()

            visited++

            if (
                node.isVisibleToUser &&
                node.isEnabled &&
                node.isScrollable
            ) {

                val bounds =
                    Rect()

                node.getBoundsInScreen(
                    bounds
                )

                val area =
                    bounds.width()
                        .toLong() *
                        bounds.height()
                            .toLong()

                if (
                    area >
                    bestArea
                ) {

                    best =
                        node

                    bestArea =
                        area
                }
            }

            for (
                index in
                0 until node.childCount
            ) {

                childWithPrefetch(
                    node,
                    index
                )
                    ?.let {
                        queue.add(
                            it
                        )
                    }
            }
        }

        return best
    }

    /**
     * Android 13+ can prefetch descendants when the window root is obtained.
     * Samsung tablet multi-window/Settings was observed to expose the window
     * identity while legacy root traversal returned an empty content tree.
     * Try safe descendant prefetch strategies, then fall back to getRoot().
     */
    /**
     * Cheap root richness score used only to choose between two roots that
     * describe the same Android window. It never combines evidence from
     * different windows and therefore preserves strict verification isolation.
     */
    private fun rootSemanticProbe(
        root: AccessibilityNodeInfo?,
        maxNodes: Int = ROOT_SEMANTIC_PROBE_NODE_LIMIT
    ): RootSemanticProbe {

        if (root == null) {
            return RootSemanticProbe(
                nodeCount = 0,
                readableTextCount = 0,
                editableCount = 0,
                clickableCount = 0
            )
        }

        val queue =
            ArrayDeque<AccessibilityNodeInfo>()

        queue.add(
            root
        )

        val seenTexts =
            linkedSetOf<String>()

        var nodeCount =
            0

        var editableCount =
            0

        var clickableCount =
            0

        while (
            queue.isNotEmpty() &&
            nodeCount < maxNodes
        ) {
            val node =
                queue.removeFirst()

            nodeCount++

            val visible =
                try {
                    node.isVisibleToUser
                } catch (_: Exception) {
                    true
                }

            if (visible) {
                val password =
                    try {
                        node.isPassword
                    } catch (_: Exception) {
                        false
                    }

                if (!password) {
                    val text =
                        safeText(
                            try {
                                node.text
                                    ?.toString()
                                    .orEmpty()
                            } catch (_: Exception) {
                                ""
                            }
                        )

                    val description =
                        safeText(
                            try {
                                node.contentDescription
                                    ?.toString()
                                    .orEmpty()
                            } catch (_: Exception) {
                                ""
                            }
                        )

                    if (text.isNotBlank()) {
                        seenTexts.add(
                            text
                        )
                    }

                    if (description.isNotBlank()) {
                        seenTexts.add(
                            description
                        )
                    }
                }

                if (
                    try {
                        node.isEditable
                    } catch (_: Exception) {
                        false
                    }
                ) {
                    editableCount++
                }

                if (
                    try {
                        node.isClickable
                    } catch (_: Exception) {
                        false
                    }
                ) {
                    clickableCount++
                }
            }

            val childCount =
                try {
                    node.childCount
                } catch (_: Exception) {
                    0
                }

            for (
                index in
                0 until childCount
            ) {
                childWithPrefetch(
                    node,
                    index
                )?.let { child ->
                    queue.add(
                        child
                    )
                }
            }
        }

        return RootSemanticProbe(
            nodeCount = nodeCount,
            readableTextCount = seenTexts.size,
            editableCount = editableCount,
            clickableCount = clickableCount
        )
    }

    /**
     * Bounded semantic richness score used only to choose between roots that
     * describe the SAME Android window. Unlike v5/v6.0's first-level probe, this
     * sees enough descendants to distinguish a package-bearing shell from a real
     * AYANA/Settings content tree without doing an unbounded walk.
     */
    private fun rootContentScore(
        root: AccessibilityNodeInfo?
    ): Int {

        val probe =
            rootSemanticProbe(
                root
            )

        return probe.nodeCount +
            probe.readableTextCount * 30 +
            probe.editableCount * 18 +
            probe.clickableCount * 6
    }

    private fun rootLooksSemanticallyRich(
        root: AccessibilityNodeInfo?
    ): Boolean {

        val probe =
            rootSemanticProbe(
                root
            )

        return probe.readableTextCount >= 2 &&
            (
                probe.nodeCount >= 6 ||
                    probe.editableCount > 0 ||
                    probe.clickableCount > 0
                )
    }

    private fun prefetchedActiveWindowRoot():
        AccessibilityNodeInfo? {

        val legacy =
            try {
                rootInActiveWindow
            } catch (_: Exception) {
                null
            }

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {
            return legacy
        }

        if (
            rootLooksSemanticallyRich(
                legacy
            )
        ) {
            return legacy
        }

        val candidates =
            mutableListOf<AccessibilityNodeInfo>()

        legacy?.let {
            candidates.add(
                it
            )
        }

        val strategies =
            listOf(
                AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID,
                AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
                    AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
            )

        for (
            strategy in
            strategies
        ) {
            try {
                getRootInActiveWindow(
                    strategy
                )
                    ?.let { root ->
                        candidates.add(
                            root
                        )
                    }
            } catch (_: Exception) {
            }
        }

        return candidates
            .maxByOrNull { root ->
                rootContentScore(
                    root
                )
            }
            ?: legacy
    }

    private fun recoveredRootForContext(
        context: WindowContext
    ): AccessibilityNodeInfo? {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU ||
            context.type !=
            AccessibilityWindowInfo.TYPE_APPLICATION ||
            context.packageName.isBlank()
        ) {
            return context.root
        }

        val candidates =
            mutableListOf<AccessibilityNodeInfo>()

        context.root?.let {
            candidates.add(
                it
            )
        }

        val strategy =
            AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
                AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE

        try {
            windows
                .firstOrNull { window ->
                    try {
                        window.id == context.windowId
                    } catch (_: Exception) {
                        false
                    }
                }
                ?.getRoot(
                    strategy
                )
                ?.let { root ->
                    val rootPackage =
                        root.packageName
                            ?.toString()
                            .orEmpty()

                    if (
                        rootPackage ==
                        context.packageName
                    ) {
                        candidates.add(
                            root
                        )
                    }
                }
        } catch (_: Exception) {
        }

        if (
            context.active ||
            context.focused
        ) {
            try {
                getRootInActiveWindow(
                    strategy
                )
                    ?.let { root ->
                        val rootPackage =
                            root.packageName
                                ?.toString()
                                .orEmpty()

                        val rootWindowId =
                            try {
                                root.windowId
                            } catch (_: Exception) {
                                -1
                            }

                        if (
                            rootPackage ==
                            context.packageName &&
                            (
                                context.windowId < 0 ||
                                    rootWindowId < 0 ||
                                    rootWindowId ==
                                    context.windowId
                                )
                        ) {
                            candidates.add(
                                root
                            )
                        }
                    }
            } catch (_: Exception) {
            }
        }

        return candidates
            .maxByOrNull { root ->
                rootContentScore(
                    root
                )
            }
    }

    private fun prefetchedWindowRoot(
        window: AccessibilityWindowInfo
    ): AccessibilityNodeInfo? {

        val legacy =
            try {
                window.root
            } catch (_: Exception) {
                null
            }

        val foregroundCandidate =
            try {
                window.isActive ||
                    window.isFocused
            } catch (_: Exception) {
                false
            }

        // Non-foreground windows stay on the cheapest path. A populated
        // background root is useful for topology but must not trigger expensive
        // descendant IPC.
        if (
            !foregroundCandidate &&
            legacy != null &&
            rootLooksPopulated(
                legacy
            )
        ) {
            return legacy
        }

        if (
            !foregroundCandidate ||
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {
            return legacy
        }

        if (
            rootLooksSemanticallyRich(
                legacy
            )
        ) {
            return legacy
        }

        val candidates =
            mutableListOf<AccessibilityNodeInfo>()

        legacy?.let {
            candidates.add(
                it
            )
        }

        val strategies =
            listOf(
                AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID,
                AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
                    AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
            )

        for (
            strategy in
            strategies
        ) {
            try {
                window.getRoot(
                    strategy
                )
                    ?.let { root ->
                        candidates.add(
                            root
                        )
                    }
            } catch (_: Exception) {
            }
        }

        return candidates
            .maxByOrNull { root ->
                rootContentScore(
                    root
                )
            }
            ?: legacy
    }

    private fun rootLooksPopulated(
        root: AccessibilityNodeInfo
    ): Boolean {

        val childCount =
            try {
                root.childCount
            } catch (_: Exception) {
                0
            }

        if (
            childCount >
            0
        ) {
            return true
        }

        val text =
            try {
                safeText(
                    root.text
                        ?.toString()
                        .orEmpty()
                )
            } catch (_: Exception) {
                ""
            }

        if (
            text.isNotBlank()
        ) {
            return true
        }

        val description =
            try {
                safeText(
                    root.contentDescription
                        ?.toString()
                        .orEmpty()
                )
            } catch (_: Exception) {
                ""
            }

        return description.isNotBlank()
    }

    /**
     * Safe child access for traversal. Root-level prefetch, when justified, has
     * already happened in prefetchedWindowRoot(). Re-prefetching descendants for
     * EVERY child caused the v11.2 UI/Orb regression and high CPU/thermal load.
     */
    private fun childWithPrefetch(
        node: AccessibilityNodeInfo,
        index: Int
    ): AccessibilityNodeInfo? {

        return try {
            node.getChild(
                index
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun normalize(
        value: String
    ): String {

        return value
            .lowercase(
                Locale.getDefault()
            )
            .replace(
                'ё',
                'е'
            )
            .replace(
                Regex(
                    "[^\\p{L}\\p{N}\\s_]"
                ),
                " "
            )
            .replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
            .trim()
    }

    private fun safeText(
        value: String
    ): String {

        return value
            .replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
            .trim()
            .take(
                280
            )
    }

    @Volatile
    private var lastRootSource:
        String =
        "unavailable"

    private fun settingsSemanticSurface(
        packageName: String,
        title: String,
        verificationText: String
    ): String {
        if (packageName != SETTINGS_PACKAGE) return ""

        val n = normalize("$title | $verificationText")
        val titleN = normalize(title)

        return when {
            titleN.contains("уведом") ||
                titleN.contains("notification") ||
                n.contains("уведомления приложений") ||
                n.contains("разрешение уведомлений") ||
                n.contains("категории уведом") ||
                n.contains("app notifications") ||
                n.contains("allow notifications") ||
                n.contains("notification categories") ->
                "app_notifications"

            titleN.contains("разреш") ||
                titleN.contains("permission") ||
                n.contains("разрешения приложений") ||
                n.contains("разрешения для") ||
                n.contains("app permissions") ||
                n.contains("permissions for") ||
                n.contains("разрешено") ||
                n.contains("не разрешено") ||
                n.contains("allowed") ||
                n.contains("not allowed") ->
                "app_permissions"

            titleN.contains("батар") || titleN.contains("аккумуля") || titleN.contains("battery") ->
                "app_battery"

            titleN.contains("хранили") || titleN.contains("память") || titleN.contains("storage") ->
                "app_storage"

            titleN.contains("по умолч") || titleN.contains("open by default") ||
                titleN.contains("открытие ссыл") ->
                "app_defaults"

            titleN.contains("информация о прилож") || titleN.contains("сведения о прилож") ||
                titleN.contains("app info") ->
                "app_info"

            // Samsung may expose a sparse App Info root without the app label or
            // title but still exposes the stable action cluster from the SAME
            // Settings window. This is structural evidence only; target identity
            // must come from a separate exact intent attestation in VoiceService.
            hasAppInfoActionCluster(n) ->
                "app_info_structure"

            else -> "settings_unknown"
        }
    }

    private fun settingsSemanticConfidence(
        packageName: String,
        title: String,
        verificationText: String
    ): Int {
        if (packageName != SETTINGS_PACKAGE) return 0
        val surface = settingsSemanticSurface(packageName, title, verificationText)
        return when (surface) {
            "app_notifications", "app_permissions", "app_battery", "app_storage",
            "app_defaults", "app_info" -> 90
            "app_info_structure" -> 72
            "settings_unknown" -> 35
            else -> 0
        }
    }

    private fun hasAppInfoActionCluster(normalized: String): Boolean {
        val hasOpen = normalized.contains("открыть") || normalized.contains("open") ||
            normalized.contains("включить") || normalized.contains("enable")
        val hasStop = normalized.contains("остановить") || normalized.contains("force stop") ||
            normalized.contains("принудительно останов")
        return hasOpen && hasStop
    }

    companion object {

        private const val RECENTS_ENTER_TIMEOUT_MS =
            1800L

        private const val RECENTS_DISMISS_VERIFY_TIMEOUT_MS =
            1500L

        private const val RECENTS_VERIFY_POLL_MS =
            120L

        private const val RECENTS_EVENT_CLASS_FRESH_MS =
            2200L

        private const val RECENTS_MAX_SCAN_STEPS =
            12

        private const val RECENTS_STRUCTURE_NODE_LIMIT =
            520

        private const val RECENTS_CANDIDATE_NODE_LIMIT =
            700

        private const val RECENTS_CARD_PARENT_HOPS =
            8

        private const val RECENTS_IDENTITY_PROBE_NODE_LIMIT =
            700

        private const val RECENTS_IDENTITY_PROBE_RECORD_LIMIT =
            48

        private const val RECENTS_IDENTITY_PROBE_TARGET_HIT_LIMIT =
            12

        private const val CLICK_VERIFY_TIMEOUT_MS =
            650L

        private const val CLICK_VERIFY_POLL_MS =
            100L

        private const val SCROLL_VERIFY_TIMEOUT_MS =
            900L

        private const val SCROLL_VERIFY_POLL_MS =
            90L

        private const val SCROLL_FINGERPRINT_NODE_LIMIT =
            160

        private const val SCROLL_GESTURE_DURATION_MS =
            260L

        private const val SCROLL_GESTURE_MIN_WIDTH_PX =
            220

        private const val SCROLL_GESTURE_MIN_HEIGHT_PX =
            320

        private const val SCROLL_GESTURE_MIN_AREA_RATIO =
            0.20

        private const val SCROLL_GESTURE_MAX_OCCLUSION_RATIO =
            0.35

        private const val SETTINGS_LANDSCAPE_DETAIL_MIN_CENTER_RATIO =
            0.42

        private const val MIN_MATCH_SCORE =
            55

        private const val EVENT_EVIDENCE_TTL_MS =
            12000L

        private const val EVENT_VERIFICATION_TTL_MS =
            4500L

        private const val EVENT_ACTION_TTL_MS =
            4500L

        private const val EVENT_EVIDENCE_ORPHAN_TTL_MS =
            3500L

        private const val EVENT_EVIDENCE_BURST_MS =
            1600L

        private const val EVENT_EVIDENCE_STRUCTURAL_LEEWAY_MS =
            450L

        private const val EVENT_INTERACTION_FRESH_MS =
            3500L

        private const val WINDOW_VISIBLE_TEXT_LIMIT =
            36

        private const val TOP_VISIBLE_TEXT_LIMIT =
            96

        private const val ALL_VISIBLE_TEXT_LIMIT =
            144

        private const val VERIFICATION_TEXT_MAX_CHARS =
            7000

        private const val OWN_OVERLAY_MAX_AREA_RATIO =
            0.20

        private const val OWN_APP_MIN_APPLICATION_AREA_RATIO =
            0.35

        private const val FOREGROUND_OWNER_SOURCE_MIN_AREA_RATIO =
            0.35

        private const val EVIDENCE_WINDOW_TYPE =
            -100

        private const val EVIDENCE_CONTEXT_RANK_PENALTY =
            350

        private const val EVENT_EVIDENCE_MAX_RECORDS =
            12

        private const val EVENT_EVIDENCE_NODE_LIMIT =
            90

        private const val EVENT_EVIDENCE_CHAR_LIMIT =
            9000

        private const val EVENT_EVIDENCE_TEXT_LIMIT =
            80

        private const val EVENT_EVIDENCE_PARENT_LIMIT =
            12

        private const val SETTINGS_PACKAGE =
            "com.android.settings"

        private const val ROOT_SEMANTIC_PROBE_NODE_LIMIT =
            48

        private const val FOREGROUND_SNAPSHOT_RECOVERY_THROTTLE_MS =
            650L

        private const val FOREGROUND_SNAPSHOT_FRESH_EVIDENCE_MS =
            900L

        private const val FOREGROUND_SNAPSHOT_FRESH_MIN_NODES =
            6

        private const val FOREGROUND_SNAPSHOT_RECOVERY_NODE_LIMIT =
            96

        private const val FOREGROUND_SNAPSHOT_RECOVERY_CHAR_LIMIT =
            8200

        private const val OWN_APP_EVENT_RECOVERY_THROTTLE_MS =
            500L

        private const val OWN_APP_EVENT_RECOVERY_NODE_LIMIT =
            128

        private const val OWN_APP_EVENT_RECOVERY_CHAR_LIMIT =
            11000

        private const val SETTINGS_EVENT_RECOVERY_THROTTLE_MS =
            650L

        private const val SETTINGS_EVENT_RECOVERY_NODE_LIMIT =
            72

        private const val SETTINGS_EVENT_RECOVERY_CHAR_LIMIT =
            6500

        private const val SETTINGS_SNAPSHOT_RECOVERY_THROTTLE_MS =
            700L

        private const val SETTINGS_SNAPSHOT_FRESH_EVIDENCE_MS =
            900L

        private const val SETTINGS_SNAPSHOT_FRESH_MIN_NODES =
            6

        private const val SETTINGS_SNAPSHOT_RECOVERY_NODE_LIMIT =
            100

        private const val SETTINGS_SNAPSHOT_RECOVERY_CHAR_LIMIT =
            8500

        @Volatile
        var instance:
            AgentAccessibilityService? =
            null

        @Volatile
        var lastForegroundOwnerPackage:
            String = ""

        @Volatile
        var lastForegroundOwnerWindowId:
            Int = -1

        @Volatile
        var lastForegroundOwnerTime:
            Long = 0L

        @Volatile
        var lastForegroundOwnerSource:
            String = ""

        /**
         * Accept only already-verified external foreground truth from AYANA's own execution
         * pipeline. The caller must have same-window semantic/Accessibility proof; an intent
         * dispatch alone is never sufficient. This method merely preserves that proven owner
         * across Samsung getWindows() shell gaps until a later genuine ownership event replaces it.
         */
        fun attestVerifiedForegroundOwner(
            ownerPackage: String,
            windowId: Int = -1,
            source: String = "verified_external_proof"
        ): Boolean {

            val cleanPackage =
                ownerPackage.trim()

            if (cleanPackage.isBlank()) {
                return false
            }

            lastForegroundOwnerPackage =
                cleanPackage

            lastForegroundOwnerWindowId =
                windowId

            lastForegroundOwnerTime =
                System.currentTimeMillis()

            lastForegroundOwnerSource =
                source.trim()
                    .ifBlank {
                        "verified_external_proof"
                    }

            return true
        }

        @Volatile
        var lastEventPackage:
            String = ""

        @Volatile
        var lastEventClass:
            String = ""

        @Volatile
        var lastEventWindowId:
            Int = -1

        @Volatile
        var lastEventTime:
            Long = 0L
    }
}
