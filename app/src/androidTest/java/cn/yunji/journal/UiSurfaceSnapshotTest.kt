package cn.yunji.journal

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * 2026-10-24 UI 证据缺口：对话框 / 日期选择 / Snackbar 等“表面组件”的真实 App 状态截图。
 *
 * 现有 22 张 UiSnapshotCoverageTest 截图覆盖的是页面主体，不含 AlertDialog / DatePicker / Snackbar /
 * DropdownMenu 这些独立窗口或临时表面；本测试在真实 App 导航里触发它们并保存整屏截图：
 *
 *   1. `surface-alert-dialog.png`：产检资料 → 删除 → “将资料移入回收站？” AlertDialog（Compose Dialog 窗口）。
 *   2. `surface-date-picker.png`：首页 → 新建产检预约 → 预约日期 → M3 DatePickerDialog。
 *   3. `surface-snackbar.png`：产检详情 → 删除产检记录 → 底部 Snackbar「已移入全局回收站 · 撤销」。
 *   4. `surface-dropdown-menu.png`（附加）：产检资料 → “全部分类” DropdownMenu。
 *
 * Dialog / Popup 是独立窗口，Compose `captureToImage()` 拿不到其像素，因此统一用
 * `Instrumentation.uiAutomation.takeScreenshot()` 整屏截图（测试宿主 Activity 未设 FLAG_SECURE）。
 * 容器色测量在宿主机用 Pillow 完成（docs/verification/2026-10-24-ui-evidence-gaps/measure_surfaces.py）：
 * AlertDialog/DatePicker 容器应回落到设计色板（`#FFFFFF`），Snackbar 使用 inverseSurface（`#223C32`），
 * 且不得出现 M3 基线淡紫灰（`#ECE6F0` / `#E6E0E9` 等）。
 */
class UiSurfaceSnapshotTest : PageMatrixBaseTest() {

    @Test
    fun surface01DeleteConfirmAlertDialog() {
        val visitId = seedShortVisit(items = "常规产检")
        val documentId = enqueueDocument(visitId, createPngSource())
        runBlocking { model.documents.process(documentId) }

        launchApp()
        openVisitDocuments("常规产检")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("删除").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("删除").onFirst().performScrollTo().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("将资料移入回收站？").fetchSemanticsNodes().isNotEmpty() }

        captureScreen("surface-alert-dialog")
    }

    @Test
    fun surface02DatePickerDialog() {
        launchApp()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("新建产检预约"))
        ui.onNodeWithText("新建产检预约").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("预约日期").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("预约日期").performScrollTo().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("确定").fetchSemanticsNodes().isNotEmpty() }

        captureScreen("surface-date-picker")
    }

    @Test
    fun surface03UndoSnackbar() {
        seedShortVisit(items = "常规产检")
        launchApp()
        openTab("产检", "visits-list")
        ui.onNodeWithTag("visits-list").performScrollToNode(hasText("常规产检", substring = true))
        ui.onNodeWithText("常规产检", substring = true).performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("visit-detail-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("visit-detail-list").performScrollToNode(hasText("删除产检记录"))
        ui.onNodeWithText("删除产检记录").performClick(); settle()
        ui.onNodeWithTag("confirm-root-deletion").assertIsEnabled().performClick(); settle()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("已移入全局回收站").fetchSemanticsNodes().isNotEmpty() }

        // Snackbar 在 JournalApp 的 Compose 根内（非独立窗口），root capture 可完整取到。
        captureRoot("surface-snackbar")
    }

    /** 附加：DropdownMenu（Popup 窗口）。 */
    @Test
    fun surface04DocumentCategoryDropdownMenu() {
        val visitId = seedShortVisit(items = "常规产检")
        val documentId = enqueueDocument(visitId, createPngSource())
        runBlocking { model.documents.process(documentId) }

        launchApp()
        openVisitDocuments("常规产检")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("全部分类").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("全部分类").performScrollTo().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("检验").fetchSemanticsNodes().isNotEmpty() }

        captureScreen("surface-dropdown-menu")
    }

    // ---------- 截图 ----------

    /** 独立窗口（Dialog/Popup）：UiAutomation 整屏截图（含系统状态栏与 scrim 遮罩）。 */
    private fun captureScreen(name: String) {
        settle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(700) // 等 Dialog/Popup 的入场动画结束，避免截到半透明中间帧。
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UiAutomation 截图返回 null" }
        saveBitmap(bitmap, name)
        bitmap.recycle()
    }

    /** Compose 根内表面（Snackbar）：root capture。 */
    private fun captureRoot(name: String) {
        settle()
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        saveBitmap(bitmap, name)
        bitmap.recycle()
    }

    private fun saveBitmap(bitmap: Bitmap, name: String) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "verification/evidence-gaps").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "saved $name ${bitmap.width}x${bitmap.height}")
    }

    private companion object {
        const val TAG = "UiSurfaceSnapshot"
    }
}
