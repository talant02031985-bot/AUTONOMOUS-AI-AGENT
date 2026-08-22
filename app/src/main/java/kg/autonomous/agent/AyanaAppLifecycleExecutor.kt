package kg.autonomous.agent

import org.json.JSONObject

/**
 * AYANA App Lifecycle Executor v1.0 — verified user-task removal.
 *
 * Android third-party apps cannot honestly claim cross-app force-stop/process kill.
 * This executor implements the user-visible close contract as removal of the app's
 * task card from Android Recents, then restores the user's prior foreground context.
 * SUCCESS is emitted only when the Recents layer reports verified task removal (or
 * verified absence after an exhaustive bounded scan). All uncertain topologies fail closed.
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
                    .put(
                        "message",
                        error.message
                            ?: "Ошибка удаления задачи из недавних"
                    )
            }

        if (shouldCancel()) {
            return terminal(
                status = "CANCELLED",
                reason = "cancelled_during_close",
                message = "Закрытие отменено."
            ).put(
                "removal",
                removal
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
            return terminal(
                status =
                    when (removalStatus) {
                        "BLOCKED" -> "BLOCKED"
                        "UNSUPPORTED" -> "UNSUPPORTED"
                        "CANCELLED" -> "CANCELLED"
                        else -> "ERROR"
                    },
                reason = removal.optString(
                    "reason",
                    "task_removal_unverified"
                ),
                message = removal.optString(
                    "message",
                    "Удаление задачи приложения не подтверждено."
                )
            ).put(
                "removal",
                removal
            )
        }

        val concreteDismissal =
            removal.optInt(
                "removed_count",
                0
            ) > 0 &&
                removal.optString(
                    "reason"
                ) ==
                "verified_task_removed"

        if (!concreteDismissal) {
            return terminal(
                status = "ERROR",
                reason = "task_removal_without_concrete_dismissal",
                message = "Закрытие не подтверждено фактическим удалением карточки приложения."
            ).put(
                "removal",
                removal
            )
        }

        val context =
            restoreUserContext(
                targetPackage = packageName,
                originalForegroundPackage = sourcePackage
            )

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", "task_removed")
            .put("reason", removal.optString("reason", "verified_task_removed"))
            .put("message", "$label закрыт.")
            .put("target_package", packageName)
            .put("target_label", label)
            .put("method", "verified_recents_task_removal")
            .put("removal", removal)
            .put("context_restored", context.optBoolean("restored", false))
            .put("context", context)
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
