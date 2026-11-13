package cn.yunji.journal

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实服务商（坚果云）**父目录留空**下连续两次连接测试。
 *
 * 该场景此前失败：根目录 DELETE 恒返回 403，残留探测目录会让第二次测试抛异常（清理死锁）。
 * 本测试要求两次 probe 都完成读写校验，且不因残留而失败。
 */
@RunWith(AndroidJUnit4::class)
class JianguoRepeatedProbeTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun repeatedProbeWithEmptyParentFolderAlwaysSucceeds() {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val endpoint = args.getString("davEndpoint")
        val user = args.getString("davUser")
        val pass = args.getString("davPass")
        assumeTrue("未提供真实 DAV 凭据，跳过（不属于失败）",
            !endpoint.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank())

        val material = cn.yunji.journal.core.security.VaultMaterial(
            java.util.UUID.randomUUID().toString(), cn.yunji.journal.core.security.VaultCrypto.randomKey())
        val store = cn.yunji.journal.data.CloudConnectionStore(context, material)
        val repository = cn.yunji.journal.data.CloudConnectionRepository(store, material.id, java.time.Clock.systemUTC())
        val connection = cn.yunji.journal.core.network.CloudConnection(endpoint!!, user!!, pass!!, emptyList())

        repeat(2) { round ->
            val capabilities = kotlinx.coroutines.runBlocking { repository.probe(connection) }
            Log.i(TAG, "round=${round + 1} capabilities=$capabilities")
            assertTrue("第 ${round + 1} 次：读取应可用", capabilities.readable)
            assertTrue("第 ${round + 1} 次：写入应可用", capabilities.writable)
            assertTrue("第 ${round + 1} 次：回读校验应通过", capabilities.verified)
            assertTrue("第 ${round + 1} 次：应能创建子目录", capabilities.canCreateDirectory)
            // 根目录 DELETE 恒 403，canDelete 如实为 false 是可接受的；关键是不能再因此失败。
        }
        // 保存连接：默认路径必须可保存，且 capabilities 如实反映不可删目录。
        kotlinx.coroutines.runBlocking {
            repository.save(connection, kotlinx.coroutines.runBlocking { repository.probe(connection) })
        }
        val saved = kotlinx.coroutines.runBlocking { repository.load() }
        Log.i(TAG, "saved=${saved != null} folder=${saved?.connection?.folder}")
        assertTrue("连接应已保存", saved != null)
        assertTrue("父目录留空应原样保存为空列表", saved!!.connection.folder.isEmpty())
        assertTrue("保存的能力应包含读写校验通过", saved.capabilities.verified)
        store.disconnect()
        assertTrue("断开后不应残留连接", kotlinx.coroutines.runBlocking { repository.load() } == null)
    }

    private companion object { const val TAG = "JianguoRepeat" }
}
