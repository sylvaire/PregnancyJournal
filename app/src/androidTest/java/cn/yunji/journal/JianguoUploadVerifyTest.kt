package cn.yunji.journal

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunji.journal.core.network.CloudConnection
import cn.yunji.journal.data.CloudBackupRunner
import cn.yunji.journal.data.CloudConnectionRepository
import cn.yunji.journal.data.CloudConnectionStore
import cn.yunji.journal.data.CloudSchedulerStore
import cn.yunji.journal.data.CloudUploadStatusStore
import cn.yunji.journal.data.RecoverySettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.util.UUID

/**
 * 真机上传复验：真实坚果云、**父目录留空**、真实 SQLCipher 库与加密原件。
 *
 * 走 App 的生产实现：`CloudConnectionRepository`（probe + save）→ `CloudBackupRunner`（真实上传）。
 * 与 UI 点击等价，但不依赖设备解锁后的界面交互。
 */
@RunWith(AndroidJUnit4::class)
class JianguoUploadVerifyTest {

    private val base get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun savedConnectionUploadsToRealProviderWithEmptyParentFolder() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("davEndpoint")
        val user = args.getString("davUser")
        val pass = args.getString("davPass")
        assumeTrue("未提供真实 DAV 凭据，跳过（不属于失败）",
            !endpoint.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank())

        IsolatedVault.fresh(base, "jianguo-upload-${UUID.randomUUID()}").use { vault ->
            val material = runBlocking { vault.runtime.resources().material }
            val connection = CloudConnection(endpoint!!, user!!, pass!!, emptyList())

            // 1) 保存连接：与 App 完全相同的仓库路径。
            val repository = CloudConnectionRepository(
                CloudConnectionStore(vault.context, material), material.id, Clock.systemUTC())
            val capabilities = runBlocking { repository.probe(connection) }
            Log.i(TAG, "probe=$capabilities")
            assertTrue("父目录留空时应可写", capabilities.writable)
            assertTrue("父目录留空时应通过回读校验", capabilities.verified)
            runBlocking { repository.save(connection, capabilities) }
            val saved = runBlocking { repository.load() }
            assertNotNull("连接应已保存", saved)
            assertTrue("父目录应保持为空", saved!!.connection.folder.isEmpty())
            Log.i(TAG, "saved=true folder=${saved.connection.folder}")

            // 2) 上传：真实 runner（内部走 CloudBackupCoordinator → 生产 pipeline）。
            //    需要已确认的恢复密钥，否则上传会被前置校验拒绝。
            val recoverySettings = RecoverySettings(vault.context, material)
            // 生成恢复密钥并用它要求的两组完成核对，产出真实 RecoverySlot（与 UI 路径一致）。
            val setup = cn.yunji.journal.core.backup.RecoveryKeySetup()
            val groups = setup.keyText().removePrefix("YJ1:").chunked(6)
            val answers = setup.requestedGroups.map { groups[it - 1] }
            Log.i(TAG, "recovery groups requested=${setup.requestedGroups}")
            val slot = setup.confirm(material, answers)
            Log.i(TAG, "slot confirmed")
            recoverySettings.save(slot)
            Log.i(TAG, "recovery slot saved")
            assertNotNull("恢复密钥应已保存", runBlocking { recoverySettings.load() })

            val runner = CloudBackupRunner(
                vault.context, vault.runtime, Clock.systemUTC(),
                CloudUploadStatusStore(vault.context), CloudSchedulerStore(vault.context),
            )
            Log.i(TAG, "calling runner.upload()")
            val outcome = runBlocking { runner.upload() }
            Log.i(TAG, "upload success=${outcome.success} message=${outcome.message} status=${outcome.status}")
            assertTrue("上传应成功：${outcome.message}", outcome.success)
            assertTrue("应记录成功的快照", outcome.status.hasSuccess)
            assertFalse("上传后不应仍在运行", runner.isRunning())
        }
    }

    private companion object { const val TAG = "JianguoUpload" }
}
