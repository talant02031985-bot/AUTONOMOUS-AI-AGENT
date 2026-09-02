package kg.autonomous.agent

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale

/**
 * AYANA Settings Master Pane Navigator v1.0.
 *
 * Purpose:
 * Samsung/large-screen Settings uses a split layout. Generic scrolling in
 * AyanaScreenIntelligence intentionally favors the detail pane, which is correct
 * for App Info subpages but cannot reach off-viewport top-level categories in
 * the left master pane.
 *
 * This helper owns ONLY bounded semantic navigation inside the left Settings pane:
 * - no coordinates / gestures;
 * - exact normalized text or content-description match only;
 * - package must be com.android.settings;
 * - the clicked row must be geometrically inside the left master pane;
 * - only a left-pane Accessibility scroll container may be scrolled;
 * - bidirectional search is bounded and stops on repeated/no-progress viewports.
 *
 * Final section truth remains owned by AyanaSystemSettingsNavigator.
 */
class AyanaSettingsMasterPaneNavigator(
    private val shouldCancel: () -> Boolean = { false }
) {

    fun findAndClick(
        targets: List<String>,
        maxBackwardScrolls: Int = DEFAULT_BACKWARD_SCROLLS,
        maxForwardScrolls: Int = DEFAULT_FORWARD_SCROLLS
    ): JSONObject {
        val cleanTargets =
            targets
                .map(::normalize)
                .filter { it.isNotBlank() }
                .distinct()

        if (cleanTargets.isEmpty()) {
            return failure("empty_targets")
        }

        val service =
            AgentAccessibilityService.instance
                ?: return failure("accessibility_unavailable")
                    .put("terminal_status", "UNSUPPORTED")

        if (isCancelled()) {
            return cancelled()
        }

        val trace = JSONArray()

        // Always try the factual current viewport before moving it.
        clickVisibleTarget(service, cleanTargets)?.let { click ->
            return success(click, trace, "current_viewport")
        }

        // Samsung may retain the master-pane scroll position between Settings
        // launches. First walk toward the top boundary, then scan downward.
        val backLimit = maxBackwardScrolls.coerceIn(0, MAX_SCROLLS_HARD)
        var backwardMoves = 0
        var previousFingerprint = masterFingerprint(service)

        while (backwardMoves < backLimit && !isCancelled()) {
            val step = scrollMasterPane(service, forward = false)
            trace.put(step)

            if (!step.optBoolean("action_accepted", false)) {
                break
            }

            waitForViewportChange(
                service = service,
                beforeFingerprint = previousFingerprint
            )

            val currentFingerprint = masterFingerprint(service)
            val progressed =
                currentFingerprint.isNotBlank() &&
                    currentFingerprint != previousFingerprint

            if (!progressed) {
                break
            }

            backwardMoves++
            previousFingerprint = currentFingerprint

            clickVisibleTarget(service, cleanTargets)?.let { click ->
                return success(click, trace, "backward_scan")
                    .put("backward_moves", backwardMoves)
            }
        }

        if (isCancelled()) {
            return cancelled().put("trace", trace)
        }

        // Scan from the reached upper viewport toward the lower boundary.
        val forwardLimit = maxForwardScrolls.coerceIn(0, MAX_SCROLLS_HARD)
        var forwardMoves = 0
        previousFingerprint = masterFingerprint(service)
        val seenFingerprints = linkedSetOf<String>()
        if (previousFingerprint.isNotBlank()) {
            seenFingerprints.add(previousFingerprint)
        }

        clickVisibleTarget(service, cleanTargets)?.let { click ->
            return success(click, trace, "upper_viewport")
                .put("backward_moves", backwardMoves)
        }

        while (forwardMoves < forwardLimit && !isCancelled()) {
            val step = scrollMasterPane(service, forward = true)
            trace.put(step)

            if (!step.optBoolean("action_accepted", false)) {
                break
            }

            waitForViewportChange(
                service = service,
                beforeFingerprint = previousFingerprint
            )

            val currentFingerprint = masterFingerprint(service)
            val progressed =
                currentFingerprint.isNotBlank() &&
                    currentFingerprint != previousFingerprint

            if (!progressed) {
                break
            }

            if (!seenFingerprints.add(currentFingerprint)) {
                break
            }

            forwardMoves++
            previousFingerprint = currentFingerprint

            clickVisibleTarget(service, cleanTargets)?.let { click ->
                return success(click, trace, "forward_scan")
                    .put("backward_moves", backwardMoves)
                    .put("forward_moves", forwardMoves)
            }
        }

        return failure(
            if (isCancelled()) {
                "cancelled"
            } else {
                "master_target_not_found_within_bounds"
            }
        )
            .put("terminal_status", if (isCancelled()) "CANCELLED" else "ERROR")
            .put("backward_moves", backwardMoves)
            .put("forward_moves", forwardMoves)
            .put("trace", trace)
            .put("proof_level", "bounded_left_master_semantic_search")
    }

    private fun clickVisibleTarget(
        service: AgentAccessibilityService,
        normalizedTargets: List<String>
    ): JSONObject? {
        val context = settingsRoot(service) ?: return null
        val screenWidth = screenWidth(service)
        val nodes = collectNodes(context.root, NODE_LIMIT)

        val matches =
            nodes
                .mapNotNull { node ->
                    val bounds = Rect()
                    try {
                        node.getBoundsInScreen(bounds)
                    } catch (_: Exception) {
                        return@mapNotNull null
                    }

                    if (!isInsideMasterPane(bounds, screenWidth)) {
                        return@mapNotNull null
                    }

                    val text =
                        normalize(
                            node.text
                                ?.toString()
                                .orEmpty()
                        )

                    val description =
                        normalize(
                            node.contentDescription
                                ?.toString()
                                .orEmpty()
                        )

                    val index =
                        normalizedTargets.indexOfFirst { target ->
                            target == text || target == description
                        }

                    if (index < 0) {
                        null
                    } else {
                        Candidate(
                            node = node,
                            target = normalizedTargets[index],
                            bounds = bounds,
                            score =
                                1000 -
                                    index * 10 +
                                    if (text == normalizedTargets[index]) 5 else 0
                        )
                    }
                }
                .sortedByDescending { it.score }

        val best = matches.firstOrNull() ?: return null
        val sameScoreCount =
            matches.count {
                it.score == best.score &&
                    it.bounds != best.bounds
            }

        // Do not guess between duplicate equal-strength rows.
        if (sameScoreCount > 0) {
            return null
        }

        val clickable = clickableAncestorInMasterPane(
            start = best.node,
            screenWidth = screenWidth
        ) ?: return null

        val before = masterFingerprint(service)
        val accepted =
            try {
                clickable.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )
            } catch (_: Exception) {
                false
            }

        if (!accepted) {
            return null
        }

        return JSONObject()
            .put("success", true)
            .put("verified", false)
            .put("action_accepted", true)
            .put("terminal_status", "RUNNING")
            .put("reason", "exact_master_target_click_accepted")
            .put("clicked_target", best.target)
            .put("before_fingerprint", before)
            .put("window_id", context.windowId)
            .put(
                "bounds",
                "${best.bounds.left},${best.bounds.top},${best.bounds.right},${best.bounds.bottom}"
            )
            .put("proof_level", "exact_settings_master_semantic_target")
    }

    private fun scrollMasterPane(
        service: AgentAccessibilityService,
        forward: Boolean
    ): JSONObject {
        val context =
            settingsRoot(service)
                ?: return failure("settings_window_unavailable")

        val screenWidth = screenWidth(service)
        val nodes = collectNodes(context.root, NODE_LIMIT)

        val candidates =
            nodes
                .mapNotNull { node ->
                    val bounds = Rect()
                    try {
                        node.getBoundsInScreen(bounds)
                    } catch (_: Exception) {
                        return@mapNotNull null
                    }

                    if (!isMasterScrollContainer(node, bounds, screenWidth)) {
                        return@mapNotNull null
                    }

                    val area =
                        bounds.width()
                            .coerceAtLeast(0)
                            .toLong() *
                            bounds.height()
                                .coerceAtLeast(0)
                                .toLong()

                    Pair(node, area)
                }
                .sortedByDescending { it.second }

        val target =
            candidates.firstOrNull()?.first
                ?: return failure("left_master_scroll_container_not_found")

        val before = masterFingerprint(service)
        val action =
            if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }

        val accepted =
            try {
                target.performAction(action)
            } catch (_: Exception) {
                false
            }

        return JSONObject()
            .put("success", accepted)
            .put("verified", false)
            .put("action_accepted", accepted)
            .put("terminal_status", if (accepted) "RUNNING" else "ERROR")
            .put(
                "reason",
                if (accepted) {
                    if (forward) {
                        "left_master_scroll_forward_accepted"
                    } else {
                        "left_master_scroll_backward_accepted"
                    }
                } else {
                    "left_master_scroll_rejected"
                }
            )
            .put("direction", if (forward) "forward" else "backward")
            .put("before_fingerprint", before)
            .put("proof_level", "settings_left_master_scroll_container")
    }

    private fun waitForViewportChange(
        service: AgentAccessibilityService,
        beforeFingerprint: String
    ) {
        val deadline =
            SystemClock.uptimeMillis() +
                SCROLL_SETTLE_TIMEOUT_MS

        while (
            SystemClock.uptimeMillis() < deadline &&
            !isCancelled()
        ) {
            SystemClock.sleep(SCROLL_POLL_MS)

            val current =
                masterFingerprint(service)

            if (
                current.isNotBlank() &&
                current != beforeFingerprint
            ) {
                return
            }
        }
    }

    private fun masterFingerprint(
        service: AgentAccessibilityService
    ): String {
        val context = settingsRoot(service) ?: return ""
        val screenWidth = screenWidth(service)
        val values = linkedSetOf<String>()

        for (node in collectNodes(context.root, FINGERPRINT_NODE_LIMIT)) {
            val bounds = Rect()
            try {
                node.getBoundsInScreen(bounds)
            } catch (_: Exception) {
                continue
            }

            if (!isInsideMasterPane(bounds, screenWidth)) {
                continue
            }

            val text =
                normalize(
                    node.text
                        ?.toString()
                        .orEmpty()
                )

            val description =
                normalize(
                    node.contentDescription
                        ?.toString()
                        .orEmpty()
                )

            if (text.isNotBlank()) {
                values.add("$text@${bounds.top}:${bounds.bottom}")
            }

            if (
                description.isNotBlank() &&
                description != text
            ) {
                values.add("$description@${bounds.top}:${bounds.bottom}")
            }

            if (values.size >= FINGERPRINT_TEXT_LIMIT) {
                break
            }
        }

        return buildString {
            append(context.windowId)
            append('|')
            append(values.joinToString("||"))
        }
    }

    private fun settingsRoot(
        service: AgentAccessibilityService
    ): RootContext? {
        val windows =
            try {
                service.windows
            } catch (_: Exception) {
                emptyList()
            }

        val ranked =
            windows
                .mapNotNull { window ->
                    val root =
                        try {
                            window.root
                        } catch (_: Exception) {
                            null
                        }
                            ?: return@mapNotNull null

                    val packageName =
                        root.packageName
                            ?.toString()
                            .orEmpty()
                            .trim()

                    if (packageName != SETTINGS_PACKAGE) {
                        return@mapNotNull null
                    }

                    val score =
                        (if (window.isFocused) 10000 else 0) +
                            (if (window.isActive) 7000 else 0) +
                            (
                                if (
                                    window.type ==
                                    AccessibilityWindowInfo.TYPE_APPLICATION
                                ) {
                                    2500
                                } else {
                                    0
                                }
                            )

                    Triple(window, root, score)
                }
                .sortedByDescending { it.third }

        ranked.firstOrNull()?.let { item ->
            return RootContext(
                root = item.second,
                windowId =
                    try {
                        item.first.id
                    } catch (_: Exception) {
                        -1
                    }
            )
        }

        val activeRoot =
            try {
                service.rootInActiveWindow
            } catch (_: Exception) {
                null
            }
                ?: return null

        if (
            activeRoot.packageName
                ?.toString()
                .orEmpty()
                .trim() !=
            SETTINGS_PACKAGE
        ) {
            return null
        }

        return RootContext(
            root = activeRoot,
            windowId = -1
        )
    }

    private fun collectNodes(
        root: AccessibilityNodeInfo,
        limit: Int
    ): List<AccessibilityNodeInfo> {
        val result =
            ArrayList<AccessibilityNodeInfo>(
                minOf(limit, 128)
            )

        val queue =
            ArrayDeque<AccessibilityNodeInfo>()

        queue.add(root)

        while (
            queue.isNotEmpty() &&
            result.size < limit
        ) {
            val node = queue.removeFirst()
            result.add(node)

            val count =
                try {
                    node.childCount
                } catch (_: Exception) {
                    0
                }

            for (index in 0 until count) {
                val child =
                    try {
                        node.getChild(index)
                    } catch (_: Exception) {
                        null
                    }

                if (child != null) {
                    queue.addLast(child)
                }
            }
        }

        return result
    }

    private fun clickableAncestorInMasterPane(
        start: AccessibilityNodeInfo,
        screenWidth: Int
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = start
        var depth = 0

        while (
            current != null &&
            depth <= CLICK_ANCESTOR_LIMIT
        ) {
            val bounds = Rect()
            try {
                current.getBoundsInScreen(bounds)
            } catch (_: Exception) {
                return null
            }

            if (!isInsideMasterPane(bounds, screenWidth)) {
                return null
            }

            val clickable =
                try {
                    current.isClickable
                } catch (_: Exception) {
                    false
                }

            if (clickable) {
                return current
            }

            current =
                try {
                    current.parent
                } catch (_: Exception) {
                    null
                }

            depth++
        }

        return null
    }

    private fun isMasterScrollContainer(
        node: AccessibilityNodeInfo,
        bounds: Rect,
        screenWidth: Int
    ): Boolean {
        if (!isInsideMasterPane(bounds, screenWidth)) {
            return false
        }

        val minHeight =
            (screenHeightPx() * MIN_MASTER_HEIGHT_RATIO)
                .toInt()

        if (bounds.height() < minHeight) {
            return false
        }

        val scrollable =
            try {
                node.isScrollable
            } catch (_: Exception) {
                false
            }

        val actions =
            try {
                node.actionList
                    .map { it.id }
                    .toSet()
            } catch (_: Exception) {
                emptySet()
            }

        return scrollable ||
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions ||
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions
    }

    private fun isInsideMasterPane(
        bounds: Rect,
        screenWidth: Int
    ): Boolean {
        if (
            screenWidth <= 0 ||
            bounds.width() <= 0 ||
            bounds.height() <= 0
        ) {
            return false
        }

        val rightLimit =
            (screenWidth * MASTER_RIGHT_LIMIT_RATIO)
                .toInt()

        val centerX =
            bounds.left +
                bounds.width() / 2

        return bounds.left >= 0 &&
            bounds.right <= rightLimit &&
            centerX <
                (screenWidth * MASTER_CENTER_LIMIT_RATIO)
                    .toInt()
    }

    private fun screenWidth(
        service: AgentAccessibilityService
    ): Int =
        service.resources
            .displayMetrics
            .widthPixels
            .coerceAtLeast(1)

    private fun screenHeightPx(): Int =
        AgentAccessibilityService.instance
            ?.resources
            ?.displayMetrics
            ?.heightPixels
            ?.coerceAtLeast(1)
            ?: 1

    private fun normalize(
        value: String
    ): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(
                Regex("[^\\p{L}\\p{N}]+"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()

    private fun isCancelled(): Boolean =
        try {
            shouldCancel()
        } catch (_: Exception) {
            false
        }

    private fun success(
        click: JSONObject,
        trace: JSONArray,
        mode: String
    ): JSONObject =
        JSONObject(click.toString())
            .put("success", true)
            .put("action_accepted", true)
            .put("search_mode", mode)
            .put("trace", trace)

    private fun failure(
        reason: String
    ): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("action_accepted", false)
            .put("terminal_status", "ERROR")
            .put("reason", reason)
            .put("proof_level", "none")

    private fun cancelled(): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("action_accepted", false)
            .put("terminal_status", "CANCELLED")
            .put("reason", "cancelled")
            .put("proof_level", "none")

    private data class RootContext(
        val root: AccessibilityNodeInfo,
        val windowId: Int
    )

    private data class Candidate(
        val node: AccessibilityNodeInfo,
        val target: String,
        val bounds: Rect,
        val score: Int
    )

    companion object {
        private const val SETTINGS_PACKAGE =
            "com.android.settings"

        private const val DEFAULT_BACKWARD_SCROLLS =
            5

        private const val DEFAULT_FORWARD_SCROLLS =
            9

        private const val MAX_SCROLLS_HARD =
            12

        private const val NODE_LIMIT =
            260

        private const val FINGERPRINT_NODE_LIMIT =
            220

        private const val FINGERPRINT_TEXT_LIMIT =
            44

        private const val CLICK_ANCESTOR_LIMIT =
            7

        // Samsung Tab split-pane master column is substantially narrower than
        // half the display. 0.58 leaves room for OEM padding but excludes the
        // right detail pane and preserves App Info's existing scroll behavior.
        private const val MASTER_RIGHT_LIMIT_RATIO =
            0.58f

        private const val MASTER_CENTER_LIMIT_RATIO =
            0.46f

        private const val MIN_MASTER_HEIGHT_RATIO =
            0.34f

        private const val SCROLL_SETTLE_TIMEOUT_MS =
            780L

        private const val SCROLL_POLL_MS =
            90L
    }
}
