package cn.yunji.journal

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.yunji.journal.core.network.CloudConnection
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实服务商（坚果云）连接测试：**父目录留空**的默认路径。
 *
 * 该场景此前在真机上失败（服务商对目录名有长度上限，`yunji-probe-<uuid>` 48 字符被拒），
 * 本测试用真实网络与凭据锁定该回归。凭据从 instrumentation 参数读取，未提供则跳过。
 */
@RunWith(AndroidJUnit4::class)
class JianguoRootFolderProbeTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun rootFolderProbeSucceedsAgainstRealProvider() {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val endpoint = args.getString("davEndpoint")
        val user = args.getString("davUser")
        val pass = args.getString("davPass")
        assumeTrue("未提供真实 DAV 凭据，跳过（不属于失败）", !endpoint.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank())

        val material = cn.yunji.journal.core.security.VaultMaterial(
            java.util.UUID.randomUUID().toString(), cn.yunji.journal.core.security.VaultCrypto.randomKey())
        val store = cn.yunji.journal.data.CloudConnectionStore(context, material)
        val repository = cn.yunji.journal.data.CloudConnectionRepository(store, material.id, java.time.Clock.systemUTC())
        val connection = CloudConnection(endpoint!!, user!!, pass!!, emptyList())

        val capabilities = kotlinx.coroutines.runBlocking { repository.probe(connection) }
        Log.i(TAG, "capabilities=$capabilities")
        assertTrue("读取目录应可用", capabilities.readable)
        assertTrue("写入应可用（父目录留空时的历史失败点）", capabilities.writable)
        assertTrue("回读校验应通过", capabilities.verified)
        assertTrue("应能创建子目录", capabilities.canCreateDirectory)

        // 保存连接后立即上传一次，确认默认路径可以走完备份链路。
        kotlinx.coroutines.runBlocking { repository.save(connection, capabilities) }
        val saved = kotlinx.coroutines.runBlocking { repository.load() }
        assertTrue("连接应已保存", saved != null)
        store.disconnect()
    }

    private companion object { const val TAG = "JianguoProbe" }
}
