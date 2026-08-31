package kg.autonomous.agent

import java.util.Locale

/**
 * AYANA Composite Intent Gate v1.1 — PRE-EXECUTION GOAL INTEGRITY.
 *
 * v1.1 keeps the pure classifier contract and hardens Cyrillic confirmation/constraint
 * extraction so execution payloads never retain the confirmation wrapper.
 * Pure-Kotlin pre-execution classifier. It does not perform side effects.
 * The caller must inspect the whole command before dispatching any clause.
 * This prevents partial execution when the original goal contains sequencing,
 * conditions, confirmation requirements, data-only blocks, or explicit runtime
 * constraints.
 */
object AyanaCompositeIntentGate {

    enum class DecisionType {
        PASS_THROUGH,
        DATA_ONLY,
        REQUIRE_CONFIRMATION,
        CONDITIONAL,
        COMPOSITE,
        ENVIRONMENT_CONSTRAINED,
        INVALID
    }

    enum class Comparator {
        LT, LTE, EQ, GTE, GT
    }

    enum class Metric {
        BATTERY_PERCENT,
        MEDIA_VOLUME_LEVEL,
        MEDIA_VOLUME_PERCENT,
        BRIGHTNESS_PERCENT
    }

    data class Condition(
        val metric: Metric,
        val comparator: Comparator,
        val value: Int,
        val scale: Int? = null,
        val raw: String
    )

    data class Constraints(
        val forbidSettings: Boolean = false,
        val forbidNetwork: Boolean = false
    )

    data class Decision(
        val type: DecisionType,
        val original: String,
        val executableCommand: String? = null,
        val clauses: List<String> = emptyList(),
        val condition: Condition? = null,
        val constraints: Constraints = Constraints(),
        val reason: String = ""
    ) {
        val requiresAtomicHandling: Boolean
            get() = type in setOf(
                DecisionType.REQUIRE_CONFIRMATION,
                DecisionType.CONDITIONAL,
                DecisionType.COMPOSITE,
                DecisionType.ENVIRONMENT_CONSTRAINED
            )
    }

    data class Snapshot(
        val batteryPercent: Int? = null,
        val mediaVolumeLevel: Int? = null,
        val mediaVolumeScale: Int? = null,
        val brightnessPercent: Int? = null
    )

    enum class ConditionResult {
        TRUE,
        FALSE,
        UNKNOWN
    }

    fun analyze(raw: String): Decision {
        val original = raw.trim()
        val c = normalize(original)
        if (c.isBlank()) {
            return Decision(
                type = DecisionType.INVALID,
                original = original,
                reason = "empty_command"
            )
        }

        val constraints = Constraints(
            forbidSettings = forbidsSettings(c),
            forbidNetwork = forbidsNetwork(c)
        )

        if (
            (isExplicitDataOnly(c) && containsActionLanguage(c)) ||
            isQuotedActionDataContext(original)
        ) {
            return Decision(
                type = DecisionType.DATA_ONLY,
                original = original,
                constraints = constraints,
                reason =
                    if (isQuotedActionDataContext(original)) {
                        "quoted_action_data_non_execution"
                    } else {
                        "explicit_data_only_non_execution"
                    }
            )
        }

        parseConfirmation(original, c)?.let { executable ->
            return Decision(
                type = DecisionType.REQUIRE_CONFIRMATION,
                original = original,
                executableCommand = executable,
                constraints = constraints,
                reason = "explicit_confirmation_required"
            )
        }

        parseConditional(original, c)?.let { parsed ->
            return Decision(
                type = DecisionType.CONDITIONAL,
                original = original,
                executableCommand = parsed.second,
                condition = parsed.first,
                constraints = constraints,
                reason = "explicit_condition"
            )
        }

        // A state-changing conditional that cannot be evaluated locally must
        // still be captured by the gate. It must never fall through to a
        // simple-action router which could execute the consequent unconditionally.
        if (looksConditional(c) && containsActionLanguage(c)) {
            return Decision(
                type = DecisionType.CONDITIONAL,
                original = original,
                executableCommand = extractConditionalConsequent(original, c),
                condition = null,
                constraints = constraints,
                reason = "unsupported_or_ambiguous_condition"
            )
        }

        val clauses = splitComposite(original)
        if (clauses.size >= 2 && clauses.count { containsActionLanguage(normalize(it)) } >= 2) {
            return Decision(
                type = DecisionType.COMPOSITE,
                original = original,
                clauses = clauses,
                constraints = constraints,
                reason = "multi_step_goal"
            )
        }

        if ((constraints.forbidSettings || constraints.forbidNetwork) && containsActionLanguage(c)) {
            return Decision(
                type = DecisionType.ENVIRONMENT_CONSTRAINED,
                original = original,
                executableCommand = stripConstraintPhrases(original),
                constraints = constraints,
                reason = "runtime_constraint"
            )
        }

        return Decision(
            type = DecisionType.PASS_THROUGH,
            original = original,
            constraints = constraints
        )
    }

