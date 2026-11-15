package cn.yunji.journal

import android.graphics.Bitmap
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
import cn.yunji.journal.core.designsystem.PregnancyTheme
import cn.yunji.journal.feature.HomeIntakeSummaryCard
import cn.yunji.journal.feature.JournalPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 只渲染合成摘要，不访问应用数据库或真实服药资料。 */
class HomeIntakeSummaryCardTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun pendingSummaryKeepsCountsReadableAndEntryWorking() {
        var opens = 0
        ui.setContent { PregnancyTheme { JournalPage("首页服药摘要") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                HomeIntakeSummaryCard(false, false, 4, 1, 1, "08:00", 2) { opens++ }
            }
        } } }
        for (text in listOf("今日服药记录", "下一项 · 用药待确认", "08:00", "未记录", "2 项",
            "固定计划 4 项", "已服用 1 项", "已跳过 1 项", "另有补记2条", "查看服药记录")) {
            checkText(text)
        }
        val time = ui.onNodeWithText("08:00").fetchSemanticsNode().boundsInRoot
        val pending = ui.onNodeWithText("2 项").fetchSemanticsNode().boundsInRoot
        assertFalse("时间与待记录数不可重叠", time.overlaps(pending))
        capture("pending")
        ui.onNodeWithText("查看服药记录").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun loadingFailureEmptyAndCompletedNeverShowStalePendingTime() {
        var state by mutableIntStateOf(0)
        ui.setContent { PregnancyTheme { JournalPage("首页服药摘要") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                HomeIntakeSummaryCard(state == 0, state == 1, if(state == 2) 0 else 4,
                    3, 1, if(state < 2) "08:00" else null, 1) {}
            }
        } } }
        for ((index, text) in listOf("正在读取今日计划…", "暂时无法读取计划，请打开记录重试",
            "今天没有固定服药计划", "今天的固定计划已全部记录").withIndex()) {
            ui.runOnIdle { state = index }
            checkText(text)
            ui.onNodeWithText("08:00").assertDoesNotExist()
            if(index < 2) {
                ui.onNodeWithText("另有补记1条").assertDoesNotExist()
                ui.onNodeWithText("固定计划 4 项").assertDoesNotExist()
            } else checkText("另有补记1条")
            if(index == 3) {
                checkText("已服用 3 项")
                checkText("已跳过 1 项")
            }
            capture(listOf("loading", "failed", "empty", "completed")[index])
        }
    }

    private fun checkText(text: String) {
        ui.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
            val layouts = mutableListOf<TextLayoutResult>()
            read(layouts)
            assertTrue(layouts.isNotEmpty())
            for(layout in layouts) {
                assertFalse("文字不可省略：$text", layout.multiParagraph.didExceedMaxLines)
                for(offset in text.indices) {
                    val glyph = layout.getBoundingBox(offset)
                    assertTrue("字形不可越界：$text", glyph.left >= -1f && glyph.right <= layout.size.width + 1f)
                }
            }
        }
    }

    private fun capture(name: String) {
        ui.onNodeWithText("今日服药记录").performScrollTo()
        ui.waitForIdle()
        val file = File(ui.activity.getExternalFilesDir(null), "verification/home-intake/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
