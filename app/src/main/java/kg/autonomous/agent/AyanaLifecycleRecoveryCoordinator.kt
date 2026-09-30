package kg.autonomous.agent

import org.json.JSONObject
import java.util.Locale

/**
 * AYANA R10.13 Lifecycle / Process-Death Recovery Coordinator v1.1.
 *
 * Pure policy/audit layer. It never kills/restarts Android components and never
 * dispatches tools. VoiceService owns lifecycle/process operations and execution.
 *
 * R10.12 contract retained:
 * - safe production dynamic goals may auto-resume after verified Service recreation;
 * - user stop/cancel, confirmation, in-flight and unresolved-side-effect boundaries fail closed.
 *
 * R10.13 extension:
 * - a full process-death acceptance is valid only when BOTH PID and process-epoch change;
 * - service recreation alone can never satisfy the R10.13 process-death gate;
 * - the pre-death checkpoint must be persisted before process termination;
 * - continuation remains blind-replay forbidden and must originate from durable state.
 */
class AyanaLifecycleRecoveryCoordinator {

    fun evaluateAutomaticResume(
        goal: JSONObject?,
        currentServiceInstanceId: String,
        currentProcessId: Int,
        currentProcessEpochId: String = ""
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

        if (
            runtime.optBoolean("r10_12_user_stop_requested", false) ||
            runtime.optBoolean("r10_13_user_stop_requested", false)
        ) {
            return decision(false, "user_stop_marker")
        }

        val r10_13Acceptance = runtime.optBoolean("r10_13_acceptance", false)
        val r10_12Acceptance =
            !r10_13Acceptance && runtime.optBoolean("r10_12_acceptance", false)

        val originServiceInstanceId =
            if (r10_13Acceptance) {
                runtime.optString("r10_13_origin_service_instance_id").trim()
            } else {
                runtime.optString("r10_12_origin_service_instance_id").trim()
            }

        val originProcessId =
            if (r10_13Acceptance) {
                runtime.optInt("r10_13_origin_process_id", -1)
            } else {
                runtime.optInt("r10_12_origin_process_id", -1)
            }

        val originProcessEpochId =
            if (r10_13Acceptance) {
                runtime.optString("r10_13_origin_process_epoch_id").trim()
            } else {
                runtime.optString("r10_12_origin_process_epoch_id").trim()
            }

        val serviceRecreated =
            originServiceInstanceId.isNotBlank() &&
                currentServiceInstanceId.isNotBlank() &&
                originServiceInstanceId != currentServiceInstanceId

        val processRecreated =
            originProcessId > 0 &&
                currentProcessId > 0 &&
                originProcessId != currentProcessId

        val processEpochRecreated =
            originProcessEpochId.isNotBlank() &&
                currentProcessEpochId.isNotBlank() &&
                originProcessEpochId != currentProcessEpochId

        if (r10_13Acceptance && (!processRecreated || !processEpochRecreated)) {
            return decision(false, "process_identity_not_recreated")
                .put("status", status)
                .put("service_instance_recreated", serviceRecreated)
                .put("process_recreated", processRecreated)
                .put("process_epoch_recreated", processEpochRecreated)
                .put("current_service_instance_id", currentServiceInstanceId)
                .put("origin_service_instance_id", originServiceInstanceId)
                .put("current_process_id", currentProcessId)
                .put("origin_process_id", originProcessId)
                .put("current_process_epoch_id", currentProcessEpochId)
                .put("origin_process_epoch_id", originProcessEpochId)
                .put("r10_13_acceptance", true)
        }

        if (r10_12Acceptance && !serviceRecreated) {
            return decision(false, "service_instance_not_recreated")
                .put("status", status)
                .put("service_instance_recreated", false)
                .put("process_recreated", processRecreated)
                .put("process_epoch_recreated", processEpochRecreated)
                .put("r10_12_acceptance", true)
        }

        return decision(true, "lifecycle_auto_resume_contract_verified")
            .put("status", status)
            .put("service_instance_recreated", serviceRecreated)
            .put("process_recreated", processRecreated)
            .put("process_epoch_recreated", processEpochRecreated)
            .put("current_service_instance_id", currentServiceInstanceId)
            .put("origin_service_instance_id", originServiceInstanceId)
            .put("current_process_id", currentProcessId)
            .put("origin_process_id", originProcessId)
            .put("current_process_epoch_id", currentProcessEpochId)
            .put("origin_process_epoch_id", originProcessEpochId)
            .put("r10_12_acceptance", r10_12Acceptance)
            .put("r10_13_acceptance", r10_13Acceptance)
            .put("blind_replay_allowed", false)
    }

