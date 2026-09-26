package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * AYANA R9.5 Verified Result Transfer v1.0.
 *
 * Pure provenance/evidence layer for transferring a verified result from one
 * application step into a later application step. It never performs Android
 * actions and never upgrades unverified screen content into factual evidence.
 *
 * Contract:
 * - source app action must already be success=true + verified=true;
 * - a source action reporting action_committed=true is never transferable;
 * - screen-derived values require exact source-package ownership and readable content;
 * - screen-marker capture requires the marker to exist inside one interaction context
 *   (or the legacy top-level context when structured windows are unavailable);
 * - action-field capture is limited to an explicit allow-list;
 * - consumer payloads are rendered only from verified transfer records;
 * - transfer records carry source provenance and a deterministic evidence fingerprint;
 * - no mutation authority, replay authority or Agent-Core truth inference exists here.
 */
class AyanaVerifiedResultTransfer {

    enum class CaptureKind {
        SCREEN_MARKER,
        SCREEN_TITLE,
        ACTION_FIELD
    }

    data class CaptureSpec(
        val transferKey: String,
        val kind: CaptureKind,
        val marker: String = "",
        val actionField: String = "",
        val maxChars: Int = MAX_VALUE_CHARS
    )

    data class BindingSpec(
        val transferKey: String,
        val template: String,
        val placeholder: String = DEFAULT_PLACEHOLDER,
        val maxPayloadChars: Int = MAX_BOUND_PAYLOAD_CHARS
    )