    fun evaluate(condition: Condition, snapshot: Snapshot): ConditionResult {
        val actual: Int = when (condition.metric) {
            Metric.BATTERY_PERCENT -> snapshot.batteryPercent
            Metric.BRIGHTNESS_PERCENT -> snapshot.brightnessPercent
            Metric.MEDIA_VOLUME_LEVEL -> {
                val level = snapshot.mediaVolumeLevel
                val sourceScale = snapshot.mediaVolumeScale
                val targetScale = condition.scale
                when {
                    level == null -> null
                    targetScale != null && targetScale > 0 && sourceScale != null && sourceScale > 0 ->
                        kotlin.math.round(level.toDouble() * targetScale / sourceScale).toInt()
                    else -> level
                }
            }
            Metric.MEDIA_VOLUME_PERCENT -> {
                val level = snapshot.mediaVolumeLevel
                val scale = snapshot.mediaVolumeScale
                if (level == null || scale == null || scale <= 0) null
                else kotlin.math.round((level * 100.0) / scale).toInt()
            }
        } ?: return ConditionResult.UNKNOWN

        val ok = when (condition.comparator) {
            Comparator.LT -> actual < condition.value
            Comparator.LTE -> actual <= condition.value
            Comparator.EQ -> actual == condition.value
            Comparator.GTE -> actual >= condition.value
            Comparator.GT -> actual > condition.value
        }
        return if (ok) ConditionResult.TRUE else ConditionResult.FALSE
    }

    fun isPositiveConfirmation(raw: String): Boolean {
        val c = normalize(raw)
        val compact = c.replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        return compact in setOf(
            "да", "подтверждаю", "подтвердить", "выполняй", "выполни", "продолжай", "согласен", "согласна"
        ) || compact.startsWith("да ") || compact.startsWith("подтверждаю ")
    }

    fun isNegativeConfirmation(raw: String): Boolean {
        val c = normalize(raw)
        val compact = c.replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        return compact in setOf(
            "нет", "отмена", "отмени", "не выполняй", "не надо", "не делай", "стоп"
        ) || compact.startsWith("нет ") || compact.startsWith("отмени ")
    }

    private fun parseConfirmation(original: String, c: String): String? {
        if (!containsActionLanguage(c)) return null

        val markers = listOf(
            "спроси подтверждение",
            "спроси подтверждения",
            "запроси подтверждение",
            "запроси подтверждения",
            "сначала спроси",
            "сначала запроси подтверждение",
            "до моего подтверждения",
            "пока я не подтвержу",
            "только после моего подтверждения",
            "только после подтверждения"
        )
        if (markers.none { c.contains(it) }) return null

        // Do not rely on Java \b for Cyrillic: boundary behavior differs across
        // runtimes and previously left the entire confirmation wrapper executable.
        // Find the first real imperative payload after the confirmation language.
        val payload = extractFirstImperativeSuffix(original)
        return payload
            ?.takeIf { it.isNotBlank() && containsActionLanguage(normalize(it)) }
            ?: original
    }

    private fun parseConditional(original: String, c: String): Pair<Condition, String>? {
        if (!c.startsWith("если ") || !containsActionLanguage(c)) return null

        val candidates = listOf(" то ", ", то ", ", тогда ", " тогда ", "; то ", "; тогда ")
        var splitAt = -1
        var splitToken = ""
        for (token in candidates) {
            val idx = c.indexOf(token)
            if (idx > 5 && (splitAt == -1 || idx < splitAt)) {
                splitAt = idx
                splitToken = token
            }
        }
        if (splitAt == -1) {
            val comma = c.indexOf(',')
            if (comma > 5) {
                splitAt = comma
                splitToken = ","
            }
        }
        if (splitAt == -1) return null

        val conditionTextNormalized = c.substring(5, splitAt).trim()
        val commandNormalized = c.substring(splitAt + splitToken.length).trim()
        if (commandNormalized.isBlank() || !containsActionLanguage(commandNormalized)) return null

        val command = extractSuffixByNormalized(original, commandNormalized)
            ?: commandNormalized

        val condition = parseCondition(conditionTextNormalized) ?: return null
        return condition to command.trim()
    }

