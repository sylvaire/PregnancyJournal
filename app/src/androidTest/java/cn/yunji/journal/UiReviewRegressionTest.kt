package cn.yunji.journal

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.yunji.journal.core.designsystem.*
import cn.yunji.journal.core.database.VisitEntity
import cn.yunji.journal.feature.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 本轮体验复核：窄宽度、200%字体，以及点击后的实际可达性。仅使用隔离夹具。 */
class UiReviewRegressionTest : PageMatrixBaseTest() {
    @Test fun homeAppointmentLabelsAndMissingDateActionMatchTheirDestinations() {
        val active = runBlocking { db.journalDao().pregnancy(pregnancyId) }!!
        val today = LocalDate.now()
        val visit = VisitEntity("synthetic-appointment", pregnancyId, today.toString(), zoneId = "Asia/Shanghai",
            createdAt = 1, updatedAt = 1)
        var state by mutableStateOf(JournalState(pregnancies = listOf(active), activeId = pregnancyId))
        var editDates = 0
        var openedVisit: String? = null
        largeType { HomeScreen(state, {}, {}, { openedVisit = it }, onEditPregnancy = { editDates++ }) }
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("补充日期"))
        ui.onNodeWithText("补充日期").assertIsDisplayed().performClick()
        assertEquals("日期补充应直接进入日期编辑", 1, editDates)
        listOf(-1L to "待处理的产检", 0L to "今天的产检", 1L to "下一次产检").forEach { (offset, label) ->
            ui.runOnIdle {
                openedVisit = null
                state = state.copy(visits = listOf(visit.copy(scheduledDate = today.plusDays(offset).toString())))
            }
            ui.onNodeWithTag("home-list").performScrollToNode(hasText(label, substring = true))
            ui.onNodeWithText(label, substring = true).assertIsDisplayed().performClick()
            assertEquals("预约文案应保留原详情入口", visit.id, openedVisit)
            capture("home-appointment-$offset")
        }
        listOf("检查资料", "健康指标", "写备注").forEach { label ->
            ui.onNodeWithTag("home-list").performScrollToNode(hasText(label))
            ui.onNodeWithText(label).assertIsDisplayed().assertHasClickAction()
            assertSingleLine(label)
        }
        capture("home-quick-actions-large")
    }

    @Test fun longDetailKeepsLabelAndFullValueReadable() {
        val hospital = "市妇幼保健院东院区产前诊断与遗传咨询中心".repeat(3)
        largeType { Column(Modifier.width(280.dp)) { JournalKeyValue("医院", hospital) } }
        capture("key-value-long")
        ui.onNodeWithText("医院").assertIsDisplayed()
        ui.onNodeWithText(hospital).assertIsDisplayed()
        assertNoTruncation(hasText("医院") or hasText(hospital), "长医院名称与标签")
    }

    @Test fun fullScreenTitleAndActionsRemainReadable() {
        largeType {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                JournalFullScreenHeader("检查资料原件预览", "超声检查报告单", onBack = {}) {
                    JournalTextAction("导出", onClick = {})
                    JournalTextAction("替换", onClick = {})
                    JournalTextAction("删除", danger = true, onClick = {})
                }
            }
        }
        capture("preview-header-large")
        listOf("检查资料原件预览", "导出", "替换", "删除").forEach {
            ui.onNodeWithText(it).assertIsDisplayed()
            assertNoTruncation(hasText(it), "预览顶栏 $it")
        }
    }

    @Test fun profileSwitchHasAFullTouchTarget() {
        largeType { PageHeader("今天，慢慢来", "切换孕期", {}) }
        ui.onNodeWithText("切换孕期").assertHasClickAction().assertHeightIsAtLeast(48.dp)
    }

    @Test fun calendarDatesDescribeDateAndSelection() {
        val active = runBlocking { db.journalDao().pregnancy(pregnancyId) }!!
        val date = LocalDate.now()
        largeType {
            Column(Modifier.width(360.dp)) { VisitsScreen(JournalState(pregnancies = listOf(active), activeId = pregnancyId), {}, {}) }
        }
        ui.onNodeWithText("日历").performClick()
        assertSingleLine("切换月份")
        capture("calendar-large")
        val label = date.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA))
        ui.onNodeWithTag("visits-list").performScrollToNode(hasContentDescription(label))
        val day = ui.onNodeWithContentDescription(label)
        day.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        day.assertIsSelected()
        day.performClick().assertIsNotSelected()
    }

    @Test fun intakeActionsFitWithLargeType() {
        seedMedicationPlan()
        var manualCalls=0
        largeType { JournalPage("服药记录", back = {}) { IntakeScreen(pregnancyId, model, {}, { occurrence, record, _, _ ->
            if(occurrence==null && record==null)manualCalls++
        }) } }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("补记服药").fetchSemanticsNodes().isNotEmpty() }
        capture("intakes-large")
        listOf("今天", "历史", "方案", "补记服药").forEach {
            ui.onNodeWithText(it).assertIsDisplayed()
            assertNoTruncation(hasText(it), "服药入口 $it")
            assertSingleLine(it)
        }
        ui.onAllNodesWithText("补记服药").assertCountEquals(1)
        ui.onNodeWithText("补记服药").performClick()
        ui.onNodeWithText("历史").performClick()
        ui.onAllNodesWithText("补记服药").assertCountEquals(1)
        ui.onNodeWithText("补记服药").assertIsDisplayed().performClick()
        ui.runOnIdle {assertEquals(2,manualCalls)}
    }

    @Test fun medicationActionsFitWithLargeType() {
        seedMedicationPlan()
        largeType { JournalPage("用药方案", back = {}) { MedicationListScreen(pregnancyId, model, {}, {}, {}, {}) } }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("修改方案").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("medication-list").performScrollToNode(hasText("修改方案"))
        listOf("修改方案", "历史版本", "暂停").forEach {
            ui.onNodeWithText(it).performScrollTo().assertIsDisplayed()
            assertNoTruncation(hasText(it), "方案操作 $it")
            assertSingleLine(it)
        }
        capture("medication-actions-large")
    }

    @Test fun trashTabsKeepWordsTogetherWithLargeType() {
        largeType { JournalPage("回收站", back = {}) { TrashScreen(model) } }
        listOf("删除的项目（0）", "保留的资料（0）").forEach {
            ui.onNodeWithText(it).assertIsDisplayed()
            assertSingleLine(it)
        }
        capture("trash-tabs-large")
    }

    @Test fun measurementFailureDoesNotPretendDataIsEmpty() {
        val missing = runBlocking { db.journalDao().pregnancy(pregnancyId) }!!.copy(id = java.util.UUID.randomUUID().toString())
        largeType { JournalPage("健康指标", back = {}) { MeasurementScreen(missing, model, {}) } }
        val error = "暂时无法读取指标，请返回后重试"
        ui.waitUntil(5_000) { ui.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("还没有测量记录").assertDoesNotExist()
        ui.onNodeWithText("分组趋势").performClick()
        ui.onNodeWithText("此范围没有测量数据。").assertDoesNotExist()
        ui.onNodeWithText(error).assertExists()
        capture("measurement-error-large")
    }

    private fun assertSingleLine(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        ui.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("短按钮文案应保持完整一行：$text", 1, layouts.single().lineCount)
    }

    private fun largeType(content: @Composable () -> Unit) {
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                PregnancyTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) { content() } } }
            }
        }
        settle()
    }

    private fun capture(name: String) {
        settle()
        val dir = File(context.getExternalFilesDir(null), "verification/ui-review-regressions").apply { mkdirs() }
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
