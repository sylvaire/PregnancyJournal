package cn.yunji.journal.data

import cn.yunji.journal.core.network.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Clock

interface CloudConnectionAccess {
    fun cancelProbe()
    suspend fun browse(input: CloudConnection): Pair<List<String>,Boolean>
    suspend fun probe(input: CloudConnection): CloudCapabilities
    suspend fun load(): SavedCloudConnection?
    suspend fun save(connection: CloudConnection,capabilities: CloudCapabilities)
    suspend fun disconnect()
    suspend fun pendingCleanup(): ProbeCleanup?
    suspend fun forgetCleanup()
}

class CloudConnectionRepository(val store: CloudSettingsStorage,private val vaultId: String,private val clock: Clock,
    private val factory: (CloudConnection)->WebDavClient={WebDavClient(it.endpoint.toHttpUrl(),it.username,it.password)}): CloudConnectionAccess {
    private var client: WebDavClient?=null
    @Synchronized override fun cancelProbe() {client?.close()}
    private suspend fun <T> useConnection(input: CloudConnection,block: (WebDavClient,CloudConnection,Job)->T): T=withContext(Dispatchers.IO) {
        val connection=input.normalized()
        val url=connection.endpoint.toHttpUrl()
        val origin="${url.scheme}://${url.host}:${url.port}"
        store.cooldown()?.takeIf {it.origin==origin && it.until>clock.millis()}?.let {throw DavException(429,((it.until-clock.millis()+999)/1000).coerceAtMost(604_800))}
        val activeClient=factory(connection)
        synchronized(this@CloudConnectionRepository){if(client!=null){activeClient.close();error("正在测试连接")};client=activeClient}
        val job=coroutineContext.job
        try {
            job.ensureActive()
            block(activeClient,connection,job)
        } catch(e: DavException) {
            if(e.status==429 || e.status==503 && e.retryAfterSeconds!=null) {
                val seconds=(e.retryAfterSeconds ?: 30).coerceIn(1,604_800)
                runCatching {store.saveCooldown(CloudCooldown(origin,clock.millis()+seconds*1000))}
            }
            throw e
        } finally {
            activeClient.close()
            synchronized(this@CloudConnectionRepository){if(client===activeClient)client=null}
        }
    }
    override suspend fun browse(input: CloudConnection): Pair<List<String>,Boolean> = useConnection(input) {client,connection,job ->
        val entries=client.list(connection.folder);job.ensureActive()
        val names=entries.filter {it.collection && it.status in 200..299 && it.segments.size==connection.folder.size+1}.map {it.segments.last()}
        val collator=java.text.Collator.getInstance(java.util.Locale.CHINA)
        names.sortedWith {a,b->collator.compare(a,b)} to entries.all {it.status in 200..299}
    }
    override suspend fun probe(input: CloudConnection): CloudCapabilities=useConnection(input) {activeClient,connection,job ->
            val previous=store.pending()
            if(previous!=null) {
                require(previous.matches(connection)) {"上次测试尚有待清理项目，请先使用原服务、账号和目录完成清理"}
                // 旧版把探测文件直接写在父目录（`<name>.bin`）；新版写在自建目录内。
                // 两种残留都要清。404 表示该形式不存在；403/405/501 表示服务商不允许删除
                // （坚果云对根目录下的 DELETE 恒返回 403），残留只是空目录，不应阻塞重新测试。
                try { activeClient.delete(connection.folder+"${previous.name}.bin") }
                catch(e: DavException){if(e.status !in UNDELETABLE)throw e}
                // 只删除本次探测自建目录内的内容：目录名是随机生成的，内容必然属于探测。
                try {
                    val directory=connection.folder+previous.name
                    val entries=activeClient.list(directory)
                    entries.filter { it.segments != directory && it.segments.size == directory.size + 1 }
                        .forEach { child ->
                            try { activeClient.delete(child.segments) }
                            catch(e: DavException){if(e.status !in UNDELETABLE)throw e}
                        }
                    try { activeClient.delete(directory) }
                    catch(e: DavException){if(e.status !in UNDELETABLE)throw e}
                }catch(e: DavException){if(e.status!=404)throw e}
                // 即使服务商拒绝删除残留目录，本机标记也应清掉，否则用户无法再次测试。
                store.probeCleaned()
            }
            CloudProbe.run(activeClient,connection,vaultId,{job.ensureActive()},
                {name->store.beginProbe(ProbeCleanup(connection.endpoint,connection.username,connection.folder,name))},{store.probeCleaned()})
    }
    override suspend fun load()=withContext(Dispatchers.IO){store.load()}
    override suspend fun save(connection: CloudConnection,capabilities: CloudCapabilities)=withContext(Dispatchers.IO){store.save(SavedCloudConnection(connection=connection.normalized(),capabilities=capabilities,testedAt=clock.millis()))}
    override suspend fun disconnect()=withContext(Dispatchers.IO){store.disconnect()}
    override suspend fun pendingCleanup()=withContext(Dispatchers.IO){store.pending()}
    override suspend fun forgetCleanup()=withContext(Dispatchers.IO){store.probeCleaned()}

    private companion object {
        /**
         * 服务商拒绝删除残留时返回的状态。坚果云对**根目录下的 DELETE 恒返回 403**（同一目录放到
         * 子目录下即 204），所以「父目录留空」时探测目录可能永久残留。它只是空目录，不应让
         * 用户卡在「必须清理才能重测」的死锁里。
         */
        val UNDELETABLE = setOf(403, 405, 501)
    }
}
