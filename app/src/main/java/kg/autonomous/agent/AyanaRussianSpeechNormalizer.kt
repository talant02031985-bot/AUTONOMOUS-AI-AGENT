package kg.autonomous.agent

import java.util.Locale

/**
 * AYANA Russian Speech Normalizer v1.0 — presentation-only pronunciation.
 *
 * Semantic text is never rewritten. This class prepares a separate string only
 * for Marin TTS so Latin product names and common technical abbreviations are
 * pronounced consistently in Russian. History, UI text, Agent Core replies and
 * command semantics keep the original spelling.
 *
 * The glossary is intentionally explicit and fail-safe: there is no generic
 * transliteration of arbitrary Latin text, URLs, package names, passwords or
 * user data. New pronunciations must be added as reviewed glossary entries.
 */
class AyanaRussianSpeechNormalizer {

    data class Result(
        val text: String,
        val changed: Boolean,
        val appliedRules: List<String>
    )

    private data class Rule(
        val id: String,
        val pattern: Regex,
        val replacement: String
    )

    fun prepare(input: String): Result {
        if (input.isBlank()) {
            return Result(
                text = input,
                changed = false,
                appliedRules = emptyList()
            )
        }

        var result = input
        val applied = linkedSetOf<String>()

        for (rule in RULES) {
            val before = result
            result = rule.pattern.replace(result, rule.replacement)
            if (result != before) {
                applied += rule.id
            }
        }

        result = normalizePresentationWhitespace(result)

        return Result(
            text = result,
            changed = result != input,
            appliedRules = applied.toList()
        )
    }

    private fun normalizePresentationWhitespace(value: String): String =
        value
            .replace(Regex("[\\t\\r\\n]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()

    companion object {

        private fun tokenRule(
            id: String,
            source: String,
            replacement: String
        ): Rule =
            Rule(
                id = id,
                pattern = Regex(
                    "(?iu)(?<![\\p{L}\\p{N}_])(?:$source)(?![\\p{L}\\p{N}_])"
                ),
                replacement = replacement
            )

        /**
         * Longest/specific phrases come first so a broad token rule cannot break
         * a more natural compound pronunciation.
         */
        private val RULES = listOf(
            tokenRule(
                id = "google_maps",
                source = "google\\s+maps",
                replacement = "Гугл Карты"
            ),
            tokenRule(
                id = "google_play",
                source = "google\\s+play",
                replacement = "Гугл Плей"
            ),
            tokenRule(
                id = "play_market",
                source = "play\\s+market",
                replacement = "Плей Маркет"
            ),
            tokenRule(
                id = "samsung_notes",
                source = "samsung\\s+notes",
                replacement = "Самсунг Ноутс"
            ),
            tokenRule(
                id = "google_drive",
                source = "google\\s+drive",
                replacement = "Гугл Диск"
            ),
            tokenRule(
                id = "google_photos",
                source = "google\\s+photos",
                replacement = "Гугл Фото"
            ),
            tokenRule(
                id = "google_translate",
                source = "google\\s+translate",
                replacement = "Гугл Переводчик"
            ),
            tokenRule(
                id = "chatgpt",
                source = "chat\\s*-?\\s*gpt|chatgpt",
                replacement = "Чат Джи-Пи-Ти"
            ),
            tokenRule(
                id = "openai",
                source = "open\\s*-?\\s*ai|openai",
                replacement = "Оупен Эй-Ай"
            ),
            tokenRule(
                id = "youtube",
                source = "you\\s*-?\\s*tube|youtube",
                replacement = "Ютуб"
            ),
            tokenRule(
                id = "gmail",
                source = "g\\s*-?\\s*mail|gmail",
                replacement = "Джимейл"
            ),
            tokenRule(
                id = "whatsapp",
                source = "whats\\s*-?\\s*app|whatsapp",
                replacement = "Ватсап"
            ),
            tokenRule(
                id = "telegram",
                source = "telegram",
                replacement = "Телеграм"
            ),
            tokenRule(
                id = "chrome",
                source = "chrome",
                replacement = "Хром"
            ),
            tokenRule(
                id = "android",
                source = "android",
                replacement = "Андроид"
            ),
            tokenRule(
                id = "samsung",
                source = "samsung",
                replacement = "Самсунг"
            ),
            tokenRule(
                id = "google",
                source = "google",
                replacement = "Гугл"
            ),
            tokenRule(
                id = "bluetooth",
                source = "bluetooth",
                replacement = "Блютуз"
            ),
            tokenRule(
                id = "wifi",
                source = "wi\\s*-?\\s*fi|wifi",
                replacement = "Вай-Фай"
            ),
            tokenRule(
                id = "https",
                source = "https",
                replacement = "Эйч-Ти-Ти-Пи-Эс"
            ),
            tokenRule(
                id = "http",
                source = "http",
                replacement = "Эйч-Ти-Ти-Пи"
            ),
            tokenRule(
                id = "usb",
                source = "usb",
                replacement = "Ю-Эс-Би"
            ),
            tokenRule(
                id = "nfc",
                source = "nfc",
                replacement = "Эн-Эф-Си"
            ),
            tokenRule(
                id = "vpn",
                source = "vpn",
                replacement = "Ви-Пи-Эн"
            ),
            tokenRule(
                id = "api",
                source = "api",
                replacement = "Эй-Пи-Ай"
            ),
            tokenRule(
                id = "apk",
                source = "apk",
                replacement = "Эй-Пи-Кей"
            ),
            tokenRule(
                id = "pdf",
                source = "pdf",
                replacement = "Пи-Ди-Эф"
            ),
            tokenRule(
                id = "url",
                source = "url",
                replacement = "Ю-Ар-Эл"
            ),
            tokenRule(
                id = "gpt",
                source = "gpt",
                replacement = "Джи-Пи-Ти"
            ),
            tokenRule(
                id = "ai",
                source = "ai",
                replacement = "Эй-Ай"
            )
        )
    }
}