    fun capture(
        spec: CaptureSpec,
        sourceStepKey: String,
        appKey: String,
        actionKey: String,
        actionResult: JSONObject,
        observation: JSONObject = JSONObject()
    ): JSONObject {
        val key = normalizeKey(spec.transferKey)
        if (!isValidTransferKey(key)) {
            return captureFailure(
                key = key,
                reason = "invalid_transfer_key"
            )
        }

        val actionVerified =
            actionResult.optBoolean("success", false) &&
                actionResult.optBoolean("verified", false) &&
                actionResult.optString("terminal_status")
                    .equals("SUCCESS", ignoreCase = true)

        if (!actionVerified) {
            return captureFailure(
                key = key,
                reason = "source_action_not_verified"
            )
        }

        val actionCommitted =
            actionResult.optBoolean("action_committed", false)

        if (actionCommitted) {
            return captureFailure(
                key = key,
                reason = "source_action_committed"
            )
        }

        val targetPackage =
            actionResult
                .optString("target_package")
                .trim()

        val observedActionPackage =
            actionResult
                .optString("observed_package")
                .trim()

        if (
            targetPackage.isNotBlank() &&
            observedActionPackage.isNotBlank() &&
            targetPackage != observedActionPackage
        ) {
            return captureFailure(
                key = key,
                reason = "source_action_package_mismatch"
            )
        }

        val expectedPackage =
            observedActionPackage
                .ifBlank {
                    targetPackage
                }

        if (expectedPackage.isBlank()) {
            return captureFailure(
                key = key,
                reason = "source_package_missing"
            )
        }

        val maxChars =
            spec.maxChars
                .coerceIn(1, MAX_VALUE_CHARS)

        val capturedAt =
            System.currentTimeMillis()

        val sourceKind =
            spec.kind.name

        var value = ""
        var sourceContentState = ""
        var sourceContextMode = ""
        var sourcePackageMatch = true
        var markerVerified = false
        var fieldVerified = false

        when (spec.kind) {
            CaptureKind.ACTION_FIELD -> {
                val field = spec.actionField.trim()
                if (field !in ALLOWED_ACTION_FIELDS) {
                    return captureFailure(
                        key = key,
                        reason = "action_field_not_allowed"
                    )
                }

                value =
                    cleanValue(
                        actionResult.optString(field),
                        maxChars
                    )

                fieldVerified = value.isNotBlank()
                if (!fieldVerified) {
                    return captureFailure(
                        key = key,
                        reason = "action_field_empty"
                    )
                }
            }

            CaptureKind.SCREEN_MARKER,
            CaptureKind.SCREEN_TITLE -> {
                if (!observation.optBoolean("success", false)) {
                    return captureFailure(
                        key = key,
                        reason = "screen_observation_failed"
                    )
                }

                if (!observation.optBoolean("verified", false)) {
                    return captureFailure(
                        key = key,
                        reason = "screen_observation_not_verified"
                    )
                }

                val observedPackage =
                    effectivePackage(observation)

                sourcePackageMatch =
                    observedPackage.isNotBlank() &&
                        observedPackage == expectedPackage

                if (!sourcePackageMatch) {
                    return captureFailure(
                        key = key,
                        reason = "screen_package_mismatch"
                    )
                }

                sourceContentState =
                    contentState(observation)

                if (sourceContentState != "readable") {
                    return captureFailure(
                        key = key,
                        reason = "screen_not_readable"
                    )
                }

                sourceContextMode =
                    observation
                        .optString("window_context_mode")
                        .trim()

                when (spec.kind) {
                    CaptureKind.SCREEN_MARKER -> {
                        val marker =
                            cleanValue(
                                spec.marker,
                                maxChars
                            )

                        if (marker.isBlank()) {
                            return captureFailure(
                                key = key,
                                reason = "marker_missing"
                            )
                        }

                        markerVerified =
                            interactionContexts(observation)
                                .any { context ->
                                    normalizeText(context)
                                        .contains(
                                            normalizeText(marker)
                                        )
                                }

                        if (!markerVerified) {
                            return captureFailure(
                                key = key,
                                reason = "marker_not_observed"
                            )
                        }

                        // Preserve the explicit marker, not an arbitrary surrounding block.
                        value = marker
                    }

                    CaptureKind.SCREEN_TITLE -> {
                        value =
                            cleanValue(
                                primaryTitle(observation),
                                maxChars
                            )

                        if (value.isBlank()) {
                            return captureFailure(
                                key = key,
                                reason = "screen_title_missing"
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }

        if (value.isBlank()) {
            return captureFailure(
                key = key,
                reason = "captured_value_empty"
            )
        }

        val boundedSourceStepKey =
            sourceStepKey.take(120)

        val boundedAppKey =
            appKey.take(80)

        val boundedActionKey =
            actionKey.take(80)

        val fingerprint =
            evidenceFingerprint(
                key = key,
                sourceStepKey = boundedSourceStepKey,
                appKey = boundedAppKey,
                actionKey = boundedActionKey,
                expectedPackage = expectedPackage,
                sourceKind = sourceKind,
                value = value
            )

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("transfer_key", key)
            .put("value", value)
            .put("value_chars", value.length)
            .put("capture_kind", sourceKind)
            .put("captured_at_ms", capturedAt)
            .put("source_step_key", boundedSourceStepKey)
            .put("source_app_key", boundedAppKey)
            .put("source_action_key", boundedActionKey)
            .put("source_expected_package", expectedPackage)
            .put("source_action_verified", true)
            .put("source_action_committed", false)
            .put("source_package_match", sourcePackageMatch)
            .put("source_content_state", sourceContentState)
            .put("source_context_mode", sourceContextMode)
            .put("marker_verified", markerVerified)
            .put("action_field_verified", fieldVerified)
            .put("evidence_fingerprint", fingerprint)
            .put("persistent_mutation_authority", false)
            .put("blind_replay_authority", false)
    }

    fun store(
        ledger: JSONObject,
        record: JSONObject
    ): Boolean {
        if (!isVerifiedRecord(record)) return false

        val key =
            normalizeKey(
                record.optString("transfer_key")
            )

        if (!isValidTransferKey(key)) return false

        ledger.put(
            key,
            JSONObject(record.toString())
        )

        return true
    }

    fun bind(
        spec: BindingSpec,
        ledger: JSONObject
    ): JSONObject {
        val key = normalizeKey(spec.transferKey)
        val record = ledger.optJSONObject(key)
            ?: return bindFailure(
                key = key,
                reason = "transfer_record_missing"
            )

        if (!isVerifiedRecord(record)) {
            return bindFailure(
                key = key,
                reason = "transfer_record_not_verified"
            )
        }

        val placeholder =
            spec.placeholder
                .trim()
                .ifBlank { DEFAULT_PLACEHOLDER }

        val template =
            spec.template

        val occurrenceCount =
            countOccurrences(
                template,
                placeholder
            )

        if (occurrenceCount != 1) {
            return bindFailure(
                key = key,
                reason = "template_placeholder_count_$occurrenceCount"
            )
        }

        val value =
            cleanValue(
                record.optString("value"),
                MAX_VALUE_CHARS
            )

        if (value.isBlank()) {
            return bindFailure(
                key = key,
                reason = "transfer_value_empty"
            )
        }

        val maxPayloadChars =
            spec.maxPayloadChars
                .coerceIn(1, MAX_BOUND_PAYLOAD_CHARS)

        val rendered =
            template
                .replace(
                    placeholder,
                    value
                )
                .trim()

        if (
            rendered.isBlank() ||
            rendered.length > maxPayloadChars
        ) {
            return bindFailure(
                key = key,
                reason = "bound_payload_invalid"
            )
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("transfer_key", key)
            .put("payload", rendered)
            .put("value", value)
            .put(
                "source_evidence_fingerprint",
                record.optString("evidence_fingerprint")
            )
            .put(
                "source_step_key",
                record.optString("source_step_key")
            )
            .put("source_record_verified", true)
            .put("persistent_mutation_authority", false)
    }

    fun isVerifiedRecord(
        record: JSONObject
    ): Boolean {
        if (!record.optBoolean("success", false)) return false
        if (!record.optBoolean("verified", false)) return false
        if (!record.optBoolean("source_action_verified", false)) return false
        if (record.optBoolean("source_action_committed", true)) return false
        if (!record.optBoolean("source_package_match", false)) return false
        val value =
            record.optString("value")
                .trim()

        if (value.isBlank()) return false

        val fingerprint =
            record.optString("evidence_fingerprint")
                .trim()

        if (fingerprint.isBlank()) return false

        val expectedFingerprint =
            evidenceFingerprint(
                key =
                    normalizeKey(
                        record.optString("transfer_key")
                    ),
                sourceStepKey =
                    record.optString("source_step_key"),
                appKey =
                    record.optString("source_app_key"),
                actionKey =
                    record.optString("source_action_key"),
                expectedPackage =
                    record.optString("source_expected_package"),
                sourceKind =
                    record.optString("capture_kind"),
                value = value
            )

        if (fingerprint != expectedFingerprint) return false

        val kind =
            record.optString("capture_kind")

        if (
            kind == CaptureKind.SCREEN_MARKER.name ||
            kind == CaptureKind.SCREEN_TITLE.name
        ) {
            if (record.optString("source_content_state") != "readable") return false
        }

        if (
            kind == CaptureKind.SCREEN_MARKER.name &&
            !record.optBoolean("marker_verified", false)
        ) {
            return false
        }

        if (
            kind == CaptureKind.ACTION_FIELD.name &&
            !record.optBoolean("action_field_verified", false)
        ) {
            return false
        }

        return true
    }

    fun selfTest(): Boolean {
        val action =
            JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("terminal_status", "SUCCESS")
                .put("action_committed", false)
                .put("target_package", "com.sec.android.app.sbrowser")
                .put("observed_package", "com.sec.android.app.sbrowser")
                .put("requested_url", "https://example.com/")

        val screen =
            JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("effective_foreground_package", "com.sec.android.app.sbrowser")
                .put("package", "com.sec.android.app.sbrowser")
                .put("primary_content_state", "readable")
                .put("window_context_mode", "external_accessibility")
                .put("primary_window_title", "Example Domain")
                .put(
                    "visible_text",
                    JSONArray()
                        .put("Example Domain")
                        .put("This domain is for use in illustrative examples")
                )

        val markerRecord =
            capture(
                spec =
                    CaptureSpec(
                        transferKey = "page_marker",
                        kind = CaptureKind.SCREEN_MARKER,
                        marker = "Example Domain"
                    ),
                sourceStepKey = "browser-open",
                appKey = "browser",
                actionKey = "open_url",
                actionResult = action,
                observation = screen
            )

        if (!isVerifiedRecord(markerRecord)) return false
        if (markerRecord.optString("value") != "Example Domain") return false

        val ledger = JSONObject()
        if (!store(ledger, markerRecord)) return false

        val binding =
            bind(
                BindingSpec(
                    transferKey = "page_marker",
                    template = "AYANA R9.5 — {{value}} — не сохранять"
                ),
                ledger
            )

        if (!binding.optBoolean("verified", false)) return false
        if (!binding.optString("payload").contains("Example Domain")) return false

        val wrongPackage =
            capture(
                CaptureSpec(
                    transferKey = "bad_pkg",
                    kind = CaptureKind.SCREEN_MARKER,
                    marker = "Example Domain"
                ),
                "browser-open",
                "browser",
                "open_url",
                action,
                JSONObject(screen.toString())
                    .put("effective_foreground_package", "com.google.android.youtube")
                    .put("package", "com.google.android.youtube")
            )

        if (wrongPackage.optBoolean("success", true)) return false

        val structureOnly =
            capture(
                CaptureSpec(
                    transferKey = "not_readable",
                    kind = CaptureKind.SCREEN_MARKER,
                    marker = "Example Domain"
                ),
                "browser-open",
                "browser",
                "open_url",
                action,
                JSONObject(screen.toString())
                    .put("primary_content_state", "structure_only")
            )

        if (structureOnly.optBoolean("success", true)) return false

        val committedAction =
            JSONObject(action.toString())
                .put("action_committed", true)

        val committedRecord =
            capture(
                CaptureSpec(
                    transferKey = "committed",
                    kind = CaptureKind.ACTION_FIELD,
                    actionField = "requested_url"
                ),
                "browser-open",
                "browser",
                "open_url",
                committedAction
            )

        if (committedRecord.optBoolean("success", true)) return false

        val fieldRecord =
            capture(
                CaptureSpec(
                    transferKey = "verified_url",
                    kind = CaptureKind.ACTION_FIELD,
                    actionField = "requested_url"
                ),
                "browser-open",
                "browser",
                "open_url",
                action
            )

        if (!isVerifiedRecord(fieldRecord)) return false

        val forgedLedger =
            JSONObject()
                .put(
                    "forged",
                    JSONObject(markerRecord.toString())
                        .put("verified", false)
                )

        val forgedBinding =
            bind(
                BindingSpec(
                    transferKey = "forged",
                    template = "{{value}}"
                ),
                forgedLedger
            )

        if (forgedBinding.optBoolean("success", true)) return false

        val tamperedLedger =
            JSONObject()
                .put(
                    "page_marker",
                    JSONObject(markerRecord.toString())
                        .put("value", "Tampered")
                )

        val tamperedBinding =
            bind(
                BindingSpec(
                    transferKey = "page_marker",
                    template = "{{value}}"
                ),
                tamperedLedger
            )

        if (tamperedBinding.optBoolean("success", true)) return false

        return true
    }

    private fun interactionContexts(
        observation: JSONObject
    ): List<String> {
        val windows =
            observation.optJSONArray("windows")

        if (windows != null) {
            var hasInteractionContext = false
            for (index in 0 until windows.length()) {
                val window = windows.optJSONObject(index) ?: continue
                if (window.optBoolean("interaction_context", false)) {
                    hasInteractionContext = true
                    break
                }
            }

            val structured =
                observation.optString("window_context_mode").isNotBlank()

            if (structured && windows.length() > 0 && !hasInteractionContext) {
                // Do not combine sibling windows when the structured snapshot could
                // not identify an interaction context. Fall through to the top-level
                // primary context, which Screen Intelligence already selected.
            } else {
                val result = mutableListOf<String>()
                for (index in 0 until windows.length()) {
                    val window = windows.optJSONObject(index) ?: continue
                    if (
                        hasInteractionContext &&
                        !window.optBoolean("interaction_context", false)
                    ) {
                        continue
                    }

                    if (window.optString("type_name") == "input_method") continue

                    val text =
                        buildString {
                            append(window.optString("title"))
                            append(' ')
                            append(window.optString("verification_text"))

                            val visible = window.optJSONArray("visible_text")
                            if (visible != null) {
                                for (itemIndex in 0 until visible.length()) {
                                    append(' ')
                                    append(visible.optString(itemIndex))
                                }
                            }
                        }
                            .trim()

                    if (text.isNotBlank()) result += text
                }

                if (result.isNotEmpty()) return result
            }
        }

        val fallback =
            buildString {
                append(observation.optString("primary_window_title"))
                append(' ')
                append(observation.optString("verification_text"))

                val visible = observation.optJSONArray("visible_text")
                if (visible != null) {
                    for (index in 0 until visible.length()) {
                        append(' ')
                        append(visible.optString(index))
                    }
                }
            }
                .trim()

        return if (fallback.isBlank()) emptyList() else listOf(fallback)
    }

    private fun primaryTitle(
        observation: JSONObject
    ): String {
        val top =
            observation
                .optString("primary_window_title")
                .trim()

        if (top.isNotBlank()) return top

        val windows = observation.optJSONArray("windows") ?: return ""
        for (index in 0 until windows.length()) {
            val window = windows.optJSONObject(index) ?: continue
            if (
                window.optBoolean("interaction_context", false) &&
                window.optString("type_name") != "input_method"
            ) {
                val title = window.optString("title").trim()
                if (title.isNotBlank()) return title
            }
        }

        return ""
    }

    private fun effectivePackage(
        observation: JSONObject
    ): String =
        observation
            .optString("effective_foreground_package")
            .trim()
            .ifBlank {
                observation
                    .optString("package")
                    .trim()
            }
            .ifBlank {
                observation
                    .optString("interaction_package")
                    .trim()
            }

    private fun contentState(
        observation: JSONObject
    ): String =
        observation
            .optString("primary_content_state")
            .trim()
            .lowercase(Locale.ROOT)
            .ifBlank {
                observation
                    .optString("content_state")
                    .trim()
                    .lowercase(Locale.ROOT)
            }
            .ifBlank {
                observation
                    .optString("content_status")
                    .trim()
                    .lowercase(Locale.ROOT)
            }
            .ifBlank { "unknown" }

    private fun evidenceFingerprint(
        key: String,
        sourceStepKey: String,
        appKey: String,
        actionKey: String,
        expectedPackage: String,
        sourceKind: String,
        value: String
    ): String {
        val canonical =
            listOf(
                VERSION,
                key,
                sourceStepKey,
                appKey,
                actionKey,
                expectedPackage,
                sourceKind,
                value
            )
                .joinToString("\u001F")

        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            .take(24)
    }

    private fun captureFailure(
        key: String,
        reason: String
    ): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("transfer_key", key)
            .put("reason", reason)
            .put("persistent_mutation_authority", false)

    private fun bindFailure(
        key: String,
        reason: String
    ): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("transfer_key", key)
            .put("reason", reason)
            .put("persistent_mutation_authority", false)

    private fun countOccurrences(
        value: String,
        needle: String
    ): Int {
        if (needle.isBlank()) return 0
        var count = 0
        var index = 0

        while (true) {
            val found = value.indexOf(needle, index)
            if (found < 0) break
            count++
            index = found + needle.length
        }

        return count
    }

    private fun cleanValue(
        value: String,
        maxChars: Int
    ): String =
        value
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars.coerceAtLeast(1))

    private fun normalizeText(
        value: String
    ): String =
        value
            .trim()
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")

    private fun normalizeKey(
        value: String
    ): String =
        value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_.-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(MAX_KEY_CHARS)

    private fun isValidTransferKey(
        key: String
    ): Boolean =
        key.isNotBlank() &&
            key.length <= MAX_KEY_CHARS &&
            Regex("^[a-z0-9][a-z0-9_.-]*$").matches(key)

    companion object {
        const val VERSION = "1.0"
        const val DEFAULT_PLACEHOLDER = "{{value}}"
        const val MAX_VALUE_CHARS = 180
        const val MAX_BOUND_PAYLOAD_CHARS = 240
        const val MAX_KEY_CHARS = 64

        val ALLOWED_ACTION_FIELDS =
            setOf(
                "requested_url",
                "requested_query",
                "requested_title",
                "observed_package",
                "target_package"
            )
    }
}
