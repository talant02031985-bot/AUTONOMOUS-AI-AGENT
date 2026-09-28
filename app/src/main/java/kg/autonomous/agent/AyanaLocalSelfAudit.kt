package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA R10.1 Local Self-Audit Engine v1.0.
 *
 * Pure deterministic renderer over local/runtime truth that has already been
 * collected by AYANA. This class does not call Agent Core, Worker, web APIs or
 * Android executors and therefore cannot turn a network/model completion into
 * audit truth.
 *
 * Input contract:
 * - registrySnapshot: current Capability Registry snapshot;
 * - diagnostics: normalized local Self-Diagnostics result;
 * - latency: stored Agent Core latency telemetry (read-only evidence only);
 * - screenTruth: current R10.0 Unified Screen Intelligence truth;
 * - releaseMetadata: compiled release/version/lineage metadata;
 * - enrichedCapabilities: one row for every registry capability, augmented by
 *   the caller with effective_device_confirmed/evidence_code/evidence_history.
 * - knownLimits: local known-limit rows.
 *
 * Output contract:
 * - deterministic text report suitable for publishing as TXT;
 * - complete capability inventory;
 * - explicit implemented/available/confirmed state;
 * - explicit evidence code;
 * - runtime/diagnostic/release sections;
 * - contradictions are surfaced and never silently normalized away;
 * - agent_core_turns is always 0.
 */
class AyanaLocalSelfAudit {

    fun build(
        registrySnapshot: JSONObject,
        diagnostics: JSONObject,
        latency: JSONObject,
        screenTruth: JSONObject,
        releaseMetadata: JSONObject,
        enrichedCapabilities: JSONArray,
        knownLimits: JSONArray
    ): JSONObject {
        val expectedCount =
            registrySnapshot
                .optJSONArray("capabilities")
                ?.length()
                ?: 0

        if (
            !registrySnapshot.optBoolean("success", false) ||
            expectedCount <= 0
        ) {
            return failure("registry_snapshot_incomplete")
        }

        if (enrichedCapabilities.length() != expectedCount) {
            return failure("capability_inventory_incomplete")
                .put("registry_count", expectedCount)
                .put("audit_count", enrichedCapabilities.length())
        }

        var verifiedAvailable = 0
        var verifiedUnavailable = 0
        var availableUnconfirmed = 0
        var unavailableUnconfirmed = 0
        var notImplemented = 0
        var contradictions = 0
        var evidenceHistorical = 0

        val capabilityRows =
            mutableListOf<String>()

        for (index in 0 until enrichedCapabilities.length()) {
            val item =
                enrichedCapabilities.optJSONObject(index)
                    ?: return failure("capability_row_invalid")
                        .put("row", index)

            val id =
                item.optString("id")
                    .trim()
                    .ifBlank { "unnamed_$index" }

            val implemented =
                item.optBoolean("implemented", false)

            val available =
                item.optBoolean("available_now", false)

            val confirmed =
                item.optBoolean(
                    "effective_device_confirmed",
                    item.optBoolean("device_confirmed", false)
                )

            val historical =
                item.optBoolean("evidence_historical", false)

            if (historical) {
                evidenceHistorical++
            }

            val status =
                when {
                    !implemented && (available || confirmed) -> {
                        contradictions++
                        "CONTRADICTION"
                    }

                    !implemented -> {
                        notImplemented++
                        "NOT_IMPLEMENTED"
                    }

                    available && confirmed -> {
                        verifiedAvailable++
                        "VERIFIED_AVAILABLE"
                    }

                    !available && confirmed -> {
                        verifiedUnavailable++
                        "VERIFIED_UNAVAILABLE_NOW"
                    }

                    available -> {
                        availableUnconfirmed++
                        "AVAILABLE_UNCONFIRMED"
                    }

                    else -> {
                        unavailableUnconfirmed++
                        "UNAVAILABLE_UNCONFIRMED"
                    }
                }

            val evidence =
                item.optString("evidence_code")
                    .ifBlank { "none" }

            val note =
                item.optString("note")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_NOTE_CHARS)

            capabilityRows +=
                buildString {
                    append(String.format(Locale.ROOT, "%02d", index + 1))
                    append(". ")
                    append(id)
                    append(" | ")
                    append(status)
                    append(" | evidence=")
                    append(evidence)
                    append(" | implemented=")
                    append(implemented)
                    append(" | available_now=")
                    append(available)
                    append(" | device_confirmed=")
                    append(confirmed)
                    if (historical) {
                        append(" | historical_fusion=true")
                    }
                    if (note.isNotBlank()) {
                        append(" | note=")
                        append(note)
                    }
                }
        }

