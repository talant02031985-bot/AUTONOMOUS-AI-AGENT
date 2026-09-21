package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * AYANA R9.0 Development Transaction Contract v1.0.
 *
 * Pure capability gate for future self-development workflows.
 * No repository/build action is executed here. The contract prevents AYANA from claiming
 * a build/test/rollback transaction is available unless every required executor is both
 * authorized and device/runtime-confirmed.
 */
class AyanaDevelopmentTransactionContract {

    data class Availability(
        val repositoryAuthorized: Boolean,
        val writeExecutorAvailable: Boolean,
        val commitPushExecutorAvailable: Boolean,
        val buildExecutorAvailable: Boolean,
        val artifactVerifierAvailable: Boolean,
        val rollbackExecutorAvailable: Boolean
    )

    fun evaluate(
        availability: Availability
    ): JSONObject {
        val missing = JSONArray()

        fun require(ok: Boolean, id: String) {
            if (!ok) missing.put(id)
        }

        require(availability.repositoryAuthorized, "repository_authorization")
        require(availability.writeExecutorAvailable, "repository_write_executor")
        require(availability.commitPushExecutorAvailable, "commit_push_executor")
        require(availability.buildExecutorAvailable, "android_build_executor")
        require(availability.artifactVerifierAvailable, "apk_artifact_verifier")
        require(availability.rollbackExecutorAvailable, "rollback_executor")

        val ready = missing.length() == 0

        return JSONObject()
            .put("version", VERSION)
            .put("ready", ready)
            .put("terminal_status", if (ready) "READY" else "UNAVAILABLE")
            .put("missing", missing)
            .put(
                "transaction_order",
                JSONArray()
                    .put("preflight")
                    .put("snapshot")
                    .put("edit")
                    .put("static_checks")
                    .put("commit")
                    .put("push")
                    .put("ci_build")
                    .put("artifact_verify")
                    .put("device_acceptance")
                    .put("accept_or_rollback")
            )
            .put("false_success_allowed", false)
            .put("rollback_required_on_failed_acceptance", true)
    }

    fun selfTest(): Boolean {
        val closed =
            evaluate(
                Availability(
                    repositoryAuthorized = false,
                    writeExecutorAvailable = false,
                    commitPushExecutorAvailable = false,
                    buildExecutorAvailable = false,
                    artifactVerifierAvailable = false,
                    rollbackExecutorAvailable = false
                )
            )

        val ready =
            evaluate(
                Availability(
                    repositoryAuthorized = true,
                    writeExecutorAvailable = true,
                    commitPushExecutorAvailable = true,
                    buildExecutorAvailable = true,
                    artifactVerifierAvailable = true,
                    rollbackExecutorAvailable = true
                )
            )

        return !closed.optBoolean("ready") &&
            closed.optJSONArray("missing")?.length() == 6 &&
            ready.optBoolean("ready") &&
            !ready.optBoolean("false_success_allowed", true)
    }

    companion object {
        const val VERSION = "1.0"
    }
}
