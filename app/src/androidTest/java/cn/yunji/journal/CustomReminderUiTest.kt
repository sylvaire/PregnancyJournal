package cn.yunji.journal

import android.view.View
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.compose.ui.test.*
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Uses the isolated encrypted fixture from PageMatrixBaseTest; never touches the installed journal. */
class CustomReminderUiTest : PageMatrixBaseTest() {
    @Test fun invalidTitleAndPastSingleReminderStayInEditor() {
        launchApp();openProfileRow("提醒设置")
        ui.onNodeWithTag("custom-reminder-add").performScrollTo().performClick()
        ui.onNodeWithTag("custom-reminder-save").performClick()
        ui.onNodeWithText("请输入提醒标题").assertExists()
        assertTrue(runBlocking { db.journalDao().allCustomReminders().isEmpty() })
        ui.onNodeWithTag("custom-reminder-title").performTextInput("过期测试")
        ui.onNodeWithText("提醒日期",substring=true).performClick()
        onView(isAssignableFrom(DatePicker::class.java)).perform(changeDate(LocalDate.now().minusDays(1)))
        onView(withId(android.R.id.button1)).perform(click())
        ui.onNodeWithTag("custom-reminder-save").performClick()
        ui.onNodeWithText("单次提醒时间必须在未来").assertExists()
        assertTrue(runBlocking { db.journalDao().allCustomReminders().isEmpty() })
    }

    @Test fun dateTimeAndWeeklyEndPickersPersistChosenValues() {
        val start=LocalDate.now().plusDays(2)
        val end=start.plusDays(7)
        launchApp();openProfileRow("提醒设置")
        ui.onNodeWithTag("custom-reminder-add").performScrollTo().performClick()
        ui.onNodeWithTag("custom-reminder-title").performTextInput("选择器合成提醒")
        closeSoftKeyboard()
        ui.onNodeWithText("提醒日期",substring=true).performScrollTo().performClick()
        onView(isAssignableFrom(DatePicker::class.java)).perform(changeDate(start))
        onView(withId(android.R.id.button1)).perform(click())
        ui.onNode(hasText("提醒时间",substring=true) and hasClickAction()).performScrollTo().performClick()
        onView(isAssignableFrom(TimePicker::class.java)).perform(changeTime(10,15))
        onView(isAssignableFrom(TimePicker::class.java)).check { view, error ->
            if(error!=null)throw error
            assertEquals(10,(view as TimePicker).hour)
            assertEquals(15,view.minute)
        }
        onView(withId(android.R.id.button1)).perform(click())
        ui.waitUntil(5_000) { ui.onAllNodes(hasText("10:15", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("custom-reminder-repeat-WEEKLY").performScrollTo().performClick()
        ui.onNodeWithText("设置结束日期").performScrollTo().performClick()
        onView(isAssignableFrom(DatePicker::class.java)).perform(changeDate(end))
        onView(withId(android.R.id.button1)).perform(click())
        ui.onNodeWithTag("custom-reminder-save").performClick()
        ui.waitUntil(10_000) { runBlocking { db.journalDao().allCustomReminders().size==1 } }
        val row=runBlocking { db.journalDao().allCustomReminders().single() }
        assertEquals(start.toString(),row.startDate)
        assertEquals("10:15",row.localTime)
        assertEquals("WEEKLY",row.repeat)
        assertEquals(end.toString(),row.endDate)
    }

    private fun changeDate(date:LocalDate)=object:ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(DatePicker::class.java)
        override fun getDescription()="set date to $date"
        override fun perform(controller:UiController,view:View) {
            (view as DatePicker).updateDate(date.year,date.monthValue-1,date.dayOfMonth)
            controller.loopMainThreadUntilIdle()
        }
    }

    private fun changeTime(hour:Int,minute:Int)=object:ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(TimePicker::class.java)
        override fun getDescription()="set time to $hour:$minute"
        override fun perform(controller:UiController,view:View) {
            view.clearFocus()
            (view as TimePicker).hour=hour
            view.minute=minute
            view.clearFocus()
            controller.loopMainThreadUntilIdle()
        }
    }

    @Test fun createEditAndDeleteFromReminderSettings() {
        launchApp()
        openProfileRow("提醒设置")
        ui.onNodeWithTag("custom-reminder-add").performScrollTo().performClick()
        ui.onNodeWithTag("custom-reminder-title").performTextInput("合成自定义提醒")
        ui.onNodeWithTag("custom-reminder-repeat-DAILY").performScrollTo().performClick()
        // 标签选中态有 160 ms 颜色过渡；全量负载下点击可能落在过渡/滚动动画期间。
        // 先等选中态进入语义树再保存（只同步，不放宽断言）。
        ui.onNodeWithTag("custom-reminder-repeat-DAILY").assertIsSelected()
        ui.onNodeWithTag("custom-reminder-save").performClick()
        ui.waitUntil(10_000) { runBlocking { db.journalDao().allCustomReminders().size == 1 } }
        val id = runBlocking { db.journalDao().allCustomReminders().single().id }
        assertEquals("DAILY",runBlocking { db.journalDao().customReminder(id)?.repeat })

        ui.onNodeWithTag("custom-reminder-edit-$id").performScrollTo().performClick()
        ui.onNodeWithTag("custom-reminder-title").performTextReplacement("修改后的提醒")
        ui.onNodeWithTag("custom-reminder-save").performClick()
        try { ui.waitUntil(10_000) { runBlocking { db.journalDao().customReminder(id)?.title == "修改后的提醒" } } }
        catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("修改提醒未保存：当前=${runBlocking { db.journalDao().customReminder(id) }}；界面=${ui.onRoot().printToString()}", timeout)
        }
        assertEquals(2L,runBlocking { db.journalDao().customReminder(id)?.revision })

        ui.onNodeWithTag("custom-reminder-delete-$id").performScrollTo().performClick()
        // The confirmation dialog is a separate composition; under cold/loaded emulators it may take a
        // frame or two to appear, so wait for it instead of clicking immediately.
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("custom-reminder-confirm-delete").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("custom-reminder-confirm-delete").performClick()
        ui.waitUntil(10_000) { runBlocking { db.journalDao().customReminder(id) == null } }
        assertTrue(runBlocking { db.journalDao().allCustomReminders().isEmpty() })
    }
}
