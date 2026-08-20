package kg.autonomous.agent

import android.content.Context
import org.json.JSONObject

/**
 * AYANA Screen Intelligence v3.0 — evidence contract.
 * Snapshot success is separated from content understanding.
 */
class AyanaScreenIntelligence(
    context: Context
) {

    private val appContext =
        context.applicationContext

    fun getScreenState():
        JSONObject {

        val service =
            AgentAccessibilityService
                .instance
                ?: return unavailable()

        return annotateSnapshot(
            service
                .buildScreenSnapshot()
        )
    }

    fun click(
        target: String,
        confirmed: Boolean = false
    ): JSONObject {

        if (
            isSensitiveTarget(
                target
            ) &&
            !confirmed
        ) {

            return JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "requires_confirmation",
                    true
                )
                .put(
                    "message",
                    "Чувствительное действие требует явного подтверждения пользователя"
                )
        }

        val service =
            AgentAccessibilityService
                .instance
                ?: return unavailable()

        val before =
            service
                .screenSignature()

        val success =
            service
                .clickElement(
                    target
                )

        sleepBriefly()

        val after =
            service
                .screenSignature()

        return JSONObject()
            .put(
                "success",
                success
            )
            .put(
                "target",
                target
            )
            .put(
                "screen_changed",
                before != after
            )
            .put(
                "action_accepted",
                success
            )
            .put(
                "proof_level",
                if (before != after) {
                    "screen_change"
                } else if (success) {
                    "action_accepted"
                } else {
                    "none"
                }
            )
            .put(
                "screen",
                compactScreenState(
                    service
                )
            )
            .put(
                "message",
                if (
                    success
                ) {
                    "Элемент нажат"
                } else {
                    "Подходящий элемент не найден или он недоступен для нажатия"
                }
            )
    }

    fun inputText(
        target: String?,
        text: String
    ): JSONObject {

        if (
            looksLikeSecret(
                text
            )
        ) {

            return JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "requires_confirmation",
                    true
                )
                .put(
                    "message",
                    "AYANA не вводит пароли, коды подтверждения, платёжные данные или другие секреты автономно"
                )
        }

        val service =
            AgentAccessibilityService
                .instance
                ?: return unavailable()

        val before =
            service
                .screenSignature()

        val success =
            service
                .setText(
                    target =
                        target,
                    text =
                        text
                )

        sleepBriefly()

        val after =
            service
                .screenSignature()

        return JSONObject()
            .put(
                "success",
                success
            )
            .put(
                "target",
                target
                    ?: ""
            )
            .put(
                "screen_changed",
                before != after
            )
            .put(
                "action_accepted",
                success
            )
            .put(
                "proof_level",
                if (before != after) {
                    "screen_change"
                } else if (success) {
                    "action_accepted"
                } else {
                    "none"
                }
            )
            .put(
                "screen",
                compactScreenState(
                    service
                )
            )
            .put(
                "message",
                if (
                    success
                ) {
                    "Текст введён"
                } else {
                    "Поле ввода не найдено, недоступно или является защищённым"
                }
            )
    }

    fun scroll(
        direction: String
    ): JSONObject {

        val service =
            AgentAccessibilityService
                .instance
                ?: return unavailable()

        val before =
            service
                .screenSignature()

        val success =
            service
                .scroll(
                    direction
                )

        sleepBriefly()

        val after =
            service
                .screenSignature()

        return JSONObject()
            .put(
                "success",
                success
            )
            .put(
                "direction",
                direction
            )
            .put(
                "screen_changed",
                before != after
            )
            .put(
                "action_accepted",
                success
            )
            .put(
                "proof_level",
                if (before != after) {
                    "screen_change"
                } else if (success) {
                    "action_accepted"
                } else {
                    "none"
                }
            )
            .put(
                "screen",
                compactScreenState(
                    service
                )
            )
            .put(
                "message",
                if (
                    success
                ) {
                    "Экран прокручен"
                } else {
                    "Прокручиваемая область не найдена"
                }
            )
    }

    fun tap(
        x: Int,
        y: Int,
        confirmed: Boolean = false
    ): JSONObject {

        if (
            !confirmed
        ) {

            return JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "requires_confirmation",
                    true
                )
                .put(
                    "message",
                    "Касание по координатам используется только после явного подтверждения пользователя"
                )
        }

        val service =
            AgentAccessibilityService
                .instance
                ?: return unavailable()

        val before =
            service
                .screenSignature()

        val success =
            service
                .tapCoordinates(
                    x,
                    y
                )

        sleepBriefly()

        val after =
            service
                .screenSignature()

        return JSONObject()
            .put(
                "success",
                success
            )
            .put(
                "x",
                x
            )
            .put(
                "y",
                y
            )
            .put(
                "screen_changed",
                before != after
            )
            .put(
                "screen",
                compactScreenState(
                    service
                )
            )
            .put(
                "message",
                if (
                    success
                ) {
                    "Касание отправлено"
                } else {
                    "Не удалось выполнить касание"
                }
            )
    }

    fun pressBack():
        JSONObject {

        val service =
            AgentAccessibilityService
                .instance
                ?: return unavailable()

        val before =
            service
                .screenSignature()

        val success =
            service
                .pressBack()

        sleepBriefly()

        val after =
            service
                .screenSignature()

        return JSONObject()
            .put(
                "success",
                success
            )
            .put(
                "screen_changed",
                before != after
            )
            .put(
                "screen",
                compactScreenState(
                    service
                )
            )
            .put(
                "message",
                if (
                    success
                ) {
                    "Назад выполнено"
                } else {
                    "Не удалось выполнить Назад"
                }
            )
    }

    fun pressHome():
        JSONObject {

        val service =
            AgentAccessibilityService
                .instance
                ?: return unavailable()

        val success =
            service
                .pressHome()

        sleepBriefly()

        return JSONObject()
            .put(
                "success",
                success
            )
            .put(
                "screen",
                compactScreenState(
                    service
                )
            )
            .put(
                "message",
                if (
                    success
                ) {
                    "Домой выполнено"
                } else {
                    "Не удалось выполнить Домой"
                }
            )
    }

    private fun compactScreenState(
        service: AgentAccessibilityService
    ): JSONObject {

        return annotateSnapshot(
            service
                .buildScreenSnapshot(
                    maxNodes =
                        80,
                    maxChars =
                        8000
                )
        )
    }

    private fun annotateSnapshot(
        snapshot: JSONObject
    ): JSONObject {

        val snapshotSuccess =
            snapshot.optBoolean(
                "success",
                false
            )

        val contentState =
            snapshot
                .optString(
                    "primary_content_state",
                    snapshot.optString(
                        "content_status",
                        "unknown"
                    )
                )
                .ifBlank {
                    "unknown"
                }

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
            .put(
                "source",
                "android_accessibility"
            )
            .put(
                "content_contract_version",
                snapshot.optInt(
                    "content_contract_version",
                    2
                )
            )
            .put(
                "snapshot_success",
                snapshotSuccess
            )
            .put(
                "understanding_success",
                snapshotSuccess &&
                    contentAvailable
            )
            .put(
                "content_status",
                contentState
            )
            .put(
                "content_available",
                contentAvailable
            )
            .put(
                "content_message",
                message
            )
    }

    private fun unavailable():
        JSONObject {

        return JSONObject()
            .put(
                "success",
                false
            )
            .put(
                "snapshot_success",
                false
            )
            .put(
                "understanding_success",
                false
            )
            .put(
                "content_contract_version",
                2
            )
            .put(
                "content_status",
                "unknown"
            )
            .put(
                "primary_content_state",
                "unknown"
            )
            .put(
                "primary_content_available",
                false
            )
            .put(
                "accessibility_enabled",
                false
            )
            .put(
                "message",
                "Служба Accessibility AYANA не подключена"
            )
    }

    private fun sleepBriefly() {

        try {

            Thread.sleep(
                420
            )

        } catch (_: InterruptedException) {

            Thread
                .currentThread()
                .interrupt()
        }
    }

    private fun isSensitiveTarget(
        value: String
    ): Boolean {

        val normalized =
            value
                .lowercase()

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

        return sensitiveWords
            .any {
                normalized.contains(
                    it
                )
            }
    }

    private fun looksLikeSecret(
        value: String
    ): Boolean {

        val trimmed =
            value.trim()

        if (
            trimmed.matches(
                Regex(
                    "^\\d{4,8}$"
                )
            )
        ) {
            return true
        }

        if (
            trimmed.matches(
                Regex(
                    ".*\\b\\d{13,19}\\b.*"
                )
            )
        ) {
            return true
        }

        val lower =
            trimmed.lowercase()

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
        ).any {
            lower.contains(
                it
            )
        }
    }
}
