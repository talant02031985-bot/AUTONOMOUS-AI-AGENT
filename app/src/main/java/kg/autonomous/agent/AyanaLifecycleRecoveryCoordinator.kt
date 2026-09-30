package kg.autonomous.agent

import org.json.JSONObject
import java.util.Locale

/**
 * AYANA R10.12 Natural Lifecycle Recovery Coordinator v1.0.
 *
 * Pure policy/audit layer for production dynamic objectives crossing a real
 * Android Service lifecycle recreation. It never starts/stops services and never
 * dispatches tools. VoiceService owns lifecycle operations and execution.
 *
 * Contract:
 * - automatic lifecycle continuation is allowed only for production dynamic goals;
 * - cancelled/failed/completed/confirmation-bound goals never auto-resume;
 * - in-flight or unresolved-side-effect boundaries never auto-resume;
 * - safe_auto_resume must be explicitly persisted before lifecycle handoff;
 * - service instance identity must change before an R10.12 acceptance can claim
 *   real Service recreation;
 * - process identity is recorded separately and is never inferred from a service
 *   object recreation;
 * - user cancellation/stop can never be reinterpreted as process interruption.
 */
class AyanaLifecycleRecoveryCoordinator {

    fun evaluateAutomaticResume(
        goal: JSONObject?,
        currentServiceInstanceId: String,
        currentProcessId: Int
    ): JSONObject {
        if (goal == null || goal.length() == 0) {
            return decision(false, "goal_missing")
        }

        if (!goal.optBoolean("production_dynamic_planner", false)) {
            return decision(false, "not_production_dynamic_goal")
        }

        val status = normalize(goal.optString("status"))
        if (status !in setOf("recovery_pending", "active", "paused")) {
            return decision(false, "goal_status_not_auto_recoverable")
                .put("status", status)
        }

        if (goal.optBoolean("requires_confirmation", false)) {
            return decision(false, "confirmation_boundary")
        }

        if (!goal.optBoolean("safe_auto_resume", false)) {
            return decision(false, "safe_auto_resume_not_persisted")
        }

        if (
            goal.optBoolean("step_in_flight", false) ||
            goal.optBoolean("interrupted_from_in_flight", false)
        ) {
            return decision(false, "in_flight_boundary_requires_reconciliation")
        }

        val adaptive = goal.optJSONObject("adaptive_execution_loop")
        if (adaptive?.optBoolean("unresolved_side_effect", false) == true) {
            return decision(false, "adaptive_unresolved_side_effect")
        }

        val continuity = goal.optJSONObject("cross_lane_continuity")
        if (continuity?.optBoolean("unresolved_side_effect", false) == true) {
            return decision(false, "cross_lane_unresolved_side_effect")
        }

        val runtime =
            goal.optJSONObject("dynamic_planner_runtime_context")
                ?: JSONObject()

        if (runtime.optBoolean("r10_12_user_stop_requested", false)) {
            return decision(false, "user_stop_marker")
        }

        val originServiceInstanceId =
            runtime.optString("r10_12_origin_service_instance_id").trim()
        val originProcessId =
            runtime.optInt("r10_12_origin_process_id", -1)
        val acceptance =
            runtime.optBoolean("r10_12_acceptance", false)

        val serviceRecreated =
            originServiceInstanceId.isNotBlank() &&
                currentServiceInstanceId.isNotBlank() &&
                originServiceInstanceId != currentServiceInstanceId

        val processRecreated =
            originProcessId > 0 &&
                currentProcessId > 0 &&
                originProcessId != currentProcessId

        if (acceptance && !serviceRecreated) {
            return decision(false, "service_instance_not_recreated")
                .put("service_instance_recreated", false)
                .put("process_recreated", processRecreated)
        }

        return decision(true, "lifecycle_auto_resume_contract_verified")
            .put("status", status)
            .put("service_instance_recreated", serviceRecreated)
            .put("process_recreated", processRecreated)
            .put("current_service_instance_id", currentServiceInstanceId)
            .put("origin_service_instance_id", originServiceInstanceId)
            .put("current_process_id", currentProcessId)
            .put("origin_process_id", originProcessId)
            .put("acceptance", acceptance)
            .put("blind_replay_allowed", false)
    }

