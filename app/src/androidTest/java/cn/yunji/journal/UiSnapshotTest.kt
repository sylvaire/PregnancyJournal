package cn.yunji.journal

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 把当前实现的整屏界面真实渲染出来，供 Figma 同步使用。
 *
 * 设备为 1080×2400 / 420dpi，等价设计尺寸 412×915（与 Figma 画板一致），
 * 产物写到应用外部 files 的 `verification/figma-sync/`，由宿主 adb pull 取回。
 */
class UiSnapshotTest : PageMatrixBaseTest() {

    @Before
    fun seedSnapshotData() {
        runBlocking {
            seedJournalEntry()
            seedShortVisit(items = "常规产检")
            seedMedicationPlan()
        }
    }

    @Test
    fun snap01Today() {
        launchApp()
        capture("01-today")
    }

    @Test
    fun homeFigmaSectionsOpenTheirDestinations() {
        launchApp()
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("今天的安排"))
        ui.onNodeWithText("今天的安排").assertExists()
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("记录与资料"))
        ui.onNodeWithText("记录与资料").assertExists()
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("检查资料"))
        ui.onNodeWithText("检查资料").performClick()
        ui.waitUntil(10_000) {ui.onAllNodesWithTag("all-documents-list").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("navigate-up").performClick()
        settle()
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("健康指标"))
        ui.onNodeWithText("健康指标").performClick()
        ui.waitUntil(10_000) {ui.onAllNodesWithTag("measurement-list").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("navigate-up").performClick()
        settle()
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("写备注"))
        capture("home-quick-actions")
        ui.onNodeWithText("写备注").performClick()
        ui.waitUntil(10_000) {ui.onAllNodesWithText("写日常记录").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("navigate-up").performClick()
        settle()
        ui.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-profile-switch"))
        ui.onNodeWithTag("home-profile-switch").performClick()
        ui.waitUntil(10_000) {ui.onAllNodesWithTag("profiles-list").fetchSemanticsNodes().isNotEmpty()}
    }

    @Test
    fun snap02Visits() {
        launchApp()
        openTab("产检", "visits-list")
        capture("02-visits")
        ui.onNodeWithTag("visits-list").performScrollToNode(hasText("常规产检", substring = true))
        capture("02-visits-card")
    }

    @Test
    fun snap03RecordsHub() {
        launchApp()
        openTab("记录", "records-hub-list")
        capture("03-records-hub")
    }

    @Test
    fun snap04Profiles() {
        launchApp()
        openTab("我的", "profiles-root-list")
        capture("04-profiles")
    }

    @Test
    fun snap04bPregnancyManagement() {
        launchApp()
        openProfileRow("管理孕期")
        capture("04b-pregnancy-management")
    }

    @Test
    fun snap05Medications() {
        launchApp()
        openProfileRow("用药方案")
        capture("05-medications")
        ui.onNodeWithTag("medication-list").performScrollToNode(hasText("复合维生素片（孕妇型）"))
        capture("05-medications-card")
        ui.onNodeWithTag("medication-list").performScrollToNode(hasText("每次 1 片"))
        capture("05-medications-values")
    }

    @Test
    fun snap06Intakes() {
        launchApp()
        openProfileRow("服药记录")
        capture("06-intakes")
    }

    @Test
    fun snap07Measurements() {
        launchApp()
        openProfileRow("健康指标")
        capture("07-measurements")
    }

    @Test
    fun snap08VisitDocuments() {
        val visit = runBlocking { db.journalDao().allVisits().first() }
        val source = createPngSource()
        val documentId = enqueueDocument(visit.id, source)
        runBlocking {
            model.documents.process(documentId)
            model.documents.updateMetadata(
                documentId, "超声检查报告单", "OTHER",
                java.time.LocalDate.now().toString(), "市妇幼保健院", "孕 24 周第三次超声",
            )
        }
        launchApp()
        openVisitDocuments("常规产检")
        settle()
        capture("08-visit-documents")
    }

    @Test
    fun snap09Recovery() {
        launchApp()
        openProfileRow("本地备份")
        capture("09-recovery")
    }

    private fun capture(name: String) {
        settle()
        val dir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "verification/figma-sync",
        ).apply { mkdirs() }
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        Log.i("UiSnapshot", "$name ${bitmap.width}x${bitmap.height}")
        bitmap.recycle()
    }
}
