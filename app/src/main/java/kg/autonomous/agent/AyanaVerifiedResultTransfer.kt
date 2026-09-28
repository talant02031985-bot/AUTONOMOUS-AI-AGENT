package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * AYANA R9.8 Verified Result Transfer v2.0 — GENERIC TYPED TRANSFER.
 *
 * Pure provenance/evidence layer for transferring a verified result from one
 * application step into a later application step. It never performs Android
 * actions and never upgrades unverified screen content into factual evidence.
 *
 * Contract:
 * - source app action must already be success=true + verified=true;
 * - a source action reporting action_committed=true is never transferable;
 * - screen-marker values require exact source-package ownership and an observed marker in readable/partial content;
 * - screen-title values still require fully readable content; structure_only/unavailable remain rejected;
 * - screen-marker capture requires the marker to exist inside one interaction context
 *   (or the legacy top-level context when structured windows are unavailable);
 * - action-field capture is limited to an explicit allow-list;
 * - consumer payloads are rendered only from verified transfer records;
 * - transfer records carry source provenance and a deterministic evidence fingerprint;
 * - R9.8 adds typed, app-agnostic producer/consumer contracts for generic transfer edges;
 * - producer contracts are based on action semantics, not hard-coded app chains;
 * - consumer compatibility is checked before payload rendering;
 * - no mutation authority, replay authority or Agent-Core truth inference exists here.
 */
class AyanaVerifiedResultTransfer {

    enum class CaptureKind {
        SCREEN_MARKER,
        SCREEN_TITLE,
        ACTION_FIELD
    }

    enum class ValueType {
        AUTO,
        TEXT,
        URL,
        QUERY,
        TITLE,
        PACKAGE
    }

    data class CaptureSpec(
        val transferKey: String,
        val kind: CaptureKind,
        val marker: String = "",
        val actionField: String = "",
        val maxChars: Int = MAX_VALUE_CHARS,
        val valueType: ValueType = ValueType.AUTO
    )

    data class BindingSpec(
        val transferKey: String,
        val template: String,
        val placeholder: String = DEFAULT_PLACEHOLDER,
        val maxPayloadChars: Int = MAX_BOUND_PAYLOAD_CHARS,
        val consumerAppKey: String = "",
        val consumerActionKey: String = "",
        val acceptedValueTypes: Set<ValueType> = emptySet()
    )

    /**
     * Generic producer contract for the currently registered app-action surface.
     * The contract depends on action semantics only; app order/chain is irrelevant.
     */
    fun captureSpecForAction(
        transferKey: String,
        appKey: String,
        actionKey: String,
        semanticHint: String = ""
    ): CaptureSpec? {
        if (appKey.trim().isBlank() || actionKey.trim().isBlank()) return null

        val hint = semanticHint.trim().lowercase(Locale.ROOT)

        if (hint == SEMANTIC_HINT_SCREEN_TITLE) {
            return CaptureSpec(
                transferKey = transferKey,
                kind = CaptureKind.SCREEN_TITLE,
                valueType = ValueType.TITLE
            )
        }

        return when (actionKey.trim().lowercase(Locale.ROOT)) {
            ACTION_OPEN_URL ->
                CaptureSpec(
                    transferKey = transferKey,
                    kind = CaptureKind.ACTION_FIELD,
                    actionField = "requested_url",
                    valueType = ValueType.URL
                )

            ACTION_SEARCH ->
                CaptureSpec(
                    transferKey = transferKey,
                    kind = CaptureKind.ACTION_FIELD,
                    actionField = "requested_query",
                    valueType = ValueType.QUERY
                )

            ACTION_CREATE_EVENT_DRAFT ->
                CaptureSpec(
                    transferKey = transferKey,
                    kind = CaptureKind.ACTION_FIELD,
                    actionField = "requested_title",
                    valueType = ValueType.TITLE
                )

            ACTION_OPEN ->
                CaptureSpec(
                    transferKey = transferKey,
                    kind = CaptureKind.ACTION_FIELD,
                    actionField = "observed_package",
                    valueType = ValueType.PACKAGE
                )

            else -> null
        }
    }

    /**
     * Generic consumer contract. The template is produced by the orchestrator by
     * replacing an explicit previous-result reference in a normal registry payload.
     */
    fun bindingSpecForAction(
        transferKey: String,
        appKey: String,
        actionKey: String,
        template: String
    ): BindingSpec? {
        if (appKey.trim().isBlank() || actionKey.trim().isBlank()) return null

        val accepted = acceptedValueTypesForAction(actionKey)
        if (accepted.isEmpty()) return null
        if (countOccurrences(template, DEFAULT_PLACEHOLDER) != 1) return null

        return BindingSpec(
            transferKey = transferKey,
            template = template,
            consumerAppKey = appKey.trim().take(80),
            consumerActionKey = actionKey.trim().take(80),
            acceptedValueTypes = accepted
        )
    }

