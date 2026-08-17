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
 * Stores a bounded JSON array in app-private storage so the user can inspect
 * exactly what AYANA heard, thought, executed and how the task finished.
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
            System.currentTimeMillis().toString() + "-" +
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
                    .put("status", "running")
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
            val events = record.optJSONArray("events") ?: JSONArray().also {
                record.put("events", it)
            }

            val last = events.optJSONObject(events.length() - 1)
            if (
                last != null &&
                last.optString("state") == state &&
                last.optString("message") == message &&
                last.optString("details") == details
            ) {
                return
            }

            events.put(
                eventJson(
                    state = state,
                    message = message,
                    details = details.take(MAX_DETAILS_CHARS)
                )
            )
            while (events.length() > MAX_EVENTS_PER_RECORD) {
                val trimmed = JSONArray()
                for (index in 1 until events.length()) {
                    events.opt(index)?.let { trimmed.put(it) }
                }
                record.put("events", trimmed)
                break
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
        if (id.isNullOrBlank()) return
        synchronized(lock) {
            val records = loadUnsafe()
            val record = findRecord(records, id) ?: return
            val now = System.currentTimeMillis()
            val started = record.optLong("started_at", now)
            record
                .put("finished_at", now)
                .put("status", if (success) "success" else "error")
                .put("success", success)
                .put("duration_ms", (now - started).coerceAtLeast(0L))
                .put("result", result.take(MAX_RESULT_CHARS))
                .put("technical", technical.take(MAX_TECHNICAL_CHARS))

            val events = record.optJSONArray("events") ?: JSONArray().also {
                record.put("events", it)
            }
            events.put(
                eventJson(
                    state = if (success) "success" else "error",
                    message = result.take(600),
                    details = technical.take(MAX_DETAILS_CHARS)
                )
            )
            saveUnsafe(records)
        }
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

    fun clear() {
        synchronized(lock) {
            saveUnsafe(JSONArray())
        }
    }

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
            append("records=").append(rows.size).append("\n\n")

            rows.forEachIndexed { index, record ->
                val started = record.optLong("started_at", 0L)
                append("#").append(index + 1).append(" ")
                append(if (record.optBoolean("success", false)) "SUCCESS" else record.optString("status", "RUNNING").uppercase(Locale.ROOT))
                append("  ")
                if (started > 0L) append(formatter.format(Date(started)))
                append("\nsource=").append(record.optString("source"))
                append("\nduration_ms=").append(record.opt("duration_ms"))
                append("\ncommand=").append(record.optString("command"))
                append("\nresult=").append(record.optString("result"))
                val technical = record.optString("technical")
                if (technical.isNotBlank()) {
                    append("\ntechnical=").append(technical)
                }
                append("\nevents:\n")
                val events = record.optJSONArray("events") ?: JSONArray()
                for (eventIndex in 0 until events.length()) {
                    val event = events.optJSONObject(eventIndex) ?: continue
                    append("  - ")
                    append(event.optString("state"))
                    append(": ")
                    append(event.optString("message"))
                    val details = event.optString("details")
                    if (details.isNotBlank()) {
                        append(" | ").append(details)
                    }
                    append("\n")
                }
                append("\n")
            }
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
        private const val MAX_RECORDS = 120
        private const val MAX_EVENTS_PER_RECORD = 40
        private const val MAX_DETAILS_CHARS = 5000
        private const val MAX_RESULT_CHARS = 2500
        private const val MAX_TECHNICAL_CHARS = 7000
    }
}
