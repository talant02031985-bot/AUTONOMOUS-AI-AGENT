package kg.autonomous.agent

import java.util.Locale
import kotlin.math.max

/**
 * AYANA Local App Phonetic Router v1.1 — CANONICAL SOUND ROUTING.
 *
 * Ranks short ASR-distorted app names against the already-approved local alias
 * vocabulary. It does NOT launch anything and does NOT trust a package name.
 * VoiceService must resolve the returned aliases through AyanaAppResolver, group
 * candidates by the actually observed launcher package, and fail closed on
 * ambiguity before any launch.
 *
 * This is a general phonetic layer, not an app-specific patch. The canonical
 * sound key handles common Russian ASR sound collapses (for example "дж" ->
 * "д") and repeated-grapheme stretching (for example "питти" -> "пити").
 * That repairs device-observed short-name variants such as «чат де питти»
 * without weakening the fail-closed launcher-package verification.
 * Pure Kotlin; intentionally JVM-testable.
 */
class AyanaLocalAppPhoneticRouter {

    data class Candidate(
        val alias: String,
        val score: Int,
        val literalScore: Int,
        val phoneticScore: Int
    )

    data class ResolvedTarget(
        val packageName: String,
        val label: String
    )

    data class ResolvedCandidate(
        val alias: String,
        val packageName: String,
        val label: String,
        val score: Int,
        val literalScore: Int,
        val phoneticScore: Int
    )

    data class Selection(
        val selected: ResolvedCandidate?,
        val runnerUp: ResolvedCandidate?,
        val ambiguous: Boolean
    )

