package kg.autonomous.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Позже здесь подключим Observe → Verify.
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false

        val nodes = root.findAccessibilityNodeInfosByText(text)

        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node

            while (current != null) {
                if (current.isClickable) {
                    return current.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )
                }
                current = current.parent
            }
        }

        return false
    }

    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    100
                )
            )
            .build()

        return dispatchGesture(gesture, null, null)
    }
}
