package cn.yunji.journal

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunji.journal.core.database.VisitEntity
import cn.yunji.journal.core.designsystem.PregnancyTheme
import cn.yunji.journal.feature.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** 仅用合成预约与回调，不访问真实资料或创建通知。 */
class VisitReminderCardTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun configuredDateAndTimeStayCompleteAndActionsRespectBusyState() {
        val date = LocalDate.now().plusYears(1).toString()
        var busy by mutableStateOf(false)
        var edits = 0
        var clears = 0
        ui.setContent { PregnancyTheme { JournalPage("产检提醒") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                VisitReminderCard(date, "09:30", busy, { edits++ }, { clears++ })
            }
        } } }
        for(text in listOf("已设置", "提醒日期", displayDate(date), "提醒时间", "09:30", "修改提醒", "关闭提醒")) {
            ui.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            ui.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
                val layouts = mutableListOf<TextLayoutResult>()
                read(layouts)
                assertTrue(layouts.isNotEmpty())
                for(layout in layouts) {
                    assertFalse("不得省略提醒内容：$text", layout.multiParagraph.didExceedMaxLines)
                    for(offset in text.indices) {
                        val glyph = layout.getBoundingBox(offset)
                        assertTrue("提醒文字不可越界：$text", glyph.left >= -1f && glyph.right <= layout.size.width + 1f)
                    }
                }
            }
        }
        capture("configured")
        ui.runOnIdle { busy = true }
        ui.onNodeWithTag("visit-reminder-set").assertIsNotEnabled()
        ui.onNodeWithTag("clear-visit-reminder").assertIsNotEnabled()
        ui.runOnIdle { busy = false }
        ui.onNodeWithTag("visit-reminder-set").performScrollTo().performClick()
        ui.onNodeWithTag("clear-visit-reminder").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(1, edits); assertEquals(1, clears) }
    }

    @Test fun emptyCardHasOneSetupActionAndNoExtraExplanation() {
        var edits = 0
        ui.setContent { PregnancyTheme { JournalPage("产检提醒") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                VisitReminderCard(null, null, false, { edits++ }, {})
            }
        } } }
        ui.onNodeWithText("未设置").assertExists()
        ui.onNodeWithTag("clear-visit-reminder").assertDoesNotExist()
        ui.onNodeWithText("提醒时间按本机当前时区", substring = true).assertDoesNotExist()
        capture("empty")
        ui.onNodeWithTag("visit-reminder-set").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(1, edits) }
    }

    @Test fun detailDialogKeepsPolicyDefaultTimeAndClearBehavior() {
        if(Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ui.activity.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val scheduled = LocalDate.now().plusDays(7)
        var visit by mutableStateOf(VisitEntity("synthetic-reminder", "synthetic-pregnancy", scheduled.toString(),
            zoneId = "Asia/Shanghai", items = "合成产检", createdAt = 1, updatedAt = 1))
        ui.setContent { PregnancyTheme { JournalPage("产检详情") {
            VisitDetail(visit, false, {}, {}, {}, {}, onSetReminder = { date, time ->
                visit = visit.copy(reminderDate = date, reminderTime = time)
            }, onClearReminder = { visit = visit.copy(reminderDate = null, reminderTime = null) })
        } } }
        ui.onNodeWithTag("visit-detail-list").performScrollToNode(hasTestTag("visit-reminder-set"))
        ui.onNodeWithText("提醒时间按本机当前时区", substring = true).assertDoesNotExist()
        ui.onNodeWithTag("visit-reminder-set").performClick()
        ui.onNodeWithText("提醒时间", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("提醒时间", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
            val layouts = mutableListOf<TextLayoutResult>()
            read(layouts)
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { assertEquals("浮动标签应保持单行，避免侵占上一字段", 1, it.lineCount) }
        }
        ui.onNodeWithText("24小时制").assertExists()
        ui.onNodeWithText("提醒时间按本机当前时区", substring = true).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("无具体预约时间时", substring = true).performScrollTo().assertIsDisplayed()
        val dialog = File(ui.activity.getExternalFilesDir(null), "verification/visit-reminder/dialog.png")
        dialog.parentFile!!.mkdirs()
        dialog.outputStream().use { ui.onNodeWithTag("visit-reminder-dialog").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithTag("confirm-visit-reminder").performClick()
        ui.runOnIdle {
            assertEquals(scheduled.minusDays(1).toString(), visit.reminderDate)
            assertEquals("09:00", visit.reminderTime)
        }
        ui.onNodeWithTag("visit-detail-list").performScrollToNode(hasTestTag("clear-visit-reminder"))
        ui.onNodeWithTag("clear-visit-reminder").performClick()
        ui.runOnIdle { assertNull(visit.reminderDate); assertNull(visit.reminderTime) }
        ui.onNodeWithTag("visit-reminder-set").assertTextContains("设置提醒")
    }

    private fun capture(name: String) {
        ui.onNodeWithText("提醒").performScrollTo()
        ui.waitForIdle()
        val file = File(ui.activity.getExternalFilesDir(null), "verification/visit-reminder/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
