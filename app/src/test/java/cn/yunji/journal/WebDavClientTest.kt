package cn.yunji.journal

import cn.yunji.journal.core.network.DavException
import cn.yunji.journal.core.network.WebDavClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class WebDavClientTest {
    private fun withServer(username: String="test",password: String="not-a-real-secret",http1: Boolean=false,block: (MockWebServer, WebDavClient) -> Unit) {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            val http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).apply {if(http1)protocols(listOf(okhttp3.Protocol.HTTP_1_1))}.build()
            val endpoint = server.url("/backup/").newBuilder().host("localhost").build()
            block(server, WebDavClient(endpoint, username, password, http))
        }
    }
    @Test fun clientAlwaysNegotiatesHttp11() {
        // 真机实测：HTTP/2 与坚果云组合会出现 Http2Stream 流级超时，PUT 长期挂起且 callTimeout 不触发。
        // 客户端必须固定使用 HTTP/1.1，不依赖对端协商。
        val protocols = mutableListOf<okhttp3.Protocol>()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start()
            server.enqueue(MockResponse().setResponseCode(207).setBody(
                "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/backup/</d:href><d:propstat><d:prop>" +
                    "<d:resourcetype><d:collection/></d:resourcetype></d:prop>" +
                    "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"))
            val http = OkHttpClient.Builder()
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .addInterceptor { chain ->
                    chain.proceed(chain.request()).also { protocols += it.protocol }
                }
                .build()
            val endpoint = server.url("/backup/").newBuilder().host("localhost").build()
            WebDavClient(endpoint, "test", "not-a-real-secret", http).use { client -> client.probe() }
            assertEquals("客户端应固定使用 HTTP/1.1", listOf(okhttp3.Protocol.HTTP_1_1), protocols)
        }
    }

    @Test fun handlesMultiStatusAndAuthenticationFailure() = withServer { server, client ->
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/backup/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"))
        assertEquals(207, client.probe())
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method); assertEquals("0", request.getHeader("Depth"))
        server.enqueue(MockResponse().setResponseCode(401))
        val e = runCatching { client.probe() }.exceptionOrNull()
        assertTrue(e is DavException && e.status == 401)
    }
    @Test fun refusesRedirectsInsteadOfLeakingCredentials() = withServer { server, client ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.invalid/"))
        assertTrue(runCatching { client.probe() }.exceptionOrNull() is DavException)
        assertEquals(1, server.requestCount)
    }
    @Test fun refusesOversizedAndUnsafePaths() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("12345"))
        assertTrue(runCatching { client.get(listOf("object"), 4) }.isFailure)
        assertTrue(runCatching { client.get(listOf(".."), 100) }.isFailure)
    }
    private fun collection(href: String="/backup/",code: Int=200)=
        "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 $code test</d:status></d:propstat></d:response>"
    private fun xml(vararg responses: String)="<d:multistatus xmlns:d=\"DAV:\">${responses.joinToString("")}</d:multistatus>"

    @Test fun successfulUnrelatedPropertyCannotHideForbiddenTargetCollection() = withServer {server,client ->
        val forbidden=collection(code=403).replace("</d:response>","<d:propstat><d:prop><d:getetag>synthetic-etag</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        server.enqueue(MockResponse().setResponseCode(207).setBody(xml(forbidden)))
        val error=runCatching {client.probe()}.exceptionOrNull()
        assertTrue(error is DavException && error.status==403)
        assertNotEquals(DavException(401).message,DavException(403).message)
    }
    @Test fun listKeepsFailedChildrenAndRequestsOnlyOneLevel() = withServer {server,client ->
        server.enqueue(MockResponse().setResponseCode(207).setBody(xml(collection(),collection("/backup/denied/",403))))
        val entries=client.list();assertEquals(2,entries.size);assertEquals(403,entries.single {it.segments==listOf("denied")}.status)
        val request=server.takeRequest();assertEquals("1",request.getHeader("Depth"));assertEquals("PROPFIND",request.method)
    }
    @Test fun crossOriginHrefAndEscapedRootAreRejected() = withServer {server,client ->
        for(href in listOf("https://example.invalid/backup/intruder/","/backup/%2e%2e/private/child/","/backup/escaped%2Ffolder/")) {
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml(collection(),collection(href))))
            assertTrue(runCatching {client.list()}.isFailure)
        }
    }
    @Test fun malformedMissingAndDuplicateCollectionsAreRejected() = withServer {server,client ->
        for(body in listOf(xml(),xml(collection(),collection()),"<multistatus/>",xml("<d:response><d:href>/backup/</d:href><d:status>bad status</d:status></d:response>"))) {
            server.enqueue(MockResponse().setResponseCode(207).setBody(body));assertTrue(runCatching {client.probe()}.isFailure)
        }
    }
    @Test fun dtdAndExcessiveXmlNestingAreRejected() = withServer {server,client ->
        val dtd="<!DOCTYPE d:multistatus [<!ENTITY x SYSTEM 'file:///not-readable'>]>"+xml(collection())
        val nested="<d:multistatus xmlns:d=\"DAV:\">"+"<d:x>".repeat(40)+"</d:x>".repeat(40)+"</d:multistatus>"
        for(body in listOf(dtd,nested)) {server.enqueue(MockResponse().setResponseCode(207).setBody(body));assertTrue(runCatching {client.probe()}.isFailure)}
    }
    @Test fun depthZeroCannotAcceptChildInsteadOfTargetCollection() = withServer {server,client ->
        server.enqueue(MockResponse().setResponseCode(207).setBody(xml(collection("/backup/child/"))))
        assertTrue(runCatching {client.probe()}.isFailure)
    }
    @Test fun closeCancelsAnAlreadyStreamingResponseBody() = withServer {server,client ->
        server.enqueue(MockResponse().setBody("x".repeat(256)).throttleBody(1,500,java.util.concurrent.TimeUnit.MILLISECONDS))
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result=executor.submit<Boolean> {runCatching {client.get(listOf("slow"),1024)}.isFailure}
            assertNotNull(server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS));Thread.sleep(150)
            client.close();assertTrue(result.get(5,java.util.concurrent.TimeUnit.SECONDS))
            val requests=server.requestCount;assertTrue(runCatching {client.probe()}.isFailure);assertEquals(requests,server.requestCount)
        } finally {executor.shutdownNow()}
    }
    @Test fun retryAfterHttpDateIsParsedWithoutRetrying()=withServer {server,client ->
        val date=java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(90).format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After",date))
        val error=runCatching {client.probe()}.exceptionOrNull()
        assertTrue(error is DavException && error.retryAfterSeconds in 80L..90L);assertEquals(1,server.requestCount)
    }
    @Test fun nonAsciiCredentialsAreNotLossilyReplacedInAuthorizationHeader()=withServer(username="合成账号",password="合成密码") {server,client ->
        server.enqueue(MockResponse().setResponseCode(401));runCatching {client.probe()}
        val value=requireNotNull(server.takeRequest().getHeader("Authorization")).removePrefix("Basic ")
        assertEquals("合成账号:合成密码",java.util.Base64.getDecoder().decode(value).toString(Charsets.UTF_8))
    }
    private fun sha(bytes: ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    @Test fun streamsFileAndDownloadsForContentVerification()=withServer(http1=true) {server,client->
        val bytes=ByteArray(2*1024*1024+17){(it%251).toByte()}
        val file=java.io.File.createTempFile("dav-stream",".bin")
        try {
            file.writeBytes(bytes);server.enqueue(MockResponse().setResponseCode(201))
            client.putFile(listOf("objects","random.bin"),file,bytes.size.toLong(),sha(bytes))
            val request=server.takeRequest();assertEquals("PUT",request.method);assertEquals("*",request.getHeader("If-None-Match"))
            assertArrayEquals(bytes,request.body.readByteArray())
            server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(bytes),8192))
            client.verifyDownloaded(listOf("objects","random.bin"),bytes.size.toLong(),sha(bytes))
            assertEquals("identity",server.takeRequest().getHeader("Accept-Encoding"))
        }finally {file.delete();client.close()}
    }
    @Test fun sameLengthCorruptionTruncationAndExtraBytesFailReadback()=withServer {server,client->
        val expected="correct-object".toByteArray()
        for(actual in listOf("corrupt-object","short","correct-object-extra")) {
            server.enqueue(MockResponse().setBody(actual))
            assertTrue(runCatching {client.verifyDownloaded(listOf("object"),expected.size.toLong(),sha(expected))}.isFailure)
        }
    }
    @Test fun readbackCancellationAndAuthenticationFailureCannotSucceed()=withServer {server,client->
        val bytes=ByteArray(200000){7}
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        var checks=0
        assertTrue(runCatching {client.verifyDownloaded(listOf("object"),bytes.size.toLong(),sha(bytes)){if(++checks==2)throw java.util.concurrent.CancellationException()}}.isFailure)
        assertEquals(2,checks)
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching {client.verifyDownloaded(listOf("object"),bytes.size.toLong(),sha(bytes))}.exceptionOrNull() is DavException)
    }
    @Test fun fileMutationAndExistingTargetAreNotReportedAsUploaded()=withServer {server,client->
        val bytes="synthetic-ciphertext".toByteArray();val file=java.io.File.createTempFile("dav-source",".bin")
        try {
            file.writeBytes(bytes);server.enqueue(MockResponse().setResponseCode(412))
            val error=runCatching {client.putFile(listOf("existing"),file,bytes.size.toLong(),sha(bytes))}.exceptionOrNull()
            assertTrue(error is DavException && error.status==412)
            server.takeRequest()
            file.writeBytes(bytes.copyOf(bytes.size-1))
            assertTrue(runCatching {client.putFile(listOf("new"),file,bytes.size.toLong(),sha(bytes))}.isFailure)
            assertEquals(1,server.requestCount)
        }finally {file.delete()}
    }    @Test fun http2BinaryReadbackUsesActualBytesWithoutChunkFraming()=withServer {server,client->
        val bytes=ByteArray(200003){(it%199).toByte()}
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        client.verifyDownloaded(listOf("h2-object"),bytes.size.toLong(),sha(bytes))
        assertEquals("GET",server.takeRequest().method)
    }}
