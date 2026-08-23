package kg.autonomous.agent

import org.json.JSONObject
import java.util.Locale

/**
 * AYANA Safety Policy v1.3 — ARTIFACT SAFE WRITE.
 *
 * Local fail-closed guard executed immediately before Agent Core device tools.
 * It is intentionally independent from model instructions: a model mistake must
 * not be enough to type secrets or perform an obvious dangerous generic click.
 *
 * Risk levels:
 * 0 READ_ONLY            - inspection / information only;
 * 1 SAFE_ACTION          - reversible or low-risk navigation/action;
 * 2 CONFIRMATION_REQUIRED- action may proceed only after fresh user approval;
 * 3 PROHIBITED           - AYANA must not execute this path at all.
 */
class AyanaSafetyPolicy {

    data class Decision(
        val allowed: Boolean,
        val requiresConfirmation: Boolean,
        val riskLevel: Int,
        val riskName: String,
        val reason: String
    )

    /**
     * Early command-level guard used before Agent Core.
     *
     * It intentionally triggers only for an explicit text-entry imperative.
     * A discussion such as "что такое API-ключ" is not blocked, while
     * "введи sk-..." is rejected locally before any network/tool planning.
     */
    fun evaluateUserCommand(
        command: String
    ): Decision {

        val normalized =
            normalize(
                command
            )

        if (
            !EXPLICIT_TEXT_ENTRY_PREFIX
                .containsMatchIn(
                    normalized
                )
        ) {
            return allow(
                RISK_READ_ONLY,
                "command_not_sensitive"
            )
        }

        if (
            containsCredential(
                command
            ) ||
            containsPaymentCard(
                command
            ) ||
            COMMAND_SECRET_CONTEXT_WORDS.any {
                token ->
                normalized.contains(
                    token
                )
            }
        ) {
            return prohibit(
                "Локальный Safety Engine заблокировал попытку автоматического ввода секрета, токена, API-ключа или платёжных данных. Введите чувствительные данные вручную."
            )
        }

        return allow(
            RISK_SAFE_ACTION,
            "ordinary_text_entry_request"
        )
    }

    fun evaluateTool(
        toolName: String,
        arguments: JSONObject
    ): Decision {

        val name =
            toolName
                .trim()
                .lowercase(
                    Locale.ROOT
                )

        return when (name) {

            "get_device_state",
            "get_screen_state",
            "get_device_capabilities",
            "run_self_diagnostics",
            "list_installed_apps",
            "resolve_app",
            "list_goals",
            "recall_memory",
            "list_memory",
            "list_reminders" ->
                allow(
                    RISK_READ_ONLY,
                    "read_only"
                )

            "open_app",
            "open_settings",
            "open_app_info",
            "open_app_settings",
            "press_back",
            "press_home",
            "change_volume",
            "youtube_search",
            "google_search",
            "map_search",
            "scroll_screen",
            "execute_android_goal",
            "execute_android_plan" ->
                allow(
                    RISK_SAFE_ACTION,
                    "safe_action"
                )

            "tap_screen_coordinates" ->
                if (
                    arguments.optBoolean(
                        "confirmed",
                        false
                    )
                ) {
                    allow(
                        RISK_CONFIRMATION_REQUIRED,
                        "confirmation_required"
                    )
                } else {
                    confirmation(
                        "Координатное нажатие требует нового явного подтверждения пользователя."
                    )
                }

            "click_text" ->
                evaluateGenericTextClick(
                    arguments.optString(
                        "text"
                    )
                        .ifBlank {
                            arguments.optString(
                                "target"
                            )
                        }
                )

            "input_screen_text" ->
                evaluateTextInput(
                    target = arguments.optString(
                        "target"
                    ),
                    text = arguments.optString(
                        "text"
                    )
                )

            "click_screen_element" ->
                // Screen Intelligence performs semantic target resolution and
                // its own confirmation check. Keep this policy layer permissive
                // so a fresh confirmed=true replay can reach that validator.
                allow(
                    RISK_SAFE_ACTION,
                    "semantic_screen_action"
                )

            "remember_memory",
            "update_memory" ->
                evaluateMemoryWrite(
                    arguments.optString(
                        "text"
                    )
                        .ifBlank {
                            arguments.optString(
                                "new_text"
                            )
                        }
                )

            "forget_memory",
            "create_artifact",
            "create_reminder",
            "delete_reminder",
            "update_reminder",
            "set_reminder_enabled",
            "select_goal",
            "cancel_goal" ->
                allow(
                    RISK_SAFE_ACTION,
                    if (name == "create_artifact") {
                        "safe_scoped_artifact_write"
                    } else {
                        "local_user_data_action"
                    }
                )

            else ->
                // Unknown tools are rejected by executeAgentTool itself. Marking
                // them prohibited here gives a clearer local safety boundary.
                prohibit(
                    "Инструмент не входит в разрешённую локальную политику AYANA."
                )
        }
    }

