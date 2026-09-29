package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * AYANA R10.6 Cross-Lane Adaptive Continuity v1.0.
 *
 * This coordinator does not execute tools and does not grant capability authority.
 * AyanaAdaptiveExecutionLoop remains the verified plan/replan ledger. This class
 * adds one objective identity and a fail-closed execution-lane transition contract
 * around that ledger.
 *
 * Invariants:
 * - one objective_id survives Agent Core <-> Android Goal <-> Multi-App transitions;
 * - a lane switch never changes Adaptive Loop revision or verified prefix;
 * - only exact lane/authority pairs present in the immutable authority ceiling may bind;
 * - unknown/unsupported authority cannot be laundered through another lane;
 * - unresolved side effects block lane switching;
 * - same failed transition replay remains owned by the common Adaptive Loop ledger and
 *   therefore remains blocked after a lane switch;
 * - persistence is bounded and backward-safe: missing R10.6 state starts from the
 *   already-restored Adaptive Loop context without replaying work.
 */
class AyanaCrossLaneAdaptiveContinuity private constructor(
    private val objectiveId: String,
    private val objective: String,
    private var currentLane: String,
    private var currentAuthority: String,
    private val authorityCeiling: JSONObject,
    private val laneTransitions: JSONArray,
    private var transitionSequence: Int,
    private var lastStateFingerprint: String
) {

    fun objectiveId(): String = objectiveId

    fun currentLane(): String = currentLane

    fun currentAuthority(): String = currentAuthority

    fun laneTransitionCount(): Int = laneTransitions.length()

    fun authorityCeilingSnapshot(): JSONObject = JSONObject(authorityCeiling.toString())

    fun authorityAllowed(
        lane: String,
        authority: String
    ): Boolean {
        val cleanLane = normalizeLane(lane)
        val cleanAuthority = normalizeAuthority(authority)
        if (cleanLane.isBlank() || cleanAuthority.isBlank()) return false

        val expected =
            authorityCeiling
                .optString(cleanLane)
                .let(::normalizeAuthority)

        return expected.isNotBlank() && expected == cleanAuthority
    }

    /**
     * Bind or transition the shared Adaptive Loop to another registered lane.
     * A same-lane bind is allowed only when the authority remains exactly within
     * the objective's ceiling. A real lane transition is recorded only after the
     * underlying Adaptive Loop reports the new context and its revision/prefix are
     * proven unchanged.
     */
    fun transitionTo(
        adaptiveLoop: AyanaAdaptiveExecutionLoop,
        targetLane: String,
        targetAuthority: String,
        stateFingerprint: String,
        reason: String
    ): JSONObject {
        val cleanLane = normalizeLane(targetLane)
        val cleanAuthority = normalizeAuthority(targetAuthority)
        val cleanState = stateFingerprint.trim().take(MAX_FINGERPRINT_CHARS)

        if (!authorityAllowed(cleanLane, cleanAuthority)) {
            return decision(
                allowed = false,
                reason = "authority_ceiling_violation",
                targetLane = cleanLane,
                targetAuthority = cleanAuthority,
                adaptiveLoop = adaptiveLoop
            )
        }

        if (adaptiveLoop.hasUnresolvedSideEffect()) {
            return decision(
                allowed = false,
                reason = "unresolved_side_effect_blocks_lane_switch",
                targetLane = cleanLane,
                targetAuthority = cleanAuthority,
                adaptiveLoop = adaptiveLoop
            )
        }

        if (
            adaptiveLoop.status() in
            setOf(
                AyanaAdaptiveExecutionLoop.STATUS_SUCCEEDED,
                AyanaAdaptiveExecutionLoop.STATUS_FAILED,
                AyanaAdaptiveExecutionLoop.STATUS_CANCELLED
            )
        ) {
            return decision(
                allowed = false,
                reason = "adaptive_loop_terminal",
                targetLane = cleanLane,
                targetAuthority = cleanAuthority,
                adaptiveLoop = adaptiveLoop
            )
        }

        val beforeRevision = adaptiveLoop.currentRevision()
        val beforeVerified = adaptiveLoop.verifiedStepCount()
        val fromLane = currentLane
        val fromAuthority = currentAuthority

        // Same lane + same authority is an idempotent bind, not a lane transition.
        if (
            cleanLane == currentLane &&
            cleanAuthority == currentAuthority
        ) {
            adaptiveLoop.bindExecutionContext(
                lane = cleanLane,
                authority = cleanAuthority
            )

            lastStateFingerprint =
                cleanState.ifBlank { lastStateFingerprint }

            return decision(
                allowed = true,
                reason = "context_already_bound",
                targetLane = cleanLane,
                targetAuthority = cleanAuthority,
                adaptiveLoop = adaptiveLoop
            )
                .put("transition_recorded", false)
                .put("revision_preserved", adaptiveLoop.currentRevision() == beforeRevision)
                .put("verified_prefix_preserved", adaptiveLoop.verifiedStepCount() == beforeVerified)
        }

        val bound =
            adaptiveLoop.bindExecutionContext(
                lane = cleanLane,
                authority = cleanAuthority
            )

        val revisionPreserved = adaptiveLoop.currentRevision() == beforeRevision
        val prefixPreserved = adaptiveLoop.verifiedStepCount() == beforeVerified
        val bindingVerified =
            adaptiveLoop.executionLane() == cleanLane &&
                normalizeAuthority(adaptiveLoop.authorityContext()) == cleanAuthority

        if (!revisionPreserved || !prefixPreserved || !bindingVerified) {
            // Fail closed. The previous authority is restored because a continuity
            // transition is not permitted to mutate plan truth or prefix truth.
            adaptiveLoop.bindExecutionContext(
                lane = fromLane,
                authority = fromAuthority
            )

            return decision(
                allowed = false,
                reason = "lane_transition_invariant_failed",
                targetLane = cleanLane,
                targetAuthority = cleanAuthority,
                adaptiveLoop = adaptiveLoop
            )
                .put("bind_result", bound)
                .put("revision_preserved", revisionPreserved)
                .put("verified_prefix_preserved", prefixPreserved)
                .put("binding_verified", bindingVerified)
        }

        transitionSequence++
        currentLane = cleanLane
        currentAuthority = cleanAuthority
        lastStateFingerprint = cleanState.ifBlank { lastStateFingerprint }

        laneTransitions.put(
            JSONObject()
                .put("seq", transitionSequence)
                .put("from_lane", fromLane)
                .put("to_lane", currentLane)
                .put("from_authority", fromAuthority.take(MAX_AUTHORITY_CHARS))
                .put("to_authority", currentAuthority.take(MAX_AUTHORITY_CHARS))
                .put("reason", reason.trim().take(MAX_REASON_CHARS))
                .put("state_fingerprint", lastStateFingerprint)
                .put("revision", adaptiveLoop.currentRevision())
                .put("verified_prefix_count", adaptiveLoop.verifiedStepCount())
                .put("revision_preserved", true)
                .put("verified_prefix_preserved", true)
                .put("at_ms", System.currentTimeMillis())
        )
        trimArray(laneTransitions, MAX_LANE_TRANSITIONS)

        return decision(
            allowed = true,
            reason = "lane_transition_verified",
            targetLane = cleanLane,
            targetAuthority = cleanAuthority,
            adaptiveLoop = adaptiveLoop
        )
            .put("transition_recorded", true)
            .put("from_lane", fromLane)
            .put("to_lane", currentLane)
            .put("revision_preserved", true)
            .put("verified_prefix_preserved", true)
    }

    fun persistenceSnapshot(
        adaptiveLoop: AyanaAdaptiveExecutionLoop
    ): JSONObject {
        val full =
            JSONObject()
                .put("version", VERSION)
                .put("objective_id", objectiveId)
                .put("objective", objective.take(MAX_OBJECTIVE_CHARS))
                .put("current_lane", currentLane)
                .put("current_authority", currentAuthority.take(MAX_AUTHORITY_CHARS))
                .put("authority_ceiling", JSONObject(authorityCeiling.toString()))
                .put("lane_transitions", boundedArray(laneTransitions, MAX_PERSISTED_TRANSITIONS))
                .put("transition_sequence", transitionSequence)
                .put("last_state_fingerprint", lastStateFingerprint)
                .put("adaptive_revision", adaptiveLoop.currentRevision())
                .put("verified_prefix_count", adaptiveLoop.verifiedStepCount())
                .put("unresolved_side_effect", adaptiveLoop.hasUnresolvedSideEffect())
                .put("updated_at_ms", System.currentTimeMillis())

        if (full.toString().length <= MAX_PERSISTED_CHARS) {
            return full
        }

        return JSONObject()
            .put("version", VERSION)
            .put("objective_id", objectiveId)
            .put("objective", objective.take(500))
            .put("current_lane", currentLane)
            .put("current_authority", currentAuthority.take(420))
            .put("authority_ceiling", JSONObject(authorityCeiling.toString()))
            .put("lane_transitions", boundedArray(laneTransitions, 6))
            .put("transition_sequence", transitionSequence)
            .put("last_state_fingerprint", lastStateFingerprint)
            .put("adaptive_revision", adaptiveLoop.currentRevision())
            .put("verified_prefix_count", adaptiveLoop.verifiedStepCount())
            .put("unresolved_side_effect", adaptiveLoop.hasUnresolvedSideEffect())
            .put("compacted", true)
            .put("updated_at_ms", System.currentTimeMillis())
    }

    fun compactSummary(
        adaptiveLoop: AyanaAdaptiveExecutionLoop
    ): String =
        "cross_lane=v$VERSION; objective_id=$objectiveId; lane=$currentLane; " +
            "transitions=${laneTransitions.length()}; adaptive_revision=${adaptiveLoop.currentRevision()}; " +
            "verified_prefix=${adaptiveLoop.verifiedStepCount()}; unresolved_side_effect=${adaptiveLoop.hasUnresolvedSideEffect()}"

    private fun decision(
        allowed: Boolean,
        reason: String,
        targetLane: String,
        targetAuthority: String,
        adaptiveLoop: AyanaAdaptiveExecutionLoop
    ): JSONObject =
        JSONObject()
            .put("allowed", allowed)
            .put("reason", reason)
            .put("objective_id", objectiveId)
            .put("current_lane", currentLane)
            .put("current_authority", currentAuthority)
            .put("target_lane", targetLane)
            .put("target_authority", targetAuthority)
            .put("adaptive_revision", adaptiveLoop.currentRevision())
            .put("verified_prefix_count", adaptiveLoop.verifiedStepCount())
            .put("unresolved_side_effect", adaptiveLoop.hasUnresolvedSideEffect())

    companion object {
        const val VERSION = "1.0"

        const val LANE_AGENT_CORE = "agent_core"
        const val LANE_ANDROID_GOAL = "android_goal"
        const val LANE_MULTI_APP = "multi_app"

        const val AUTH_AGENT_CORE =
            "worker_schema+safety_policy+registered_executor"

        const val AUTH_ANDROID_GOAL =
            "goal_compiler+android_task_engine+safety_policy+registered_executor"

        const val AUTH_MULTI_APP =
            "app_integration_registry+verified_result_transfer+screen_intelligence"

        private const val MAX_OBJECTIVE_CHARS = 1800
        private const val MAX_OBJECTIVE_ID_CHARS = 96
        private const val MAX_AUTHORITY_CHARS = 1200
        private const val MAX_REASON_CHARS = 1000
        private const val MAX_FINGERPRINT_CHARS = 96
        private const val MAX_LANE_TRANSITIONS = 24
        private const val MAX_PERSISTED_TRANSITIONS = 12
        private const val MAX_PERSISTED_CHARS = 12_000

        fun defaultUnifiedAuthorityCeiling(): JSONObject =
            JSONObject()
                .put(LANE_AGENT_CORE, AUTH_AGENT_CORE)
                .put(LANE_ANDROID_GOAL, AUTH_ANDROID_GOAL)
                .put(LANE_MULTI_APP, AUTH_MULTI_APP)

        fun singleLaneAuthorityCeiling(
            lane: String,
            authority: String
        ): JSONObject =
            JSONObject()
                .put(
                    normalizeLane(lane),
                    normalizeAuthority(authority)
                )

        fun create(
            objective: String,
            adaptiveLoop: AyanaAdaptiveExecutionLoop,
            initialLane: String = adaptiveLoop.executionLane(),
            initialAuthority: String = adaptiveLoop.authorityContext(),
            authorityCeiling: JSONObject = defaultUnifiedAuthorityCeiling()
        ): AyanaCrossLaneAdaptiveContinuity {
            val cleanObjective = objective.trim().take(MAX_OBJECTIVE_CHARS)
            val cleanLane = normalizeLane(initialLane)
            val cleanAuthority = normalizeAuthority(initialAuthority)
            val cleanCeiling = sanitizeCeiling(authorityCeiling)

            val safeLane =
                cleanLane.takeIf {
                    cleanCeiling.optString(it).let(::normalizeAuthority) == cleanAuthority
                }
                    ?: LANE_AGENT_CORE

            val safeAuthority =
                cleanCeiling
                    .optString(safeLane)
                    .let(::normalizeAuthority)
                    .ifBlank { AUTH_AGENT_CORE }

            adaptiveLoop.bindExecutionContext(
                lane = safeLane,
                authority = safeAuthority
            )

            return AyanaCrossLaneAdaptiveContinuity(
                objectiveId = createObjectiveId(cleanObjective),
                objective = cleanObjective,
                currentLane = safeLane,
                currentAuthority = safeAuthority,
                authorityCeiling = cleanCeiling,
                laneTransitions = JSONArray(),
                transitionSequence = 0,
                lastStateFingerprint = ""
            )
        }

        fun restore(
            snapshot: JSONObject?,
            fallbackObjective: String,
            adaptiveLoop: AyanaAdaptiveExecutionLoop,
            fallbackAuthorityCeiling: JSONObject = defaultUnifiedAuthorityCeiling()
        ): AyanaCrossLaneAdaptiveContinuity {
            if (snapshot == null || snapshot.length() == 0) {
                return create(
                    objective = fallbackObjective,
                    adaptiveLoop = adaptiveLoop,
                    initialLane = adaptiveLoop.executionLane(),
                    initialAuthority = adaptiveLoop.authorityContext(),
                    authorityCeiling = fallbackAuthorityCeiling
                )
            }

            val version = snapshot.optString("version")
            if (version.isNotBlank() && version != VERSION) {
                return create(
                    objective = fallbackObjective,
                    adaptiveLoop = adaptiveLoop,
                    initialLane = adaptiveLoop.executionLane(),
                    initialAuthority = adaptiveLoop.authorityContext(),
                    authorityCeiling = fallbackAuthorityCeiling
                )
            }

            val ceiling =
                sanitizeCeiling(
                    snapshot.optJSONObject("authority_ceiling")
                        ?: fallbackAuthorityCeiling
                )

            val requestedLane =
                normalizeLane(
                    snapshot.optString(
                        "current_lane",
                        adaptiveLoop.executionLane()
                    )
                )

            val requestedAuthority =
                normalizeAuthority(
                    snapshot.optString(
                        "current_authority",
                        adaptiveLoop.authorityContext()
                    )
                )

            val laneAllowed =
                ceiling
                    .optString(requestedLane)
                    .let(::normalizeAuthority) == requestedAuthority

            val safeLane =
                if (laneAllowed) requestedLane
                else normalizeLane(adaptiveLoop.executionLane())

            val safeAuthority =
                ceiling
                    .optString(safeLane)
                    .let(::normalizeAuthority)
                    .ifBlank {
                        normalizeAuthority(adaptiveLoop.authorityContext())
                    }

            adaptiveLoop.bindExecutionContext(
                lane = safeLane,
                authority = safeAuthority
            )

            return AyanaCrossLaneAdaptiveContinuity(
                objectiveId =
                    snapshot.optString("objective_id")
                        .trim()
                        .take(MAX_OBJECTIVE_ID_CHARS)
                        .ifBlank {
                            createObjectiveId(
                                snapshot.optString("objective", fallbackObjective)
                            )
                        },
                objective =
                    snapshot.optString("objective", fallbackObjective)
                        .ifBlank { fallbackObjective }
                        .take(MAX_OBJECTIVE_CHARS),
                currentLane = safeLane,
                currentAuthority = safeAuthority,
                authorityCeiling = ceiling,
                laneTransitions =
                    boundedArray(
                        snapshot.optJSONArray("lane_transitions") ?: JSONArray(),
                        MAX_LANE_TRANSITIONS
                    ),
                transitionSequence =
                    snapshot.optInt("transition_sequence", 0)
                        .coerceAtLeast(0),
                lastStateFingerprint =
                    snapshot.optString("last_state_fingerprint")
                        .take(MAX_FINGERPRINT_CHARS)
            )
        }

        fun selfTest(): Boolean {
            val loop =
                AyanaAdaptiveExecutionLoop.create(
                    objective = "cross lane self test",
                    stateFingerprint = "state-A",
                    executionLane = LANE_AGENT_CORE,
                    authorityContext = AUTH_AGENT_CORE
                )

            val continuity =
                create(
                    objective = "cross lane self test",
                    adaptiveLoop = loop
                )

            val originalId = continuity.objectiveId()
            val verifiedBefore = loop.verifiedStepCount()
            val revisionBefore = loop.currentRevision()

            val android =
                continuity.transitionTo(
                    adaptiveLoop = loop,
                    targetLane = LANE_ANDROID_GOAL,
                    targetAuthority = AUTH_ANDROID_GOAL,
                    stateFingerprint = "state-A",
                    reason = "self_test_android_goal"
                )

            if (!android.optBoolean("allowed", false)) return false
            if (loop.currentRevision() != revisionBefore) return false
            if (loop.verifiedStepCount() != verifiedBefore) return false

            val laundering =
                continuity.transitionTo(
                    adaptiveLoop = loop,
                    targetLane = LANE_MULTI_APP,
                    targetAuthority = "github_repository_write",
                    stateFingerprint = "state-A",
                    reason = "must_be_blocked"
                )

            if (laundering.optBoolean("allowed", true)) return false
            if (laundering.optString("reason") != "authority_ceiling_violation") return false

            val multi =
                continuity.transitionTo(
                    adaptiveLoop = loop,
                    targetLane = LANE_MULTI_APP,
                    targetAuthority = AUTH_MULTI_APP,
                    stateFingerprint = "state-A",
                    reason = "self_test_multi_app"
                )

            if (!multi.optBoolean("allowed", false)) return false
            if (continuity.objectiveId() != originalId) return false

            val restoredLoop =
                AyanaAdaptiveExecutionLoop.restore(
                    snapshot = loop.persistenceSnapshot(),
                    fallbackObjective = "cross lane self test",
                    fallbackExecutionLane = LANE_AGENT_CORE,
                    fallbackAuthorityContext = AUTH_AGENT_CORE
                )

            val restored =
                restore(
                    snapshot = continuity.persistenceSnapshot(loop),
                    fallbackObjective = "cross lane self test",
                    adaptiveLoop = restoredLoop
                )

            if (restored.objectiveId() != originalId) return false
            if (restored.currentLane() != LANE_MULTI_APP) return false
            if (restored.laneTransitionCount() != 2) return false
            if (restoredLoop.currentRevision() != loop.currentRevision()) return false
            if (restoredLoop.verifiedStepCount() != loop.verifiedStepCount()) return false

            return true
        }

        private fun createObjectiveId(
            objective: String
        ): String {
            val normalized =
                objective
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(5000)

            val digest =
                try {
                    MessageDigest.getInstance("SHA-256")
                        .digest(normalized.toByteArray(Charsets.UTF_8))
                        .joinToString("") { byte -> "%02x".format(byte) }
                        .take(20)
                } catch (_: Exception) {
                    Integer.toHexString(normalized.hashCode())
                }

            return "obj-${System.currentTimeMillis()}-$digest"
                .take(MAX_OBJECTIVE_ID_CHARS)
        }

        private fun sanitizeCeiling(
            source: JSONObject
        ): JSONObject {
            val out = JSONObject()

            listOf(
                LANE_AGENT_CORE,
                LANE_ANDROID_GOAL,
                LANE_MULTI_APP
            ).forEach { lane ->
                val authority =
                    normalizeAuthority(
                        source.optString(lane)
                    )

                if (authority.isNotBlank()) {
                    out.put(lane, authority)
                }
            }

            if (out.length() == 0) {
                return defaultUnifiedAuthorityCeiling()
            }

            return out
        }

        private fun normalizeLane(
            lane: String
        ): String =
            lane
                .trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9_\\-]+"), "_")
                .trim('_')
                .take(80)

        private fun normalizeAuthority(
            authority: String
        ): String =
            authority
                .trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
                .take(MAX_AUTHORITY_CHARS)

        private fun boundedArray(
            source: JSONArray,
            maxItems: Int
        ): JSONArray {
            val out = JSONArray()
            val start = (source.length() - maxItems).coerceAtLeast(0)
            for (index in start until source.length()) {
                val item = source.opt(index)
                when (item) {
                    is JSONObject -> out.put(JSONObject(item.toString()))
                    is JSONArray -> out.put(JSONArray(item.toString()))
                    null -> Unit
                    else -> out.put(item)
                }
            }
            return out
        }

        private fun trimArray(
            array: JSONArray,
            maxItems: Int
        ) {
            while (array.length() > maxItems) {
                array.remove(0)
            }
        }
    }
}
