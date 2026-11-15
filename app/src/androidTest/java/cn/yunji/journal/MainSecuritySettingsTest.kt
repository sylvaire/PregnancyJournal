package cn.yunji.journal

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.security.DeviceKeyStore
import cn.yunji.journal.data.SecurityStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual MainActivity on an unsecured dedicated emulator; never changes a lock or a vault. */
class MainSecuritySettingsTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun unavailableAuthenticationKeepsSettingsUsableAndNeverChangesCommittedWrapper() {
        assumeTrue(Build.MODEL in setOf("sdk_gphone64_x86_64","sdk_gphone16k_x86_64"))
        val context=ApplicationProvider.getApplicationContext<Context>()
        val settings=SecurityStore(context)
        val keys=DeviceKeyStore(context)
        assumeTrue(!context.getSystemService(KeyguardManager::class.java).isDeviceSecure && !settings.lockEnabled() && !keys.strict())
        ui.waitUntil(15_000) {ui.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty()}
        ui.onNode(hasText("我的") and hasClickAction()).performClick()
        ui.onNodeWithText("安全设置").performScrollTo().performClick()
        ui.onNodeWithTag("security-settings").assertIsDisplayed()
        val wrapper=File(context.noBackupFilesDir,"vault/device-key.json")
        val original=wrapper.readBytes()
        listOf("开启严格模式","开启应用锁").forEach {label ->
            ui.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
            ui.onNodeWithText("手机尚未设置安全锁屏。",substring=true).performScrollTo().assertIsDisplayed()
            ui.onNodeWithTag("security-settings").assertIsDisplayed()
            ui.onNodeWithTag("vault-locked").assertDoesNotExist()
            assertFalse(settings.lockEnabled());assertFalse(keys.strict())
            assertArrayEquals(original,wrapper.readBytes())
        }
        ui.onNodeWithTag("navigate-up").performClick()
        ui.onNodeWithTag("profiles-root-list").assertIsDisplayed()
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15_000) {ui.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("vault-locked").assertDoesNotExist()
        assertArrayEquals(original,wrapper.readBytes())
    }
}
