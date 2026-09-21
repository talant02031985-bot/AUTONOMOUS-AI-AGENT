package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * AYANA R9.0 Diagnostic Closure v1.0.
 *
 * Normalizes diagnostic *presentation* without inventing PASS evidence.
 * Old command errors stop looking like a current degradation once they are outside
 * the active incident window and newer successful commands prove recovery.
 * Stale Agent Core / TTS telemetry remains UNKNOWN, never silently PASS.
 */
class AyanaDiagnosticClosure {

    fun normalizeSelfDiagnostics(
        raw: JSONObject,
        recentHistory: List<JSONObject>,
        nowMs: Long = System.currentTimeMillis()
    ): JSONObject {
        val result = JSONObject(raw.toString())
        val checks = result.optJSONArray("checks") ?: JSONArray()

        val lastError =
            recentHistory
                .filter {
                    it.optString("status").equals("error", ignoreCase = true)
                }
                .maxByOrNull { recordTime(it) }

        val lastErrorAt = lastError?.let { recordTime(it) } ?: 0L
        val newerSuccesses =
            if (lastErrorAt > 0L) {
                recentHistory.count {
                    it.optString("status").equals("success", ignoreCase = true) &&
                        recordTime(it) > lastErrorAt
                }
            } else {
                0
            }

        val errorAgeMs =
            if (lastErrorAt > 0L) {
                (nowMs - lastErrorAt).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }

        for (index in 0 until checks.length()) {
            val item = checks.optJSONObject(index) ?: continue
            if (item.optString("id") != "recent_command_health") continue

            val currentStatus = item.optString("status")
            if (currentStatus != STATUS_WARNING) continue

            val recovered =
                lastError == null ||
                    errorAgeMs > ACTIVE_INCIDENT_WINDOW_MS &&
                        newerSuccesses >= MIN_SUCCESS_AFTER_OLD_ERROR

            if (recovered) {
                item
                    .put("status", STATUS_PASS)
                    .put("ok", true)
                    .put("verified", true)
                    .put(
                        "details",
                        if (lastError == null) {
                            "Активных ERROR в доступной истории нет."
                        } else {
                            "Последняя ERROR относится к прошлому состоянию; после неё подтверждено успешных команд: $newerSuccesses. " +
                                "Возраст ошибки: ${errorAgeMs / 1000L} с."
                        }
                    )
                    .put("r9_incident_state", "recovered_historical")
            } else {
                item.put("r9_incident_state", "active_recent")
            }
        }

        recalculateCounts(result, checks)

        result
            .put("diagnostic_closure_version", VERSION)
            .put("active_incident_window_ms", ACTIVE_INCIDENT_WINDOW_MS)
            .put("last_error_age_ms", if (lastErrorAt > 0L) errorAgeMs else -1L)
            .put("successes_after_last_error", newerSuccesses)

        return result
    }

    fun reportFromNormalized(
        normalized: JSONObject
    ): String {
        val passed = normalized.optInt("passed", 0)
        val warnings = normalized.optInt("warnings", 0)
        val unknown = normalized.optInt("unknown", 0)
        val failed = normalized.optInt("failed", 0)
        val checks = normalized.optJSONArray("checks") ?: JSONArray()

        val issues = mutableListOf<String>()
        for (index in 0 until checks.length()) {
            val item = checks.optJSONObject(index) ?: continue
            val status = item.optString("status")
            if (status == STATUS_PASS) continue

            val prefix =
                when (status) {
                    STATUS_FAIL -> "Ошибка"
                    STATUS_WARNING -> "Внимание"
                    STATUS_UNKNOWN -> "Нет данных"
                    else -> status
                }

            issues +=
                "$prefix: ${item.optString("name", item.optString("id"))} — ${item.optString("details").take(700)}"
        }

        return buildString {
            append(
                "Самодиагностика завершена: исправно=$passed, внимание=$warnings, " +
                    "нет данных=$unknown, ошибки=$failed."
            )
            if (issues.isNotEmpty()) {
                append(" Отклонения: ")
                issues.forEachIndexed { index, issue ->
                    if (index > 0) append("; ")
                    append(index + 1)
                    append(") ")
                    append(issue)
                }
            }
        }
    }

    fun classifyLatency(
        telemetry: JSONObject
    ): JSONObject {
        val total = telemetry.optLong("total_ms", -1L)
        val prepare = telemetry.optLong("prepare_ms", 0L).coerceAtLeast(0L)
        val upload = telemetry.optLong("upload_ms", 0L).coerceAtLeast(0L)
        val headers = telemetry.optLong("headers_wait_ms", 0L).coerceAtLeast(0L)
        val body = telemetry.optLong("body_read_ms", 0L).coerceAtLeast(0L)
        val parse = telemetry.optLong("json_parse_ms", 0L).coerceAtLeast(0L)

        val local = prepare + upload + body + parse
        val remote = headers

        val classification =
            when {
                total < 0L -> "NO_DATA"
                remote >= local * 3L && remote >= 1000L -> "MODEL_OR_SERVER_WAIT"
                local >= remote * 2L && local >= 1000L -> "ANDROID_OR_TRANSPORT_LOCAL"
                else -> "MIXED"
            }

        return JSONObject()
            .put("classification", classification)
            .put("total_ms", total)
            .put("local_android_transport_ms", local)
            .put("model_server_wait_ms", remote)
            .put(
                "remote_share_pct",
                if (total > 0L) {
                    ((remote * 100L) / total).coerceIn(0L, 100L)
                } else {
                    -1L
                }
            )
    }

