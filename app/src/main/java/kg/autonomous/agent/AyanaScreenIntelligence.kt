package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA Screen Intelligence v4.0 — universal semantic action truth.
 *
 * v4.0 keeps the v3 perception contract intact, but all semantic click/input
 * actions now pass through AyanaSemanticTargetResolver before Android receives
 * input. A generic screen change is evidence of progress, not permission to
 * invent target identity. Action results expose explicit acceptance,
 * verification and terminal status fields for higher execution layers.
 */
class AyanaScreenIntelligence(
    context: Context
) {

    @Suppress("unused")
    private val appContext =
        context.applicationContext

    private val targetResolver =
        AyanaSemanticTargetResolver()

    fun getScreenState(): JSONObject {
        val service =
            AgentAccessibilityService.instance
                ?: return unavailable()

        return currentSnapshot(
            service = service,
            compact = false
        )
    }

    fun click(
        target: String,
        confirmed: Boolean = false
    ): JSONObject {

        val cleanTarget = target.trim()

        if (
            isSensitiveTarget(cleanTarget) &&
            !confirmed
        ) {
            return JSONObject()
                .put("success", false)
                .put("verified", false)
                .put("action_accepted", false)
                .put("terminal_status", "BLOCKED")
                .put("status", "confirmation_required")
                .put("reason", "confirmation_required")
                .put("requires_confirmation", true)
                .put("target", cleanTarget)
                .put("requested_target", cleanTarget)
                .put("proof_level", "none")
                .put(
                    "message",
                    "Чувствительное действие требует явного подтверждения пользователя"
                )
        }

        val service =
            AgentAccessibilityService.instance
                ?: return unavailable()

        val before = currentSnapshot(service)
        val beforeSignature = safeSignature(service)

        val resolution =
            targetResolver.resolve(
                screen = before,
                requestedTarget = cleanTarget,
                mode = AyanaSemanticTargetResolver.Mode.CLICK
            )

        if (!resolution.optBoolean("resolved", false)) {
            return resolutionFailure(
                resolution = resolution,
                requestedTarget = cleanTarget,
                before = before
            )
        }

        val actionTarget =
            resolution.optString("action_target")
                .trim()
                .ifBlank { cleanTarget }

        val accepted =
            try {
                service.clickElement(actionTarget)
            } catch (_: Exception) {
                false
            }

        // AgentAccessibilityService v5.9 returns true for semantic click only
        // after its own bounded screen-change verification. Still reacquire the
        // factual snapshot here so upper layers receive the resulting state.
        sleepBriefly()

        val afterSignature = safeSignature(service)
        val after = currentSnapshot(service)
        val changed =
            beforeSignature.isNotBlank() &&
                afterSignature.isNotBlank() &&
                beforeSignature != afterSignature

        val verified = accepted && changed

        return JSONObject()
            .put("success", verified)
            .put("verified", verified)
            .put("action_accepted", accepted)
            .put("terminal_status", if (verified) "SUCCESS" else "ERROR")
            .put("status", if (verified) "target_action_verified" else "target_action_not_verified")
            .put("reason", if (verified) "target_action_verified" else "target_action_not_verified")
            .put("target", cleanTarget)
            .put("requested_target", cleanTarget)
            .put("resolved_target", actionTarget)
            .put("screen_changed", changed)
            .put("proof_level", if (verified) "semantic_target_and_screen_change" else if (accepted) "action_accepted_unverified" else "none")
            .put("target_evidence", resolution)
            .put("screen_before", screenEvidenceSummary(before, beforeSignature))
            .put("screen", after)
            .put(
                "message",
                if (verified) {
                    "Целевой элемент нажат; изменение экрана подтверждено"
                } else if (accepted) {
                    "Android принял действие, но требуемый результат не подтверждён"
                } else {
                    "Семантическая цель найдена, но Android не подтвердил действие"
                }
            )
    }

    fun inputText(
        target: String?,
        text: String
    ): JSONObject {

        if (looksLikeSecret(text)) {
            return JSONObject()
                .put("success", false)
                .put("verified", false)
                .put("action_accepted", false)
                .put("terminal_status", "BLOCKED")
                .put("status", "secret_input_blocked")
                .put("reason", "secret_input_blocked")
                .put("requires_confirmation", true)
                .put("target", target.orEmpty())
                .put("proof_level", "none")
                .put(
                    "message",
                    "AYANA не вводит пароли, коды подтверждения, платёжные данные или другие секреты автономно"
                )
        }

        val service =
            AgentAccessibilityService.instance
                ?: return unavailable()

        val cleanTarget = target?.trim()?.takeIf { it.isNotBlank() }
        val before = currentSnapshot(service)
        val beforeSignature = safeSignature(service)

        val resolution =
            targetResolver.resolve(
                screen = before,
                requestedTarget = cleanTarget,
                mode = AyanaSemanticTargetResolver.Mode.INPUT
            )

        if (!resolution.optBoolean("resolved", false)) {
            return resolutionFailure(
                resolution = resolution,
                requestedTarget = cleanTarget.orEmpty(),
                before = before
            )
        }

        val actionTarget =
            resolution.optString("action_target")
                .trim()
                .ifBlank { null }

        val accepted =
            try {
                service.setText(
                    target = actionTarget,
                    text = text
                )
            } catch (_: Exception) {
                false
            }

        sleepBriefly()

        val afterSignature = safeSignature(service)
        val after = currentSnapshot(service)
        val changed =
            beforeSignature.isNotBlank() &&
                afterSignature.isNotBlank() &&
                beforeSignature != afterSignature

        val inputVerification =
            if (accepted) {
                verifyInputText(
                    screen = after,
                    resolution = resolution,
                    expectedText = text
                )
            } else {
                JSONObject()
                    .put("verified", false)
                    .put("reason", "android_set_text_rejected")
            }

        val verified =
            accepted &&
                inputVerification.optBoolean("verified", false)

        return JSONObject()
            .put("success", verified)
            .put("verified", verified)
            .put("action_accepted", accepted)
            .put("terminal_status", if (verified) "SUCCESS" else "ERROR")
            .put("status", if (verified) "input_text_verified" else if (accepted) "input_text_unverified" else "input_text_rejected")
            .put("reason", if (verified) "input_text_verified" else if (accepted) "input_text_unverified" else "input_text_rejected")
            .put("target", cleanTarget.orEmpty())
            .put("requested_target", cleanTarget.orEmpty())
            .put("resolved_target", actionTarget.orEmpty())
            .put("screen_changed", changed)
            .put("proof_level", if (verified) "semantic_field_and_text_value" else if (accepted) "action_accepted_unverified" else "none")
            .put("target_evidence", resolution)
            .put("input_verification", inputVerification)
            .put("screen_before", screenEvidenceSummary(before, beforeSignature))
            .put("screen", after)
            .put(
                "message",
                if (verified) {
                    "Текст введён и подтверждён в целевом поле"
                } else if (accepted) {
                    "Android принял ввод, но точное значение поля не подтверждено Accessibility evidence"
                } else {
                    "Поле подтверждено, но Android не принял ввод текста"
                }
            )
    }

    fun scroll(
        direction: String
    ): JSONObject {

        val service =
            AgentAccessibilityService.instance
                ?: return unavailable()

        val before = currentSnapshot(service)
        val beforeSignature = safeSignature(service)

        val acceptedAndVerified =
            try {
                service.scroll(direction)
            } catch (_: Exception) {
                false
            }

        sleepBriefly()

        val afterSignature = safeSignature(service)
        val after = currentSnapshot(service)
        val changed =
            beforeSignature.isNotBlank() &&
                afterSignature.isNotBlank() &&
                beforeSignature != afterSignature

        // v5.9 scroll returns true only when performAction was accepted AND
        // waitForScreenChange() verified progress.
        val verified = acceptedAndVerified && changed

        return JSONObject()
            .put("success", verified)
            .put("verified", verified)
            .put("action_accepted", acceptedAndVerified)
            .put("terminal_status", if (verified) "SUCCESS" else "ERROR")
            .put("status", if (verified) "scroll_verified" else "scroll_no_progress")
            .put("reason", if (verified) "scroll_verified" else "scroll_no_progress")
            .put("direction", direction)
            .put("screen_changed", changed)
            .put("proof_level", if (verified) "screen_change" else "none")
            .put("screen_before", screenEvidenceSummary(before, beforeSignature))
            .put("screen", after)
            .put(
                "message",
                if (verified) {
                    "Экран прокручен; прогресс подтверждён"
                } else {
                    "Прокрутка не дала подтверждённого изменения экрана"
                }
            )
    }

    fun tap(
        x: Int,
        y: Int,
        confirmed: Boolean = false
    ): JSONObject {

        if (!confirmed) {
            return JSONObject()
                .put("success", false)
                .put("verified", false)
                .put("action_accepted", false)
                .put("terminal_status", "BLOCKED")
                .put("status", "confirmation_required")
                .put("reason", "confirmation_required")
                .put("requires_confirmation", true)
                .put("proof_level", "none")
                .put(
                    "message",
                    "Касание по координатам используется только после явного подтверждения пользователя"
                )
        }

        val service =
            AgentAccessibilityService.instance
                ?: return unavailable()

        val before = currentSnapshot(service)
        val beforeSignature = safeSignature(service)

        val accepted =
            try {
                service.tapCoordinates(x, y)
            } catch (_: Exception) {
                false
            }

        sleepBriefly()

        val afterSignature = safeSignature(service)
        val after = currentSnapshot(service)
        val changed =
            beforeSignature.isNotBlank() &&
                afterSignature.isNotBlank() &&
                beforeSignature != afterSignature

        // Coordinate tap is explicitly confirmed and only promises gesture
        // dispatch, not a semantic target state. Expose that limitation instead
        // of turning an unrelated screen change into target proof.
        return JSONObject()
            .put("success", accepted)
            .put("verified", accepted)
            .put("action_accepted", accepted)
            .put("terminal_status", if (accepted) "SUCCESS" else "ERROR")
            .put("status", if (accepted) "gesture_dispatched" else "gesture_rejected")
            .put("reason", if (accepted) "gesture_dispatched" else "gesture_rejected")
            .put("x", x)
            .put("y", y)
            .put("screen_changed", changed)
            .put("proof_level", if (accepted) "gesture_dispatch_accepted" else "none")
            .put("semantic_target_verified", false)
            .put("screen_before", screenEvidenceSummary(before, beforeSignature))
            .put("screen", after)
            .put(
                "message",
                if (accepted) {
                    "Подтверждённое пользователем касание отправлено; семантический результат не заявляется"
                } else {
                    "Не удалось отправить касание"
                }
            )
    }

    fun pressBack(): JSONObject {
        val service =
            AgentAccessibilityService.instance
                ?: return unavailable()

        val before = currentSnapshot(service)
        val beforeSignature = safeSignature(service)

        val accepted =
            try {
                service.pressBack()
            } catch (_: Exception) {
                false
            }

        sleepBriefly()

        val afterSignature = safeSignature(service)
        val after = currentSnapshot(service)
        val changed =
            beforeSignature.isNotBlank() &&
                afterSignature.isNotBlank() &&
                beforeSignature != afterSignature

        return JSONObject()
            .put("success", accepted)
            .put("verified", accepted)
            .put("action_accepted", accepted)
            .put("terminal_status", if (accepted) "SUCCESS" else "ERROR")
            .put("status", if (accepted) "global_back_dispatched" else "global_back_rejected")
            .put("reason", if (accepted) "global_back_dispatched" else "global_back_rejected")
            .put("screen_changed", changed)
            .put("proof_level", if (accepted) "global_action_accepted" else "none")
            .put("screen_before", screenEvidenceSummary(before, beforeSignature))
            .put("screen", after)
            .put("message", if (accepted) "Назад выполнено" else "Не удалось выполнить Назад")
    }

    fun pressHome(): JSONObject {
        val service =
            AgentAccessibilityService.instance
                ?: return unavailable()

        val before = currentSnapshot(service)
        val beforeSignature = safeSignature(service)

        val accepted =
            try {
                service.pressHome()
            } catch (_: Exception) {
                false
            }

        sleepBriefly()

        val afterSignature = safeSignature(service)
        val after = currentSnapshot(service)
        val changed =
            beforeSignature.isNotBlank() &&
                afterSignature.isNotBlank() &&
                beforeSignature != afterSignature

        return JSONObject()
            .put("success", accepted)
            .put("verified", accepted)
            .put("action_accepted", accepted)
            .put("terminal_status", if (accepted) "SUCCESS" else "ERROR")
            .put("status", if (accepted) "global_home_dispatched" else "global_home_rejected")
            .put("reason", if (accepted) "global_home_dispatched" else "global_home_rejected")
            .put("screen_changed", changed)
            .put("proof_level", if (accepted) "global_action_accepted" else "none")
            .put("screen_before", screenEvidenceSummary(before, beforeSignature))
            .put("screen", after)
            .put("message", if (accepted) "Домой выполнено" else "Не удалось выполнить Домой")
    }

    private fun currentSnapshot(
        service: AgentAccessibilityService,
        compact: Boolean = true
    ): JSONObject =
        annotateSnapshot(
            try {
                if (compact) {
                    // Action verification needs enough semantic context to keep
                    // target identity stable, but it must not repeatedly request
                    // the full default 140/14000 snapshot on every before/after
                    // transition. Keep the v3 compact-action spirit while allowing
                    // a little more evidence for v12.8's resolver.
                    service.buildScreenSnapshot(
                        maxNodes = 100,
                        maxChars = 10000
                    )
                } else {
                    // Public screen reads keep the Accessibility service's default
                    // acquisition budget. This preserves the richer Agent Core /
                    // diagnostics perception path instead of globally shrinking it.
                    service.buildScreenSnapshot()
                }
            } catch (_: Exception) {
                JSONObject()
                    .put("success", false)
                    .put("snapshot_success", false)
                    .put("primary_content_state", "unknown")
                    .put("primary_content_available", false)
                    .put("primary_failure_reason", "snapshot_exception")
            }
        )

    private fun verifyInputText(
        screen: JSONObject,
        resolution: JSONObject,
        expectedText: String
    ): JSONObject {

        val nodes = screen.optJSONArray("nodes")
            ?: return JSONObject()
                .put("verified", false)
                .put("reason", "post_input_nodes_unavailable")

        val expected = normalizeValue(expectedText)
        if (expected.isBlank()) {
            // Empty text is a meaningful clearing operation. Verify exact empty
            // value only on the same resolved field identity.
            return verifyResolvedInputIdentity(
                nodes = nodes,
                resolution = resolution,
                expected = expected
            )
        }

        return verifyResolvedInputIdentity(
            nodes = nodes,
            resolution = resolution,
            expected = expected
        )
    }

    private fun verifyResolvedInputIdentity(
        nodes: JSONArray,
        resolution: JSONObject,
        expected: String
    ): JSONObject {

        val candidate = resolution.optJSONObject("candidate")
        val expectedViewId = candidate?.optString("view_id").orEmpty().trim()
        val expectedDescription = normalizeValue(candidate?.optString("description").orEmpty())
        val expectedBounds = canonicalBounds(candidate?.opt("bounds"))

        val identityMatches = mutableListOf<JSONObject>()
        val focusedMatches = mutableListOf<JSONObject>()

        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue

            if (
                !node.optBoolean("visible", false) ||
                !node.optBoolean("enabled", true) ||
                !node.optBoolean("editable", false) ||
                node.optBoolean("password", false)
            ) {
                continue
            }

            val nodeText = normalizeValue(node.optString("text"))
            val nodeViewId = node.optString("view_id").trim()
            val nodeDescription = normalizeValue(node.optString("description"))
            val nodeBounds = canonicalBounds(node.opt("bounds"))

            val sameIdentity =
                (expectedViewId.isNotBlank() && nodeViewId == expectedViewId) ||
                    (expectedBounds.isNotBlank() && nodeBounds == expectedBounds) ||
                    (
                        expectedDescription.isNotBlank() &&
                            nodeDescription == expectedDescription
                    )

            if (sameIdentity && nodeText == expected) {
                identityMatches += node
            }

            if (node.optBoolean("focused", false) && nodeText == expected) {
                focusedMatches += node
            }
        }

        val verifiedNode =
            when {
                identityMatches.size == 1 -> identityMatches.first()
                identityMatches.size > 1 -> null
                focusedMatches.size == 1 -> focusedMatches.first()
                else -> null
            }

        return JSONObject()
            .put("verified", verifiedNode != null)
            .put(
                "reason",
                when {
                    verifiedNode != null -> "post_input_value_matches_resolved_field"
                    identityMatches.size > 1 -> "post_input_identity_ambiguous"
                    focusedMatches.size > 1 -> "post_input_focused_match_ambiguous"
                    else -> "post_input_value_not_observed"
                }
            )
            .put("identity_match_count", identityMatches.size)
            .put("focused_match_count", focusedMatches.size)
            .put("expected_length", expected.length)
            .apply {
                if (verifiedNode != null) {
                    put(
                        "observed_field",
                        JSONObject()
                            .put("view_id", verifiedNode.optString("view_id"))
                            .put("description", verifiedNode.optString("description"))
                            .put("bounds", verifiedNode.opt("bounds") ?: "")
                            .put("focused", verifiedNode.optBoolean("focused", false))
                    )
                }
            }
    }

    private fun resolutionFailure(
        resolution: JSONObject,
        requestedTarget: String,
        before: JSONObject
    ): JSONObject {

        val reason =
            resolution.optString("reason")
                .ifBlank { resolution.optString("status", "target_not_resolved") }

        val terminal =
            when (reason) {
                "content_unavailable",
                "target_not_found",
                "ambiguous_target",
                "editable_target_not_found",
                "ambiguous_editable_target" -> "BLOCKED"

                "snapshot_unavailable" -> "ERROR"
                else -> "ERROR"
            }

        return JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("action_accepted", false)
            .put("terminal_status", terminal)
            .put("status", reason)
            .put("reason", reason)
            .put("target", requestedTarget)
            .put("requested_target", requestedTarget)
            .put("screen_changed", false)
            .put("proof_level", "none")
            .put("target_evidence", resolution)
            .put("screen", before)
            .put(
                "message",
                resolution.optString("message")
                    .ifBlank { "Семантическая цель не подтверждена" }
            )
    }

    private fun screenEvidenceSummary(
        screen: JSONObject,
        signature: String
    ): JSONObject =
        JSONObject()
            .put("package", screen.optString("package"))
            .put("root_class", screen.optString("root_class"))
            .put("primary_content_state", screen.optString("primary_content_state", "unknown"))
            .put("primary_content_available", screen.optBoolean("primary_content_available", false))
            .put("window_context_mode", screen.optString("window_context_mode"))
            .put("node_count", screen.optInt("node_count", screen.optJSONArray("nodes")?.length() ?: -1))
            .put("signature_hash", if (signature.isBlank()) "" else signature.hashCode().toString())

    private fun compactScreenState(
        service: AgentAccessibilityService
    ): JSONObject =
        currentSnapshot(service)

    private fun annotateSnapshot(
        snapshot: JSONObject
    ): JSONObject {

        val snapshotSuccess =
            snapshot.optBoolean("success", false)

        val contentState =
            snapshot
                .optString(
                    "primary_content_state",
                    snapshot.optString("content_status", "unknown")
                )
                .ifBlank { "unknown" }

        val contentAvailable =
            snapshot.optBoolean(
                "primary_content_available",
                contentState == "readable" ||
                    contentState == "partial"
            )

        val message =
            when {
                !snapshotSuccess ->
                    "Снимок Accessibility получить не удалось"

                contentState == "readable" ->
                    "Основное окно и его содержимое доступны для чтения"

                contentState == "partial" ->
                    "Основное окно читается только частично"

                contentState == "structure_only" ->
                    "Структура окна доступна, но читаемый текст не подтверждён"

                contentState == "unavailable" ->
                    "Окно определено, но его содержимое недоступно для надёжного чтения"

                else ->
                    "Состояние содержимого экрана не удалось подтвердить"
            }

        return snapshot
            .put("source", "android_accessibility")
            .put(
                "content_contract_version",
                snapshot.optInt("content_contract_version", 2)
            )
            .put("snapshot_success", snapshotSuccess)
            .put(
                "understanding_success",
                snapshotSuccess && contentAvailable
            )
            .put("content_status", contentState)
            .put("content_available", contentAvailable)
            .put("content_message", message)
    }

    private fun unavailable(): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("action_accepted", false)
            .put("terminal_status", "UNSUPPORTED")
            .put("status", "accessibility_unavailable")
            .put("reason", "accessibility_unavailable")
            .put("snapshot_success", false)
            .put("understanding_success", false)
            .put("content_contract_version", 2)
            .put("content_status", "unknown")
            .put("primary_content_state", "unknown")
            .put("primary_content_available", false)
            .put("accessibility_enabled", false)
            .put("proof_level", "none")
            .put("message", "Служба Accessibility AYANA не подключена")

    private fun safeSignature(
        service: AgentAccessibilityService
    ): String =
        try {
            service.screenSignature()
        } catch (_: Exception) {
            ""
        }

    private fun canonicalBounds(value: Any?): String {
        if (value == null) return ""

        if (value is JSONObject) {
            return listOf("left", "top", "right", "bottom")
                .joinToString(",") { key -> value.optInt(key, Int.MIN_VALUE).toString() }
        }

        return value.toString().trim()
    }

    private fun normalizeValue(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun sleepBriefly() {
        try {
            Thread.sleep(ACTION_SETTLE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun isSensitiveTarget(
        value: String
    ): Boolean {

        val normalized =
            value.lowercase(Locale.ROOT)

        val sensitiveWords =
            listOf(
                "оплат",
                "купить",
                "перевести деньги",
                "отправить деньги",
                "отправить",
                "send",
                "удалить",
                "delete",
                "сброс",
                "factory reset",
                "подтвердить",
                "confirm",
                "разрешить доступ",
                "grant permission"
            )

        return sensitiveWords.any(normalized::contains)
    }

    private fun looksLikeSecret(
        value: String
    ): Boolean {

        val trimmed = value.trim()

        if (trimmed.matches(Regex("^\\d{4,8}$"))) {
            return true
        }

        if (trimmed.matches(Regex(".*\\b\\d{13,19}\\b.*"))) {
            return true
        }

        val lower = trimmed.lowercase(Locale.ROOT)

        return listOf(
            "пароль",
            "password",
            "cvv",
            "cvc",
            "pin-код",
            "пин-код",
            "одноразовый код",
            "код из смс",
            "otp"
        ).any(lower::contains)
    }

    companion object {
        private const val ACTION_SETTLE_MS = 420L
    }
}
