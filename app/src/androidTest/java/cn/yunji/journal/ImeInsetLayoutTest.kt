package cn.yunji.journal

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bug #3（键盘 IME 双内边距）回归断言。
 *
 * 背景：2026-10-24 UI 修复过程中发现 `JournalPage` 的内容区同时受
 * `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)`（safeDrawing 已包含 IME）与内容 `Box.imePadding()`
 * 影响，键盘打开时视口被压缩两次；实测 `document-list` 高度只有约 **106.7 dp**（见
 * docs/verification/2026-10-24-ui-header-fix/README.md §5）。产品侧已收敛为只保留 Scaffold inset（单一口径，
 * `app/src/main` 的改动不由本测试任务承担）。
 *
 * 本测试在真实 App 导航（我的资料 → 产检 → 产检详情 → 产检资料）里聚焦搜索框、等键盘弹出，断言：
 *   1) 键盘弹出后 `document-list`（LazyColumn）高度 >= 300 dp（修复前约 106.7 dp；单内边距时约 438 dp）；
 *   2) 键盘收起后高度恢复到无键盘时的水平。
 *
 * 设备：emulator-5556（API36 / 1080x2400 / 420dpi）。断言值按 dp 计算（px / displayMetrics.density），与分辨率无关。
 * 夹具与导航复用 [PageMatrixBaseTest]（独立加密数据库 + 真实 App Compose 树）。
 */
class ImeInsetLayoutTest : PageMatrixBaseTest() {

    @Test
    fun documentListViewportKeepsUsableHeightWhenImeShown() {
        // 一份 READY 的资料让“产检资料”页出现搜索框（hasActive == true）。
        val visitId = seedShortVisit(items = "常规产检")
        val documentId = enqueueDocument(visitId, createPngSource())
        runBlocking { model.documents.process(documentId) }

        launchApp()
        openVisitDocuments("常规产检")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("搜索名称、备注或医院").fetchSemanticsNodes().isNotEmpty() }

        val density = context.resources.displayMetrics.density
        fun listHeightDp(): Float =
            ui.onNodeWithTag("document-list").fetchSemanticsNode().boundsInRoot.height / density

        // document-list 未设 fillMaxSize：内容短于视口时按内容高度测量，内容超出视口时才被裁剪。
        // N22 收紧紧凑行后空态内容约 540 dp（历史基线 755 dp 是当时内容更多所致），
        // 因此这里只要求“可用”，不能用“接近整页”的绝对阈值。
        val heightWithoutIme = listHeightDp()
        assertTrue(
            "无键盘时 document-list 高度应可用（>=300dp），实测 %.2f dp".format(heightWithoutIme),
            heightWithoutIme >= 300f,
        )

        ui.onNodeWithText("搜索名称、备注或医院").performScrollTo()
        ui.onNodeWithTag("document-search").performClick()

        // safeDrawing 的 bottom inset 在键盘弹出后变为 IME 高度：只应压缩一次。
        // 用 IME 实际可见作为等待条件，避免依赖固定压缩幅度（短内容时压缩量天然更小）。
        waitForImeVisible(true)
        val heightWithIme = listHeightDp()
        captureRoot("ime-open-document-list")

        assertTrue(
            "键盘弹出后 document-list 高度必须 >= 300dp（Bug #3 修复前实测约 106.7dp；双内边距复现时应约 106~110dp），实测 %.2f dp"
                .format(heightWithIme),
            heightWithIme >= 300f,
        )

        // 键盘收起后视口恢复（允许 5 dp 的取整/动画误差）。
        hideKeyboard()
        waitForImeVisible(false)
        val heightRestored = listHeightDp()
        captureRoot("ime-hidden-document-list")
        assertTrue(
            "键盘收起后 document-list 高度应恢复（无键盘 %.2f dp，恢复后 %.2f dp）".format(heightWithoutIme, heightRestored),
            heightRestored >= heightWithoutIme - 5f,
        )

        writeMeasurements(heightWithoutIme, heightWithIme, heightRestored)
    }

    /** 等待 IME 窗口 inset 状态变化（不依赖固定 dp 压缩量）。 */
    private fun waitForImeVisible(expected: Boolean) {
        ui.waitUntil(15_000) {
            var visible = false
            ui.runOnUiThread {
                val insets = ui.activity.window.decorView.rootWindowInsets
                visible = insets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            }
            visible == expected
        }
        settle()
    }

    private fun writeMeasurements(withoutIme: Float, withIme: Float, restored: Float) {
        val dir = evidenceDir()
        File(dir, "ime-viewport-heights.txt").writeText(
            buildString {
                appendLine("device=emulator-5556 (1080x2400 / 420dpi = 411.43x914.29 dp, density=${context.resources.displayMetrics.density})")
                appendLine("page=产检资料（document-list LazyColumn）")
                appendLine("height_without_ime_dp=%.2f".format(withoutIme))
                appendLine("height_with_ime_dp=%.2f".format(withIme))
                appendLine("height_after_hide_ime_dp=%.2f".format(restored))
                appendLine("bug3_baseline_before_fix_dp=106.7")
                appendLine("assertion=height_with_ime_dp >= 300")
            }
        )
        Log.i(TAG, "ime viewport heights: without=$withoutIme with=$withIme restored=$restored")
    }

    private fun captureRoot(name: String) {
        settle()
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        saveBitmap(bitmap, name)
        bitmap.recycle()
    }

    private fun saveBitmap(bitmap: Bitmap, name: String) {
        val out = File(evidenceDir(), "$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "saved $name ${bitmap.width}x${bitmap.height}")
    }

    private fun evidenceDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "verification/evidence-gaps").apply { mkdirs() }

    private companion object {
        const val TAG = "ImeInsetLayout"
    }
}
