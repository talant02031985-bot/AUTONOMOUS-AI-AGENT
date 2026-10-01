package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * AYANA Screen Understanding Engine v2.0 — R10.17 SCREEN INTELLIGENCE 2.0.
 *
 * Truth model:
 * - Accessibility coverage and semantic understanding are different facts.
 * - A raw Accessibility snapshot may honestly remain `partial` while still containing
 *   enough verified evidence for read-only understanding.
 * - Verified visual/structured evidence may improve READ-ONLY understanding only.
 * - Visual evidence never grants click/input/scroll authority and never repairs a
 *   foreground ownership conflict.
 * - Interaction usability requires live Accessibility structure from the same verified
 *   foreground package.
 */
class AyanaScreenUnderstandingEngine {

    fun evaluate(
        snapshot: JSONObject,
        visualObservation: JSONObject?,
        effectiveForegroundPackage: String,
        foregroundTruthVerified: Boolean,
        foregroundConflict: Boolean,
        visualObservationUsed: Boolean
    ): JSONObject {
        val coverage =
            snapshot
                .optString(
                    "primary_content_state",
                    snapshot.optString("content_status", "unknown")
                )
                .trim()
                .ifBlank { "unknown" }

        val nodes = snapshot.optJSONArray("nodes")
        val visibleText = snapshot.optJSONArray("visible_text")
        val windowCount =
            snapshot.optInt(
                "window_count",
                snapshot.optInt("raw_window_count", -1)
            )
        val nodeCount =
            snapshot.optInt(
                "node_count",
                nodes?.length() ?: -1
            )

        val accessibilityTextCount =
            maxOf(
                snapshot.optInt("primary_readable_text_count", -1),
                snapshot.optInt("readable_text_count", -1),
                visibleText?.length() ?: -1,
                countMeaningfulNodeText(nodes)
            )
                .coerceAtLeast(0)

        val accessibilityActionableCount =
            countActionableNodes(nodes)

        val title =
            firstNonBlank(
                snapshot.optString("primary_window_title"),
                snapshot.optString("window_title"),
                snapshot.optString("title")
            )

        val visual = visualObservation ?: JSONObject()
        val visualVerified =
            visualObservationUsed &&
                visual.optBoolean("verified", false) &&
                !foregroundConflict

        val visualTextCount =
            if (visualVerified) {
                visual.optJSONArray("semantic_primary_text")?.length() ?: 0
            } else {
                0
            }

        val visualControlCount =
            if (visualVerified) {
                visual.optJSONArray("semantic_controls")?.length() ?: 0
            } else {
                0
            }

        val visualValueCount =
            if (visualVerified) {
                visual.optJSONArray("semantic_values")?.length() ?: 0
            } else {
                0
            }

        val visualTitle =
            if (visualVerified) {
                visual.optString("semantic_title").trim()
            } else {
                ""
            }

        val effectiveTextCount =
            maxOf(
                accessibilityTextCount,
                visualTextCount
            )

        val effectiveControlCount =
            maxOf(
                accessibilityActionableCount,
                visualControlCount
            )

        val hasWindowStructure =
            snapshot.optBoolean("success", false) &&
                (
                    windowCount > 0 ||
                        nodeCount > 0 ||
                        snapshot.optString("root_class").isNotBlank()
                    )

        var confidence = 0

        if (foregroundTruthVerified && effectiveForegroundPackage.isNotBlank()) {
            confidence += 30
        }

        confidence +=
            when (coverage) {
                "readable" -> 25
                "partial" -> 15
                "structure_only" -> 7
                else -> 0
            }

        confidence += minOf(accessibilityTextCount * 3, 24)
        confidence += minOf(accessibilityActionableCount * 2, 10)

        if (title.isNotBlank()) confidence += 5

        if (visualVerified) {
            confidence += 8
            confidence += minOf(visualTextCount * 2, 10)
            confidence += minOf(visualControlCount, 4)
            if (visualTitle.isNotBlank()) confidence += 4
        }

        if (foregroundConflict) confidence = 0
        confidence = confidence.coerceIn(0, 100)

        val accessibilitySemanticallySufficient =
            foregroundTruthVerified &&
                !foregroundConflict &&
                (
                    accessibilityTextCount >= MIN_SUFFICIENT_TEXT_ITEMS ||
                        accessibilityActionableCount >= MIN_SUFFICIENT_ACTIONABLE_ITEMS ||
                        (
                            coverage == "readable" &&
                                (accessibilityTextCount > 0 || title.isNotBlank())
                            )
                    )

        val visualSemanticallySufficient =
            foregroundTruthVerified &&
                !foregroundConflict &&
                visualVerified &&
                (
                    visualTextCount > 0 ||
                        visualControlCount > 0 ||
                        visualValueCount > 0 ||
                        visualTitle.isNotBlank()
                    )

        val status =
            when {
                foregroundConflict ->
                    STATUS_CONFLICT

                !foregroundTruthVerified ->
                    STATUS_UNVERIFIED

                coverage == "readable" && accessibilitySemanticallySufficient ->
                    STATUS_VERIFIED_FULL

                accessibilitySemanticallySufficient || visualSemanticallySufficient ->
                    STATUS_VERIFIED_SUFFICIENT

                hasWindowStructure ->
                    STATUS_STRUCTURE_ONLY

                else ->
                    STATUS_INSUFFICIENT
            }

        val readOnlyUsable =
            status == STATUS_VERIFIED_FULL ||
                status == STATUS_VERIFIED_SUFFICIENT

        // Interaction authority is deliberately Accessibility-only. A screenshot or
        // visual semantic observation can never make this true by itself.
        val interactionUsable =
            foregroundTruthVerified &&
                !foregroundConflict &&
                hasWindowStructure &&
                nodes != null &&
                nodes.length() > 0 &&
                accessibilityActionableCount > 0 &&
                coverage in setOf("readable", "partial", "structure_only")

        val source =
            when {
                accessibilitySemanticallySufficient && visualSemanticallySufficient ->
                    "accessibility_plus_verified_visual"

                accessibilitySemanticallySufficient ->
                    "accessibility"

                visualSemanticallySufficient ->
                    "verified_visual_read_only"

                hasWindowStructure ->
                    "accessibility_structure"

                else ->
                    "insufficient"
            }

        val reason =
            when (status) {
                STATUS_VERIFIED_FULL ->
                    "complete_accessibility_coverage_with_verified_semantic_evidence"

                STATUS_VERIFIED_SUFFICIENT ->
                    if (coverage == "partial") {
                        "partial_accessibility_coverage_but_semantic_evidence_is_sufficient"
                    } else if (visualSemanticallySufficient && !accessibilitySemanticallySufficient) {
                        "verified_visual_evidence_is_sufficient_for_read_only_understanding"
                    } else {
                        "verified_semantic_evidence_is_sufficient"
                    }

                STATUS_STRUCTURE_ONLY ->
                    "window_structure_verified_but_semantic_content_is_insufficient"

                STATUS_CONFLICT ->
                    "foreground_truth_conflict"

                STATUS_UNVERIFIED ->
                    "foreground_truth_not_verified"

                else ->
                    "semantic_evidence_insufficient"
            }

        return JSONObject()
            .put("screen_understanding_version", VERSION)
            .put("understanding_contract_version", CONTRACT_VERSION)
            .put("accessibility_coverage_status", coverage)
            .put("screen_understanding_status", status)
            .put("screen_understanding_confidence", confidence)
            .put("screen_understanding_source", source)
            .put("screen_understanding_reason", reason)
            .put("read_only_understanding_usable", readOnlyUsable)
            .put("interaction_understanding_usable", interactionUsable)
            .put("visual_read_only_corroboration_used", visualSemanticallySufficient)
            .put("visual_grants_action_authority", false)
            .put("screen_text_instruction_authority", false)
            .put("understanding_text_count", effectiveTextCount)
            .put("understanding_control_count", effectiveControlCount)
            .put("accessibility_text_count", accessibilityTextCount)
            .put("accessibility_actionable_count", accessibilityActionableCount)
            .put("visual_semantic_text_count", visualTextCount)
            .put("visual_semantic_control_count", visualControlCount)
            .put("visual_semantic_value_count", visualValueCount)
            .put("window_count_observed", windowCount)
            .put("node_count_observed", nodeCount)
            .put("coverage_complete", coverage == "readable")
            .put("coverage_partial_but_understanding_sufficient",
                coverage == "partial" && readOnlyUsable)
    }

