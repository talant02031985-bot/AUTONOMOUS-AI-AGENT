package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * AYANA Semantic Target Resolver v1.0 — universal, fail-closed target identity.
 *
 * This layer is deliberately pure Kotlin/JSON. It does not dispatch Android input.
 * It resolves a requested semantic target against one factual Accessibility snapshot,
 * preserving the exact evidence used for the decision. No coordinate/position-only
 * fallback is ever invented here.
 */
class AyanaSemanticTargetResolver {

    enum class Mode {
        CLICK,
        INPUT,
        VERIFY
    }

    fun resolve(
        screen: JSONObject,
        requestedTarget: String?,
        mode: Mode
    ): JSONObject {

        val requested = requestedTarget.orEmpty().trim()

        if (mode != Mode.INPUT && requested.isBlank()) {
            return failure(
                status = "invalid_target",
                requested = requested,
                mode = mode,
                message = "Семантическая цель не указана"
            )
        }

        val snapshotOk =
            screen.optBoolean(
                "snapshot_success",
                screen.optBoolean("success", false)
            )

        if (!snapshotOk) {
            return failure(
                status = "snapshot_unavailable",
                requested = requested,
                mode = mode,
                message = "Accessibility snapshot недоступен"
            )
        }

        val nodes = screen.optJSONArray("nodes")
        val contentState = contentState(screen)

        if (mode == Mode.INPUT && requested.isBlank()) {
            return resolveImplicitEditable(
                nodes = nodes,
                contentState = contentState
            )
        }

        val candidates = mutableListOf<Candidate>()

        if (nodes != null) {
            for (index in 0 until nodes.length()) {
                val node = nodes.optJSONObject(index) ?: continue
                candidateFor(
                    node = node,
                    arrayIndex = index,
                    requested = requested,
                    mode = mode
                )?.let(candidates::add)
            }
        }

        val ranked =
            candidates
                .sortedWith(
                    compareByDescending<Candidate> { it.score }
                        .thenByDescending { it.exactness }
                        .thenByDescending { it.actionableBonus }
                        .thenBy { it.arrayIndex }
                )

        val top = ranked.firstOrNull()

        if (top == null || top.score < minimumScore(mode)) {
            val exactVisible =
                exactVisibleFallback(
                    screen = screen,
                    requested = requested,
                    mode = mode,
                    contentState = contentState
                )

            if (exactVisible != null) {
                return exactVisible
            }

            return failure(
                status = if (contentState in setOf("unavailable", "structure_only", "unknown")) {
                    "content_unavailable"
                } else {
                    "target_not_found"
                },
                requested = requested,
                mode = mode,
                message = if (contentState in setOf("unavailable", "structure_only", "unknown")) {
                    "Экран определён, но надёжной семантической цели в доступном содержимом нет"
                } else {
                    "Семантическая цель не найдена"
                }
            )
                .put("content_state", contentState)
                .put("candidate_count", ranked.size)
        }

        val secondDistinct =
            ranked
                .drop(1)
                .firstOrNull { candidate ->
                    !sameConcreteIdentity(
                        top,
                        candidate
                    )
                }

        val secondScore = secondDistinct?.score ?: -1
        val margin = if (secondScore >= 0) top.score - secondScore else top.score

        if (
            secondDistinct != null &&
            secondScore >= minimumScore(mode) &&
            margin < AMBIGUITY_MARGIN
        ) {
            return failure(
                status = "ambiguous_target",
                requested = requested,
                mode = mode,
                message = "На экране найдено несколько почти равнозначных целей; действие остановлено"
            )
                .put("content_state", contentState)
                .put("candidate_count", ranked.size)
                .put("score", top.score)
                .put("second_score", secondScore)
                .put("margin", margin)
                .put("candidate", top.toJson())
                .put("competing_candidate", secondDistinct.toJson())
        }

        val confidence = confidence(top.score, margin, top.exactness)

        return JSONObject()
            .put("resolved", true)
            .put("success", true)
            .put("status", "resolved")
            .put("reason", "semantic_target_resolved")
            .put("mode", mode.name.lowercase(Locale.ROOT))
            .put("requested", requested)
            .put("content_state", contentState)
            .put("candidate_count", ranked.size)
            .put("score", top.score)
            .put("second_score", secondScore)
            .put("margin", margin)
            .put("confidence", confidence)
            .put("match_source", top.matchSource)
            .put("match_kind", top.matchKind)
            .put("action_target", top.actionTarget)
            .put("candidate", top.toJson())
            .put("message", "Семантическая цель подтверждена доступным Accessibility evidence")
    }

