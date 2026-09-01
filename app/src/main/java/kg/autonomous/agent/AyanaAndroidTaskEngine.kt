package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA Android Task Engine v5.4 — FOREGROUND + SETTINGS VERIFICATION TRUTH.
 *
 * v5.4 preserves v5.3 exact foreground-package truth and adds a verified
 * Settings-gateway handoff. open_settings can no longer succeed from Intent
 * dispatch/screen-change alone: the gateway must prove the requested Settings
 * section and com.android.settings ownership. A gateway-verified final section
 * is accepted as factual progress even when AYANA's overlay keeps the generic
 * screen fingerprint unchanged. v5.3 app-launch rules remain unchanged:
 *
 * - AYANA overlay / sibling windows are never sufficient proof of the launched app;
 * - the exact resolved package must be proven by the primary/interaction context,
 *   an active/focused application window, or a fresh target-specific Accessibility
 *   foreground-owner transition;
 * - a broad packages[] membership alone is not accepted for open_app;
 * - fresh target ownership evidence counts as observable transition evidence
 *   even when AYANA's floating overlay masks the primary snapshot fingerprint.
 *
 * No application name is hard-coded; every app uses the same rule.
 */
class AyanaAndroidTaskEngine(
    private val screenIntelligence: AyanaScreenIntelligence,
    private val gateway: ActionGateway,
    private val shouldCancel: () -> Boolean = { false }
) {

    interface ActionGateway {
        fun openSettings(section: String): JSONObject
        fun openApp(name: String): JSONObject
        fun openAppInfo(name: String): JSONObject
        fun openAppSettings(name: String, section: String): JSONObject
        fun changeVolume(action: String): JSONObject
    }

    private data class PackageProof(
        val verified: Boolean,
        val source: String = "",
        val expectedPackage: String = "",
        val observedPackage: String = "",
        val eventPackage: String = "",
        val eventAgeMs: Long = -1L,
        val ownerPackage: String = "",
        val ownerAgeMs: Long = -1L,
        val transitionObserved: Boolean = false
    )

    private val targetResolver = AyanaSemanticTargetResolver()

    fun execute(
        plan: JSONObject,
        confirmed: Boolean = false,
        startIndex: Int = 0,
        initialActionsUsed: Int = 0,
        onCheckpoint: ((JSONObject) -> Boolean)? = null
    ): JSONObject {
        val goal = plan.optString("goal").trim()

        if (isCancelled()) {
            return engineCancelled(goal, JSONArray(), safeScreenState(), 0)
        }

        val steps = plan.optJSONArray("steps")
            ?: return engineFailure(goal, "План не содержит steps")

        if (steps.length() == 0) {
            return engineFailure(goal, "План пуст")
        }

        var terminalCount = 0
        var terminalIndex = -1
        for (index in 0 until steps.length()) {
            val step = steps.optJSONObject(index)
                ?: return engineFailure(goal, "План содержит повреждённый шаг #${index + 1}")
            if (step.optBoolean("terminal", false)) {
                terminalCount++
                terminalIndex = index
            }
        }

        if (terminalCount != 1 || terminalIndex != steps.length() - 1) {
            return engineFailure(
                goal,
                "План должен содержать ровно один финальный проверяемый шаг в конце"
            )
        }

        val maxActions = plan.optInt("max_actions", DEFAULT_MAX_ACTIONS)
            .coerceIn(1, HARD_MAX_ACTIONS)
        val resumeIndex = startIndex.coerceIn(0, steps.length())
        var currentScreen = safeScreenState()
        var actionsUsed = initialActionsUsed.coerceIn(0, maxActions)

        if (resumeIndex >= steps.length()) {
            return engineBlocked(
                goal = goal,
                reason = "Нет оставшихся шагов для безопасного восстановления; нужна повторная проверка цели",
                trace = JSONArray(),
                screen = currentScreen,
                actionsUsed = actionsUsed,
                replanRecommended = true,
                failureLayer = "recovery"
            )
        }

        val trace = JSONArray()
        var confirmationAvailable = confirmed
        var noProgressStreak = 0
        val transitionCounts = linkedMapOf<String, Int>()

        for (index in resumeIndex until steps.length()) {
            if (isCancelled()) {
                return engineCancelled(goal, trace, currentScreen, actionsUsed)
            }

            val step = steps.optJSONObject(index)
                ?: return engineFailure(
                    goal = goal,
                    reason = "Некорректный шаг #${index + 1}",
                    trace = trace,
                    screen = currentScreen,
                    actionsUsed = actionsUsed
                )

            val action = normalizedAction(step)
            val minimumCost = minimumActionCost(action)
            if (actionsUsed + minimumCost > maxActions) {
                return engineBlocked(
                    goal = goal,
                    reason = "Достигнут локальный лимит действий",
                    trace = trace,
                    screen = currentScreen,
                    actionsUsed = actionsUsed,
                    replanRecommended = true,
                    failureLayer = "action_budget"
                )
            }

            val requiresConfirmation = stepRequiresConfirmation(step)
            val stepConfirmed = requiresConfirmation && confirmationAvailable

            if (requiresConfirmation && !stepConfirmed) {
                val result = confirmationRequired(step, currentScreen)
                trace.put(traceRecord(index, step, result, currentScreen, currentScreen))
                emitCheckpoint(
                    callback = onCheckpoint,
                    checkpoint = "waiting_confirmation",
                    nextStepIndex = index,
                    actionsUsed = actionsUsed,
                    step = step,
                    stepResult = result,
                    screen = currentScreen,
                    inFlight = false
                )
                return JSONObject()
                    .put("success", false)
                    .put("status", "needs_confirmation")
                    .put("terminal_status", "BLOCKED")
                    .put("goal", goal)
                    .put("requires_confirmation", true)
                    .put("failure_layer", "safety_confirmation")
                    .put("reason", "confirmation_required")
                    .put("message", result.optString("message", "Требуется подтверждение пользователя"))
                    .put("actions_used", actionsUsed)
                    .put("replan_recommended", false)
                    .put("trace", trace)
                    .put("screen", currentScreen)
            }

            if (stepConfirmed) {
                confirmationAvailable = false
            }

            val preCheckpointOk = emitCheckpoint(
                callback = onCheckpoint,
                checkpoint = "before_step",
                nextStepIndex = index,
                actionsUsed = actionsUsed,
                step = step,
                stepResult = null,
                screen = currentScreen,
                inFlight = true
            )

            if (!preCheckpointOk) {
                return engineBlocked(
                    goal = goal,
                    reason = "Не удалось сохранить checkpoint перед действием; выполнение остановлено до dispatch",
                    trace = trace,
                    screen = currentScreen,
                    actionsUsed = actionsUsed,
                    replanRecommended = false,
                    checkpointFailed = true,
                    failureLayer = "checkpoint"
                )
                    .put("safe_to_retry", true)
                    .put("action_dispatched", false)
            }

            val beforeScreen = currentScreen
            val result = executeStep(
                step = step,
                screenBefore = beforeScreen,
                confirmed = stepConfirmed,
                remainingBudget = maxActions - actionsUsed
            )

            val used = result.optInt("actions_used", 0).coerceAtLeast(0)
            actionsUsed = (actionsUsed + used).coerceAtMost(maxActions)
            currentScreen = result.optJSONObject("screen") ?: safeScreenState()

            trace.put(traceRecord(index, step, result, beforeScreen, currentScreen))

            if (result.optString("status") == "cancelled" || isCancelled()) {
                emitCheckpoint(
                    callback = onCheckpoint,
                    checkpoint = "cancelled",
                    nextStepIndex = index,
                    actionsUsed = actionsUsed,
                    step = step,
                    stepResult = result,
                    screen = currentScreen,
                    inFlight = false
                )
                return engineCancelled(goal, trace, currentScreen, actionsUsed)
            }

            if (result.optBoolean("requires_confirmation", false)) {
                emitCheckpoint(
                    callback = onCheckpoint,
                    checkpoint = "waiting_confirmation",
                    nextStepIndex = index,
                    actionsUsed = actionsUsed,
                    step = step,
                    stepResult = result,
                    screen = currentScreen,
                    inFlight = false
                )
                return JSONObject()
                    .put("success", false)
                    .put("status", "needs_confirmation")
                    .put("terminal_status", "BLOCKED")
                    .put("goal", goal)
                    .put("requires_confirmation", true)
                    .put("failure_layer", result.optString("failure_layer", "safety_confirmation"))
                    .put("reason", result.optString("reason", "confirmation_required"))
                    .put("message", result.optString("message", "Требуется подтверждение пользователя"))
                    .put("actions_used", actionsUsed)
                    .put("replan_recommended", false)
                    .put("trace", trace)
                    .put("screen", currentScreen)
            }

            val stepSuccess = result.optBoolean("success", false)
            val progress = result.optBoolean("progress", false)

            val postCheckpointOk = emitCheckpoint(
                callback = onCheckpoint,
                checkpoint = if (stepSuccess) "step_completed" else "step_failed",
                nextStepIndex = if (stepSuccess) index + 1 else index,
                actionsUsed = actionsUsed,
                step = step,
                stepResult = result,
                screen = currentScreen,
                inFlight = false
            )

            if (!postCheckpointOk) {
                return engineBlocked(
                    goal = goal,
                    reason = if (stepSuccess) {
                        "Действие подтверждено, но post-action checkpoint не сохранён; автоматический повтор запрещён"
                    } else {
                        "Не удалось сохранить post-action checkpoint"
                    },
                    trace = trace,
                    screen = currentScreen,
                    actionsUsed = actionsUsed,
                    replanRecommended = false,
                    checkpointFailed = true,
                    failureLayer = "checkpoint"
                )
                    .put("action_already_verified", stepSuccess)
                    .put("safe_to_retry", !stepSuccess)
                    .put("action_dispatched", result.optBoolean("action_accepted", stepSuccess))
            }

            if (!stepSuccess) {
                if (step.optBoolean("optional", false)) {
                    continue
                }
                return engineStepFailure(
                    goal = goal,
                    result = result,
                    trace = trace,
                    screen = currentScreen,
                    actionsUsed = actionsUsed,
                    action = action
                )
            }

            if (stepRequiresObservableProgress(action)) {
                if (progress) {
                    noProgressStreak = 0
                } else {
                    noProgressStreak++
                }

                if (noProgressStreak >= MAX_NO_PROGRESS_STREAK) {
                    return engineBlocked(
                        goal = goal,
                        reason = "Два последовательных навигационных действия не дали подтверждённого прогресса",
                        trace = trace,
                        screen = currentScreen,
                        actionsUsed = actionsUsed,
                        replanRecommended = true,
                        failureLayer = "recovery",
                        failureReason = "no_progress_streak"
                    )
                }
            }

            if (progress) {
                val transition = transitionFingerprint(action, beforeScreen, currentScreen)
                if (transition.isNotBlank()) {
                    val count = (transitionCounts[transition] ?: 0) + 1
                    transitionCounts[transition] = count
                    if (count >= MAX_IDENTICAL_TRANSITION_REPEATS) {
                        return engineBlocked(
                            goal = goal,
                            reason = "Обнаружен повтор одного и того же Android-перехода; цикл остановлен",
                            trace = trace,
                            screen = currentScreen,
                            actionsUsed = actionsUsed,
                            replanRecommended = true,
                            failureLayer = "recovery",
                            failureReason = "transition_cycle"
                        )
                    }
                }
            }

            if (step.optBoolean("terminal", false)) {
                val terminalVerified = verifyTerminalStep(step, result, currentScreen)
                if (terminalVerified) {
                    return engineSuccess(
                        goal = goal,
                        message = result.optString("message", "Цель достигнута"),
                        trace = trace,
                        screen = currentScreen,
                        actionsUsed = actionsUsed,
                        terminalEvidence = terminalEvidence(step, result, currentScreen)
                    )
                }

                return engineBlocked(
                    goal = goal,
                    reason = "Финальный шаг выполнен, но конечное состояние не подтверждено",
                    trace = trace,
                    screen = currentScreen,
                    actionsUsed = actionsUsed,
                    replanRecommended = true,
                    failureLayer = "verification",
                    failureReason = "terminal_not_verified"
                )
            }
        }

        return engineBlocked(
            goal = goal,
            reason = "План завершился без подтверждённого конечного состояния",
            trace = trace,
            screen = currentScreen,
            actionsUsed = actionsUsed,
            replanRecommended = true,
            failureLayer = "verification",
            failureReason = "missing_terminal_proof"
        )
    }

    private fun executeStep(
        step: JSONObject,
        screenBefore: JSONObject,
        confirmed: Boolean,
        remainingBudget: Int
    ): JSONObject {
        val action = normalizedAction(step)

        return when (action) {
            "open_settings" -> executeDirectAction(
                step, screenBefore, action,
                call = { gateway.openSettings(step.optString("section")) }
            )

            "open_app" -> executeDirectAction(
                step, screenBefore, action,
                call = { gateway.openApp(step.optString("name")) }
            )

            "open_app_info" -> executeDirectAction(
                step, screenBefore, action,
                call = { gateway.openAppInfo(step.optString("name")) }
            )

            "open_app_settings" -> executeDirectAction(
                step, screenBefore, action,
                call = {
                    gateway.openAppSettings(
                        step.optString("name"),
                        step.optString("section", "info")
                    )
                }
            )

            "change_volume" -> executeDirectAction(
                step, screenBefore, action,
                call = { gateway.changeVolume(step.optString("volume_action")) },
                screenChangeRequired = false
            )

            "click_any" -> executeClickAny(step, screenBefore, confirmed, remainingBudget)
            "input_text" -> executeInputText(step, screenBefore, confirmed)
            "scroll" -> executeScroll(step, screenBefore)

            "back" -> wrapSemanticAction(
                before = screenBefore,
                result = screenIntelligence.pressBack(),
                actionName = action,
                progressRequired = false
            )

            "home" -> wrapSemanticAction(
                before = screenBefore,
                result = screenIntelligence.pressHome(),
                actionName = action,
                progressRequired = false
            )

            "verify" -> executeVerify(step, screenBefore)

            else -> JSONObject()
                .put("success", false)
                .put("verified", false)
                .put("progress", false)
                .put("actions_used", 0)
                .put("status", "unsupported_action")
                .put("reason", "unsupported_action")
                .put("terminal_status", "UNSUPPORTED")
                .put("failure_layer", "executor")
                .put("screen", screenBefore)
                .put("message", "Неизвестное действие плана: $action")
        }
    }

    private fun executeDirectAction(
        step: JSONObject,
        screenBefore: JSONObject,
        actionName: String,
        call: () -> JSONObject,
        screenChangeRequired: Boolean = true
    ): JSONObject {
        if (isCancelled()) return cancelledStep(screenBefore)

        val raw = try {
            call()
        } catch (error: Exception) {
            return JSONObject()
                .put("success", false)
                .put("verified", false)
                .put("progress", false)
                .put("actions_used", 1)
                .put("status", "direct_action_exception")
                .put("reason", "direct_action_exception")
                .put("terminal_status", "ERROR")
                .put("failure_layer", "executor")
                .put("screen", screenBefore)
                .put("message", error.message ?: "Ошибка $actionName")
        }

        if (raw.optBoolean("requires_confirmation", false)) {
            return raw
                .put("success", false)
                .put("verified", false)
                .put("progress", false)
                .put("actions_used", 1)
                .put("failure_layer", "safety_confirmation")
                .put("screen", screenBefore)
        }

        val gatewayVerified = raw.optBoolean("verified", false)
        val gatewayScreenChanged = raw.optBoolean("screen_changed", false)
        val gatewayOwnerVerified = raw.optBoolean("settings_owner_verified", false)
        val gatewaySettingsProof =
            actionName == "open_settings" &&
                gatewayVerified &&
                gatewayOwnerVerified

        // A verified Settings navigator already performed its own bounded
        // acquisition + same-context section verification. Do not spend another
        // full ready-screen timeout waiting for a generic fingerprint change that
        // an overlay/split pane may legitimately hide. Other actions remain on
        // the existing v5.3 wait contract.
        val screenAfter = awaitReadyScreen(
            screenBefore = screenBefore,
            screenChangeRequired = screenChangeRequired && !gatewaySettingsProof,
            expectedStep = step
        )

        if (isCancelled()) return cancelledStep(screenAfter)

        val semanticChanged = !sameScreen(screenBefore, screenAfter)
        val accepted = raw.optBoolean("success", false)
        val expected = verifyExpectedScreen(step, screenAfter)
        val packageProof = verifyDirectPackage(
            step = step,
            raw = raw,
            screenBefore = screenBefore,
            screenAfter = screenAfter,
            actionName = actionName
        )

        // v5.4 SETTINGS HANDOFF. A Settings navigator may prove the exact final
        // section from same-window semantic evidence even when the generic screen
        // fingerprint remains unchanged under AYANA's overlay. This proof is only
        // accepted for open_settings and only when the gateway also proved the
        // factual com.android.settings owner. Other direct actions keep v5.3 truth.
        val gatewayContractOk =
            actionName != "open_settings" ||
                gatewaySettingsProof

        // A fresh exact target-package ownership/window event, a factual gateway
        // screen-change proof, or a verified Settings final-state handoff counts
        // as observable transition/final-state evidence. Mere dispatch never does.
        val observedTransition =
            semanticChanged ||
                packageProof.transitionObserved ||
                (gatewayVerified && gatewayScreenChanged) ||
                gatewaySettingsProof

        val verified =
            accepted &&
                gatewayContractOk &&
                packageProof.verified &&
                when {
                    expected != null && screenChangeRequired -> expected && observedTransition
                    expected != null -> expected
                    screenChangeRequired -> observedTransition
                    else -> true
                }

        return raw
            .put("success", verified)
            .put("verified", verified)
            .put("action_accepted", accepted)
            .put("progress", observedTransition || (verified && !screenChangeRequired))
            .put("screen_changed", semanticChanged || (gatewayVerified && gatewayScreenChanged))
            .put("gateway_verified", gatewayVerified)
            .put("gateway_settings_proof", gatewaySettingsProof)
            .put("foreground_transition_observed", packageProof.transitionObserved)
            .put("package_verified", packageProof.verified)
            .put("package_verification_source", packageProof.source)
            .put("expected_package", packageProof.expectedPackage)
            .put("observed_package", packageProof.observedPackage)
            .put("event_package", packageProof.eventPackage)
            .put("event_age_ms", packageProof.eventAgeMs)
            .put("foreground_owner_package", packageProof.ownerPackage)
            .put("foreground_owner_age_ms", packageProof.ownerAgeMs)
            .put("actions_used", 1)
            .put("action", actionName)
            .put("status", if (verified) "direct_action_verified" else "direct_action_not_verified")
            .put("reason", if (verified) "direct_action_verified" else "direct_action_not_verified")
            .put("terminal_status", if (verified) "SUCCESS" else "ERROR")
            .put("failure_layer", if (verified) "" else "verification")
            .put("screen", screenAfter)
    }

    private fun executeClickAny(
        step: JSONObject,
        initialScreen: JSONObject,
        confirmed: Boolean,
        remainingBudget: Int
    ): JSONObject {
        val targets = jsonStringList(step.optJSONArray("targets"))
        if (targets.isEmpty()) {
            return JSONObject()
                .put("success", false)
                .put("verified", false)
                .put("progress", false)
                .put("actions_used", 0)
                .put("status", "invalid_target")
                .put("reason", "empty_targets")
                .put("terminal_status", "ERROR")
                .put("failure_layer", "target_resolver")
                .put("screen", initialScreen)
                .put("message", "Шаг click_any не содержит целей")
        }

        val maxScrolls = if (step.optBoolean("scroll_if_missing", false)) {
            step.optInt("max_scrolls", DEFAULT_SCROLL_SEARCH_BUDGET)
                .coerceIn(0, MAX_SCROLLS_PER_STEP)
        } else {
            0
        }

        val requestedScrollDirection = step.optString("scroll_direction", "auto")
            .trim()
            .lowercase(Locale.ROOT)
        val autoScrollSearch = requestedScrollDirection !in setOf("up", "down")
        var activeScrollDirection = if (requestedScrollDirection == "up") "up" else "down"
        var directionReversed = false

        var currentScreen = initialScreen
        var actionsUsed = 0
        var anyProgress = false
        val seenViewportFingerprintsByDirection = linkedMapOf(
            "down" to linkedSetOf<String>(),
            "up" to linkedSetOf<String>()
        )

        for (pass in 0..maxScrolls) {
            if (isCancelled()) {
                return cancelledStep(currentScreen).put("actions_used", actionsUsed)
            }

            val resolution = targetResolver.resolveAny(
                screen = currentScreen,
                requestedTargets = targets,
                mode = AyanaSemanticTargetResolver.Mode.CLICK
            )

            if (resolution.optBoolean("resolved", false)) {
                if (actionsUsed >= remainingBudget) {
                    return JSONObject()
                        .put("success", false)
                        .put("verified", false)
                        .put("progress", anyProgress)
                        .put("actions_used", actionsUsed)
                        .put("status", "action_budget_exhausted")
                        .put("reason", "action_budget_exhausted")
                        .put("terminal_status", "BLOCKED")
                        .put("failure_layer", "action_budget")
                        .put("screen", currentScreen)
                        .put("message", "Не осталось локального бюджета для нажатия цели")
                }

                val requested = resolution.optString("resolved_requested")
                    .ifBlank { resolution.optString("requested") }
                val concrete = resolution.optString("action_target")
                    .ifBlank { requested }

                val clickResult = screenIntelligence.click(
                    target = concrete,
                    confirmed = confirmed
                )
                actionsUsed++

                val screenAfter = clickResult.optJSONObject("screen") ?: safeScreenState()
                val changed = clickResult.optBoolean("screen_changed", false) ||
                    !sameScreen(currentScreen, screenAfter)
                val accepted = clickResult.optBoolean("success", false)
                val expected = verifyExpectedScreen(step, screenAfter)
                val requireScreenChange = step.optBoolean("require_screen_change", true)

                val verified =
                    accepted &&
                        when {
                            expected != null && requireScreenChange -> expected && changed
                            expected != null -> expected
                            requireScreenChange -> changed
                            else -> true
                        }

                return clickResult
                    .put("success", verified)
                    .put("verified", verified)
                    .put("progress", changed)
                    .put(
                        "clicked_target",
                        resolution.optJSONObject("candidate")
                            ?.optString("text")
                            .orEmpty()
                            .ifBlank { concrete }
                    )
                    .put("requested_target", requested)
                    .put("resolved_click_target", concrete)
                    .put("resolver_score", resolution.optInt("score", 0))
                    .put("target_resolution", resolution)
                    .put("actions_used", actionsUsed)
                    .put(
                        "status",
                        if (verified) "click_target_verified"
                        else clickResult.optString("status", "click_target_not_verified")
                    )
                    .put(
                        "reason",
                        if (verified) "click_target_verified"
                        else clickResult.optString("reason", "click_target_not_verified")
                    )
                    .put(
                        "failure_layer",
                        if (verified) ""
                        else clickResult.optString("failure_layer", "verification")
                    )
                    .put("screen", screenAfter)
            }

            val resolutionStatus = resolution.optString("status")
            if (resolutionStatus.startsWith("ambiguous") || resolutionStatus == "snapshot_unavailable") {
                return JSONObject()
                    .put("success", false)
                    .put("verified", false)
                    .put("progress", anyProgress)
                    .put("actions_used", actionsUsed)
                    .put("status", resolutionStatus)
                    .put("reason", resolution.optString("reason", resolutionStatus))
                    .put(
                        "terminal_status",
                        if (resolutionStatus == "snapshot_unavailable") "UNSUPPORTED" else "BLOCKED"
                    )
                    .put("failure_layer", "target_resolver")
                    .put("target_resolution", resolution)
                    .put("screen", currentScreen)
                    .put("message", resolution.optString("message", "Цель нельзя однозначно определить"))
            }

            if (pass < maxScrolls && actionsUsed < remainingBudget) {
                val beforeScroll = currentScreen
                val seenInDirection =
                    seenViewportFingerprintsByDirection.getOrPut(activeScrollDirection) {
                        linkedSetOf()
                    }
                val beforeFingerprint = viewportFingerprint(beforeScroll)
                if (beforeFingerprint.isNotBlank()) {
                    seenInDirection.add(beforeFingerprint)
                }

                val scrollResult = screenIntelligence.scroll(activeScrollDirection)
                actionsUsed++
                currentScreen = scrollResult.optJSONObject("screen") ?: safeScreenState()
                val scrollVerified = scrollResult.optBoolean("success", false)
                val changed = scrollResult.optBoolean("screen_changed", false) ||
                    !sameScreen(beforeScroll, currentScreen)
                val afterFingerprint = viewportFingerprint(currentScreen)
                val lowLevelViewportProof =
                    scrollResult.optBoolean("viewport_changed", false) ||
                        scrollResult.optBoolean("scroll_event_observed", false)

                val cycled =
                    scrollVerified &&
                        changed &&
                        !lowLevelViewportProof &&
                        afterFingerprint.isNotBlank() &&
                        afterFingerprint in seenInDirection

                if (scrollVerified && changed && !cycled) {
                    anyProgress = true
                    if (afterFingerprint.isNotBlank()) {
                        seenInDirection.add(afterFingerprint)
                    }
                } else if (autoScrollSearch && !directionReversed) {
                    activeScrollDirection = oppositeScrollDirection(activeScrollDirection)
                    directionReversed = true
                } else {
                    return scrollResult
                        .put("success", false)
                        .put("verified", false)
                        .put("progress", anyProgress)
                        .put("actions_used", actionsUsed)
                        .put(
                            "status",
                            if (cycled) "scroll_cycle_detected" else "scroll_search_boundary_reached"
                        )
                        .put(
                            "reason",
                            if (cycled) "scroll_cycle_detected" else "scroll_search_boundary_reached"
                        )
                        .put("terminal_status", "BLOCKED")
                        .put("failure_layer", "recovery")
                        .put("scroll_direction", activeScrollDirection)
                        .put("scroll_direction_reversed", directionReversed)
                        .put("screen", currentScreen)
                }
            }
        }

        return JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("progress", anyProgress)
            .put("actions_used", actionsUsed)
            .put("status", "target_not_found")
            .put("reason", "target_not_found")
            .put("terminal_status", "BLOCKED")
            .put("failure_layer", "target_resolver")
            .put("screen", currentScreen)
            .put("message", "Ни один целевой элемент не найден на текущем экране")
    }

    private fun executeInputText(
        step: JSONObject,
        screenBefore: JSONObject,
        confirmed: Boolean
    ): JSONObject {
        if (step.optBoolean("sensitive", false) && !confirmed) {
            return confirmationRequired(step, screenBefore)
        }

        val result = screenIntelligence.inputText(
            target = step.optString("target").takeIf { it.isNotBlank() },
            text = step.optString("text")
        )

        return wrapSemanticAction(
            before = screenBefore,
            result = result,
            actionName = "input_text",
            progressRequired = false
        )
    }

    private fun executeScroll(
        step: JSONObject,
        screenBefore: JSONObject
    ): JSONObject = wrapSemanticAction(
        before = screenBefore,
        result = screenIntelligence.scroll(step.optString("direction", "down")),
        actionName = "scroll",
        progressRequired = true
    )

    private fun executeVerify(
        step: JSONObject,
        screen: JSONObject
    ): JSONObject {
        val verified = verifyExpectedScreen(step, screen) ?: false
        return JSONObject()
            .put("success", verified)
            .put("verified", verified)
            .put("progress", verified)
            .put("actions_used", 0)
            .put("status", if (verified) "verification_satisfied" else "verification_failed")
            .put("reason", if (verified) "verification_satisfied" else "verification_failed")
            .put("terminal_status", if (verified) "SUCCESS" else "BLOCKED")
            .put("failure_layer", if (verified) "" else "verification")
            .put("screen", screen)
            .put(
                "message",
                if (verified) "Конечное состояние подтверждено"
                else "Ожидаемые признаки экрана не подтверждены доступным содержимым"
            )
    }

    private fun wrapSemanticAction(
        before: JSONObject,
        result: JSONObject,
        actionName: String,
        progressRequired: Boolean
    ): JSONObject {
        if (result.optBoolean("requires_confirmation", false)) {
            return result
                .put("success", false)
                .put("verified", false)
                .put("progress", false)
                .put("actions_used", 0)
                .put("failure_layer", result.optString("failure_layer", "safety_confirmation"))
                .put("screen", result.optJSONObject("screen") ?: before)
        }

        val after = result.optJSONObject("screen") ?: safeScreenState()
        val changed = result.optBoolean("screen_changed", false) || !sameScreen(before, after)
        val acceptedAndVerified = result.optBoolean("success", false)
        val success = acceptedAndVerified && (!progressRequired || changed)

        return result
            .put("success", success)
            .put("verified", success)
            .put("progress", changed)
            .put("screen_changed", changed)
            .put("actions_used", 1)
            .put("action", actionName)
            .put(
                "failure_layer",
                if (success) "" else result.optString("failure_layer", "verification")
            )
            .put("screen", after)
    }

    private fun verifyTerminalStep(
        step: JSONObject,
        stepResult: JSONObject,
        screen: JSONObject
    ): Boolean {
        if (!stepResult.optBoolean("success", false)) return false

        val expected = verifyExpectedScreen(step, screen)
        val requireScreenChange = step.optBoolean(
            "require_screen_change",
            normalizedAction(step) == "click_any"
        )
        val progress = stepResult.optBoolean("progress", false)

        return when {
            expected != null && requireScreenChange -> expected && progress
            expected != null -> expected
            requireScreenChange -> progress
            else -> stepResult.optBoolean("verified", stepResult.optBoolean("success", false))
        }
    }

    private fun verifyExpectedScreen(
        step: JSONObject,
        screen: JSONObject
    ): Boolean? {
        val expectAny = jsonStringList(step.optJSONArray("expect_any"))
        val expectAll = jsonStringList(step.optJSONArray("expect_all"))
        val expectNone = jsonStringList(step.optJSONArray("expect_none"))
        val expectPackage = step.optString("expect_package").trim()

        if (
            expectAny.isEmpty() &&
            expectAll.isEmpty() &&
            expectNone.isEmpty() &&
            expectPackage.isBlank()
        ) {
            return null
        }

        if (!screen.optBoolean("snapshot_success", screen.optBoolean("success", false))) {
            return false
        }

        val textCorpus = verificationWindowTexts(screen)
            .joinToString(" | ")
            .let(::normalize)

        val anyOk = expectAny.isEmpty() || expectAny.any { textCorpus.contains(normalize(it)) }
        val allOk = expectAll.all { textCorpus.contains(normalize(it)) }
        val noneOk = expectNone.none { textCorpus.contains(normalize(it)) }
        val packageOk = expectPackage.isBlank() ||
            normalize(screen.optString("package")) == normalize(expectPackage) ||
            jsonStringList(screen.optJSONArray("packages")).any {
                normalize(it) == normalize(expectPackage)
            }

        return anyOk && allOk && noneOk && packageOk
    }

    private fun verificationWindowTexts(screen: JSONObject): List<String> {
        val result = mutableListOf<String>()
        val windows = screen.optJSONArray("windows")

        if (windows != null) {
            for (index in 0 until windows.length()) {
                val window = windows.optJSONObject(index) ?: continue
                if (!window.optBoolean("interaction_context", false)) continue

                val text = window.optString("verification_text").trim()
                if (text.isNotBlank()) result += text
                appendStrings(window.optJSONArray("visible_text"), result)
            }
        }

        if (result.isNotEmpty()) return result.distinct()

        val verification = screen.optString("verification_text").trim()
        if (verification.isNotBlank()) result += verification
        appendStrings(screen.optJSONArray("visible_text"), result)

        if (
            result.isNotEmpty() ||
            (windows != null && screen.optString("window_context_mode").isNotBlank())
        ) {
            return result.distinct()
        }

        appendStrings(screen.optJSONArray("all_visible_text"), result)
        return result.distinct()
    }

    private fun awaitReadyScreen(
        screenBefore: JSONObject,
        screenChangeRequired: Boolean,
        expectedStep: JSONObject? = null
    ): JSONObject {
        val deadline = System.currentTimeMillis() + DIRECT_ACTION_READY_TIMEOUT_MS
        var latest = safeScreenState()

        while (System.currentTimeMillis() < deadline && !isCancelled()) {
            val ready = latest.optBoolean(
                "snapshot_success",
                latest.optBoolean("success", false)
            ) &&
                (latest.optInt("node_count", 0) > 0 || latest.optJSONArray("visible_text") != null)
            val changed = !sameScreen(screenBefore, latest)
            val expected = expectedStep?.let { verifyExpectedScreen(it, latest) }
            val expectationReady = expected != false

            if (ready && expectationReady && (!screenChangeRequired || changed)) {
                return latest
            }

            try {
                Thread.sleep(DIRECT_ACTION_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            latest = safeScreenState()
        }

        return latest
    }

    /**
     * Package verification for direct actions.
     *
     * For explicit expect_package outside open_app, preserve v5.2 compatibility.
     * For open_app, broad packages[] membership is deliberately insufficient: a
     * sibling/background/overlay window can expose a package without owning the
     * foreground. Exact launch verification therefore uses foreground-grade proof.
     */
    private fun verifyDirectPackage(
        step: JSONObject,
        raw: JSONObject,
        screenBefore: JSONObject,
        screenAfter: JSONObject,
        actionName: String
    ): PackageProof {
        val explicitExpected = step.optString("expect_package").trim()

        if (actionName != "open_app" && explicitExpected.isNotBlank()) {
            val direct = normalize(screenAfter.optString("package")) == normalize(explicitExpected)
            val listed = jsonStringList(screenAfter.optJSONArray("packages")).any {
                normalize(it) == normalize(explicitExpected)
            }
            return PackageProof(
                verified = direct || listed,
                source = when {
                    direct -> "explicit_primary_package"
                    listed -> "explicit_package_set"
                    else -> "explicit_package_not_proven"
                },
                expectedPackage = explicitExpected,
                observedPackage = screenAfter.optString("package"),
                eventPackage = screenAfter.optString("event_package"),
                eventAgeMs = screenAfter.optLong("event_age_ms", -1L),
                ownerPackage = screenAfter.optString("foreground_owner_package"),
                ownerAgeMs = screenAfter.optLong("foreground_owner_age_ms", -1L),
                transitionObserved = false
            )
        }

        if (actionName == "open_settings") {
            return foregroundPackageProof(
                expectedPackage = SETTINGS_PACKAGE,
                screenBefore = screenBefore,
                screenAfter = screenAfter
            )
        }

        if (actionName == "open_app") {
            val resolvedPackage = raw.optString("package").trim()
            val expectedPackage = explicitExpected.ifBlank { resolvedPackage }
            if (expectedPackage.isNotBlank()) {
                return foregroundPackageProof(
                    expectedPackage = expectedPackage,
                    screenBefore = screenBefore,
                    screenAfter = screenAfter
                )
            }
        }

        return PackageProof(
            verified = true,
            source = "package_not_required",
            expectedPackage = explicitExpected,
            observedPackage = screenAfter.optString("package"),
            eventPackage = screenAfter.optString("event_package"),
            eventAgeMs = screenAfter.optLong("event_age_ms", -1L),
            ownerPackage = screenAfter.optString("foreground_owner_package"),
            ownerAgeMs = screenAfter.optLong("foreground_owner_age_ms", -1L)
        )
    }

    private fun foregroundPackageProof(
        expectedPackage: String,
        screenBefore: JSONObject,
        screenAfter: JSONObject
    ): PackageProof {
        val expected = normalize(expectedPackage)
        val primary = screenAfter.optString("package").trim()
        val interaction = screenAfter.optString("interaction_package").trim()
        val eventPackage = screenAfter.optString("event_package").trim()
        val eventAge = screenAfter.optLong("event_age_ms", -1L)
        val ownerPackage = screenAfter.optString("foreground_owner_package").trim()
        val ownerAge = screenAfter.optLong("foreground_owner_age_ms", -1L)

        if (normalize(primary) == expected) {
            return PackageProof(
                verified = true,
                source = "primary_package",
                expectedPackage = expectedPackage,
                observedPackage = primary,
                eventPackage = eventPackage,
                eventAgeMs = eventAge,
                ownerPackage = ownerPackage,
                ownerAgeMs = ownerAge,
                transitionObserved = true
            )
        }

        if (normalize(interaction) == expected) {
            return PackageProof(
                verified = true,
                source = "interaction_package",
                expectedPackage = expectedPackage,
                observedPackage = interaction,
                eventPackage = eventPackage,
                eventAgeMs = eventAge,
                ownerPackage = ownerPackage,
                ownerAgeMs = ownerAge,
                transitionObserved = true
            )
        }

        val activeWindowProof = findForegroundWindowProof(screenAfter, expectedPackage)
        if (activeWindowProof != null) {
            return PackageProof(
                verified = true,
                source = activeWindowProof,
                expectedPackage = expectedPackage,
                observedPackage = primary,
                eventPackage = eventPackage,
                eventAgeMs = eventAge,
                ownerPackage = ownerPackage,
                ownerAgeMs = ownerAge,
                transitionObserved = true
            )
        }

        val beforeOwner = screenBefore.optString("foreground_owner_package").trim()
        val beforeOwnerAge = screenBefore.optLong("foreground_owner_age_ms", -1L)
        val ownerNewOrRefreshed = signalIsNewOrRefreshed(
            expectedPackage = expectedPackage,
            beforePackage = beforeOwner,
            beforeAgeMs = beforeOwnerAge,
            afterPackage = ownerPackage,
            afterAgeMs = ownerAge,
            maxFreshAgeMs = FOREGROUND_OWNER_PROOF_MAX_AGE_MS
        )

        if (ownerNewOrRefreshed) {
            return PackageProof(
                verified = true,
                source = "fresh_foreground_owner",
                expectedPackage = expectedPackage,
                observedPackage = primary,
                eventPackage = eventPackage,
                eventAgeMs = eventAge,
                ownerPackage = ownerPackage,
                ownerAgeMs = ownerAge,
                transitionObserved = true
            )
        }

        return PackageProof(
            verified = false,
            source = "foreground_package_not_proven",
            expectedPackage = expectedPackage,
            observedPackage = primary,
            eventPackage = eventPackage,
            eventAgeMs = eventAge,
            ownerPackage = ownerPackage,
            ownerAgeMs = ownerAge,
            transitionObserved = false
        )
    }

    private fun findForegroundWindowProof(
        screen: JSONObject,
        expectedPackage: String
    ): String? {
        val expected = normalize(expectedPackage)
        val windows = screen.optJSONArray("windows") ?: return null

        for (index in 0 until windows.length()) {
            val window = windows.optJSONObject(index) ?: continue
            if (normalize(window.optString("package")) != expected) continue

            val typeName = normalize(window.optString("type_name"))
            val applicationLike =
                typeName.isBlank() ||
                    typeName.contains("application") ||
                    window.optInt("type", -1) == 1

            if (!applicationLike) continue

            if (window.optBoolean("focused", false)) {
                return "focused_application_window"
            }
            if (window.optBoolean("active", false)) {
                return "active_application_window"
            }
            if (window.optBoolean("interaction_context", false)) {
                return "interaction_application_window"
            }
        }

        return null
    }

    private fun signalIsNewOrRefreshed(
        expectedPackage: String,
        beforePackage: String,
        beforeAgeMs: Long,
        afterPackage: String,
        afterAgeMs: Long,
        maxFreshAgeMs: Long
    ): Boolean {
        if (normalize(afterPackage) != normalize(expectedPackage)) return false
        if (afterAgeMs !in 0L..maxFreshAgeMs) return false

        // Different package before => the target signal necessarily belongs to
        // the post-dispatch transition. If the same package existed before, only
        // accept a clear age reset; otherwise a stale signal could fake SUCCESS.
        if (normalize(beforePackage) != normalize(expectedPackage)) return true
        if (beforeAgeMs < 0L) return true

        return afterAgeMs + SIGNAL_REFRESH_MIN_DELTA_MS < beforeAgeMs
    }

    private fun confirmationRequired(
        step: JSONObject,
        screen: JSONObject
    ): JSONObject = JSONObject()
        .put("success", false)
        .put("verified", false)
        .put("progress", false)
        .put("requires_confirmation", true)
        .put("actions_used", 0)
        .put("status", "confirmation_required")
        .put("reason", "confirmation_required")
        .put("terminal_status", "BLOCKED")
        .put("failure_layer", "safety_confirmation")
        .put("screen", screen)
        .put("message", step.optString("confirmation_message", "Чувствительное действие требует подтверждения"))

    private fun stepRequiresConfirmation(step: JSONObject): Boolean {
        if (step.optBoolean("sensitive", false)) return true
        if (normalizedAction(step) != "click_any") return false
        return jsonStringList(step.optJSONArray("targets")).any(::isStateChangingTarget)
    }

    private fun isStateChangingTarget(value: String): Boolean {
        val normalized = normalize(value)
        return STATE_CHANGING_MARKERS.any { normalized.contains(it) }
    }

    private fun stepRequiresObservableProgress(action: String): Boolean =
        action in setOf(
            "open_settings",
            "open_app",
            "open_app_info",
            "open_app_settings",
            "click_any",
            "scroll"
        )

    private fun shouldRecommendReplan(result: JSONObject): Boolean {
        if (result.optBoolean("requires_confirmation", false)) return false
        val status = result.optString("status")
        val terminal = result.optString("terminal_status").uppercase(Locale.ROOT)
        if (terminal == "UNSUPPORTED") return false
        if (status in setOf("ambiguous_target", "ambiguous_editable_target", "secret_input_blocked")) {
            return false
        }
        return true
    }

    private fun failureLayerFor(action: String, result: JSONObject): String {
        val status = result.optString("status")
        return when {
            status.contains("target") || status.contains("editable") -> "target_resolver"
            status.contains("verification") || status.contains("unverified") -> "verification"
            status.contains("snapshot") || status.contains("content_unavailable") -> "screen_acquisition"
            action == "verify" -> "verification"
            else -> "executor"
        }
    }

    private fun normalizedAction(step: JSONObject): String =
        step.optString("action").trim().lowercase(Locale.ROOT)

    private fun minimumActionCost(action: String): Int = if (action == "verify") 0 else 1

    private fun safeScreenState(): JSONObject = try {
        screenIntelligence.getScreenState()
    } catch (error: Exception) {
        JSONObject()
            .put("success", false)
            .put("snapshot_success", false)
            .put("primary_content_state", "unavailable")
            .put("message", error.message ?: "Не удалось прочитать экран")
    }

    private fun isCancelled(): Boolean = try {
        shouldCancel()
    } catch (_: Exception) {
        false
    }

    private fun cancelledStep(screen: JSONObject): JSONObject = JSONObject()
        .put("success", false)
        .put("verified", false)
        .put("progress", false)
        .put("status", "cancelled")
        .put("reason", "cancelled")
        .put("terminal_status", "CANCELLED")
        .put("failure_layer", "cancellation")
        .put("actions_used", 0)
        .put("screen", screen)
        .put("message", "Команда остановлена пользователем")

    private fun engineCancelled(
        goal: String,
        trace: JSONArray,
        screen: JSONObject,
        actionsUsed: Int
    ): JSONObject = JSONObject()
        .put("success", false)
        .put("status", "cancelled")
        .put("terminal_status", "CANCELLED")
        .put("goal", goal)
        .put("message", "Команда остановлена пользователем")
        .put("failure_layer", "cancellation")
        .put("reason", "cancelled")
        .put("actions_used", actionsUsed)
        .put("replan_recommended", false)
        .put("trace", trace)
        .put("screen", screen)

    private fun engineSuccess(
        goal: String,
        message: String,
        trace: JSONArray,
        screen: JSONObject,
        actionsUsed: Int,
        terminalEvidence: JSONObject
    ): JSONObject = JSONObject()
        .put("success", true)
        .put("status", "success")
        .put("terminal_status", "SUCCESS")
        .put("goal", goal)
        .put("message", message)
        .put("actions_used", actionsUsed)
        .put("replan_recommended", false)
        .put("verified", true)
        .put("terminal_evidence", terminalEvidence)
        .put("trace", trace)
        .put("screen", screen)

    private fun engineStepFailure(
        goal: String,
        result: JSONObject,
        trace: JSONArray,
        screen: JSONObject,
        actionsUsed: Int,
        action: String
    ): JSONObject {
        val message = result.optString("message", "Шаг не выполнен")
        val failureLayer = result.optString("failure_layer", failureLayerFor(action, result))
        val failureReason = result.optString("reason", result.optString("status", "action_failed"))
        val terminal = result.optString("terminal_status").trim().uppercase(Locale.ROOT)
        val replan = shouldRecommendReplan(result)

        return when (terminal) {
            "UNSUPPORTED" -> JSONObject()
                .put("success", false)
                .put("status", "unsupported")
                .put("terminal_status", "UNSUPPORTED")
                .put("goal", goal)
                .put("message", message)
                .put("failure_layer", failureLayer)
                .put("reason", failureReason)
                .put("actions_used", actionsUsed)
                .put("replan_recommended", false)
                .put("trace", trace)
                .put("screen", screen)

            "ERROR" -> JSONObject()
                .put("success", false)
                .put("status", "error")
                .put("terminal_status", "ERROR")
                .put("goal", goal)
                .put("message", message)
                .put("failure_layer", failureLayer)
                .put("reason", failureReason)
                .put("actions_used", actionsUsed)
                .put("replan_recommended", replan)
                .put("trace", trace)
                .put("screen", screen)

            "CANCELLED" -> engineCancelled(goal, trace, screen, actionsUsed)

            else -> engineBlocked(
                goal = goal,
                reason = message,
                trace = trace,
                screen = screen,
                actionsUsed = actionsUsed,
                replanRecommended = replan,
                failureLayer = failureLayer,
                failureReason = failureReason
            )
        }
    }

    private fun engineBlocked(
        goal: String,
        reason: String,
        trace: JSONArray,
        screen: JSONObject,
        actionsUsed: Int,
        replanRecommended: Boolean,
        checkpointFailed: Boolean = false,
        failureLayer: String = "executor",
        failureReason: String = reason
    ): JSONObject = JSONObject()
        .put("success", false)
        .put("status", "blocked")
        .put("terminal_status", "BLOCKED")
        .put("goal", goal)
        .put("message", reason)
        .put("failure_layer", failureLayer)
        .put("reason", failureReason)
        .put("actions_used", actionsUsed)
        .put("replan_recommended", replanRecommended)
        .put("checkpoint_failed", checkpointFailed)
        .put("trace", trace)
        .put("screen", screen)

    private fun engineFailure(
        goal: String,
        reason: String,
        trace: JSONArray = JSONArray(),
        screen: JSONObject = JSONObject(),
        actionsUsed: Int = 0
    ): JSONObject = JSONObject()
        .put("success", false)
        .put("status", "invalid_plan")
        .put("terminal_status", "ERROR")
        .put("goal", goal)
        .put("message", reason)
        .put("failure_layer", "goal_compiler")
        .put("reason", "invalid_plan")
        .put("actions_used", actionsUsed)
        .put("replan_recommended", false)
        .put("trace", trace)
        .put("screen", screen)

    private fun emitCheckpoint(
        callback: ((JSONObject) -> Boolean)?,
        checkpoint: String,
        nextStepIndex: Int,
        actionsUsed: Int,
        step: JSONObject?,
        stepResult: JSONObject?,
        screen: JSONObject,
        inFlight: Boolean
    ): Boolean {
        if (callback == null) return true

        return try {
            callback(
                JSONObject()
                    .put("checkpoint", checkpoint)
                    .put("next_step_index", nextStepIndex.coerceAtLeast(0))
                    .put("actions_used", actionsUsed.coerceAtLeast(0))
                    .put("step_id", step?.optString("id").orEmpty())
                    .put("step_action", step?.optString("action").orEmpty())
                    .put("step_success", stepResult?.optBoolean("success", false) ?: false)
                    .put(
                        "requires_confirmation",
                        stepResult?.optBoolean("requires_confirmation", false) ?: false
                    )
                    .put("in_flight", inFlight)
                    .put("step_status", stepResult?.optString("status").orEmpty())
                    .put("failure_layer", stepResult?.optString("failure_layer").orEmpty())
                    .put("screen_fingerprint", screenFingerprint(screen))
                    .put("screen", screen)
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun traceRecord(
        index: Int,
        step: JSONObject,
        result: JSONObject,
        before: JSONObject,
        after: JSONObject
    ): JSONObject = JSONObject()
        .put("step_index", index)
        .put("step_id", step.optString("id"))
        .put("action", step.optString("action"))
        .put("success", result.optBoolean("success", false))
        .put("verified", result.optBoolean("verified", false))
        .put("progress", result.optBoolean("progress", false))
        .put("status", result.optString("status"))
        .put("reason", result.optString("reason"))
        .put("failure_layer", result.optString("failure_layer"))
        .put("actions_used", result.optInt("actions_used", 0))
        .put("package_verified", result.optBoolean("package_verified", true))
        .put("package_verification_source", result.optString("package_verification_source"))
        .put("expected_package", result.optString("expected_package"))
        .put("observed_package", result.optString("observed_package"))
        .put("before_fingerprint", screenFingerprint(before))
        .put("after_fingerprint", screenFingerprint(after))

    private fun terminalEvidence(
        step: JSONObject,
        result: JSONObject,
        screen: JSONObject
    ): JSONObject = JSONObject()
        .put("step_id", step.optString("id"))
        .put("action", step.optString("action"))
        .put("action_verified", result.optBoolean("verified", result.optBoolean("success", false)))
        .put("screen_changed", result.optBoolean("progress", false))
        .put("explicit_expectation", verifyExpectedScreen(step, screen))
        .put("proof_source", result.optString("package_verification_source").ifBlank {
            result.optString("proof_level", result.optString("status"))
        })
        .put("expected_package", result.optString("expected_package"))
        .put("observed_package", result.optString("observed_package"))
        .put("event_package", result.optString("event_package"))
        .put("event_age_ms", result.optLong("event_age_ms", -1L))
        .put("foreground_owner_package", result.optString("foreground_owner_package"))
        .put("foreground_owner_age_ms", result.optLong("foreground_owner_age_ms", -1L))
        .put("screen_fingerprint", screenFingerprint(screen))

    private fun transitionFingerprint(
        action: String,
        before: JSONObject,
        after: JSONObject
    ): String {
        val first = screenFingerprint(before)
        val second = screenFingerprint(after)
        if (first.isBlank() || second.isBlank()) return ""
        return "$action::$first->$second"
    }

    private fun sameScreen(first: JSONObject, second: JSONObject): Boolean =
        screenFingerprint(first) == screenFingerprint(second)

    private fun screenFingerprint(screen: JSONObject): String {
        val packageName = normalize(screen.optString("package"))
        val rootClass = normalize(screen.optString("root_class"))
        val contextId = normalize(screen.optString("primary_context_id"))
        val verificationText = screen.optString("verification_text").trim()
        val visible = if (verificationText.isNotBlank()) {
            normalize(verificationText)
        } else {
            jsonStringList(screen.optJSONArray("visible_text"))
                .joinToString("|") { normalize(it) }
        }

        return "$packageName::$rootClass::$contextId::$visible"
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun jsonStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotBlank()) result += value
        }
        return result
    }

    private fun appendStrings(array: JSONArray?, target: MutableList<String>) {
        if (array == null) return
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotBlank()) target += value
        }
    }

    private fun oppositeScrollDirection(direction: String): String =
        if (direction.equals("up", ignoreCase = true)) "down" else "up"

    private fun viewportFingerprint(screen: JSONObject): String {
        val packageName = screen.optString("package").trim()
        val contentState = screen.optString(
            "primary_content_state",
            screen.optString("content_status")
        ).trim()
        val nodes = screen.optJSONArray("nodes")
        val nodeText = StringBuilder()

        if (nodes != null) {
            for (index in 0 until nodes.length().coerceAtMost(80)) {
                val node = nodes.optJSONObject(index) ?: continue
                val text = node.optString("text").trim()
                val description = node.optString(
                    "content_description",
                    node.optString("description")
                ).trim()
                val viewId = node.optString("view_id").trim()

                if (text.isNotBlank() || description.isNotBlank() || viewId.isNotBlank()) {
                    nodeText
                        .append(text.lowercase(Locale.ROOT))
                        .append('|')
                        .append(description.lowercase(Locale.ROOT))
                        .append('|')
                        .append(viewId.lowercase(Locale.ROOT))
                        .append(';')
                }
            }
        }

        return "$packageName#$contentState#$nodeText"
    }

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val DEFAULT_MAX_ACTIONS = 8
        private const val HARD_MAX_ACTIONS = 10
        private const val MAX_NO_PROGRESS_STREAK = 2
        private const val DEFAULT_SCROLL_SEARCH_BUDGET = 6
        private const val MAX_SCROLLS_PER_STEP = 6
        private const val MAX_IDENTICAL_TRANSITION_REPEATS = 2
        private const val DIRECT_ACTION_READY_TIMEOUT_MS = 1600L
        private const val DIRECT_ACTION_POLL_MS = 80L

        // Tight enough to reject stale ownership state, wide enough to cover
        // the current 1.6 s direct-action observation loop plus Samsung
        // Accessibility delivery jitter. Generic last-event package data is
        // diagnostic only and is never accepted as foreground proof.
        private const val FOREGROUND_OWNER_PROOF_MAX_AGE_MS = 3200L
        private const val SIGNAL_REFRESH_MIN_DELTA_MS = 120L

        private val STATE_CHANGING_MARKERS = setOf(
            "включить", "выключить", "разрешить", "запретить", "удалить",
            "отправить", "оплатить", "сброс", "стереть", "очистить данные",
            "factory reset", "delete", "send", "pay", "confirm", "подтвердить"
        )
    }
}
