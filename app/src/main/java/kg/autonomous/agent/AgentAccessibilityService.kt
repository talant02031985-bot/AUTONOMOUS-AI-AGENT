package kg.autonomous.agent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AgentAccessibilityService :
    AccessibilityService() {

    companion object {
        var instance:
            AgentAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(
        intent: android.content.Intent?
    ): Boolean {
        if (instance === this) {
            instance = null
        }

        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }

    fun pressBack(): Boolean {
        return performGlobalAction(
            GLOBAL_ACTION_BACK
        )
    }

    fun pressHome(): Boolean {
        return performGlobalAction(
            GLOBAL_ACTION_HOME
        )
    }

    fun clickByText(
        target: String
    ): Boolean {

        val root =
            rootInActiveWindow
                ?: return false

        val candidates =
            root.findAccessibilityNodeInfosByText(
                target
            )

        for (node in candidates) {

            val clickable =
                findClickableParent(node)

            if (
                clickable != null &&
                clickable.performAction(
                    AccessibilityNodeInfo
                        .ACTION_CLICK
                )
            ) {
                return true
            }
        }

        return false
    }

    private fun findClickableParent(
        start:
            AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {

        var node = start

        var depth = 0

        while (
            node != null &&
            depth < 8
        ) {

            if (
                node.isVisibleToUser &&
                node.isClickable &&
                node.isEnabled
            ) {
                return node
            }

            node = node.parent
            depth++
        }

        return null
    }
}