    private fun evaluateMemoryWrite(
        text: String
    ): Decision {

        if (
            containsCredential(
                text
            ) ||
            containsPaymentCard(
                text
            )
        ) {
            return prohibit(
                "Safety Engine не сохраняет API-ключи, токены, пароли, OTP или платёжные данные в долговременную память."
            )
        }

        return allow(
            RISK_SAFE_ACTION,
            "safe_memory_write"
        )
    }

    private fun evaluateGenericTextClick(
        target: String
    ): Decision {

        val normalized =
            normalize(
                target
            )

        if (
            normalized.isBlank()
        ) {
            return prohibit(
                "Нельзя выполнять generic click без семантической цели."
            )
        }

        if (
            DANGEROUS_CLICK_WORDS.any {
                token ->
                normalized.contains(
                    token
                )
            }
        ) {
            return prohibit(
                "Опасный generic click заблокирован. Используйте click_screen_element, чтобы Screen Intelligence проверил элемент и запросил подтверждение при необходимости."
            )
        }

        return allow(
            RISK_SAFE_ACTION,
            "safe_generic_click"
        )
    }

    private fun evaluateTextInput(
        target: String,
        text: String
    ): Decision {

        val normalizedTarget =
            normalize(
                target
            )

        val normalizedText =
            text
                .trim()

        if (
            SECRET_TARGET_WORDS.any {
                token ->
                normalizedTarget.contains(
                    token
                )
            }
        ) {
            return prohibit(
                "AYANA не вводит пароли, PIN, OTP/SMS-коды, данные карт, токены или другие секреты."
            )
        }

        if (
            looksLikeCredential(
                normalizedText
            ) ||
            looksLikePaymentCard(
                normalizedText
            )
        ) {
            return prohibit(
                "Ввод похож на секрет, токен или данные платёжной карты и заблокирован локальным Safety Engine."
            )
        }

        return allow(
            RISK_SAFE_ACTION,
            "non_secret_text_input"
        )
    }

    private fun looksLikeCredential(
        text: String
    ): Boolean =
        containsCredential(
            text
        )

    private fun containsCredential(
        text: String
    ): Boolean {

        val value =
            text
                .trim()
                .lowercase(
                    Locale.ROOT
                )

        if (
            value.isBlank()
        ) {
            return false
        }

        return value.startsWith(
            "bearer "
        ) ||
            CREDENTIAL_PATTERN
                .containsMatchIn(
                    value
                ) ||
            NAMED_SECRET_PATTERN
                .containsMatchIn(
                    value
                )
    }

    private fun containsPaymentCard(
        text: String
    ): Boolean =
        PAYMENT_CARD_CANDIDATE_PATTERN
            .findAll(
                text
            )
            .any {
                match ->
                looksLikePaymentCard(
                    match.groupValues[
                        1
                    ]
                )
            }

