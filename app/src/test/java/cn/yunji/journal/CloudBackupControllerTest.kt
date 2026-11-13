package cn.yunji.journal

import cn.yunji.journal.data.CloudBackupAccess
import cn.yunji.journal.data.CloudSchedulerState
import cn.yunji.journal.data.CloudUploadOutcome
import cn.yunji.journal.data.CloudUploadStatus
import cn.yunji.journal.feature.CloudBackupController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

/** 云端上传的前置条件与提示：缺恢复密钥时不得静默卡住。 */
class CloudBackupControllerTest {

    private class Access : CloudBackupAccess {
        var uploads = 0
        override fun status() = CloudUploadStatus()
        override suspend fun upload(): CloudUploadOutcome {
            uploads++
            return CloudUploadOutcome(true, "云端备份已提交并回读验证", CloudUploadStatus())
        }
        override fun isRunning() = false
        override fun scheduler() = CloudSchedulerState()
    }

    @Test fun missingRecoveryKeyExplainsWhatToDoInsteadOfStartingUpload() {
        val access = Access()
        val controller = CloudBackupController(
            CoroutineScope(Dispatchers.Unconfined), access,
            recoveryConfigured = { false },
        )
        controller.upload()
        assertEquals("缺少恢复密钥时不应发起上传", 0, access.uploads)
        val message = controller.state.value.message.orEmpty()
        assertTrue("应指向「本地备份」生成密钥：$message", message.contains("本地备份"))
        assertTrue("应说明需要核对恢复密钥：$message", message.contains("恢复密钥"))
        assertFalse("不应停留在运行中：$message", controller.state.value.running)
    }

    @Test fun uploadProceedsWhenRecoveryKeyIsConfirmed() {
        val access = Access()
        val controller = CloudBackupController(
            CoroutineScope(Dispatchers.Unconfined), access,
            recoveryConfigured = { true },
        )
        controller.upload()
        assertEquals(1, access.uploads)
        assertEquals("云端备份已提交并回读验证", controller.state.value.message)
        assertFalse(controller.state.value.running)
    }
}
