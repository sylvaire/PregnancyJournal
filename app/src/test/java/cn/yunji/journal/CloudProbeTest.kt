package cn.yunji.journal

import cn.yunji.journal.core.network.*
import cn.yunji.journal.data.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CloudProbeTest {
    private class Service: Dispatcher() {
        val directories=mutableSetOf("/backup")
        val files=mutableMapOf<String,ByteArray>()
        val requests=CopyOnWriteArrayList<Pair<String,String>>()
        var denyWrite=false;var denyDelete=false;var corruptDownload=false;var noHead=false;var denyAuth=false;var limited=false
        /** 模拟坚果云根目录：文件可删，目录 DELETE 恒 403。 */
        var denyDirectoryDelete=false
        /** 模拟坚果云：WebDAV 根目录拒绝直接 PUT 文件（404），但子目录内可写。 */
        var rejectRootPut=false
        lateinit var factory: ()->WebDavClient
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path=requireNotNull(request.requestUrl).encodedPath.trimEnd('/')
            val method=requireNotNull(request.method);requests+=method to path
            if(limited)return MockResponse().setResponseCode(429).setHeader("Retry-After","120")
            if(denyAuth || request.getHeader("Authorization")!=okhttp3.Credentials.basic("synthetic","synthetic-secret"))return MockResponse().setResponseCode(401)
            return when(method) {
                "PROPFIND"->{
                    if(path !in directories)return MockResponse().setResponseCode(404)
                    val paths=if(request.getHeader("Depth")=="0")listOf(path) else listOf(path)+(directories+files.keys).filter {it.substringBeforeLast('/')==path}
                    val body=paths.joinToString("") {item->"<d:response><d:href>$item${if(item in directories)"/" else ""}</d:href><d:propstat><d:prop><d:resourcetype>${if(item in directories)"<d:collection/>" else ""}</d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"}
                    MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\">$body</d:multistatus>")
                }
                "MKCOL"->if(denyWrite)MockResponse().setResponseCode(403) else {directories+=path;MockResponse().setResponseCode(201)}
                "PUT"->if(denyWrite)MockResponse().setResponseCode(403)
                    else if(rejectRootPut && path.substringBeforeLast('/').isEmpty())MockResponse().setResponseCode(404)
                    else {files[path]=request.body.readByteArray();MockResponse().setResponseCode(201)}
                "GET"->files[path]?.let {MockResponse().setBody(okio.Buffer().write(if(corruptDownload)byteArrayOf(1) else it))} ?: MockResponse().setResponseCode(404)
                "HEAD"->if(noHead)MockResponse().setResponseCode(405) else MockResponse().setHeader("Content-Length",files[path]?.size ?: 0)
                "DELETE"->if(denyDelete)MockResponse().setResponseCode(403)
                    else if(denyDirectoryDelete && path in directories && files.remove(path)==null)MockResponse().setResponseCode(403)
                    else {
                        val removed=files.remove(path)!=null || directories.remove(path);MockResponse().setResponseCode(if(removed)204 else 404)
                    }
                else->MockResponse().setResponseCode(405)
            }
        }
    }
    private fun service(block: (Service,WebDavClient,CloudConnection)->Unit) {
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls=HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use {server ->
            val service=Service();server.dispatcher=service;server.useHttps(tls.sslSocketFactory(),false);server.start()
            val url=server.url("/backup/").newBuilder().host("localhost").build()
            val connection=CloudConnection(url.toString(),"synthetic","synthetic-secret")
            val http=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build()
            service.factory={WebDavClient(url,connection.username,connection.password,http)}
            WebDavClient(url,connection.username,connection.password,http).use {block(service,it,connection)}
        }
    }
    @Test fun readWriteProbeVerifiesDownloadedBytesAndCleansOnlyItsOwnPaths()=service {server,client,connection ->
        server.files["/backup/keep.bin"]=byteArrayOf(7)
        var marked=false;var cleaned=false
        val result=CloudProbe.run(client,connection,"local",beforeWrite={marked=true},afterCleanup={cleaned=true})
        assertTrue(marked && cleaned);assertTrue(result.readable && result.writable && result.verified && result.canCreateDirectory && result.canDelete && result.headAvailable)
        assertEquals(setOf("/backup/keep.bin"),server.files.keys);assertEquals(setOf("/backup"),server.directories)
        // 根目录场景下探测发生在 `pregnancy-journal` 容器内（服务商拒绝根目录 PUT/DELETE）。
        val probeReq=server.requests.filter {it.first in setOf("PUT","MKCOL","DELETE")}
        assertTrue("probeReq=$probeReq",probeReq.all {
            it.second.startsWith("/backup/yjp-") || it.second.startsWith("/backup/backup/yjp-")
                || it.second=="/backup/pregnancy-journal"
        })
    }
    @Test fun readOnlyDirectoryNeverClaimsWriteOrDeleteCapability()=service {server,client,connection ->
        server.denyWrite=true
        val result=CloudProbe.run(client,connection,"local")
        assertTrue(result.readable);assertFalse(result.writable || result.verified || result.canCreateDirectory || result.canDelete)
        assertTrue(server.files.isEmpty());assertFalse(result.cleanupPending)
    }
    @Test fun existingForeignVaultIsDiscoveredWithoutWritingIntoIt()=service {server,client,connection ->
        server.directories+=setOf("/backup/pregnancy-journal","/backup/pregnancy-journal/foreign")
        server.files["/backup/pregnancy-journal/foreign/vault.json"]=byteArrayOf(8)
        val result=CloudProbe.run(client,connection,"local")
        assertEquals(1,result.existingVaultCount);assertFalse(result.containsThisVault)
        assertArrayEquals(byteArrayOf(8),server.files["/backup/pregnancy-journal/foreign/vault.json"])
        assertTrue(server.requests.filter {it.first in setOf("PUT","MKCOL","DELETE")}.none {it.second.contains("/pregnancy-journal/")})
    }
    @Test fun incorrectGetCannotPassAndStillCleansTheProbe()=service {server,client,connection ->
        server.corruptDownload=true;var cleaned=false
        assertTrue(runCatching {CloudProbe.run(client,connection,"local",afterCleanup={cleaned=true})}.isFailure)
        assertTrue(cleaned);assertTrue(server.files.isEmpty());assertEquals(setOf("/backup"),server.directories)
    }
    @Test fun probeDirectoryNameStaysWithinProviderLengthLimit()=service {server,client,connection ->
        // 坚果云等对目录名有长度上限（实测 ≤28 字符可建，≥32 返回 400 "sandbox name is too long"）。
        // 早期 "yunji-probe-<uuid>"（48 字符）会让连接测试必然失败。
        CloudProbe.run(client,connection,"local")
        val mkcol=server.requests.filter {it.first=="MKCOL"}.map {it.second.substringAfterLast('/')}
        assertTrue("探测目录名应存在",mkcol.isNotEmpty())
        assertTrue("目录名过长会触发服务商 400：$mkcol",mkcol.all {it.length<=28})
    }
    @Test fun cleanupFailureRemainsVisibleAndHeadIsOptional()=service {server,client,connection ->
        server.denyDelete=true;server.noHead=true
        val result=CloudProbe.run(client,connection,"local")
        assertTrue(result.verified && result.cleanupPending);assertFalse(result.canDelete || result.headAvailable)
        assertTrue(result.testName.startsWith("yjp-"));assertTrue(server.files.isNotEmpty())
    }
    @Test fun failedAuthenticationDoesNotCreateAnyTestObject()=service {server,client,connection ->
        server.denyAuth=true
        val error=runCatching {CloudProbe.run(client,connection,"local")}.exceptionOrNull()
        assertTrue(error is DavException && error.status==401)
        assertEquals(1,server.requests.size);assertTrue(server.requests.all {it.first=="PROPFIND"});assertTrue(server.files.isEmpty())
    }
    @Test fun serverRejectingRootFileWritesStillProbesInsideItsOwnDirectory()=service {server,client,connection ->
        // 坚果云行为：WebDAV 根目录直接 PUT 返回 404；若探测文件直接落在 folder 根，
        // 父目录留空的默认配置会被误判为“不可写”。探测必须写进自建子目录。
        server.rejectRootPut=true
        val rootConnection=CloudConnection(connection.endpoint,"synthetic","synthetic-secret")
        val result=CloudProbe.run(client,rootConnection,"local")
        assertTrue("应为可写并已完成回读校验",result.writable && result.verified)
        // 根目录（folder 为空）时探测发生在 pregnancy-journal 容器内，PUT 必须至少两段目录。
        val puts=server.requests.filter {it.first=="PUT"}
        assertTrue("PUT=$puts",puts.isNotEmpty() && puts.all {it.second.count {c->c=='/'}>=3})
        assertTrue(server.files.isEmpty())
    }
    @Test fun probeDirectoryIsNotHiddenSoItStaysDeletable()=service {server,client,connection ->
        // 以 "." 开头的目录在部分服务商上无法 DELETE（坚果云返回 403），会导致清理死锁。
        CloudProbe.run(client,connection,"local")
        assertTrue(server.requests.filter {it.first=="MKCOL"}.none {it.second.substringAfterLast('/').startsWith(".")})
    }
    private class Settings: CloudSettingsStorage {
        var saved: SavedCloudConnection?=null;var cleanup: ProbeCleanup?=null;var limit: CloudCooldown?=null
        override fun load()=saved
        override fun save(value: SavedCloudConnection){saved=value}
        override fun pending()=cleanup
        override fun beginProbe(value: ProbeCleanup){cleanup=value}
        override fun probeCleaned(){cleanup=null}
        override fun disconnect(){saved=null;limit=null}
        override fun cooldown()=limit
        override fun saveCooldown(value: CloudCooldown){limit=value}
    }
    @Test fun repositoryRecoversOldProbeAndNeverSavesCredentialsDuringTesting()=service {server,_,connection ->runBlocking {
        val settings=Settings();val old=SavedCloudConnection(connection=connection,capabilities=CloudCapabilities(),testedAt=1);settings.saved=old
        val repo=CloudConnectionRepository(settings,"local",java.time.Clock.systemUTC()){server.factory()}
        server.denyDelete=true;val first=repo.probe(connection)
        assertTrue(first.cleanupPending);assertNotNull(settings.pending());assertEquals(old,settings.saved)
        server.denyDelete=false;val second=repo.probe(connection)
        assertTrue(second.verified && !second.cleanupPending);assertNull(settings.pending());assertTrue(server.files.isEmpty());assertEquals(old,settings.saved)
    }}
    @Test fun leftoverProbeDirectoryThatProviderRefusesToDeleteStillAllowsRetest()=service {server,_,connection ->runBlocking {
        // 坚果云对根目录下的 DELETE 恒返回 403：残留的空探测目录不该让用户卡在
        // “必须清理才能重测”的死锁里。第二次测试必须能正常跑完读写校验。
        val settings=Settings()
        val repo=CloudConnectionRepository(settings,"local",java.time.Clock.systemUTC()){server.factory()}
        server.denyDirectoryDelete=true
        val first=repo.probe(connection)
        assertTrue("首次应完成读写校验",first.verified)
        assertTrue("根目录无法删目录时应如实报告 canDelete=false",!first.canDelete)

        // 关键：第二次 probe 不应因旧残留而抛异常。
        val second=repo.probe(connection)
        assertTrue("残留不可删时仍应完成读写校验",second.verified)
        assertTrue("第二次也应如实报告 canDelete=false",!second.canDelete)
    }}
    @Test fun retryAfterSurvivesRepositoryRecreationWithoutAnotherRequest()=service {server,_,connection ->runBlocking {
        val settings=Settings();val clock=java.time.Clock.systemUTC();server.limited=true
        val first=CloudConnectionRepository(settings,"local",clock){server.factory()}
        val error=runCatching {first.probe(connection)}.exceptionOrNull()
        assertTrue(error is DavException && error.status==429 && error.retryAfterSeconds==120L)
        assertNotNull(settings.cooldown());val requests=server.requests.size
        val restarted=CloudConnectionRepository(settings,"local",clock){server.factory()}
        val repeated=runCatching {restarted.probe(connection)}.exceptionOrNull()
        assertTrue(repeated is DavException && repeated.status==429);assertEquals(requests,server.requests.size)
    }}
}
