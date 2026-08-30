package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Persistent command/event history for AYANA diagnostics.
 *
 * v2.7 — TERMINAL RECONCILIATION TRUTH:
 * - history terminal is reconciled against the latest Execution Kernel terminal;
 * - contradictory side-effect truth can never be persisted as SUCCESS/CANCELLED/
 *   BLOCKED/UNSUPPORTED;
 * - requested and effective terminals are stored separately when reconciliation
 *   changes the caller's requested status;
 * - v2.6 public API/storage bounds/export behavior are preserved.
 */
class AyanaCommandHistoryStore(
    context: Context
) {

    private val file =
        File(
            context.applicationContext.filesDir,
            "ayana_command_history.json"
        )

    private val lock = Any()

    fun begin(
        command: String,
        source: String
    ): String {
        val id =
            System.currentTimeMillis().toString() +
                "-" +
                UUID.randomUUID().toString().take(8)

        synchronized(lock) {
            val records = loadUnsafe()
            val now = System.currentTimeMillis()
            val record =
                JSONObject()
                    .put("id", id)
                    .put("started_at", now)
                    .put("finished_at", JSONObject.NULL)
                    .put("source", source)
                    .put("command", command)
                    .put("status", STATUS_RUNNING)
                    .put("success", JSONObject.NULL)
                    .put("duration_ms", JSONObject.NULL)
                    .put("result", "")
                    .put("technical", "")
                    .put(
                        "events",
                        JSONArray().put(
                            eventJson(
                                state = "received",
                                message = "Команда получена",
                                details = ""
                            )
                        )
                    )

            val next = JSONArray()
            next.put(record)
            val keep = minOf(records.length(), MAX_RECORDS - 1)
            for (index in 0 until keep) {
                records.optJSONObject(index)?.let { next.put(it) }
            }
            saveUnsafe(next)
        }

        return id
    }

    fun addEvent(
        id: String?,
        state: String,
        message: String,
        details: String = ""
    ) {
        if (id.isNullOrBlank()) return

        synchronized(lock) {
            val records = loadUnsafe()
            val record = findRecord(records, id) ?: return
            var events =
                record.optJSONArray("events")
                    ?: JSONArray().also { record.put("events", it) }

            val last = events.optJSONObject(events.length() - 1)
            if (
                last != null &&
                last.optString("state") == state &&
                last.optString("message") == message &&
                last.optString("details") == details
            ) {
                return
            }

            val storedDetails = compactDetailsForStorage(state, details)
            events.put(
                eventJson(
                    state = state,
                    message = message,
                    details = storedDetails.take(MAX_DETAILS_CHARS)
                )
            )

            if (events.length() > MAX_EVENTS_PER_RECORD) {
                val trimmed = JSONArray()
                for (index in 1 until events.length()) {
                    events.opt(index)?.let { trimmed.put(it) }
                }
                record.put("events", trimmed)
                events = trimmed
            }

            saveUnsafe(records)
        }
    }

    fun finish(
        id: String?,
        success: Boolean,
        result: String,
        technical: String = ""
    ) {
        finishWithStatus(
            id = id,
            status = if (success) STATUS_SUCCESS else STATUS_ERROR,
            success = success,
            result = result,
            technical = technical
        )
    }

    fun finishBlocked(
        id: String?,
        result: String,
        technical: String = ""
    ) {
        finishWithStatus(
            id = id,
            status = STATUS_BLOCKED,
            success = false,
            result = result,
            technical = technical
        )
    }

    fun finishUnsupported(
        id: String?,
        result: String,
        technical: String = ""
    ) {
        finishWithStatus(
            id = id,
            status = STATUS_UNSUPPORTED,
            success = false,
            result = result,
            technical = technical
        )
    }

    fun finishCancelled(
        id: String?,
        result: String,
        source: String
    ) {
        if (id.isNullOrBlank()) return

        addEvent(
            id = id,
            state = STATUS_CANCELLED,
            message = result,
            details = "cancel_source=$source"
        )

        finishWithStatus(
            id = id,
            status = STATUS_CANCELLED,
            success = false,
            result = result,
            technical = "cancel_source=$source"
        )
    }

    fun recent(limit: Int = 30): List<JSONObject> =
        synchronized(lock) {
            val records = loadUnsafe()
            val count = minOf(limit.coerceAtLeast(0), records.length())
            val result = ArrayList<JSONObject>(count)
            for (index in 0 until count) {
                records.optJSONObject(index)?.let {
                    result.add(JSONObject(it.toString()))
                }
            }
            result
        }

    fun count(): Int =
        synchronized(lock) {
            loadUnsafe().length()
        }

    fun delete(id: String): Boolean {
        if (id.isBlank()) return false

        synchronized(lock) {
            val records = loadUnsafe()
            val next = JSONArray()
            var removed = false
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                if (record.optString("id") == id) {
                    removed = true
                    continue
                }
                next.put(record)
            }
            if (removed) saveUnsafe(next)
            return removed
        }
    }

    /** Small structured continuity context for Agent Core. */
    fun contextForAgent(limit: Int = 8): String {
        val rows = recent(limit.coerceIn(1, 20))
        if (rows.isEmpty()) return "AYANA recent command context: empty"

        val latest =
            rows.firstOrNull { it.optString("status") != STATUS_RUNNING }
                ?: rows.first()

        val latestError =
            rows.firstOrNull { it.optString("status") == STATUS_ERROR }

        return buildString {
            append("AYANA recent command context: ")
            append("last_status=")
            append(latest.optString("status"))
            append("; last_command=")
            append(
                latest.optString("command")
                    .replace(Regex("\\s+"), " ")
                    .take(260)
            )
            append("; last_result=")
            append(
                latest.optString("result")
                    .replace(Regex("\\s+"), " ")
                    .take(420)
            )

            if (latestError != null) {
                append("; last_error_command=")
                append(
                    latestError.optString("command")
                        .replace(Regex("\\s+"), " ")
                        .take(260)
                )
                append("; last_error_result=")
                append(
                    latestError.optString("result")
                        .replace(Regex("\\s+"), " ")
                        .take(520)
                )
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            saveUnsafe(JSONArray())
        }
    }

    /** Compact, human-readable export. */
    fun exportRecent(limit: Int = 30): String {
        val formatter =
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            )
        val rows = recent(limit)
        if (rows.isEmpty()) {
            return "AYANA COMMAND HISTORY\nИстория команд пока пуста."
        }

        return buildString {
            append("AYANA COMMAND HISTORY\n")
            append("records=")
            append(rows.size)
            append("\n\n")

            rows.forEachIndexed { index, record ->
                val started = record.optLong("started_at", 0L)
                val status = record.optString("status", STATUS_RUNNING)

                append("#")
                append(index + 1)
                append(" ")
                append(exportStatusLabel(status))
                append("  ")
                if (started > 0L) append(formatter.format(Date(started)))

                append("\nsource=")
                append(record.optString("source"))
                append("\nduration_ms=")
                append(record.opt("duration_ms"))
                append("\ncommand=")
                append(record.optString("command"))
                append("\nresult=")
                append(record.optString("result"))

                if (record.optBoolean("result_truncated", false)) {
                    append("\nresult_meta=truncated; original_length=")
                    append(record.optInt("result_length", -1))
                }

                if (record.optBoolean("terminal_reconciled", false)) {
                    append("\nterminal_truth=requested=")
                    append(record.optString("terminal_requested"))
                    append("; effective=")
                    append(record.optString("terminal_effective"))
                    val sideEffect = record.optString("terminal_side_effect_state")
                    if (sideEffect.isNotBlank()) {
                        append("; side_effect_state=")
                        append(sideEffect)
                    }
                }

                val technical = record.optString("technical")
                if (technical.isNotBlank()) {
                    append("\ntechnical=")
                    append(technical.take(MAX_EXPORT_LINE_CHARS))
                }

                append("\nevents:\n")
                val events = record.optJSONArray("events") ?: JSONArray()
                for (eventIndex in 0 until events.length()) {
                    val event = events.optJSONObject(eventIndex) ?: continue
                    append("  - ")
                    val eventAt = event.optLong("at", 0L)
                    if (started > 0L && eventAt >= started) {
                        append("+")
                        append(eventAt - started)
                        append("ms ")
                    }
                    val state = event.optString("state")
                    append(state)
                    append(": ")
                    append(
                        compactEventMessageForExport(record, event)
                            .take(MAX_EXPORT_MESSAGE_CHARS)
                    )
                    val details =
                        compactDetailsForExport(
                            state = state,
                            details = event.optString("details")
                        )
                    if (details.isNotBlank()) {
                        append(" | ")
                        append(details)
                    }
                    append("\n")
                }
                append("\n")
            }
        }
    }

    private data class ExecutionTerminalEvidence(
        val terminal: String,
        val sideEffectState: String,
        val rawDetails: String
    )

    private data class TerminalResolution(
        val requested: String,
        val effective: String,
        val reconciled: Boolean,
        val sideEffectState: String,
        val reason: String
    )

    private fun finishWithStatus(
        id: String?,
        status: String,
        success: Boolean,
        result: String,
        technical: String
    ) {
        if (id.isNullOrBlank()) return

        synchronized(lock) {
            val records = loadUnsafe()
            val record = findRecord(records, id) ?: return

            val existingStatus = record.optString("status", STATUS_RUNNING)
            if (existingStatus != STATUS_RUNNING) return

            val terminalResolution =
                resolveTerminalTruth(
                    record = record,
                    requestedStatus = status,
                    requestedSuccess = success
                )

            val effectiveStatus = terminalResolution.effective
            val effectiveSuccess = effectiveStatus == STATUS_SUCCESS
            val effectiveTechnical =
                if (!terminalResolution.reconciled) {
                    technical
                } else {
                    buildString {
                        if (technical.isNotBlank()) {
                            append(technical.trim())
                            append("; ")
                        }
                        append("terminal_truth_reconciled=true")
                        append("; requested_terminal=")
                        append(terminalResolution.requested)
                        append("; effective_terminal=")
                        append(terminalResolution.effective)
                        if (terminalResolution.sideEffectState.isNotBlank()) {
                            append("; side_effect_state=")
                            append(terminalResolution.sideEffectState)
                        }
                        if (terminalResolution.reason.isNotBlank()) {
                            append("; reason=")
                            append(terminalResolution.reason)
                        }
                    }
                }

            val now = System.currentTimeMillis()
            val started = record.optLong("started_at", now)

            record
                .put("finished_at", now)
                .put("status", effectiveStatus)
                .put("success", effectiveSuccess)
                .put("duration_ms", (now - started).coerceAtLeast(0L))
                .put("result", result.take(MAX_RESULT_CHARS))
                .put("result_length", result.length)
                .put("result_truncated", result.length > MAX_RESULT_CHARS)
                .put("technical", effectiveTechnical.take(MAX_TECHNICAL_CHARS))
                .put("terminal_requested", terminalResolution.requested)
                .put("terminal_effective", terminalResolution.effective)
                .put("terminal_reconciled", terminalResolution.reconciled)
                .put("terminal_side_effect_state", terminalResolution.sideEffectState)

            val events =
                record.optJSONArray("events")
                    ?: JSONArray().also { record.put("events", it) }

            if (terminalResolution.reconciled) {
                events.put(
                    eventJson(
                        state = "terminal_truth_reconciled",
                        message = "Терминальный статус скорректирован по фактическому Execution Kernel state",
                        details = buildString {
                            append("requested=")
                            append(terminalResolution.requested)
                            append("; effective=")
                            append(terminalResolution.effective)
                            if (terminalResolution.sideEffectState.isNotBlank()) {
                                append("; side_effect_state=")
                                append(terminalResolution.sideEffectState)
                            }
                            if (terminalResolution.reason.isNotBlank()) {
                                append("; reason=")
                                append(terminalResolution.reason)
                            }
                        }.take(MAX_DETAILS_CHARS)
                    )
                )
            }

            val terminalMessage = terminalMessage(effectiveStatus)
            val lastEvent = events.optJSONObject(events.length() - 1)
            val terminalAlreadyLogged =
                lastEvent != null &&
                    lastEvent.optString("state") == effectiveStatus

            if (!terminalAlreadyLogged) {
                events.put(
                    eventJson(
                        state = effectiveStatus,
                        message = terminalMessage,
                        details = effectiveTechnical.take(MAX_DETAILS_CHARS)
                    )
                )
            }

            saveUnsafe(records)
        }
    }

    /**
     * The VoiceService records `execution_terminal` immediately after asking the
     * kernel to complete. Its details contain the kernel's effective terminal and
     * factual side_effect_state. History must trust that factual evidence over a
     * stale/requested boolean passed by a later presentation callback.
     */
    private fun resolveTerminalTruth(
        record: JSONObject,
        requestedStatus: String,
        requestedSuccess: Boolean
    ): TerminalResolution {
        val evidence = latestExecutionTerminalEvidence(record)
            ?: return TerminalResolution(
                requested = requestedStatus,
                effective = requestedStatus,
                reconciled = false,
                sideEffectState = "",
                reason = ""
            )

        val kernelMapped = mapKernelTerminal(evidence.terminal)
        var effective =
            if (kernelMapped != null && evidence.terminal != "RUNNING") {
                kernelMapped
            } else {
                requestedStatus
            }

        val sideEffect = evidence.sideEffectState
        var reason = ""

        fun forceError(value: String) {
            if (effective != STATUS_ERROR) {
                effective = STATUS_ERROR
            }
            reason = value
        }

        when (sideEffect) {
            "PREPARING" -> {
                if (effective == STATUS_SUCCESS) {
                    forceError("success_without_irreversible_dispatch")
                }
            }

            "DISPATCHING",
            "DISPATCHED",
            "RECONCILING" -> {
                if (effective != STATUS_ERROR) {
                    forceError("terminal_before_side_effect_reconciliation")
                }
            }

            "VERIFIED_NOT_COMMITTED" -> {
                if (effective == STATUS_SUCCESS) {
                    forceError("success_contradicts_verified_not_committed")
                }
            }

            "VERIFIED_COMMITTED" -> {
                if (
                    effective in setOf(
                        STATUS_CANCELLED,
                        STATUS_BLOCKED,
                        STATUS_UNSUPPORTED
                    )
                ) {
                    forceError("non_execution_terminal_contradicts_verified_commit")
                }
            }
        }

        // A RUNNING kernel after the irreversible boundary means semantic
        // cancellation/blocked/unsupported was rejected and reconciliation is
        // still required. If the caller is about to drop history ownership,
        // persist fail-closed ERROR rather than a factual lie.
        if (
            evidence.terminal == "RUNNING" &&
            sideEffect in setOf(
                "DISPATCHING",
                "DISPATCHED",
                "RECONCILING",
                "VERIFIED_COMMITTED"
            ) &&
            requestedStatus != STATUS_ERROR
        ) {
            forceError("kernel_terminal_still_running_after_irreversible_boundary")
        }

        val requestedWasSuccess = requestedSuccess || requestedStatus == STATUS_SUCCESS
        if (requestedWasSuccess && effective != STATUS_SUCCESS && reason.isBlank()) {
            reason = "execution_kernel_terminal_overrode_requested_success"
        }

        return TerminalResolution(
            requested = requestedStatus,
            effective = effective,
            reconciled = effective != requestedStatus,
            sideEffectState = sideEffect,
            reason = reason
        )
    }

    private fun latestExecutionTerminalEvidence(
        record: JSONObject
    ): ExecutionTerminalEvidence? {
        val events = record.optJSONArray("events") ?: return null
        for (index in events.length() - 1 downTo 0) {
            val event = events.optJSONObject(index) ?: continue
            if (event.optString("state") != "execution_terminal") continue

            val details = event.optString("details")
            val terminal =
                TERMINAL_PATTERN.find(details)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.uppercase(Locale.ROOT)
                    .orEmpty()
                    .ifBlank {
                        event.optString("message")
                            .trim()
                            .uppercase(Locale.ROOT)
                    }

            val sideEffect =
                SIDE_EFFECT_PATTERN.find(details)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.uppercase(Locale.ROOT)
                    .orEmpty()

            return ExecutionTerminalEvidence(
                terminal = terminal,
                sideEffectState = sideEffect,
                rawDetails = details
            )
        }
        return null
    }

    private fun mapKernelTerminal(value: String): String? =
        when (value.uppercase(Locale.ROOT)) {
            "SUCCESS" -> STATUS_SUCCESS
            "ERROR" -> STATUS_ERROR
            "BLOCKED" -> STATUS_BLOCKED
            "UNSUPPORTED" -> STATUS_UNSUPPORTED
            "CANCELLED" -> STATUS_CANCELLED
            else -> null
        }

    private fun exportStatusLabel(status: String): String =
        when (status) {
            STATUS_SUCCESS -> "SUCCESS"
            STATUS_ERROR -> "ERROR"
            STATUS_BLOCKED -> "BLOCKED"
            STATUS_UNSUPPORTED -> "UNSUPPORTED"
            STATUS_CANCELLED -> "CANCELLED"
            else -> "RUNNING"
        }

    private fun terminalMessage(status: String): String =
        when (status) {
            STATUS_SUCCESS -> "Команда завершена"
            STATUS_ERROR -> "Команда завершилась ошибкой"
            STATUS_BLOCKED -> "Команда заблокирована возможностями устройства"
            STATUS_UNSUPPORTED -> "Команда не поддерживается текущим набором исполнителей"
            STATUS_CANCELLED -> "Команда остановлена"
            else -> "Команда завершена"
        }

    private fun compactEventMessageForExport(
        record: JSONObject,
        event: JSONObject
    ): String {
        val state = event.optString("state")
        val message = event.optString("message")
        val result = record.optString("result")
        val terminal =
            state in setOf(
                STATUS_SUCCESS,
                STATUS_ERROR,
                STATUS_BLOCKED,
                STATUS_UNSUPPORTED,
                STATUS_CANCELLED
            )

        if (
            terminal &&
            result.isNotBlank() &&
            (message == result || message.take(600) == result.take(600))
        ) {
            return terminalMessage(state)
        }
        return message
    }

    private fun compactDetailsForStorage(
        state: String,
        details: String
    ): String {
        if (details.isBlank()) return ""
        if (state == "tool_call") return details.take(MAX_DETAILS_CHARS)
        if (state !in setOf("tool_result", "engine_result", "compiled_plan")) {
            return details.take(MAX_DETAILS_CHARS)
        }

        val json =
            try {
                JSONObject(details)
            } catch (_: Exception) {
                return details.replace("\n", " ").take(MAX_DETAILS_CHARS)
            }

        if (state == "compiled_plan") {
            val out = JSONObject()
            copyIfPresent(json, out, "goal")
            copyIfPresent(json, out, "max_actions")
            val steps = json.optJSONArray("steps")
            if (steps != null) {
                val compactSteps = JSONArray()
                for (index in 0 until minOf(steps.length(), 12)) {
                    val step = steps.optJSONObject(index) ?: continue
                    compactSteps.put(
                        JSONObject()
                            .put("id", step.optString("id"))
                            .put("action", step.optString("action"))
                            .put("terminal", step.optBoolean("terminal", false))
                            .put("targets", step.optJSONArray("targets") ?: JSONArray())
                    )
                }
                out.put("steps", compactSteps)
            }
            return out.toString().take(MAX_DETAILS_CHARS)
        }

        val out = JSONObject()
        listOf(
            "success",
            "status",
            "message",
            "screen_changed",
            "actions_used",
            "replan_recommended",
            "goal",
            "goal_type",
            "clicked_target",
            "requested_target",
            "resolved_click_target",
            "resolver_score"
        ).forEach { copyIfPresent(json, out, it) }

        val screen = json.optJSONObject("screen")
        if (screen != null) {
            val compactScreen = JSONObject()
            listOf(
                "success",
                "package",
                "root_class",
                "root_source",
                "window_count",
                "node_count",
                "message"
            ).forEach { copyIfPresent(screen, compactScreen, it) }

            val visible = screen.optJSONArray("visible_text")
            if (visible != null) {
                val compactVisible = JSONArray()
                for (index in 0 until minOf(visible.length(), 14)) {
                    compactVisible.put(visible.optString(index))
                }
                compactScreen.put("visible_text", compactVisible)
            }
            out.put("screen", compactScreen)
        }

        val trace = json.optJSONArray("trace")
        if (trace != null) {
            val compactTrace = JSONArray()
            for (index in 0 until minOf(trace.length(), 16)) {
                val item = trace.optJSONObject(index) ?: continue
                compactTrace.put(
                    JSONObject()
                        .put("id", item.optString("id"))
                        .put("action", item.optString("action"))
                        .put("success", item.optBoolean("success", false))
                        .put("message", item.optString("message").take(180))
                )
            }
            out.put("trace", compactTrace)
        }

        return out.toString().take(MAX_DETAILS_CHARS)
    }

    private fun compactDetailsForExport(
        state: String,
        details: String
    ): String {
        if (details.isBlank()) return ""
        if (state == "tool_call") return details.take(MAX_EXPORT_LINE_CHARS)

        val json =
            try {
                JSONObject(details)
            } catch (_: Exception) {
                return details.replace("\n", " ").take(MAX_EXPORT_LINE_CHARS)
            }

        val out = JSONObject()
        copyIfPresent(json, out, "success")
        copyIfPresent(json, out, "status")
        copyIfPresent(json, out, "message")
        copyIfPresent(json, out, "screen_changed")
        copyIfPresent(json, out, "actions_used")
        copyIfPresent(json, out, "replan_recommended")
        copyIfPresent(json, out, "goal")

        val screen = json.optJSONObject("screen")
        if (screen != null) {
            if (screen.has("package")) out.put("package", screen.optString("package"))
            if (screen.has("message") && screen.optString("message").isNotBlank()) {
                out.put("screen_message", screen.optString("message"))
            }
            if (screen.has("root_source")) out.put("root_source", screen.optString("root_source"))
            if (screen.has("window_count")) out.put("window_count", screen.optInt("window_count", 0))

            val visible = screen.optJSONArray("visible_text")
            if (visible != null) {
                val compactVisible = JSONArray()
                for (index in 0 until minOf(visible.length(), 12)) {
                    compactVisible.put(visible.optString(index))
                }
                out.put("visible_text", compactVisible)
            }
        }

        val trace = json.optJSONArray("trace")
        if (trace != null) {
            val compactTrace = JSONArray()
            for (index in 0 until minOf(trace.length(), 8)) {
                val item = trace.optJSONObject(index) ?: continue
                compactTrace.put(
                    JSONObject()
                        .put("id", item.optString("id"))
                        .put("action", item.optString("action"))
                        .put("success", item.optBoolean("success", false))
                        .put("message", item.optString("message").take(160))
                )
            }
            out.put("trace", compactTrace)
        }

        if (out.length() == 0) {
            return details.replace("\n", " ").take(MAX_EXPORT_LINE_CHARS)
        }
        return out.toString().take(MAX_EXPORT_LINE_CHARS)
    }

    private fun copyIfPresent(
        source: JSONObject,
        target: JSONObject,
        key: String
    ) {
        if (source.has(key) && !source.isNull(key)) {
            target.put(key, source.opt(key))
        }
    }

    private fun eventJson(
        state: String,
        message: String,
        details: String
    ): JSONObject =
        JSONObject()
            .put("at", System.currentTimeMillis())
            .put("state", state)
            .put("message", message)
            .put("details", details)

    private fun findRecord(
        records: JSONArray,
        id: String
    ): JSONObject? {
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            if (record.optString("id") == id) return record
        }
        return null
    }

    private fun loadUnsafe(): JSONArray {
        if (!file.exists()) return JSONArray()
        return try {
            val text = file.readText(Charsets.UTF_8).trim()
            if (text.isBlank()) JSONArray() else JSONArray(text)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun saveUnsafe(records: JSONArray) {
        try {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(records.toString(), Charsets.UTF_8)
            if (file.exists()) file.delete()
            temp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_ERROR = "error"
        const val STATUS_BLOCKED = "blocked"
        const val STATUS_UNSUPPORTED = "unsupported"
        const val STATUS_CANCELLED = "cancelled"

        private val TERMINAL_PATTERN =
            Regex("(?:^|;\\s*)terminal=([A-Z_]+)")

        private val SIDE_EFFECT_PATTERN =
            Regex("(?:^|;\\s*)side_effect_state=([A-Z_]+)")

        private const val MAX_RECORDS = 120
        private const val MAX_EVENTS_PER_RECORD = 100
        private const val MAX_DETAILS_CHARS = 5000
        private const val MAX_RESULT_CHARS = 2500
        private const val MAX_TECHNICAL_CHARS = 7000
        private const val MAX_EXPORT_LINE_CHARS = 1400
        private const val MAX_EXPORT_MESSAGE_CHARS = 600
    }
}