    fun screenContainsMarker(
        screen: JSONObject,
        marker: String
    ): Boolean {
        if (marker.isBlank()) return false

        val direct =
            listOf(
                screen.optString("verification_text"),
                screen.optString("visible_text_joined"),
                screen.optString("text")
            )
                .any { it.contains(marker, ignoreCase = true) }

        if (direct) return true

        val arrays =
            listOf(
                screen.optJSONArray("visible_text"),
                screen.optJSONArray("all_visible_text")
            )

        arrays.forEach { array ->
            if (array == null) return@forEach
            for (index in 0 until array.length()) {
                if (
                    array.optString(index)
                        .contains(marker, ignoreCase = true)
                ) {
                    return true
                }
            }
        }

        return false
    }

    fun selfTest(): Boolean {
        val now = 1_000_000L
        val checks =
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "agent_core")
                        .put("status", STATUS_UNKNOWN)
                        .put("name", "Agent Core")
                        .put("details", "stale")
                )
                .put(
                    JSONObject()
                        .put("id", "recent_command_health")
                        .put("status", STATUS_WARNING)
                        .put("name", "Последние команды")
                        .put("details", "old error")
                )

        val raw =
            JSONObject()
                .put("checks", checks)
                .put("passed", 0)
                .put("warnings", 1)
                .put("unknown", 1)
                .put("failed", 0)

        val history =
            listOf(
                JSONObject()
                    .put("status", "success")
                    .put("finished_at", now - 1_000L),
                JSONObject()
                    .put("status", "success")
                    .put("finished_at", now - 2_000L),
                JSONObject()
                    .put("status", "success")
                    .put("finished_at", now - 3_000L),
                JSONObject()
                    .put("status", "error")
                    .put("finished_at", now - ACTIVE_INCIDENT_WINDOW_MS - 10_000L)
            )

        val normalized = normalizeSelfDiagnostics(raw, history, now)
        val normalizedChecks = normalized.optJSONArray("checks") ?: return false
        val commandHealth =
            (0 until normalizedChecks.length())
                .mapNotNull { normalizedChecks.optJSONObject(it) }
                .firstOrNull { it.optString("id") == "recent_command_health" }
                ?: return false

        val latency =
            classifyLatency(
                JSONObject()
                    .put("total_ms", 10_000L)
                    .put("prepare_ms", 20L)
                    .put("upload_ms", 30L)
                    .put("headers_wait_ms", 9_900L)
                    .put("body_read_ms", 20L)
                    .put("json_parse_ms", 10L)
            )

        return commandHealth.optString("status") == STATUS_PASS &&
            normalized.optInt("warnings") == 0 &&
            normalized.optInt("unknown") == 1 &&
            latency.optString("classification") == "MODEL_OR_SERVER_WAIT"
    }

    private fun recordTime(
        record: JSONObject
    ): Long {
        val finished = record.optLong("finished_at", 0L)
        if (finished > 0L) return finished

        val started = record.optLong("started_at", 0L)
        if (started > 0L) return started

        val idPrefix =
            record
                .optString("id")
                .substringBefore('-')
                .toLongOrNull()

        return idPrefix ?: 0L
    }

    private fun recalculateCounts(
        result: JSONObject,
        checks: JSONArray
    ) {
        var passed = 0
        var warnings = 0
        var unknown = 0
        var failed = 0

        for (index in 0 until checks.length()) {
            when (checks.optJSONObject(index)?.optString("status")) {
                STATUS_PASS -> passed++
                STATUS_WARNING -> warnings++
                STATUS_UNKNOWN -> unknown++
                STATUS_FAIL -> failed++
            }
        }

        result
            .put("passed", passed)
            .put("warnings", warnings)
            .put("unknown", unknown)
            .put("failed", failed)
            .put(
                "overall_status",
                when {
                    failed > 0 -> STATUS_FAIL
                    warnings > 0 -> STATUS_WARNING
                    unknown > 0 -> STATUS_UNKNOWN
                    else -> STATUS_PASS
                }
            )
    }

    companion object {
        const val VERSION = "1.0"

        const val STATUS_PASS = "PASS"
        const val STATUS_WARNING = "WARNING"
        const val STATUS_UNKNOWN = "UNKNOWN"
        const val STATUS_FAIL = "FAIL"

        const val ACTIVE_INCIDENT_WINDOW_MS = 15L * 60L * 1000L
        private const val MIN_SUCCESS_AFTER_OLD_ERROR = 2
    }
}
