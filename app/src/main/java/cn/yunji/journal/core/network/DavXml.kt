package cn.yunji.journal.core.network

import okhttp3.HttpUrl
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory

data class DavEntry(val segments: List<String>,val collection: Boolean,val status: Int,val size: Long?=null,val etag: String?=null,val lastModified: Long?=null)

/** Scope and resource status are facts of each response, never of an unrelated propstat. */
internal object DavXml {
    private const val DAV="DAV:"
    fun parse(bytes: ByteArray,base: HttpUrl,requested: List<String>,depth: Int): List<DavEntry> {
        require(bytes.size<=2*1024*1024 && depth in 0..1) {"云盘目录响应超过限制"}
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require('\u0000' !in text && !text.contains("<!DOCTYPE",ignoreCase=true) && !text.contains("<!ENTITY",ignoreCase=true)) {"云盘XML包含不安全声明"}
        // Bound nesting before allocating the DOM, including on Android parsers without this optional feature.
        val sax=SAXParserFactory.newInstance().apply {isNamespaceAware=true;isValidating=false}
        runCatching {sax.setFeature("http://xml.org/sax/features/external-general-entities",false)}
        runCatching {sax.setFeature("http://xml.org/sax/features/external-parameter-entities",false)}
        var nesting=0;var nodes=0
        sax.newSAXParser().parse(InputSource(StringReader(text)),object: DefaultHandler() {
            override fun startElement(uri: String?,localName: String?,qName: String?,attributes: org.xml.sax.Attributes?) {
                if(++nesting>32 || ++nodes>20_000)throw SAXException("云盘XML结构超过限制")
            }
            override fun endElement(uri: String?,localName: String?,qName: String?) {nesting--}
            override fun resolveEntity(publicId: String?,systemId: String?): InputSource = throw SAXException("不允许外部实体")
        })
        val factory=DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true;isValidating=false;isExpandEntityReferences=false}
        runCatching {factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}
        runCatching {factory.setFeature("http://xml.org/sax/features/external-general-entities",false)}
        runCatching {factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false)}
        val builder=factory.newDocumentBuilder().apply {setEntityResolver {_,_->throw SAXException("不允许外部实体")}}
        val root=builder.parse(InputSource(StringReader(text))).documentElement
        require(root.namespaceURI==DAV && root.localName=="multistatus") {"云盘未返回DAV目录"}
        val baseParts=base.pathSegments.dropLastWhile(String::isEmpty)
        val results=root.children("response").map {response ->
            val hrefs=response.children("href");require(hrefs.size==1) {"云盘目录缺少唯一资源路径"}
            val href=hrefs.single().textContent.trim();require(href.length in 1..8192)
            val target=requireNotNull(base.resolve(href)) {"云盘返回无效资源路径"}
            require(target.scheme==base.scheme && target.host==base.host && target.port==base.port && target.username.isEmpty() && target.password.isEmpty() && target.query==null && target.fragment==null) {"云盘返回的资源不在目标服务内"}
            val parts=target.pathSegments.dropLastWhile(String::isEmpty)
            require(parts.take(baseParts.size)==baseParts && parts.all {it!="." && it!=".." && '/' !in it && '\\' !in it && it.none(Char::isISOControl)}) {"云盘返回越界资源路径"}
            val relative=parts.drop(baseParts.size)
            require(relative.take(requested.size)==requested && relative.size in requested.size..(requested.size+depth)) {"云盘目录响应超出请求范围"}
            val directStatus=response.children("status").singleOrNull()?.textContent?.let(::status)
            val properties=response.children("propstat").map {part ->
                val code=status(part.children("status").singleOrNull()?.textContent ?: error("云盘属性缺少状态"))
                code to part.children("prop").singleOrNull()
            }
            val successful=properties.filter {it.first in 200..299}.mapNotNull {it.second}
            val resourceType=successful.flatMap {it.children("resourcetype")}.singleOrNull()
            val code=directStatus ?: properties.firstOrNull {it.first in 200..299 && it.second?.children("resourcetype")?.isNotEmpty()==true}?.first
                ?: properties.firstOrNull()?.first ?: error("云盘资源缺少状态")
            val length=successful.flatMap {it.children("getcontentlength")}.firstOrNull()?.textContent?.trim()?.toLongOrNull()?.takeIf {it>=0}
            val etag=successful.flatMap {it.children("getetag")}.firstOrNull()?.textContent?.trim()?.takeIf {it.length<=1024}
            // Only a well-formed HTTP date is accepted; anything else is treated as unknown age.
            val lastModified=successful.flatMap {it.children("getlastmodified")}.firstOrNull()?.textContent?.trim()
                ?.takeIf {it.length<=64}?.let {raw->runCatching {
                    java.time.ZonedDateTime.parse(raw,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                }.getOrNull()}
            DavEntry(relative,resourceType?.children("collection")?.isNotEmpty()==true,code,length,etag,lastModified)
        }
        require(results.size<=4096 && results.map {it.segments}.distinct().size==results.size) {"云盘目录重复或过大"}
        return results
    }
    private fun status(value: String): Int {
        val code=Regex("HTTP/\\d+(?:\\.\\d+)?\\s+(\\d{3})(?:\\s+.*)?").matchEntire(value.trim())?.groupValues?.get(1)?.toIntOrNull()
        return requireNotNull(code?.takeIf {it in 100..599}) {"云盘资源状态无效"}
    }
    private fun Element.children(name: String): List<Element> = (0 until childNodes.length).mapNotNull {childNodes.item(it) as? Element}
        .filter {it.namespaceURI==DAV && it.localName==name}
}