    fun selfTest(): Boolean {
        val partialRich =
            JSONObject()
                .put("success", true)
                .put("primary_content_state", "partial")
                .put("window_count", 1)
                .put("primary_readable_text_count", 6)
                .put(
                    "nodes",
                    JSONArray()
                        .put(JSONObject().put("visible", true).put("enabled", true).put("clickable", true).put("text", "Apps"))
                        .put(JSONObject().put("visible", true).put("enabled", true).put("text", "Settings"))
                )

        val partialResult =
            evaluate(
                snapshot = partialRich,
                visualObservation = null,
                effectiveForegroundPackage = "com.android.settings",
                foregroundTruthVerified = true,
                foregroundConflict = false,
                visualObservationUsed = false
            )

        val visual =
            JSONObject()
                .put("verified", true)
                .put("semantic_title", "Example Domain")
                .put("semantic_primary_text", JSONArray().put("Example Domain"))
                .put("semantic_controls", JSONArray().put(JSONObject().put("role", "link").put("text", "More information")))
                .put("semantic_values", JSONArray())

        val structureOnly =
            JSONObject()
                .put("success", true)
                .put("primary_content_state", "structure_only")
                .put("window_count", 1)
                .put("nodes", JSONArray().put(JSONObject().put("visible", true)))

        val visualResult =
            evaluate(
                snapshot = structureOnly,
                visualObservation = visual,
                effectiveForegroundPackage = "com.sec.android.app.sbrowser",
                foregroundTruthVerified = true,
                foregroundConflict = false,
                visualObservationUsed = true
            )

        return partialResult.optString("screen_understanding_status") == STATUS_VERIFIED_SUFFICIENT &&
            partialResult.optBoolean("read_only_understanding_usable", false) &&
            partialResult.optBoolean("coverage_partial_but_understanding_sufficient", false) &&
            visualResult.optString("screen_understanding_status") == STATUS_VERIFIED_SUFFICIENT &&
            visualResult.optBoolean("read_only_understanding_usable", false) &&
            !visualResult.optBoolean("interaction_understanding_usable", true) &&
            !visualResult.optBoolean("visual_grants_action_authority", true)
    }

