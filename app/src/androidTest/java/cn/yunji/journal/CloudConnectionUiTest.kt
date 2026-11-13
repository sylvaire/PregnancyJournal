package cn.yunji.journal

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.yunji.journal.core.designsystem.PregnancyTheme
import cn.yunji.journal.feature.CloudConnectionContent
import cn.yunji.journal.feature.CloudUiState
import cn.yunji.journal.feature.CloudConnectionScreen
import cn.yunji.journal.feature.CloudConnectionController
import cn.yunji.journal.feature.CloudBackupController
import cn.yunji.journal.feature.CloudBackupUiState
import cn.yunji.journal.core.network.*
import cn.yunji.journal.data.*
import androidx.lifecycle.lifecycleScope
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic host only. Never launch this Compose test host on the Xiaomi phone. */
class CloudConnectionUiTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    @Test fun untestedConnectionCannotBeSavedAndDisconnectRequiresConfirmation() {
        var disconnects=0
        ui.setContent {PregnancyTheme {CloudConnectionContent(CloudUiState(loading=false,saved=true,hasSavedPassword=true),{_,_->},{},{},{},{disconnects++},{})}}
        ui.onNodeWithText("保存连接").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithText("清除本机连接").performScrollTo().performClick()
        ui.onNodeWithText("清除本机连接？").assertIsDisplayed();assertEquals(0,disconnects)
        ui.onNodeWithText("取消").performClick();assertEquals(0,disconnects)
        ui.onNodeWithText("清除本机连接").performScrollTo().performClick()
        ui.onNodeWithText("清除",useUnmergedTree=true).performClick();assertEquals(1,disconnects)
    }
    @Test fun browserUsesConfirmedDirectoryWithoutRefillingPasswordOrWritingToCloud() {
        var writeProbes=0
        val access=object: CloudConnectionAccess {
            override fun cancelProbe()=Unit
            override suspend fun load()=SavedCloudConnection(connection=CloudConnection("https://synthetic.invalid/","synthetic-account","synthetic-secret"),capabilities=CloudCapabilities(),testedAt=1)
            override suspend fun probe(input: CloudConnection): CloudCapabilities {writeProbes++;return CloudCapabilities()}
            override suspend fun browse(input: CloudConnection)=when(input.folder){emptyList<String>()->listOf("家庭") to true;listOf("家庭")->listOf("孕期资料") to true;else->emptyList<String>() to true}
            override suspend fun save(connection: CloudConnection,capabilities: CloudCapabilities)=Unit
            override suspend fun disconnect()=Unit
            override suspend fun pendingCleanup(): ProbeCleanup?=null
            override suspend fun forgetCleanup()=Unit
        }
        val controller=CloudConnectionController(ui.activity.lifecycleScope,access)
        val backup=CloudBackupController(ui.activity.lifecycleScope,EmptyCloudBackup())
        ui.setContent {PregnancyTheme {CloudConnectionScreen(controller,backup)}}
        ui.waitUntil(5_000){!controller.state.value.loading}
        assertEquals("",controller.state.value.password)
        ui.onNodeWithText("浏览云盘目录").performScrollTo().performClick()
        ui.onNodeWithText("家庭").performClick()
        ui.onNodeWithText("孕期资料").performClick()
        assertEquals("",controller.state.value.folder)
        ui.onNodeWithText("使用此目录").performClick()
        assertEquals("家庭/孕期资料",controller.state.value.folder);assertEquals(0,writeProbes)
        ui.onNodeWithText("保存连接").performScrollTo().assertIsNotEnabled()
    }
    @Test fun backupSectionDistinguishesNeverAttemptedFromFailedAndDoesNotOfferUploadWhenUnverified() {
        ui.setContent {PregnancyTheme {CloudConnectionContent(CloudUiState(loading=false,saved=true,hasSavedPassword=true,capabilities=CloudCapabilities()),
            {_,_->},{},{},{},{},{},null,CloudBackupUiState(),{},{})}}
        ui.onNodeWithText("云端备份").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("尚未成功上传过备份").assertExists()
        ui.onNodeWithText("立即上传备份").assertIsNotEnabled()
    }
    @Test fun failedAttemptAndPriorSuccessAreShownSeparately() {
        val status=CloudUploadStatus(lastAttemptAt=1_700_000_000_000,lastSuccessAt=1_699_000_000_000,
            lastSnapshotId="12345678-1234-1234-1234-123456789012",lastPhase="FAILED",lastError="网络或回读校验未完成")
        ui.setContent {PregnancyTheme {CloudConnectionContent(CloudUiState(loading=false,saved=true,hasSavedPassword=true,capabilities=CloudCapabilities(verified=true)),
            {_,_->},{},{},{},{},{},null,CloudBackupUiState(status=status),{},{})}}
        ui.onNodeWithText("最近尝试",substring=true).performScrollTo().assertExists()
        ui.onNodeWithText("上次未能完成：网络或回读校验未完成").assertExists()
        ui.onNodeWithText("立即上传备份").assertIsEnabled()
        val shot=java.io.File(ui.activity.getExternalFilesDir(null),"verification/d03-cloud-backup.png").apply {parentFile!!.mkdirs()}
        shot.outputStream().use {ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
}

/** Upload is unreachable in these connection-only tests; it must never touch the network. */
private class EmptyCloudBackup : cn.yunji.journal.data.CloudBackupAccess {
    override fun status() = cn.yunji.journal.data.CloudUploadStatus()
    override fun isRunning() = false
    override suspend fun upload() = error("Upload must not run in connection tests")
}