    private fun looksLikePaymentCard(
        text: String
    ): Boolean {

        val digits =
            text.filter {
                it.isDigit()
            }

        if (
            digits.length !in
            13..19
        ) {
            return false
        }

        // Require the original text to be almost entirely digits/separators so
        // an arbitrary sentence containing many numbers is not misclassified.
        val allowedChars =
            text.count {
                it.isDigit() ||
                    it.isWhitespace() ||
                    it == '-'
            }

        if (
            allowedChars !=
            text.length
        ) {
            return false
        }

        return passesLuhn(
            digits
        )
    }

    private fun passesLuhn(
        digits: String
    ): Boolean {

        var sum =
            0

        var doubleDigit =
            false

        for (
            index in
            digits.lastIndex downTo 0
        ) {
            var value =
                digits[index] -
                    '0'

            if (doubleDigit) {
                value *=
                    2

                if (
                    value >
                    9
                ) {
                    value -=
                        9
                }
            }

            sum +=
                value

            doubleDigit =
                !doubleDigit
        }

        return sum %
            10 ==
            0
    }

    private fun normalize(
        value: String
    ): String =
        value
            .trim()
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

    private fun allow(
        level: Int,
        name: String
    ): Decision =
        Decision(
            allowed = true,
            requiresConfirmation = false,
            riskLevel = level,
            riskName = name,
            reason = ""
        )

    private fun confirmation(
        reason: String
    ): Decision =
        Decision(
            allowed = false,
            requiresConfirmation = true,
            riskLevel = RISK_CONFIRMATION_REQUIRED,
            riskName = "confirmation_required",
            reason = reason
        )

    private fun prohibit(
        reason: String
    ): Decision =
        Decision(
            allowed = false,
            requiresConfirmation = false,
            riskLevel = RISK_PROHIBITED,
            riskName = "prohibited",
            reason = reason
        )

    companion object {

        const val RISK_READ_ONLY =
            0

        const val RISK_SAFE_ACTION =
            1

        const val RISK_CONFIRMATION_REQUIRED =
            2

        const val RISK_PROHIBITED =
            3

        private val COMMAND_SECRET_CONTEXT_WORDS =
            setOf(
                "api key",
                "api ключ",
                "пароль",
                "password",
                "pin код",
                "пин код",
                "otp",
                "одноразовый код",
                "код подтверждения",
                "sms код",
                "смс код",
                "cvv",
                "cvc",
                "номер карты",
                "card number"
            )

        private val EXPLICIT_TEXT_ENTRY_PREFIX =
            Regex(
                "^(?:введи|введите|вставь|вставьте|впиши|впишите|напечатай|напечатайте|набери|наберите)(?:\\s|$)",
                RegexOption.IGNORE_CASE
            )

        private val CREDENTIAL_PATTERN =
            Regex(
                "(?:^|[^a-z0-9])sk-[a-z0-9_-]{12,}(?:$|[^a-z0-9_-])",
                RegexOption.IGNORE_CASE
            )

        private val NAMED_SECRET_PATTERN =
            Regex(
                "(?:api[_ -]?(?:key|ключ)|token|токен|secret|секрет)\\s*[:=]\\s*\\S+",
                RegexOption.IGNORE_CASE
            )

        private val PAYMENT_CARD_CANDIDATE_PATTERN =
            Regex(
                "(?:^|[^\\d])((?:\\d[ -]?){12,18}\\d)(?:$|[^\\d])"
            )

        private val DANGEROUS_CLICK_WORDS =
            setOf(
                "удалить",
                "стереть",
                "очистить все",
                "отправить",
                "оплатить",
                "купить",
                "подтвердить",
                "разрешить",
                "установить",
                "удалить приложение",
                "сбросить",
                "factory reset",
                "delete",
                "send",
                "pay",
                "purchase",
                "confirm",
                "allow",
                "install",
                "uninstall",
                "reset"
            )

        private val SECRET_TARGET_WORDS =
            setOf(
                "пароль",
                "password",
                "pin",
                "пин",
                "otp",
                "одноразовый код",
                "код подтверждения",
                "sms код",
                "смс код",
                "cvv",
                "cvc",
                "номер карты",
                "card number",
                "api key",
                "api ключ",
                "токен",
                "token",
                "секрет",
                "secret"
            )
    }
}
