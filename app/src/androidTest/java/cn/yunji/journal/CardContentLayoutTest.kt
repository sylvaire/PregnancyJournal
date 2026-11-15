package cn.yunji.journal

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
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

/** 检查实际文字边界和可操作性，覆盖窄卡片/长内容/系统大字体。只使用合成内容。 */
class CardContentLayoutTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun longSettingLabelDoesNotPushSwitchOutsideCardAndCanToggle() {
        val label = "允许本机在连接无线网络后自动备份已保存的资料"
        var checked by mutableStateOf(false)
        ui.setContent { PregnancyTheme { JournalPage("设置卡片") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                Box(Modifier.widthIn(max = 320.dp).testTag("card-bounds")) {
                    JournalCard { JournalSettingRow(label, checked, { checked = it }, switchModifier = Modifier.testTag("switch")) }
                }
            }
        } } }
        ui.onNodeWithTag("switch").performScrollTo().assertIsDisplayed()
        val card = ui.onNodeWithTag("card-bounds").fetchSemanticsNode().boundsInRoot
        val control = ui.onNodeWithTag("switch").fetchSemanticsNode().boundsInRoot
        assertTrue("开关不得超出卡片", control.left >= card.left && control.right <= card.right)
        completeText(label)
        ui.onNodeWithTag("switch").performScrollTo().performClick().assertIsOn()
        ui.runOnIdle { assertTrue(checked) }
        capture("setting")
    }

    @Test fun keyValueGlyphsStayInsideOwnBoundsForShortAndLongValues() {
        val values = listOf("1.25 MB", "2027年12月31日 09:30", "合成产检资料备份-完整文件名称.yunji")
        ui.setContent { PregnancyTheme { JournalPage("信息卡片") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                JournalCard {
                    JournalKeyValue("文件大小", values[0])
                    JournalKeyValue("预约时间", values[1])
                    JournalKeyValue("备份文件", values[2])
                }
            }
        } } }
        (values + listOf("文件大小", "预约时间", "备份文件")).forEach(::completeText)
        capture("key-value")
    }

    @Test fun passiveInformationKeepsFullExplanationWithoutAnUnavailableDetailPage() {
        val title = "验证方式与本机资料保护"
        val explanation = "使用手机系统提供的指纹、面容或锁屏密码验证身份。验证失败可以重新尝试，应用会保留当前记录和加密原件。"
        ui.setContent { PregnancyTheme { JournalPage("说明卡片") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                RecordRow(title, explanation, "ic-lock.svg")
            }
        } } }
        completeText(title)
        completeText(explanation)
        capture("information")
    }

    private fun completeText(text: String) {
        ui.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
            val layouts = mutableListOf<TextLayoutResult>()
            read(layouts)
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertFalse("完整内容不可省略：$text", layout.multiParagraph.didExceedMaxLines)
                text.indices.forEach { offset ->
                    val glyph = layout.getBoundingBox(offset)
                    assertTrue("字形不可越界：$text", glyph.left >= -1f && glyph.right <= layout.size.width + 1f)
                }
            }
        }
    }

    private fun capture(name: String) {
        val file = File(ui.activity.getExternalFilesDir(null), "verification/all-cards/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
