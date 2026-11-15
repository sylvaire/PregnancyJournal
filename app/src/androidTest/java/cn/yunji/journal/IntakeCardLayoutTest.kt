package cn.yunji.journal

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.designsystem.*
import cn.yunji.journal.feature.IntakeRecordCard
import cn.yunji.journal.feature.JournalPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic visual fixtures; real record/edit/delete behavior is covered by navigation regression. */
class IntakeCardLayoutTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()

    @Test fun statusAndDoseUseTheRightSideAndLongNamesRemainComplete() {
        var expanded by mutableStateOf(false)
        val longName="合成复合维生素片（长药品名称与规格信息完整展示）"
        ui.setContent {PregnancyTheme {JournalPage("服药记录",back={}) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                IntakeRecordCard("合成叶酸片","待确认","计划时刻","08:00","固定计划 · 第1版方案","方案用量","1 片",expanded,
                    Modifier.testTag("card-scheduled")) {expanded=!expanded}
                if(expanded)JournalCard {JournalKeyValue("计划时区","Asia/Shanghai");Text("按实际情况记录")}
                IntakeRecordCard(longName,"已服用","实际时间","11月15日 08:12","补记 · 第2版方案","实际用量","0.25 片",false,
                    Modifier.testTag("card-taken")) {}
                IntakeRecordCard("合成补充剂","已跳过","记录日期","11月14日","计划记录 · 第1版方案","实际用量","未记录",false,
                    Modifier.testTag("card-skipped")) {}
            }
        }}}
        ui.onNodeWithTag("card-scheduled").performScrollTo().assertIsDisplayed()
        val name=ui.onNodeWithText("合成叶酸片",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val status=ui.onNodeWithText("待确认",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val time=ui.onNodeWithText("08:00",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val dose=ui.onNodeWithText("1 片",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue("状态应位于药名右侧",status.left>=name.right)
        assertTrue("用量应位于时间右侧",dose.left>time.right)
        capture("scheduled")
        ui.onNodeWithTag("card-scheduled").performClick()
        ui.onNodeWithText("按实际情况记录").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("card-scheduled").performScrollTo().performClick()
        ui.onNodeWithText("按实际情况记录").assertDoesNotExist()
        ui.onNodeWithTag("card-taken").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(longName,useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {read ->
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>();read(layouts)
            assertTrue(layouts.isNotEmpty())
            layouts.forEach {layout ->
                assertFalse("长药名不得省略",layout.multiParagraph.didExceedMaxLines)
                assertTrue("长药名高度应完整",layout.size.height+1>=layout.multiParagraph.height)
            }
        }
        capture("taken-long-name")
        ui.onNodeWithTag("card-skipped").performScrollTo().assertIsDisplayed()
        capture("skipped")
    }

    private fun capture(name:String) {
        ui.waitForIdle()
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val config=context.resources.configuration
        val directory=File(context.getExternalFilesDir(null),"verification/intake-card/${config.fontScale}-${config.orientation}").apply {mkdirs()}
        val bitmap=ui.onRoot().captureToImage().asAndroidBitmap()
        File(directory,"$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
