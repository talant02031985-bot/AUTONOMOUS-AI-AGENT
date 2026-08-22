AYANA AI — AgentAccessibilityService v5.9
P0 SIDE-EFFECT RECONCILIATION
TYPE: SOURCE PATCH — NOT A FULL FILE
BASE: AgentAccessibilityService v5.8 SAMSUNG TASKVIEW IDENTITY BUILD CANDIDATE

IMPORTANT
- This patch intentionally does NOT change Samsung One UI task identity matching.
- Keep the proven exact taskview / semantic label logic from v5.8 unchanged.
- Do NOT restore any position-only fallback.
- This patch changes only destructive dispatch ownership + post-dispatch reconciliation.

======================================================================
PATCH A — REPLACE recentTaskResult(...)
======================================================================

Replace the current recentTaskResult helper with this version:

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

======================================================================
PATCH B — ADD RECONCILIATION ENUM
======================================================================

Add near RecentTaskCandidate / other private helper types:

    private enum class RecentTaskReconciliation {
        REMOVED,
        STILL_PRESENT,
        UNKNOWN
    }

======================================================================
PATCH C — REPLACE waitForRecentTaskToDisappear(...)
======================================================================

Delete the old Boolean helper that takes shouldCancel. Replace it with:

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

======================================================================
PATCH D — REPLACE removeRecentTaskByLabel(...)
======================================================================

Replace the whole current removeRecentTaskByLabel method with this version.
The Samsung semantic candidate finder used inside it remains the existing v5.8 one.

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

======================================================================
INVARIANTS AFTER PATCH
======================================================================

1. STOP before irreversible gate -> CANCELLED, no dismiss call.
2. Accepted dismiss -> user STOP cannot abort bounded reconciliation.
3. Verified removed -> SUCCESS even if STOP is pending.
4. Verified still present -> no commit is proven; STOP may finish CANCELLED.
5. Accepted dispatch + unknown outcome -> ERROR / dispatch_outcome_unverified.
6. No positional Samsung fallback is introduced.
7. After STOP following one verified removal, no additional duplicate-card
   destructive action is attempted.
