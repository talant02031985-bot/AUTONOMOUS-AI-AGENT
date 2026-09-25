package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * AYANA R9.3.4 App Integration Device Probe v1.0.
 *
 * A bounded, navigation-only device acceptance runner for the five R9.3 integrations.
 * It never saves a calendar event and never exposes persistent mutation authority.
 *
 * Android execution remains owned by AyanaVoiceService through injected callbacks.
 * The probe only owns:
 * - deterministic test plan;
 * - per-step verification requirements;
 * - restore-after-every-step contract;
 * - machine-readable evidence aggregation.
 */
class AyanaAppIntegrationDeviceProbe(
    private val registry: AyanaAppIntegrationRegistry
) {

    data class ProbeStep(
        val key: String,
        val label: String,
        val appKey: String,
        val actionKey: String,
        val payload: String = ""
    )

    fun plan(): List<ProbeStep> =
        listOf(
            ProbeStep(
                key = STEP_FILES_OPEN,
                label = "Мои файлы — verified open",
                appKey = AyanaAppIntegrationRegistry.APP_FILES,
                actionKey = AyanaAppIntegrationRegistry.ACTION_OPEN
            ),
            ProbeStep(
                key = STEP_GALLERY_OPEN,
                label = "Галерея — verified open",
                appKey = AyanaAppIntegrationRegistry.APP_GALLERY,
                actionKey = AyanaAppIntegrationRegistry.ACTION_OPEN
            ),
            ProbeStep(
                key = STEP_YOUTUBE_SEARCH,
                label = "YouTube — verified search handoff",
                appKey = AyanaAppIntegrationRegistry.APP_YOUTUBE,
                actionKey = AyanaAppIntegrationRegistry.ACTION_SEARCH,
                payload = YOUTUBE_PROBE_QUERY
            ),
            ProbeStep(
                key = STEP_BROWSER_URL,
                label = "Браузер — verified URL handoff",
                appKey = AyanaAppIntegrationRegistry.APP_BROWSER,
                actionKey = AyanaAppIntegrationRegistry.ACTION_OPEN_URL,
                payload = BROWSER_PROBE_URL
            ),
            ProbeStep(
                key = STEP_CALENDAR_DRAFT,
                label = "Календарь — verified unsaved draft",
                appKey = AyanaAppIntegrationRegistry.APP_CALENDAR,
                actionKey = AyanaAppIntegrationRegistry.ACTION_CREATE_EVENT_DRAFT,
                payload = CALENDAR_PROBE_TITLE
            )
        )

    fun selfTest(): Boolean {
        val steps = plan()
        if (steps.size != EXPECTED_STEP_COUNT) return false
        if (steps.map { it.key }.toSet().size != EXPECTED_STEP_COUNT) return false

        for (step in steps) {
            val action =
                registry.action(
                    step.appKey,
                    step.actionKey
                ) ?: return false

            if (!action.autonomousAllowed) return false
            if (
                action.commitSemantics ==
                AyanaAppIntegrationRegistry.CommitSemantics.MUTATION
            ) {
                return false
            }
        }

        val calendarAction =
            registry.action(
                AyanaAppIntegrationRegistry.APP_CALENDAR,
                AyanaAppIntegrationRegistry.ACTION_CREATE_EVENT_DRAFT
            ) ?: return false

        if (
            calendarAction.commitSemantics !=
            AyanaAppIntegrationRegistry.CommitSemantics.DRAFT_ONLY
        ) {
            return false
        }

        return steps.last().key == STEP_CALENDAR_DRAFT &&
            steps.first().key == STEP_FILES_OPEN
    }

    /**
     * Executes all five navigation-only device checks.
     *
     * execute(step) must return the real App Integration action evidence.
     * restore(step) must return proof that AYANA is back on the foreground.
     * The probe restores after every dispatched attempt, including failures.
     */
    fun run(
        execute: (ProbeStep) -> JSONObject,
        restore: (ProbeStep) -> JSONObject,
        shouldCancel: () -> Boolean = { false }
    ): JSONObject {
        val startedAt =
            System.currentTimeMillis()

        val results =
            JSONArray()

        var passed = 0
        var failed = 0
        var cancelled = false
        var mutationCommittedDetected = false

        for (step in plan()) {
            if (shouldCancel()) {
                cancelled = true
                break
            }

            val actionSpec =
                registry.action(
                    step.appKey,
                    step.actionKey
                )

            val safeByRegistry =
                actionSpec != null &&
                    actionSpec.autonomousAllowed &&
                    actionSpec.commitSemantics !=
                    AyanaAppIntegrationRegistry.CommitSemantics.MUTATION

            val stepStartedAt =
                System.currentTimeMillis()

            val actionResult =
                if (!safeByRegistry) {
                    JSONObject()
                        .put("success", false)
                        .put("verified", false)
                        .put("terminal_status", "BLOCKED")
                        .put(
                            "message",
                            "Device probe blocked by App Integration registry."
                        )
                } else {
                    try {
                        execute(step)
                    } catch (error: Exception) {
                        JSONObject()
                            .put("success", false)
                            .put("verified", false)
                            .put("terminal_status", "ERROR")
                            .put(
                                "message",
                                error.message ?: error.javaClass.simpleName
                            )
                    }
                }

            val actionVerified =
                safeByRegistry &&
                    actionResult.optBoolean("success", false) &&
                    actionResult.optBoolean("verified", false)

            val actionCommitted =
                actionResult.optBoolean(
                    "action_committed",
                    false
                )

            if (actionCommitted) {
                mutationCommittedDetected = true
            }

            val calendarGuard =
                if (
                    step.key ==
                    STEP_CALENDAR_DRAFT
                ) {
                    actionResult.optBoolean(
                        "draft_only",
                        false
                    ) &&
                        !actionCommitted
                } else {
                    !actionCommitted
                }

            val restoreResult =
                try {
                    restore(step)
                } catch (error: Exception) {
                    JSONObject()
                        .put("success", false)
                        .put("verified", false)
                        .put(
                            "message",
                            error.message ?: error.javaClass.simpleName
                        )
                }

            val restoreVerified =
                restoreResult.optBoolean(
                    "success",
                    false
                ) &&
                    restoreResult.optBoolean(
                        "verified",
                        false
                    )

            val stepOk =
                actionVerified &&
                    calendarGuard &&
                    restoreVerified

            if (stepOk) {
                passed += 1
            } else {
                failed += 1
            }

            results.put(
                JSONObject()
                    .put("key", step.key)
                    .put("label", step.label)
                    .put("app_key", step.appKey)
                    .put("action_key", step.actionKey)
                    .put(
                        "payload_present",
                        step.payload.isNotBlank()
                    )
                    .put("registry_safe", safeByRegistry)
                    .put("action_verified", actionVerified)
                    .put("calendar_no_commit_guard", calendarGuard)
                    .put("restore_verified", restoreVerified)
                    .put("success", stepOk)
                    .put(
                        "duration_ms",
                        (
                            System.currentTimeMillis() -
                                stepStartedAt
                            ).coerceAtLeast(0L)
                    )
                    .put(
                        "action",
                        JSONObject(actionResult.toString())
                    )
                    .put(
                        "restore",
                        JSONObject(restoreResult.toString())
                    )
            )

            if (shouldCancel()) {
                cancelled = true
                break
            }
        }

        val completed =
            passed + failed

        val allVerified =
            !cancelled &&
                completed == EXPECTED_STEP_COUNT &&
                failed == 0 &&
                !mutationCommittedDetected

        return JSONObject()
            .put("version", VERSION)
            .put(
                "terminal_status",
                when {
                    cancelled -> "CANCELLED"
                    allVerified -> "SUCCESS"
                    else -> "ERROR"
                }
            )
            .put("success", allVerified)
            .put("verified", allVerified)
            .put("cancelled", cancelled)
            .put("steps_total", EXPECTED_STEP_COUNT)
            .put("steps_completed", completed)
            .put("passed", passed)
            .put("failed", failed)
            .put(
                "restore_after_every_step",
                true
            )
            .put(
                "persistent_mutation_authority",
                false
            )
            .put(
                "mutation_committed_detected",
                mutationCommittedDetected
            )
            .put(
                "calendar_save_claimed",
                false
            )
            .put(
                "duration_ms",
                (
                    System.currentTimeMillis() -
                        startedAt
                    ).coerceAtLeast(0L)
            )
            .put("results", results)
    }

    companion object {
        const val VERSION =
            "1.0"

        const val EXPECTED_STEP_COUNT =
            5

        const val STEP_FILES_OPEN =
            "files_open"

        const val STEP_GALLERY_OPEN =
            "gallery_open"

        const val STEP_YOUTUBE_SEARCH =
            "youtube_search"

        const val STEP_BROWSER_URL =
            "browser_url"

        const val STEP_CALENDAR_DRAFT =
            "calendar_draft"

        const val YOUTUBE_PROBE_QUERY =
            "AYANA R9.3.4 device probe"

        const val BROWSER_PROBE_URL =
            "https://example.com/"

        const val CALENDAR_PROBE_TITLE =
            "AYANA R9.3.4 device probe — не сохранять"
    }
}