    private fun parseCondition(text: String): Condition? {
        val comparator = when {
            text.contains("не больше") || text.contains("меньше или равно") || text.contains("ниже или равно") || text.contains("<=") -> Comparator.LTE
            text.contains("не меньше") || text.contains("больше или равно") || text.contains("выше или равно") || text.contains(">=") -> Comparator.GTE
            Regex("(?:меньше|ниже|<)").containsMatchIn(text) -> Comparator.LT
            Regex("(?:больше|выше|>)").containsMatchIn(text) -> Comparator.GT
            text.contains("равен") || text.contains("равна") || text.contains("равно") || text.contains("=") -> Comparator.EQ
            else -> null
        } ?: return null

        val number = Regex("(\\d{1,3})").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null

        val metric = when {
            text.contains("батар") || text.contains("заряд") -> Metric.BATTERY_PERCENT
            text.contains("ярк") -> Metric.BRIGHTNESS_PERCENT
            text.contains("громк") || text.contains("звук") -> {
                if (text.contains("%") || text.contains("процент")) Metric.MEDIA_VOLUME_PERCENT
                else Metric.MEDIA_VOLUME_LEVEL
            }
            else -> return null
        }

        if ((metric == Metric.BATTERY_PERCENT || metric == Metric.BRIGHTNESS_PERCENT || metric == Metric.MEDIA_VOLUME_PERCENT) && number !in 0..100) {
            return null
        }

        val scale = if (metric == Metric.MEDIA_VOLUME_LEVEL) {
            Regex("(?:из|/)\\s*(\\d{1,3})").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        } else null

        return Condition(metric, comparator, number, scale, text)
    }

    private fun splitComposite(raw: String): List<String> {
        val normalized = raw.replace('—', '-').replace('–', '-')
        val regex = Regex("(?i)\\s*(?:;|,?\\s+затем\\s+|,?\\s+потом\\s+|,?\\s+после этого\\s+|,?\\s+после чего\\s+)")
        val firstPass = regex.split(normalized).map { it.trim(' ', ',', ';') }.filter { it.isNotBlank() }
        if (firstPass.size >= 2) return firstPass

        // Plain "и" is accepted only when the right clause starts with a known
        // imperative. Avoid Java \b here: Cyrillic word-boundary behavior varies
        // across runtimes/flags and must not decide execution safety.
        val andRegex = Regex("(?i)\\s+и\\s+(?=(?:открой|запусти|включи|выключи|закрой|сверни|установи|поставь|выставь|задай|сделай|измени|увеличь|уменьши|прибавь|убавь|нажми|выбери|удали|очисти|скопируй|введи|напиши|перейди|зайди|найди|поищи|покажи|прокрути|пролистай|вернись|остановись)(?:\\s|$))")
        return andRegex.split(normalized).map { it.trim(' ', ',', ';') }.filter { it.isNotBlank() }
    }

    private fun stripConstraintPhrases(raw: String): String {
        var result = raw
        listOf(
            "не открывай настройки",
            "настройки не открывай",
            "не используй интернет",
            "без интернета",
            "не выходи в интернет"
        ).forEach { phrase ->
            result = result.replace(
                Regex(Regex.escape(phrase), RegexOption.IGNORE_CASE),
                " "
            )
        }
        return result
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', ';', ':', '.')
    }

    private fun extractFirstImperativeSuffix(raw: String): String? {
        val lower = raw.lowercase(Locale.ROOT).replace('ё', 'е')
        val candidates = EXECUTABLE_IMPERATIVES
            .mapNotNull { verb ->
                var from = 0
                var best: Int? = null
                while (from < lower.length) {
                    val index = lower.indexOf(verb, from)
                    if (index < 0) break
                    val beforeOk = index == 0 || !lower[index - 1].isLetterOrDigit()
                    val after = index + verb.length
                    val afterOk = after >= lower.length || !lower[after].isLetterOrDigit()
                    if (beforeOk && afterOk) {
                        best = index
                        break
                    }
                    from = index + 1
                }
                best
            }
        val index = candidates.minOrNull() ?: return null
        return raw.substring(index).trim(' ', ',', ';', ':', '.')
    }

