package cn.yunji.journal

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import cn.yunji.journal.core.designsystem.*
import cn.yunji.journal.feature.JournalPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ContentHeadingLayoutTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun namesAndStatusesRemainCompleteWithoutOrphanedStatusLines() {
        val name = "复合维生素片（孕妇型）"
        val status = "进行中 · 第1版"
        ui.setContent { PregnancyTheme { JournalPage("内容卡片") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                JournalCard { JournalContentHeading("体重", "60.125 kg") }
                JournalCard { JournalContentHeading(name, status) }
                JournalCard { JournalContentHeading("每日叶酸提醒", "每天") }
            }
        } } }
        ui.onNodeWithText(status).performScrollTo().assertIsDisplayed()
        val nameBounds = ui.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        val statusBounds = ui.onNodeWithText(status).fetchSemanticsNode().boundsInRoot
        assertTrue("长标题和状态不可交叠", statusBounds.top >= nameBounds.bottom || statusBounds.left >= nameBounds.right)
        for (text in listOf(name, status, "每日叶酸提醒", "每天", "60.125 kg")) {
            ui.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            ui.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
                val layouts = mutableListOf<TextLayoutResult>()
                read(layouts)
                assertTrue(layouts.isNotEmpty())
                layouts.forEach { layout ->
                    // Paragraph保留父级测量宽度时hasVisualOverflow可能为true；检查实际字形是否越界。
                    // 这仍可捕捉TextAlign.End把字形放在自身宽度之外的情况。
                    assertFalse("文字不得被省略：$text", layout.multiParagraph.didExceedMaxLines)
                    for (offset in text.indices) {
                        val glyph = layout.getBoundingBox(offset)
                        assertTrue("字形不可超出文字区域：$text [$offset] $glyph size=${layout.size}",
                            glyph.left >= -1f && glyph.right <= layout.size.width + 1f &&
                                glyph.top >= -1f && glyph.bottom <= layout.size.height + 1f)
                    }
                    if (text == status || text == "每天") assertEquals("短状态应完整成行", 1, layout.lineCount)
                }
            }
        }
        ui.onNodeWithText("体重").performScrollTo()
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        val context = ui.activity
        val file = File(context.getExternalFilesDir(null), "verification/visual-fix/content-heading.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