    /**
     * Resolve one of several planner/user semantic alternatives against the SAME
     * factual snapshot. This centralizes cross-target ranking so callers do not
     * invent their own "first matching string wins" behavior.
     *
     * Important truth rule: two near-equal alternatives that resolve to different
     * physical node identities are ambiguous and therefore fail closed. Synonyms
     * that resolve to the same concrete node are collapsed, not treated as an
     * ambiguity.
     */
    fun resolveAny(
        screen: JSONObject,
        requestedTargets: List<String>,
        mode: Mode
    ): JSONObject {

        val cleaned =
            requestedTargets
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy(::normalize)

        if (cleaned.isEmpty()) {
            return failure(
                status = "invalid_target",
                requested = "",
                mode = mode,
                message = "Список семантических целей пуст"
            )
        }

        if (mode == Mode.INPUT && cleaned.size == 1) {
            return resolve(screen, cleaned.first(), mode)
        }

        data class ResolvedAlternative(
            val requested: String,
            val result: JSONObject,
            val score: Int,
            val identity: String
        )

        val resolved = mutableListOf<ResolvedAlternative>()
        val failures = mutableListOf<JSONObject>()

        for (target in cleaned) {
            val result = resolve(
                screen = screen,
                requestedTarget = target,
                mode = mode
            )

            if (result.optBoolean("resolved", false)) {
                resolved += ResolvedAlternative(
                    requested = target,
                    result = result,
                    score = result.optInt("score", 0),
                    identity = resultCandidateIdentity(result)
                )
            } else {
                failures += result
            }
        }

        if (resolved.isEmpty()) {
            val preferredFailure =
                failures.firstOrNull { it.optString("status") == "snapshot_unavailable" }
                    ?: failures.firstOrNull { it.optString("status") == "content_unavailable" }
                    ?: failures.firstOrNull { it.optString("status").startsWith("ambiguous") }
                    ?: failures.firstOrNull()

            val baseFailure =
                preferredFailure
                    ?: failure(
                        status = "target_not_found",
                        requested = cleaned.joinToString(" | "),
                        mode = mode,
                        message = "Ни одна семантическая цель не найдена"
                    )

            return baseFailure
                .put("requested_targets", JSONArray(cleaned))
                .put("alternative_count", cleaned.size)
        }

        val ranked =
            resolved.sortedWith(
                compareByDescending<ResolvedAlternative> { it.score }
                    .thenByDescending { it.result.optInt("confidence", 0) }
                    .thenBy { cleaned.indexOf(it.requested) }
            )

        val top = ranked.first()
        val competing =
            ranked
                .drop(1)
                .firstOrNull { other ->
                    top.identity.isBlank() ||
                        other.identity.isBlank() ||
                        top.identity != other.identity
                }

        val secondScore = competing?.score ?: -1
        val margin = if (secondScore >= 0) top.score - secondScore else top.score

        if (
            competing != null &&
            secondScore >= minimumScore(mode) &&
            margin < AMBIGUITY_MARGIN
        ) {
            return failure(
                status = "ambiguous_target",
                requested = cleaned.joinToString(" | "),
                mode = mode,
                message = "Несколько альтернатив указывают на разные почти равнозначные цели; действие остановлено"
            )
                .put("requested_targets", JSONArray(cleaned))
                .put("alternative_count", cleaned.size)
                .put("score", top.score)
                .put("second_score", secondScore)
                .put("margin", margin)
                .put("candidate", top.result.optJSONObject("candidate"))
                .put("competing_candidate", competing.result.optJSONObject("candidate"))
                .put("resolved_requested", top.requested)
                .put("competing_requested", competing.requested)
        }

        return top.result
            .put("reason", "semantic_target_resolved_from_alternatives")
            .put("requested_targets", JSONArray(cleaned))
            .put("alternative_count", cleaned.size)
            .put("resolved_requested", top.requested)
            .put("second_alternative_score", secondScore)
            .put("alternative_margin", margin)
    }

