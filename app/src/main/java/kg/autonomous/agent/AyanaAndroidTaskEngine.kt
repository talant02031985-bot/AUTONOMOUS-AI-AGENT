package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA Android Task Engine v3.3 — deterministic local executor with strict terminal verification and text-first semantic clicks.
 *
 * IMPORTANT ARCHITECTURE RULE:
 * The LLM understands the user's intent and produces ONE short structured plan.
 * This class executes that plan locally on Android and verifies progress after
 * every action. It does NOT parse natural-language commands and does NOT contain
 * command-specific shortcuts for YouTube, AYANA AI, Telegram, etc.
 *
 * Expected plan shape:
 * {
 *   "goal": "Open the requested final Android screen",
 *   "max_actions": 8,
 *   "steps": [
 *     {
 *       "id": "open_parent",
 *       "action": "open_settings",
 *       "section": "accessibility"
 *     },
 *     {
 *       "id": "open_group",
 *       "action": "click_any",
 *       "targets": ["Установленные приложения", "Установленные службы"],
 *       "scroll_if_missing": true,
 *       "max_scrolls": 1
 *     },
 *     {
 *       "id": "open_target",
 *       "action": "click_any",
 *       "targets": ["AYANA AI"],
 *       "terminal": true,
 *       "require_screen_change": true
 *     }
 *   ]
 * }
 *
 * The engine is intentionally generic. New user commands should normally require
 * a new plan from Agent Core, not a new Kotlin if/regex branch.
 */
