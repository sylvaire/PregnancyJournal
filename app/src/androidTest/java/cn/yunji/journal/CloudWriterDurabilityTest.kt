package cn.yunji.journal

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.backup.CloudWriterStore
import cn.yunji.journal.core.security.VaultMaterial
import cn.yunji.journal.data.AndroidCloudWriter
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class CloudWriterDurabilityTest {
    private fun fixture(block: (File,VaultMaterial)->Unit) {
        assumeTrue(Build.MODEL=="sdk_gphone64_x86_64" && Build.VERSION.SDK_INT==34)
        val context=ApplicationProvider.getApplicationContext<Context>()
        val directory=File(context.noBackupFilesDir,"writer-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        val vault=VaultMaterial(UUID.randomUUID().toString(),ByteArray(32){(it+1).toByte()})
        try {AndroidCloudWriter.syncDirectory(context.noBackupFilesDir);block(directory,vault)}finally {vault.master.fill(0);directory.deleteRecursively()}
    }
    private fun store(directory: File,vault: VaultMaterial)=CloudWriterStore(directory,vault,AndroidCloudWriter::syncDirectory)
    @Test fun actualAndroidDirectorySyncAndReopenKeepSequence()=fixture {dir,vault->
        val first=store(dir,vault).reserve();val next=store(dir,vault).reserve()
        assertEquals(first.writerId,next.writerId);assertEquals(2,next.sequence)
        assertFalse(File(dir,"cloud-writer.pending").exists())
    }
    @Test fun actualFilesystemConcurrentReservationsAreDistinct()=fixture {dir,vault->
        val pool=Executors.newFixedThreadPool(4)
        try {
            val reservations=pool.invokeAll((1..20).map {Callable {store(dir,vault).reserve()}}).map {it.get()}
            assertEquals(1,reservations.map {it.writerId}.distinct().size)
            assertEquals((1L..20L).toList(),reservations.map {it.sequence}.sorted())
        }finally {pool.shutdownNow()}
    }
}