    private fun resultCandidateIdentity(
        result: JSONObject
    ): String {
        val candidate = result.optJSONObject("candidate") ?: return ""

        val text = normalize(candidate.optString("text"))
        val description = normalize(candidate.optString("description"))
        val viewId = normalize(candidate.optString("view_id"))
        val bounds = canonicalBoundsIdentity(candidate.opt("bounds"))
        val label = when {
            text.isNotBlank() -> text
            description.isNotBlank() -> description
            else -> viewId
        }

        return "$label|$viewId|$bounds"
    }

    private fun canonicalBoundsIdentity(value: Any?): String {
        if (value == null) return ""

        if (value is JSONObject) {
            val left = value.optInt("left", Int.MIN_VALUE)
            val top = value.optInt("top", Int.MIN_VALUE)
            val right = value.optInt("right", Int.MIN_VALUE)
            val bottom = value.optInt("bottom", Int.MIN_VALUE)

            if (
                left != Int.MIN_VALUE &&
                top != Int.MIN_VALUE &&
                right != Int.MIN_VALUE &&
                bottom != Int.MIN_VALUE
            ) {
                return "$left,$top,$right,$bottom"
            }
        }

        return value.toString().trim()
    }

    private fun resolveImplicitEditable(
        nodes: JSONArray?,
        contentState: String
    ): JSONObject {

        if (nodes == null) {
            return failure(
                status = "editable_target_not_found",
                requested = "",
                mode = Mode.INPUT,
                message = "Редактируемое поле не подтверждено"
            ).put("content_state", contentState)
        }

        val editable = mutableListOf<Candidate>()

        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue

            if (!node.optBoolean("visible", false) || !node.optBoolean("enabled", true)) {
                continue
            }

            if (!node.optBoolean("editable", false) || node.optBoolean("password", false)) {
                continue
            }

            val text = node.optString("text").trim()
            val description = node.optString("description").trim()
            val viewId = node.optString("view_id").trim()
            val actionTarget =
                when {
                    text.isNotBlank() -> text
                    description.isNotBlank() -> description
                    viewId.isNotBlank() -> viewId
                    else -> ""
                }

            val focused = node.optBoolean("focused", false)
            val score = if (focused) 180 else 110

            editable += Candidate(
                node = node,
                arrayIndex = index,
                score = score,
                exactness = if (focused) 3 else 1,
                actionableBonus = if (focused) 25 else 0,
                matchSource = if (focused) "focused_editable" else "editable",
                matchKind = if (focused) "focused" else "implicit",
                actionTarget = actionTarget
            )
        }

        val focused = editable.filter { it.node.optBoolean("focused", false) }
        val chosen =
            when {
                focused.size == 1 -> focused.first()
                focused.size > 1 -> null
                editable.size == 1 -> editable.first()
                else -> null
            }

        if (chosen == null) {
            return failure(
                status = if (editable.size > 1) "ambiguous_editable_target" else "editable_target_not_found",
                requested = "",
                mode = Mode.INPUT,
                message = if (editable.size > 1) {
                    "На экране несколько полей ввода и ни одно не подтверждено как единственная цель"
                } else {
                    "Редактируемое поле не подтверждено"
                }
            )
                .put("content_state", contentState)
                .put("candidate_count", editable.size)
        }

