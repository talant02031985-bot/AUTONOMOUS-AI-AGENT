package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA Universal UI Action Engine v1.1 — R10.18.2 LIVE TARGET AUTHORITY RECONCILIATION.
 *
 * Production contract:
 * - every generic UI mutation starts from fresh verified Screen Intelligence truth;
 * - exact click targets may delegate target resolution to a live Accessibility query only
 *   where Screen Intelligence already has a fail-closed native-query contract (Samsung Settings);
 * - visual/structured evidence may help understanding, but NEVER grants action authority;
 * - click/input/scroll are dispatched at most once per engine call;
 * - a mutation that Android accepted but AYANA could not verify is never blindly retried;
 * - a no-dispatch/no-side-effect failure may request a higher-layer replan;
 * - post-condition verification uses a fresh read-only observation after dispatch;
 * - role words (button/field/item/link/switch) are normalized without inventing a target.
 */
class AyanaUniversalUiActionEngine(
    private val screenIntelligence: AyanaScreenIntelligence,
    private val shouldCancel: () -> Boolean = { false }
) {

    fun click(
        target: String,
        confirmed: Boolean = false
    ): JSONObject =
        execute(
            kind = ACTION_CLICK,
            requestedTarget = target,
            text = "",
            direction = "",
            confirmed = confirmed
        )

    fun inputText(
        target: String?,
        text: String
    ): JSONObject =
        execute(
            kind = ACTION_INPUT_TEXT,
            requestedTarget = target.orEmpty(),
            text = text,
            direction = "",
            confirmed = false
        )

    fun scroll(
        direction: String
    ): JSONObject =
        execute(
            kind = ACTION_SCROLL,
            requestedTarget = "",
            text = "",
            direction = direction,
            confirmed = false
        )

    fun execute(
        kind: String,
        requestedTarget: String = "",
        text: String = "",
        direction: String = "",
        confirmed: Boolean = false
    ): JSONObject {
        val actionKind = kind.trim().lowercase(Locale.ROOT)
        if (actionKind !in SUPPORTED_ACTIONS) {
            return failure(
                terminal = "UNSUPPORTED",
                status = "unsupported_ui_action",
                reason = "unsupported_ui_action",
                message = "Неподдерживаемое универсальное действие интерфейса"
            )
                .put("requested_action", actionKind)
        }

        if (shouldCancel()) {
            return cancelled("cancelled_before_preflight")
        }

        val normalizedTarget = normalizeTarget(requestedTarget)
        if (
            actionKind in setOf(ACTION_CLICK, ACTION_INPUT_TEXT) &&
            normalizedTarget.canonical.isBlank()
        ) {
            return failure(
                terminal = "ERROR",
                status = "ui_target_missing",
                reason = "ui_target_missing",
                message = "Не указан целевой элемент интерфейса"
            )
                .put("requested_action", actionKind)
                .put("requested_target", requestedTarget)
        }

        val preflight =
            acquireStablePreflight(
                actionKind = actionKind,
                target = normalizedTarget.canonical
            )
        val before = preflight.optJSONObject("screen") ?: JSONObject()
        val authorityMode =
            preDispatchAuthorityMode(
                actionKind = actionKind,
                screen = before,
                target = normalizedTarget.canonical
            )
        val authorityReady = authorityMode != AUTHORITY_NONE

        if (shouldCancel()) {
            return cancelled("cancelled_after_preflight")
                .put("preflight", preflight)
        }

        if (!authorityReady) {
            return failure(
                terminal = "BLOCKED",
                status = "interaction_authority_not_verified",
                reason = "interaction_authority_not_verified",
                message = "Действие не выполнялось: live Accessibility authority для текущего экрана не подтверждена"
            )
                .put("requested_action", actionKind)
                .put("requested_target", requestedTarget)
                .put("normalized_target", normalizedTarget.canonical)
                .put("role_hint", normalizedTarget.roleHint)
                .put("preflight", preflight)
                .put("dispatch_count", 0)
                .put("pre_dispatch_verified", false)
                .put("pre_dispatch_authority_mode", authorityMode)
                .put("visual_grants_action_authority", false)
                .put("blind_retry_allowed", false)
                .put("safe_replan_allowed", true)
                .put("replan_required", true)
                .put("reconciliation_required", false)
                .put("unresolved_side_effect", false)
        }

        val dispatch =
            when (actionKind) {
                ACTION_CLICK ->
                    screenIntelligence.click(
                        target = normalizedTarget.canonical,
                        confirmed = confirmed
                    )

                ACTION_INPUT_TEXT ->
                    screenIntelligence.inputText(
                        target = normalizedTarget.canonical,
                        text = text
                    )

                ACTION_SCROLL ->
                    screenIntelligence.scroll(
                        normalizeDirection(direction)
                    )

                else -> JSONObject()
            }

        val actionAccepted =
            dispatch.optBoolean(
                "action_accepted",
                dispatch.optBoolean("success", false)
            )
        val lowLevelVerified =
            dispatch.optBoolean(
                "verified",
                dispatch.optBoolean("success", false)
            )

        val postObservation = acquireStablePostObservation()
        val after = postObservation.optJSONObject("screen") ?: JSONObject()
        val postTruthVerified =
            snapshotTruthVerified(after)

        val postconditionVerified =
            when (actionKind) {
                ACTION_CLICK ->
                    lowLevelVerified &&
                        dispatch.optBoolean("screen_changed", false) &&
                        postTruthVerified

                ACTION_INPUT_TEXT ->
                    lowLevelVerified &&
                        dispatch
                            .optJSONObject("input_verification")
                            ?.optBoolean("verified", false) == true &&
                        postTruthVerified

                ACTION_SCROLL ->
                    lowLevelVerified &&
                        dispatch.optBoolean("viewport_changed", false) &&
                        postTruthVerified

                else -> false
            }

        val unresolvedSideEffect =
            actionAccepted && !postconditionVerified

        val requiresConfirmation =
            dispatch.optBoolean("requires_confirmation", false) ||
                dispatch.optString("reason") == "confirmation_required"

        val safeReplanAllowed =
            !actionAccepted &&
                !requiresConfirmation &&
                !unresolvedSideEffect

        val replanRequired =
            !postconditionVerified && safeReplanAllowed

        val terminal =
            when {
                postconditionVerified -> "SUCCESS"
                requiresConfirmation -> "BLOCKED"
                unresolvedSideEffect -> "BLOCKED"
                dispatch.optString("terminal_status").isNotBlank() ->
                    dispatch.optString("terminal_status").uppercase(Locale.ROOT)
                else -> "ERROR"
            }

        val status =
            when {
                postconditionVerified -> "ui_action_verified"
                requiresConfirmation -> "ui_action_confirmation_required"
                unresolvedSideEffect -> "ui_action_reconciliation_required"
                replanRequired -> "ui_action_replan_required"
                else -> "ui_action_failed"
            }

        val lowLevel =
            JSONObject(dispatch.toString()).apply {
                remove("screen")
                remove("screen_before")
            }

        val proofLevel =
            dispatch.optString("proof_level").trim()

        val actionAuthoritySource =
            when {
                proofLevel.contains("native_accessibility_text_query") ->
                    "live_accessibility_exact_target_query"
                proofLevel.contains("semantic_target") ->
                    "live_accessibility_semantic_target"
                authorityMode != AUTHORITY_NONE ->
                    authorityMode
                else ->
                    AUTHORITY_NONE
            }

        return JSONObject()
            .put("success", postconditionVerified)
            .put("verified", postconditionVerified)
            .put("action_accepted", actionAccepted)
            .put("terminal_status", terminal)
            .put("status", status)
            .put("reason", status)
            .put("universal_ui_action_engine_version", VERSION)
            .put("universal_ui_action_contract_version", CONTRACT_VERSION)
            .put("requested_action", actionKind)
            .put("requested_target", requestedTarget)
            .put("normalized_target", normalizedTarget.canonical)
            .put("role_hint", normalizedTarget.roleHint)
            .put("dispatch_count", 1)
            .put("pre_dispatch_verified", authorityReady)
            .put("pre_dispatch_authority_mode", authorityMode)
            .put("postcondition_verified", postconditionVerified)
            .put("post_observation_verified", postTruthVerified)
            .put("action_authority_source", actionAuthoritySource)
            .put("visual_read_only_corroboration_allowed", true)
            .put("visual_grants_action_authority", false)
            .put("screen_text_instruction_authority", false)
            .put("blind_retry_allowed", false)
            .put("mutation_blind_retry_blocked", true)
            .put("safe_replan_allowed", safeReplanAllowed)
            .put("replan_required", replanRequired)
            .put("reconciliation_required", unresolvedSideEffect)
            .put("unresolved_side_effect", unresolvedSideEffect)
            .put("requires_confirmation", requiresConfirmation)
            .put("preflight", preflight)
            .put("post_observation", postObservation)
            .put("low_level_result", lowLevel)
            .put("screen", after)
            .put(
                "message",
                when {
                    postconditionVerified ->
                        "Универсальное действие выполнено и подтверждено свежим состоянием экрана"
                    requiresConfirmation ->
                        "Действие требует явного подтверждения пользователя"
                    unresolvedSideEffect ->
                        "Android принял действие, но конечное состояние не подтверждено; повтор вслепую запрещён"
                    replanRequired ->
                        "Действие не было выполнено; разрешён безопасный replan по свежему экрану"
                    else ->
                        dispatch.optString("message", "Действие интерфейса не подтверждено")
                }
            )
    }

    /** Pure contract regression. No Android action is dispatched. */
    fun selfTest(): Boolean {
        val live =
            JSONObject()
                .put("success", true)
                .put("snapshot_success", true)
                .put("foreground_truth_verified", true)
                .put("foreground_truth_conflict", false)
                .put("effective_foreground_package", "com.android.settings")
                .put("interaction_understanding_usable", true)
                .put("visual_grants_action_authority", false)
                .put(
                    "nodes",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("visible", true)
                                .put("enabled", true)
                                .put("clickable", true)
                                .put("editable", false)
                                .put("scrollable", false)
                        )
                )

        val visualOnly =
            JSONObject(live.toString())
                .put("interaction_understanding_usable", false)
                .put("read_only_understanding_usable", true)
                .put("visual_observation_used", true)
                .put("visual_grants_action_authority", true)

        val sparseSettings =
            JSONObject()
                .put("success", true)
                .put("snapshot_success", true)
                .put("foreground_truth_verified", true)
                .put("foreground_truth_conflict", false)
                .put("effective_foreground_package", "com.android.settings")
                .put("interaction_package", "com.android.settings")
                .put("interaction_understanding_usable", false)
                .put("visual_grants_action_authority", false)
                .put("nodes", JSONArray())

        val roleButton = normalizeTarget("кнопку Разрешения")
        val roleField = normalizeTarget("поле ввода Поиск")
        val literal = normalizeTarget("AYANA AI")

        return mutationAuthorityReady(live) &&
            !mutationAuthorityReady(visualOnly) &&
            preDispatchAuthorityMode(
                actionKind = ACTION_CLICK,
                screen = sparseSettings,
                target = "Экран"
            ) == AUTHORITY_LIVE_EXACT_TARGET_QUERY &&
            preDispatchAuthorityMode(
                actionKind = ACTION_INPUT_TEXT,
                screen = sparseSettings,
                target = "Поиск"
            ) == AUTHORITY_NONE &&
            roleButton.canonical == "Разрешения" &&
            roleButton.roleHint == "button" &&
            roleField.canonical == "Поиск" &&
            roleField.roleHint == "field" &&
            literal.canonical == "AYANA AI" &&
            literal.roleHint == "" &&
            SUPPORTED_ACTIONS.size == 3
    }

    private fun acquireStablePreflight(
        actionKind: String,
        target: String
    ): JSONObject =
        acquireStableObservation(
            requireInteraction = true,
            maxAttempts = PREFLIGHT_MAX_ATTEMPTS,
            actionKind = actionKind,
            target = target
        )

    private fun acquireStablePostObservation(): JSONObject =
        acquireStableObservation(
            requireInteraction = false,
            maxAttempts = POST_OBSERVATION_MAX_ATTEMPTS,
            actionKind = "",
            target = ""
        )

    private fun acquireStableObservation(
        requireInteraction: Boolean,
        maxAttempts: Int,
        actionKind: String,
        target: String
    ): JSONObject {
        var latest = JSONObject()
        var attempts = 0

        while (attempts < maxAttempts && !shouldCancel()) {
            attempts++
            latest =
                try {
                    screenIntelligence.getScreenState()
                } catch (_: Throwable) {
                    JSONObject()
                }

            val ready =
                if (requireInteraction) {
                    preDispatchAuthorityMode(
                        actionKind = actionKind,
                        screen = latest,
                        target = target
                    ) != AUTHORITY_NONE
                } else {
                    snapshotTruthVerified(latest)
                }

            if (ready) {
                break
            }

            if (attempts < maxAttempts) {
                try {
                    Thread.sleep(OBSERVATION_SETTLE_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        val finalAuthorityMode =
            if (requireInteraction) {
                preDispatchAuthorityMode(
                    actionKind = actionKind,
                    screen = latest,
                    target = target
                )
            } else {
                AUTHORITY_NONE
            }

        return JSONObject()
            .put(
                "verified",
                if (requireInteraction) {
                    finalAuthorityMode != AUTHORITY_NONE
                } else {
                    snapshotTruthVerified(latest)
                }
            )
            .put("attempts", attempts)
            .put("read_only_only", true)
            .put("authority_mode", finalAuthorityMode)
            .put("screen", latest)
    }

    private fun preDispatchAuthorityMode(
        actionKind: String,
        screen: JSONObject,
        target: String
    ): String {
        if (!snapshotTruthVerified(screen)) return AUTHORITY_NONE
        if (screen.optBoolean("visual_grants_action_authority", false)) return AUTHORITY_NONE

        if (mutationAuthorityReady(screen)) {
            return AUTHORITY_LIVE_STRUCTURE
        }

        if (
            actionKind == ACTION_CLICK &&
            target.isNotBlank() &&
            supportsDelegatedExactTargetResolution(screen)
        ) {
            return AUTHORITY_LIVE_EXACT_TARGET_QUERY
        }

        return AUTHORITY_NONE
    }

    private fun supportsDelegatedExactTargetResolution(
        screen: JSONObject
    ): Boolean {
        val effectivePackage =
            screen.optString("effective_foreground_package").trim()
        val interactionPackage =
            screen
                .optString("interaction_package", effectivePackage)
                .trim()

        // AyanaScreenIntelligence has a fail-closed exact native text/description
        // query only for Samsung/Android Settings. The live query is restricted to
        // current TYPE_APPLICATION interaction windows, exact normalized match,
        // visible+enabled nodes, ambiguity rejection and verified screen change.
        return effectivePackage == "com.android.settings" &&
            interactionPackage == "com.android.settings"
    }

    private fun mutationAuthorityReady(
        screen: JSONObject
    ): Boolean {
        if (!snapshotTruthVerified(screen)) return false
        if (screen.optBoolean("visual_grants_action_authority", false)) return false
        if (!screen.optBoolean("interaction_understanding_usable", false)) return false

        val nodes = screen.optJSONArray("nodes") ?: return false
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            if (
                node.optBoolean("visible", false) &&
                node.optBoolean("enabled", true) &&
                (
                    node.optBoolean("clickable", false) ||
                        node.optBoolean("editable", false) ||
                        node.optBoolean("scrollable", false)
                    )
            ) {
                return true
            }
        }
        return false
    }

    private fun snapshotTruthVerified(
        screen: JSONObject
    ): Boolean =
        screen.optBoolean(
            "snapshot_success",
            screen.optBoolean("success", false)
        ) &&
            screen.optBoolean("foreground_truth_verified", false) &&
            !screen.optBoolean("foreground_truth_conflict", false) &&
            screen.optString("effective_foreground_package").isNotBlank()

    private fun normalizeDirection(
        value: String
    ): String {
        val normalized =
            value
                .trim()
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')

        return if (
            normalized.contains("up") ||
            normalized.contains("вверх") ||
            normalized.contains("наверх")
        ) {
            "up"
        } else {
            "down"
        }
    }

    private data class NormalizedTarget(
        val canonical: String,
        val roleHint: String
    )

    private fun normalizeTarget(
        value: String
    ): NormalizedTarget {
        var clean =
            value
                .trim()
                .trim('"', '\'', '«', '»', '“', '”')
                .replace(Regex("\\s+"), " ")
                .trim()

        val lower = clean.lowercase(Locale.ROOT).replace('ё', 'е')

        val prefixes =
            listOf(
                Triple("поле ввода ", "field", "поле ввода "),
                Triple("кнопку ", "button", "кнопку "),
                Triple("кнопка ", "button", "кнопка "),
                Triple("кнопки ", "button", "кнопки "),
                Triple("пункт ", "item", "пункт "),
                Triple("пункта ", "item", "пункта "),
                Triple("элемент ", "item", "элемент "),
                Triple("элемента ", "item", "элемента "),
                Triple("ссылку ", "link", "ссылку "),
                Triple("ссылка ", "link", "ссылка "),
                Triple("переключатель ", "switch", "переключатель "),
                Triple("тумблер ", "switch", "тумблер "),
                Triple("поле ", "field", "поле "),
                Triple("строку ", "item", "строку "),
                Triple("строка ", "item", "строка "),
                Triple("вкладку ", "tab", "вкладку "),
                Triple("вкладка ", "tab", "вкладка ")
            )

        for ((prefix, role, rawPrefix) in prefixes) {
            if (lower.startsWith(prefix)) {
                clean = clean.substring(rawPrefix.length).trim()
                clean = clean.trim('"', '\'', '«', '»', '“', '”').trim()
                return NormalizedTarget(clean, role)
            }
        }

        return NormalizedTarget(clean, "")
    }

    private fun failure(
        terminal: String,
        status: String,
        reason: String,
        message: String
    ): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("action_accepted", false)
            .put("terminal_status", terminal)
            .put("status", status)
            .put("reason", reason)
            .put("message", message)
            .put("universal_ui_action_engine_version", VERSION)
            .put("universal_ui_action_contract_version", CONTRACT_VERSION)
            .put("visual_grants_action_authority", false)
            .put("blind_retry_allowed", false)
            .put("mutation_blind_retry_blocked", true)
            .put("unresolved_side_effect", false)

    private fun cancelled(
        reason: String
    ): JSONObject =
        failure(
            terminal = "CANCELLED",
            status = "ui_action_cancelled",
            reason = reason,
            message = "Универсальное действие интерфейса отменено"
        )

    companion object {
        const val VERSION = "1.1"
        const val CONTRACT_VERSION = 2

        const val ACTION_CLICK = "click"
        const val ACTION_INPUT_TEXT = "input_text"
        const val ACTION_SCROLL = "scroll"

        private val SUPPORTED_ACTIONS =
            setOf(
                ACTION_CLICK,
                ACTION_INPUT_TEXT,
                ACTION_SCROLL
            )

        private const val PREFLIGHT_MAX_ATTEMPTS = 3
        private const val POST_OBSERVATION_MAX_ATTEMPTS = 3
        private const val OBSERVATION_SETTLE_MS = 90L

        private const val AUTHORITY_NONE = "none"
        private const val AUTHORITY_LIVE_STRUCTURE = "live_accessibility_structure"
        private const val AUTHORITY_LIVE_EXACT_TARGET_QUERY = "live_accessibility_exact_target_query"
    }
}