    private fun countMeaningfulNodeText(nodes: JSONArray?): Int {
        if (nodes == null) return 0
        var count = 0
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            if (!node.optBoolean("visible", true)) continue
            val value =
                firstNonBlank(
                    node.optString("value_text"),
                    node.optString("visual_text"),
                    node.optString("text"),
                    node.optString("description")
                )
            if (value.isNotBlank()) count++
        }
        return count
    }

    private fun countActionableNodes(nodes: JSONArray?): Int {
        if (nodes == null) return 0
        var count = 0
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            if (!node.optBoolean("visible", true) || !node.optBoolean("enabled", true)) continue
            if (
                node.optBoolean("clickable", false) ||
                node.optBoolean("editable", false) ||
                node.optBoolean("checkable", false) ||
                node.optBoolean("scrollable", false) ||
                node.optBoolean("long_clickable", false)
            ) {
                count++
            }
        }
        return count
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.trim().isNotBlank() }
            ?.trim()
            .orEmpty()

    companion object {
        const val VERSION = "2.0"
        const val CONTRACT_VERSION = 1

        const val STATUS_VERIFIED_FULL = "verified_full"
        const val STATUS_VERIFIED_SUFFICIENT = "verified_sufficient"
        const val STATUS_STRUCTURE_ONLY = "structure_only"
        const val STATUS_INSUFFICIENT = "insufficient"
        const val STATUS_UNVERIFIED = "unverified"
        const val STATUS_CONFLICT = "conflict"

        private const val MIN_SUFFICIENT_TEXT_ITEMS = 3
        private const val MIN_SUFFICIENT_ACTIONABLE_ITEMS = 2
    }
}