        return JSONObject()
            .put("resolved", true)
            .put("success", true)
            .put("status", "resolved")
            .put("reason", "semantic_target_resolved")
            .put("mode", Mode.INPUT.name.lowercase(Locale.ROOT))
            .put("requested", "")
            .put("content_state", contentState)
            .put("candidate_count", editable.size)
            .put("score", chosen.score)
            .put("second_score", -1)
            .put("margin", chosen.score)
            .put("confidence", if (chosen.node.optBoolean("focused", false)) 99 else 88)
            .put("match_source", chosen.matchSource)
            .put("match_kind", chosen.matchKind)
            .put("action_target", chosen.actionTarget)
            .put("candidate", chosen.toJson())
            .put("message", "Поле ввода подтверждено Accessibility evidence")
    }

    private fun candidateFor(
        node: JSONObject,
        arrayIndex: Int,
        requested: String,
        mode: Mode
    ): Candidate? {

        if (!node.optBoolean("visible", false) || !node.optBoolean("enabled", true)) {
            return null
        }

        if (mode == Mode.INPUT) {
            if (!node.optBoolean("editable", false) || node.optBoolean("password", false)) {
                return null
            }
        }

        val text = node.optString("text").trim()
        val description = node.optString("description").trim()
        val viewId = node.optString("view_id").trim()

        val textMatch = fieldScore(text, requested, TEXT_EXACT_SCORE)
        val descriptionMatch = fieldScore(description, requested, DESCRIPTION_EXACT_SCORE)

        // Android/Samsung commonly reuses android:id/title for many rows. A view id
        // is therefore a label only when no real user-visible label exists.
        val viewIdMatch =
            if (text.isBlank() && description.isBlank()) {
                fieldScore(viewIdLabel(viewId), requested, VIEW_ID_EXACT_SCORE)
            } else {
                MatchScore.NONE
            }

        val best =
            listOf(
                "text" to textMatch,
                "description" to descriptionMatch,
                "view_id" to viewIdMatch
            )
                .maxByOrNull { it.second.score }
                ?: return null

        if (best.second.score <= 0) {
            return null
        }

        var actionableBonus = 0

        if (mode == Mode.CLICK && node.optBoolean("clickable", false)) {
            actionableBonus += CLICKABLE_BONUS
        }

        if (mode == Mode.INPUT && node.optBoolean("focused", false)) {
            actionableBonus += FOCUSED_EDITABLE_BONUS
        }

        val score = best.second.score + actionableBonus

        val actionTarget =
            when {
                text.isNotBlank() -> text
                description.isNotBlank() -> description
                viewId.isNotBlank() -> viewId
                else -> requested
            }

        return Candidate(
            node = node,
            arrayIndex = arrayIndex,
            score = score,
            exactness = best.second.exactness,
            actionableBonus = actionableBonus,
            matchSource = best.first,
            matchKind = best.second.kind,
            actionTarget = actionTarget
        )
    }

    private fun exactVisibleFallback(
        screen: JSONObject,
        requested: String,
        mode: Mode,
        contentState: String
    ): JSONObject? {

        if (mode == Mode.INPUT) {
            return null
        }

        if (contentState !in setOf("readable", "partial")) {
            return null
        }

        val target = normalize(requested)
        if (target.isBlank()) return null

        val visible = mutableListOf<String>()
        appendStrings(screen.optJSONArray("visible_text"), visible)

        if (visible.isEmpty()) {
            appendStrings(screen.optJSONArray("all_visible_text"), visible)
        }

        val exact = visible.filter { normalize(it) == target }.distinct()
        if (exact.size != 1) {
            return null
        }

        return JSONObject()
            .put("resolved", true)
            .put("success", true)
            .put("status", "resolved")
            .put("reason", "exact_visible_text_fallback")
            .put("mode", mode.name.lowercase(Locale.ROOT))
            .put("requested", requested)
            .put("content_state", contentState)
            .put("candidate_count", 1)
            .put("score", EXACT_VISIBLE_FALLBACK_SCORE)
            .put("second_score", -1)
            .put("margin", EXACT_VISIBLE_FALLBACK_SCORE)
            .put("confidence", 76)
            .put("match_source", "visible_text")
            .put("match_kind", "exact")
            .put("action_target", exact.first())
            .put(
                "candidate",
                JSONObject()
                    .put("text", exact.first())
                    .put("description", "")
                    .put("view_id", "")
                    .put("class", "")
                    .put("package", screen.optString("package"))
                    .put("bounds", "")
                    .put("visible", true)
                    .put("enabled", true)
                    .put("evidence_source", "visible_text_fallback")
            )
            .put("message", "Цель подтверждена точным видимым текстом; fuzzy fallback запрещён")
    }

    private fun fieldScore(
        value: String,
        target: String,
        exactScore: Int
    ): MatchScore {

        val left = normalize(value)
        val right = normalize(target)

        if (left.isBlank() || right.isBlank()) {
            return MatchScore.NONE
        }

        if (left == right) {
            return MatchScore(exactScore, 3, "exact")
        }

        // Use bounded five-character semantic token keys, inherited from the
        // device-proven Task Engine resolver. This handles ordinary Russian/English
        // inflection ("мобильные данные" vs "мобильных данных") without
        // command-specific aliases. Generic UI words are discarded before scoring.
        val leftTokens = semanticTokenKeys(left)
        val rightTokens = semanticTokenKeys(right)

        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return MatchScore.NONE
        }

        val intersection = leftTokens.intersect(rightTokens).size
        if (intersection == 0) {
            return MatchScore.NONE
        }

        // Single-token semantic matching is allowed only when the normalized
        // bounded token key is identical. We never use raw substring matching,
        // so "mail" cannot resolve to "Email settings". Ambiguous equal-key
        // candidates are still rejected by the global score-margin gate.
        if (leftTokens.size == 1 && rightTokens.size == 1) {
            return MatchScore(
                (exactScore - 34).coerceAtLeast(66),
                2,
                "semantic_token_key"
            )
        }

        val smaller = minOf(leftTokens.size, rightTokens.size)
        val larger = maxOf(leftTokens.size, rightTokens.size)
        val shortCoverage = intersection.toDouble() / smaller.toDouble()
        val longCoverage = intersection.toDouble() / larger.toDouble()

        if (shortCoverage >= 1.0 && longCoverage >= 0.50) {
            val extra = (larger - smaller).coerceAtLeast(0)
            return MatchScore(
                (exactScore - 25 - extra * 2).coerceAtLeast(70),
                2,
                "semantic_tokens_contained"
            )
        }

        if (
            intersection >= 2 &&
            shortCoverage >= 0.75 &&
            longCoverage >= 0.50
        ) {
            val jaccard = intersection.toDouble() /
                leftTokens.union(rightTokens).size.coerceAtLeast(1).toDouble()

            return MatchScore(
                (exactScore - 44 + (jaccard * 10.0).roundToInt())
                    .coerceAtLeast(64),
                1,
                "semantic_token_similarity"
            )
        }

        return MatchScore.NONE
    }

    private fun viewIdLabel(viewId: String): String {
        if (viewId.isBlank()) return ""

        return viewId
            .substringAfterLast('/')
            .replace('_', ' ')
            .replace('-', ' ')
    }

    private fun contentState(screen: JSONObject): String =
        screen
            .optString(
                "primary_content_state",
                screen.optString("content_status", "unknown")
            )
            .trim()
            .lowercase(Locale.ROOT)
            .ifBlank { "unknown" }

    private fun minimumScore(mode: Mode): Int =
        when (mode) {
            Mode.CLICK -> MIN_CLICK_SCORE
            Mode.INPUT -> MIN_INPUT_SCORE
            Mode.VERIFY -> MIN_VERIFY_SCORE
        }

    private fun confidence(
        score: Int,
        margin: Int,
        exactness: Int
    ): Int {
        var value =
            when {
                score >= 145 -> 99
                score >= 130 -> 96
                score >= 110 -> 91
                score >= 95 -> 86
                score >= 80 -> 78
                else -> 70
            }

        if (exactness >= 3) value += 1
        if (margin in 0 until AMBIGUITY_STRONG_MARGIN) value -= 8
        if (margin >= 30) value += 1

        return value.coerceIn(0, 100)
    }

    private fun sameConcreteIdentity(
        first: Candidate,
        second: Candidate
    ): Boolean {
        val firstKey = first.identityKey()
        val secondKey = second.identityKey()
        return firstKey.isNotBlank() && firstKey == secondKey
    }

    private fun appendStrings(
        array: JSONArray?,
        target: MutableList<String>
    ) {
        if (array == null) return
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotBlank()) target += value
        }
    }

    private fun failure(
        status: String,
        requested: String,
        mode: Mode,
        message: String
    ): JSONObject =
        JSONObject()
            .put("resolved", false)
            .put("success", false)
            .put("status", status)
            .put("reason", status)
            .put("mode", mode.name.lowercase(Locale.ROOT))
            .put("requested", requested)
            .put("confidence", 0)
            .put("message", message)

    private fun normalize(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun semanticTokenKeys(value: String): Set<String> =
        normalize(value)
            .split(' ')
            .asSequence()
            .map(String::trim)
            .filter { it.length >= 3 }
            .map(::tokenKey)
            .filter { it.isNotBlank() && it !in GENERIC_UI_TOKEN_KEYS }
            .toCollection(linkedSetOf())

    private fun tokenKey(token: String): String {
        val normalized = normalize(token)
        return if (normalized.length >= 6) normalized.take(5) else normalized
    }

    private data class MatchScore(
        val score: Int,
        val exactness: Int,
        val kind: String
    ) {
        companion object {
            val NONE = MatchScore(0, 0, "none")
        }
    }

    private data class Candidate(
        val node: JSONObject,
        val arrayIndex: Int,
        val score: Int,
        val exactness: Int,
        val actionableBonus: Int,
        val matchSource: String,
        val matchKind: String,
        val actionTarget: String
    ) {
        fun identityKey(): String {
            val text = normalizedIdentity(node.optString("text"))
            val description = normalizedIdentity(node.optString("description"))
            val viewId = normalizedIdentity(node.optString("view_id"))
            val bounds = canonicalBoundsIdentity(node.opt("bounds"))
            val label = when {
                text.isNotBlank() -> text
                description.isNotBlank() -> description
                else -> viewId
            }
            return "$label|$viewId|$bounds"
        }

        fun toJson(): JSONObject =
            JSONObject()
                .put("index", node.optInt("index", arrayIndex))
                .put("array_index", arrayIndex)
                .put("depth", node.optInt("depth", -1))
                .put("text", node.optString("text"))
                .put("description", node.optString("description"))
                .put("view_id", node.optString("view_id"))
                .put("class", node.optString("class"))
                .put("package", node.optString("package"))
                .put("bounds", node.opt("bounds") ?: "")
                .put("clickable", node.optBoolean("clickable", false))
                .put("editable", node.optBoolean("editable", false))
                .put("focused", node.optBoolean("focused", false))
                .put("enabled", node.optBoolean("enabled", true))
                .put("visible", node.optBoolean("visible", false))
                .put("password", node.optBoolean("password", false))
                .put("evidence_source", node.optString("evidence_source", "accessibility_snapshot"))
                .put("score", score)
                .put("match_source", matchSource)
                .put("match_kind", matchKind)
                .put("action_target", actionTarget)

        companion object {
            private fun canonicalBoundsIdentity(value: Any?): String {
                if (value == null) return ""

                if (value is JSONObject) {
                    val left = value.optInt("left", Int.MIN_VALUE)
                    val top = value.optInt("top", Int.MIN_VALUE)
                    val right = value.optInt("right", Int.MIN_VALUE)
                    val bottom = value.optInt("bottom", Int.MIN_VALUE)

                    if (
                        left != Int.MIN_VALUE &&
                        top != Int.MIN_VALUE &&
                        right != Int.MIN_VALUE &&
                        bottom != Int.MIN_VALUE
                    ) {
                        return "$left,$top,$right,$bottom"
                    }
                }

                return value.toString().trim()
            }

            private fun normalizedIdentity(value: String): String =
                value
                    .lowercase(Locale.ROOT)
                    .replace('ё', 'е')
                    .replace(Regex("\\s+"), " ")
                    .trim()
        }
    }

    companion object {
        private const val TEXT_EXACT_SCORE = 140
        private const val DESCRIPTION_EXACT_SCORE = 122
        private const val VIEW_ID_EXACT_SCORE = 90
        private const val EXACT_VISIBLE_FALLBACK_SCORE = 78

        private const val CLICKABLE_BONUS = 12
        private const val FOCUSED_EDITABLE_BONUS = 18

        private const val MIN_CLICK_SCORE = 72
        private const val MIN_INPUT_SCORE = 74
        private const val MIN_VERIFY_SCORE = 68

        private const val AMBIGUITY_MARGIN = 9
        private const val AMBIGUITY_STRONG_MARGIN = 16

        // Kept aligned with the generic semantic vocabulary already proven in
        // AyanaAndroidTaskEngine v4.x. These are structural UI words, not app
        // aliases; removing them makes the resolver focus on target identity.
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