    fun acceptedValueTypesForAction(
        actionKey: String
    ): Set<ValueType> =
        when (actionKey.trim().lowercase(Locale.ROOT)) {
            ACTION_SEARCH,
            ACTION_CREATE_EVENT_DRAFT ->
                TEXTUAL_INPUT_TYPES

            ACTION_OPEN_URL ->
                setOf(ValueType.URL)

            else ->
                emptySet()
        }

    fun areCompatible(
        captureSpec: CaptureSpec,
        bindingSpec: BindingSpec
    ): Boolean {
        val sourceType = resolvedValueType(captureSpec)
        val accepted =
            bindingSpec.acceptedValueTypes
                .ifEmpty {
                    acceptedValueTypesForAction(
                        bindingSpec.consumerActionKey
                    )
                }

        return sourceType != ValueType.AUTO &&
            accepted.isNotEmpty() &&
            sourceType in accepted
    }

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

        val valueType =
            resolvedValueType(spec)

        if (valueType == ValueType.AUTO) {
            return captureFailure(
                key = key,
                reason = "capture_value_type_unresolved"
            )
        }

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

                val contentStateTransferable =
                    when (spec.kind) {
                        CaptureKind.SCREEN_MARKER ->
                            sourceContentState == "readable" ||
                                sourceContentState == "partial"

                        CaptureKind.SCREEN_TITLE ->
                            sourceContentState == "readable"

                        else ->
                            false
                    }

