package kg.autonomous.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

class AgentAccessibilityService :
    AccessibilityService() {

    data class NodeMatch(
        val node: AccessibilityNodeInfo,
        val score: Int
    )

    override fun onServiceConnected() {
        super.onServiceConnected()

        // Samsung One UI may temporarily return rootInActiveWindow == null
        // during Settings transitions / multi-window. Ask Android for the full
        // interactive-window list so we can safely fall back to the real app.
        try {

            val info =
                serviceInfo

            info.flags =
                info.flags or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS

            serviceInfo =
                info

        } catch (_: Exception) {
        }

        instance =
            this
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {

        if (event == null) {
            return
        }

        lastEventPackage =
            event.packageName
                ?.toString()
                .orEmpty()

        lastEventClass =
            event.className
                ?.toString()
                .orEmpty()

        lastEventTime =
            System.currentTimeMillis()
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {

        if (
            instance === this
        ) {
            instance =
                null
        }

        super.onDestroy()
    }

    fun pressBack():
        Boolean {

        return performGlobalAction(
            GLOBAL_ACTION_BACK
        )
    }

    fun pressHome():
        Boolean {

        return performGlobalAction(
            GLOBAL_ACTION_HOME
        )
    }

    fun pressRecents():
        Boolean {

        return performGlobalAction(
            GLOBAL_ACTION_RECENTS
        )
    }

    fun clickByText(
        target: String
    ): Boolean {

        return clickElement(
            target
        )
    }

    fun clickElement(
        target: String
    ): Boolean {

        val root =
            resolveRoot()
                ?: return false

        val match =
            findBestNode(
                root =
                    root,
                target =
                    target,
                requireEditable =
                    false,
                requireClickable =
                    false
            )
                ?: return false

        // One UI can return ACTION_CLICK=true while the UI does not actually
        // navigate. A boolean acknowledgement is not enough. Verify a real
        // screen-signature change before declaring success; if it does not
        // change, fall back to a semantic tap on the already-resolved node.
        val before =
            screenSignature()

        val actionable =
            findActionableParent(
                match.node,
                AccessibilityNodeInfo
                    .ACTION_CLICK
            )

        if (
            actionable != null
        ) {

            val accepted =
                try {
                    actionable.performAction(
                        AccessibilityNodeInfo
                            .ACTION_CLICK
                    )
                } catch (_: Exception) {
                    false
                }

            if (
                accepted &&
                waitForScreenChange(
                    before
                )
            ) {
                return true
            }
        }

        val semanticTapAccepted =
            tapNodeCenter(
                match.node
            )

        if (
            semanticTapAccepted &&
            waitForScreenChange(
                before
            )
        ) {
            return true
        }

        if (
            actionable != null &&
            actionable !== match.node
        ) {

            val rowTapAccepted =
                tapNodeCenter(
                    actionable
                )

            if (
                rowTapAccepted &&
                waitForScreenChange(
                    before
                )
            ) {
                return true
            }
        }

        return false
    }

    private fun waitForScreenChange(
        before: String
    ): Boolean {

        val deadline =
            System.currentTimeMillis() +
                CLICK_VERIFY_TIMEOUT_MS

        while (
            System.currentTimeMillis() <
            deadline
        ) {

            try {

                Thread.sleep(
                    CLICK_VERIFY_POLL_MS
                )

            } catch (_: InterruptedException) {

                Thread.currentThread()
                    .interrupt()

                return false
            }

            if (
                screenSignature() !=
                before
            ) {
                return true
            }
        }

        return false
    }

    fun setText(
        target: String?,
        text: String
    ): Boolean {

        val root =
            resolveRoot()
                ?: return false

        val node =
            if (
                target.isNullOrBlank()
            ) {

                findFocusedEditable(
                    root
                )
                    ?: findFirstEditable(
                        root
                    )

            } else {

                findBestNode(
                    root =
                        root,
                    target =
                        target,
                    requireEditable =
                        true,
                    requireClickable =
                        false
                )
                    ?.node
            }
                ?: return false

        if (
            node.isPassword
        ) {
            return false
        }

        node.performAction(
            AccessibilityNodeInfo
                .ACTION_FOCUS
        )

        val arguments =
            Bundle().apply {

                putCharSequence(
                    AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }

        return node
            .performAction(
                AccessibilityNodeInfo
                    .ACTION_SET_TEXT,
                arguments
            )
    }

    fun scroll(
        direction: String
    ): Boolean {

        val root =
            resolveRoot()
                ?: return false

        val scrollable =
            findBestScrollable(
                root
            )
                ?: return false

        val normalized =
            normalize(
                direction
            )

        val preferredAction =
            when {

                normalized.contains(
                    "вверх"
                ) ||
                    normalized.contains(
                        "up"
                    ) ->
                    AccessibilityNodeInfo
                        .ACTION_SCROLL_BACKWARD

                normalized.contains(
                    "назад"
                ) ||
                    normalized.contains(
                        "back"
                    ) ->
                    AccessibilityNodeInfo
                        .ACTION_SCROLL_BACKWARD

                else ->
                    AccessibilityNodeInfo
                        .ACTION_SCROLL_FORWARD
            }

        return scrollable
            .performAction(
                preferredAction
            )
    }

    private fun tapNodeCenter(
        node: AccessibilityNodeInfo
    ): Boolean {

        if (
            !node.isVisibleToUser ||
            !node.isEnabled
        ) {
            return false
        }

        val bounds =
            Rect()

        node.getBoundsInScreen(
            bounds
        )

        if (
            bounds.width() <= 1 ||
            bounds.height() <= 1
        ) {
            return false
        }

        return tapCoordinates(
            bounds.centerX(),
            bounds.centerY()
        )
    }

    fun tapCoordinates(
        x: Int,
        y: Int
    ): Boolean {

        if (
            x < 0 ||
            y < 0
        ) {
            return false
        }

        val path =
            Path().apply {

                moveTo(
                    x.toFloat(),
                    y.toFloat()
                )
            }

        val gesture =
            GestureDescription
                .Builder()
                .addStroke(
                    GestureDescription
                        .StrokeDescription(
                            path,
                            0,
                            80
                        )
                )
                .build()

        return dispatchGesture(
            gesture,
            null,
            null
        )
    }

    fun buildScreenSnapshot(
        maxNodes: Int = 140,
        maxChars: Int = 14000
    ): JSONObject {

        val root =
            resolveRoot()

        if (
            root == null
        ) {

            return JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "message",
                    "Активное окно недоступно"
                )
                .put(
                    "window_count",
                    safeWindowCount()
                )
        }

        val result =
            JSONObject()

        val nodes =
            JSONArray()

        val packageName =
            root.packageName
                ?.toString()
                .orEmpty()

        val rootClass =
            root.className
                ?.toString()
                .orEmpty()

        var charCount =
            0

        var visited =
            0

        val queue =
            ArrayDeque<
                Pair<
                    AccessibilityNodeInfo,
                    Int
                >
            >()

        queue.add(
            root to 0
        )

        while (
            queue.isNotEmpty() &&
            visited < maxNodes &&
            charCount < maxChars
        ) {

            val (
                node,
                depth
            ) =
                queue.removeFirst()

            visited++

            val item =
                nodeToJson(
                    node =
                        node,
                    depth =
                        depth,
                    index =
                        visited
                )

            val serialized =
                item.toString()

            if (
                charCount +
                    serialized.length >
                maxChars
            ) {
                break
            }

            nodes.put(
                item
            )

            charCount +=
                serialized.length

            val childCount =
                node.childCount

            for (
                index in
                0 until childCount
            ) {

                val child =
                    node.getChild(
                        index
                    )
                        ?: continue

                queue.add(
                    child to
                        (
                            depth +
                                1
                            )
                )
            }
        }

        val visibleTexts =
            collectVisibleTexts(
                nodes
            )

        return result
            .put(
                "success",
                true
            )
            .put(
                "package",
                packageName
            )
            .put(
                "root_class",
                rootClass
            )
            .put(
                "event_package",
                lastEventPackage
            )
            .put(
                "event_class",
                lastEventClass
            )
            .put(
                "event_age_ms",
                (
                    System.currentTimeMillis() -
                        lastEventTime
                    )
                    .coerceAtLeast(
                        0L
                    )
            )
            .put(
                "root_source",
                lastRootSource
            )
            .put(
                "window_count",
                safeWindowCount()
            )
            .put(
                "node_count",
                nodes.length()
            )
            .put(
                "visible_text",
                visibleTexts
            )
            .put(
                "nodes",
                nodes
            )
    }

    fun screenSignature():
        String {

        val snapshot =
            buildScreenSnapshot(
                maxNodes =
                    80,
                maxChars =
                    6000
            )

        if (
            !snapshot.optBoolean(
                "success",
                false
            )
        ) {
            return "unavailable"
        }

        val packageName =
            snapshot.optString(
                "package"
            )

        val visibleText =
            snapshot
                .optJSONArray(
                    "visible_text"
                )
                ?.toString()
                .orEmpty()

        return (
            packageName +
                "|" +
                visibleText
            )
            .take(
                7000
            )
            .hashCode()
            .toString()
    }

    /**
     * Prefer rootInActiveWindow. If One UI temporarily returns null, choose the
     * best root from AccessibilityService.windows. The last AccessibilityEvent
     * package gets the strongest preference, then active/focused application
     * windows. This avoids selecting AYANA's overlay instead of Settings.
     */
    private fun resolveRoot():
        AccessibilityNodeInfo? {

        try {

            val active =
                rootInActiveWindow

            if (
                active != null
            ) {

                lastRootSource =
                    "active"

                return active
            }

        } catch (_: Exception) {
        }

        val snapshotWindows =
            try {
                windows
            } catch (_: Exception) {
                emptyList()
            }

        var bestRoot:
            AccessibilityNodeInfo? = null

        var bestScore =
            Int.MIN_VALUE

        val expectedPackage =
            lastEventPackage
                .trim()

        for (
            window in
            snapshotWindows
        ) {

            val root =
                try {
                    window.root
                } catch (_: Exception) {
                    null
                }
                    ?: continue

            val rootPackage =
                root.packageName
                    ?.toString()
                    .orEmpty()

            var score =
                0

            if (
                window.isActive
            ) {
                score +=
                    140
            }

            if (
                window.isFocused
            ) {
                score +=
                    120
            }

            if (
                window.type ==
                android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
            ) {
                score +=
                    70
            }

            if (
                expectedPackage.isNotBlank() &&
                rootPackage ==
                expectedPackage
            ) {
                score +=
                    180
            }

            if (
                rootPackage.isNotBlank() &&
                rootPackage !=
                packageName
            ) {
                score +=
                    25
            }

            score +=
                minOf(
                    root.childCount,
                    20
                ) *
                    3

            if (
                score >
                bestScore
            ) {

                bestScore =
                    score

                bestRoot =
                    root
            }
        }

        if (
            bestRoot != null
        ) {
            lastRootSource =
                "windows_fallback"
        } else {
            lastRootSource =
                "unavailable"
        }

        return bestRoot
    }

    private fun safeWindowCount():
        Int {

        return try {
            windows.size
        } catch (_: Exception) {
            0
        }
    }

    private fun nodeToJson(
        node: AccessibilityNodeInfo,
        depth: Int,
        index: Int
    ): JSONObject {

        val bounds =
            Rect()

        node.getBoundsInScreen(
            bounds
        )

        val password =
            node.isPassword

        val text =
            if (
                password
            ) {
                "[PASSWORD_HIDDEN]"
            } else {
                node.text
                    ?.toString()
                    .orEmpty()
            }

        val description =
            if (
                password
            ) {
                "[PASSWORD_HIDDEN]"
            } else {
                node.contentDescription
                    ?.toString()
                    .orEmpty()
            }

        return JSONObject()
            .put(
                "index",
                index
            )
            .put(
                "depth",
                depth
            )
            .put(
                "text",
                safeText(
                    text
                )
            )
            .put(
                "description",
                safeText(
                    description
                )
            )
            .put(
                "view_id",
                safeText(
                    node.viewIdResourceName
                        .orEmpty()
                )
            )
            .put(
                "class",
                safeText(
                    node.className
                        ?.toString()
                        .orEmpty()
                )
            )
            .put(
                "package",
                safeText(
                    node.packageName
                        ?.toString()
                        .orEmpty()
                )
            )
            .put(
                "clickable",
                node.isClickable
            )
            .put(
                "editable",
                node.isEditable
            )
            .put(
                "scrollable",
                node.isScrollable
            )
            .put(
                "focusable",
                node.isFocusable
            )
            .put(
                "focused",
                node.isFocused
            )
            .put(
                "enabled",
                node.isEnabled
            )
            .put(
                "visible",
                node.isVisibleToUser
            )
            .put(
                "password",
                password
            )
            .put(
                "bounds",
                JSONObject()
                    .put(
                        "left",
                        bounds.left
                    )
                    .put(
                        "top",
                        bounds.top
                    )
                    .put(
                        "right",
                        bounds.right
                    )
                    .put(
                        "bottom",
                        bounds.bottom
                    )
            )
    }

    private fun collectVisibleTexts(
        nodes: JSONArray
    ): JSONArray {

        val result =
            JSONArray()

        val seen =
            linkedSetOf<String>()

        for (
            index in
            0 until nodes.length()
        ) {

            val item =
                nodes
                    .optJSONObject(
                        index
                    )
                    ?: continue

            val text =
                item
                    .optString(
                        "text"
                    )
                    .trim()

            val description =
                item
                    .optString(
                        "description"
                    )
                    .trim()

            listOf(
                text,
                description
            )
                .filter {
                    it.isNotBlank() &&
                        it !=
                        "[PASSWORD_HIDDEN]"
                }
                .forEach {
                    value ->

                    val clipped =
                        safeText(
                            value
                        )

                    if (
                        clipped.isNotBlank() &&
                        seen.add(
                            clipped
                        )
                    ) {

                        result.put(
                            clipped
                        )
                    }
                }
        }

        return result
    }

    private fun findBestNode(
        root: AccessibilityNodeInfo,
        target: String,
        requireEditable: Boolean,
        requireClickable: Boolean
    ): NodeMatch? {

        val normalizedTarget =
            normalize(
                target
            )

        if (
            normalizedTarget.isBlank()
        ) {
            return null
        }

        var best:
            NodeMatch? = null

        val queue =
            ArrayDeque<
                AccessibilityNodeInfo
            >()

        queue.add(
            root
        )

        var visited =
            0

        while (
            queue.isNotEmpty() &&
            visited < 500
        ) {

            val node =
                queue.removeFirst()

            visited++

            if (
                node.isVisibleToUser &&
                node.isEnabled &&
                (
                    !requireEditable ||
                        node.isEditable
                    ) &&
                (
                    !requireClickable ||
                        node.isClickable
                    )
            ) {

                val score =
                    scoreNode(
                        node,
                        normalizedTarget
                    )

                if (
                    score >
                    (
                        best?.score
                            ?: 0
                        )
                ) {

                    best =
                        NodeMatch(
                            node,
                            score
                        )
                }
            }

            for (
                index in
                0 until node.childCount
            ) {

                node.getChild(
                    index
                )
                    ?.let {
                        queue.add(
                            it
                        )
                    }
            }
        }

        return best
            ?.takeIf {
                it.score >=
                    MIN_MATCH_SCORE
            }
    }

    private fun scoreNode(
        node: AccessibilityNodeInfo,
        target: String
    ): Int {

        if (
            node.isPassword
        ) {
            return 0
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

        val viewId =
            normalize(
                node.viewIdResourceName
                    .orEmpty()
            )

        var score =
            0

        score =
            max(
                score,
                scoreValue(
                    text,
                    target,
                    100
                )
            )

        score =
            max(
                score,
                scoreValue(
                    description,
                    target,
                    95
                )
            )

        score =
            max(
                score,
                scoreValue(
                    viewId,
                    target,
                    80
                )
            )

        if (
            score > 0 &&
            node.isClickable
        ) {
            score +=
                8
        }

        if (
            score > 0 &&
            node.isEditable
        ) {
            score +=
                5
        }

        return score
    }

    private fun scoreValue(
        value: String,
        target: String,
        exactScore: Int
    ): Int {

        if (
            value.isBlank()
        ) {
            return 0
        }

        return when {

            value ==
                target ->
                exactScore

            value.contains(
                target
            ) ->
                exactScore -
                    15

            target.contains(
                value
            ) &&
                value.length >=
                3 ->
                exactScore -
                    25

            tokenOverlap(
                value,
                target
            ) >=
                0.7 ->
                exactScore -
                    35

            else ->
                0
        }
    }

    private fun tokenOverlap(
        left: String,
        right: String
    ): Double {

        val leftTokens =
            left
                .split(" ")
                .filter {
                    it.length >= 2
                }
                .toSet()

        val rightTokens =
            right
                .split(" ")
                .filter {
                    it.length >= 2
                }
                .toSet()

        if (
            leftTokens.isEmpty() ||
            rightTokens.isEmpty()
        ) {
            return 0.0
        }

        val common =
            leftTokens
                .intersect(
                    rightTokens
                )
                .size

        return common.toDouble() /
            max(
                leftTokens.size,
                rightTokens.size
            )
                .toDouble()
    }

    private fun findActionableParent(
        node: AccessibilityNodeInfo,
        action: Int
    ): AccessibilityNodeInfo? {

        var current:
            AccessibilityNodeInfo? =
            node

        var hops =
            0

        while (
            current != null &&
            hops < 7
        ) {

            if (
                current.actionList.any {
                    it.id == action
                }
            ) {
                return current
            }

            current =
                current.parent

            hops++
        }

        return null
    }

    private fun findFocusedEditable(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        val focused =
            root.findFocus(
                AccessibilityNodeInfo
                    .FOCUS_INPUT
            )

        if (
            focused != null &&
            focused.isEditable &&
            !focused.isPassword
        ) {
            return focused
        }

        return null
    }

    private fun findFirstEditable(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        val queue =
            ArrayDeque<
                AccessibilityNodeInfo
            >()

        queue.add(
            root
        )

        var visited =
            0

        while (
            queue.isNotEmpty() &&
            visited < 400
        ) {

            val node =
                queue.removeFirst()

            visited++

            if (
                node.isVisibleToUser &&
                node.isEnabled &&
                node.isEditable &&
                !node.isPassword
            ) {
                return node
            }

            for (
                index in
                0 until node.childCount
            ) {

                node.getChild(
                    index
                )
                    ?.let {
                        queue.add(
                            it
                        )
                    }
            }
        }

        return null
    }

    private fun findBestScrollable(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        val queue =
            ArrayDeque<
                AccessibilityNodeInfo
            >()

        queue.add(
            root
        )

        var best:
            AccessibilityNodeInfo? =
            null

        var bestArea =
            0L

        var visited =
            0

        while (
            queue.isNotEmpty() &&
            visited < 500
        ) {

            val node =
                queue.removeFirst()

            visited++

            if (
                node.isVisibleToUser &&
                node.isEnabled &&
                node.isScrollable
            ) {

                val bounds =
                    Rect()

                node.getBoundsInScreen(
                    bounds
                )

                val area =
                    bounds.width()
                        .toLong() *
                        bounds.height()
                            .toLong()

                if (
                    area >
                    bestArea
                ) {

                    best =
                        node

                    bestArea =
                        area
                }
            }

            for (
                index in
                0 until node.childCount
            ) {

                node.getChild(
                    index
                )
                    ?.let {
                        queue.add(
                            it
                        )
                    }
            }
        }

        return best
    }

    private fun normalize(
        value: String
    ): String {

        return value
            .lowercase(
                Locale.getDefault()
            )
            .replace(
                'ё',
                'е'
            )
            .replace(
                Regex(
                    "[^\\p{L}\\p{N}\\s_]"
                ),
                " "
            )
            .replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
            .trim()
    }

    private fun safeText(
        value: String
    ): String {

        return value
            .replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
            .trim()
            .take(
                280
            )
    }

    @Volatile
    private var lastRootSource:
        String =
        "unavailable"

    companion object {

        private const val CLICK_VERIFY_TIMEOUT_MS =
            650L

        private const val CLICK_VERIFY_POLL_MS =
            100L

        private const val MIN_MATCH_SCORE =
            55

        @Volatile
        var instance:
            AgentAccessibilityService? =
            null

        @Volatile
        var lastEventPackage:
            String = ""

        @Volatile
        var lastEventClass:
            String = ""

        @Volatile
        var lastEventTime:
            Long = 0L
    }
}
