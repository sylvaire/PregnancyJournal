package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.backup.SafetyCopyStore
import cn.yunji.journal.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class SafetyCopyExporterTest {
    @Test fun realContentDestinationMatchesCiphertextAndOccupiedOrStaleTargetsArePreserved()=runBlocking {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val root=File(base.cacheDir,"safety-export-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object: ContextWrapper(base){override fun getApplicationContext(): Context=this;override fun getNoBackupFilesDir()=root}
        val directory=File(root,"vault/restore-safety").apply {mkdirs()}
        val source=File(directory,"${UUID.randomUUID()}.yunji").apply {writeBytes(ByteArray(150000){(it%251).toByte()})}
        val bytes=source.readBytes();val copy=SafetyCopyStore(File(root,"vault")).list().single()
        val target=TestDestinations.empty(base,"Yunji-Safety-Test-${UUID.randomUUID()}.yunji")
        try {
            // Production destinations come from CreateDocument: the document already exists and is empty.
            
            val exporter=SafetyCopyExporter(context)
            exporter.export(copy,target)
            assertArrayEquals(bytes,requireNotNull(base.contentResolver.openInputStream(target)).use {it.readBytes()})
            assertArrayEquals(bytes,source.readBytes())
            assertTrue(runCatching {exporter.export(copy,target)}.exceptionOrNull() is BackupDestinationOccupiedException)
            assertArrayEquals(bytes,requireNotNull(base.contentResolver.openInputStream(target)).use {it.readBytes()})
            source.appendText("changed")
            assertTrue(runCatching {exporter.export(copy,target)}.isFailure)
            assertArrayEquals(bytes,requireNotNull(base.contentResolver.openInputStream(target)).use {it.readBytes()})

            // An unreadable destination must fail closed instead of silently overwriting unknown content.
            val removed=TestDestinations.empty(base,"Yunji-Safety-Gone-${UUID.randomUUID()}.yunji")
            TestDestinations.delete(base,removed)
            val unreadable=runCatching {exporter.export(copy,removed)}
            assertTrue("A destination that cannot be read must not be reported as exported",unreadable.isFailure)
        }finally {TestDestinations.delete(base,target);root.deleteRecursively()}
    }
}
