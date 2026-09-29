package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * AYANA Long Objective Coordinator v1.0 — R10.8 GENERAL-PURPOSE LONG AUTONOMOUS OBJECTIVES.
 *
 * Pure policy/state component. It never dispatches Android actions and never grants
 * capability authority. It owns only the dependency graph and provenance-bound partial
 * result ledger for one long objective. Execution remains in AyanaVoiceService and every
 * real action must still pass the existing Cross-Lane + Adaptive Execution gates.
 *
 * Guarantees:
 * - one immutable objective id;
 * - bounded acyclic subgoal graph with explicit dependencies;
 * - a subgoal is READY only after every dependency is VERIFIED;
 * - lane/authority must exactly match the subgoal contract (no authority laundering);
 * - only VERIFIED subgoals may publish partial results;
 * - partial results carry producer provenance and survive snapshot/restore;
 * - VERIFIED subgoals cannot be begun again (no replay through this coordinator);
 * - terminal success requires every required subgoal VERIFIED and no running/failed required subgoal;
 * - restore fails closed on malformed graph/state/provenance.
 */
class AyanaLongObjectiveCoordinator private constructor(
    private val objectiveIdValue: String,
    private val objectiveValue: String,
    private val subgoals: LinkedHashMap<String, SubgoalState>,
    private val partialResults: LinkedHashMap<String, PartialResult>,
    private val eventLog: MutableList<JSONObject>,
    private val createdAtMs: Long
) {

    data class SubgoalSpec(
        val id: String,
        val title: String,
        val lane: String,
        val authority: String,
        val dependencies: List<String> = emptyList(),
        val resultKey: String = "",
        val required: Boolean = true
    )

    private data class SubgoalState(
        val spec: SubgoalSpec,
        var status: String = STATUS_PENDING,
        var attempts: Int = 0,
        var verifiedAtMs: Long = 0L,
        var lastEvidence: String = "",
        var lastFailure: String = "",
        var resultFingerprint: String = ""
    )

    private data class PartialResult(
        val key: String,
        val value: String,
        val producerSubgoalId: String,
        val producerLane: String,
        val producerAuthority: String,
        val fingerprint: String,
        val verifiedAtMs: Long
    )

    fun objectiveId(): String = objectiveIdValue

    fun objective(): String = objectiveValue

    fun subgoalCount(): Int = subgoals.size

    fun verifiedSubgoalCount(): Int =
        subgoals.values.count { it.status == STATUS_VERIFIED }

    fun requiredSubgoalCount(): Int =
        subgoals.values.count { it.spec.required }

    fun partialResultCount(): Int = partialResults.size

    fun statusOf(subgoalId: String): String =
        subgoals[normalizeId(subgoalId)]?.status ?: STATUS_UNKNOWN

    fun beginSubgoal(
        subgoalId: String,
        lane: String,
        authority: String
    ): JSONObject {
        val id = normalizeId(subgoalId)
        val state = subgoals[id]
            ?: return denied("unknown_subgoal", id)

        if (state.status == STATUS_VERIFIED) {
            return denied("verified_subgoal_replay_blocked", id)
        }

        if (state.status == STATUS_RUNNING) {
            return denied("subgoal_already_running", id)
        }

        val normalizedLane = normalizeToken(lane)
        val normalizedAuthority = normalizeAuthority(authority)

        if (
            normalizedLane != normalizeToken(state.spec.lane) ||
            normalizedAuthority != normalizeAuthority(state.spec.authority)
        ) {
            return denied("subgoal_lane_or_authority_mismatch", id)
                .put("expected_lane", state.spec.lane)
                .put("expected_authority", state.spec.authority)
                .put("requested_lane", lane)
                .put("requested_authority", authority)
        }

        val missing = JSONArray()
        for (dependency in state.spec.dependencies) {
            if (subgoals[dependency]?.status != STATUS_VERIFIED) {
                missing.put(dependency)
            }
        }

        if (missing.length() > 0) {
            return denied("dependency_not_verified", id)
                .put("missing_dependencies", missing)
        }

        state.status = STATUS_RUNNING
        state.attempts += 1
        state.lastFailure = ""

        appendEvent(
            type = "subgoal_started",
            subgoalId = id,
            details = JSONObject()
                .put("lane", state.spec.lane)
                .put("authority", state.spec.authority)
                .put("attempt", state.attempts)
        )

        return JSONObject()
            .put("allowed", true)
            .put("reason", "dependency_and_authority_verified")
            .put("subgoal_id", id)
            .put("attempt", state.attempts)
            .put("lane", state.spec.lane)
            .put("authority", state.spec.authority)
    }

    fun recordVerified(
        subgoalId: String,
        lane: String,
        authority: String,
        evidence: String,
        resultValue: String = ""
    ): JSONObject {
        val id = normalizeId(subgoalId)
        val state = subgoals[id]
            ?: return denied("unknown_subgoal", id)

        if (state.status != STATUS_RUNNING) {
            return denied("subgoal_not_running", id)
        }

        if (
            normalizeToken(lane) != normalizeToken(state.spec.lane) ||
            normalizeAuthority(authority) != normalizeAuthority(state.spec.authority)
        ) {
            return denied("verification_lane_or_authority_mismatch", id)
        }

        val now = System.currentTimeMillis()
        val cleanEvidence = evidence.trim().take(MAX_EVIDENCE_CHARS)
        val cleanValue = resultValue.trim().take(MAX_RESULT_CHARS)
        val fingerprint = fingerprint(
            listOf(
                objectiveIdValue,
                id,
                state.spec.lane,
                state.spec.authority,
                cleanEvidence,
                cleanValue
            ).joinToString("|")
        )

        state.status = STATUS_VERIFIED
        state.verifiedAtMs = now
        state.lastEvidence = cleanEvidence
        state.lastFailure = ""
        state.resultFingerprint = fingerprint

        if (state.spec.resultKey.isNotBlank()) {
            if (cleanValue.isBlank()) {
                state.status = STATUS_FAILED
                state.lastFailure = "verified_result_value_missing"
                return denied("verified_result_value_missing", id)
            }

            partialResults[state.spec.resultKey] =
                PartialResult(
                    key = state.spec.resultKey,
                    value = cleanValue,
                    producerSubgoalId = id,
                    producerLane = state.spec.lane,
                    producerAuthority = state.spec.authority,
                    fingerprint = fingerprint,
                    verifiedAtMs = now
                )
        }

        appendEvent(
            type = "subgoal_verified",
            subgoalId = id,
            details = JSONObject()
                .put("result_key", state.spec.resultKey)
                .put("result_fingerprint", fingerprint)
        )

        return JSONObject()
            .put("recorded", true)
            .put("verified", true)
            .put("subgoal_id", id)
            .put("result_key", state.spec.resultKey)
            .put("result_fingerprint", fingerprint)
    }

    fun recordFailure(
        subgoalId: String,
        failure: String
    ): JSONObject {
        val id = normalizeId(subgoalId)
        val state = subgoals[id]
            ?: return denied("unknown_subgoal", id)

        if (state.status == STATUS_VERIFIED) {
            return denied("verified_subgoal_failure_rejected", id)
        }

        state.status = STATUS_FAILED
        state.lastFailure = failure.trim().take(MAX_EVIDENCE_CHARS)

        appendEvent(
            type = "subgoal_failed",
            subgoalId = id,
            details = JSONObject().put("failure", state.lastFailure)
        )

        return JSONObject()
            .put("recorded", true)
            .put("verified", false)
            .put("subgoal_id", id)
            .put("status", STATUS_FAILED)
    }

    fun resetFailedForReplan(subgoalId: String): JSONObject {
        val id = normalizeId(subgoalId)
        val state = subgoals[id]
            ?: return denied("unknown_subgoal", id)

        if (state.status != STATUS_FAILED) {
            return denied("subgoal_not_failed", id)
        }

        state.status = STATUS_PENDING
        appendEvent(
            type = "subgoal_replanned",
            subgoalId = id,
            details = JSONObject()
        )

        return JSONObject()
            .put("allowed", true)
            .put("subgoal_id", id)
            .put("status", STATUS_PENDING)
    }

    fun partialResult(
        key: String,
        consumerSubgoalId: String? = null
    ): JSONObject {
        val normalizedKey = normalizeResultKey(key)
        val result = partialResults[normalizedKey]
            ?: return JSONObject()
                .put("available", false)
                .put("verified", false)
                .put("reason", "partial_result_not_found")
                .put("key", normalizedKey)

        if (!consumerSubgoalId.isNullOrBlank()) {
            val consumer = subgoals[normalizeId(consumerSubgoalId)]
                ?: return JSONObject()
                    .put("available", false)
                    .put("verified", false)
                    .put("reason", "unknown_consumer_subgoal")

            if (result.producerSubgoalId !in consumer.spec.dependencies) {
                return JSONObject()
                    .put("available", false)
                    .put("verified", false)
                    .put("reason", "producer_not_dependency_of_consumer")
                    .put("producer_subgoal_id", result.producerSubgoalId)
            }

            if (subgoals[result.producerSubgoalId]?.status != STATUS_VERIFIED) {
                return JSONObject()
                    .put("available", false)
                    .put("verified", false)
                    .put("reason", "producer_not_verified")
            }
        }

        return JSONObject()
            .put("available", true)
            .put("verified", true)
            .put("key", result.key)
            .put("value", result.value)
            .put("producer_subgoal_id", result.producerSubgoalId)
            .put("producer_lane", result.producerLane)
            .put("producer_authority", result.producerAuthority)
            .put("fingerprint", result.fingerprint)
            .put("verified_at_ms", result.verifiedAtMs)
    }

    fun readySubgoals(): JSONArray {
        val array = JSONArray()
        for ((id, state) in subgoals) {
            if (state.status != STATUS_PENDING) continue
            if (state.spec.dependencies.all { subgoals[it]?.status == STATUS_VERIFIED }) {
                array.put(id)
            }
        }
        return array
    }

    fun canDeclareSuccess(): Boolean {
        if (subgoals.isEmpty()) return false
        for (state in subgoals.values) {
            if (state.spec.required && state.status != STATUS_VERIFIED) {
                return false
            }
        }
        return true
    }

    fun snapshot(): JSONObject {
        val subgoalArray = JSONArray()
        for ((id, state) in subgoals) {
            subgoalArray.put(
                JSONObject()
                    .put("id", id)
                    .put("title", state.spec.title)
                    .put("lane", state.spec.lane)
                    .put("authority", state.spec.authority)
                    .put("dependencies", JSONArray(state.spec.dependencies))
                    .put("result_key", state.spec.resultKey)
                    .put("required", state.spec.required)
                    .put("status", state.status)
                    .put("attempts", state.attempts)
                    .put("verified_at_ms", state.verifiedAtMs)
                    .put("last_evidence", state.lastEvidence)
                    .put("last_failure", state.lastFailure)
                    .put("result_fingerprint", state.resultFingerprint)
            )
        }

        val resultsArray = JSONArray()
        for (result in partialResults.values) {
            resultsArray.put(
                JSONObject()
                    .put("key", result.key)
                    .put("value", result.value)
                    .put("producer_subgoal_id", result.producerSubgoalId)
                    .put("producer_lane", result.producerLane)
                    .put("producer_authority", result.producerAuthority)
                    .put("fingerprint", result.fingerprint)
                    .put("verified_at_ms", result.verifiedAtMs)
            )
        }

        val events = JSONArray()
        for (event in eventLog.takeLast(MAX_EVENT_COUNT)) {
            events.put(JSONObject(event.toString()))
        }

        return JSONObject()
            .put("version", VERSION)
            .put("snapshot_version", SNAPSHOT_VERSION)
            .put("objective_id", objectiveIdValue)
            .put("objective", objectiveValue)
            .put("created_at_ms", createdAtMs)
            .put("subgoals", subgoalArray)
            .put("partial_results", resultsArray)
            .put("events", events)
            .put("verified_subgoal_count", verifiedSubgoalCount())
            .put("required_subgoal_count", requiredSubgoalCount())
            .put("partial_result_count", partialResultCount())
            .put("terminal_ready", canDeclareSuccess())
            .put("plan_fingerprint", planFingerprint())
    }

    fun verifyRestoredAgainst(
        persistedSnapshot: JSONObject?
    ): JSONObject {
        if (persistedSnapshot == null) {
            return JSONObject()
                .put("verified", false)
                .put("reason", "persisted_snapshot_missing")
        }

        val expectedObjectiveId = persistedSnapshot.optString("objective_id").trim()
        val expectedPlanFingerprint = persistedSnapshot.optString("plan_fingerprint").trim()
        val expectedVerified = persistedSnapshot.optInt("verified_subgoal_count", -1)
        val expectedResultCount = persistedSnapshot.optInt("partial_result_count", -1)

        val objectiveOk =
            expectedObjectiveId.isNotBlank() && expectedObjectiveId == objectiveIdValue
        val planOk =
            expectedPlanFingerprint.isNotBlank() && expectedPlanFingerprint == planFingerprint()
        val verifiedCountOk = expectedVerified == verifiedSubgoalCount()
        val resultCountOk = expectedResultCount == partialResultCount()
        val provenanceOk = verifyPartialResultProvenance()

        return JSONObject()
            .put(
                "verified",
                objectiveOk && planOk && verifiedCountOk && resultCountOk && provenanceOk
            )
            .put("objective_id_preserved", objectiveOk)
            .put("plan_fingerprint_preserved", planOk)
            .put("verified_subgoal_count_preserved", verifiedCountOk)
            .put("partial_result_count_preserved", resultCountOk)
            .put("partial_result_provenance_verified", provenanceOk)
            .put("reason", if (objectiveOk && planOk && verifiedCountOk && resultCountOk && provenanceOk) "exact_long_objective_restore_verified" else "long_objective_restore_mismatch")
    }

    fun compactSummary(): String =
        "long_objective=v$VERSION; objective_id=$objectiveIdValue; subgoals=${subgoals.size}; verified=${verifiedSubgoalCount()}; partial_results=${partialResultCount()}; terminal=${canDeclareSuccess()}"

    private fun planFingerprint(): String {
        val canonical = buildString {
            append(objectiveIdValue)
            append('|')
            append(objectiveValue)
            for ((id, state) in subgoals) {
                append('|')
                append(id)
                append(':')
                append(state.spec.lane)
                append(':')
                append(state.spec.authority)
                append(':')
                append(state.spec.required)
                append(':')
                append(state.spec.resultKey)
                append(':')
                append(state.spec.dependencies.joinToString(","))
            }
        }
        return fingerprint(canonical)
    }

    private fun verifyPartialResultProvenance(): Boolean {
        for ((key, result) in partialResults) {
            if (key != result.key) return false
            val producer = subgoals[result.producerSubgoalId] ?: return false
            if (producer.status != STATUS_VERIFIED) return false
            if (producer.spec.resultKey != key) return false
            if (normalizeToken(producer.spec.lane) != normalizeToken(result.producerLane)) return false
            if (normalizeAuthority(producer.spec.authority) != normalizeAuthority(result.producerAuthority)) return false
            if (result.value.isBlank() || result.fingerprint.isBlank()) return false
            if (producer.resultFingerprint.isBlank() || producer.resultFingerprint != result.fingerprint) return false
        }
        return true
    }

    private fun appendEvent(
        type: String,
        subgoalId: String,
        details: JSONObject
    ) {
        eventLog.add(
            JSONObject()
                .put("type", type)
                .put("subgoal_id", subgoalId)
                .put("at_ms", System.currentTimeMillis())
                .put("details", JSONObject(details.toString()))
        )
        while (eventLog.size > MAX_EVENT_COUNT) {
            eventLog.removeAt(0)
        }
    }

    private fun denied(reason: String, subgoalId: String): JSONObject =
        JSONObject()
            .put("allowed", false)
            .put("reason", reason)
            .put("subgoal_id", subgoalId)

    companion object {
        const val VERSION = "1.0"
        const val SNAPSHOT_VERSION = 1

        const val STATUS_UNKNOWN = "UNKNOWN"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_VERIFIED = "VERIFIED"
        const val STATUS_FAILED = "FAILED"

        private const val MAX_SUBGOALS = 24
        private const val MAX_EVENT_COUNT = 96
        private const val MAX_TITLE_CHARS = 240
        private const val MAX_RESULT_CHARS = 2000
        private const val MAX_EVIDENCE_CHARS = 1000

        fun create(
            objectiveId: String,
            objective: String,
            specs: List<SubgoalSpec>
        ): AyanaLongObjectiveCoordinator {
            val cleanObjectiveId = objectiveId.trim()
            val cleanObjective = objective.trim().take(1000)

            require(cleanObjectiveId.isNotBlank()) { "objective_id_required" }
            require(cleanObjective.isNotBlank()) { "objective_required" }
            require(specs.isNotEmpty()) { "subgoals_required" }
            require(specs.size <= MAX_SUBGOALS) { "too_many_subgoals" }

            val map = LinkedHashMap<String, SubgoalState>()
            for (rawSpec in specs) {
                val id = normalizeId(rawSpec.id)
                require(id.isNotBlank()) { "subgoal_id_required" }
                require(id !in map) { "duplicate_subgoal_id:$id" }

                val dependencies = rawSpec.dependencies.map { normalizeId(it) }.distinct()
                require(id !in dependencies) { "self_dependency:$id" }

                val spec =
                    SubgoalSpec(
                        id = id,
                        title = rawSpec.title.trim().take(MAX_TITLE_CHARS),
                        lane = normalizeToken(rawSpec.lane),
                        authority = rawSpec.authority.trim(),
                        dependencies = dependencies,
                        resultKey = normalizeResultKey(rawSpec.resultKey),
                        required = rawSpec.required
                    )

                require(spec.title.isNotBlank()) { "subgoal_title_required:$id" }
                require(spec.lane.isNotBlank()) { "subgoal_lane_required:$id" }
                require(spec.authority.isNotBlank()) { "subgoal_authority_required:$id" }
                map[id] = SubgoalState(spec)
            }

            for ((id, state) in map) {
                for (dependency in state.spec.dependencies) {
                    require(dependency in map) { "unknown_dependency:$id->$dependency" }
                }
            }

            require(isAcyclic(map)) { "cyclic_subgoal_graph" }

            return AyanaLongObjectiveCoordinator(
                objectiveIdValue = cleanObjectiveId,
                objectiveValue = cleanObjective,
                subgoals = map,
                partialResults = LinkedHashMap(),
                eventLog = mutableListOf(),
                createdAtMs = System.currentTimeMillis()
            )
        }

        fun restore(
            snapshot: JSONObject?
        ): AyanaLongObjectiveCoordinator {
            require(snapshot != null) { "long_objective_snapshot_required" }
            require(snapshot.optString("version") == VERSION) { "unsupported_long_objective_version" }
            require(snapshot.optInt("snapshot_version", -1) == SNAPSHOT_VERSION) { "unsupported_snapshot_version" }

            val objectiveId = snapshot.optString("objective_id").trim()
            val objective = snapshot.optString("objective").trim()
            val subgoalArray = snapshot.optJSONArray("subgoals") ?: JSONArray()

            val specs = mutableListOf<SubgoalSpec>()
            for (i in 0 until subgoalArray.length()) {
                val item = subgoalArray.optJSONObject(i) ?: continue
                val deps = mutableListOf<String>()
                val depArray = item.optJSONArray("dependencies") ?: JSONArray()
                for (j in 0 until depArray.length()) {
                    deps.add(depArray.optString(j))
                }
                specs.add(
                    SubgoalSpec(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        lane = item.optString("lane"),
                        authority = item.optString("authority"),
                        dependencies = deps,
                        resultKey = item.optString("result_key"),
                        required = item.optBoolean("required", true)
                    )
                )
            }

            val restored = create(objectiveId, objective, specs)

            for (i in 0 until subgoalArray.length()) {
                val item = subgoalArray.optJSONObject(i) ?: continue
                val id = normalizeId(item.optString("id"))
                val state = restored.subgoals[id] ?: continue
                val status = item.optString("status", STATUS_PENDING)
                state.status = when (status) {
                    STATUS_PENDING, STATUS_RUNNING, STATUS_VERIFIED, STATUS_FAILED -> status
                    else -> STATUS_PENDING
                }
                // A persisted RUNNING subgoal is uncertain after restart. Fail closed.
                if (state.status == STATUS_RUNNING) {
                    state.status = STATUS_FAILED
                    state.lastFailure = "interrupted_while_running_requires_reconciliation"
                } else {
                    state.lastFailure = item.optString("last_failure").take(MAX_EVIDENCE_CHARS)
                }
                state.attempts = item.optInt("attempts", 0).coerceAtLeast(0)
                state.verifiedAtMs = item.optLong("verified_at_ms", 0L).coerceAtLeast(0L)
                state.lastEvidence = item.optString("last_evidence").take(MAX_EVIDENCE_CHARS)
                state.resultFingerprint = item.optString("result_fingerprint").take(128)
            }

            val results = snapshot.optJSONArray("partial_results") ?: JSONArray()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val key = normalizeResultKey(item.optString("key"))
                if (key.isBlank()) continue
                restored.partialResults[key] =
                    PartialResult(
                        key = key,
                        value = item.optString("value").take(MAX_RESULT_CHARS),
                        producerSubgoalId = normalizeId(item.optString("producer_subgoal_id")),
                        producerLane = normalizeToken(item.optString("producer_lane")),
                        producerAuthority = item.optString("producer_authority").trim(),
                        fingerprint = item.optString("fingerprint").take(128),
                        verifiedAtMs = item.optLong("verified_at_ms", 0L).coerceAtLeast(0L)
                    )
            }

            val events = snapshot.optJSONArray("events") ?: JSONArray()
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                restored.eventLog.add(JSONObject(event.toString()))
            }

            require(restored.verifyPartialResultProvenance()) { "partial_result_provenance_invalid" }
            require(snapshot.optString("plan_fingerprint") == restored.planFingerprint()) { "plan_fingerprint_mismatch" }

            return restored
        }

        fun selfTest(): Boolean {
            return try {
                val specs = listOf(
                    SubgoalSpec(
                        id = "source",
                        title = "Source",
                        lane = "agent_core",
                        authority = "a",
                        resultKey = "value"
                    ),
                    SubgoalSpec(
                        id = "consumer",
                        title = "Consumer",
                        lane = "multi_app",
                        authority = "b",
                        dependencies = listOf("source")
                    )
                )
                val coordinator = create("obj-test", "test", specs)

                val dependencyBlocked =
                    !coordinator.beginSubgoal("consumer", "multi_app", "b")
                        .optBoolean("allowed", true)

                val sourceStarted =
                    coordinator.beginSubgoal("source", "agent_core", "a")
                        .optBoolean("allowed", false)
                val sourceVerified =
                    coordinator.recordVerified(
                        "source",
                        "agent_core",
                        "a",
                        "evidence",
                        "Example Domain"
                    ).optBoolean("verified", false)

                val replayBlocked =
                    coordinator.beginSubgoal("source", "agent_core", "a")
                        .optString("reason") == "verified_subgoal_replay_blocked"

                val partial = coordinator.partialResult("value", "consumer")
                val consumerStarted =
                    coordinator.beginSubgoal("consumer", "multi_app", "b")
                        .optBoolean("allowed", false)
                val consumerVerified =
                    coordinator.recordVerified(
                        "consumer",
                        "multi_app",
                        "b",
                        "consumer evidence"
                    ).optBoolean("verified", false)

                val snapshot = coordinator.snapshot()
                val restored = restore(snapshot)
                val restoreVerified = restored.verifyRestoredAgainst(snapshot)
                    .optBoolean("verified", false)

                dependencyBlocked &&
                    sourceStarted &&
                    sourceVerified &&
                    replayBlocked &&
                    partial.optBoolean("verified", false) &&
                    partial.optString("value") == "Example Domain" &&
                    consumerStarted &&
                    consumerVerified &&
                    coordinator.canDeclareSuccess() &&
                    restored.canDeclareSuccess() &&
                    restoreVerified
            } catch (_: Throwable) {
                false
            }
        }

        private fun isAcyclic(map: LinkedHashMap<String, SubgoalState>): Boolean {
            val visiting = mutableSetOf<String>()
            val visited = mutableSetOf<String>()

            fun visit(id: String): Boolean {
                if (id in visited) return true
                if (!visiting.add(id)) return false
                val state = map[id] ?: return false
                for (dependency in state.spec.dependencies) {
                    if (!visit(dependency)) return false
                }
                visiting.remove(id)
                visited.add(id)
                return true
            }

            return map.keys.all { visit(it) }
        }

        private fun normalizeId(value: String): String =
            value.trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9._-]+"), "_")
                .trim('_')
                .take(96)

        private fun normalizeResultKey(value: String): String =
            normalizeId(value)

        private fun normalizeToken(value: String): String =
            value.trim().lowercase(Locale.ROOT).take(120)

        private fun normalizeAuthority(value: String): String =
            value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "")

        private fun fingerprint(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
