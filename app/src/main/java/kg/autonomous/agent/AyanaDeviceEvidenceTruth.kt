package kg.autonomous.agent

/**
 * AYANA Device Evidence Truth v1.0 — SHARED DEVICE EVIDENCE TRUTH.
 *
 * Single source for effective device-confirmation decisions used by both
 * AyanaVoiceService self-review and AyanaAutonomousTestIntelligence.
 *
 * Rules:
 * - live availability is NOT fabricated from historical proof;
 * - implementation/availability remain owned by Capability Registry;
 * - persisted/static Registry proof is preferred when present;
 * - a fresh baseline runtime probe can confirm only the current diagnostic run;
 * - accepted historical device/runtime evidence can close a proof gap without
 *   rewriting Registry metadata;
 * - unimplemented capabilities never become implemented through this ledger.
 */
object AyanaDeviceEvidenceTruth {

    const val VERSION = "1.0"

    data class Resolution(
        val effectiveConfirmed: Boolean,
        val sourceCode: String,
        val sourceDetail: String,
        val historicalAccepted: Boolean,
        val runtimeProbeConfirmed: Boolean,
        val truthState: String
    )

    private val acceptedEvidence =
        linkedMapOf(
            "bounded_voice_follow_up" to
                "device acceptance: wake-only follow-up window verified",
            "relative_media_volume_delta" to
                "device acceptance: relative media volume delta verified",
            "exact_screen_brightness_set" to
                "device acceptance: 20/80/40% brightness round-trip verified",
            "app_task_removal" to
                "device acceptance: Recents task removal verified",
            "multimodal_stop_during_analysis" to
                "device acceptance: multimodal STOP reached CANCELLED terminal",
            "settings_intent_attestation" to
                "device acceptance: Samsung Settings target attestation verified",
            "app_detail_permissions_navigation" to
                "device acceptance: YouTube App Info -> Permissions verified",
            "local_acceptance_test_engine" to
                "device acceptance: local acceptance suite + report publication verified",
            "capability_truth_grounding" to
                "device acceptance: Registry/runtime capability truth verified",
            "agent_core_latency_classification" to
                "device acceptance: measured Agent Core phase telemetry verified",
            "perception_owner_fusion" to
                "device acceptance: overlay/foreground owner fusion verified",
            "whole_goal_routing_guard" to
                "device acceptance: whole-goal routing contract verified",
            "artifact_whole_goal_orchestration" to
                "device acceptance: artifact whole-goal + verified publication",
            "goal_compiler_execution_contract" to
                "runtime acceptance: Goal Compiler execution contract PASS",
            "unified_execution_session" to
                "runtime acceptance: unified Execution Session PASS",
            "autonomous_execution_loop" to
                "runtime acceptance: autonomous execution loop PASS",
            "agent_core_timeout_recovery" to
                "device acceptance: ONLINE-002 long/deep recovery PASS"
        )

    fun acceptedEvidenceDetail(
        capabilityId: String
    ): String? =
        acceptedEvidence[
            capabilityId.trim()
        ]

    fun acceptedEvidenceIds(): Set<String> =
        acceptedEvidence.keys.toSet()

    fun resolve(
        capabilityId: String,
        implemented: Boolean,
        availableNow: Boolean,
        registryDeviceConfirmed: Boolean,
        staticDeviceConfirmed: Boolean,
        runtimeEvidencePersisted: Boolean,
        runtimeEvidenceDetail: String,
        runtimeProbeConfirmed: Boolean = false
    ): Resolution {

        val id =
            capabilityId.trim()

        val historicalDetail =
            acceptedEvidenceDetail(id)

        val historicalAccepted =
            historicalDetail != null

        val effectiveConfirmed =
            implemented &&
                (
                    registryDeviceConfirmed ||
                        staticDeviceConfirmed ||
                        runtimeEvidencePersisted ||
                        runtimeProbeConfirmed ||
                        historicalAccepted
                    )

        val sourceCode: String
        val sourceDetail: String

        when {
            !implemented -> {
                sourceCode = "neg"
                sourceDetail = "Capability Registry: implemented=false"
            }

            runtimeEvidencePersisted -> {
                sourceCode = "run"
                sourceDetail =
                    runtimeEvidenceDetail
                        .trim()
                        .ifBlank {
                            "persisted runtime evidence"
                        }
            }

            staticDeviceConfirmed -> {
                sourceCode = "reg"
                sourceDetail = "static Capability Registry device proof"
            }

            runtimeProbeConfirmed -> {
                sourceCode = "probe"
                sourceDetail = "fresh baseline runtime probe PASS"
            }

            historicalAccepted -> {
                sourceCode = "hist"
                sourceDetail = historicalDetail.orEmpty()
            }

            registryDeviceConfirmed -> {
                sourceCode = "reg"
                sourceDetail = "effective Capability Registry device proof"
            }

            !availableNow -> {
                sourceCode = "off"
                sourceDetail = "implemented but unavailable now"
            }

            else -> {
                sourceCode = "live"
                sourceDetail = "available now; no device-confirmation evidence"
            }
        }

        val truthState =
            when {
                !implemented ->
                    "UNIMPLEMENTED"

                !availableNow && effectiveConfirmed ->
                    "DEVICE_CONFIRMED_UNAVAILABLE_NOW"

                !availableNow ->
                    "IMPLEMENTED_UNAVAILABLE_NOW"

                effectiveConfirmed ->
                    "DEVICE_CONFIRMED_AVAILABLE"

                else ->
                    "IMPLEMENTED_AVAILABLE_UNCONFIRMED"
            }

        return Resolution(
            effectiveConfirmed = effectiveConfirmed,
            sourceCode = sourceCode,
            sourceDetail = sourceDetail,
            historicalAccepted = historicalAccepted,
            runtimeProbeConfirmed = runtimeProbeConfirmed,
            truthState = truthState
        )
    }
}
