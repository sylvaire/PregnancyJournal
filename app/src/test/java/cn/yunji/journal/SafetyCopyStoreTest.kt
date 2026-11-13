package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID

class SafetyCopyStoreTest {
    private fun fixture(block: (File,File,SafetyCopyStore)->Unit) {
        val vault=Files.createTempDirectory("safety-store-").toFile()
        val root=File(vault,"restore-safety").apply {mkdirs()}
        try {block(vault,root,SafetyCopyStore(vault) {})}finally {vault.deleteRecursively()}
    }
    private fun archive(root: File)=File(root,"${UUID.randomUUID()}.yunji").apply {writeBytes(ByteArray(140000){(it%251).toByte()})}
    @Test fun discoversOnlyCompletedRegularArchivesAndCopiesExactBytes()=fixture {_,root,store->
        val file=archive(root)
        File(root,"${UUID.randomUUID()}.pending").writeText("partial")
        File(root,"unknown.yunji").writeText("unrelated")
        File(root,"${UUID.randomUUID()}.yunji").mkdir()
        val copy=store.list().single();val out=ByteArrayOutputStream()
        val result=store.copyTo(copy,out)
        assertArrayEquals(file.readBytes(),out.toByteArray());assertEquals(file.length(),result.size)
        assertEquals(64,result.sha256.length);assertTrue(file.isFile)
    }
    @Test fun staleSelectionCannotDeleteOrExportChangedFile()=fixture {_,root,store->
        val file=archive(root);val copy=store.list().single();file.appendText("changed")
        assertTrue(runCatching {store.delete(copy)}.isFailure)
        assertTrue(runCatching {store.copyTo(copy,ByteArrayOutputStream())}.isFailure)
        assertTrue(file.exists())
    }
    @Test fun anyPendingRestoreBlocksListingCopyAndDeletion()=fixture {vault,root,store->
        val file=archive(root);val copy=store.list().single()
        File(vault,"restore-journal.json").writeText("even unreadable pending state")
        assertTrue(runCatching {store.list()}.isFailure)
        assertTrue(runCatching {store.delete(copy)}.isFailure)
        assertTrue(runCatching {store.copyTo(copy,ByteArrayOutputStream())}.isFailure)
        assertTrue(file.exists())
    }
    @Test fun explicitSelectionDeletesOnlySelectedCopy()=fixture {_,root,store->
        val first=archive(root);val second=archive(root)
        val copy=store.list().single {it.id==first.nameWithoutExtension}
        store.delete(copy);assertFalse(first.exists());assertTrue(second.exists())
        assertEquals(1,store.list().size)
        assertTrue(runCatching {store.delete(copy.copy(id="../outside"))}.isFailure)
        assertTrue(second.exists())
    }
    @Test fun cancelledCopyKeepsSourceAndOtherFiles()=fixture {_,root,store->
        val first=archive(root);val second=archive(root);var checks=0
        val copy=store.list().single {it.id==first.nameWithoutExtension}
        assertTrue(runCatching {store.copyTo(copy,ByteArrayOutputStream()){if(++checks==2)error("Synthetic cancellation")}}.isFailure)
        assertTrue(first.exists() && second.exists())
    }
}
