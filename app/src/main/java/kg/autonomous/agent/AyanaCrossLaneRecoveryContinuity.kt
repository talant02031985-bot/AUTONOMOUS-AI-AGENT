package kg.autonomous.agent

import org.json.JSONObject
import java.util.Locale

/**
 * AYANA R10.7 Cross-Lane Recovery Continuity v1.0.
 *
 * Pure policy layer that joins already accepted R10.3 durable recovery with the
 * R10.6 cross-lane adaptive objective ledger. It never dispatches tools and never
 * grants capability authority.
 *
 * Recovery contract:
 * - the persisted Adaptive Execution Loop and Cross-Lane Continuity snapshots must
 *   describe the same lane, authority, revision and verified-prefix count;
 * - the persisted lane/authority pair must still be inside the objective authority ceiling;
 * - unresolved side effects block automatic continuation;
 * - R10.3 remains the authority for interruption strategy (continue, reconcile,
 *   verify-terminal-only, confirmation/manual-only);
 * - restoring a snapshot must preserve objective_id, revision, verified prefix,
 *   failed-transition ledger, lane and authority exactly;
 * - no completed verified step may be replayed merely because a process/service
 *   boundary was crossed.
 */
class AyanaCrossLaneRecoveryContinuity(
    private val longTaskRecoveryCoordinator: AyanaLongTaskRecoveryCoordinator =
        AyanaLongTaskRecoveryCoordinator()
) {

    enum class Strategy {
        RESTORE_AND_CONTINUE,
        RECONCILE_THEN_CONTINUE,
        VERIFY_TERMINAL_ONLY,
        REQUIRE_USER_CONFIRMATION,
        MANUAL_RESUME_ONLY,
        BLOCKED
    }

    fun buildRecoveryCheckpoint(
        adaptiveLoop: AyanaAdaptiveExecutionLoop,
        continuity: AyanaCrossLaneAdaptiveContinuity,
        checkpointTag: String,
        safeAutoResume: Boolean
    ): JSONObject =
        JSONObject()
            .put(
                "adaptive_execution_loop",
                adaptiveLoop.persistenceSnapshot()
            )
            .put(
                "cross_lane_continuity",
                continuity.persistenceSnapshot(adaptiveLoop)
            )
            .put(
                "adaptive_lane",
                continuity.currentLane()
            )
            .put(
                "adaptive_revision",
                adaptiveLoop.currentRevision()
            )
            .put(
                "adaptive_authority",
                adaptiveLoop.authorityContext()
            )
            .put(
                "cross_lane_objective_id",
                continuity.objectiveId()
            )
            .put(
                "cross_lane_verified_prefix_count",
                adaptiveLoop.verifiedStepCount()
            )
            .put(
                "cross_lane_failed_transition_count",
                adaptiveLoop.failedTransitionCount()
            )
            .put(
                "cross_lane_replan_count",
                adaptiveLoop.replanCount()
            )
            .put(
                "safe_auto_resume",
                safeAutoResume
            )
            .put(
                "step_in_flight",
                false
            )
            .put(
                "last_checkpoint",
                checkpointTag
                    .trim()
                    .ifBlank { "cross_lane_checkpoint" }
                    .take(MAX_SHORT_CHARS)
            )

    fun evaluate(
        goal: JSONObject?,
        automatic: Boolean = false
    ): JSONObject {
        if (goal == null || goal.length() == 0) {
            return decision(
                strategy = Strategy.BLOCKED,
                reason = "goal_missing"
            )
        }

        val adaptiveSnapshot =
            goal.optJSONObject("adaptive_execution_loop")
                ?: return decision(
                    strategy = Strategy.BLOCKED,
                    reason = "adaptive_snapshot_missing"
                )

        val continuitySnapshot =
            goal.optJSONObject("cross_lane_continuity")
                ?: return decision(
                    strategy = Strategy.BLOCKED,
                    reason = "cross_lane_snapshot_missing"
                )

        val consistency =
            persistedConsistency(
                adaptiveSnapshot = adaptiveSnapshot,
                continuitySnapshot = continuitySnapshot
            )

        if (!consistency.optBoolean("verified", false)) {
            return decision(
                strategy = Strategy.BLOCKED,
                reason = consistency.optString(
                    "reason",
                    "persisted_cross_lane_invariant_failed"
                )
            )
                .put("consistency", consistency)
        }

        if (
            adaptiveSnapshot.optBoolean("unresolved_side_effect", false) ||
            continuitySnapshot.optBoolean("unresolved_side_effect", false)
        ) {
            return decision(
                strategy = Strategy.BLOCKED,
                reason = "unresolved_side_effect_blocks_cross_lane_recovery"
            )
                .put("consistency", consistency)
        }

        val baseDecision =
            longTaskRecoveryCoordinator.evaluate(
                goal = goal,
                automatic = automatic
            )

        val baseStrategy =
            baseDecision
                .optString("strategy")
                .trim()
                .uppercase(Locale.ROOT)

        val strategy =
            when (baseStrategy) {
                AyanaLongTaskRecoveryCoordinator.Strategy
                    .CONTINUE_FROM_CHECKPOINT.name ->
                    Strategy.RESTORE_AND_CONTINUE

                AyanaLongTaskRecoveryCoordinator.Strategy
                    .RECONCILE_IN_FLIGHT.name ->
                    Strategy.RECONCILE_THEN_CONTINUE

                AyanaLongTaskRecoveryCoordinator.Strategy
                    .VERIFY_COMPLETION_ONLY.name ->
                    Strategy.VERIFY_TERMINAL_ONLY

                AyanaLongTaskRecoveryCoordinator.Strategy
                    .REQUIRE_USER_CONFIRMATION.name ->
                    Strategy.REQUIRE_USER_CONFIRMATION

                AyanaLongTaskRecoveryCoordinator.Strategy
                    .MANUAL_RESUME_ONLY.name ->
                    Strategy.MANUAL_RESUME_ONLY

                else ->
                    Strategy.BLOCKED
            }

        val allowed =
            strategy !in
                setOf(
                    Strategy.BLOCKED,
                    Strategy.REQUIRE_USER_CONFIRMATION,
                    Strategy.MANUAL_RESUME_ONLY
                )

        return decision(
            strategy = strategy,
            reason =
                if (allowed) {
                    "r10_3_recovery_strategy_cross_lane_invariants_verified"
                } else {
                    baseDecision.optString(
                        "reason",
                        "cross_lane_recovery_not_allowed"
                    )
                }
        )
            .put("allowed", allowed)
            .put("automatic_requested", automatic)
            .put(
                "automatic_resume_allowed",
                allowed &&
                    baseDecision.optBoolean(
                        "automatic_resume_allowed",
                        false
                    )
            )
            .put(
                "explicit_resume_allowed",
                allowed ||
                    baseDecision.optBoolean(
                        "explicit_resume_allowed",
                        false
                    )
            )
            .put("blind_replay_allowed", false)
            .put("base_recovery", baseDecision)
            .put("consistency", consistency)
            .put(
                "objective_id",
                continuitySnapshot.optString("objective_id")
            )
            .put(
                "execution_lane",
                continuitySnapshot.optString("current_lane")
            )
            .put(
                "adaptive_revision",
                adaptiveSnapshot.optInt("revision", 0)
            )
            .put(
                "verified_prefix_count",
                consistency.optInt("verified_prefix_count", 0)
            )
    }

    fun verifyRestored(
        persistedGoal: JSONObject?,
        adaptiveLoop: AyanaAdaptiveExecutionLoop,
        continuity: AyanaCrossLaneAdaptiveContinuity
    ): JSONObject {
        if (persistedGoal == null || persistedGoal.length() == 0) {
            return verification(
                verified = false,
                reason = "persisted_goal_missing"
            )
        }

        val adaptiveSnapshot =
            persistedGoal.optJSONObject("adaptive_execution_loop")
                ?: return verification(
                    verified = false,
                    reason = "adaptive_snapshot_missing"
                )

        val continuitySnapshot =
            persistedGoal.optJSONObject("cross_lane_continuity")
                ?: return verification(
                    verified = false,
                    reason = "cross_lane_snapshot_missing"
                )

        val consistency =
            persistedConsistency(
                adaptiveSnapshot = adaptiveSnapshot,
                continuitySnapshot = continuitySnapshot
            )

        if (!consistency.optBoolean("verified", false)) {
            return verification(
                verified = false,
                reason = consistency.optString(
                    "reason",
                    "persisted_cross_lane_invariant_failed"
                )
            )
                .put("consistency", consistency)
        }

        val expectedObjectiveId =
            continuitySnapshot
                .optString("objective_id")
                .trim()

        val expectedLane =
            normalizeLane(
                continuitySnapshot.optString("current_lane")
            )

        val expectedAuthority =
            normalizeAuthority(
                continuitySnapshot.optString("current_authority")
            )

        val expectedRevision =
            adaptiveSnapshot.optInt("revision", 0)

        val expectedVerified =
            consistency.optInt("verified_prefix_count", 0)

        val expectedFailed =
            adaptiveSnapshot
                .optJSONArray("failed_transitions")
                ?.length()
                ?: 0

        val expectedReplans =
            adaptiveSnapshot
                .optJSONArray("replans")
                ?.length()
                ?: 0

        val objectiveIdPreserved =
            expectedObjectiveId.isNotBlank() &&
                continuity.objectiveId() == expectedObjectiveId

        val lanePreserved =
            normalizeLane(continuity.currentLane()) == expectedLane &&
                normalizeLane(adaptiveLoop.executionLane()) == expectedLane

        val authorityPreserved =
            normalizeAuthority(continuity.currentAuthority()) == expectedAuthority &&
                normalizeAuthority(adaptiveLoop.authorityContext()) == expectedAuthority

        val revisionPreserved =
            adaptiveLoop.currentRevision() == expectedRevision

        val verifiedPrefixPreserved =
            adaptiveLoop.verifiedStepCount() == expectedVerified

        val failedLedgerPreserved =
            adaptiveLoop.failedTransitionCount() == expectedFailed

        val replanLedgerPreserved =
            adaptiveLoop.replanCount() == expectedReplans

        val noUnresolvedSideEffect =
            !adaptiveLoop.hasUnresolvedSideEffect()

        val verified =
            objectiveIdPreserved &&
                lanePreserved &&
                authorityPreserved &&
                revisionPreserved &&
                verifiedPrefixPreserved &&
                failedLedgerPreserved &&
                replanLedgerPreserved &&
                noUnresolvedSideEffect

        return verification(
            verified = verified,
            reason =
                if (verified) {
                    "cross_lane_recovery_state_restored_exactly"
                } else {
                    "restored_cross_lane_state_mismatch"
                }
        )
            .put("objective_id_preserved", objectiveIdPreserved)
            .put("lane_preserved", lanePreserved)
            .put("authority_preserved", authorityPreserved)
            .put("revision_preserved", revisionPreserved)
            .put("verified_prefix_preserved", verifiedPrefixPreserved)
            .put("failed_transition_ledger_preserved", failedLedgerPreserved)
            .put("replan_ledger_preserved", replanLedgerPreserved)
            .put("unresolved_side_effect", adaptiveLoop.hasUnresolvedSideEffect())
            .put("blind_replay_allowed", false)
            .put("expected_revision", expectedRevision)
            .put("restored_revision", adaptiveLoop.currentRevision())
            .put("expected_verified_prefix_count", expectedVerified)
            .put("restored_verified_prefix_count", adaptiveLoop.verifiedStepCount())
    }

    private fun persistedConsistency(
        adaptiveSnapshot: JSONObject,
        continuitySnapshot: JSONObject
    ): JSONObject {
        val adaptiveVersion =
            adaptiveSnapshot.optString("version")

        if (
            adaptiveVersion.isNotBlank() &&
            adaptiveVersion !in setOf("1.0", AyanaAdaptiveExecutionLoop.VERSION)
        ) {
            return verification(
                verified = false,
                reason = "adaptive_snapshot_version_unsupported"
            )
        }

        val continuityVersion =
            continuitySnapshot.optString("version")

        if (
            continuityVersion.isNotBlank() &&
            continuityVersion != AyanaCrossLaneAdaptiveContinuity.VERSION
        ) {
            return verification(
                verified = false,
                reason = "cross_lane_snapshot_version_unsupported"
            )
        }

        val objectiveId =
            continuitySnapshot
                .optString("objective_id")
                .trim()

        if (objectiveId.isBlank()) {
            return verification(
                verified = false,
                reason = "objective_id_missing"
            )
        }

        val lane =
            normalizeLane(
                continuitySnapshot.optString("current_lane")
            )

        val authority =
            normalizeAuthority(
                continuitySnapshot.optString("current_authority")
            )

        val adaptiveLane =
            normalizeLane(
                adaptiveSnapshot.optString("execution_lane")
            )

        val adaptiveAuthority =
            normalizeAuthority(
                adaptiveSnapshot.optString("authority_context")
            )

        if (
            lane.isBlank() ||
            authority.isBlank() ||
            adaptiveLane != lane ||
            adaptiveAuthority != authority
        ) {
            return verification(
                verified = false,
                reason = "adaptive_and_cross_lane_context_mismatch"
            )
        }

        val ceiling =
            continuitySnapshot.optJSONObject("authority_ceiling")
                ?: return verification(
                    verified = false,
                    reason = "authority_ceiling_missing"
                )

        val expectedAuthority =
            normalizeAuthority(
                ceiling.optString(lane)
            )

        if (
            expectedAuthority.isBlank() ||
            expectedAuthority != authority
        ) {
            return verification(
                verified = false,
                reason = "persisted_authority_ceiling_violation"
            )
        }

        val adaptiveRevision =
            adaptiveSnapshot.optInt("revision", 0)

        val continuityRevision =
            continuitySnapshot.optInt(
                "adaptive_revision",
                adaptiveRevision
            )

        if (adaptiveRevision != continuityRevision) {
            return verification(
                verified = false,
                reason = "adaptive_revision_mismatch"
            )
        }

        val verifiedPrefixCount =
            adaptiveSnapshot
                .optJSONArray("verified_steps")
                ?.length()
                ?: 0

        val continuityVerifiedPrefix =
            continuitySnapshot.optInt(
                "verified_prefix_count",
                verifiedPrefixCount
            )

        if (verifiedPrefixCount != continuityVerifiedPrefix) {
            return verification(
                verified = false,
                reason = "verified_prefix_count_mismatch"
            )
        }

        return verification(
            verified = true,
            reason = "persisted_cross_lane_ledgers_consistent"
        )
            .put("objective_id", objectiveId)
            .put("execution_lane", lane)
            .put("authority", authority)
            .put("adaptive_revision", adaptiveRevision)
            .put("verified_prefix_count", verifiedPrefixCount)
            .put(
                "failed_transition_count",
                adaptiveSnapshot
                    .optJSONArray("failed_transitions")
                    ?.length()
                    ?: 0
            )
            .put(
                "replan_count",
                adaptiveSnapshot
                    .optJSONArray("replans")
                    ?.length()
                    ?: 0
            )
    }

    private fun decision(
        strategy: Strategy,
        reason: String
    ): JSONObject =
        JSONObject()
            .put("cross_lane_recovery_version", VERSION)
            .put("strategy", strategy.name)
            .put("reason", reason)
            .put("allowed", false)
            .put("automatic_resume_allowed", false)
            .put("explicit_resume_allowed", false)
            .put("blind_replay_allowed", false)

    private fun verification(
        verified: Boolean,
        reason: String
    ): JSONObject =
        JSONObject()
            .put("cross_lane_recovery_version", VERSION)
            .put("verified", verified)
            .put("reason", reason)

    private fun normalizeLane(
        value: String
    ): String =
        value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_\\-]+"), "_")
            .trim('_')
            .take(80)

    private fun normalizeAuthority(
        value: String
    ): String =
        value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .take(MAX_AUTHORITY_CHARS)

    companion object {
        const val VERSION = "1.0"

        private const val MAX_SHORT_CHARS = 180
        private const val MAX_AUTHORITY_CHARS = 1200

        fun selfTest(): Boolean {
            val recovery = AyanaCrossLaneRecoveryContinuity()

            val loop =
                AyanaAdaptiveExecutionLoop.create(
                    objective = "R10.7 cross-lane recovery self test",
                    stateFingerprint = "state-A",
                    executionLane = AyanaCrossLaneAdaptiveContinuity.LANE_AGENT_CORE,
                    authorityContext = AyanaCrossLaneAdaptiveContinuity.AUTH_AGENT_CORE
                )

            val continuity =
                AyanaCrossLaneAdaptiveContinuity.create(
                    objective = "R10.7 cross-lane recovery self test",
                    adaptiveLoop = loop,
                    initialLane = AyanaCrossLaneAdaptiveContinuity.LANE_AGENT_CORE,
                    initialAuthority = AyanaCrossLaneAdaptiveContinuity.AUTH_AGENT_CORE,
                    authorityCeiling =
                        AyanaCrossLaneAdaptiveContinuity.defaultUnifiedAuthorityCeiling()
                )

            val args = JSONObject()
            val proposal =
                loop.propose(
                    toolName = "get_device_state",
                    arguments = args,
                    stateFingerprint = "state-A",
                    mayMutate = false,
                    authoritySource = AyanaCrossLaneAdaptiveContinuity.AUTH_AGENT_CORE
                )

            if (!proposal.optBoolean("allowed", false)) return false

            val record =
                loop.recordResult(
                    toolName = "get_device_state",
                    arguments = args,
                    beforeStateFingerprint = "state-A",
                    afterStateFingerprint = "state-B",
                    success = true,
                    verified = true,
                    actionDispatched = false,
                    actionCommitted = false,
                    reconciliationComplete = true,
                    evidence = "self-test read-only verification"
                )

            if (!record.optBoolean("verified", false)) return false

            val transition =
                continuity.transitionTo(
                    adaptiveLoop = loop,
                    targetLane = AyanaCrossLaneAdaptiveContinuity.LANE_MULTI_APP,
                    targetAuthority = AyanaCrossLaneAdaptiveContinuity.AUTH_MULTI_APP,
                    stateFingerprint = "state-B",
                    reason = "self_test_pre_interrupt_handoff"
                )

            if (!transition.optBoolean("allowed", false)) return false

            val goal =
                recovery
                    .buildRecoveryCheckpoint(
                        adaptiveLoop = loop,
                        continuity = continuity,
                        checkpointTag = "r10_7_self_test_verified_checkpoint",
                        safeAutoResume = true
                    )
                    .put("status", "recovery_pending")
                    .put("mode", "orchestrator")
                    .put("recovery_reason", "service_destroyed")
                    .put("last_checkpoint", "interrupted")
                    .put(
                        "interrupted_from_checkpoint",
                        "r10_7_self_test_verified_checkpoint"
                    )
                    .put("interrupted_from_in_flight", false)

            val decision =
                recovery.evaluate(
                    goal = goal,
                    automatic = true
                )

            if (
                decision.optString("strategy") !=
                Strategy.RESTORE_AND_CONTINUE.name
            ) {
                return false
            }

            if (!decision.optBoolean("automatic_resume_allowed", false)) {
                return false
            }

            val restoredLoop =
                AyanaAdaptiveExecutionLoop.restore(
                    snapshot = goal.optJSONObject("adaptive_execution_loop"),
                    fallbackObjective = "R10.7 cross-lane recovery self test",
                    fallbackExecutionLane = AyanaCrossLaneAdaptiveContinuity.LANE_AGENT_CORE,
                    fallbackAuthorityContext = AyanaCrossLaneAdaptiveContinuity.AUTH_AGENT_CORE
                )

            val restoredContinuity =
                AyanaCrossLaneAdaptiveContinuity.restore(
                    snapshot = goal.optJSONObject("cross_lane_continuity"),
                    fallbackObjective = "R10.7 cross-lane recovery self test",
                    adaptiveLoop = restoredLoop,
                    fallbackAuthorityCeiling =
                        AyanaCrossLaneAdaptiveContinuity.defaultUnifiedAuthorityCeiling()
                )

            val restored =
                recovery.verifyRestored(
                    persistedGoal = goal,
                    adaptiveLoop = restoredLoop,
                    continuity = restoredContinuity
                )

            if (!restored.optBoolean("verified", false)) return false
            if (restoredContinuity.objectiveId() != continuity.objectiveId()) return false
            if (restoredLoop.verifiedStepCount() != loop.verifiedStepCount()) return false
            if (restoredLoop.currentRevision() != loop.currentRevision()) return false

            val tampered = JSONObject(goal.toString())
            val tamperedContinuity =
                JSONObject(
                    tampered
                        .optJSONObject("cross_lane_continuity")
                        ?.toString()
                        ?: return false
                )
                    .put("current_authority", "github_repository_write")

            tampered.put(
                "cross_lane_continuity",
                tamperedContinuity
            )

            val blocked =
                recovery.evaluate(
                    goal = tampered,
                    automatic = true
                )

            if (blocked.optBoolean("allowed", true)) return false
            if (
                blocked.optString("strategy") !=
                Strategy.BLOCKED.name
            ) {
                return false
            }

            return true
        }
    }
}
