package kg.autonomous.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.max

class AgentAccessibilityService :
    AccessibilityService() {

    // AYANA Accessibility v4.0 — WINDOW CONTENT CORE.
    // Every visible Android window is an independent context. Android 13+ roots
    // are requested with descendant prefetch before falling back to legacy roots.
    // Fresh AccessibilityEvent evidence is merged only into the matching window.
    // Cross-window verification/clicking stays fail-closed in split-screen,
    // freeform, PiP, popup/dialog, Recents and overlay scenarios.

    data class NodeMatch(
        val node: AccessibilityNodeInfo,
        val score: Int
    )

    private data class WindowContext(
        val contextId: String,
        val windowId: Int,
        val packageName: String,
        val className: String,
        val type: Int,
        val layer: Int,
        val active: Boolean,
        val focused: Boolean,
        val pictureInPicture: Boolean,
        val bounds: Rect,
        val root: AccessibilityNodeInfo?,
        val source: String,
        val rank: Int,
        val title: String = ""
    )

    private data class ContextNodeMatch(
        val context: WindowContext,
        val match: NodeMatch,
        val totalScore: Int
    )

    private data class EventTapTarget(
        val bounds: Rect,
        val windowId: Int,
        val packageName: String
    )

    private data class EventEvidence(
        val at: Long,
        val windowId: Int,
        val packageName: String,
        val className: String,
        val eventType: Int,
        val bounds: Rect,
        val nodes: JSONArray,
        val visibleText: List<String>
    )

    private val eventEvidenceLock =
        Any()

    private val recentEventEvidence =
        ArrayDeque<EventEvidence>()

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
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS

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

        val eventPackage =
            event.packageName
                ?.toString()
                .orEmpty()

        // AYANA's own floating Orb is a TYPE_APPLICATION_OVERLAY surface. Its
        // visual/state updates must never steal foreground-window recency from
        // the app the user is actually controlling. MainActivity still remains
        // fully readable/actionable through the live focused window root.
        if (eventPackage == packageName) {
            pruneEventEvidence()
            return
        }

        lastEventPackage =
            eventPackage

        lastEventClass =
            event.className
                ?.toString()
                .orEmpty()

        lastEventTime =
            System.currentTimeMillis()

        lastEventWindowId =
            try {
                event.windowId
            } catch (_: Exception) {
                -1
            }

        captureEventEvidence(
            event
        )
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

        val allContexts =
            resolveWindowContexts()

        val interactionContexts =
            interactionWindowContexts(
                allContexts
            )

        if (
            interactionContexts.isEmpty()
        ) {
            return false
        }

        var best: ContextNodeMatch? = null

        for (context in interactionContexts) {
            val root =
                context.root
                    ?: continue

            val candidate =
                findBestNode(
                    root = root,
                    target = target,
                    requireEditable = false,
                    requireClickable = false
                )
                    ?: continue

            val totalScore =
                candidate.score * 1000 +
                    context.rank

            if (
                best == null ||
                totalScore > best.totalScore
            ) {
                best =
                    ContextNodeMatch(
                        context = context,
                        match = candidate,
                        totalScore = totalScore
                    )
            }
        }

        val before =
            screenSignature()

        if (best != null) {
            val match =
                best.match

            val center =
                nodeCenter(
                    match.node
                )

            val safeAtPoint =
                center == null ||
                    !isPointOccludedForContext(
                        x = center.first,
                        y = center.second,
                        target = best.context,
                        allContexts = allContexts
                    )

            if (safeAtPoint) {
                val actionable =
                    findActionableParent(
                        match.node,
                        AccessibilityNodeInfo.ACTION_CLICK
                    )

                if (actionable != null) {
                    val accepted =
                        try {
                            actionable.performAction(
                                AccessibilityNodeInfo.ACTION_CLICK
                            )
                        } catch (_: Exception) {
                            false
                        }

                    if (
                        accepted &&
                        waitForScreenChange(before)
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
                    waitForScreenChange(before)
                ) {
                    return true
                }

                if (
                    actionable != null &&
                    actionable !== match.node
                ) {
                    val rowCenter =
                        nodeCenter(
                            actionable
                        )

                    if (
                        rowCenter != null &&
                        !isPointOccludedForContext(
                            x = rowCenter.first,
                            y = rowCenter.second,
                            target = best.context,
                            allContexts = allContexts
                        )
                    ) {
                        val rowTapAccepted =
                            tapNodeCenter(
                                actionable
                            )

                        if (
                            rowTapAccepted &&
                            waitForScreenChange(before)
                        ) {
                            return true
                        }
                    }
                }
            }
        }

        // Event-source fallback is allowed only inside the current interaction
        // package/window group. Never use stale evidence from a background app.
        val eventTarget =
            findRecentEventTapTarget(
                target = target,
                interactionContexts = interactionContexts
            )

        if (eventTarget != null) {
            val targetContext =
                contextForEventTarget(
                    eventTarget,
                    interactionContexts
                )

            val centerX =
                eventTarget.bounds.centerX()
            val centerY =
                eventTarget.bounds.centerY()

            if (
                targetContext != null &&
                !isPointOccludedForContext(
                    x = centerX,
                    y = centerY,
                    target = targetContext,
                    allContexts = allContexts
                ) &&
                tapBoundsCenter(
                    eventTarget.bounds
                ) &&
                waitForScreenChange(before)
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

        val contexts =
            interactionWindowContexts(
                resolveWindowContexts()
            )

        if (contexts.isEmpty()) {
            return false
        }

        val node =
            if (target.isNullOrBlank()) {
                contexts
                    .asSequence()
                    .mapNotNull { context ->
                        context.root
                            ?.let { root ->
                                findFocusedEditable(root)
                            }
                    }
                    .firstOrNull()
                    ?: contexts
                        .asSequence()
                        .mapNotNull { context ->
                            context.root
                                ?.let { root ->
                                    findFirstEditable(root)
                                }
                        }
                        .firstOrNull()
            } else {
                var best: ContextNodeMatch? = null

                for (context in contexts) {
                    val root = context.root ?: continue
                    val candidate =
                        findBestNode(
                            root = root,
                            target = target,
                            requireEditable = true,
                            requireClickable = false
                        ) ?: continue

                    val totalScore =
                        candidate.score * 1000 +
                            context.rank

                    if (best == null || totalScore > best.totalScore) {
                        best = ContextNodeMatch(context, candidate, totalScore)
                    }
                }

                best?.match?.node
            }
                ?: return false

        if (node.isPassword) {
            return false
        }

        node.performAction(
            AccessibilityNodeInfo.ACTION_FOCUS
        )

        val arguments =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }

        return node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            arguments
        )
    }

    fun scroll(
        direction: String
    ): Boolean {

        val allContexts =
            resolveWindowContexts()

        val contexts =
            interactionWindowContexts(
                allContexts
            )

        if (contexts.isEmpty()) {
            return false
        }

        val normalized =
            normalize(direction)

        val preferredAction =
            when {
                normalized.contains("вверх") ||
                    normalized.contains("up") ||
                    normalized.contains("назад") ||
                    normalized.contains("back") ->
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

                else ->
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }

        data class ScrollCandidate(
            val context: WindowContext,
            val node: AccessibilityNodeInfo,
            val score: Long
        )

        var best: ScrollCandidate? = null

        for (context in contexts) {
            val root = context.root ?: continue
            val scrollable =
                findBestScrollable(root)
                    ?: continue

            val bounds = Rect()
            scrollable.getBoundsInScreen(bounds)

            val area =
                bounds.width().coerceAtLeast(0).toLong() *
                    bounds.height().coerceAtLeast(0).toLong()

            val score =
                area + context.rank.toLong() * 100L

            if (best == null || score > best.score) {
                best = ScrollCandidate(context, scrollable, score)
            }
        }

        val candidate =
            best
                ?: return false

        val center =
            nodeCenter(candidate.node)

        if (
            center != null &&
            isPointOccludedForContext(
                x = center.first,
                y = center.second,
                target = candidate.context,
                allContexts = allContexts
            )
        ) {
            return false
        }

        val before =
            screenSignature()

        val accepted =
            try {
                candidate.node.performAction(
                    preferredAction
                )
            } catch (_: Exception) {
                false
            }

        return accepted &&
            waitForScreenChange(before)
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

        val liveContexts =
            resolveWindowContexts()

        val evidence =
            currentEventEvidenceBurst(
                liveContexts
            )

        if (
            liveContexts.isEmpty() &&
            evidence.isEmpty()
        ) {
            return JSONObject()
                .put("success", false)
                .put("message", "Активное окно недоступно")
                .put("window_count", 0)
                .put("raw_window_count", safeWindowCount())
        }

        data class WindowAccumulator(
            val context: WindowContext,
            val nodes: JSONArray = JSONArray(),
            val texts: LinkedHashSet<String> = linkedSetOf(),
            val liveTexts: LinkedHashSet<String> = linkedSetOf(),
            val evidenceTexts: LinkedHashSet<String> = linkedSetOf(),
            var evidenceAgeMs: Long = -1L
        )

        val accumulators =
            linkedMapOf<String, WindowAccumulator>()

        for (context in liveContexts) {
            val accumulator =
                WindowAccumulator(context)

            // Window titles are Android-owned metadata and are useful when an
            // OEM exposes a valid window but temporarily returns a sparse root.
            // Keep the title inside the SAME context; never promote it across
            // windows or use it as proof for another package.
            val title =
                safeText(
                    context.title
                )

            if (title.isNotBlank()) {
                accumulator.texts.add(title)
                accumulator.liveTexts.add(title)
            }

            accumulators[context.contextId] =
                accumulator
        }

        val liveNodeBudget =
            if (evidence.isNotEmpty()) {
                maxOf(40, maxNodes * 2 / 3)
            } else {
                maxNodes
            }

        val liveCharBudget =
            if (evidence.isNotEmpty()) {
                maxOf(4000, maxChars * 2 / 3)
            } else {
                maxChars
            }

        data class QueueItem(
            val context: WindowContext,
            val node: AccessibilityNodeInfo,
            val depth: Int
        )

        val queue =
            ArrayDeque<QueueItem>()

        for (context in liveContexts) {
            context.root?.let { root ->
                queue.add(
                    QueueItem(context, root, 0)
                )
            }
        }

        var visited = 0
        var charCount = 0

        while (
            queue.isNotEmpty() &&
            visited < liveNodeBudget &&
            charCount < liveCharBudget
        ) {
            val current = queue.removeFirst()
            visited++

            val item =
                try {
                    nodeToJson(
                        node = current.node,
                        depth = current.depth,
                        index = visited
                    )
                        .put("context_id", current.context.contextId)
                        .put("window_id", current.context.windowId)
                        .put("window_type", current.context.type)
                        .put("window_layer", current.context.layer)
                        .put("window_active", current.context.active)
                        .put("window_focused", current.context.focused)
                        .put("node_source", "live")
                } catch (_: Exception) {
                    null
                }

            if (item != null) {
                val serialized = item.toString()
                if (charCount + serialized.length <= liveCharBudget) {
                    accumulators[current.context.contextId]
                        ?.nodes
                        ?.put(item)
                    appendNodeTexts(
                        accumulators[current.context.contextId]?.texts,
                        item
                    )
                    appendNodeTexts(
                        accumulators[current.context.contextId]?.liveTexts,
                        item
                    )
                    charCount += serialized.length
                }
            }

            val childCount =
                try {
                    current.node.childCount
                } catch (_: Exception) {
                    0
                }

            for (index in 0 until childCount) {
                val child =
                    childWithPrefetch(
                        current.node,
                        index
                    ) ?: continue

                queue.add(
                    QueueItem(
                        context = current.context,
                        node = child,
                        depth = current.depth + 1
                    )
                )
            }
        }

        val now =
            System.currentTimeMillis()

        var evidenceContextCount = 0

        for (item in evidence) {
            val matched =
                accumulators.values.firstOrNull { accumulator ->
                    item.windowId >= 0 &&
                        accumulator.context.windowId == item.windowId
                } ?: run {
                    if (item.windowId >= 0) {
                        null
                    } else {
                        accumulators.values
                            .filter { accumulator ->
                                accumulator.context.packageName == item.packageName
                            }
                            .maxByOrNull { accumulator ->
                                overlapRatio(
                                    accumulator.context.bounds,
                                    item.bounds
                                )
                            }
                            ?.takeIf { accumulator ->
                                overlapRatio(
                                    accumulator.context.bounds,
                                    item.bounds
                                ) >= 0.25
                            }
                    }
                }

            val accumulator =
                matched
                    ?: run {
                        val synthetic =
                            syntheticContextForEvidence(
                                evidence = item,
                                liveContexts = liveContexts
                            )
                        evidenceContextCount++
                        WindowAccumulator(synthetic).also { created ->
                            accumulators[synthetic.contextId] = created
                        }
                    }

            val age =
                (now - item.at).coerceAtLeast(0L)

            if (
                accumulator.evidenceAgeMs < 0L ||
                age < accumulator.evidenceAgeMs
            ) {
                accumulator.evidenceAgeMs = age
            }

            for (text in item.visibleText) {
                val clipped = safeText(text)
                if (clipped.isNotBlank()) {
                    accumulator.texts.add(clipped)
                    accumulator.evidenceTexts.add(clipped)
                }
            }

            for (index in 0 until item.nodes.length()) {
                if (visited >= maxNodes || charCount >= maxChars) {
                    break
                }

                val original =
                    item.nodes.optJSONObject(index)
                        ?: continue

                val node =
                    JSONObject(original.toString())
                        .put("context_id", accumulator.context.contextId)
                        .put("window_id", accumulator.context.windowId)
                        .put("window_type", accumulator.context.type)
                        .put("window_layer", accumulator.context.layer)
                        .put("window_active", accumulator.context.active)
                        .put("window_focused", accumulator.context.focused)
                        .put("node_source", "event")

                val serialized = node.toString()
                if (charCount + serialized.length > maxChars) {
                    break
                }

                accumulator.nodes.put(node)
                appendNodeTexts(accumulator.texts, node)
                appendNodeTexts(accumulator.evidenceTexts, node)
                visited++
                charCount += serialized.length
            }
        }

        val allContexts =
            accumulators.values
                .map { it.context }
                .sortedByDescending { it.rank }

        val interactionContexts =
            interactionWindowContexts(
                allContexts
            )

        val interactionIds =
            interactionContexts
                .map { it.contextId }
                .toSet()

        val primary =
            primaryWindowContext(
                allContexts
            )

        val windowsJson = JSONArray()
        val topNodes = JSONArray()
        val topVisible = linkedSetOf<String>()
        val verificationVisible = linkedSetOf<String>()
        val allVisible = linkedSetOf<String>()
        val packages = linkedSetOf<String>()

        val sortedAccumulators =
            accumulators.values
                .sortedByDescending { it.context.rank }

        for (accumulator in sortedAccumulators) {
            val context = accumulator.context
            if (context.packageName.isNotBlank()) {
                packages.add(context.packageName)
            }

            val visibleArray = JSONArray()
            for (text in accumulator.texts) {
                if (visibleArray.length() < WINDOW_VISIBLE_TEXT_LIMIT) {
                    visibleArray.put(text)
                }

                if (allVisible.size < ALL_VISIBLE_TEXT_LIMIT) {
                    allVisible.add(text)
                }

                if (context.contextId in interactionIds && topVisible.size < TOP_VISIBLE_TEXT_LIMIT) {
                    topVisible.add(text)
                }
            }

            val contextVerificationTexts =
                linkedSetOf<String>()
                    .apply {
                        addAll(accumulator.liveTexts)
                        if (
                            accumulator.evidenceAgeMs >= 0L &&
                            accumulator.evidenceAgeMs <= EVENT_VERIFICATION_TTL_MS
                        ) {
                            addAll(accumulator.evidenceTexts)
                        }
                    }

            val contextVerificationText =
                contextVerificationTexts
                    .joinToString(" | ")
                    .take(VERIFICATION_TEXT_MAX_CHARS)

            if (context.contextId in interactionIds) {
                verificationVisible.addAll(contextVerificationTexts)
            }

            windowsJson.put(
                JSONObject()
                    .put("context_id", context.contextId)
                    .put("window_id", context.windowId)
                    .put("package", context.packageName)
                    .put("root_class", context.className)
                    .put("title", context.title)
                    .put("type", context.type)
                    .put("type_name", windowTypeName(context.type))
                    .put("layer", context.layer)
                    .put("active", context.active)
                    .put("focused", context.focused)
                    .put("picture_in_picture", context.pictureInPicture)
                    .put("source", context.source)
                    .put("interaction_rank", context.rank)
                    .put("interaction_context", context.contextId in interactionIds)
                    .put("occlusion_ratio", contextOcclusionRatio(context, allContexts))
                    .put("bounds", rectToJson(context.bounds))
                    .put("evidence_age_ms", accumulator.evidenceAgeMs)
                    .put("verification_text", contextVerificationText)
                    .put("visible_text", visibleArray)
            )

            if (context.contextId in interactionIds) {
                for (index in 0 until accumulator.nodes.length()) {
                    topNodes.put(
                        accumulator.nodes.optJSONObject(index)
                    )
                }
            }
        }

        val visibleText = JSONArray()
        for (text in topVisible) visibleText.put(text)

        val allVisibleText = JSONArray()
        for (text in allVisible) allVisibleText.put(text)

        val packageArray = JSONArray()
        for (pkg in packages) packageArray.put(pkg)

        val verificationText =
            verificationVisible
                .joinToString(" | ")
                .take(VERIFICATION_TEXT_MAX_CHARS)

        return JSONObject()
            .put("success", true)
            .put("package", primary?.packageName.orEmpty())
            .put("root_class", primary?.className.orEmpty())
            .put("primary_context_id", primary?.contextId.orEmpty())
            .put("primary_window_title", primary?.title.orEmpty())
            .put("interaction_package", primary?.packageName.orEmpty())
            .put("window_context_mode", "v2_prefetch")
            .put("window_count", allContexts.size)
            .put("raw_window_count", safeWindowCount())
            .put("evidence_context_count", evidenceContextCount)
            .put("packages", packageArray)
            .put("visible_text", visibleText)
            .put("all_visible_text", allVisibleText)
            .put("verification_text", verificationText)
            .put("node_count", topNodes.length())
            .put("nodes", topNodes)
            .put("windows", windowsJson)
            .put("event_package", lastEventPackage)
            .put("event_class", lastEventClass)
            .put("event_window_id", lastEventWindowId)
            .put(
                "event_age_ms",
                (System.currentTimeMillis() - lastEventTime)
                    .coerceAtLeast(0L)
            )
    }

    fun screenSignature():
        String {

        val snapshot =
            buildScreenSnapshot(
                maxNodes = 80,
                maxChars = 6000
            )

        if (!snapshot.optBoolean("success", false)) {
            return "unavailable"
        }

        return (
            snapshot.optString("primary_context_id") +
                "|" +
                snapshot.optString("package") +
                "|" +
                snapshot.optString("verification_text")
            )
            .take(7000)
            .hashCode()
            .toString()
    }

    private fun captureEventEvidence(
        event: AccessibilityEvent
    ) {

        val now =
            System.currentTimeMillis()

        val eventPackage =
            event.packageName
                ?.toString()
                .orEmpty()

        if (
            eventPackage.isBlank() ||
            eventPackage ==
                packageName
        ) {
            pruneEventEvidence(
                now
            )
            return
        }

        val source =
            try {
                event.source
            } catch (_: Exception) {
                null
            }

        val evidenceRoot =
            source
                ?.let {
                    highestUsableEventRoot(
                        it
                    )
                }

        val nodes =
            if (
                evidenceRoot !=
                null
            ) {
                snapshotEventTree(
                    root =
                        evidenceRoot,
                    maxNodes =
                        EVENT_EVIDENCE_NODE_LIMIT,
                    maxChars =
                        EVENT_EVIDENCE_CHAR_LIMIT
                )
            } else {
                JSONArray()
            }

        val texts =
            linkedSetOf<String>()

        try {
            for (
                item in
                event.text
            ) {

                val value =
                    safeText(
                        item
                            ?.toString()
                            .orEmpty()
                    )

                if (
                    value.isNotBlank()
                ) {
                    texts.add(
                        value
                    )
                }
            }
        } catch (_: Exception) {
        }

        val eventDescription =
            try {
                safeText(
                    event.contentDescription
                        ?.toString()
                        .orEmpty()
                )
            } catch (_: Exception) {
                ""
            }

        if (
            eventDescription.isNotBlank()
        ) {
            texts.add(
                eventDescription
            )
        }

        val nodeTexts =
            collectVisibleTexts(
                nodes
            )

        for (
            index in
            0 until nodeTexts.length()
        ) {

            val value =
                nodeTexts
                    .optString(
                        index
                    )
                    .trim()

            if (
                value.isNotBlank()
            ) {
                texts.add(
                    value
                )
            }

            if (
                texts.size >=
                EVENT_EVIDENCE_TEXT_LIMIT
            ) {
                break
            }
        }

        if (
            texts.isEmpty() &&
            nodes.length() ==
                0
        ) {
            pruneEventEvidence(
                now
            )
            return
        }

        val eventWindowId =
            try {
                event.windowId
            } catch (_: Exception) {
                -1
            }

        val evidenceBounds =
            Rect().apply {
                if (evidenceRoot != null) {
                    try {
                        evidenceRoot.getBoundsInScreen(this)
                    } catch (_: Exception) {
                    }
                }
            }

        val evidence =
            EventEvidence(
                at = now,
                windowId = eventWindowId,
                packageName =
                    eventPackage,
                className =
                    event.className
                        ?.toString()
                        .orEmpty(),
                eventType =
                    event.eventType,
                bounds = evidenceBounds,
                nodes =
                    nodes,
                visibleText =
                    texts
                        .take(
                            EVENT_EVIDENCE_TEXT_LIMIT
                        )
            )

        synchronized(
            eventEvidenceLock
        ) {

            pruneEventEvidenceLocked(
                now
            )

            recentEventEvidence.addFirst(
                evidence
            )

            while (
                recentEventEvidence.size >
                EVENT_EVIDENCE_MAX_RECORDS
            ) {
                recentEventEvidence.removeLast()
            }
        }
    }

    private fun highestUsableEventRoot(
        source: AccessibilityNodeInfo
    ): AccessibilityNodeInfo {

        var current =
            source

        var best =
            source

        val sourceWindowId =
            try {
                source.windowId
            } catch (_: Exception) {
                -1
            }

        var depth =
            0

        while (
            depth <
            EVENT_EVIDENCE_PARENT_LIMIT
        ) {

            val parent =
                try {
                    current.parent
                } catch (_: Exception) {
                    null
                }
                    ?: break

            val parentWindowId =
                try {
                    parent.windowId
                } catch (_: Exception) {
                    sourceWindowId
                }

            if (
                sourceWindowId >=
                0 &&
                parentWindowId >=
                0 &&
                parentWindowId !=
                sourceWindowId
            ) {
                break
            }

            best =
                parent

            current =
                parent

            depth++
        }

        return best
    }

    private fun snapshotEventTree(
        root: AccessibilityNodeInfo,
        maxNodes: Int,
        maxChars: Int
    ): JSONArray {

        val nodes =
            JSONArray()

        val queue =
            ArrayDeque<
                Pair<
                    AccessibilityNodeInfo,
                    Int
                >
            >()

        queue.add(
            root to
                0
        )

        var visited =
            0

        var charCount =
            0

        while (
            queue.isNotEmpty() &&
            visited <
            maxNodes &&
            charCount <
            maxChars
        ) {

            val (
                node,
                depth
            ) =
                queue.removeFirst()

            visited++

            val item =
                try {
                    nodeToJson(
                        node =
                            node,
                        depth =
                            depth,
                        index =
                            visited
                    )
                        .put(
                            "evidence_source",
                            "accessibility_event"
                        )
                } catch (_: Exception) {
                    null
                }

            if (
                item !=
                null
            ) {

                val serialized =
                    item.toString()

                if (
                    charCount +
                    serialized.length <=
                    maxChars
                ) {
                    nodes.put(
                        item
                    )

                    charCount +=
                        serialized.length
                }
            }

            val childCount =
                try {
                    node.childCount
                } catch (_: Exception) {
                    0
                }

            for (
                index in
                0 until childCount
            ) {

                val child =
                    childWithPrefetch(
                        node,
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

        return nodes
    }

    private fun tapBoundsCenter(
        bounds: Rect
    ): Boolean {

        if (
            bounds.width() <=
            0 ||
            bounds.height() <=
            0
        ) {
            return false
        }

        val x =
            bounds.centerX()

        val y =
            bounds.centerY()

        val path =
            Path()
                .apply {
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

    private fun pruneEventEvidence(
        now: Long =
            System.currentTimeMillis()
    ) {

        synchronized(
            eventEvidenceLock
        ) {
            pruneEventEvidenceLocked(
                now
            )
        }
    }

    private fun pruneEventEvidenceLocked(
        now: Long
    ) {

        while (
            recentEventEvidence.isNotEmpty()
        ) {

            val oldest =
                recentEventEvidence.last()

            if (
                now -
                oldest.at <=
                EVENT_EVIDENCE_TTL_MS
            ) {
                break
            }

            recentEventEvidence.removeLast()
        }
    }

    /**
     * Samsung tablet Settings can expose the navigation pane and the detail pane
     * through more than one interactive accessibility root. Keep every relevant
     * application root, rank the most likely foreground roots first, and let
     * snapshot/search logic inspect them together. This is stricter than using
     * rootInActiveWindow alone and prevents false verification failures when the
     * requested detail page is visible in a sibling Settings root.
     */
    private fun resolveWindowContexts():
        List<WindowContext> {

        val contexts =
            mutableListOf<WindowContext>()

        val seen =
            linkedSetOf<String>()

        val snapshotWindows =
            try {
                windows
            } catch (_: Exception) {
                emptyList()
            }

        var maxLayer = 0

        for (window in snapshotWindows) {
            val bounds = Rect()
            try {
                window.getBoundsInScreen(bounds)
            } catch (_: Exception) {
            }

            val root =
                prefetchedWindowRoot(
                    window
                )

            val windowTitle =
                try {
                    safeText(
                        window.title
                            ?.toString()
                            .orEmpty()
                    )
                } catch (_: Exception) {
                    ""
                }

            val rootPackage =
                root?.packageName
                    ?.toString()
                    .orEmpty()

            val rootClass =
                root?.className
                    ?.toString()
                    .orEmpty()

            val windowId =
                try { window.id } catch (_: Exception) { -1 }

            val type =
                try { window.type } catch (_: Exception) { -1 }

            val layer =
                try { window.layer } catch (_: Exception) { 0 }

            maxLayer = maxOf(maxLayer, layer)

            val active =
                try { window.isActive } catch (_: Exception) { false }

            val focused =
                try { window.isFocused } catch (_: Exception) { false }

            val pip =
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        window.isInPictureInPictureMode
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }

            if (
                isLikelyOwnOverlayWindow(
                    packageName = rootPackage,
                    bounds = bounds,
                    active = active,
                    focused = focused,
                    type = type
                )
            ) {
                continue
            }

            val contextId =
                liveContextId(
                    windowId = windowId,
                    packageName = rootPackage,
                    bounds = bounds
                )

            if (!seen.add(contextId)) {
                continue
            }

            contexts.add(
                WindowContext(
                    contextId = contextId,
                    windowId = windowId,
                    packageName = rootPackage,
                    className = rootClass,
                    type = type,
                    layer = layer,
                    active = active,
                    focused = focused,
                    pictureInPicture = pip,
                    bounds = Rect(bounds),
                    root = root,
                    source = "windows",
                    rank = windowInteractionRank(
                        windowId = windowId,
                        packageName = rootPackage,
                        type = type,
                        layer = layer,
                        active = active,
                        focused = focused,
                        pictureInPicture = pip
                    ),
                    title = windowTitle
                )
            )
        }

        val activeRoot =
            try {
                rootInActiveWindow
            } catch (_: Exception) {
                null
            }

        if (activeRoot != null) {
            val activeWindowId =
                try { activeRoot.windowId } catch (_: Exception) { -1 }

            val activePackage =
                activeRoot.packageName
                    ?.toString()
                    .orEmpty()

            val alreadyPresent =
                contexts.any { context ->
                    (
                        activeWindowId >= 0 &&
                        context.windowId == activeWindowId
                    ) ||
                        context.root === activeRoot
                }

            if (!alreadyPresent) {
                val bounds = Rect()
                try { activeRoot.getBoundsInScreen(bounds) } catch (_: Exception) { }

                if (
                    !isLikelyOwnOverlayWindow(
                        packageName = activePackage,
                        bounds = bounds,
                        active = true,
                        focused = true,
                        type = AccessibilityWindowInfo.TYPE_APPLICATION
                    )
                ) {
                    val contextId =
                        liveContextId(
                            windowId = activeWindowId,
                            packageName = activePackage,
                            bounds = bounds
                        )

                    contexts.add(
                        WindowContext(
                            contextId = contextId,
                            windowId = activeWindowId,
                            packageName = activePackage,
                            className = activeRoot.className
                                ?.toString()
                                .orEmpty(),
                            type = AccessibilityWindowInfo.TYPE_APPLICATION,
                            layer = maxLayer + 1,
                            active = true,
                            focused = true,
                            pictureInPicture = false,
                            bounds = Rect(bounds),
                            root = activeRoot,
                            source = "active_root",
                            rank = windowInteractionRank(
                                windowId = activeWindowId,
                                packageName = activePackage,
                                type = AccessibilityWindowInfo.TYPE_APPLICATION,
                                layer = maxLayer + 1,
                                active = true,
                                focused = true,
                                pictureInPicture = false
                            )
                        )
                    )
                }
            }
        }

        lastRootSource =
            when {
                contexts.isEmpty() -> "unavailable"
                contexts.size == 1 -> "single_window_context"
                else -> "multi_window_context"
            }

        return contexts
            .sortedByDescending { it.rank }
    }

    private fun primaryWindowContext(
        contexts: List<WindowContext>
    ): WindowContext? {

        if (contexts.isEmpty()) {
            return null
        }

        // v4.0: a blank focused shell is not allowed to outrank a real app.
        // Samsung/One UI can briefly expose an active/focused window with no
        // package/root text during fragment and multi-window transitions.
        // Prefer a package-bearing context first; fall back to a blank shell
        // only when Android exposes nothing else.
        val usable =
            contexts
                .filter {
                    it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                        it.packageName.isNotBlank()
                }

        return usable
            .filter { it.focused }
            .maxByOrNull { it.rank }
            ?: usable
                .filter { it.active }
                .maxByOrNull { it.rank }
            ?: usable
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .maxByOrNull { it.rank }
            ?: usable.maxByOrNull { it.rank }
            ?: contexts
                .filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .maxByOrNull { it.rank }
            ?: contexts.maxByOrNull { it.rank }
    }

    private fun interactionWindowContexts(
        contexts: List<WindowContext>
    ): List<WindowContext> {

        val primary =
            primaryWindowContext(contexts)
                ?: return emptyList()

        val primaryPackage =
            primary.packageName

        val result =
            contexts
                .filter { context ->
                    if (context.contextId == primary.contextId) {
                        return@filter true
                    }

                    if (primaryPackage.isBlank() || context.packageName != primaryPackage) {
                        return@filter false
                    }

                    if (context.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        return@filter false
                    }

                    // Fail closed for multiple independent windows that happen
                    // to belong to the same package (for example two Chrome
                    // freeform windows). A sibling joins the interaction group only
                    // when Android marks it active/focused or a fresh Accessibility
                    // event identifies that exact window as the current interaction
                    // surface. This still admits Samsung Settings detail panes via
                    // their event evidence without making every same-package window
                    // actionable.
                    val currentInteractionEvidence =
                        context.active ||
                            context.focused ||
                            context.source == "event_evidence" ||
                            (
                                context.windowId >= 0 &&
                                    context.windowId == lastEventWindowId &&
                                    System.currentTimeMillis() - lastEventTime <=
                                    EVENT_INTERACTION_FRESH_MS
                                )

                    if (!currentInteractionEvidence) {
                        return@filter false
                    }

                    val overlap =
                        overlapRatio(
                            context.bounds,
                            primary.bounds
                        )

                    // A lower-layer overlapping window is obscured background,
                    // even if it emitted a recent event. Adjacent/non-overlapping
                    // sibling panes may coexist in the current interaction group.
                    if (
                        overlap > 0.20 &&
                        context.layer < primary.layer
                    ) {
                        return@filter false
                    }

                    contextOcclusionRatio(
                        context,
                        contexts
                    ) < 0.80
                }
                .sortedByDescending { it.rank }

        return if (result.isNotEmpty()) {
            result
        } else {
            listOf(primary)
        }
    }

    private fun windowInteractionRank(
        windowId: Int,
        packageName: String,
        type: Int,
        layer: Int,
        active: Boolean,
        focused: Boolean,
        pictureInPicture: Boolean
    ): Int {

        var score =
            layer.coerceIn(-1000, 1000) * 10

        if (focused) score += 10000
        if (active) score += 7000
        if (type == AccessibilityWindowInfo.TYPE_APPLICATION) score += 2500
        if (packageName.isBlank()) score -= 12000
        if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) score -= 1800
        if (pictureInPicture) score -= 400
        if (windowId >= 0 && windowId == lastEventWindowId) score += 1800
        if (packageName.isNotBlank() && packageName == lastEventPackage) score += 600

        return score
    }

    private fun liveContextId(
        windowId: Int,
        packageName: String,
        bounds: Rect
    ): String {

        return if (windowId >= 0) {
            "w:$windowId:$packageName"
        } else {
            "w:x:$packageName:${bounds.left}:${bounds.top}:${bounds.right}:${bounds.bottom}"
        }
    }

    private fun isLikelyOwnOverlayWindow(
        packageName: String,
        bounds: Rect,
        active: Boolean,
        focused: Boolean,
        type: Int
    ): Boolean {

        if (packageName != this.packageName) {
            return false
        }

        if (active || focused) {
            return false
        }

        if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
            return true
        }

        val screenArea =
            resources.displayMetrics.widthPixels
                .coerceAtLeast(1)
                .toLong() *
                resources.displayMetrics.heightPixels
                    .coerceAtLeast(1)
                    .toLong()

        val area =
            bounds.width().coerceAtLeast(0).toLong() *
                bounds.height().coerceAtLeast(0).toLong()

        return area > 0L &&
            area.toDouble() / screenArea.toDouble() <= OWN_OVERLAY_MAX_AREA_RATIO
    }

    private fun currentEventEvidenceBurst(
        liveContexts: List<WindowContext>
    ): List<EventEvidence> {

        val now = System.currentTimeMillis()
        val liveIds = liveContexts.map { it.windowId }.filter { it >= 0 }.toSet()
        val livePackages = liveContexts.map { it.packageName }.filter { it.isNotBlank() }.toSet()

        synchronized(eventEvidenceLock) {
            pruneEventEvidenceLocked(now)

            val eligible =
                recentEventEvidence
                    .filter { evidence ->
                        val age = now - evidence.at
                        age <= EVENT_EVIDENCE_TTL_MS &&
                            evidence.packageName != packageName &&
                            (
                                (evidence.windowId >= 0 && evidence.windowId in liveIds) ||
                                    evidence.packageName in livePackages ||
                                    (
                                        liveContexts.isEmpty() &&
                                        evidence.packageName == lastEventPackage &&
                                        age <= EVENT_EVIDENCE_ORPHAN_TTL_MS
                                    )
                                )
                    }

            val newestByKey = mutableMapOf<String, Long>()
            val newestStructuralByKey = mutableMapOf<String, Long>()

            for (evidence in eligible) {
                val key = evidenceContextKey(evidence)
                val current = newestByKey[key] ?: Long.MIN_VALUE
                if (evidence.at > current) newestByKey[key] = evidence.at

                if (isStructuralEvidence(evidence.eventType)) {
                    val structural = newestStructuralByKey[key] ?: Long.MIN_VALUE
                    if (evidence.at > structural) newestStructuralByKey[key] = evidence.at
                }
            }

            return eligible
                .filter { evidence ->
                    val key = evidenceContextKey(evidence)
                    val newest = newestByKey[key] ?: evidence.at
                    val newestStructural = newestStructuralByKey[key]

                    val burstOk =
                        newest - evidence.at <= EVENT_EVIDENCE_BURST_MS

                    val structuralCutoffOk =
                        newestStructural == null ||
                            evidence.at >= newestStructural - EVENT_EVIDENCE_STRUCTURAL_LEEWAY_MS

                    burstOk && structuralCutoffOk
                }
                .sortedByDescending { it.at }
        }
    }

    private fun isStructuralEvidence(
        eventType: Int
    ): Boolean {

        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
    }

    private fun evidenceContextKey(
        evidence: EventEvidence
    ): String {

        return if (evidence.windowId >= 0) {
            "${evidence.packageName}|${evidence.windowId}"
        } else {
            "${evidence.packageName}|${evidence.className}|${evidence.bounds.left / 80}|${evidence.bounds.top / 80}"
        }
    }

    private fun syntheticContextForEvidence(
        evidence: EventEvidence,
        liveContexts: List<WindowContext>
    ): WindowContext {

        val samePackageLayer =
            liveContexts
                .filter { it.packageName == evidence.packageName }
                .maxOfOrNull { it.layer }
                ?: 0

        val rank =
            windowInteractionRank(
                windowId = evidence.windowId,
                packageName = evidence.packageName,
                type = EVIDENCE_WINDOW_TYPE,
                layer = samePackageLayer,
                active = evidence.windowId >= 0 && evidence.windowId == lastEventWindowId,
                focused = false,
                pictureInPicture = false
            ) - EVIDENCE_CONTEXT_RANK_PENALTY

        return WindowContext(
            contextId = "e:${evidenceContextKey(evidence)}",
            windowId = evidence.windowId,
            packageName = evidence.packageName,
            className = evidence.className,
            type = EVIDENCE_WINDOW_TYPE,
            layer = samePackageLayer,
            active = evidence.windowId >= 0 && evidence.windowId == lastEventWindowId,
            focused = false,
            pictureInPicture = false,
            bounds = Rect(evidence.bounds),
            root = null,
            source = "event_evidence",
            rank = rank
        )
    }

    private fun windowTypeName(
        type: Int
    ): String {

        return when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
            EVIDENCE_WINDOW_TYPE -> "event_evidence"
            else -> "other_$type"
        }
    }

    private fun overlapRatio(
        first: Rect,
        second: Rect
    ): Double {

        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)

        if (right <= left || bottom <= top) return 0.0

        val intersection =
            (right - left).toLong() * (bottom - top).toLong()

        val base =
            minOf(
                first.width().coerceAtLeast(0).toLong() * first.height().coerceAtLeast(0).toLong(),
                second.width().coerceAtLeast(0).toLong() * second.height().coerceAtLeast(0).toLong()
            )

        if (base <= 0L) return 0.0

        return (intersection.toDouble() / base.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun contextOcclusionRatio(
        target: WindowContext,
        contexts: List<WindowContext>
    ): Double {

        val targetArea =
            target.bounds.width().coerceAtLeast(0).toLong() *
                target.bounds.height().coerceAtLeast(0).toLong()

        if (targetArea <= 0L) return 0.0

        var covered = 0L

        for (other in contexts) {
            if (other.contextId == target.contextId || other.layer <= target.layer) continue

            val left = maxOf(target.bounds.left, other.bounds.left)
            val top = maxOf(target.bounds.top, other.bounds.top)
            val right = minOf(target.bounds.right, other.bounds.right)
            val bottom = minOf(target.bounds.bottom, other.bounds.bottom)

            if (right > left && bottom > top) {
                covered += (right - left).toLong() * (bottom - top).toLong()
            }
        }

        return (covered.toDouble() / targetArea.toDouble())
            .coerceIn(0.0, 1.0)
    }

    private fun isPointOccludedForContext(
        x: Int,
        y: Int,
        target: WindowContext,
        allContexts: List<WindowContext>
    ): Boolean {

        return allContexts.any { other ->
            other.contextId != target.contextId &&
                other.layer > target.layer &&
                other.bounds.contains(x, y)
        }
    }

    private fun nodeCenter(
        node: AccessibilityNodeInfo
    ): Pair<Int, Int>? {

        val bounds = Rect()
        return try {
            node.getBoundsInScreen(bounds)
            if (bounds.width() <= 1 || bounds.height() <= 1) null
            else bounds.centerX() to bounds.centerY()
        } catch (_: Exception) {
            null
        }
    }

    private fun rectToJson(
        bounds: Rect
    ): JSONObject {

        return JSONObject()
            .put("left", bounds.left)
            .put("top", bounds.top)
            .put("right", bounds.right)
            .put("bottom", bounds.bottom)
    }

    private fun appendNodeTexts(
        target: MutableSet<String>?,
        node: JSONObject
    ) {

        if (target == null) return

        listOf(
            node.optString("text"),
            node.optString("description")
        )
            .map { safeText(it) }
            .filter { it.isNotBlank() && it != "[PASSWORD_HIDDEN]" }
            .forEach { target.add(it) }
    }

    private fun contextForEventTarget(
        target: EventTapTarget,
        contexts: List<WindowContext>
    ): WindowContext? {

        return contexts.firstOrNull { context ->
            target.windowId >= 0 && context.windowId == target.windowId
        } ?: contexts.firstOrNull { context ->
            context.packageName == target.packageName &&
                overlapRatio(context.bounds, target.bounds) > 0.10
        } ?: contexts.firstOrNull { it.packageName == target.packageName }
    }

    private fun findRecentEventTapTarget(
        target: String,
        interactionContexts: List<WindowContext>
    ): EventTapTarget? {

        val normalizedTarget = normalize(target)
        if (normalizedTarget.isBlank()) return null

        val allowedPackages =
            interactionContexts
                .map { it.packageName }
                .filter { it.isNotBlank() }
                .toSet()

        val allowedWindowIds =
            interactionContexts
                .map { it.windowId }
                .filter { it >= 0 }
                .toSet()

        var bestScore = 0
        var best: EventTapTarget? = null
        val now = System.currentTimeMillis()

        for (evidence in currentEventEvidenceBurst(interactionContexts)) {
            if (now - evidence.at > EVENT_ACTION_TTL_MS) {
                continue
            }

            val allowed =
                (evidence.windowId >= 0 && evidence.windowId in allowedWindowIds) ||
                    evidence.packageName in allowedPackages

            if (!allowed) continue

            for (index in 0 until evidence.nodes.length()) {
                val node = evidence.nodes.optJSONObject(index) ?: continue
                if (!node.optBoolean("visible", false) || !node.optBoolean("enabled", true)) continue

                val text = normalize(node.optString("text"))
                val description = normalize(node.optString("description"))
                val viewId = normalize(node.optString("view_id"))

                var score = 0
                score = max(score, scoreValue(text, normalizedTarget, 100))
                score = max(score, scoreValue(description, normalizedTarget, 95))
                score = max(score, scoreValue(viewId, normalizedTarget, 80))
                if (score > 0 && node.optBoolean("clickable", false)) score += 8

                if (score < MIN_MATCH_SCORE || score <= bestScore) continue

                val b = node.optJSONObject("bounds") ?: continue
                val rect = Rect(
                    b.optInt("left", -1),
                    b.optInt("top", -1),
                    b.optInt("right", -1),
                    b.optInt("bottom", -1)
                )

                if (rect.left < 0 || rect.top < 0 || rect.width() <= 1 || rect.height() <= 1) continue

                bestScore = score
                best = EventTapTarget(rect, evidence.windowId, evidence.packageName)
            }
        }

        return best
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

                childWithPrefetch(
                    node,
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

                childWithPrefetch(
                    node,
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

                childWithPrefetch(
                    node,
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

    /**
     * Android 13+ can prefetch descendants when the window root is obtained.
     * Samsung tablet multi-window/Settings was observed to expose the window
     * identity while legacy root traversal returned an empty content tree.
     * Try safe descendant prefetch strategies, then fall back to getRoot().
     */
    private fun prefetchedWindowRoot(
        window: AccessibilityWindowInfo
    ): AccessibilityNodeInfo? {

        var sparseFallback:
            AccessibilityNodeInfo? =
            null

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            val strategies =
                intArrayOf(
                    AccessibilityNodeInfo.FLAG_PREFETCH_ANCESTORS or
                        AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS or
                        AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or
                        AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE,
                    AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST or
                        AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE,
                    AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
                        AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
                )

            for (strategy in strategies) {
                val root =
                    try {
                        window.getRoot(
                            strategy
                        )
                    } catch (_: Exception) {
                        null
                    }
                        ?: continue

                if (
                    sparseFallback ==
                    null
                ) {
                    sparseFallback =
                        root
                }

                if (
                    rootLooksPopulated(
                        root
                    )
                ) {
                    return root
                }
            }
        }

        val legacy =
            try {
                window.root
            } catch (_: Exception) {
                null
            }

        if (
            legacy !=
            null &&
            rootLooksPopulated(
                legacy
            )
        ) {
            return legacy
        }

        // Fail-soft extraction: keeping a sparse package-bearing root is still
        // useful for window identity, bounds and event correlation, but it is
        // NOT considered proof of screen content by verification code.
        return legacy
            ?: sparseFallback
    }

    private fun rootLooksPopulated(
        root: AccessibilityNodeInfo
    ): Boolean {

        val childCount =
            try {
                root.childCount
            } catch (_: Exception) {
                0
            }

        if (
            childCount >
            0
        ) {
            return true
        }

        val text =
            try {
                safeText(
                    root.text
                        ?.toString()
                        .orEmpty()
                )
            } catch (_: Exception) {
                ""
            }

        if (
            text.isNotBlank()
        ) {
            return true
        }

        val description =
            try {
                safeText(
                    root.contentDescription
                        ?.toString()
                        .orEmpty()
                )
            } catch (_: Exception) {
                ""
            }

        return description.isNotBlank()
    }

    /**
     * Child traversal also asks Android 13+ to prefetch the descendant subtree.
     * Normal getChild(index) remains the compatibility path on older Android.
     */
    private fun childWithPrefetch(
        node: AccessibilityNodeInfo,
        index: Int
    ): AccessibilityNodeInfo? {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            val prefetched =
                try {
                    node.getChild(
                        index,
                        AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or
                            AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS
                    )
                } catch (_: Exception) {
                    null
                }

            if (prefetched != null) {
                return prefetched
            }
        }

        return try {
            node.getChild(
                index
            )
        } catch (_: Exception) {
            null
        }
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

        private const val EVENT_EVIDENCE_TTL_MS =
            12000L

        private const val EVENT_VERIFICATION_TTL_MS =
            4500L

        private const val EVENT_ACTION_TTL_MS =
            4500L

        private const val EVENT_EVIDENCE_ORPHAN_TTL_MS =
            3500L

        private const val EVENT_EVIDENCE_BURST_MS =
            1600L

        private const val EVENT_EVIDENCE_STRUCTURAL_LEEWAY_MS =
            450L

        private const val EVENT_INTERACTION_FRESH_MS =
            3500L

        private const val WINDOW_VISIBLE_TEXT_LIMIT =
            36

        private const val TOP_VISIBLE_TEXT_LIMIT =
            96

        private const val ALL_VISIBLE_TEXT_LIMIT =
            144

        private const val VERIFICATION_TEXT_MAX_CHARS =
            7000

        private const val OWN_OVERLAY_MAX_AREA_RATIO =
            0.20

        private const val EVIDENCE_WINDOW_TYPE =
            -100

        private const val EVIDENCE_CONTEXT_RANK_PENALTY =
            350

        private const val EVENT_EVIDENCE_MAX_RECORDS =
            12

        private const val EVENT_EVIDENCE_NODE_LIMIT =
            90

        private const val EVENT_EVIDENCE_CHAR_LIMIT =
            9000

        private const val EVENT_EVIDENCE_TEXT_LIMIT =
            80

        private const val EVENT_EVIDENCE_PARENT_LIMIT =
            12

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
        var lastEventWindowId:
            Int = -1

        @Volatile
        var lastEventTime:
            Long = 0L
    }
}
