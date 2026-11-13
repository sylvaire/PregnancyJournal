package cn.yunji.journal

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import cn.yunji.journal.core.designsystem.JournalSwitch
import cn.yunji.journal.core.designsystem.PregnancyTheme
import org.junit.Test
import java.io.File

/**
 * P1 遗留：JournalSwitch 禁用态像素证据。
 *
 * 现有快照只覆盖 enabled 状态；本测试在同一页面渲染 4 种状态（禁用/启用 × 选中/未选），
 * 供宿主机 Pillow 测量禁用态颜色（应为 onSurface alpha 语义、与启用态可区分）。
 * 产物：应用外部 files `verification/remaining-debug/switch-states.png`。
 */
class UiSwitchStateSnapshotTest : PageMatrixBaseTest() {
    @Test
    fun switchStates() {
        ui.setContent {
            PregnancyTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text("disabled off"); JournalSwitch(false, {}, Modifier.testTag("switch-disabled-off"), enabled = false)
                        Text("disabled on"); JournalSwitch(true, {}, Modifier.testTag("switch-disabled-on"), enabled = false)
                        Text("enabled off"); JournalSwitch(false, {}, Modifier.testTag("switch-enabled-off"))
                        Text("enabled on"); JournalSwitch(true, {}, Modifier.testTag("switch-enabled-on"))
                    }
                }
            }
        }
        settle()
        val dir = File(context.getExternalFilesDir(null), "verification/remaining-debug").apply { mkdirs() }
        File(dir, "switch-states.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