    private fun isQuotedActionDataContext(raw: String): Boolean {
        val lower = raw.lowercase(Locale.ROOT).replace('ё', 'е')
        val meta = listOf(
            "пример команды",
            "пример фразы",
            "проанализируй фразу",
            "проанализируй текст",
            "разбери фразу",
            "цитата",
            "как текст",
            "как данные"
        ).any { lower.contains(it) }
        if (!meta) return false

        val quoted = Regex("[«\"]([^»\"]+)[»\"]")
            .findAll(raw)
            .map { it.groupValues[1] }
            .toList()
        return quoted.any { containsActionLanguage(normalize(it)) }
    }

    private fun extractSuffixByNormalized(original: String, suffixNormalized: String): String? {
        val tokens = suffixNormalized.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val first = tokens.first()
        val lower = original.lowercase(Locale.ROOT).replace('ё', 'е')
        val idx = lower.lastIndexOf(first)
        return if (idx >= 0) original.substring(idx) else null
    }


    private fun looksConditional(c: String): Boolean =
        c.startsWith("если ") || c.contains("; если ") || c.contains(", если ") ||
            (c.contains("если") && c.contains(" то "))

    private fun extractConditionalConsequent(original: String, c: String): String? {
        val tokens = listOf(", то ", " то ", ", тогда ", " тогда ", "; то ", "; тогда ")
        var splitAt = -1
        var tokenLength = 0
        for (token in tokens) {
            val idx = c.indexOf(token)
            if (idx > 0 && (splitAt == -1 || idx < splitAt)) {
                splitAt = idx
                tokenLength = token.length
            }
        }
        if (splitAt == -1) {
            val comma = c.indexOf(',')
            if (comma > 0) {
                splitAt = comma
                tokenLength = 1
            }
        }
        if (splitAt == -1) return null
        val suffix = c.substring(splitAt + tokenLength).trim()
        return extractSuffixByNormalized(original, suffix) ?: suffix.takeIf { it.isNotBlank() }
    }

    private fun isExplicitDataOnly(c: String): Boolean =
        c.contains("только как данные") ||
            c.contains("считай это данными") ||
            c.contains("рассматривай это как данные") ||
            c.contains("не выполняй его как команд") ||
            c.contains("не выполняй это как команд") ||
            c.contains("ничего из него не выполняй") ||
            c.contains("ничего из этого не выполняй")

    private fun forbidsSettings(c: String): Boolean =
        c.contains("не открывай настройки") || c.contains("настройки не открывай")

    private fun forbidsNetwork(c: String): Boolean =
        c.contains("не используй интернет") || c.contains("без интернета") || c.contains("не выходи в интернет")

    private fun containsActionLanguage(c: String): Boolean {
        val tokens = c.split(' ')
        return ACTION_STEMS.any { stem -> tokens.any { it.startsWith(stem) } } ||
            c.contains("громче") || c.contains("тише") || c.contains("без звука") ||
            c == "домой" || c.contains("на главный экран")
    }

    private fun normalize(raw: String): String = raw
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace('–', '-')
        .replace('—', '-')
        .replace(Regex("[«»\"]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val EXECUTABLE_IMPERATIVES = listOf(
        "открой", "запусти", "включи", "выключи", "закрой", "сверни",
        "установи", "поставь", "выставь", "задай", "сделай", "измени",
        "увеличь", "уменьши", "прибавь", "убавь", "нажми", "выбери",
        "удали", "очисти", "скопируй", "введи", "напиши", "перейди",
        "создай", "запомни", "зайди", "найди", "поищи", "покажи",
        "прокрути", "пролистай", "вернись", "остановись"
    )

    private val ACTION_STEMS = listOf(
        "открой", "запуст", "включ", "выключ", "закрой", "сверн",
        "установ", "постав", "выстав", "задай", "сделай", "измени",
        "увелич", "уменьш", "прибав", "убав", "нажми", "выбери",
        "удали", "очист", "скопир", "введи", "напиши", "перейди",
        "создай", "запомни", "зайд", "найд", "поищ", "покаж",
        "прокрут", "пролист", "верни", "останов"
    )
}