        val runtime =
            registrySnapshot.optJSONObject("runtime")
                ?: JSONObject()

        val nonPassChecks =
            diagnostics.optJSONArray("non_pass_checks")
                ?: JSONArray()

        val limitationRows =
            mutableListOf<String>()

        for (index in 0 until knownLimits.length()) {
            val item = knownLimits.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { "limit_${index + 1}" }
            val label =
                item.optString("label")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_LIMIT_CHARS)
            limitationRows += "${index + 1}. $id — $label"
        }

        if (limitationRows.isEmpty()) {
            limitationRows += "1. Явных known-limit записей локальный источник не вернул."
        }

        val report =
            buildString {
                append("AYANA AI — R10.1 LOCAL SELF-AUDIT\n")
                append("engine=AyanaLocalSelfAudit v$VERSION\n")
                append("local_only=true; agent_core_turns=0\n")
                append('\n')

                append("========================================\n")
                append("RELEASE / LINEAGE\n")
                append("========================================\n")
                append("app_version=")
                append(releaseMetadata.optString("app_version", "unknown"))
                append('\n')
                append("voice_service=")
                append(releaseMetadata.optString("voice_service_release", "unknown"))
                append('\n')
                append("search_engine=")
                append(releaseMetadata.optString("personal_search_engine_release", "unknown"))
                append('\n')
                append("capability_registry=")
                append(releaseMetadata.optString("capability_registry_release", "unknown"))
                append('\n')
                append("capability_registry_build=")
                append(registrySnapshot.optString("build", "unknown"))
                append('\n')
                append("acceptance_engine=")
                append(releaseMetadata.optString("acceptance_engine_version", "unknown"))
                append('\n')
                append("worker=")
                append(releaseMetadata.optString("worker_release", "unknown"))
                append('\n')
                append("accepted_checkpoint=")
                append(releaseMetadata.optString("accepted_checkpoint", "unknown"))
                append('\n')
                append("current_release=")
                append(releaseMetadata.optString("current_release", "unknown"))
                append('\n')
                append("release_lineage=")
                append(releaseMetadata.optString("release_lineage", "unknown"))
                append('\n')
                append('\n')

                append("========================================\n")
                append("CAPABILITY SUMMARY\n")
                append("========================================\n")
                append("registered=")
                append(expectedCount)
                append('\n')
                append("verified_available=")
                append(verifiedAvailable)
                append('\n')
                append("verified_unavailable_now=")
                append(verifiedUnavailable)
                append('\n')
                append("available_unconfirmed=")
                append(availableUnconfirmed)
                append('\n')
                append("unavailable_unconfirmed=")
                append(unavailableUnconfirmed)
                append('\n')
                append("not_implemented=")
                append(notImplemented)
                append('\n')
                append("contradictions=")
                append(contradictions)
                append('\n')
                append("historical_evidence_fused=")
                append(evidenceHistorical)
                append('\n')
                append('\n')

                append("========================================\n")
                append("RUNTIME EVIDENCE\n")
                append("========================================\n")
                append("voice_service_running=")
                append(runtime.optBoolean("voice_service_running", false))
                append('\n')
                append("accessibility_connected=")
                append(runtime.optBoolean("accessibility_connected", false))
                append('\n')
                append("notification_listener_connected=")
                append(runtime.optBoolean("notification_listener_connected", false))
                append('\n')
                append("launchable_app_count=")
                append(runtime.optInt("launchable_app_count", -1))
                append('\n')
                append("memory_count=")
                append(runtime.optInt("memory_count", -1))
                append('\n')
                append("reminder_count=")
                append(runtime.optInt("reminder_count", -1))
                append('\n')
                append("screen_intelligence_version=")
                append(screenTruth.optString("screen_intelligence_version", "unknown"))
                append('\n')
                append("unified_screen_truth_version=")
                append(screenTruth.optString("unified_screen_truth_version", "unknown"))
                append('\n')
                append("effective_foreground_package=")
                append(screenTruth.optString("effective_foreground_package", "unknown"))
                append('\n')
                append("foreground_truth_verified=")
                append(screenTruth.optBoolean("foreground_truth_verified", false))
                append('\n')
                append("foreground_truth_conflict=")
                append(screenTruth.optBoolean("foreground_truth_conflict", false))
                append('\n')
                append("execution_evidence_usable=")
                append(screenTruth.optBoolean("execution_evidence_usable", false))
                append('\n')
                append("agent_core_latency_class=")
                append(latency.optString("classification", "NO_DATA"))
                append('\n')
                append("agent_core_latency_total_ms=")
                append(latency.optLong("total_ms", -1L))
                append('\n')
                append("agent_core_latency_is_read_only_evidence=true\n")
                append('\n')

                append("========================================\n")
                append("LOCAL SELF-DIAGNOSTICS\n")
                append("========================================\n")
                append("overall_status=")
                append(diagnostics.optString("overall_status", "unknown"))
                append('\n')
                append("passed=")
                append(diagnostics.optInt("passed", 0))
                append('\n')
                append("warnings=")
                append(diagnostics.optInt("warnings", 0))
                append('\n')
                append("unknown=")
                append(diagnostics.optInt("unknown", 0))
                append('\n')
                append("failed=")
                append(diagnostics.optInt("failed", 0))
                append('\n')
                append("diagnostic_closure_version=")
                append(diagnostics.optString("diagnostic_closure_version", "unknown"))
                append('\n')
                append("non_pass_checks=")
                append(nonPassChecks.length())
                append('\n')
                for (index in 0 until nonPassChecks.length()) {
                    val item = nonPassChecks.optJSONObject(index) ?: continue
                    append(index + 1)
                    append(". ")
                    append(item.optString("id", "unknown"))
                    append(" | ")
                    append(item.optString("status", "UNKNOWN"))
                    append(" | ")
                    append(
                        item.optString("details")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                            .take(MAX_DIAGNOSTIC_DETAIL_CHARS)
                    )
                    append('\n')
                }
                append('\n')

                append("========================================\n")
                append("REGISTERED CAPABILITIES\n")
                append("========================================\n")
                capabilityRows.forEach {
                    append(it)
                    append('\n')
                }
                append('\n')

                append("========================================\n")
                append("KNOWN LIMITS / RESTRICTIONS\n")
                append("========================================\n")
                limitationRows.forEach {
                    append(it)
                    append('\n')
                }
                append('\n')

                append("========================================\n")
                append("AUDIT INTEGRITY\n")
                append("========================================\n")
                append("source_of_capabilities=Capability Registry\n")
                append("source_of_runtime=local persisted/live runtime evidence\n")
                append("source_of_screen=R10.0 Unified Screen Intelligence\n")
                append("source_of_diagnostics=local Self-Diagnostics + DiagnosticClosure\n")
                append("agent_core_used_for_audit=false\n")
                append("worker_used_for_audit=false\n")
                append("network_turns=0\n")
                append("model_freeform_completion_required=false\n")
                append("contradictions_are_reported_findings=true\n")
                append("report_completeness_requires_all_registry_rows=true\n")
            }

        val success =
            capabilityRows.size == expectedCount

        val integrityStatus =
            if (contradictions == 0) {
                "CONSISTENT"
            } else {
                "CONTRADICTIONS_PRESENT"
            }

        val summary =
            "R10.1 локальный самоаудит: зарегистрировано $expectedCount; " +
                "verified_available=$verifiedAvailable; verified_unavailable=$verifiedUnavailable; " +
                "not_implemented=$notImplemented; contradictions=$contradictions; " +
                "Agent Core turns=0."

        return JSONObject()
            .put("success", success)
            .put("verified", success)
            .put("engine", "AyanaLocalSelfAudit")
            .put("engine_version", VERSION)
            .put("local_only", true)
            .put("agent_core_turns", 0)
            .put("worker_turns", 0)
            .put("registered_capabilities", expectedCount)
            .put("reported_capabilities", capabilityRows.size)
            .put("verified_available", verifiedAvailable)
            .put("verified_unavailable_now", verifiedUnavailable)
            .put("available_unconfirmed", availableUnconfirmed)
            .put("unavailable_unconfirmed", unavailableUnconfirmed)
            .put("not_implemented", notImplemented)
            .put("contradictions", contradictions)
            .put("audit_integrity_status", integrityStatus)
            .put("historical_evidence_fused", evidenceHistorical)
            .put("diagnostics_overall", diagnostics.optString("overall_status", "unknown"))
            .put("known_limits_count", knownLimits.length())
            .put("report_text", report)
            .put("summary", summary)
    }

    fun selfTest(): Boolean {
        val registry =
            JSONObject()
                .put("success", true)
                .put("build", "test")
                .put("runtime", JSONObject().put("voice_service_running", true))
                .put(
                    "capabilities",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("id", "a")
                                .put("implemented", true)
                                .put("available_now", true)
                                .put("device_confirmed", true)
                        )
                        .put(
                            JSONObject()
                                .put("id", "b")
                                .put("implemented", false)
                                .put("available_now", false)
                                .put("device_confirmed", false)
                        )
                )

        val enriched =
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "a")
                        .put("implemented", true)
                        .put("available_now", true)
                        .put("device_confirmed", true)
                        .put("effective_device_confirmed", true)
                        .put("evidence_code", "reg")
                        .put("note", "verified")
                )
                .put(
                    JSONObject()
                        .put("id", "b")
                        .put("implemented", false)
                        .put("available_now", false)
                        .put("device_confirmed", false)
                        .put("effective_device_confirmed", false)
                        .put("evidence_code", "neg")
                        .put("note", "not implemented")
                )

        val result =
            build(
                registrySnapshot = registry,
                diagnostics =
                    JSONObject()
                        .put("overall_status", "PASS")
                        .put("passed", 1)
                        .put("warnings", 0)
                        .put("unknown", 0)
                        .put("failed", 0),
                latency = JSONObject().put("classification", "NO_DATA"),
                screenTruth =
                    JSONObject()
                        .put("screen_intelligence_version", "5.0")
                        .put("unified_screen_truth_version", "1.0")
                        .put("foreground_truth_verified", true)
                        .put("execution_evidence_usable", true),
                releaseMetadata =
                    JSONObject()
                        .put("app_version", "test")
                        .put("voice_service_release", "test")
                        .put("release_lineage", "test"),
                enrichedCapabilities = enriched,
                knownLimits = JSONArray()
            )

        return result.optBoolean("success", false) &&
            result.optInt("registered_capabilities", 0) == 2 &&
            result.optInt("reported_capabilities", 0) == 2 &&
            result.optInt("verified_available", 0) == 1 &&
            result.optInt("not_implemented", 0) == 1 &&
            result.optInt("contradictions", -1) == 0 &&
            result.optInt("agent_core_turns", -1) == 0 &&
            result.optString("report_text").contains("agent_core_used_for_audit=false")
    }

    private fun failure(reason: String): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("engine", "AyanaLocalSelfAudit")
            .put("engine_version", VERSION)
            .put("local_only", true)
            .put("agent_core_turns", 0)
            .put("reason", reason)

    companion object {
        const val VERSION = "1.0"

        private const val MAX_NOTE_CHARS = 420
        private const val MAX_LIMIT_CHARS = 700
        private const val MAX_DIAGNOSTIC_DETAIL_CHARS = 700
    }
}
