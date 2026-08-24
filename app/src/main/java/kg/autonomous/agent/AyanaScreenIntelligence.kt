package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA Screen Intelligence v4.2 — exact input value truth + bounded local boundary scroll.
 *
 * v4.2 preserves the v4.1 semantic target and verified viewport contract. Own-app
 * editable fields may expose a semantic resolver label in `text`, so exact input
 * verification now consumes factual `value_text` first, then `visual_text`, then
 * the legacy external-app `text` field. Password values remain hidden.
 *
 * `scrollToBoundary()` executes a bounded local scroll loop without cloud round
 * trips. It reports success only after at least one verified viewport move followed
 * by a verified no-progress boundary observation; dispatch alone is never success.
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
                    "Android принял ввод, но точное значение поля не подтверждено фактическими данными экрана"
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

        val before =
            currentSnapshot(service)

        val beforeSignature =
            safeSignature(service)

        val dispatch =
            try {
                service.scrollDetailed(
                    direction
                )
            } catch (_: Exception) {
                JSONObject()
                    .put("success", false)
                    .put("verified", false)
                    .put("action_accepted", false)
                    .put("status", "scroll_dispatch_exception")
                    .put("reason", "scroll_dispatch_exception")
                    .put("proof_level", "none")
            }

        // scrollDetailed() already performs bounded factual verification. The
        // brief settle lets Samsung's event-recovery snapshot catch up so the
        // next semantic target lookup can consume freshly visible Settings rows.
        sleepBriefly()

        val afterSignature =
            safeSignature(service)

        val after =
            currentSnapshot(service)

        val signatureChanged =
            beforeSignature.isNotBlank() &&
                afterSignature.isNotBlank() &&
                beforeSignature != afterSignature

        val actionAccepted =
            dispatch.optBoolean(
                "action_accepted",
                false
            )

        val lowLevelVerified =
            dispatch.optBoolean(
                "verified",
                dispatch.optBoolean(
                    "success",
                    false
                )
            )

        val viewportChanged =
            dispatch.optBoolean(
                "viewport_changed",
                false
            ) ||
                dispatch.optBoolean(
                    "scroll_event_observed",
                    false
                )

        // v4.1: physical viewport movement is a screen-progress fact even when
        // Samsung's verification_text/signature remains sparse and unchanged.
        // The low-level service may set viewportChanged only after same-window
        // movement proof, so this does not turn gesture dispatch into success.
        val changed =
            signatureChanged ||
                (
                    lowLevelVerified &&
                        viewportChanged
                    )

        val verified =
            lowLevelVerified &&
                viewportChanged

        val status =
            when {
                verified ->
                    "scroll_verified"

                actionAccepted ->
                    "scroll_no_progress"

                else ->
                    "scroll_rejected"
            }

        val terminal =
            when {
                verified ->
                    "SUCCESS"

                actionAccepted ->
                    "BLOCKED"

                else ->
                    "ERROR"
            }

        return JSONObject()
            .put("success", verified)
            .put("verified", verified)
            .put("action_accepted", actionAccepted)
            .put("terminal_status", terminal)
            .put("status", status)
            .put(
                "reason",
                dispatch.optString(
                    "reason",
                    status
                )
            )
            .put("direction", direction)
            .put("screen_changed", changed)
            .put("signature_changed", signatureChanged)
            .put("viewport_changed", viewportChanged)
            .put(
                "scroll_event_observed",
                dispatch.optBoolean(
                    "scroll_event_observed",
                    false
                )
            )
            .put(
                "dispatch_method",
                dispatch.optString(
                    "dispatch_method"
                )
            )
            .put(
                "proof_level",
                if (verified) {
                    dispatch.optString(
                        "proof_level",
                        "verified_viewport_progress"
                    )
                } else if (actionAccepted) {
                    "action_accepted_unverified"
                } else {
                    "none"
                }
            )
            .put("scroll_dispatch", dispatch)
            .put("screen_before", screenEvidenceSummary(before, beforeSignature))
            .put("screen", after)
            .put(
                "message",
                when {
                    verified ->
                        "Экран прокручен; физический прогресс viewport подтверждён"

                    actionAccepted ->
                        "Android принял прокрутку, но физическое движение viewport не подтверждено"

                    else ->
                        "Прокрутка не была принята или безопасный путь недоступен"
                }
            )
    }

    /**
     * Bounded local scroll-to-boundary transaction.
     *
     * A boundary is accepted only after factual viewport progress was observed at
     * least once and the following attempt is accepted but proves no additional
     * movement. An initial no-progress result is not enough to invent a boundary.
     */
    fun scrollToBoundary(
        direction: String,
        maxSteps: Int = 6,
        shouldCancel: () -> Boolean = { false }
    ): JSONObject {

        val boundedMaxSteps =
            maxSteps
                .coerceIn(
                    1,
                    12
                )

        val steps =
            JSONArray()

        var verifiedMoves =
            0

        for (stepIndex in 0 until boundedMaxSteps) {

            if (shouldCancel()) {
                return JSONObject()
                    .put("success", false)
                    .put("verified", false)
                    .put("action_accepted", verifiedMoves > 0)
                    .put("terminal_status", "CANCELLED")
                    .put("status", "scroll_boundary_cancelled")
                    .put("reason", "cancel_requested")
                    .put("direction", direction)
                    .put("verified_moves", verifiedMoves)
                    .put("attempts", steps.length())
                    .put("steps", steps)
                    .put("proof_level", if (verifiedMoves > 0) "verified_partial_viewport_progress" else "none")
                    .put("message", "Прокрутка до границы отменена")
            }

            val step =
                scroll(
                    direction
                )

            steps.put(
                JSONObject(step.toString())
                    .apply {
                        remove("screen")
                        remove("screen_before")
                        remove("scroll_dispatch")
                    }
            )

            if (
                step.optBoolean(
                    "verified",
                    false
                ) &&
                step.optString(
                    "status"
                ) ==
                "scroll_verified"
            ) {
                verifiedMoves++
                continue
            }

            if (
                verifiedMoves > 0 &&
                step.optBoolean(
                    "action_accepted",
                    false
                ) &&
                step.optString(
                    "status"
                ) ==
                "scroll_no_progress"
            ) {
                return JSONObject()
                    .put("success", true)
                    .put("verified", true)
                    .put("action_accepted", true)
                    .put("terminal_status", "SUCCESS")
                    .put("status", "scroll_boundary_verified")
                    .put("reason", "verified_progress_then_no_progress")
                    .put("direction", direction)
                    .put("verified_moves", verifiedMoves)
                    .put("attempts", steps.length())
                    .put("boundary_verified", true)
                    .put("steps", steps)
                    .put("proof_level", "verified_viewport_progress_and_boundary")
                    .put("screen", step.optJSONObject("screen") ?: JSONObject())
                    .put("message", "Граница прокрутки подтверждена после фактического движения экрана")
            }

            val terminal =
                step
                    .optString(
                        "terminal_status",
                        "ERROR"
                    )
                    .uppercase(
                        Locale.ROOT
                    )

            return JSONObject()
                .put("success", false)
                .put("verified", false)
                .put(
                    "action_accepted",
                    step.optBoolean(
                        "action_accepted",
                        false
                    )
                )
                .put(
                    "terminal_status",
                    when (terminal) {
                        "BLOCKED" -> "BLOCKED"
                        "UNSUPPORTED" -> "UNSUPPORTED"
                        "CANCELLED" -> "CANCELLED"
                        else -> "ERROR"
                    }
                )
                .put(
                    "status",
                    if (verifiedMoves == 0 && step.optString("status") == "scroll_no_progress") {
                        "scroll_boundary_not_proven"
                    } else {
                        step.optString("status", "scroll_boundary_failed")
                    }
                )
                .put(
                    "reason",
                    if (verifiedMoves == 0 && step.optString("status") == "scroll_no_progress") {
                        "initial_no_progress_is_not_boundary_proof"
                    } else {
                        step.optString("reason", "scroll_boundary_failed")
                    }
                )
                .put("direction", direction)
                .put("verified_moves", verifiedMoves)
                .put("attempts", steps.length())
                .put("boundary_verified", false)
                .put("steps", steps)
                .put("proof_level", if (verifiedMoves > 0) "verified_partial_viewport_progress" else "none")
                .put("screen", step.optJSONObject("screen") ?: JSONObject())
                .put(
                    "message",
                    if (verifiedMoves == 0 && step.optString("status") == "scroll_no_progress") {
                        "Граница не подтверждена: движение экрана до остановки не наблюдалось"
                    } else {
                        step.optString(
                            "message",
                            "Прокрутка до границы не подтверждена"
                        )
                    }
                )
        }

        return JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("action_accepted", verifiedMoves > 0)
            .put("terminal_status", "BLOCKED")
            .put("status", "scroll_boundary_not_reached")
            .put("reason", "bounded_step_limit_reached_while_still_moving")
            .put("direction", direction)
            .put("verified_moves", verifiedMoves)
            .put("attempts", steps.length())
            .put("boundary_verified", false)
            .put("steps", steps)
            .put("proof_level", if (verifiedMoves > 0) "verified_partial_viewport_progress" else "none")
            .put("message", "Достигнут безопасный лимит локальной прокрутки; граница не подтверждена")
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

            val nodeValue =
                normalizeValue(
                    node
                        .optString(
                            "value_text"
                        )
                        .ifBlank {
                            node
                                .optString(
                                    "visual_text"
                                )
                                .ifBlank {
                                    node.optString(
                                        "text"
                                    )
                                }
                        }
                )
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

            if (sameIdentity && nodeValue == expected) {
                identityMatches += node
            }

            if (node.optBoolean("focused", false) && nodeValue == expected) {
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
