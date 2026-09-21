package kg.autonomous.agent

import java.net.SocketTimeoutException
import java.util.Locale

/**
 * AYANA R9.0 Agent Core Recovery Policy v1.0.
 *
 * Pure fail-closed policy. It never performs network I/O itself.
 * VoiceService asks this policy whether one failed Agent Core turn may be retried.
 *
 * Safety contract:
 * - timeouts may use the existing bounded retry contract;
 * - incomplete-response / continuation-budget failures may be retried only for
 *   read-only text requests with no tool results and no device-fact execution handoff;
 * - side-effect-like requests are never restarted after an HTTP/API completion error;
 * - at most one compact-restart recovery is permitted per call chain.
 */
class AyanaAgentCoreRecoveryPolicy {

    enum class Strategy {
        NONE,
        TIMEOUT_RETRY,
        COMPACT_RESTART
    }

    data class Decision(
        val recoverable: Boolean,
        val strategy: Strategy,
        val reason: String,
        val retryMessage: String? = null,
        val preservePreviousResponseId: Boolean = true
    )

    fun decide(
        error: Throwable,
        originalMessage: String?,
        source: String,
        hasToolResults: Boolean,
        hasVerifiedDeviceFacts: Boolean,
        compactRestartAlreadyUsed: Boolean
    ): Decision {
        if (error is SocketTimeoutException) {
            return Decision(
                recoverable = true,
                strategy = Strategy.TIMEOUT_RETRY,
                reason = "socket_timeout"
            )
        }

        val raw =
            buildString {
                append(error.javaClass.simpleName)
                append(' ')
                append(error.message.orEmpty())
            }
                .lowercase(Locale.ROOT)

        val incompleteResponse =
            raw.contains("openai agent core incomplete response") ||
                raw.contains("completion_continuation_budget_exhausted") ||
                raw.contains("incomplete response") &&
                    raw.contains("continuation")

        if (!incompleteResponse) {
            return Decision(
                recoverable = false,
                strategy = Strategy.NONE,
                reason = "not_recoverable_completion_failure"
            )
        }

        if (compactRestartAlreadyUsed) {
            return Decision(
                recoverable = false,
                strategy = Strategy.NONE,
                reason = "compact_restart_budget_exhausted"
            )
        }

        val message =
            originalMessage
                .orEmpty()
                .trim()

        val safeReadOnly =
            source.equals("text", ignoreCase = true) &&
                message.isNotBlank() &&
                !hasToolResults &&
                !hasVerifiedDeviceFacts &&
                !containsPotentialSideEffectVerb(message)

        if (!safeReadOnly) {
            return Decision(
                recoverable = false,
                strategy = Strategy.NONE,
                reason = "incomplete_response_not_safe_to_restart"
            )
        }

        return Decision(
            recoverable = true,
            strategy = Strategy.COMPACT_RESTART,
            reason = "continuation_budget_exhausted_read_only",
            retryMessage = compactRecoveryMessage(message),
            preservePreviousResponseId = true
        )
    }

    fun isRecoverableIncompleteResponse(
        error: Throwable
    ): Boolean {
        val raw =
            (error.message.orEmpty() + " " + error.javaClass.simpleName)
                .lowercase(Locale.ROOT)

        return raw.contains("openai agent core incomplete response") ||
            raw.contains("completion_continuation_budget_exhausted") ||
            raw.contains("incomplete response") && raw.contains("continuation")
    }

    fun compactRecoveryMessage(
        original: String
    ): String {
        val clean =
            original
                .replace(Regex("\\s+"), " ")
                .trim()

        return buildString {
            append(clean)
            append("\n\n")
            append("R9.0 RECOVERY CONTRACT: предыдущая попытка не смогла завершить длинный ответ. ")
            append("Дай ПОЛНЫЙ, но компактный ответ в одном завершённом результате. ")
            append("Сохрани все явно запрошенные пункты и факты, убери повторения и лишние пояснения. ")
            append("Не заявляй выполнение действий на устройстве, если их не было. ")
            append("Не обрывай список на середине.")
        }
            .take(MAX_RECOVERY_MESSAGE_CHARS)
    }

    fun selfTest(): Boolean {
        val incomplete =
            IllegalStateException(
                "Agent HTTP 502: {\"error\":\"OpenAI Agent Core incomplete response\",\"details\":{\"reason\":\"completion_continuation_budget_exhausted\"}}"
            )

        val safe =
            decide(
                error = incomplete,
                originalMessage = "проведи полный самоаудит возможностей",
                source = "text",
                hasToolResults = false,
                hasVerifiedDeviceFacts = false,
                compactRestartAlreadyUsed = false
            )

        val unsafe =
            decide(
                error = incomplete,
                originalMessage = "удали файл и потом подробно объясни",
                source = "text",
                hasToolResults = false,
                hasVerifiedDeviceFacts = false,
                compactRestartAlreadyUsed = false
            )

        val exhausted =
            decide(
                error = incomplete,
                originalMessage = "подробно сравни возможности",
                source = "text",
                hasToolResults = false,
                hasVerifiedDeviceFacts = false,
                compactRestartAlreadyUsed = true
            )

        return safe.recoverable &&
            safe.strategy == Strategy.COMPACT_RESTART &&
            !safe.retryMessage.isNullOrBlank() &&
            !unsafe.recoverable &&
            !exhausted.recoverable
    }

    private fun containsPotentialSideEffectVerb(
        message: String
    ): Boolean {
        val normalized =
            message
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')

        return SIDE_EFFECT_VERB_REGEX.containsMatchIn(normalized)
    }

    companion object {
        const val VERSION = "1.0"

        private const val MAX_RECOVERY_MESSAGE_CHARS = 7000

        private val SIDE_EFFECT_VERB_REGEX =
            Regex(
                "(?:^|\\s)(?:открой|открыть|закрой|закрыть|сверни|свернуть|нажми|нажать|введи|ввести|напечатай|измени|изменить|установи|установить|включи|включить|выключи|выключить|создай|создать|сохрани|сохранить|удали|удалить|отправь|отправить|запусти|запустить|загрузи|загрузить|commit|push|коммит|пуш)(?:\\s|$)",
                RegexOption.IGNORE_CASE
            )
    }
}