class AyanaAndroidTaskEngine(
    private val screenIntelligence: AyanaScreenIntelligence,
    private val gateway: ActionGateway,
    private val shouldCancel: () -> Boolean = { false }
) {

    private data class ResolvedVisibleTarget(
        val requested: String,
        val clickTarget: String,
        val label: String,
        val score: Int
    )

    interface ActionGateway {

        fun openSettings(
            section: String
        ): JSONObject

        fun openApp(
            name: String
        ): JSONObject

        fun openAppInfo(
            name: String
        ): JSONObject

        fun openAppSettings(
            name: String,
            section: String
        ): JSONObject

        fun changeVolume(
            action: String
        ): JSONObject
    }

    fun execute(
        plan: JSONObject,
        confirmed: Boolean = false
    ): JSONObject {

        val goal =
            plan.optString(
                "goal"
            ).trim()

        if (
            isCancelled()
        ) {
            return engineCancelled(
                goal = goal,
                trace = JSONArray(),
                screen = safeScreenState(),
                actionsUsed = 0
            )
        }

        val steps =
            plan.optJSONArray(
                "steps"
            )
                ?: return engineFailure(
                    goal = goal,
                    reason = "План не содержит steps"
                )

        if (steps.length() == 0) {
            return engineFailure(
                goal = goal,
                reason = "План пуст"
            )
        }

        val maxActions =
            plan.optInt(
                "max_actions",
                DEFAULT_MAX_ACTIONS
            ).coerceIn(
                1,
                HARD_MAX_ACTIONS
            )

        val trace =
            JSONArray()

        var currentScreen =
            safeScreenState()

        var actionsUsed = 0
        var noProgressStreak = 0

        for (index in 0 until steps.length()) {

            if (
                isCancelled()
            ) {
                return engineCancelled(
                    goal = goal,
                    trace = trace,
                    screen = currentScreen,
                    actionsUsed = actionsUsed
                )
            }

            val step =
                steps.optJSONObject(
                    index
                )
                    ?: return engineFailure(
                        goal = goal,
                        reason = "Некорректный шаг #${index + 1}",
                        trace = trace,
                        screen = currentScreen,
                        actionsUsed = actionsUsed
                    )

            if (actionsUsed >= maxActions) {
                return engineBlocked(
                    goal = goal,
                    reason = "Достигнут локальный лимит действий",
                    trace = trace,
                    screen = currentScreen,
                    actionsUsed = actionsUsed,
                    replanRecommended = true
                )
            }

            val remainingBudget =
                maxActions -
                    actionsUsed

            val stepResult =
                executeStep(
                    step = step,
                    screenBefore = currentScreen,
                    confirmed = confirmed,
                    remainingBudget = remainingBudget
                )

            val usedByStep =
                stepResult
                    .optInt(
                        "actions_used",
                        0
                    )
                    .coerceAtLeast(
                        0
                    )

            actionsUsed +=
                usedByStep

            val returnedScreen =
                stepResult
                    .optJSONObject(
                        "screen"
                    )

            if (returnedScreen != null) {
                currentScreen =
                    returnedScreen
            } else {
                currentScreen =
                    safeScreenState()
            }

            trace.put(
                JSONObject()
                    .put(
                        "index",
                        index
                    )
                    .put(
                        "id",
                        step.optString(
                            "id",
                            "step_${index + 1}"
                        )
                    )
                    .put(
                        "action",
                        step.optString(
                            "action"
                        )
                    )
                    .put(
                        "success",
                        stepResult.optBoolean(
                            "success",
                            false
                        )
                    )
                    .put(
                        "progress",
                        stepResult.optBoolean(
                            "progress",
                            false
                        )
                    )
                    .put(
                        "message",
                        stepResult.optString(
                            "message"
                        )
                    )
                    .put(
                        "actions_used",
                        usedByStep
                    )
            )

            if (
                stepResult.optString(
                    "status"
                ) ==
                "cancelled" ||
                isCancelled()
            ) {
                return engineCancelled(
                    goal = goal,
                    trace = trace,
                    screen = currentScreen,
                    actionsUsed = actionsUsed
                )
            }

            if (
                stepResult.optBoolean(
                    "requires_confirmation",
                    false
                )
            ) {
                return JSONObject()
                    .put(
                        "success",
                        false
                    )
                    .put(
                        "status",
                        "needs_confirmation"
                    )
                    .put(
                        "goal",
                        goal
                    )
                    .put(
                        "requires_confirmation",
                        true
                    )
                    .put(
                        "message",
                        stepResult.optString(
                            "message",
                            "Требуется подтверждение пользователя"
                        )
                    )
                    .put(
                        "actions_used",
                        actionsUsed
                    )
                    .put(
                        "trace",
                        trace
                    )
                    .put(
                        "screen",
                        currentScreen
                    )
            }

            val progress =
                stepResult.optBoolean(
                    "progress",
                    false
                )

            if (progress) {
                noProgressStreak = 0
            } else {
                noProgressStreak++
            }

            val stepSuccess =
                stepResult.optBoolean(
                    "success",
                    false
                )

            if (!stepSuccess) {

                if (
                    step.optBoolean(
                        "optional",
                        false
                    )
                ) {
                    continue
                }

                return engineBlocked(
                    goal = goal,
                    reason = stepResult.optString(
                        "message",
                        "Шаг не выполнен"
                    ),
                    trace = trace,
                    screen = currentScreen,
                    actionsUsed = actionsUsed,
                    replanRecommended = true
                )
            }

            if (
                noProgressStreak >=
                MAX_NO_PROGRESS_STREAK
            ) {
                return engineBlocked(
                    goal = goal,
                    reason = "Два последовательных действия не дали наблюдаемого прогресса",
                    trace = trace,
                    screen = currentScreen,
                    actionsUsed = actionsUsed,
                    replanRecommended = true
                )
            }

            val terminal =
                step.optBoolean(
                    "terminal",
                    false
                )

            if (terminal) {

                val terminalVerified =
                    verifyTerminalStep(
                        step = step,
                        stepResult = stepResult,
                        screen = currentScreen
                    )

                if (terminalVerified) {
                    return engineSuccess(
                        goal = goal,
                        message = stepResult.optString(
                            "message",
                            "Цель достигнута"
                        ),
                        trace = trace,
                        screen = currentScreen,
                        actionsUsed = actionsUsed
                    )
                }

                return engineBlocked(
                    goal = goal,
                    reason = "Финальный шаг выполнен, но конечное состояние не подтверждено",
                    trace = trace,
                    screen = currentScreen,
                    actionsUsed = actionsUsed,
                    replanRecommended = true
                )
            }
        }

        return engineSuccess(
            goal = goal,
            message = "Все шаги локального плана выполнены",
            trace = trace,
            screen = currentScreen,
            actionsUsed = actionsUsed
        )
    }

    private fun executeStep(
        step: JSONObject,
        screenBefore: JSONObject,
        confirmed: Boolean,
        remainingBudget: Int
    ): JSONObject {

        val action =
            step.optString(
                "action"
            )
                .trim()
                .lowercase(
                    Locale.ROOT
                )

        return when (action) {

            "open_settings" ->
                executeDirectAction(
                    screenBefore = screenBefore,
                    actionName = action,
                    call = {
                        gateway.openSettings(
                            step.optString(
                                "section"
                            )
                        )
                    }
                )

            "open_app" ->
                executeDirectAction(
                    screenBefore = screenBefore,
                    actionName = action,
                    call = {
                        gateway.openApp(
                            step.optString(
                                "name"
                            )
                        )
                    }
                )

            "open_app_info" ->
                executeDirectAction(
                    screenBefore = screenBefore,
                    actionName = action,
                    call = {
                        gateway.openAppInfo(
                            step.optString(
                                "name"
                            )
                        )
                    }
                )

            "open_app_settings" ->
                executeDirectAction(
                    screenBefore = screenBefore,
                    actionName = action,
                    call = {
                        gateway.openAppSettings(
                            name = step.optString(
                                "name"
                            ),
                            section = step.optString(
                                "section",
                                "info"
                            )
                        )
                    }
                )

            "change_volume" ->
                executeDirectAction(
                    screenBefore = screenBefore,
                    actionName = action,
                    call = {
                        gateway.changeVolume(
                            step.optString(
                                "volume_action"
                            )
                        )
                    },
                    screenChangeRequired = false
                )

            "click_any" ->
                executeClickAny(
                    step = step,
                    initialScreen = screenBefore,
                    confirmed = confirmed,
                    remainingBudget = remainingBudget
                )

            "input_text" ->
                executeInputText(
                    step = step,
                    screenBefore = screenBefore,
                    confirmed = confirmed
                )

            "scroll" ->
                executeScroll(
                    step = step,
                    screenBefore = screenBefore
                )

            "back" ->
                wrapScreenAction(
                    before = screenBefore,
                    result = screenIntelligence.pressBack(),
                    actionName = action
                )

            "home" ->
                wrapScreenAction(
                    before = screenBefore,
                    result = screenIntelligence.pressHome(),
                    actionName = action
                )

            "verify" ->
                executeVerify(
                    step = step,
                    screen = screenBefore
                )

            else ->
                JSONObject()
                    .put(
                        "success",
                        false
                    )
                    .put(
                        "progress",
                        false
                    )
                    .put(
                        "actions_used",
                        0
                    )
                    .put(
                        "screen",
                        screenBefore
                    )
                    .put(
                        "message",
                        "Неизвестное действие плана: $action"
                    )
        }
    }

    private fun executeDirectAction(
        screenBefore: JSONObject,
        actionName: String,
        call: () -> JSONObject,
        screenChangeRequired: Boolean = true
    ): JSONObject {

        if (
            isCancelled()
        ) {
            return cancelledStep(
                screenBefore
            )
        }

        val raw =
            try {
                call()
            } catch (error: Exception) {
                return JSONObject()
                    .put(
                        "success",
                        false
                    )
                    .put(
                        "progress",
                        false
                    )
                    .put(
                        "actions_used",
                        1
                    )
                    .put(
                        "screen",
                        screenBefore
                    )
                    .put(
                        "message",
                        error.message
                            ?: "Ошибка $actionName"
                    )
            }

        if (
            raw.optBoolean(
                "requires_confirmation",
                false
            )
        ) {
            return JSONObject(
                raw.toString()
            )
                .put(
                    "progress",
                    false
                )
                .put(
                    "actions_used",
                    1
                )
                .put(
                    "screen",
                    screenBefore
                )
        }

        val screenAfter =
            awaitReadyScreen(
                screenBefore = screenBefore,
                screenChangeRequired = screenChangeRequired
            )

        if (
            isCancelled()
        ) {
            return cancelledStep(
                screenAfter
            )
        }

        val changed =
            !sameScreen(
                screenBefore,
                screenAfter
            )

        val accepted =
            raw.optBoolean(
                "success",
                false
            )

        val success =
            accepted &&
                (
                    !screenChangeRequired ||
                    changed
                )

        return JSONObject(
            raw.toString()
        )
            .put(
                "success",
                success
            )
            .put(
                "progress",
                changed ||
                    (
                        accepted &&
                        !screenChangeRequired
                    )
            )
            .put(
                "screen_changed",
                changed
            )
            .put(
                "actions_used",
                1
            )
            .put(
                "screen",
                screenAfter
            )
    }

    private fun executeClickAny(
        step: JSONObject,
        initialScreen: JSONObject,
        confirmed: Boolean,
        remainingBudget: Int
    ): JSONObject {

        val targets =
            jsonStringList(
                step.optJSONArray(
                    "targets"
                )
            )
                .filter {
                    it.isNotBlank()
                }

        if (targets.isEmpty()) {
            return JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "progress",
                    false
                )
                .put(
                    "actions_used",
                    0
                )
                .put(
                    "screen",
                    initialScreen
                )
                .put(
                    "message",
                    "click_any не содержит targets"
                )
        }

        val allowOverflow =
            step.optBoolean(
                "allow_overflow",
                false
            )

        val safeTargets =
            targets.filter { target ->
                allowOverflow ||
                    !isVagueOverflowTarget(
                        target
                    )
            }

        if (safeTargets.isEmpty()) {
            return JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "progress",
                    false
                )
                .put(
                    "actions_used",
                    0
                )
                .put(
                    "screen",
                    initialScreen
                )
                .put(
                    "message",
                    "Заблокирован неопределённый overflow/menu target"
                )
        }

        val scrollIfMissing =
            step.optBoolean(
                "scroll_if_missing",
                false
            )

        val requestedMaxScrolls =
            step.optInt(
                "max_scrolls",
                1
            ).coerceIn(
                0,
                MAX_SCROLLS_PER_STEP
            )

        val maxScrolls =
            if (scrollIfMissing) {
                minOf(
                    requestedMaxScrolls,
                    maxOf(
                        0,
                        remainingBudget - 1
                    )
                )
            } else {
                0
            }

        var currentScreen =
            initialScreen

        var actionsUsed = 0
        var anyProgress = false

        repeat(
            maxScrolls + 1
        ) { pass ->

            val visibleTarget =
                resolveVisibleTarget(
                    screen = currentScreen,
                    targets = safeTargets,
                    allowOverflow = allowOverflow
                )

            if (visibleTarget != null) {

                if (
                    (
                        isStateChangingTarget(
                            visibleTarget.requested
                        ) ||
                            isStateChangingTarget(
                                visibleTarget.label
                            )
                        ) &&
                    !confirmed
                ) {
                    return JSONObject()
                        .put(
                            "success",
                            false
                        )
                        .put(
                            "progress",
                            anyProgress
                        )
                        .put(
                            "requires_confirmation",
                            true
                        )
                        .put(
                            "actions_used",
                            actionsUsed
                        )
                        .put(
                            "screen",
                            currentScreen
                        )
                        .put(
                            "message",
                            "Изменение состояния требует подтверждения пользователя"
                        )
                }

                val clickResult =
                    screenIntelligence.click(
                        target = visibleTarget.clickTarget,
                        confirmed = confirmed
                    )

                actionsUsed++

                val screenAfter =
                    clickResult
                        .optJSONObject(
                            "screen"
                        )
                        ?: safeScreenState()

                val changed =
                    clickResult.optBoolean(
                        "screen_changed",
                        false
                    ) ||
                        !sameScreen(
                            currentScreen,
                            screenAfter
                        )

                val accepted =
                    clickResult.optBoolean(
                        "success",
                        false
                    )

                val requireScreenChange =
                    step.optBoolean(
                        "require_screen_change",
                        false
                    )

                val expectedVerified =
                    verifyExpectedScreen(
                        step = step,
                        screen = screenAfter
                    )

                val success =
                    when {
                        expectedVerified != null &&
                            requireScreenChange ->
                            expectedVerified &&
                                changed

                        expectedVerified != null ->
                            expectedVerified

                        requireScreenChange ->
                            changed

                        else ->
                            accepted ||
                                changed
                    }

                return JSONObject(
                    clickResult.toString()
                )
                    .put(
                        "success",
                        success
                    )
                    .put(
                        "progress",
                        changed
                    )
                    .put(
                        "clicked_target",
                        visibleTarget.label
                    )
                    .put(
                        "requested_target",
                        visibleTarget.requested
                    )
                    .put(
                        "resolved_click_target",
                        visibleTarget.clickTarget
                    )
                    .put(
                        "resolver_score",
                        visibleTarget.score
                    )
                    .put(
                        "actions_used",
                        actionsUsed
                    )
                    .put(
                        "screen",
                        screenAfter
                    )
            }

            if (
                pass < maxScrolls &&
                actionsUsed < remainingBudget
            ) {

                val direction =
                    step.optString(
                        "scroll_direction",
                        "down"
                    )

                val beforeScroll =
                    currentScreen

                val scrollResult =
                    screenIntelligence.scroll(
                        direction
                    )

                actionsUsed++

                currentScreen =
                    scrollResult
                        .optJSONObject(
                            "screen"
                        )
                        ?: safeScreenState()

                val changed =
                    scrollResult.optBoolean(
                        "screen_changed",
                        false
                    ) ||
                        !sameScreen(
                            beforeScroll,
                            currentScreen
                        )

                if (changed) {
                    anyProgress = true
                }
            }
        }

        return JSONObject()
            .put(
                "success",
                false
            )
            .put(
                "progress",
                anyProgress
            )
            .put(
                "actions_used",
                actionsUsed
            )
            .put(
                "screen",
                currentScreen
            )
            .put(
                "message",
                "Ни один целевой элемент не найден на текущем экране"
            )
    }

    private fun executeInputText(
        step: JSONObject,
        screenBefore: JSONObject,
        confirmed: Boolean
    ): JSONObject {

        if (
            step.optBoolean(
                "sensitive",
                false
            ) &&
            !confirmed
        ) {
            return JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "progress",
                    false
                )
                .put(
                    "requires_confirmation",
                    true
                )
                .put(
                    "actions_used",
                    0
                )
                .put(
                    "screen",
                    screenBefore
                )
                .put(
                    "message",
                    "Чувствительный ввод требует подтверждения"
                )
        }

        val result =
            screenIntelligence.inputText(
                target = step.optString(
                    "target"
                ).takeIf {
                    it.isNotBlank()
                },
                text = step.optString(
                    "text"
                )
            )

        return wrapScreenAction(
            before = screenBefore,
            result = result,
            actionName = "input_text"
        )
    }

    private fun executeScroll(
        step: JSONObject,
        screenBefore: JSONObject
    ): JSONObject {

        val result =
            screenIntelligence.scroll(
                step.optString(
                    "direction",
                    "down"
                )
            )

        return wrapScreenAction(
            before = screenBefore,
            result = result,
            actionName = "scroll"
        )
    }

    private fun executeVerify(
        step: JSONObject,
        screen: JSONObject
    ): JSONObject {

        val verified =
            verifyExpectedScreen(
                step = step,
                screen = screen
            )
                ?: false

        return JSONObject()
            .put(
                "success",
                verified
            )
            .put(
                "progress",
                verified
            )
            .put(
                "actions_used",
                0
            )
            .put(
                "screen",
                screen
            )
            .put(
                "message",
                if (verified) {
                    "Конечное состояние подтверждено"
                } else {
                    "Ожидаемые признаки экрана не найдены"
                }
            )
    }

    private fun wrapScreenAction(
        before: JSONObject,
        result: JSONObject,
        actionName: String
    ): JSONObject {

        if (
            result.optBoolean(
                "requires_confirmation",
                false
            )
        ) {
            return JSONObject(
                result.toString()
            )
                .put(
                    "progress",
                    false
                )
                .put(
                    "actions_used",
                    1
                )
                .put(
                    "screen",
                    result.optJSONObject(
                        "screen"
                    )
                        ?: before
                )
        }

        val after =
            result
                .optJSONObject(
                    "screen"
                )
                ?: safeScreenState()

        val changed =
            result.optBoolean(
                "screen_changed",
                false
            ) ||
                !sameScreen(
                    before,
                    after
                )

        val accepted =
            result.optBoolean(
                "success",
                false
            )

        return JSONObject(
            result.toString()
        )
            .put(
                "success",
                accepted ||
                    changed
            )
            .put(
                "progress",
                changed
            )
            .put(
                "actions_used",
                1
            )
            .put(
                "action",
                actionName
            )
            .put(
                "screen",
                after
            )
    }

    private fun verifyTerminalStep(
        step: JSONObject,
        stepResult: JSONObject,
        screen: JSONObject
    ): Boolean {

        val expected =
            verifyExpectedScreen(
                step = step,
                screen = screen
            )

        val requireScreenChange =
            step.optBoolean(
                "require_screen_change",
                step.optString(
                    "action"
                ) == "click_any"
            )

        if (expected != null) {
            return if (requireScreenChange) {
                expected &&
                    stepResult.optBoolean(
                        "progress",
                        false
                    )
            } else {
                expected
            }
        }

        return if (requireScreenChange) {
            stepResult.optBoolean(
                "progress",
                false
            )
        } else {
            stepResult.optBoolean(
                "success",
                false
            )
        }
    }

    /**
     * Returns:
     * - true/false when the step defines explicit screen expectations;
     * - null when there are no explicit expectations and normal action semantics
     *   should be used.
     */
    private fun verifyExpectedScreen(
        step: JSONObject,
        screen: JSONObject
    ): Boolean? {

        val expectAny =
            jsonStringList(
                step.optJSONArray(
                    "expect_any"
                )
            )

        val expectAll =
            jsonStringList(
                step.optJSONArray(
                    "expect_all"
                )
            )

        val expectNone =
            jsonStringList(
                step.optJSONArray(
                    "expect_none"
                )
            )

        if (
            expectAny.isEmpty() &&
            expectAll.isEmpty() &&
            expectNone.isEmpty()
        ) {
            return null
        }

        val normalized =
            normalize(
                screen.toString()
            )

        val anyOk =
            expectAny.isEmpty() ||
                expectAny.any { value ->
                    normalized.contains(
                        normalize(
                            value
                        )
                    )
                }

        val allOk =
            expectAll.all { value ->
                normalized.contains(
                    normalize(
                        value
                    )
                )
            }

        val noneOk =
            expectNone.none { value ->
                normalized.contains(
                    normalize(
                        value
                    )
                )
            }

        return anyOk &&
            allOk &&
            noneOk
    }

    /**
     * Resolve a planner phrase to a REAL visible Accessibility node.
     *
     * The planner may say «Использование мобильных данных» while Samsung shows
     * «Мобильные данные», or «приложение браузера» while the row is simply
     * «Браузер». We therefore resolve semantically against structured node data
     * and then click the node's concrete view id/text instead of requiring the
     * planner phrase to appear verbatim on screen.
     *
     * Text matches outrank content descriptions, so a visible settings row wins
     * over a toolbar overflow icon that may share a generic description.
     */
    private fun resolveVisibleTarget(
        screen: JSONObject,
        targets: List<String>,
        allowOverflow: Boolean
    ): ResolvedVisibleTarget? {

        val nodes =
            screen.optJSONArray(
                "nodes"
            )

        var best:
            ResolvedVisibleTarget? =
            null

        if (nodes != null) {

            for (
                index in
                0 until nodes.length()
            ) {

                val node =
                    nodes.optJSONObject(
                        index
                    )
                        ?: continue

                if (
                    !node.optBoolean(
                        "visible",
                        false
                    ) ||
                    !node.optBoolean(
                        "enabled",
                        true
                    )
                ) {
                    continue
                }

                if (
                    !allowOverflow &&
                    isLikelyOverflowNode(
                        node
                    )
                ) {
                    continue
                }

                val text =
                    node.optString(
                        "text"
                    ).trim()

                val description =
                    node.optString(
                        "description"
                    ).trim()

                val viewId =
                    node.optString(
                        "view_id"
                    ).trim()

                for (requested in targets) {

                    val textScore =
                        semanticFieldScore(
                            value = text,
                            target = requested,
                            exactScore = 125
                        )

                    val descriptionScore =
                        semanticFieldScore(
                            value = description,
                            target = requested,
                            exactScore = 108
                        )

                    // Resource ids such as android:id/title are often reused by
                    // many rows on Samsung/Android settings screens. Treat view_id
                    // as a semantic label only when the node has no real visible
                    // text/description; otherwise it can point at the wrong row.
                    val viewIdScore =
                        if (
                            text.isBlank() &&
                            description.isBlank()
                        ) {
                            semanticFieldScore(
                                value = viewId,
                                target = requested,
                                exactScore = 88
                            )
                        } else {
                            0
                        }

                    var score =
                        maxOf(
                            textScore,
                            descriptionScore,
                            viewIdScore
                        )

                    if (
                        score <= 0
                    ) {
                        continue
                    }

                    if (
                        node.optBoolean(
                            "clickable",
                            false
                        )
                    ) {
                        score +=
                            5
                    }

                    val label =
                        when {
                            text.isNotBlank() ->
                                text

                            description.isNotBlank() ->
                                description

                            else ->
                                requested
                        }

                    // Prefer the actual visible TEXT. Generic resource ids are
                    // frequently shared by every row (for example android:id/title)
                    // and are therefore less specific than the visible label.
                    // Description is second; view id is only the last structured
                    // fallback when the node has no usable user-visible label.
                    val clickTarget =
                        when {
                            text.isNotBlank() ->
                                text

                            description.isNotBlank() ->
                                description

                            viewId.isNotBlank() ->
                                viewId

                            else ->
                                requested
                        }

                    if (
                        best == null ||
                        score >
                        best.score
                    ) {
                        best =
                            ResolvedVisibleTarget(
                                requested = requested,
                                clickTarget = clickTarget,
                                label = label,
                                score = score
                            )
                    }
                }
            }
        }

        if (
            best != null &&
            best.score >=
            MIN_VISIBLE_TARGET_SCORE
        ) {
            return best
        }

        // Compatibility fallback for older/partial snapshots without node data:
        // only accept an exact visible-string match, never invent a fuzzy click.
        val normalizedScreen =
            normalize(
                screen.toString()
            )

        val exact =
            targets.firstOrNull { target ->
                normalizedScreen.contains(
                    normalize(
                        target
                    )
                )
            }

        return exact?.let { target ->
            ResolvedVisibleTarget(
                requested = target,
                clickTarget = target,
                label = target,
                score = MIN_VISIBLE_TARGET_SCORE
            )
        }
    }

    private fun semanticFieldScore(
        value: String,
        target: String,
        exactScore: Int
    ): Int {

        val left =
            normalize(
                value
            )

        val right =
            normalize(
                target
            )

        if (
            left.isBlank() ||
            right.isBlank()
        ) {
            return 0
        }

        if (
            left ==
            right
        ) {
            return exactScore
        }

        if (
            left.contains(
                right
            )
        ) {
            return exactScore -
                10
        }

        if (
            right.contains(
                left
            ) &&
            left.length >=
            4
        ) {
            return exactScore -
                18
        }

        val leftKeys =
            semanticTokenKeys(
                left
            )

        val rightKeys =
            semanticTokenKeys(
                right
            )

        if (
            leftKeys.isEmpty() ||
            rightKeys.isEmpty()
        ) {
            return 0
        }

        val common =
            leftKeys
                .intersect(
                    rightKeys
                )
                .size

        if (common == 0) {
            return 0
        }

        val smaller =
            minOf(
                leftKeys.size,
                rightKeys.size
            )

        val larger =
            maxOf(
                leftKeys.size,
                rightKeys.size
            )

        val shortCoverage =
            common.toDouble() /
                smaller.toDouble()

        val longCoverage =
            common.toDouble() /
                larger.toDouble()

        return when {

            shortCoverage >= 1.0 &&
                longCoverage >= 0.5 ->
                exactScore -
                    24

            common >= 2 &&
                shortCoverage >= 0.75 &&
                longCoverage >= 0.5 ->
                exactScore -
                    32

            rightKeys.size == 1 &&
                common == 1 ->
                exactScore -
                    34

            else ->
                0
        }
    }

    private fun semanticTokenKeys(
        value: String
    ): Set<String> {

        return normalize(
            value
        )
            .split(
                " "
            )
            .mapNotNull { token ->

                if (
                    token.length <
                    3
                ) {
                    return@mapNotNull null
                }

                val key =
                    tokenKey(
                        token
                    )

                if (
                    key in
                    GENERIC_UI_TOKEN_KEYS
                ) {
                    null
                } else {
                    key
                }
            }
            .toSet()
    }

    private fun tokenKey(
        token: String
    ): String {

        val normalized =
            normalize(
                token
            )

        return if (
            normalized.length >=
            6
        ) {
            normalized.take(
                5
            )
        } else {
            normalized
        }
    }

    private fun isLikelyOverflowNode(
        node: JSONObject
    ): Boolean {

        val text =
            normalize(
                node.optString(
                    "text"
                )
            )

        val description =
            normalize(
                node.optString(
                    "description"
                )
            )

        val viewId =
            normalize(
                node.optString(
                    "view_id"
                )
            )

        val className =
            normalize(
                node.optString(
                    "class"
                )
            )

        val iconLike =
            className.contains(
                "imagebutton"
            ) ||
                className.contains(
                    "image button"
                ) ||
                className.contains(
                    "imageview"
                ) ||
                className.contains(
                    "image view"
                )

        val descriptionLooksLikeMenu =
            description in
                setOf(
                    "еще",
                    "ещё",
                    "дополнительные параметры",
                    "другие параметры",
                    "more",
                    "more options",
                    "additional options",
                    "options"
                )

        val idLooksLikeMenu =
            listOf(
                "overflow",
                "more",
                "menu"
            ).any { marker ->
                viewId.contains(
                    marker
                )
            }

        return text.isBlank() &&
            iconLike &&
            (
                descriptionLooksLikeMenu ||
                    idLooksLikeMenu
                )
    }

    private fun sameScreen(
        first: JSONObject,
        second: JSONObject
    ): Boolean {

        return normalizeScreenForComparison(
            first
        ) ==
            normalizeScreenForComparison(
                second
            )
    }

    private fun normalizeScreenForComparison(
        screen: JSONObject
    ): String {

        val packageName =
            normalize(
                screen.optString(
                    "package"
                )
            )

        val rootClass =
            normalize(
                screen.optString(
                    "root_class"
                )
            )

        val visibleText =
            jsonStringList(
                screen.optJSONArray(
                    "visible_text"
                )
            )
                .joinToString(
                    separator = "|"
                ) {
                    normalize(
                        it
                    )
                }

        // Do not compare event_age_ms, node bounds or other volatile metadata:
        // those values change even when the user is still on exactly the same
        // screen and previously caused false "screen_changed=true" results.
        return "$packageName::$rootClass::$visibleText"
    }

    private fun normalize(
        value: String
    ): String {

        return value
            .lowercase(
                Locale.ROOT
            )
            .replace(
                'ё',
                'е'
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun jsonStringList(
        array: JSONArray?
    ): List<String> {

        if (array == null) {
            return emptyList()
        }

        val result =
            ArrayList<String>(
                array.length()
            )

        for (index in 0 until array.length()) {
            val value =
                array.optString(
                    index
                ).trim()

            if (value.isNotBlank()) {
                result.add(
                    value
                )
            }
        }

        return result
    }

    private fun isVagueOverflowTarget(
        value: String
    ): Boolean {

        val normalized =
            normalize(
                value
            )

        return normalized in
            setOf(
                "⋮",
                "...",
                "еще",
                "ещё",
                "more",
                "три точки",
                "меню три точки",
                "overflow",
                "overflow menu"
            )
    }

    private fun isStateChangingTarget(
        value: String
    ): Boolean {

        val normalized =
            normalize(
                value
            )

        return listOf(
            "включить",
            "выключить",
            "разрешить",
            "запретить",
            "удалить",
            "отправить",
            "оплатить",
            "сброс",
            "стереть",
            "очистить данные",
            "factory reset",
            "delete",
            "send",
            "pay",
            "confirm",
            "подтвердить"
        ).any { marker ->
            normalized.contains(
                marker
            )
        }
    }

    private fun safeScreenState():
        JSONObject {

        return try {
            screenIntelligence
                .getScreenState()
        } catch (error: Exception) {
            JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "message",
                    error.message
                        ?: "Не удалось прочитать экран"
                )
        }
    }

    /**
     * Samsung One UI may accept an Intent immediately while Accessibility still
     * reports "Активное окно недоступно". Poll for a short bounded interval and
     * continue as soon as a usable window is available.
     */
    private fun awaitReadyScreen(
        screenBefore: JSONObject,
        screenChangeRequired: Boolean
    ): JSONObject {

        val deadline =
            System.currentTimeMillis() +
                DIRECT_ACTION_READY_TIMEOUT_MS

        var latest =
            safeScreenState()

        while (
            System.currentTimeMillis() <
            deadline &&
            !isCancelled()
        ) {

            val ready =
                latest.optBoolean(
                    "success",
                    false
                ) &&
                    (
                        latest.optInt(
                            "node_count",
                            0
                        ) >
                            0 ||
                        latest.optJSONArray(
                            "visible_text"
                        ) !=
                            null
                    )

            val changed =
                !sameScreen(
                    screenBefore,
                    latest
                )

            if (
                ready &&
                (
                    !screenChangeRequired ||
                    changed
                )
            ) {
                return latest
            }

            try {

                Thread.sleep(
                    DIRECT_ACTION_POLL_MS
                )

            } catch (_: InterruptedException) {

                Thread.currentThread()
                    .interrupt()

                break
            }

            latest =
                safeScreenState()
        }

        return latest
    }

    private fun isCancelled():
        Boolean {

        return try {
            shouldCancel()
        } catch (_: Exception) {
            false
        }
    }

    private fun cancelledStep(
        screen: JSONObject
    ): JSONObject {

        return JSONObject()
            .put(
                "success",
                false
            )
            .put(
                "progress",
                false
            )
            .put(
                "status",
                "cancelled"
            )
            .put(
                "actions_used",
                0
            )
            .put(
                "screen",
                screen
            )
            .put(
                "message",
                "Команда остановлена пользователем"
            )
    }

    private fun engineCancelled(
        goal: String,
        trace: JSONArray,
        screen: JSONObject,
        actionsUsed: Int
    ): JSONObject {

        return JSONObject()
            .put(
                "success",
                false
            )
            .put(
                "status",
                "cancelled"
            )
            .put(
                "goal",
                goal
            )
            .put(
                "message",
                "Команда остановлена пользователем"
            )
            .put(
                "actions_used",
                actionsUsed
            )
            .put(
                "replan_recommended",
                false
            )
            .put(
                "trace",
                trace
            )
            .put(
                "screen",
                screen
            )
    }

    private fun engineSuccess(
        goal: String,
        message: String,
        trace: JSONArray,
        screen: JSONObject,
        actionsUsed: Int
    ): JSONObject {

        return JSONObject()
            .put(
                "success",
                true
            )
            .put(
                "status",
                "success"
            )
            .put(
                "goal",
                goal
            )
            .put(
                "message",
                message
            )
            .put(
                "actions_used",
                actionsUsed
            )
            .put(
                "replan_recommended",
                false
            )
            .put(
                "trace",
                trace
            )
            .put(
                "screen",
                screen
            )
    }

    private fun engineBlocked(
        goal: String,
        reason: String,
        trace: JSONArray,
        screen: JSONObject,
        actionsUsed: Int,
        replanRecommended: Boolean
    ): JSONObject {

        return JSONObject()
            .put(
                "success",
                false
            )
            .put(
                "status",
                "blocked"
            )
            .put(
                "goal",
                goal
            )
            .put(
                "message",
                reason
            )
            .put(
                "actions_used",
                actionsUsed
            )
            .put(
                "replan_recommended",
                replanRecommended
            )
            .put(
                "trace",
                trace
            )
            .put(
                "screen",
                screen
            )
    }

    private fun engineFailure(
        goal: String,
        reason: String,
        trace: JSONArray = JSONArray(),
        screen: JSONObject = JSONObject(),
        actionsUsed: Int = 0
    ): JSONObject {

        return JSONObject()
            .put(
                "success",
                false
            )
            .put(
                "status",
                "invalid_plan"
            )
            .put(
                "goal",
                goal
            )
            .put(
                "message",
                reason
            )
            .put(
                "actions_used",
                actionsUsed
            )
            .put(
                "replan_recommended",
                false
            )
            .put(
                "trace",
                trace
            )
            .put(
                "screen",
                screen
            )
    }

    companion object {

        private const val DEFAULT_MAX_ACTIONS =
            8

        private const val HARD_MAX_ACTIONS =
            10

        private const val MAX_NO_PROGRESS_STREAK =
            2

        private const val MAX_SCROLLS_PER_STEP =
            2

        private const val DIRECT_ACTION_READY_TIMEOUT_MS =
            1600L

        private const val DIRECT_ACTION_POLL_MS =
            80L

        private const val MIN_VISIBLE_TARGET_SCORE =
            66

        // Generic UI words are removed only for semantic comparison. This lets
        // «приложение браузера» match «Браузер» and «использование мобильных
        // данных» match «Мобильные данные» without hard-coding any app or screen.
        private val GENERIC_UI_TOKEN_KEYS =
            setOf(
                "прило",
                "разде",
                "настр",
                "исполь",
                "парам",
                "пункт",
                "стран",
                "экран",
                "откры",
                "выбор",
                "appli",
                "setti",
                "secti",
                "usage",
                "page",
                "scree",
                "item",
                "selec"
            )
    }
}
