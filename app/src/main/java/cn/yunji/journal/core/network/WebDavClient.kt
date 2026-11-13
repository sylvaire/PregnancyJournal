package cn.yunji.journal.core.network

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.ResponseBody
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

class DavException(val status: Int,val retryAfterSeconds: Long?=null) : IOException(when (status) {
    401 -> "云盘认证失败，请更新应用密码"
    403 -> "云盘拒绝访问此目录，请检查权限或服务限制"
    404 -> "云盘目录或文件不存在，请核对位置"
    405 -> "云盘不允许此操作，请检查目录和服务能力"
    409 -> "上级目录不存在，请先核对目录层级"
    413, 507 -> "文件过大或云盘空间不足"
    429 -> "请求过于频繁，请稍后重试"
    else -> "云盘请求失败（$status）"
})

/** No cross-origin redirects and no trust-all TLS configuration. Credentials never enter logs. */
class WebDavClient(base: HttpUrl, username: String, password: String, client: OkHttpClient = OkHttpClient()): AutoCloseable {
    private val base = base.newBuilder().apply { if (!base.encodedPath.endsWith('/')) addPathSegment("") }.build()
    private var authorization: String? = Credentials.basic(username, password,Charsets.UTF_8)
    private val calls=java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
    private var closed=false
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        // 强制 HTTP/1.1：部分服务商（坚果云）与部分系统的 HTTP/2 实现组合下会出现
        // Http2Stream 流级超时（真机实测），改用 1.1 后稳定。
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    init { require(base.isHttps && base.username.isEmpty() && base.password.isEmpty() && base.fragment == null && base.query == null) { "请使用不带凭据和查询参数的 HTTPS 地址" } }

    fun probe(): Int {
        val entries=properties(emptyList(),0)
        val root=entries.singleOrNull {it.segments.isEmpty()} ?: error("云盘响应缺少目标目录")
        if(root.status !in 200..299)throw DavException(root.status)
        require(root.collection) {"目标地址不是可访问的云盘目录"}
        return 207
    }

    fun list(segments: List<String> = emptyList()): List<DavEntry> {
        val entries=properties(segments,1)
        val root=entries.singleOrNull {it.segments==segments} ?: error("云盘响应缺少目标目录")
        if(root.status !in 200..299)throw DavException(root.status)
        require(root.collection) {"目标地址不是可访问的云盘目录"}
        return entries
    }

    private fun properties(segments: List<String>,depth: Int): List<DavEntry> {
        val target=if(segments.isEmpty())base else path(segments).newBuilder().addPathSegment("").build()
        val response=execute(target,"PROPFIND","<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/></d:prop></d:propfind>".toByteArray(),mapOf("Depth" to depth.toString(),"Content-Type" to "application/xml; charset=utf-8"))
        response.use {
            if (it.code != 207) throw failure(it)
            val bytes = readBounded(it.body?.byteStream() ?: error("响应为空"), 2 * 1024 * 1024)
            return DavXml.parse(bytes,base,segments,depth)
        }
    }

    fun put(segments: List<String>, bytes: ByteArray) {
        execute(path(segments), "PUT", bytes).use { if (it.code !in 200..299) throw failure(it) }
    }

    fun putNew(segments: List<String>,bytes: ByteArray) {
        execute(path(segments),"PUT",bytes,mapOf("If-None-Match" to "*")).use {if(it.code !in 200..299)throw failure(it)}
    }

    fun get(segments: List<String>, maxBytes: Int): ByteArray = execute(path(segments), "GET").use {
        if (it.code != 200) throw failure(it)
        readBounded(it.body?.byteStream() ?: error("响应为空"), maxBytes)
    }

