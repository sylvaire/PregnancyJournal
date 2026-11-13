package cn.yunji.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Static wiring checks: the share provider must get its own authority, a read-grant-only provider
 * declaration and a paths file that exposes exactly `filesDir/share` (never the vault or cache).
 */
class ShareWiringTest {
    private fun source(relative: String): File {
        val direct = File(relative)
        if (direct.isFile) return direct
        val fallback = File("app/$relative")
        assertTrue("缺少 $relative（工作目录 ${File(".").absolutePath}）", fallback.isFile)
        return fallback
    }

    @Test
    fun pathsXmlExposesOnlyTheTemporaryShareDirectory() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source("src/main/res/xml/share_paths.xml"))
        val root = document.documentElement
        assertEquals("paths", root.tagName)
        val elements = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE }
        assertEquals(1, elements.size)
        val entry = elements.single()
        assertEquals("files-path", entry.nodeName)
        assertEquals("share", entry.attributes.getNamedItem("name").nodeValue)
        assertEquals("share/", entry.attributes.getNamedItem("path").nodeValue)
        listOf("cache-path", "external-path", "external-files-path", "external-cache-path", "root-path").forEach { forbidden ->
            assertEquals("不得暴露 $forbidden", 0, (0 until root.childNodes.length).count { root.childNodes.item(it).nodeName == forbidden })
        }
    }

    @Test
    fun manifestDeclaresTheShareProviderNonExportedWithReadGrantsOnly() {
        val manifest = source("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:authorities=\"\${applicationId}.share\""))
        assertTrue(manifest.contains("cn.yunji.journal.core.share.ShareFileProvider"))
        assertTrue(manifest.contains("@xml/share_paths"))
        val block = manifest.substring(manifest.indexOf("cn.yunji.journal.core.share.ShareFileProvider")).substringBefore("</provider>")
        assertTrue(block.contains("android:exported=\"false\""))
        assertTrue(block.contains("android:grantUriPermissions=\"true\""))
        assertFalse(block.contains("android:permission="))
        assertFalse(block.contains(".capture"))
        // The existing camera provider must stay untouched and separate.
        assertTrue(manifest.contains("android:authorities=\"\${applicationId}.capture\""))
        assertTrue(manifest.contains("@xml/capture_paths"))
    }
}
