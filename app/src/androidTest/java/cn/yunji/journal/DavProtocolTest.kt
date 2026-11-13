package cn.yunji.journal

import cn.yunji.journal.core.network.DavXml
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Android's XML implementation differs from the JVM test runtime. No network or credentials. */
class DavProtocolTest {
    private val base="https://example.invalid/backup/".toHttpUrl()
    @Test fun platformXmlParserRecognizesCollectionAndPerResourceFailure() {
        val xml="""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/backup/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>/backup/denied</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response></d:multistatus>"""
        val entries=DavXml.parse(xml.toByteArray(),base,emptyList(),1)
        assertTrue(entries.first().collection);assertEquals(403,entries.last().status)
    }
    @Test fun platformXmlParserRejectsEntitiesAndDeepTrees() {
        val bodies=listOf("<!DOCTYPE d:multistatus [<!ENTITY x SYSTEM 'file:///private'>]><d:multistatus xmlns:d=\"DAV:\"/>",
            "<d:multistatus xmlns:d=\"DAV:\">"+"<d:x>".repeat(40)+"</d:x>".repeat(40)+"</d:multistatus>")
        bodies.forEach {assertTrue(runCatching {DavXml.parse(it.toByteArray(),base,emptyList(),0)}.isFailure)}
    }
}