    /** Constant-memory transfer. Caller owns immutable source lifetime and commit ordering. */
    fun putFile(segments: List<String>,file: java.io.File,expectedSize: Long,expectedSha256: String,
        checkpoint: ()->Unit = {}) {
        validateDigest(expectedSize,expectedSha256)
        require(file.isFile && file.length()==expectedSize)
        val body=object: okhttp3.RequestBody() {
            override fun contentType()=null
            override fun contentLength()=expectedSize
            override fun isOneShot()=true
            override fun writeTo(sink: okio.BufferedSink) {
                val digest=java.security.MessageDigest.getInstance("SHA-256")
                var total=0L;val buffer=ByteArray(64*1024)
                try {
                    file.inputStream().use {input->
                        while(true) {
                            checkpoint();val count=input.read(buffer);if(count<0)break
                            require(count.toLong()<=expectedSize-total) {"备份源文件已变化"}
                            sink.write(buffer,0,count);digest.update(buffer,0,count);total+=count
                        }
                    }
                    checkpoint()
                    require(total==expectedSize && digestHex(digest.digest())==expectedSha256) {"备份源文件校验失败"}
                }finally {buffer.fill(0)}
            }
        }
        execute(path(segments),"PUT",headers=mapOf("If-None-Match" to "*","Content-Type" to "application/octet-stream"),body=body).use {
            if(it.code !in 200..299)throw failure(it)
        }
        checkpoint()
    }

    /** Downloads every byte without a plaintext/cache file; HEAD/ETag never substitute for content verification. */
    fun verifyDownloaded(segments: List<String>,expectedSize: Long,expectedSha256: String,checkpoint: ()->Unit = {}) {
        validateDigest(expectedSize,expectedSha256)
        execute(path(segments),"GET",headers=mapOf("Accept-Encoding" to "identity")).use {response->
            if(response.code!=200)throw failure(response)
            require(response.header("Content-Encoding").let {it==null || it.equals("identity",true)})
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            var total=0L;val buffer=ByteArray(64*1024)
            try {
                requireNotNull(response.body).byteStream().use {input->
                    while(true) {
                        checkpoint();val count=input.read(buffer);if(count<0)break
                        require(count.toLong()<=expectedSize-total) {"云端对象超过预期长度"}
                        digest.update(buffer,0,count);total+=count
                    }
                }
                checkpoint()
                require(total==expectedSize && digestHex(digest.digest())==expectedSha256) {"云端对象内容校验失败"}
            }finally {buffer.fill(0)}
        }
    }
    /**
     * Streams a remote object into a local file with constant memory, then verifies length and SHA-256.
     * The destination is removed on any failure, so a partially downloaded object can never be mistaken
     * for a complete one. Used when restoring a snapshot from the cloud.
     */
    fun downloadTo(segments: List<String>,destination: java.io.File,expectedSize: Long,expectedSha256: String,
        checkpoint: ()->Unit = {}) {
        validateDigest(expectedSize,expectedSha256)
        require(!destination.exists()) {"目标文件已存在"}
        destination.parentFile?.mkdirs()
        try {
            execute(path(segments),"GET",headers=mapOf("Accept-Encoding" to "identity")).use {response->
                if(response.code!=200)throw failure(response)
                require(response.header("Content-Encoding").let {it==null || it.equals("identity",true)})
                val digest=java.security.MessageDigest.getInstance("SHA-256")
                var total=0L;val buffer=ByteArray(64*1024)
                try {
                    java.io.FileOutputStream(destination).use {file->
                        requireNotNull(response.body).byteStream().use {input->
                            while(true) {
                                checkpoint();val count=input.read(buffer);if(count<0)break
                                require(count.toLong()<=expectedSize-total) {"云端对象超过预期长度"}
                                file.write(buffer,0,count);digest.update(buffer,0,count);total+=count
                            }
                        }
                        file.fd.sync()
                    }
                    checkpoint()
                    require(total==expectedSize && digestHex(digest.digest())==expectedSha256) {"云端对象内容校验失败"}
                }finally {buffer.fill(0)}
            }
        } catch (e: Throwable) {
            destination.delete()
            throw e
        }
    }

    private fun validateDigest(size: Long,sha256: String) {
        require(size in 0..8L*1024*1024*1024 && sha256.matches(Regex("[0-9a-f]{64}")))
    }
    private fun digestHex(bytes: ByteArray)=bytes.joinToString(""){"%02x".format(it)}
    fun createDirectory(segments: List<String>) {
        execute(path(segments), "MKCOL").use { if (it.code !in 200..299) throw failure(it) }
    }

