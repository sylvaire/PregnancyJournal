package cn.yunji.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.backup.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class SafetyCopyStoreDeviceTest {
    @Test fun actualDirectorySyncAndSymlinkGuardsPreserveUnrelatedFiles() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val vault=File(context.cacheDir,"safety-store-${UUID.randomUUID()}").apply {mkdirs()}
        val root=File(vault,"restore-safety").apply {mkdirs()}
        val unrelated=File(vault,"unrelated").apply {writeText("synthetic untouched bytes")}
        try {
            val archive=File(root,"${UUID.randomUUID()}.yunji").apply {writeText("synthetic encrypted-copy stand-in")}
            val link=File(root,"${UUID.randomUUID()}.yunji")
            Files.createSymbolicLink(link.toPath(),unrelated.toPath())
            val store=SafetyCopyStore(vault)
            val copy=store.list().single()
            assertEquals(archive.nameWithoutExtension,copy.id)
            assertTrue(runCatching {store.delete(SafetyCopy(link.nameWithoutExtension,unrelated.length(),unrelated.lastModified()))}.isFailure)
            store.delete(copy)
            assertFalse(archive.exists());assertTrue(unrelated.exists());assertTrue(Files.isSymbolicLink(link.toPath()))
            Files.delete(link.toPath());assertTrue(root.delete())
            Files.createSymbolicLink(root.toPath(),vault.toPath())
            assertTrue(runCatching {store.list()}.isFailure)
            assertTrue(unrelated.exists())
        }finally {
            if(Files.isSymbolicLink(root.toPath()))Files.delete(root.toPath())
            root.listFiles().orEmpty().filter {Files.isSymbolicLink(it.toPath())}.forEach {Files.delete(it.toPath())}
            vault.deleteRecursively()
        }
    }
}