    fun verifyRestoredLifecycle(
        goal: JSONObject?,
        currentServiceInstanceId: String,
        currentProcessId: Int
    ): JSONObject {
        val decision =
            evaluateAutomaticResume(
                goal = goal,
                currentServiceInstanceId = currentServiceInstanceId,
                currentProcessId = currentProcessId
            )

        if (!decision.optBoolean("allowed", false)) {
            return JSONObject(decision.toString())
                .put("verified", false)
        }

        val runtime =
            goal
                ?.optJSONObject("dynamic_planner_runtime_context")
                ?: JSONObject()

        val requested =
            runtime.optBoolean("r10_12_service_recreation_requested", false)
        val checkpointPersisted =
            runtime.optBoolean("r10_12_pre_recreation_checkpoint_persisted", false)
        val preVerified =
            runtime.optInt("r10_12_pre_recreation_verified_prefix", -1)

        val verified =
            requested &&
                checkpointPersisted &&
                preVerified >= 0 &&
                decision.optBoolean("service_instance_recreated", false)

        return JSONObject(decision.toString())
            .put("verified", verified)
            .put(
                "reason",
                if (verified) {
                    "service_lifecycle_recreation_verified"
                } else {
                    "lifecycle_restore_evidence_incomplete"
                }
            )
            .put("pre_recreation_verified_prefix", preVerified)
            .put("blind_replay_allowed", false)
    }

    fun selfTest(): Boolean {
        return try {
            val runtime =
                JSONObject()
                    .put("r10_12_acceptance", true)
                    .put("r10_12_service_recreation_requested", true)
                    .put("r10_12_pre_recreation_checkpoint_persisted", true)
                    .put("r10_12_pre_recreation_verified_prefix", 4)
                    .put("r10_12_origin_service_instance_id", "service-A")
                    .put("r10_12_origin_process_id", 100)

            val goal =
                JSONObject()
                    .put("production_dynamic_planner", true)
                    .put("status", "recovery_pending")
                    .put("safe_auto_resume", true)
                    .put("requires_confirmation", false)
                    .put("step_in_flight", false)
                    .put("interrupted_from_in_flight", false)
                    .put(
                        "adaptive_execution_loop",
                        JSONObject().put("unresolved_side_effect", false)
                    )
                    .put(
                        "cross_lane_continuity",
                        JSONObject().put("unresolved_side_effect", false)
                    )
                    .put("dynamic_planner_runtime_context", runtime)

            val allowed =
                verifyRestoredLifecycle(
                    goal = goal,
                    currentServiceInstanceId = "service-B",
                    currentProcessId = 100
                )

            val sameInstance =
                verifyRestoredLifecycle(
                    goal = goal,
                    currentServiceInstanceId = "service-A",
                    currentProcessId = 100
                )

            val inFlight =
                evaluateAutomaticResume(
                    goal = JSONObject(goal.toString())
                        .put("step_in_flight", true),
                    currentServiceInstanceId = "service-B",
                    currentProcessId = 100
                )

            val cancelled =
                evaluateAutomaticResume(
                    goal = JSONObject(goal.toString())
                        .put("status", "cancelled"),
                    currentServiceInstanceId = "service-B",
                    currentProcessId = 100
                )

            allowed.optBoolean("verified", false) &&
                allowed.optBoolean("service_instance_recreated", false) &&
                !allowed.optBoolean("process_recreated", true) &&
                !sameInstance.optBoolean("verified", true) &&
                !inFlight.optBoolean("allowed", true) &&
                !cancelled.optBoolean("allowed", true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun decision(
        allowed: Boolean,
        reason: String
    ): JSONObject =
        JSONObject()
            .put("lifecycle_recovery_version", VERSION)
            .put("allowed", allowed)
            .put("verified", allowed)
            .put("reason", reason)
            .put("blind_replay_allowed", false)

    private fun normalize(value: String?): String =
        value.orEmpty()
            .trim()
            .lowercase(Locale.ROOT)

    companion object {
        const val VERSION = "1.0"
    }
}
