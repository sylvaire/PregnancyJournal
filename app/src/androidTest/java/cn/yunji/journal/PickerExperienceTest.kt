package cn.yunji.journal

import android.content.res.Configuration
import android.view.View
import android.widget.TimePicker
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import cn.yunji.journal.core.designsystem.PregnancyTheme
import cn.yunji.journal.feature.DateField
import cn.yunji.journal.feature.RecordTimeField
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Synthetic fields only; native picker callbacks and cancellation must retain the original precision. */
class PickerExperienceTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()

    @Test fun choosingTimeKeepsSecondsAndCancellingDoesNotChangeIt() {
        var value by mutableStateOf("09:30:42")
        ui.setContent {PregnancyTheme {Surface {Column(Modifier.fillMaxWidth().padding(24.dp)) {
            RecordTimeField(value,{value=it},"测试时间",seconds=true)
        }}}}
        ui.onNodeWithContentDescription("选择测试时间").performClick()
        setTime(10,15)
        onView(isAssignableFrom(TimePicker::class.java)).check {view,error ->
            if(error!=null)throw error
            assertEquals("zh",view.resources.configuration.locales[0].language)
        }
        val bitmap=requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory=java.io.File(ApplicationProvider.getApplicationContext<android.content.Context>().getExternalFilesDir(null),"verification/ux-next-pickers").apply {mkdirs()}
        java.io.File(directory,"time-picker.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        onView(withId(android.R.id.button1)).perform(click())
        ui.runOnIdle {assertEquals("10:15:42",value)}
        ui.onNodeWithContentDescription("选择测试时间").performClick()
        setTime(11,20)
        onView(withId(android.R.id.button2)).perform(click())
        ui.runOnIdle {assertEquals("10:15:42",value)}
    }

    @Test fun addingMedicationTimesRetainsEarlierTimesAndAvoidsDuplicates() {
        var value by mutableStateOf("08:00")
        ui.setContent {PregnancyTheme {Surface {RecordTimeField(value,{value=it},"测试服药时刻",appendToList=true)}}}
        ui.onNodeWithContentDescription("选择测试服药时刻").performClick();setTime(20,0)
        onView(withId(android.R.id.button1)).perform(click())
        ui.runOnIdle {assertEquals("08:00,20:00",value)}
        ui.onNodeWithContentDescription("选择测试服药时刻").performClick();setTime(20,0)
        onView(withId(android.R.id.button1)).perform(click())
        ui.runOnIdle {assertEquals("08:00,20:00",value)}
    }

    @Test fun datePickerUsesChineseInsideAnEnglishConfiguration() {
        var selected by mutableStateOf("2026-03-15")
        ui.setContent {
            val configuration=Configuration(LocalConfiguration.current).apply {setLocale(Locale.US)}
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                PregnancyTheme {Surface {DateField("测试日期",selected,{selected=it})}}
            }
        }
        ui.onNodeWithText("测试日期").performClick()
        ui.onNode(isDialog()).assertExists()
        val directory=java.io.File(ApplicationProvider.getApplicationContext<android.content.Context>().getExternalFilesDir(null),"verification/ux-next-pickers").apply {mkdirs()}
        java.io.File(directory,"date-picker.txt").writeText(ui.onNode(isDialog()).printToString())
        java.io.File(directory,"date-picker.png").outputStream().use {ui.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        ui.onAllNodesWithText("选择日期").assertCountEquals(1)
        ui.onNodeWithText("Select date").assertDoesNotExist()
        val headline=ui.onNodeWithText("2026年3月15日")
        headline.assertIsDisplayed()
        headline.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {read ->
            val results=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            read(results)
            org.junit.Assert.assertTrue("完整日期应有可检查的文字布局",results.isNotEmpty())
            results.forEach {layout ->
                // Match the matrix checks: wrap-content text can report didOverflowWidth merely
                // because its paragraph used the parent's wider constraint. Check actual clipping.
                val constraints=layout.layoutInput.constraints
                val usedFullWidth=constraints.hasBoundedWidth && layout.size.width>=constraints.maxWidth-1
                val clippedWidth=layout.didOverflowWidth && usedFullWidth
                val clippedHeight=layout.size.height+1<layout.multiParagraph.height
                org.junit.Assert.assertFalse("日期标题不能截断: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}",
                    layout.multiParagraph.didExceedMaxLines || clippedWidth || clippedHeight)
            }
        }
        ui.onNodeWithText("取消").performClick()
        ui.onNode(isDialog()).assertDoesNotExist()
        ui.runOnIdle {assertEquals("2026-03-15",selected)}
        ui.onNodeWithText("测试日期").performClick()
        repeat(2) {ui.onNodeWithTag("date-picker-scroll").performTouchInput {swipeUp()}}
        ui.onNodeWithText("2026年3月31日星期二").assertIsDisplayed().performClick()
        java.io.File(directory,"date-picker-last-day.png").outputStream().use {ui.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        ui.onNodeWithText("确定").assertIsDisplayed().performClick()
        ui.runOnIdle {assertEquals("2026-03-31",selected)}
    }

    @Test fun leavingTheFieldDismissesTheNativePicker() {
        var visible by mutableStateOf(true)
        ui.setContent {PregnancyTheme {if(visible)Surface {RecordTimeField("09:30",{},"测试时间")}}}
        ui.onNodeWithContentDescription("选择测试时间").performClick()
        ui.runOnIdle {visible=false}
        ui.waitForIdle()
        onView(isAssignableFrom(TimePicker::class.java)).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
    }

    private fun setTime(hour:Int,minute:Int) {
        onView(isAssignableFrom(TimePicker::class.java)).perform(object:ViewAction {
            override fun getConstraints():Matcher<View> = isAssignableFrom(TimePicker::class.java)
            override fun getDescription()="set synthetic picker time"
            override fun perform(controller:UiController,view:View) {
                (view as TimePicker).apply {clearFocus();this.hour=hour;this.minute=minute;clearFocus()}
                controller.loopMainThreadUntilIdle()
            }
        })
    }
}