    fun verifyRestoredLifecycle(
        goal: JSONObject?,
        currentServiceInstanceId: String,
        currentProcessId: Int,
        currentProcessEpochId: String = ""
    ): JSONObject {
        val decision =
            evaluateAutomaticResume(
                goal = goal,
                currentServiceInstanceId = currentServiceInstanceId,
                currentProcessId = currentProcessId,
                currentProcessEpochId = currentProcessEpochId
            )

        if (!decision.optBoolean("allowed", false)) {
            return JSONObject(decision.toString())
                .put("verified", false)
        }

        val runtime =
            goal
                ?.optJSONObject("dynamic_planner_runtime_context")
                ?: JSONObject()

        val r10_13Acceptance = runtime.optBoolean("r10_13_acceptance", false)
        val requested =
            if (r10_13Acceptance) {
                runtime.optBoolean("r10_13_process_death_requested", false)
            } else {
                runtime.optBoolean("r10_12_service_recreation_requested", false)
            }
        val checkpointPersisted =
            if (r10_13Acceptance) {
                runtime.optBoolean("r10_13_pre_death_checkpoint_persisted", false)
            } else {
                runtime.optBoolean("r10_12_pre_recreation_checkpoint_persisted", false)
            }
        val preVerified =
            if (r10_13Acceptance) {
                runtime.optInt("r10_13_pre_death_verified_prefix", -1)
            } else {
                runtime.optInt("r10_12_pre_recreation_verified_prefix", -1)
            }

        val identityVerified =
            if (r10_13Acceptance) {
                decision.optBoolean("process_recreated", false) &&
                    decision.optBoolean("process_epoch_recreated", false)
            } else {
                decision.optBoolean("service_instance_recreated", false)
            }

        val verified =
            requested &&
                checkpointPersisted &&
                preVerified >= 0 &&
                identityVerified

        return JSONObject(decision.toString())
            .put("verified", verified)
            .put(
                "reason",
                if (verified) {
                    if (r10_13Acceptance) {
                        "full_process_death_recovery_verified"
                    } else {
                        "service_lifecycle_recreation_verified"
                    }
                } else {
                    "lifecycle_restore_evidence_incomplete"
                }
            )
            .put("pre_recreation_verified_prefix", preVerified)
            .put("disk_only_restore_required", r10_13Acceptance)
            .put("blind_replay_allowed", false)
    }

    fun selfTest(): Boolean {
        return try {
            val baseGoal =
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

            val r10_12Runtime =
                JSONObject()
                    .put("r10_12_acceptance", true)
                    .put("r10_12_service_recreation_requested", true)
                    .put("r10_12_pre_recreation_checkpoint_persisted", true)
                    .put("r10_12_pre_recreation_verified_prefix", 4)
                    .put("r10_12_origin_service_instance_id", "service-A")
                    .put("r10_12_origin_process_id", 100)
                    .put("r10_12_origin_process_epoch_id", "process-A")

            val r10_12Goal =
                JSONObject(baseGoal.toString())
                    .put("dynamic_planner_runtime_context", r10_12Runtime)

            val serviceOnly =
                verifyRestoredLifecycle(
                    goal = r10_12Goal,
                    currentServiceInstanceId = "service-B",
                    currentProcessId = 100,
                    currentProcessEpochId = "process-A"
                )

            val r10_13Runtime =
                JSONObject()
                    .put("r10_13_acceptance", true)
                    .put("r10_13_process_death_requested", true)
                    .put("r10_13_pre_death_checkpoint_persisted", true)
                    .put("r10_13_pre_death_verified_prefix", 4)
                    .put("r10_13_origin_service_instance_id", "service-A")
                    .put("r10_13_origin_process_id", 100)
                    .put("r10_13_origin_process_epoch_id", "process-A")

            val r10_13Goal =
                JSONObject(baseGoal.toString())
                    .put("dynamic_planner_runtime_context", r10_13Runtime)

            val fullProcess =
                verifyRestoredLifecycle(
                    goal = r10_13Goal,
                    currentServiceInstanceId = "service-B",
                    currentProcessId = 200,
                    currentProcessEpochId = "process-B"
                )

            val pidOnly =
                verifyRestoredLifecycle(
                    goal = r10_13Goal,
                    currentServiceInstanceId = "service-B",
                    currentProcessId = 200,
                    currentProcessEpochId = "process-A"
                )

            val epochOnly =
                verifyRestoredLifecycle(
                    goal = r10_13Goal,
                    currentServiceInstanceId = "service-B",
                    currentProcessId = 100,
                    currentProcessEpochId = "process-B"
                )

            val userStop =
                evaluateAutomaticResume(
                    goal = JSONObject(r10_13Goal.toString()).also {
                        it.optJSONObject("dynamic_planner_runtime_context")
                            ?.put("r10_13_user_stop_requested", true)
                    },
                    currentServiceInstanceId = "service-B",
                    currentProcessId = 200,
                    currentProcessEpochId = "process-B"
                )

            serviceOnly.optBoolean("verified", false) &&
                !serviceOnly.optBoolean("process_recreated", true) &&
                fullProcess.optBoolean("verified", false) &&
                fullProcess.optBoolean("process_recreated", false) &&
                fullProcess.optBoolean("process_epoch_recreated", false) &&
                !pidOnly.optBoolean("verified", true) &&
                !epochOnly.optBoolean("verified", true) &&
                !userStop.optBoolean("allowed", true)
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
        const val VERSION = "1.1"
    }
}
