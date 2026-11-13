package cn.yunji.journal

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Task-6: minimal accessibility based UI probe.
 *
 * The instrumented test APK has no UiAutomator dependency and the task forbids adding one, so this
 * helper drives the real app through the platform [UiAutomation] that `androidx.test` already owns:
 * it reads the active window's node tree for texts and dispatches global BACK/HOME actions. It only
 * observes text and ever performs ACTION_CLICK on the node it just found, never on hidden content.
 */
internal object UiProbe {
    private val io: UiAutomation by lazy {
        InstrumentationRegistry.getInstrumentation().uiAutomation.also { automation ->
            runCatching {
                val info = automation.serviceInfo ?: AccessibilityServiceInfo()
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                automation.serviceInfo = info
            }
        }
    }

    fun waitForText(text: String, timeoutMs: Long = 20_000): Boolean = waitForAnyText(listOf(text), timeoutMs)

    fun waitForAnyText(texts: Collection<String>, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (anyVisible(texts)) return true
            Thread.sleep(250)
        }
        return false
    }

    fun anyVisible(texts: Collection<String>): Boolean {
        val root = activeRoot() ?: return false
        return try {
            contains(root, texts, 0)
        } finally {
            root.recycle()
        }
    }

    fun clickText(text: String, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val node = findText(activeRoot(), text, 0)
            if (node != null) {
                // The text node itself may not be clickable; Compose puts the click action on an
                // ancestor, so climb until a clickable node is found before dispatching ACTION_CLICK.
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                val clicked = (target ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                if (clicked) return true
            }
            Thread.sleep(250)
        }
        return false
    }

    fun pressHome() {
        io.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun pressBack() {
        io.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun activeRoot(): AccessibilityNodeInfo? = runCatching {
        io.rootInActiveWindow ?: io.windows.firstOrNull { it.isActive }?.root
    }.getOrNull()

    private fun contains(node: AccessibilityNodeInfo, texts: Collection<String>, depth: Int): Boolean {
        if (depth > 60) return false
        val label = node.text?.toString() ?: node.contentDescription?.toString()
        if (label != null && label in texts) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (contains(child, texts, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun findText(node: AccessibilityNodeInfo?, text: String, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 60) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findText(child, text, depth + 1)
            if (found != null && found !== child) child.recycle()
            if (found != null) return found
            child.recycle()
        }
        return null
    }
}
