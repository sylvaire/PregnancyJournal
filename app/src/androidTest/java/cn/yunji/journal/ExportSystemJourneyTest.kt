package cn.yunji.journal

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.pdf.PdfRenderer
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room3.Transactor
import cn.yunji.journal.core.security.DeviceKeyStore
import cn.yunji.journal.core.security.FileHandoff
import cn.yunji.journal.data.SecurityStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.zip.ZipInputStream

/** Main → Android DocumentsUI → exported ZIP; synthetic data on the dedicated emulator only. */
@LargeTest
@SdkSuppress(minSdkVersion = 36, maxSdkVersion = 36)
class ExportSystemJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Test fun mainExportsStructuredPackageThroughSystemSavePicker() {
        assumeTrue(Build.VERSION.SDK_INT == 36 && Build.MODEL == "sdk_gphone16k_x86_64")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(!DeviceKeyStore(context).strict() && !SecurityStore(context).lockEnabled())
        val handoff = FileHandoff(context)
        assumeTrue(handoff.read() == null)
        var outputUri: Uri? = null
        var requestId: String? = null
        try {
            ui.waitUntil(10_000) { ui.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("我的").performClick()
            ui.onNodeWithText("导出资料").performScrollTo().performClick()
            ui.onNodeWithTag("export-ack").performScrollTo().performClick()
            ui.onNodeWithTag("export-data").performScrollTo().assertIsEnabled().performClick()
            waitNode("系统保存器") { it.packageName?.toString()?.contains("documentsui") == true }
            val pending = requireNotNull(handoff.read())
            assertEquals("EXPORT_DATA", pending.request.kind)
            requestId = pending.id
            val name = "Yunji-Export-System-${UUID.randomUUID()}.zip"
            val field = waitNode("文件名") { it.className?.toString() == "android.widget.EditText" }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
            }))
            val save = waitNode("保存") {
                it.isEnabled && (it.viewIdResourceName?.endsWith(":id/action_menu_select") == true ||
                    it.text?.toString() in setOf("Save", "SAVE", "保存"))
            }
            val pickerBeforeSave = visibleTexts()
            val saveDescription = "${save.viewIdResourceName}/${save.text}/${save.className}"
            click(save)
            try {
                ui.waitUntil(10_000) { ui.onAllNodesWithText("继续处理所选文件？").fetchSemanticsNodes().isNotEmpty() }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("系统保存后未显示确认；交接=${handoff.read()?.request?.kind}；点击=$saveDescription；保存器=$pickerBeforeSave；界面=${visibleTexts()}", timeout)
            }
            val selected = requireNotNull(handoff.read())
            assertEquals(pending.id, selected.id)
            assertTrue(selected.returned)
            outputUri = Uri.parse(selected.uris.single())
            ui.onNodeWithText("继续").performClick()
            try {
                ui.waitUntil(30_000) {
                    ui.onAllNodesWithText("导出完成并通过文件回读校验", substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("导出未显示成功；已捕获URI=${outputUri!=null}；交接=${handoff.read()?.request?.kind}；界面=${visibleTexts()}", timeout)
            }
            assertNull(handoff.read())
            val target = requireNotNull(outputUri) { "未捕获系统保存器返回的测试文件 URI" }
            context.contentResolver.openInputStream(target)!!.use { source ->
                ZipInputStream(source).use { zip ->
                    val names = mutableSetOf<String>()
                    while (true) names += zip.nextEntry?.name ?: break
                    assertTrue(names.contains("manifest.json"))
                    assertTrue(names.contains("fields.json"))
                }
            }
        } finally {
            outputUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
            requestId?.let { handoff.clearIf(it) }
        }
    }

    @Test fun mainExportsReadableSummaryPdfThroughSystemSavePicker() {
        assumeTrue(Build.VERSION.SDK_INT == 36 && Build.MODEL == "sdk_gphone16k_x86_64")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(!DeviceKeyStore(context).strict() && !SecurityStore(context).lockEnabled())
        val handoff = FileHandoff(context)
        assumeTrue(handoff.read() == null)
        val resources = ui.activity.runtime.resources()
        var pregnancyId: String? = null
        var outputUri: Uri? = null
        var requestId: String? = null
        try {
            pregnancyId = runBlocking {
                resources.repository.savePregnancy(null, "系统摘要 PDF 合成档案", null, null, "", "")
            }
            ui.waitUntil(10_000) { ui.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("我的").performClick()
            ui.onNodeWithText("导出资料").performScrollTo().performClick()
            ui.onNodeWithTag("export-ack").performScrollTo().performClick()
            ui.onNodeWithTag("export-summary").performScrollTo().assertIsEnabled().performClick()
            waitNode("系统保存器") { it.packageName?.toString()?.contains("documentsui") == true }
            val pending = requireNotNull(handoff.read())
            assertEquals("SUMMARY_PDF", pending.request.kind)
            requestId = pending.id
            val name = "Yunji-Summary-System-${UUID.randomUUID()}.pdf"
            val field = waitNode("文件名") { it.className?.toString() == "android.widget.EditText" }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
            }))
            val save = waitNode("保存") {
                it.isEnabled && (it.viewIdResourceName?.endsWith(":id/action_menu_select") == true ||
                    it.text?.toString() in setOf("Save", "SAVE", "保存"))
            }
            click(save)
            ui.waitUntil(10_000) { ui.onAllNodesWithText("继续处理所选文件？").fetchSemanticsNodes().isNotEmpty() }
            val selected = requireNotNull(handoff.read())
            assertEquals(pending.id, selected.id)
            assertTrue(selected.returned)
            outputUri = Uri.parse(selected.uris.single())
            ui.onNodeWithText("继续").performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText("知道了").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("知道了").performClick()
            try {
                ui.waitUntil(30_000) {
                    ui.onAllNodesWithText("就诊摘要已生成并通过文件回读校验", substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("PDF 导出未显示成功；交接=${handoff.read()?.request?.kind}；界面=${visibleTexts()}", timeout)
            }
            assertNull(handoff.read())
            val target = requireNotNull(outputUri)
            context.contentResolver.openInputStream(target)!!.use { source ->
                assertArrayEquals("%PDF-".toByteArray(Charsets.US_ASCII), ByteArray(5).also {
                    assertEquals(5, source.read(it))
                })
            }
            context.contentResolver.openFileDescriptor(target, "r")!!.use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> assertTrue(renderer.pageCount >= 1) }
            }
        } finally {
            outputUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
            requestId?.let { handoff.clearIf(it) }
            pregnancyId?.let { id ->
                runBlocking {
                    resources.database.useConnection(isReadOnly = false) { connection ->
                        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                            connection.usePrepared("DELETE FROM pregnancy_date_revisions WHERE pregnancyId=?") {
                                it.bindText(1, id); it.step()
                            }
                            connection.usePrepared("DELETE FROM change_log WHERE entityType='pregnancy' AND entityId=?") {
                                it.bindText(1, id); it.step()
                            }
                            connection.usePrepared("DELETE FROM pregnancies WHERE id=?") {
                                it.bindText(1, id); it.step()
                            }
                        }
                    }
                    assertNull("合成孕期未清理", resources.repository.pregnancy(id))
                }
            }
        }
    }

    private fun waitNode(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < end) {
            val root = automation.rootInActiveWindow
            if (root != null) {
                val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
                nodes.add(root)
                while (nodes.isNotEmpty()) {
                    val node = nodes.removeFirst()
                    if (node.isVisibleToUser && predicate(node)) return node
                    repeat(node.childCount) { node.getChild(it)?.let(nodes::add) }
                }
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("未找到：$label")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val target = current ?: return@repeat
            if (target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = target.parent
        }
        throw AssertionError("系统保存按钮无法点击")
    }

    private fun visibleTexts(): List<String> {
        val root = automation.rootInActiveWindow ?: return listOf("无活动窗口")
        val result = mutableListOf("package=${root.packageName}")
        val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty() && result.size < 100) {
            val node = nodes.removeFirst()
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(result::add)
            repeat(node.childCount) { node.getChild(it)?.let(nodes::add) }
        }
        return result
    }
}
