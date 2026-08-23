package kg.autonomous.agent

import java.util.Locale

/**
 * AYANA Cancel Phrase Detector v1.0 — BARGE-IN RELIABILITY.
 *
 * Centralizes STOP/cancel recognition for both ordinary execution and Marin
 * barge-in. During speech it is echo-aware: AYANA's own current spoken text
 * cannot cancel the command by reflecting a stop word back into Sherpa.
 *
 * A very narrow ASR-partial rule accepts the truncated Russian prefix "сто"
 * as "стоп" only while Marin is speaking, only for a short cancel-shaped
 * utterance, and only when neither "сто" nor "стоп" occurs in AYANA's current
 * spoken text. This addresses endpoint/phoneme truncation without weakening the
 * normal cancel grammar outside barge-in.
 *
 * Pure Kotlin; intentionally JVM-testable.
 */
class AyanaCancelPhraseDetector(
    wakeVariants: Collection<String>
) {

    data class Match(
        val matched: Boolean,
        val reason: String = "",
        val normalized: String = "",
        val token: String = ""
    )

    private val wakes =
        wakeVariants
            .asSequence()
            .map(::normalize)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
            .toList()

    fun detect(
        value: String,
        speaking: Boolean,
        activeSpokenText: String = ""
    ): Match {
        val normalized = normalize(value)
        if (normalized.isBlank()) {
            return Match(false, normalized = normalized)
        }

        val stripped = stripLeadingWake(normalized)
        val hadWake = stripped != normalized
        if (stripped.isBlank()) {
            return Match(false, normalized = normalized)
        }

        if (!speaking) {
            return detectStandard(stripped, normalized)
        }

        val spoken = normalize(activeSpokenText)
        val words = words(stripped)

        // During Marin speech, exact cancel tokens are accepted only when they
        // are not present in AYANA's own current speech. This closes the old
        // self-echo hole where standard cancel matching bypassed barge-in guards.
        for (keyword in SHORT_KEYWORDS) {
            if (
                words.any { it == keyword } &&
                !containsWholeWord(spoken, keyword)
            ) {
                return Match(
                    matched = true,
                    reason = "barge_in_keyword",
                    normalized = normalized,
                    token = keyword
                )
            }
        }

        for (phrase in LONG_PREFIXES) {
            if (
                stripped.startsWith(phrase) &&
                !spoken.contains(phrase)
            ) {
                return Match(
                    matched = true,
                    reason = "barge_in_phrase",
                    normalized = normalized,
                    token = phrase
                )
            }
        }

        // Safe ASR-partial recovery for device-observed "стоп" -> "сто".
        // Do not accept it outside SPEAKING, do not accept long transcripts,
        // and do not accept it if AYANA herself is currently saying "сто".
        if (
            words.size <= MAX_BARGE_PARTIAL_WORDS &&
            words.any { it == STOP_TRUNCATED_PREFIX } &&
            !containsWholeWord(spoken, STOP_TRUNCATED_PREFIX) &&
            !containsWholeWord(spoken, STOP_FULL)
        ) {
            return Match(
                matched = true,
                reason = "barge_in_stop_truncated_prefix",
                normalized = normalized,
                token = STOP_TRUNCATED_PREFIX
            )
        }

        // If AYANA's own speech contains a cancel token, an explicit wake name
        // is an additional disambiguation signal and restores the normal grammar.
        if (hadWake) {
            val standard = detectStandard(stripped, normalized)
            if (standard.matched) {
                return standard.copy(
                    reason = "barge_in_wake_disambiguated_${standard.reason}"
                )
            }
        }

        return Match(false, normalized = normalized)
    }

    private fun detectStandard(
        stripped: String,
        originalNormalized: String
    ): Match {
        if (stripped in EXACT_PHRASES) {
            return Match(
                matched = true,
                reason = "standard_exact",
                normalized = originalNormalized,
                token = stripped
            )
        }

        val words = words(stripped)
        if (words.size > MAX_STANDARD_WORDS) {
            return Match(false, normalized = originalNormalized)
        }

        val keyword =
            words.firstOrNull { it in SHORT_KEYWORDS }

        if (keyword != null) {
            return Match(
                matched = true,
                reason = "standard_keyword",
                normalized = originalNormalized,
                token = keyword
            )
        }

        val prefix =
            LONG_PREFIXES.firstOrNull {
                stripped.startsWith(it)
            }

        if (prefix != null) {
            return Match(
                matched = true,
                reason = "standard_phrase",
                normalized = originalNormalized,
                token = prefix
            )
        }

        return Match(false, normalized = originalNormalized)
    }

    private fun stripLeadingWake(
        value: String
    ): String {
        for (wake in wakes) {
            if (value == wake) {
                return ""
            }
            if (value.startsWith("$wake ")) {
                return value
                    .removePrefix("$wake ")
                    .trim()
            }
        }
        return value
    }

    private fun containsWholeWord(
        value: String,
        word: String
    ): Boolean =
        words(value)
            .any { it == word }

    private fun words(
        value: String
    ): List<String> =
        value
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    private fun normalize(
        value: String
    ): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        private const val MAX_STANDARD_WORDS = 5
        private const val MAX_BARGE_PARTIAL_WORDS = 3
        private const val STOP_FULL = "стоп"
        private const val STOP_TRUNCATED_PREFIX = "сто"

        private val SHORT_KEYWORDS =
            setOf(
                "стоп",
                "отмена",
                "отмени",
                "хватит"
            )

        private val LONG_PREFIXES =
            listOf(
                "прекрати",
                "останови команд",
                "останови выполн"
            )

        private val EXACT_PHRASES =
            setOf(
                "стоп",
                "отмена",
                "отмени",
                "прекрати",
                "прекрати выполнение",
                "останови",
                "останови команду",
                "останови выполнение",
                "хватит",
                "все хватит",
                "всё хватит"
            )
    }
}