                if (!contentStateTransferable) {
                    return captureFailure(
                        key = key,
                        reason = "screen_content_state_not_transferable"
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
                            isMarkerObserved(
                                observation = observation,
                                marker = marker
                            )

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
                valueType = valueType.name,
                value = value
            )

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("transfer_key", key)
            .put("value", value)
            .put("value_chars", value.length)
            .put("capture_kind", sourceKind)
            .put("value_type", valueType.name)
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

        val recordType =
            parseValueType(
                record.optString("value_type")
            )

        if (recordType == null || recordType == ValueType.AUTO) {
            return bindFailure(
                key = key,
                reason = "transfer_value_type_invalid"
            )
        }

        val acceptedTypes =
            spec.acceptedValueTypes
                .ifEmpty {
                    if (spec.consumerActionKey.isBlank()) {
                        TEXTUAL_INPUT_TYPES
                    } else {
                        acceptedValueTypesForAction(
                            spec.consumerActionKey
                        )
                    }
                }

        if (acceptedTypes.isEmpty() || recordType !in acceptedTypes) {
            return bindFailure(
                key = key,
                reason =
                    "transfer_type_incompatible:" +
                        recordType.name +
                        "->" +
                        spec.consumerActionKey.ifBlank { "legacy_consumer" }
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
            .put("value_type", recordType.name)
            .put("consumer_app_key", spec.consumerAppKey)
            .put("consumer_action_key", spec.consumerActionKey)
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

        val valueType =
            parseValueType(
                record.optString("value_type")
            ) ?: return false

        if (valueType == ValueType.AUTO) return false

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
                valueType = valueType.name,
                value = value
            )

        if (fingerprint != expectedFingerprint) return false

        val kind =
            record.optString("capture_kind")

        if (
            kind == CaptureKind.SCREEN_MARKER.name
        ) {
            val state =
                record.optString("source_content_state")
            if (
                state != "readable" &&
                state != "partial"
            ) {
                return false
            }
            if (!record.optBoolean("marker_verified", false)) return false
        }

        if (
            kind == CaptureKind.SCREEN_TITLE.name &&
            record.optString("source_content_state") != "readable"
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

    /**
     * Read-only readiness predicate shared by VoiceService observation polling
     * and final capture validation. It prevents the observer from returning on
     * generic browser chrome text before the requested page marker/title exists.
     */
    fun isObservationReadyForCapture(
        spec: CaptureSpec,
        observation: JSONObject
    ): Boolean =
        when (spec.kind) {
            CaptureKind.SCREEN_MARKER -> {
                val state = contentState(observation)
                (state == "readable" || state == "partial") &&
                    isMarkerObserved(
                        observation = observation,
                        marker = spec.marker
                    )
            }

            CaptureKind.SCREEN_TITLE ->
                contentState(observation) == "readable" &&
                    primaryTitle(observation).isNotBlank()

            CaptureKind.ACTION_FIELD ->
                true
        }

    fun isMarkerObserved(
        observation: JSONObject,
        marker: String
    ): Boolean {
        val normalizedMarker =
            normalizeText(marker)

        if (normalizedMarker.isBlank()) return false

        return interactionContexts(observation)
            .any { context ->
                normalizeText(context)
                    .contains(normalizedMarker)
            }
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

        val partialMarkerRecord =
            capture(
                spec =
                    CaptureSpec(
                        transferKey = "partial_page_marker",
                        kind = CaptureKind.SCREEN_MARKER,
                        marker = "Example Domain"
                    ),
                sourceStepKey = "browser-open-partial",
                appKey = "browser",
                actionKey = "open_url",
                actionResult = action,
                observation =
                    JSONObject(screen.toString())
                        .put("primary_content_state", "partial")
            )

        if (!isVerifiedRecord(partialMarkerRecord)) return false
        if (partialMarkerRecord.optString("value") != "Example Domain") return false

        val partialTitleRecord =
            capture(
                spec =
                    CaptureSpec(
                        transferKey = "partial_page_title",
                        kind = CaptureKind.SCREEN_TITLE
                    ),
                sourceStepKey = "browser-open-partial-title",
                appKey = "browser",
                actionKey = "open_url",
                actionResult = action,
                observation =
                    JSONObject(screen.toString())
                        .put("primary_content_state", "partial")
            )

        if (partialTitleRecord.optBoolean("success", true)) return false

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
        if (fieldRecord.optString("value_type") != ValueType.URL.name) return false

        val genericQueryCapture =
            captureSpecForAction(
                transferKey = "generic_query",
                appKey = "youtube",
                actionKey = ACTION_SEARCH
            ) ?: return false

        if (resolvedValueType(genericQueryCapture) != ValueType.QUERY) return false

        val queryAction =
            JSONObject(action.toString())
                .put("requested_query", "AYANA generic transfer")

        val queryRecord =
            capture(
                spec = genericQueryCapture,
                sourceStepKey = "youtube-search",
                appKey = "youtube",
                actionKey = ACTION_SEARCH,
                actionResult = queryAction
            )

        if (!isVerifiedRecord(queryRecord)) return false

        val typedLedger = JSONObject()
        if (!store(typedLedger, queryRecord)) return false

        val browserSearchBinding =
            bindingSpecForAction(
                transferKey = "generic_query",
                appKey = "browser",
                actionKey = ACTION_SEARCH,
                template = DEFAULT_PLACEHOLDER
            ) ?: return false

        if (!areCompatible(genericQueryCapture, browserSearchBinding)) return false
        if (!bind(browserSearchBinding, typedLedger).optBoolean("verified", false)) return false

        val browserUrlBinding =
            bindingSpecForAction(
                transferKey = "generic_query",
                appKey = "browser",
                actionKey = ACTION_OPEN_URL,
                template = DEFAULT_PLACEHOLDER
            ) ?: return false

        if (areCompatible(genericQueryCapture, browserUrlBinding)) return false
        if (bind(browserUrlBinding, typedLedger).optBoolean("success", true)) return false

        val tamperedTypeLedger =
            JSONObject()
                .put(
                    "generic_query",
                    JSONObject(queryRecord.toString())
                        .put("value_type", ValueType.URL.name)
                )

        if (
            bind(
                browserSearchBinding,
                tamperedTypeLedger
            ).optBoolean("success", true)
        ) {
            return false
        }

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

    private fun resolvedValueType(
        spec: CaptureSpec
    ): ValueType {
        if (spec.valueType != ValueType.AUTO) return spec.valueType

        return when (spec.kind) {
            CaptureKind.SCREEN_MARKER -> ValueType.TEXT
            CaptureKind.SCREEN_TITLE -> ValueType.TITLE
            CaptureKind.ACTION_FIELD ->
                when (spec.actionField.trim()) {
                    "requested_url" -> ValueType.URL
                    "requested_query" -> ValueType.QUERY
                    "requested_title" -> ValueType.TITLE
                    "observed_package",
                    "target_package" -> ValueType.PACKAGE
                    else -> ValueType.AUTO
                }
        }
    }

    private fun parseValueType(
        raw: String
    ): ValueType? =
        try {
            ValueType.valueOf(
                raw.trim().uppercase(Locale.ROOT)
            )
        } catch (_: Exception) {
            null
        }

    private fun evidenceFingerprint(
        key: String,
        sourceStepKey: String,
        appKey: String,
        actionKey: String,
        expectedPackage: String,
        sourceKind: String,
        valueType: String,
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
                valueType,
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
        const val VERSION = "2.0"
        const val DEFAULT_PLACEHOLDER = "{{value}}"
        const val SEMANTIC_HINT_SCREEN_TITLE = "screen_title"

        private const val ACTION_OPEN = "open"
        private const val ACTION_SEARCH = "search"
        private const val ACTION_OPEN_URL = "open_url"
        private const val ACTION_CREATE_EVENT_DRAFT = "create_event_draft"
        const val MAX_VALUE_CHARS = 180
        const val MAX_BOUND_PAYLOAD_CHARS = 240
        const val MAX_KEY_CHARS = 64

        val TEXTUAL_INPUT_TYPES: Set<ValueType> =
            setOf(
                ValueType.TEXT,
                ValueType.URL,
                ValueType.QUERY,
                ValueType.TITLE,
                ValueType.PACKAGE
            )

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
