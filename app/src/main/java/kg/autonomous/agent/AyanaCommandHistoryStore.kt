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
 * v2:
 * - SUCCESS / ERROR / CANCELLED are explicit statuses;
 * - copied diagnostics are compact (raw JSON is still stored internally);
 * - no result is classified by searching words such as "ошибка" inside a reply.
 */
class AyanaCommandHistoryStore(
    context: Context
) {

    private val file =
        File(
            context.applicationContext.filesDir,
            "ayana_command_history.json"
        )

    private val lock =
        Any()

    fun begin(
        command: String,
        source: String
    ): String {

        val id =
            System.currentTimeMillis().toString() +
                "-" +
                UUID.randomUUID()
                    .toString()
                    .take(
                        8
                    )

        synchronized(
            lock
        ) {

            val records =
                loadUnsafe()

            val now =
                System.currentTimeMillis()

            val record =
                JSONObject()
                    .put(
                        "id",
                        id
                    )
                    .put(
                        "started_at",
                        now
                    )
                    .put(
                        "finished_at",
                        JSONObject.NULL
                    )
                    .put(
                        "source",
                        source
                    )
                    .put(
                        "command",
                        command
                    )
                    .put(
                        "status",
                        STATUS_RUNNING
                    )
                    .put(
                        "success",
                        JSONObject.NULL
                    )
                    .put(
                        "duration_ms",
                        JSONObject.NULL
                    )
                    .put(
                        "result",
                        ""
                    )
                    .put(
                        "technical",
                        ""
                    )
                    .put(
                        "events",
                        JSONArray()
                            .put(
                                eventJson(
                                    state =
                                        "received",
                                    message =
                                        "Команда получена",
                                    details =
                                        ""
                                )
                            )
                    )

            val next =
                JSONArray()

            next.put(
                record
            )

            val keep =
                minOf(
                    records.length(),
                    MAX_RECORDS -
                        1
                )

            for (
                index in
                0 until keep
            ) {

                records
                    .optJSONObject(
                        index
                    )
                    ?.let {
                        next.put(
                            it
                        )
                    }
            }

            saveUnsafe(
                next
            )
        }

        return id
    }

    fun addEvent(
        id: String?,
        state: String,
        message: String,
        details: String = ""
    ) {

        if (
            id.isNullOrBlank()
        ) {
            return
        }

        synchronized(
            lock
        ) {

            val records =
                loadUnsafe()

            val record =
                findRecord(
                    records,
                    id
                )
                    ?: return

            var events =
                record.optJSONArray(
                    "events"
                )
                    ?: JSONArray()
                        .also {
                            record.put(
                                "events",
                                it
                            )
                        }

            val last =
                events.optJSONObject(
                    events.length() -
                        1
                )

            if (
                last != null &&
                last.optString(
                    "state"
                ) == state &&
                last.optString(
                    "message"
                ) == message &&
                last.optString(
                    "details"
                ) == details
            ) {
                return
            }

            events.put(
                eventJson(
                    state =
                        state,
                    message =
                        message,
                    details =
                        details.take(
                            MAX_DETAILS_CHARS
                        )
                )
            )

            if (
                events.length() >
                MAX_EVENTS_PER_RECORD
            ) {

                val trimmed =
                    JSONArray()

                for (
                    index in
                    1 until events.length()
                ) {

                    events
                        .opt(
                            index
                        )
                        ?.let {
                            trimmed.put(
                                it
                            )
                        }
                }

                record.put(
                    "events",
                    trimmed
                )

                events =
                    trimmed
            }

            saveUnsafe(
                records
            )
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
            status =
                if (
                    success
                ) {
                    STATUS_SUCCESS
                } else {
                    STATUS_ERROR
                },
            success =
                success,
            result =
                result,
            technical =
                technical
        )
    }

    fun finishCancelled(
        id: String?,
        result: String,
        source: String
    ) {

        if (
            id.isNullOrBlank()
        ) {
            return
        }

        addEvent(
            id = id,
            state =
                STATUS_CANCELLED,
            message =
                result,
            details =
                "cancel_source=$source"
        )

        finishWithStatus(
            id = id,
            status =
                STATUS_CANCELLED,
            success =
                false,
            result =
                result,
            technical =
                "cancel_source=$source"
        )
    }

    fun recent(
        limit: Int = 30
    ): List<JSONObject> =
        synchronized(
            lock
        ) {

            val records =
                loadUnsafe()

            val count =
                minOf(
                    limit.coerceAtLeast(
                        0
                    ),
                    records.length()
                )

            val result =
                ArrayList<JSONObject>(
                    count
                )

            for (
                index in
                0 until count
            ) {

                records
                    .optJSONObject(
                        index
                    )
                    ?.let {
                        result.add(
                            JSONObject(
                                it.toString()
                            )
                        )
                    }
            }

            result
        }

    fun count():
        Int =
        synchronized(
            lock
        ) {
            loadUnsafe()
                .length()
        }

    fun clear() {

        synchronized(
            lock
        ) {
            saveUnsafe(
                JSONArray()
            )
        }
    }

    /**
     * Compact, human-readable export. The app-private history file still keeps
     * raw event details (bounded), but clipboard export removes huge node trees.
     */
    fun exportRecent(
        limit: Int = 30
    ): String {

        val formatter =
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            )

        val rows =
            recent(
                limit
            )

        if (
            rows.isEmpty()
        ) {

            return "AYANA COMMAND HISTORY\nИстория команд пока пуста."
        }

        return buildString {

            append(
                "AYANA COMMAND HISTORY\n"
            )

            append(
                "records="
            )

            append(
                rows.size
            )

            append(
                "\n\n"
            )

            rows.forEachIndexed {
                index,
                record ->

                val started =
                    record.optLong(
                        "started_at",
                        0L
                    )

                val status =
                    record.optString(
                        "status",
                        STATUS_RUNNING
                    )

                append(
                    "#"
                )

                append(
                    index +
                        1
                )

                append(
                    " "
                )

                append(
                    when (
                        status
                    ) {

                        STATUS_SUCCESS ->
                            "SUCCESS"

                        STATUS_ERROR ->
                            "ERROR"

                        STATUS_CANCELLED ->
                            "CANCELLED"

                        else ->
                            "RUNNING"
                    }
                )

                append(
                    "  "
                )

                if (
                    started >
                    0L
                ) {

                    append(
                        formatter.format(
                            Date(
                                started
                            )
                        )
                    )
                }

                append(
                    "\nsource="
                )

                append(
                    record.optString(
                        "source"
                    )
                )

                append(
                    "\nduration_ms="
                )

                append(
                    record.opt(
                        "duration_ms"
                    )
                )

                append(
                    "\ncommand="
                )

                append(
                    record.optString(
                        "command"
                    )
                )

                append(
                    "\nresult="
                )

                append(
                    record.optString(
                        "result"
                    )
                )

                val technical =
                    record.optString(
                        "technical"
                    )

                if (
                    technical.isNotBlank()
                ) {

                    append(
                        "\ntechnical="
                    )

                    append(
                        technical.take(
                            MAX_EXPORT_LINE_CHARS
                        )
                    )
                }

                append(
                    "\nevents:\n"
                )

                val events =
                    record.optJSONArray(
                        "events"
                    )
                        ?: JSONArray()

                for (
                    eventIndex in
                    0 until events.length()
                ) {

                    val event =
                        events.optJSONObject(
                            eventIndex
                        )
                            ?: continue

                    append(
                        "  - "
                    )

                    val state =
                        event.optString(
                            "state"
                        )

                    append(
                        state
                    )

                    append(
                        ": "
                    )

                    append(
                        event.optString(
                            "message"
                        )
                            .take(
                                MAX_EXPORT_MESSAGE_CHARS
                            )
                    )

                    val details =
                        compactDetailsForExport(
                            state =
                                state,
                            details =
                                event.optString(
                                    "details"
                                )
                        )

                    if (
                        details.isNotBlank()
                    ) {

                        append(
                            " | "
                        )

                        append(
                            details
                        )
                    }

                    append(
                        "\n"
                    )
                }

                append(
                    "\n"
                )
            }
        }
    }

    private fun finishWithStatus(
        id: String?,
        status: String,
        success: Boolean,
        result: String,
        technical: String
    ) {

        if (
            id.isNullOrBlank()
        ) {
            return
        }

        synchronized(
            lock
        ) {

            val records =
                loadUnsafe()

            val record =
                findRecord(
                    records,
                    id
                )
                    ?: return

            // Do not overwrite a terminal record from a late/stale callback.
            val existingStatus =
                record.optString(
                    "status",
                    STATUS_RUNNING
                )

            if (
                existingStatus !=
                STATUS_RUNNING
            ) {
                return
            }

            val now =
                System.currentTimeMillis()

            val started =
                record.optLong(
                    "started_at",
                    now
                )

            record
                .put(
                    "finished_at",
                    now
                )
                .put(
                    "status",
                    status
                )
                .put(
                    "success",
                    success
                )
                .put(
                    "duration_ms",
                    (
                        now -
                            started
                        )
                        .coerceAtLeast(
                            0L
                        )
                )
                .put(
                    "result",
                    result.take(
                        MAX_RESULT_CHARS
                    )
                )
                .put(
                    "technical",
                    technical.take(
                        MAX_TECHNICAL_CHARS
                    )
                )

            val events =
                record.optJSONArray(
                    "events"
                )
                    ?: JSONArray()
                        .also {
                            record.put(
                                "events",
                                it
                            )
                        }

            val terminalMessage =
                result.take(
                    600
                )

            val lastEvent =
                events.optJSONObject(
                    events.length() -
                        1
                )

            val terminalAlreadyLogged =
                lastEvent != null &&
                    lastEvent.optString(
                        "state"
                    ) ==
                    status &&
                    lastEvent.optString(
                        "message"
                    ) ==
                    terminalMessage

            if (
                !terminalAlreadyLogged
            ) {

                events.put(
                    eventJson(
                        state =
                            status,
                        message =
                            terminalMessage,
                        details =
                            technical.take(
                                MAX_DETAILS_CHARS
                            )
                    )
                )
            }

            saveUnsafe(
                records
            )
        }
    }

    private fun compactDetailsForExport(
        state: String,
        details: String
    ): String {

        if (
            details.isBlank()
        ) {
            return ""
        }

        if (
            state ==
            "tool_call"
        ) {
            return details.take(
                MAX_EXPORT_LINE_CHARS
            )
        }

        val json =
            try {
                JSONObject(
                    details
                )
            } catch (_: Exception) {
                return details
                    .replace(
                        "\n",
                        " "
                    )
                    .take(
                        MAX_EXPORT_LINE_CHARS
                    )
            }

        val out =
            JSONObject()

        copyIfPresent(
            source = json,
            target = out,
            key = "success"
        )

        copyIfPresent(
            source = json,
            target = out,
            key = "status"
        )

        copyIfPresent(
            source = json,
            target = out,
            key = "message"
        )

        copyIfPresent(
            source = json,
            target = out,
            key = "screen_changed"
        )

        copyIfPresent(
            source = json,
            target = out,
            key = "actions_used"
        )

        copyIfPresent(
            source = json,
            target = out,
            key = "replan_recommended"
        )

        copyIfPresent(
            source = json,
            target = out,
            key = "goal"
        )

        val screen =
            json.optJSONObject(
                "screen"
            )

        if (
            screen != null
        ) {

            if (
                screen.has(
                    "package"
                )
            ) {
                out.put(
                    "package",
                    screen.optString(
                        "package"
                    )
                )
            }

            if (
                screen.has(
                    "message"
                ) &&
                !screen.optString(
                    "message"
                ).isNullOrBlank()
            ) {
                out.put(
                    "screen_message",
                    screen.optString(
                        "message"
                    )
                )
            }

            if (
                screen.has(
                    "root_source"
                )
            ) {
                out.put(
                    "root_source",
                    screen.optString(
                        "root_source"
                    )
                )
            }

            if (
                screen.has(
                    "window_count"
                )
            ) {
                out.put(
                    "window_count",
                    screen.optInt(
                        "window_count",
                        0
                    )
                )
            }

            val visible =
                screen.optJSONArray(
                    "visible_text"
                )

            if (
                visible != null
            ) {

                val compactVisible =
                    JSONArray()

                val count =
                    minOf(
                        visible.length(),
                        12
                    )

                for (
                    index in
                    0 until count
                ) {

                    compactVisible.put(
                        visible.optString(
                            index
                        )
                    )
                }

                out.put(
                    "visible_text",
                    compactVisible
                )
            }
        }

        val trace =
            json.optJSONArray(
                "trace"
            )

        if (
            trace != null
        ) {

            val compactTrace =
                JSONArray()

            for (
                index in
                0 until minOf(
                    trace.length(),
                    8
                )
            ) {

                val item =
                    trace.optJSONObject(
                        index
                    )
                        ?: continue

                compactTrace.put(
                    JSONObject()
                        .put(
                            "id",
                            item.optString(
                                "id"
                            )
                        )
                        .put(
                            "action",
                            item.optString(
                                "action"
                            )
                        )
                        .put(
                            "success",
                            item.optBoolean(
                                "success",
                                false
                            )
                        )
                        .put(
                            "message",
                            item.optString(
                                "message"
                            )
                                .take(
                                    160
                                )
                        )
                )
            }

            out.put(
                "trace",
                compactTrace
            )
        }

        if (
            out.length() ==
            0
        ) {

            return details
                .replace(
                    "\n",
                    " "
                )
                .take(
                    MAX_EXPORT_LINE_CHARS
                )
        }

        return out.toString()
            .take(
                MAX_EXPORT_LINE_CHARS
            )
    }

    private fun copyIfPresent(
        source: JSONObject,
        target: JSONObject,
        key: String
    ) {

        if (
            source.has(
                key
            ) &&
            !source.isNull(
                key
            )
        ) {

            target.put(
                key,
                source.opt(
                    key
                )
            )
        }
    }

    private fun eventJson(
        state: String,
        message: String,
        details: String
    ): JSONObject =
        JSONObject()
            .put(
                "at",
                System.currentTimeMillis()
            )
            .put(
                "state",
                state
            )
            .put(
                "message",
                message
            )
            .put(
                "details",
                details
            )

    private fun findRecord(
        records: JSONArray,
        id: String
    ): JSONObject? {

        for (
            index in
            0 until records.length()
        ) {

            val record =
                records.optJSONObject(
                    index
                )
                    ?: continue

            if (
                record.optString(
                    "id"
                ) == id
            ) {
                return record
            }
        }

        return null
    }

    private fun loadUnsafe():
        JSONArray {

        if (
            !file.exists()
        ) {
            return JSONArray()
        }

        return try {

            val text =
                file.readText(
                    Charsets.UTF_8
                )
                    .trim()

            if (
                text.isBlank()
            ) {
                JSONArray()
            } else {
                JSONArray(
                    text
                )
            }

        } catch (_: Exception) {

            JSONArray()
        }
    }

    private fun saveUnsafe(
        records: JSONArray
    ) {

        try {

            val temp =
                File(
                    file.parentFile,
                    file.name +
                        ".tmp"
                )

            temp.writeText(
                records.toString(),
                Charsets.UTF_8
            )

            if (
                file.exists()
            ) {
                file.delete()
            }

            temp.renameTo(
                file
            )

        } catch (_: Exception) {
        }
    }

    companion object {

        const val STATUS_RUNNING =
            "running"

        const val STATUS_SUCCESS =
            "success"

        const val STATUS_ERROR =
            "error"

        const val STATUS_CANCELLED =
            "cancelled"

        private const val MAX_RECORDS =
            120

        private const val MAX_EVENTS_PER_RECORD =
            50

        private const val MAX_DETAILS_CHARS =
            5000

        private const val MAX_RESULT_CHARS =
            2500

        private const val MAX_TECHNICAL_CHARS =
            7000

        private const val MAX_EXPORT_LINE_CHARS =
            1400

        private const val MAX_EXPORT_MESSAGE_CHARS =
            600
    }
}
