package cn.yunji.journal

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.yunji.journal.core.designsystem.PregnancyTheme
import cn.yunji.journal.core.share.OriginalShareDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The share entry must disclose plaintext/temporary grants and must not confirm twice. */
@RunWith(AndroidJUnit4::class)
class OriginalShareUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dialogExplainsPlaintextAndTemporaryReadOnlyGrant() {
        var confirmed = 0
        var dismissed = 0
        rule.setContent {
            PregnancyTheme {
                OriginalShareDialog("报告.pdf", false, { confirmed++ }, { dismissed++ })
            }
        }
        rule.onNodeWithTag("share-dialog").assertExists()
        rule.onNodeWithText("明文", substring = true).assertExists()
        rule.onNodeWithText("只读访问", substring = true).assertExists()
        rule.onNodeWithTag("confirm-share").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun busyDialogDisablesConfirmation() {
        var confirmed = 0
        rule.setContent {
            PregnancyTheme {
                OriginalShareDialog("报告.pdf", true, { confirmed++ }, {})
            }
        }
        rule.onNodeWithTag("confirm-share").assertIsNotEnabled()
        rule.onNodeWithText("准备中…").assertExists()
        rule.onNodeWithTag("confirm-share").performClick()
        assertEquals(0, confirmed)
    }
}
