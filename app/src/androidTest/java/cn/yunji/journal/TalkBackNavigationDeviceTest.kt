package cn.yunji.journal

import android.app.UiAutomation
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Structural accessibility check with the real TalkBack service enabled on the dedicated emulator. */
@LargeTest
@SdkSuppress(minSdkVersion = 36, maxSdkVersion = 36)
class TalkBackNavigationDeviceTest {
    @Test fun mainTabsRemainFocusableAndOrderedInAccessibilityTree() {
        assumeTrue(Build.MODEL == "sdk_gphone16k_x86_64")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val services = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assumeTrue(services.contains("com.google.android.marvin.talkback/.TalkBackService"))
        assertTrue(context.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled)
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ActivityScenario.launch(MainActivity::class.java).use {
            val labels = listOf("今天", "产检", "记录", "我的")
            waitNode(automation, "今天") { it.text?.toString() == "今天" && it.isVisibleToUser }
            val found = mutableListOf<String>()
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            queue.add(requireNotNull(automation.rootInActiveWindow))
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val text = node.text?.toString()
                if (node.isVisibleToUser && text in labels && text !in found) found += requireNotNull(text)
                repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
            }
            assertEquals(labels, found)
            for (label in labels) {
                val node = waitNode(automation, label) { it.text?.toString() == label && it.isVisibleToUser }
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                assertEquals(label, focusedLabel(automation))
            }
        }
    }

    private fun waitNode(automation: UiAutomation, label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            automation.rootInActiveWindow?.let { root ->
                val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    if (predicate(node)) return node
                    repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
                }
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("未找到无障碍节点：$label")
    }

    private fun focusedLabel(automation: UiAutomation): String? {
        val node = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        return node?.contentDescription?.toString() ?: node?.text?.toString()
    }

}
