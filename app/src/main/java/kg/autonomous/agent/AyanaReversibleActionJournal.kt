package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * AYANA R9.9 Reversible Action Journal v1.0.
 *
 * Persists only bounded technical state that is required to verify rollback of
 * explicitly supported reversible device actions. It never stores arbitrary screen
 * text, credentials, clipboard contents or hidden app data.
 *
 * Contract:
 * - only allow-listed reversible kinds may be recorded;
 * - each record contains verified BEFORE/AFTER technical state;
 * - newest UNDOABLE verified record owns a generic undo request;
 * - an undo attempt is terminally recorded as UNDONE or FAILED;
 * - failed undo stays visible for diagnostics but is not silently retried;
 * - no record grants authority to execute an action by itself;
 * - no claim of universal undo is made.
 */
class AyanaReversibleActionJournal(
    context: Context
) {

    enum class Kind(
        val wireName: String
    ) {
        MEDIA_VOLUME("media_volume"),
        SCREEN_BRIGHTNESS("screen_brightness");

        companion object {
            fun fromWireName(value: String): Kind? =
                values().firstOrNull {
                    it.wireName == value.trim().lowercase()
                }
        }
    }

    data class Entry(
        val id: String,
        val kind: Kind,
        val createdAtMs: Long,
        val source: String,
        val before: JSONObject,
        val after: JSONObject,
        val state: String,
        val undoAttempts: Int,
        val undoVerifiedAtMs: Long,
        val lastReason: String
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("id", id)
                .put("kind", kind.wireName)
                .put("created_at_ms", createdAtMs)
                .put("source", source)
                .put("before", JSONObject(before.toString()))
                .put("after", JSONObject(after.toString()))
                .put("state", state)
                .put("undo_attempts", undoAttempts)
                .put("undo_verified_at_ms", undoVerifiedAtMs)
                .put("last_reason", lastReason)
    }

    private val appContext =
        context.applicationContext

    private val journalFile =
        File(
            appContext.filesDir,
            FILE_NAME
        )

    private val lock =
        Any()

    fun recordVolume(
        beforeLevel: Int,
        afterLevel: Int,
        deviceMin: Int,
        deviceMax: Int,
        source: String
    ): Entry? {
        if (
            deviceMax < deviceMin ||
            beforeLevel !in deviceMin..deviceMax ||
            afterLevel !in deviceMin..deviceMax ||
            beforeLevel == afterLevel
        ) {
            return null
        }

        return appendVerified(
            kind = Kind.MEDIA_VOLUME,
            source = source,
            before =
                JSONObject()
                    .put("level", beforeLevel)
                    .put("device_min", deviceMin)
                    .put("device_max", deviceMax),
            after =
                JSONObject()
                    .put("level", afterLevel)
                    .put("device_min", deviceMin)
                    .put("device_max", deviceMax)
        )
    }

    fun recordBrightness(
        beforeMode: Int,
        beforeRaw: Int,
        afterMode: Int,
        afterRaw: Int,
        source: String
    ): Entry? {
        if (
            beforeMode < 0 ||
            afterMode < 0 ||
            beforeRaw !in 0..255 ||
            afterRaw !in 0..255 ||
            (beforeMode == afterMode && beforeRaw == afterRaw)
        ) {
            return null
        }

        return appendVerified(
            kind = Kind.SCREEN_BRIGHTNESS,
            source = source,
            before =
                JSONObject()
                    .put("mode", beforeMode)
                    .put("raw", beforeRaw),
            after =
                JSONObject()
                    .put("mode", afterMode)
                    .put("raw", afterRaw)
        )
    }

    fun validateVolumeUndoPrecondition(
        entry: Entry,
        currentLevel: Int,
        deviceMin: Int,
        deviceMax: Int
    ): JSONObject {
        if (
            entry.kind != Kind.MEDIA_VOLUME ||
            !isValidEntry(entry)
        ) {
            return preconditionResult(
                allowed = false,
                reason = "invalid_journal_entry"
            )
        }

        val expectedCurrent =
            entry.after.optInt("level", -1)

        val restoreTarget =
            entry.before.optInt("level", -1)

        val recordedMin =
            entry.before.optInt("device_min", -1)

        val recordedMax =
            entry.before.optInt("device_max", -1)

        val allowed =
            currentLevel == expectedCurrent &&
                deviceMin == recordedMin &&
                deviceMax == recordedMax &&
                restoreTarget in deviceMin..deviceMax

        return preconditionResult(
            allowed = allowed,
            reason =
                if (allowed) {
                    "current_state_matches_recorded_after"
                } else {
                    "current_state_diverged"
                }
        )
            .put("expected_current", expectedCurrent)
            .put("actual_current", currentLevel)
            .put("restore_target", restoreTarget)
            .put("recorded_min", recordedMin)
            .put("recorded_max", recordedMax)
            .put("device_min", deviceMin)
            .put("device_max", deviceMax)
    }

    fun validateBrightnessUndoPrecondition(
        entry: Entry,
        currentMode: Int,
        currentRaw: Int,
        tolerance: Int = 2
    ): JSONObject {
        if (
            entry.kind != Kind.SCREEN_BRIGHTNESS ||
            !isValidEntry(entry)
        ) {
            return preconditionResult(
                allowed = false,
                reason = "invalid_journal_entry"
            )
        }

        val expectedMode =
            entry.after.optInt("mode", -1)

        val expectedRaw =
            entry.after.optInt("raw", -1)

        val restoreMode =
            entry.before.optInt("mode", -1)

        val restoreRaw =
            entry.before.optInt("raw", -1)

        val boundedTolerance =
            tolerance.coerceIn(0, 8)

        val rawMatches =
            currentRaw >= 0 &&
                kotlin.math.abs(currentRaw - expectedRaw) <= boundedTolerance

        val allowed =
            currentMode == expectedMode &&
                rawMatches &&
                restoreMode >= 0 &&
                restoreRaw in 0..255

        return preconditionResult(
            allowed = allowed,
            reason =
                if (allowed) {
                    "current_state_matches_recorded_after"
                } else {
                    "current_state_diverged"
                }
        )
            .put("expected_mode", expectedMode)
            .put("actual_mode", currentMode)
            .put("expected_raw", expectedRaw)
            .put("actual_raw", currentRaw)
            .put("restore_mode", restoreMode)
            .put("restore_raw", restoreRaw)
            .put("tolerance", boundedTolerance)
    }

    fun latestUndoable(): Entry? =
        synchronized(lock) {
            readEntries()
                .asSequence()
                .filter {
                    it.state == STATE_UNDOABLE &&
                        isValidEntry(it)
                }
                .maxByOrNull {
                    it.createdAtMs
                }
        }

    fun markUndoResult(
        id: String,
        verified: Boolean,
        reason: String
    ): Entry? =
        synchronized(lock) {
            val entries =
                readEntries()
                    .toMutableList()

            val index =
                entries.indexOfFirst {
                    it.id == id
                }

            if (index < 0) {
                return@synchronized null
            }

            val current =
                entries[index]

            val updated =
                current.copy(
                    state =
                        if (verified) {
                            STATE_UNDONE
                        } else {
                            STATE_FAILED
                        },
                    undoAttempts =
                        current.undoAttempts + 1,
                    undoVerifiedAtMs =
                        if (verified) {
                            System.currentTimeMillis()
                        } else {
                            0L
                        },
                    lastReason =
                        reason
                            .trim()
                            .take(MAX_REASON_CHARS)
                )

            entries[index] = updated
            writeEntries(entries)
            updated
        }

    fun remove(
        id: String
    ): Boolean =
        synchronized(lock) {
            val entries =
                readEntries()
                    .toMutableList()

            val before =
                entries.size

            entries.removeAll {
                it.id == id
            }

            if (entries.size == before) {
                return@synchronized false
            }

            writeEntries(entries)
            true
        }

    fun summary(
        limit: Int = 8
    ): JSONObject =
        synchronized(lock) {
            val entries =
                readEntries()
                    .sortedByDescending {
                        it.createdAtMs
                    }

            val visible =
                JSONArray()

            entries
                .take(
                    limit.coerceIn(1, 20)
                )
                .forEach { entry ->
                    visible.put(
                        JSONObject()
                            .put("id", entry.id)
                            .put("kind", entry.kind.wireName)
                            .put("created_at_ms", entry.createdAtMs)
                            .put("source", entry.source)
                            .put("state", entry.state)
                            .put("undo_attempts", entry.undoAttempts)
                            .put("undo_verified_at_ms", entry.undoVerifiedAtMs)
                            .put("last_reason", entry.lastReason)
                    )
                }

            JSONObject()
                .put("version", VERSION)
                .put("supported_kinds", JSONArray(Kind.values().map { it.wireName }))
                .put("total", entries.size)
                .put(
                    "undoable",
                    entries.count {
                        it.state == STATE_UNDOABLE && isValidEntry(it)
                    }
                )
                .put("entries", visible)
                .put("universal_undo_claimed", false)
        }

    fun selfTest(): Boolean {
        val volume =
            Entry(
                id = "test-volume",
                kind = Kind.MEDIA_VOLUME,
                createdAtMs = 1L,
                source = "self_test",
                before =
                    JSONObject()
                        .put("level", 4)
                        .put("device_min", 0)
                        .put("device_max", 15),
                after =
                    JSONObject()
                        .put("level", 5)
                        .put("device_min", 0)
                        .put("device_max", 15),
                state = STATE_UNDOABLE,
                undoAttempts = 0,
                undoVerifiedAtMs = 0L,
                lastReason = ""
            )

        val brightness =
            Entry(
                id = "test-brightness",
                kind = Kind.SCREEN_BRIGHTNESS,
                createdAtMs = 2L,
                source = "self_test",
                before = JSONObject().put("mode", 1).put("raw", 90),
                after = JSONObject().put("mode", 0).put("raw", 120),
                state = STATE_UNDOABLE,
                undoAttempts = 0,
                undoVerifiedAtMs = 0L,
                lastReason = ""
            )

        val invalidVolume =
            volume.copy(
                before =
                    JSONObject()
                        .put("level", 99)
                        .put("device_min", 0)
                        .put("device_max", 15)
            )

        val jsonRoundTrip =
            parseEntry(volume.toJson())

        val volumeAllowed =
            validateVolumeUndoPrecondition(
                entry = volume,
                currentLevel = 5,
                deviceMin = 0,
                deviceMax = 15
            )

        val volumeDiverged =
            validateVolumeUndoPrecondition(
                entry = volume,
                currentLevel = 6,
                deviceMin = 0,
                deviceMax = 15
            )

        val brightnessAllowed =
            validateBrightnessUndoPrecondition(
                entry = brightness,
                currentMode = 0,
                currentRaw = 121
            )

        val brightnessDiverged =
            validateBrightnessUndoPrecondition(
                entry = brightness,
                currentMode = 0,
                currentRaw = 140
            )

        return isValidEntry(volume) &&
            isValidEntry(brightness) &&
            !isValidEntry(invalidVolume) &&
            jsonRoundTrip?.kind == Kind.MEDIA_VOLUME &&
            jsonRoundTrip.before.optInt("level", -1) == 4 &&
            jsonRoundTrip.after.optInt("level", -1) == 5 &&
            volumeAllowed.optBoolean("allowed", false) &&
            !volumeDiverged.optBoolean("allowed", true) &&
            brightnessAllowed.optBoolean("allowed", false) &&
            !brightnessDiverged.optBoolean("allowed", true) &&
            Kind.fromWireName("screen_brightness") == Kind.SCREEN_BRIGHTNESS
    }

    private fun preconditionResult(
        allowed: Boolean,
        reason: String
    ): JSONObject =
        JSONObject()
            .put("allowed", allowed)
            .put("reason", reason)
            .put("version", VERSION)

    private fun appendVerified(
        kind: Kind,
        source: String,
        before: JSONObject,
        after: JSONObject
    ): Entry? =
        synchronized(lock) {
            val entry =
                Entry(
                    id = UUID.randomUUID().toString(),
                    kind = kind,
                    createdAtMs = System.currentTimeMillis(),
                    source =
                        source
                            .trim()
                            .ifBlank {
                                "verified_local_action"
                            }
                            .take(MAX_SOURCE_CHARS),
                    before = JSONObject(before.toString()),
                    after = JSONObject(after.toString()),
                    state = STATE_UNDOABLE,
                    undoAttempts = 0,
                    undoVerifiedAtMs = 0L,
                    lastReason = ""
                )

            if (!isValidEntry(entry)) {
                return@synchronized null
            }

            val entries =
                readEntries()
                    .toMutableList()

            entries.add(entry)

            writeEntries(
                entries
                    .sortedByDescending {
                        it.createdAtMs
                    }
                    .take(MAX_ENTRIES)
            )

            entry
        }

    private fun readEntries(): List<Entry> {
        if (!journalFile.exists()) {
            return emptyList()
        }

        val root =
            try {
                JSONObject(
                    journalFile.readText(
                        Charsets.UTF_8
                    )
                )
            } catch (_: Exception) {
                return emptyList()
            }

        val array =
            root.optJSONArray("entries")
                ?: return emptyList()

        val result =
            mutableListOf<Entry>()

        for (index in 0 until array.length()) {
            val entry =
                parseEntry(
                    array.optJSONObject(index)
                        ?: continue
                )
                    ?: continue

            if (isValidEntry(entry)) {
                result += entry
            }
        }

        return result
    }

    private fun writeEntries(
        entries: List<Entry>
    ) {
        val bounded =
            entries
                .sortedByDescending {
                    it.createdAtMs
                }
                .take(MAX_ENTRIES)

        val root =
            JSONObject()
                .put("version", VERSION)
                .put(
                    "entries",
                    JSONArray().apply {
                        bounded.forEach {
                            put(it.toJson())
                        }
                    }
                )

        val parent =
            journalFile.parentFile

        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        val temp =
            File(
                parent,
                "${journalFile.name}.tmp"
            )

        temp.writeText(
            root.toString(),
            Charsets.UTF_8
        )

        if (!temp.renameTo(journalFile)) {
            journalFile.writeText(
                root.toString(),
                Charsets.UTF_8
            )
            temp.delete()
        }
    }

    private fun parseEntry(
        json: JSONObject
    ): Entry? {
        val id =
            json.optString("id")
                .trim()

        val kind =
            Kind.fromWireName(
                json.optString("kind")
            )
                ?: return null

        val before =
            json.optJSONObject("before")
                ?: return null

        val after =
            json.optJSONObject("after")
                ?: return null

        if (id.isBlank()) {
            return null
        }

        return Entry(
            id = id,
            kind = kind,
            createdAtMs = json.optLong("created_at_ms", 0L),
            source = json.optString("source").take(MAX_SOURCE_CHARS),
            before = JSONObject(before.toString()),
            after = JSONObject(after.toString()),
            state = json.optString("state", STATE_UNDOABLE),
            undoAttempts = json.optInt("undo_attempts", 0).coerceAtLeast(0),
            undoVerifiedAtMs = json.optLong("undo_verified_at_ms", 0L),
            lastReason = json.optString("last_reason").take(MAX_REASON_CHARS)
        )
    }

    private fun isValidEntry(
        entry: Entry
    ): Boolean {
        if (
            entry.id.isBlank() ||
            entry.createdAtMs < 0L ||
            entry.state !in VALID_STATES
        ) {
            return false
        }

        return when (entry.kind) {
            Kind.MEDIA_VOLUME -> {
                val before = entry.before.optInt("level", Int.MIN_VALUE)
                val after = entry.after.optInt("level", Int.MIN_VALUE)
                val min = entry.before.optInt("device_min", Int.MIN_VALUE)
                val max = entry.before.optInt("device_max", Int.MIN_VALUE)
                val afterMin = entry.after.optInt("device_min", Int.MIN_VALUE)
                val afterMax = entry.after.optInt("device_max", Int.MIN_VALUE)

                max >= min &&
                    min == afterMin &&
                    max == afterMax &&
                    before in min..max &&
                    after in min..max &&
                    before != after
            }

            Kind.SCREEN_BRIGHTNESS -> {
                val beforeMode = entry.before.optInt("mode", -1)
                val beforeRaw = entry.before.optInt("raw", -1)
                val afterMode = entry.after.optInt("mode", -1)
                val afterRaw = entry.after.optInt("raw", -1)

                beforeMode >= 0 &&
                    afterMode >= 0 &&
                    beforeRaw in 0..255 &&
                    afterRaw in 0..255 &&
                    (beforeMode != afterMode || beforeRaw != afterRaw)
            }
        }
    }

    companion object {
        const val VERSION = "1.0"

        const val STATE_UNDOABLE = "UNDOABLE"
        const val STATE_UNDONE = "UNDONE"
        const val STATE_FAILED = "FAILED"

        const val MAX_ENTRIES = 50

        private const val FILE_NAME =
            "ayana_reversible_action_journal_v1.json"

        private const val MAX_SOURCE_CHARS = 120
        private const val MAX_REASON_CHARS = 400

        private val VALID_STATES =
            setOf(
                STATE_UNDOABLE,
                STATE_UNDONE,
                STATE_FAILED
            )
    }
}
