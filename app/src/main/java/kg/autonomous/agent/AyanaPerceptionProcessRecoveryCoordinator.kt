package kg.autonomous.agent

import android.content.Context
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject

/**
 * AYANA Perception Process Recovery Coordinator v1.0 — R10.16.
 *
 * Purpose:
 * - prove and support recovery when the isolated :perception process dies while the
 *   main AYANA process remains alive;
 * - require a new provider PID/process epoch before recovery can be accepted;
 * - require the restarted Accessibility service + strict bridge route to become ready;
 * - never infer recovery from ContentProvider creation alone;
 * - never replay a mutating Android action as part of recovery.
 *
 * This component grants no execution authority. It only coordinates same-UID process
 * lifecycle truth around the existing AyanaPerceptionBridgeClient contract.
 */
class AyanaPerceptionProcessRecoveryCoordinator(
    context: Context,
    private val perceptionBridge: AyanaPerceptionBridgeClient
) {
    private val appContext = context.applicationContext

    fun forceDeathAndAwaitRecovery(
        timeoutMs: Long = DEFAULT_RECOVERY_TIMEOUT_MS
    ): JSONObject {
        val mainPid = Process.myPid()
        val startedAt = SystemClock.elapsedRealtime()

        val before =
            perceptionBridge.awaitReady(
                timeoutMs = INITIAL_READY_TIMEOUT_MS
            )

        val originPid = before.optInt("perception_process_id", -1)
        val originEpoch = before.optString("perception_process_epoch_id").trim()
        val originProcessName = before.optString("perception_process_name").trim()
        val originGeneration = before.optLong("perception_bridge_generation", 0L)

        val originVerified =
            before.optBoolean("perception_rebind_ready", false) &&
                before.optBoolean("perception_route_verified", false) &&
                before.optBoolean("provider_process_separated", false) &&
                before.optBoolean("bridge_version_match", false) &&
                before.optBoolean("provider_epoch_verified", false) &&
                before.optBoolean("accessibility_connected", false) &&
                before.optBoolean("route_contract_verified", false) &&
                originPid > 0 &&
                originPid != mainPid &&
                originEpoch.isNotBlank() &&
                originProcessName.endsWith(":perception")

        if (!originVerified) {
            return failure(
                reason = "origin_perception_not_ready",
                startedAt = startedAt
            )
                .put("origin", before)
        }

        try {
            Process.killProcess(originPid)
        } catch (error: Throwable) {
            return failure(
                reason = "perception_process_kill_failed",
                startedAt = startedAt,
                error = error.message ?: error.javaClass.simpleName
            )
                .put("origin", before)
        }

        val deadline =
            startedAt + timeoutMs.coerceIn(MIN_RECOVERY_TIMEOUT_MS, MAX_RECOVERY_TIMEOUT_MS)

        var attempts = 0
        var latest = JSONObject()

        do {
            attempts++

            try {
                Thread.sleep(RECOVERY_POLL_MS)
            } catch (_: InterruptedException) {
                break
            }

            latest =
                try {
                    perceptionBridge.status()
                } catch (error: Throwable) {
                    JSONObject()
                        .put("success", false)
                        .put("reason", "perception_status_exception")
                        .put("error", error.message ?: error.javaClass.simpleName)
                }

            val restoredPid = latest.optInt("perception_process_id", -1)
            val restoredEpoch = latest.optString("perception_process_epoch_id").trim()
            val restoredGeneration = latest.optLong("perception_bridge_generation", 0L)

            val identityChanged =
                restoredPid > 0 &&
                    restoredPid != originPid &&
                    restoredEpoch.isNotBlank() &&
                    restoredEpoch != originEpoch

            val generationAdvanced =
                originGeneration <= 0L ||
                    restoredGeneration > originGeneration ||
                    latest.optBoolean("perception_process_restarted", false)

            val ready =
                latest.optBoolean("success", false) &&
                    latest.optBoolean("perception_route_verified", false) &&
                    latest.optBoolean("provider_process_separated", false) &&
                    latest.optBoolean("bridge_version_match", false) &&
                    latest.optBoolean("provider_epoch_verified", false) &&
                    latest.optBoolean("accessibility_connected", false) &&
                    latest.optBoolean("route_contract_verified", false)

            if (identityChanged && generationAdvanced && ready) {
                return JSONObject()
                    .put("success", true)
                    .put("verified", true)
                    .put("perception_recovery_version", VERSION)
                    .put("kill_dispatched", true)
                    .put("main_process_id", mainPid)
                    .put("origin_perception_process_id", originPid)
                    .put("restored_perception_process_id", restoredPid)
                    .put("origin_perception_process_epoch_id", originEpoch)
                    .put("restored_perception_process_epoch_id", restoredEpoch)
                    .put("origin_bridge_generation", originGeneration)
                    .put("restored_bridge_generation", restoredGeneration)
                    .put("process_identity_changed", true)
                    .put("process_epoch_changed", true)
                    .put("bridge_generation_advanced", generationAdvanced)
                    .put("accessibility_reconnected", true)
                    .put("route_contract_verified", true)
                    .put("blind_mutation_retry_allowed", false)
                    .put("attempts", attempts)
                    .put(
                        "recovery_duration_ms",
                        (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                    )
                    .put("origin", before)
                    .put("restored", latest)
            }
        } while (SystemClock.elapsedRealtime() < deadline)

        return failure(
            reason = "perception_process_recovery_timeout",
            startedAt = startedAt
        )
            .put("kill_dispatched", true)
            .put("main_process_id", mainPid)
            .put("origin_perception_process_id", originPid)
            .put("origin_perception_process_epoch_id", originEpoch)
            .put("origin_bridge_generation", originGeneration)
            .put("attempts", attempts)
            .put("last_status", latest)
    }

    private fun failure(
        reason: String,
        startedAt: Long,
        error: String = ""
    ): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("perception_recovery_version", VERSION)
            .put("reason", reason)
            .put("error", error.take(300))
            .put("blind_mutation_retry_allowed", false)
            .put(
                "recovery_duration_ms",
                (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            )

    companion object {
        const val VERSION = "1.0"

        private const val INITIAL_READY_TIMEOUT_MS = 3_000L
        private const val DEFAULT_RECOVERY_TIMEOUT_MS = 12_000L
        private const val MIN_RECOVERY_TIMEOUT_MS = 3_000L
        private const val MAX_RECOVERY_TIMEOUT_MS = 20_000L
        private const val RECOVERY_POLL_MS = 180L
    }
}