    fun rank(
        query: String,
        aliases: Collection<String>,
        explicitLaunch: Boolean
    ): List<Candidate> {
        val clean = normalize(query)
        if (!isEligible(clean, explicitLaunch)) {
            return emptyList()
        }

        val compactQuery = compact(clean)
        val phoneticQuery = phoneticCompact(clean)

        val threshold =
            when {
                compactQuery.length <= 5 ->
                    if (explicitLaunch) 88 else 92

                explicitLaunch ->
                    EXPLICIT_THRESHOLD

                else ->
                    BARE_THRESHOLD
            }

        return aliases
            .asSequence()
            .map(::normalize)
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { alias ->
                val literal = similarity100(
                    compactQuery,
                    compact(alias)
                )

                val phonetic = similarity100(
                    phoneticQuery,
                    phoneticCompact(alias)
                )

                val score = max(literal, phonetic)

                if (score < threshold) {
                    null
                } else {
                    Candidate(
                        alias = alias,
                        score = score,
                        literalScore = literal,
                        phoneticScore = phonetic
                    )
                }
            }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenByDescending { it.phoneticScore }
                    .thenBy { it.alias.length }
                    .thenBy { it.alias }
            )
            .take(MAX_CANDIDATES)
            .toList()
    }

    fun selectResolved(
        candidates: List<Candidate>,
        resolver: (String) -> ResolvedTarget?,
        minPackageMargin: Int = DEFAULT_PACKAGE_MARGIN
    ): Selection {
        if (candidates.isEmpty()) {
            return Selection(
                selected = null,
                runnerUp = null,
                ambiguous = false
            )
        }

        val resolved =
            candidates
                .take(MAX_RESOLUTION_CANDIDATES)
                .mapNotNull { candidate ->
                    val target =
                        try {
                            resolver(candidate.alias)
                        } catch (_: Exception) {
                            null
                        }
                            ?: return@mapNotNull null

                    if (target.packageName.isBlank()) {
                        return@mapNotNull null
                    }

                    ResolvedCandidate(
                        alias = candidate.alias,
                        packageName = target.packageName,
                        label = target.label,
                        score = candidate.score,
                        literalScore = candidate.literalScore,
                        phoneticScore = candidate.phoneticScore
                    )
                }

        val packageWinners =
            resolved
                .groupBy { it.packageName }
                .mapNotNull { (_, group) ->
                    group.maxWithOrNull(
                        compareBy<ResolvedCandidate> { it.score }
                            .thenBy { it.phoneticScore }
                            .thenBy { it.literalScore }
                    )
                }
                .sortedWith(
                    compareByDescending<ResolvedCandidate> { it.score }
                        .thenByDescending { it.phoneticScore }
                        .thenByDescending { it.literalScore }
                        .thenBy { it.alias }
                )

        val best = packageWinners.firstOrNull()
        val second = packageWinners.getOrNull(1)
        val ambiguous =
            best != null &&
                second != null &&
                best.score - second.score <
                    minPackageMargin.coerceAtLeast(1)

        return Selection(
            selected = if (ambiguous) null else best,
            runnerUp = second,
            ambiguous = ambiguous
        )
    }

    private fun isEligible(
        clean: String,
        explicitLaunch: Boolean
    ): Boolean {
        if (clean.isBlank()) {
            return false
        }

        val words =
            clean
                .split(' ')
                .filter { it.isNotBlank() }

        if (words.isEmpty() || words.size > MAX_QUERY_WORDS) {
            return false
        }

        val compact = compact(clean)
        if (compact.length !in MIN_QUERY_CHARS..MAX_QUERY_CHARS) {
            return false
        }

        if (explicitLaunch) {
            return true
        }

        // Bare short phrases are allowed only when they look like a name, not a
        // question, settings command, search request, or lifecycle instruction.
        return BARE_REJECT_MARKERS.none { marker ->
            clean == marker ||
                clean.startsWith("$marker ") ||
                clean.contains(" $marker ")
        }
    }

    private fun phoneticCompact(
        value: String
    ): String {
        val compact =
            normalize(value)
                .replace("дж", "д")
                .replace("дз", "з")
                .replace("тс", "ц")
                .replace("ь", "")
                .replace("ъ", "")
                .replace(" ", "")

        return collapseRepeatedGraphemes(
            compact
        )
    }

    private fun collapseRepeatedGraphemes(
        value: String
    ): String {
        if (value.length < 2) {
            return value
        }

        val result =
            StringBuilder(
                value.length
            )

        var previous: Char? =
            null

        for (char in value) {
            if (char != previous) {
                result.append(char)
                previous = char
            }
        }

        return result.toString()
    }

    private fun compact(
        value: String
    ): String =
        normalize(value)
            .replace(" ", "")

    private fun similarity100(
        left: String,
        right: String
    ): Int {
        if (left.isBlank() || right.isBlank()) {
            return 0
        }

        if (left == right) {
            return 100
        }

        val distance = levenshtein(left, right)
        val denominator = max(left.length, right.length)
            .coerceAtLeast(1)

        return (((denominator - distance).toDouble() / denominator.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun levenshtein(
        left: String,
        right: String
    ): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val substitution =
                    previous[j - 1] +
                        if (left[i - 1] == right[j - 1]) 0 else 1

                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    substitution
                )
            }

            val swap = previous
            previous = current
            current = swap
        }

        return previous[right.length]
    }

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
        private const val EXPLICIT_THRESHOLD = 78
        private const val BARE_THRESHOLD = 84
        private const val MIN_QUERY_CHARS = 4
        private const val MAX_QUERY_CHARS = 28
        private const val MAX_QUERY_WORDS = 4
        private const val MAX_CANDIDATES = 12
        private const val MAX_RESOLUTION_CANDIDATES = 8
        private const val DEFAULT_PACKAGE_MARGIN = 6

        private val BARE_REJECT_MARKERS =
            setOf(
                "что",
                "кто",
                "как",
                "где",
                "когда",
                "почему",
                "зачем",
                "сколько",
                "расскажи",
                "объясни",
                "помоги",
                "можешь",
                "можно",
                "найди",
                "поищи",
                "покажи",
                "закрой",
                "сверни",
                "назад",
                "домой",
                "настройки",
                "уведомления",
                "разрешения",
                "информация",
                "поиск"
            )
    }
}
