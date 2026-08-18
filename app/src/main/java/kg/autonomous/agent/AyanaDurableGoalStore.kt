package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistent execution state for AYANA long-running goals.
 *
 * Design rules:
 * - one resumable goal at a time;
 * - every write is atomic via temp-file replacement;
 * - only bounded diagnostic/execution context is persisted;
 * - sensitive confirmation is never persisted as an approval;
 * - interrupted ACTIVE/EXECUTING goals become RECOVERY_PENDING instead of
 *   silently continuing after a reboot.
 */
class AyanaDurableGoalStore(
    context: Context
) {

    data class GoalView(
        val id: String,
        val command: String,
        val source: String,
        val mode: String,
        val status: String,
        val createdAt: Long,
        val updatedAt: Long,
        val agentSteps: Int,
        val totalActions: Int,
        val nextPlanStep: Int,
        val planSize: Int,
        val recoveryCount: Int,
        val safeAutoResume: Boolean,
        val requiresConfirmation: Boolean,
        val lastCheckpoint: String,
        val lastError: String,
        val recoveryReason: String
    )

    private val appContext =
        context.applicationContext

    private val file =
        File(
            appContext.filesDir,
            FILE_NAME
        )

    private val tempFile =
        File(
            appContext.filesDir,
            "$FILE_NAME.tmp"
        )

    private val backupFile =
        File(
            appContext.filesDir,
            "$FILE_NAME.bak"
        )

    private val lock =
        Any()

    fun startGoal(
        command: String,
        source: String,
        mode: String,
        safeAutoResume: Boolean
    ): JSONObject {

        val cleanCommand =
            command
                .trim()
                .take(
                    MAX_COMMAND_CHARS
                )

        require(
            cleanCommand.isNotBlank()
        ) {
            "Durable goal command is blank"
        }

        synchronized(lock) {

            val goals =
                loadUnsafe()

            // AYANA intentionally keeps one resumable goal. Starting another
            // device goal explicitly supersedes an older unfinished one so a
            // later "продолжи" can never revive the wrong task.
            val now =
                System.currentTimeMillis()

            for (index in 0 until goals.length()) {

                val old =
                    goals.optJSONObject(index)
                        ?: continue

                if (
                    isRecoverableStatus(
                        old.optString("status")
                    )
                ) {
                    old.put(
                        "status",
                        STATUS_CANCELLED
                    )
                    old.put(
                        "updated_at",
                        now
                    )
                    old.put(
                        "last_error",
                        "Заменена новой задачей"
                    )
                    old.put(
                        "last_checkpoint",
                        "superseded"
                    )
                }
            }

            val item =
                JSONObject()
                    .put(
                        "id",
                        UUID.randomUUID()
                            .toString()
                    )
                    .put(
                        "command",
                        cleanCommand
                    )
                    .put(
                        "source",
                        normalizeSource(
                            source
                        )
                    )
                    .put(
                        "mode",
                        normalizeMode(
                            mode
                        )
                    )
                    .put(
                        "status",
                        STATUS_ACTIVE
                    )
                    .put(
                        "created_at",
                        now
                    )
                    .put(
                        "updated_at",
                        now
                    )
                    .put(
                        "agent_steps",
                        0
                    )
                    .put(
                        "total_actions",
                        0
                    )
                    .put(
                        "recovery_count",
                        0
                    )
                    .put(
                        "safe_auto_resume",
                        safeAutoResume
                    )
                    .put(
                        "requires_confirmation",
                        false
                    )
                    .put(
                        "execution_trace",
                        ""
                    )
                    .put(
                        "last_tool_name",
                        ""
                    )
                    .put(
                        "last_tool_args",
                        JSONObject()
                    )
                    .put(
                        "latest_screen_package",
                        ""
                    )
                    .put(
                        "latest_screen_context",
                        ""
                    )
                    .put(
                        "last_tool_signature",
                        ""
                    )
                    .put(
                        "same_tool_repeat_count",
                        0
                    )
                    .put(
                        "last_checkpoint",
                        "started"
                    )
                    .put(
                        "last_error",
                        ""
                    )
                    .put(
                        "recovery_reason",
                        ""
                    )
                    .put(
                        "android_goal_arguments",
                        JSONObject()
                    )
                    .put(
                        "compiled_plan",
                        JSONObject()
                    )
                    .put(
                        "next_plan_step",
                        0
                    )
                    .put(
                        "plan_size",
                        0
                    )
                    .put(
                        "actions_used",
                        0
                    )
                    .put(
                        "android_goal_fallback_used",
                        false
                    )

            val next =
                JSONArray()
                    .put(
                        item
                    )

            var kept =
                1

            for (index in 0 until goals.length()) {

                if (
                    kept >=
                    MAX_GOALS
                ) {
                    break
                }

                val old =
                    goals.optJSONObject(index)
                        ?: continue

                if (
                    old.optString("id") ==
                    item.optString("id")
                ) {
                    continue
                }

                next.put(old)
                kept++
            }

            saveUnsafe(next)

            return JSONObject(
                item.toString()
            )
        }
    }

    fun checkpoint(
        id: String?,
        patch: JSONObject
    ): JSONObject? {

        if (
            id.isNullOrBlank()
        ) {
            return null
        }

        synchronized(lock) {

            val goals =
                loadUnsafe()

            val item =
                findById(
                    goals,
                    id
                )
                    ?: return null

            val keys =
                patch.keys()

            while (
                keys.hasNext()
            ) {

                val key =
                    keys.next()

                val value =
                    sanitizePatchValue(
                        key,
                        patch.opt(key)
                    )

                item.put(
                    key,
                    value
                )
            }

            item.put(
                "updated_at",
                System.currentTimeMillis()
            )

            saveUnsafe(goals)

            return JSONObject(
                item.toString()
            )
        }
    }

    fun checkpointOrchestrator(
        id: String?,
        agentSteps: Int,
        totalActions: Int,
        executionTrace: String,
        lastToolName: String,
        lastToolArgs: JSONObject,
        latestScreenPackage: String,
        safeAutoResume: Boolean,
        checkpoint: String,
        lastResult: String = ""
    ): JSONObject? {

        return checkpoint(
            id,
            JSONObject()
                .put(
                    "mode",
                    MODE_ORCHESTRATOR
                )
                .put(
                    "status",
                    STATUS_ACTIVE
                )
                .put(
                    "agent_steps",
                    agentSteps.coerceAtLeast(0)
                )
                .put(
                    "total_actions",
                    totalActions.coerceAtLeast(0)
                )
                .put(
                    "execution_trace",
                    executionTrace
                )
                .put(
                    "last_tool_name",
                    lastToolName
                )
                .put(
                    "last_tool_args",
                    JSONObject(
                        lastToolArgs.toString()
                    )
                )
                .put(
                    "latest_screen_package",
                    latestScreenPackage
                )
                .put(
                    "last_result",
                    lastResult
                )
                .put(
                    "safe_auto_resume",
                    safeAutoResume
                )
                .put(
                    "last_checkpoint",
                    checkpoint
                )
        )
    }

    fun attachAndroidPlan(
        id: String?,
        arguments: JSONObject,
        plan: JSONObject
    ): JSONObject? {

        val planSize =
            plan.optJSONArray("steps")
                ?.length()
                ?: 0

        return checkpoint(
            id,
            JSONObject()
                .put(
                    "mode",
                    MODE_ANDROID_GOAL
                )
                .put(
                    "android_goal_arguments",
                    JSONObject(
                        arguments.toString()
                    )
                )
                .put(
                    "compiled_plan",
                    JSONObject(
                        plan.toString()
                    )
                )
                .put(
                    "plan_size",
                    planSize
                )
                .put(
                    "last_checkpoint",
                    "plan_compiled"
                )
        )
    }

    fun checkpointAndroidStep(
        id: String?,
        checkpoint: JSONObject
    ): JSONObject? {

        return checkpoint(
            id,
            JSONObject()
                .put(
                    "mode",
                    MODE_ANDROID_GOAL
                )
                .put(
                    "status",
                    STATUS_ACTIVE
                )
                .put(
                    "next_plan_step",
                    checkpoint.optInt(
                        "next_step_index",
                        0
                    )
                )
                .put(
                    "actions_used",
                    checkpoint.optInt(
                        "actions_used",
                        0
                    )
                )
                .put(
                    "total_actions",
                    checkpoint.optInt(
                        "actions_used",
                        0
                    )
                )
                .put(
                    "latest_screen_package",
                    checkpoint.optString(
                        "screen_package"
                    )
                )
                .put(
                    "last_checkpoint",
                    checkpoint.optString(
                        "checkpoint",
                        "android_step"
                    )
                )
        )
    }

    fun markWaitingConfirmation(
        id: String?,
        reason: String
    ): JSONObject? =
        checkpoint(
            id,
            JSONObject()
                .put(
                    "status",
                    STATUS_WAITING_CONFIRMATION
                )
                .put(
                    "requires_confirmation",
                    true
                )
                .put(
                    "safe_auto_resume",
                    false
                )
                .put(
                    "last_error",
                    reason
                )
                .put(
                    "last_checkpoint",
                    "waiting_confirmation"
                )
        )

    fun markPaused(
        id: String?,
        reason: String
    ): JSONObject? =
        checkpoint(
            id,
            JSONObject()
                .put(
                    "status",
                    STATUS_PAUSED
                )
                .put(
                    "last_error",
                    reason
                )
                .put(
                    "last_checkpoint",
                    "paused"
                )
        )

    fun markRecoveryPending(
        id: String?,
        reason: String
    ): JSONObject? =
        checkpoint(
            id,
            JSONObject()
                .put(
                    "status",
                    STATUS_RECOVERY_PENDING
                )
                .put(
                    "recovery_reason",
                    reason.take(
                        MAX_SHORT_CHARS
                    )
                )
                .put(
                    "requires_confirmation",
                    false
                )
                .put(
                    "last_checkpoint",
                    "recovery_pending"
                )
        )

    /**
     * Called by service/boot startup. WAITING_CONFIRMATION is intentionally not
     * converted: a sensitive approval must never become resumable automatically.
     */
    fun markInterruptedGoals(
        reason: String
    ): Int {

        synchronized(lock) {

            val goals =
                loadUnsafe()

            var changed =
                0

            val now =
                System.currentTimeMillis()

            for (index in 0 until goals.length()) {

                val item =
                    goals.optJSONObject(index)
                        ?: continue

                val status =
                    normalizeStatus(
                        item.optString("status")
                    )

                if (
                    status ==
                    STATUS_ACTIVE
                ) {
                    item.put(
                        "status",
                        STATUS_RECOVERY_PENDING
                    )
                    item.put(
                        "recovery_reason",
                        reason.take(
                            MAX_SHORT_CHARS
                        )
                    )
                    item.put(
                        "updated_at",
                        now
                    )
                    item.put(
                        "last_checkpoint",
                        "interrupted"
                    )
                    changed++
                }
            }

            if (
                changed >
                0
            ) {
                saveUnsafe(goals)
            }

            return changed
        }
    }

    fun incrementRecovery(
        id: String?
    ): JSONObject? {

        if (
            id.isNullOrBlank()
        ) {
            return null
        }

        synchronized(lock) {

            val goals =
                loadUnsafe()

            val item =
                findById(
                    goals,
                    id
                )
                    ?: return null

            val next =
                item.optInt(
                    "recovery_count",
                    0
                ) +
                    1

            item.put(
                "recovery_count",
                next
            )
            item.put(
                "status",
                STATUS_ACTIVE
            )
            item.put(
                "requires_confirmation",
                false
            )
            item.put(
                "updated_at",
                System.currentTimeMillis()
            )
            item.put(
                "last_checkpoint",
                "recovery_started"
            )

            saveUnsafe(goals)

            return JSONObject(
                item.toString()
            )
        }
    }

    fun markCompleted(
        id: String?,
        result: String = ""
    ): JSONObject? =
        checkpoint(
            id,
            JSONObject()
                .put(
                    "status",
                    STATUS_SUCCESS
                )
                .put(
                    "requires_confirmation",
                    false
                )
                .put(
                    "last_error",
                    ""
                )
                .put(
                    "last_result",
                    result
                )
                .put(
                    "last_checkpoint",
                    "completed"
                )
        )

    fun markCancelled(
        id: String?,
        reason: String
    ): JSONObject? =
        checkpoint(
            id,
            JSONObject()
                .put(
                    "status",
                    STATUS_CANCELLED
                )
                .put(
                    "requires_confirmation",
                    false
                )
                .put(
                    "last_error",
                    reason
                )
                .put(
                    "last_checkpoint",
                    "cancelled"
                )
        )

    fun markFailed(
        id: String?,
        reason: String
    ): JSONObject? =
        checkpoint(
            id,
            JSONObject()
                .put(
                    "status",
                    STATUS_FAILED
                )
                .put(
                    "requires_confirmation",
                    false
                )
                .put(
                    "last_error",
                    reason
                )
                .put(
                    "last_checkpoint",
                    "failed"
                )
        )

    fun getRecoverable(): JSONObject? {

        synchronized(lock) {

            val goals =
                loadUnsafe()

            for (index in 0 until goals.length()) {

                val item =
                    goals.optJSONObject(index)
                        ?: continue

                if (
                    isRecoverableStatus(
                        item.optString("status")
                    )
                ) {
                    return JSONObject(
                        item.toString()
                    )
                }
            }

            return null
        }
    }

    fun getById(
        id: String?
    ): JSONObject? {

        if (
            id.isNullOrBlank()
        ) {
            return null
        }

        synchronized(lock) {

            val item =
                findById(
                    loadUnsafe(),
                    id
                )
                    ?: return null

            return JSONObject(
                item.toString()
            )
        }
    }

    fun getCurrentForUi(): GoalView? {

        val item =
            getRecoverable()
                ?: return null

        return GoalView(
            id = item.optString("id"),
            command = item.optString("command"),
            source = item.optString("source"),
            mode = item.optString("mode"),
            status = normalizeStatus(
                item.optString("status")
            ),
            createdAt = item.optLong("created_at"),
            updatedAt = item.optLong("updated_at"),
            agentSteps = item.optInt("agent_steps"),
            totalActions = maxOf(
                item.optInt("total_actions"),
                item.optInt("actions_used")
            ),
            nextPlanStep = item.optInt("next_plan_step"),
            planSize = item.optInt("plan_size"),
            recoveryCount = item.optInt("recovery_count"),
            safeAutoResume = item.optBoolean(
                "safe_auto_resume",
                false
            ),
            requiresConfirmation = item.optBoolean(
                "requires_confirmation",
                false
            ),
            lastCheckpoint = item.optString(
                "last_checkpoint"
            ),
            lastError = item.optString(
                "last_error"
            ),
            recoveryReason = item.optString(
                "recovery_reason"
            )
        )
    }

    fun canAutoResume(
        item: JSONObject?,
        now: Long = System.currentTimeMillis()
    ): Boolean {

        if (
            item == null ||
            !isRecoverableStatus(
                item.optString("status")
            ) ||
            item.optBoolean(
                "requires_confirmation",
                false
            ) ||
            !item.optBoolean(
                "safe_auto_resume",
                false
            ) ||
            item.optInt(
                "recovery_count",
                0
            ) >=
            MAX_RECOVERIES
        ) {
            return false
        }

        val status =
            normalizeStatus(
                item.optString("status")
            )

        if (
            status ==
            STATUS_WAITING_CONFIRMATION ||
            status ==
            STATUS_PAUSED
        ) {
            return false
        }

        val reason =
            item.optString(
                "recovery_reason"
            )
                .trim()
                .lowercase()

        if (
            reason ==
            "device_boot" ||
            reason ==
            "package_replaced"
        ) {
            return false
        }

        val age =
            (
                now -
                    item.optLong(
                        "updated_at",
                        0L
                    )
                )
                .coerceAtLeast(
                    0L
                )

        return age <=
            AUTO_RESUME_WINDOW_MS
    }

    fun confirmationIsFresh(
        item: JSONObject?,
        now: Long = System.currentTimeMillis()
    ): Boolean {

        if (
            item == null ||
            normalizeStatus(
                item.optString("status")
            ) !=
            STATUS_WAITING_CONFIRMATION
        ) {
            return false
        }

        val age =
            (
                now -
                    item.optLong(
                        "updated_at",
                        0L
                    )
                )
                .coerceAtLeast(
                    0L
                )

        return age <=
            CONFIRMATION_FRESH_MS
    }

    fun statusLabel(
        status: String
    ): String {

        return when (
            normalizeStatus(
                status
            )
        ) {
            STATUS_ACTIVE ->
                "Выполняется"
            STATUS_RECOVERY_PENDING ->
                "Можно продолжить"
            STATUS_PAUSED ->
                "Приостановлена"
            STATUS_WAITING_CONFIRMATION ->
                "Ждёт подтверждения"
            STATUS_SUCCESS ->
                "Завершена"
            STATUS_CANCELLED ->
                "Отменена"
            STATUS_FAILED ->
                "Ошибка"
            else ->
                "Неизвестно"
        }
    }

    private fun sanitizePatchValue(
        key: String,
        value: Any?
    ): Any {

        if (
            value == null ||
            value ==
            JSONObject.NULL
        ) {
            return JSONObject.NULL
        }

        return when (key) {

            "execution_trace" ->
                value.toString()
                    .takeLast(
                        MAX_TRACE_CHARS
                    )

            "latest_screen_context" ->
                value.toString()
                    .takeLast(
                        MAX_SCREEN_CONTEXT_CHARS
                    )

            "command" ->
                value.toString()
                    .take(
                        MAX_COMMAND_CHARS
                    )

            "last_error",
            "last_result",
            "last_checkpoint",
            "recovery_reason",
            "latest_screen_package",
            "last_tool_name",
            "last_tool_signature" ->
                value.toString()
                    .take(
                        MAX_SHORT_CHARS
                    )

            "last_tool_args",
            "android_goal_arguments",
            "compiled_plan" ->
                when (value) {
                    is JSONObject -> {
                        val serialized =
                            value.toString()

                        // Never truncate JSON text and then parse it: that can
                        // create invalid persistence data. These objects are
                        // already bounded by AYANA's tool/plan schemas. If an
                        // unexpected payload is excessively large, keep a safe
                        // empty object and preserve recovery metadata instead.
                        if (
                            serialized.length <=
                            MAX_JSON_CHARS
                        ) {
                            JSONObject(
                                serialized
                            )
                        } else {
                            JSONObject()
                        }
                    }
                    else ->
                        JSONObject()
                }

            else ->
                value
        }
    }

    private fun loadUnsafe(): JSONArray {

        val candidates =
            listOf(
                file,
                tempFile,
                backupFile
            )

        var sawExisting =
            false

        var bestFile:
            File? =
                null

        var bestGoals:
            JSONArray? =
                null

        var bestSavedAt =
            Long.MIN_VALUE

        // A complete .tmp may be newer than an intact but stale primary if the
        // process died after writeText() and before rename/copy commit. Parse all
        // available generations and choose the newest valid one. Primary wins a
        // timestamp tie because it is listed first.
        for (candidate in candidates) {

            if (!candidate.exists()) {
                continue
            }

            sawExisting =
                true

            try {
                val root =
                    JSONObject(
                        candidate.readText(
                            Charsets.UTF_8
                        )
                    )

                val goals =
                    root.optJSONArray(
                        "goals"
                    )
                        ?: JSONArray()

                val savedAt =
                    root.optLong(
                        "saved_at",
                        0L
                    )

                if (
                    bestGoals == null ||
                    savedAt >
                    bestSavedAt
                ) {
                    bestFile =
                        candidate
                    bestGoals =
                        goals
                    bestSavedAt =
                        savedAt
                }

            } catch (_: Exception) {
                // Invalid generations are ignored. A different generation may
                // still contain the last valid checkpoint.
            }
        }

        val selected =
            bestGoals

        if (selected != null) {

            if (bestFile != file) {
                try {
                    val healed =
                        JSONObject()
                            .put(
                                "schema_version",
                                SCHEMA_VERSION
                            )
                            .put(
                                "saved_at",
                                bestSavedAt.coerceAtLeast(
                                    System.currentTimeMillis()
                                )
                            )
                            .put(
                                "goals",
                                selected
                            )

                    file.writeText(
                        healed.toString(),
                        Charsets.UTF_8
                    )

                    if (bestFile == tempFile) {
                        tempFile.delete()
                    }
                } catch (_: Exception) {
                }
            }

            return selected
        }

        if (sawExisting) {
            // Preserve the damaged primary copy for diagnostics when possible.
            try {
                val broken =
                    File(
                        appContext.filesDir,
                        "$FILE_NAME.corrupt"
                    )

                if (
                    file.exists() &&
                    !broken.exists()
                ) {
                    file.copyTo(
                        broken,
                        overwrite = false
                    )
                }
            } catch (_: Exception) {
            }
        }

        return JSONArray()
    }

    private fun saveUnsafe(
        goals: JSONArray
    ) {

        val root =
            JSONObject()
                .put(
                    "schema_version",
                    SCHEMA_VERSION
                )
                .put(
                    "saved_at",
                    nextSavedAtUnsafe()
                )
                .put(
                    "goals",
                    goals
                )

        tempFile.writeText(
            root.toString(),
            Charsets.UTF_8
        )

        var movedPrimary =
            false

        if (file.exists()) {
            if (backupFile.exists()) {
                backupFile.delete()
            }

            movedPrimary =
                file.renameTo(
                    backupFile
                )

            if (!movedPrimary) {
                try {
                    file.copyTo(
                        backupFile,
                        overwrite = true
                    )
                } catch (_: Exception) {
                }
            }
        }

        var committed =
            tempFile.renameTo(
                file
            )

        if (!committed) {
            try {
                tempFile.copyTo(
                    file,
                    overwrite = true
                )
                committed =
                    true
            } catch (_: Exception) {
            }
        }

        if (committed) {
            tempFile.delete()
            // Keep exactly one previous valid generation as a last-known-good
            // snapshot. The next save replaces it with the then-current primary.
            // This protects not only against a crash during commit, but also
            // against later corruption of the primary durable-goal JSON.
            return
        }

        // Last-resort recovery: restore the previous valid primary if commit
        // failed. Keep .tmp so loadUnsafe() can also recover it later.
        if (
            !file.exists() &&
            backupFile.exists()
        ) {
            try {
                backupFile.copyTo(
                    file,
                    overwrite = true
                )
            } catch (_: Exception) {
            }
        }

        throw IllegalStateException(
            "Не удалось атомарно сохранить состояние активной цели"
        )
    }

    private fun findById(
        goals: JSONArray,
        id: String
    ): JSONObject? {

        for (index in 0 until goals.length()) {

            val item =
                goals.optJSONObject(index)
                    ?: continue

            if (
                item.optString("id") ==
                id
            ) {
                return item
            }
        }

        return null
    }

    private fun normalizeMode(
        value: String
    ): String {

        return when (
            value
                .trim()
                .lowercase()
        ) {
            MODE_ANDROID_GOAL ->
                MODE_ANDROID_GOAL
            else ->
                MODE_ORCHESTRATOR
        }
    }

    /**
     * File checkpoints can be written several times inside the same wall-clock
     * millisecond. A strictly increasing saved_at prevents a complete newer
     * .tmp from tying an older primary after a crash between write and commit.
     */
    private fun nextSavedAtUnsafe(): Long {

        var newest =
            0L

        for (candidate in listOf(file, tempFile, backupFile)) {
            if (!candidate.exists()) {
                continue
            }

            try {
                val savedAt =
                    JSONObject(
                        candidate.readText(
                            Charsets.UTF_8
                        )
                    ).optLong(
                        "saved_at",
                        0L
                    )

                if (savedAt > newest) {
                    newest =
                        savedAt
                }
            } catch (_: Exception) {
            }
        }

        val now =
            System.currentTimeMillis()

        return maxOf(
            now,
            if (newest == Long.MAX_VALUE) {
                newest
            } else {
                newest + 1L
            }
        )
    }

    private fun normalizeSource(
        value: String
    ): String {

        return when (
            value
                .trim()
                .lowercase()
        ) {
            "voice" ->
                "voice"
            else ->
                "text"
        }
    }

    private fun normalizeStatus(
        value: String
    ): String =
        value
            .trim()
            .lowercase()

    private fun isRecoverableStatus(
        value: String
    ): Boolean =
        normalizeStatus(value) in
            setOf(
                STATUS_ACTIVE,
                STATUS_RECOVERY_PENDING,
                STATUS_PAUSED,
                STATUS_WAITING_CONFIRMATION
            )

    companion object {

        const val MODE_ORCHESTRATOR =
            "orchestrator"

        const val MODE_ANDROID_GOAL =
            "android_goal"

        const val STATUS_ACTIVE =
            "active"

        const val STATUS_RECOVERY_PENDING =
            "recovery_pending"

        const val STATUS_PAUSED =
            "paused"

        const val STATUS_WAITING_CONFIRMATION =
            "waiting_confirmation"

        const val STATUS_SUCCESS =
            "success"

        const val STATUS_CANCELLED =
            "cancelled"

        const val STATUS_FAILED =
            "failed"

        const val MAX_RECOVERIES =
            2

        private const val SCHEMA_VERSION =
            1

        private const val FILE_NAME =
            "ayana_durable_goals.json"

        private const val MAX_GOALS =
            20

        private const val MAX_COMMAND_CHARS =
            1200

        private const val MAX_TRACE_CHARS =
            12000

        private const val MAX_SCREEN_CONTEXT_CHARS =
            7000

        private const val MAX_SHORT_CHARS =
            1200

        private const val MAX_JSON_CHARS =
            16000

        private const val AUTO_RESUME_WINDOW_MS =
            2 * 60 * 1000L

        private const val CONFIRMATION_FRESH_MS =
            2 * 60 * 1000L
    }
}