    fun delete(segments: List<String>): Boolean=execute(path(segments),"DELETE").use {
        if(it.code !in 200..299 && it.code!=404)throw failure(it)
        it.code in 200..299
    }
    fun head(segments: List<String>): Long? = execute(path(segments),"HEAD").use {
        if(it.code !in 200..299)throw failure(it)
        it.header("Content-Length")?.toLongOrNull()?.takeIf {length->length>=0}
    }
    @Synchronized override fun close() {closed=true;authorization=null;calls.forEach {it.cancel()};calls.clear()}

    private fun failure(response: okhttp3.Response): DavException {
        val text=response.header("Retry-After")?.trim()?.takeIf {it.length<=128}
        val seconds=text?.toLongOrNull()?.takeIf {it>=0} ?: text?.let {runCatching {
            val instant=java.time.ZonedDateTime.parse(it,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            java.time.Duration.between(java.time.Instant.now(),instant).seconds.coerceAtLeast(0)
        }.getOrNull()}
        return DavException(response.code,seconds?.coerceAtMost(604_800))
    }

    private fun path(segments: List<String>): HttpUrl {
        require(segments.size in 1..32 && segments.all { it.isNotBlank() && it.length<=255 && it != "." && it != ".." && '/' !in it && '\\' !in it && it.none(Char::isISOControl) })
        return base.newBuilder().apply { segments.forEach(::addPathSegment) }.build()
    }

    private fun execute(url: HttpUrl, method: String, bytes: ByteArray? = null, headers: Map<String, String> = emptyMap(),body: okhttp3.RequestBody? = null): okhttp3.Response {
        val auth=synchronized(this){check(!closed) {"连接已取消"};requireNotNull(authorization)}
        val request = Request.Builder().url(url).header("Authorization", auth).apply {
            headers.forEach { (key, value) -> header(key, value) }
            // MKCOL/DELETE 等无内容方法不能附带空 body：部分服务商（坚果云）会因此返回 400。
            val entity = body ?: bytes?.toRequestBody()
            if (method in setOf("GET", "HEAD") || (entity == null && method in BODYLESS_METHODS)) method(method, null)
            else method(method, entity ?: byteArrayOf().toRequestBody())
        }.build()
        val call=client.newCall(request)
        synchronized(this){check(!closed) {"连接已取消"};calls.add(call)}
        return try {
            val response=call.execute()
            val body=response.body ?: return response.also {calls.remove(call)}
            val source: okio.BufferedSource =
                object: ForwardingSource(body.source()) {override fun close(){try {super.close()} finally {calls.remove(call)}}}.buffer()
            response.newBuilder().body(object: ResponseBody() {
                override fun contentType()=body.contentType()
                override fun contentLength()=body.contentLength()
                override fun source()=source
                /**
                 * 未读尽的响应体必须丢弃剩余内容再关闭，否则 OkHttp 会判定连接不可复用；
                 * 长时间运行（真机高延迟网络）会累积大量 CLOSE_WAIT 并最终耗尽连接池。
                 */
                override fun close() {
                    try {
                        // 丢弃未读内容，让连接可以回到池中复用。
                        val sink = okio.Buffer()
                        while (source.read(sink, 64L * 1024) != -1L) sink.clear()
                    } catch (_: Exception) {
                    } finally {
                        runCatching { source.close() }
                    }
                }
            }).build()
        } catch(e: Exception){calls.remove(call);throw e}
    }

    private fun readBounded(stream: java.io.InputStream, limit: Int): ByteArray {
        require(limit in 1..(16 * 1024 * 1024))
        return stream.use { source ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                require(out.size().toLong() + count <= limit) { "云盘响应超过大小限制" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    private companion object {
        /**
         * 不携带请求体的方法。向这些方法附加空 body 会被部分服务商拒绝（坚果云对 MKCOL 返回 400）。
         * PROPFIND 不在此列：它需要 XML 请求体。
         */
        val BODYLESS_METHODS = setOf("MKCOL", "DELETE", "COPY", "MOVE", "OPTIONS")
    }
}
