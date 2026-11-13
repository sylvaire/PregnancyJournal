package cn.yunji.journal

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.yunji.journal.core.designsystem.PregnancyTheme
import cn.yunji.journal.core.network.CloudCapabilities
import cn.yunji.journal.data.CleanupRecord
import cn.yunji.journal.data.CloudSchedulerState
import cn.yunji.journal.feature.CloudBackupUiState
import cn.yunji.journal.feature.CloudCleanupUiState
import cn.yunji.journal.feature.CloudConnectionContent
import cn.yunji.journal.feature.CloudUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Cleanup section of the cloud settings page. Synthetic host only; no network access. */
class CloudCleanupUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()

    private fun show(state: CloudUiState=CloudUiState(loading=false,saved=true,hasSavedPassword=true,capabilities=CloudCapabilities(verified=true)),
        backup: CloudBackupUiState=CloudBackupUiState(), cleanup: CloudCleanupUiState=CloudCleanupUiState(), run: ()->Unit={}) {
        ui.setContent {PregnancyTheme {CloudConnectionContent(state,{_,_->},{},{},{},{},{},
            null,backup,{},{},cleanup=cleanup,runCleanup=run)}}
    }

    @Test fun neverCleanedShowsHintAndManualEntryIsAvailable() {
        show()
        ui.onNodeWithText("尚未清理，备份成功后每天最多自动清理一次").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("立即清理").performScrollTo().assertIsEnabled()
    }

    @Test fun manualCleanupRequiresConfirmationAndRunsOnce() {
        var runs=0
        show(run={runs++})
        ui.onNodeWithText("立即清理").performScrollTo().performClick()
        ui.onNodeWithText("立即清理云端备份？").assertIsDisplayed()
        ui.onNodeWithText("只删除云端按保留策略淘汰的旧快照与无引用对象，不删除本机资料、历史资料库或其他设备目录。清理失败不会影响已有备份。").assertIsDisplayed()
        assertEquals(0,runs)
        ui.onNodeWithTag("cloud-cleanup-confirm").performClick()
        assertEquals(1,runs)
        ui.onNodeWithText("立即清理云端备份？").assertDoesNotExist()
    }

    @Test fun succeededAttemptShowsTimeCountsAndResultMessage() {
        val record=CleanupRecord(attemptedAt=1_700_000_000_000,ran=true,message="云端备份清理完成",
            kept=3,commitsDeleted=2,objectsDeleted=5,orphansTracked=1)
        show(cleanup=CloudCleanupUiState(record=record,message="云端备份清理完成"))
        ui.onNodeWithText("上次尝试：",substring=true).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("保留快照 3 · 删除提交 2 · 删除对象 5 · 新登记孤立 1").assertExists()
        ui.onNodeWithText("云端备份清理完成",substring=true).assertExists()
    }

    @Test fun skippedAttemptExplainsWhyNothingWasDeleted() {
        val record=CleanupRecord(attemptedAt=1_700_000_000_000,ran=false,message="尚未保存恢复密钥，不能清理云端备份")
        show(cleanup=CloudCleanupUiState(record=record))
        ui.onNodeWithText("未清理原因：尚未保存恢复密钥，不能清理云端备份").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("立即清理").performScrollTo().assertIsEnabled()
    }

    @Test fun unverifiedConnectionDisablesManualEntryAndShowsReason() {
        show(state=CloudUiState(loading=false,saved=true,hasSavedPassword=true,capabilities=CloudCapabilities(verified=false)))
        ui.onNodeWithText("当前云盘连接未通过写入与回读校验，不能清理云端备份").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("立即清理").performScrollTo().assertIsNotEnabled()
    }

    @Test fun otherDeviceBackupDisablesManualEntryAndShowsReason() {
        show(backup=CloudBackupUiState(scheduler=CloudSchedulerState(blockedByOtherWriter=true)))
        ui.onNodeWithText("检测到其他设备的备份，已暂停自动清理").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("立即清理").performScrollTo().assertIsNotEnabled()
    }

    @Test fun runningCleanupShowsProgressAndDisablesButton() {
        show(cleanup=CloudCleanupUiState(running=true))
        ui.onNodeWithText("正在清理云端备份…").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("立即清理").performScrollTo().assertIsNotEnabled()
    }
}
