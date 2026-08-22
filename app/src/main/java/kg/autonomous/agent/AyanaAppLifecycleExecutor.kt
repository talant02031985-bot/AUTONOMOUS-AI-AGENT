package kg.autonomous.agent

import org.json.JSONObject

/**
 * AYANA App Lifecycle Executor v1.4 — destructive side-effect reconciliation.
 *
 * Android third-party apps cannot honestly claim cross-app force-stop/process kill.
 * The user-visible close contract remains exact Recents task-card removal with
 * target-specific verification. v1.4 adds terminal-truth semantics around the
 * irreversible dispatch boundary:
 *
 * 1) before dispatch, STOP may own the outcome and terminal CANCELLED is valid;
 * 2) after an accepted destructive dispatch, CANCELLED is forbidden until the
 *    real-world outcome has been reconciled;
 * 3) verified removal -> SUCCESS even when STOP arrived after dispatch;
 * 4) verified no-effect -> cancellation may be acknowledged;
 * 5) unknown post-dispatch outcome -> ERROR/UNVERIFIED, never false CANCELLED.
 *
 * Samsung Recents identity and actual dispatch/verification remain owned by the
 * Accessibility layer. This class owns lifecycle semantic terminal truth and
 * foreground-context restoration policy.
 */
class AyanaAppLifecycleExecutor(
    private val gateway: Gateway,
    private val shouldCancel: () -> Boolean
) {

    interface Gateway {
        fun screenSnapshot(): JSONObject

        fun removeRecentTaskByLabel(
            targetLabel: String,
            sourcePackage: String,
            shouldCancel: () -> Boolean
        ): JSONObject

        fun restorePackage(
            packageName: String
        ): Boolean

        fun pressHome(): Boolean
    }

    fun removeTask(
        targetPackage: String,
        targetLabel: String,
        originalForegroundPackage: String
    ): JSONObject {

        val packageName =
            targetPackage.trim()

        val label =
            targetLabel.trim()

        if (
            packageName.isBlank() ||
            label.isBlank()
        ) {
            return terminal(
                status = "ERROR",
                reason = "invalid_target",
                message = "Не удалось определить приложение для закрытия."
            )
        }

        if (shouldCancel()) {
            return terminal(
                status = "CANCELLED",
                reason = "cancelled_before_close",
                message = "Закрытие отменено."
            ).put(
                "action_dispatched",
                false
            ).put(
                "action_committed",
                false
            )
        }

        val before =
            safeSnapshot()

        val topology =
            inspectTopology(
                before
            )

        if (
            !topology.optBoolean(
                "safe",
                false
            )
        ) {
            return terminal(
                status = "BLOCKED",
                reason = topology.optString(
                    "reason",
                    "unsafe_window_topology"
                ),
                message = topology.optString(
                    "message",
                    "Не закрываю приложение в неоднозначном оконном режиме."
                )
            ).put(
                "topology",
                topology
            ).put(
                "action_dispatched",
                false
            ).put(
                "action_committed",
                false
            )
        }

        val sourcePackage =
            originalForegroundPackage
                .trim()
                .ifBlank {
                    before.optString(
                        "package"
                    ).trim()
                }

        val removal =
            try {
                gateway.removeRecentTaskByLabel(
                    targetLabel = label,
                    sourcePackage = sourcePackage,
                    shouldCancel = shouldCancel
                )
            } catch (error: Exception) {
                JSONObject()
                    .put("success", false)
                    .put("verified", false)
                    .put("terminal_status", "ERROR")
                    .put("reason", "recents_executor_exception")
                    .put("action_dispatched", false)
                    .put("reconciliation_complete", true)
                    .put("verified_not_committed", true)
                    .put(
                        "message",
                        error.message
                            ?: "Ошибка удаления задачи из недавних"
                    )
            }

        val removalStatus =
            removal.optString(
                "terminal_status",
                if (
                    removal.optBoolean(
                        "success",
                        false
                    )
                ) {
                    "SUCCESS"
                } else {
                    "ERROR"
                }
            ).uppercase()

        val actionDispatched =
            removal.optBoolean(
                "action_dispatched",
                false
            ) ||
                removal.optInt(
                    "removed_count",
                    0
                ) > 0

        val reconciliationComplete =
            removal.optBoolean(
                "reconciliation_complete",
                !actionDispatched ||
                    removal.optBoolean(
                        "verified",
                        false
                    )
            )

        val verifiedNotCommitted =
            removal.optBoolean(
                "verified_not_committed",
                false
            )

        // A destructive task removal is committed only when Recents positively
        // verifies that the exact target card disappeared after accepted dispatch.
        val concreteDismissal =
            removalStatus == "SUCCESS" &&
                removal.optBoolean(
                    "success",
                    false
                ) &&
                removal.optBoolean(
                    "verified",
                    false
                ) &&
                removal.optInt(
                    "removed_count",
                    0
                ) > 0 &&
                removal.optString(
                    "reason"
                ) ==
                "verified_task_removed"

        if (concreteDismissal) {
            val cancelAfterCommit =
                shouldCancel() ||
                    removal.optBoolean(
                        "cancel_after_dispatch",
                        false
                    )

            val context =
                if (cancelAfterCommit) {
                    JSONObject()
                        .put(
                            "restored",
                            false
                        )
                        .put(
                            "mode",
                            "cancel_after_commit"
                        )
                } else {
                    restoreUserContext(
                        targetPackage = packageName,
                        originalForegroundPackage = sourcePackage
                    )
                }

            return JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("terminal_status", "SUCCESS")
                .put("status", "task_removed")
                .put(
                    "reason",
                    removal.optString(
                        "reason",
                        "verified_task_removed"
                    )
                )
                .put("message", "$label закрыт.")
                .put("target_package", packageName)
                .put("target_label", label)
                .put("method", "verified_recents_task_removal")
                .put("action_dispatched", true)
                .put("action_committed", true)
                .put("destructive_commit_pending", false)
                .put("reconciliation_complete", true)
                .put("cancel_after_commit", cancelAfterCommit)
                .put("removal", removal)
                .put(
                    "context_restored",
                    context.optBoolean(
                        "restored",
                        false
                    )
                )
                .put("context", context)
        }

        // Point-of-no-return was crossed, but the Accessibility layer could not
        // prove what happened. STOP cannot turn uncertainty into CANCELLED.
        // Avoid context-restoration side effects while the destructive outcome
        // itself is unknown; preserve the strongest available evidence instead.
        if (
            actionDispatched &&
            !reconciliationComplete &&
            !verifiedNotCommitted
        ) {
            return terminal(
                status = "ERROR",
                reason = "dispatch_outcome_unverified",
                message = "Команда удаления была передана Android, но фактический результат не удалось надёжно подтвердить."
            ).put(
                "action_dispatched",
                true
            ).put(
                "action_committed",
                false
            ).put(
                "destructive_commit_pending",
                true
            ).put(
                "cancel_after_dispatch",
                shouldCancel() ||
                    removal.optBoolean(
                        "cancel_after_dispatch",
                        false
                    )
            ).put(
                "removal",
                removal
            ).put(
                "context_restored",
                false
            ).put(
                "context",
                JSONObject()
                    .put("restored", false)
                    .put("mode", "dispatch_outcome_unverified")
            )
        }

        // Accepted dispatch was reconciled and the exact target is proven still
        // present. The destructive side effect did not commit, so a pending STOP
        // may now legitimately own terminal CANCELLED.
        if (
            actionDispatched &&
            reconciliationComplete &&
            verifiedNotCommitted &&
            shouldCancel()
        ) {
            return terminal(
                status = "CANCELLED",
                reason = "cancelled_after_verified_no_effect",
                message = "Закрытие отменено; удаление карточки не произошло."
            ).put(
                "action_dispatched",
                true
            ).put(
                "action_committed",
                false
            ).put(
                "destructive_commit_pending",
                false
            ).put(
                "verified_not_committed",
                true
            ).put(
                "removal",
                removal
            )
        }

        // Before irreversible dispatch, STOP owns the outcome exactly as before.
        if (
            !actionDispatched &&
            shouldCancel()
        ) {
            return terminal(
                status = "CANCELLED",
                reason = "cancelled_during_close",
                message = "Закрытие отменено."
            ).put(
                "action_dispatched",
                false
            ).put(
                "action_committed",
                false
            ).put(
                "destructive_commit_pending",
                false
            ).put(
                "removal",
                removal
            )
        }

        if (
            !removal.optBoolean(
                "success",
                false
            ) ||
            !removal.optBoolean(
                "verified",
                false
            )
        ) {
            val context =
                if (
                    removalStatus !=
                        "CANCELLED" &&
                    !shouldCancel() &&
                    !actionDispatched
                ) {
                    restoreAfterFailedClose(
                        originalForegroundPackage =
                            sourcePackage
                    )
                } else if (
                    actionDispatched &&
                    verifiedNotCommitted &&
                    !shouldCancel()
                ) {
                    restoreAfterFailedClose(
                        originalForegroundPackage =
                            sourcePackage
                    )
                } else {
                    JSONObject()
                        .put(
                            "restored",
                            false
                        )
                        .put(
                            "mode",
                            if (actionDispatched) {
                                "post_dispatch_failure"
                            } else {
                                "cancelled"
                            }
                        )
                }

            val semanticStatus =
                when {
                    // Legacy/defensive rule: a lower layer is never allowed to
                    // return CANCELLED after reporting accepted dispatch unless
                    // it also proves the side effect did not commit.
                    actionDispatched &&
                        removalStatus == "CANCELLED" &&
                        !verifiedNotCommitted ->
                        "ERROR"

                    removalStatus == "BLOCKED" ->
                        "BLOCKED"

                    removalStatus == "UNSUPPORTED" ->
                        "UNSUPPORTED"

                    removalStatus == "CANCELLED" ->
                        "CANCELLED"

                    else ->
                        "ERROR"
                }

            val semanticReason =
                if (
                    actionDispatched &&
                    removalStatus == "CANCELLED" &&
                    !verifiedNotCommitted
                ) {
                    "dispatch_outcome_unverified"
                } else {
                    removal.optString(
                        "reason",
                        "task_removal_unverified"
                    )
                }

            return terminal(
                status = semanticStatus,
                reason = semanticReason,
                message =
                    if (semanticReason == "dispatch_outcome_unverified") {
                        "Команда удаления была передана Android, но её фактический результат не подтверждён."
                    } else {
                        removal.optString(
                            "message",
                            "Удаление задачи приложения не подтверждено."
                        )
                    }
            ).put(
                "action_dispatched",
                actionDispatched
            ).put(
                "action_committed",
                false
            ).put(
                "destructive_commit_pending",
                actionDispatched &&
                    !verifiedNotCommitted
            ).put(
                "verified_not_committed",
                verifiedNotCommitted
            ).put(
                "removal",
                removal
            ).put(
                "context_restored",
                context.optBoolean(
                    "restored",
                    false
                )
            ).put(
                "context",
                context
            )
        }

        val context =
            restoreAfterFailedClose(
                originalForegroundPackage =
                    sourcePackage
            )

        return terminal(
            status = "ERROR",
            reason = "task_removal_without_concrete_dismissal",
            message = "Закрытие не подтверждено фактическим удалением карточки приложения."
        ).put(
            "action_dispatched",
            actionDispatched
        ).put(
            "action_committed",
            false
        ).put(
            "destructive_commit_pending",
            actionDispatched
        ).put(
            "removal",
            removal
        ).put(
            "context_restored",
            context.optBoolean(
                "restored",
                false
            )
        ).put(
            "context",
            context
        )
    }

    private fun restoreAfterFailedClose(
        originalForegroundPackage: String
    ): JSONObject {

        if (shouldCancel()) {
            return JSONObject()
                .put(
                    "restored",
                    false
                )
                .put(
                    "mode",
                    "cancelled"
                )
        }

        val original =
            originalForegroundPackage
                .trim()

        if (original.isBlank()) {
            return JSONObject()
                .put(
                    "restored",
                    false
                )
                .put(
                    "mode",
                    "original_foreground_unknown"
                )
        }

        val observedBefore =
            currentPackage()

        if (
            observedBefore ==
                original
        ) {
            return JSONObject()
                .put(
                    "restored",
                    true
                )
                .put(
                    "mode",
                    "already_on_original_foreground"
                )
                .put(
                    "requested_package",
                    original
                )
                .put(
                    "observed_package",
                    observedBefore
                )
        }

        val launchAccepted =
            try {
                gateway.restorePackage(
                    original
                )
            } catch (_: Exception) {
                false
            }

        val restored =
            launchAccepted &&
                waitForPackage(
                    original
                )

        if (restored) {
            return JSONObject()
                .put(
                    "restored",
                    true
                )
                .put(
                    "mode",
                    "restore_original_after_failed_close"
                )
                .put(
                    "requested_package",
                    original
                )
                .put(
                    "observed_package",
                    original
                )
        }

        val homeAccepted =
            try {
                gateway.pressHome()
            } catch (_: Exception) {
                false
            }

        return JSONObject()
            .put(
                "restored",
                false
            )
            .put(
                "mode",
                if (homeAccepted) {
                    "home_fallback_after_failed_close"
                } else {
                    "restore_failed_after_failed_close"
                }
            )
            .put(
                "requested_package",
                original
            )
            .put(
                "observed_package",
                currentPackage()
            )
    }

    private fun restoreUserContext(
        targetPackage: String,
        originalForegroundPackage: String
    ): JSONObject {

        if (shouldCancel()) {
            return JSONObject()
                .put("restored", false)
                .put("mode", "cancelled")
        }

        val original =
            originalForegroundPackage.trim()

        if (
            original.isBlank() ||
            original == targetPackage
        ) {
            val accepted =
                try {
                    gateway.pressHome()
                } catch (_: Exception) {
                    false
                }

            val targetGoneFromForeground =
                waitForPackageMismatch(
                    targetPackage
                )

            return JSONObject()
                .put(
                    "restored",
                    accepted && targetGoneFromForeground
                )
                .put("mode", "home_after_target_close")
                .put("requested_package", original)
                .put("observed_package", currentPackage())
        }

        val launchAccepted =
            try {
                gateway.restorePackage(
                    original
                )
            } catch (_: Exception) {
                false
            }

        val restored =
            launchAccepted &&
                waitForPackage(
                    original
                )

        if (restored) {
            return JSONObject()
                .put("restored", true)
                .put("mode", "restore_original_foreground")
                .put("requested_package", original)
                .put("observed_package", original)
        }

        val homeAccepted =
            try {
                gateway.pressHome()
            } catch (_: Exception) {
                false
            }

        return JSONObject()
            .put("restored", false)
            .put(
                "mode",
                if (homeAccepted) {
                    "home_fallback_after_restore_failure"
                } else {
                    "restore_failed"
                }
            )
            .put("requested_package", original)
            .put("observed_package", currentPackage())
    }

    private fun inspectTopology(
        screen: JSONObject
    ): JSONObject {

        if (
            !screen.optBoolean(
                "success",
                false
            )
        ) {
            return JSONObject()
                .put("safe", false)
                .put("reason", "screen_state_unavailable")
                .put(
                    "message",
                    "Не могу надёжно определить текущий оконный режим, поэтому закрытие не выполняю."
                )
        }

        val windows =
            screen.optJSONArray(
                "windows"
            )

        if (
            windows == null ||
            windows.length() == 0
        ) {
            return JSONObject()
                .put("safe", false)
                .put("reason", "structured_window_context_missing")
                .put(
                    "message",
                    "Не удалось получить структурированный оконный контекст, поэтому закрытие не выполняю."
                )
        }

        val visibleApplicationPackages =
            linkedSetOf<String>()

        var pipDetected =
            false

        var splitScreenDividerDetected =
            false

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
                window.optBoolean(
                    "picture_in_picture",
                    false
                )
            ) {
                pipDetected =
                    true
            }

            val typeName =
                window.optString(
                    "type_name"
                )

            if (
                typeName ==
                "split_screen_divider"
            ) {
                splitScreenDividerDetected =
                    true
            }

            val packageName =
                window.optString(
                    "package"
                ).trim()

            val occlusion =
                window.optDouble(
                    "occlusion_ratio",
                    0.0
                )

            if (
                typeName == "application" &&
                packageName.isNotBlank() &&
                occlusion < 0.80
            ) {
                visibleApplicationPackages.add(
                    packageName
                )
            }
        }

        if (splitScreenDividerDetected) {
            return JSONObject()
                .put("safe", false)
                .put("reason", "split_screen_divider_detected")
                .put(
                    "message",
                    "В режиме разделённого экрана закрытие через список недавних заблокировано, чтобы не затронуть соседнее окно."
                )
        }

        if (pipDetected) {
            return JSONObject()
                .put("safe", false)
                .put("reason", "picture_in_picture_detected")
                .put(
                    "message",
                    "В режиме «картинка в картинке» закрытие через список недавних заблокировано для безопасности."
                )
        }

        if (
            visibleApplicationPackages.size >
            1
        ) {
            return JSONObject()
                .put("safe", false)
                .put("reason", "multi_window_detected")
                .put(
                    "message",
                    "В многооконном режиме закрытие через список недавних заблокировано, чтобы не затронуть соседнее окно."
                )
                .put(
                    "visible_application_packages",
                    visibleApplicationPackages.joinToString(",")
                )
        }

        return JSONObject()
            .put("safe", true)
            .put("mode", "single_window")
            .put(
                "visible_application_packages",
                visibleApplicationPackages.joinToString(",")
            )
    }

    private fun safeSnapshot(): JSONObject =
        try {
            gateway.screenSnapshot()
        } catch (_: Exception) {
            JSONObject()
                .put("success", false)
        }

    private fun currentPackage(): String =
        safeSnapshot()
            .optString(
                "package"
            )
            .trim()

    private fun waitForPackage(
        expectedPackage: String
    ): Boolean {

        val deadline =
            System.currentTimeMillis() +
                RESTORE_VERIFY_TIMEOUT_MS

        do {
            if (shouldCancel()) {
                return false
            }

            if (
                currentPackage() ==
                expectedPackage
            ) {
                return true
            }

            try {
                Thread.sleep(
                    RESTORE_VERIFY_POLL_MS
                )
            } catch (_: InterruptedException) {
                Thread.currentThread()
                    .interrupt()
                return false
            }
        } while (
            System.currentTimeMillis() <
            deadline
        )

        return false
    }

    private fun waitForPackageMismatch(
        targetPackage: String
    ): Boolean {

        val deadline =
            System.currentTimeMillis() +
                RESTORE_VERIFY_TIMEOUT_MS

        do {
            if (shouldCancel()) {
                return false
            }

            val current =
                currentPackage()

            if (
                current.isNotBlank() &&
                current != targetPackage
            ) {
                return true
            }

            try {
                Thread.sleep(
                    RESTORE_VERIFY_POLL_MS
                )
            } catch (_: InterruptedException) {
                Thread.currentThread()
                    .interrupt()
                return false
            }
        } while (
            System.currentTimeMillis() <
            deadline
        )

        return false
    }

    private fun terminal(
        status: String,
        reason: String,
        message: String
    ): JSONObject =
        JSONObject()
            .put(
                "success",
                status == "SUCCESS"
            )
            .put(
                "verified",
                status == "SUCCESS"
            )
            .put(
                "terminal_status",
                status
            )
            .put(
                "status",
                reason
            )
            .put(
                "reason",
                reason
            )
            .put(
                "message",
                message
            )

    companion object {
        private const val RESTORE_VERIFY_TIMEOUT_MS =
            1500L

        private const val RESTORE_VERIFY_POLL_MS =
            120L
    }
}
