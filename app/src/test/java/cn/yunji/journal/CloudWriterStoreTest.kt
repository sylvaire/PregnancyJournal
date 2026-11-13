package cn.yunji.journal

import cn.yunji.journal.core.backup.CloudWriterStore
import cn.yunji.journal.core.security.VaultMaterial
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Callable

class CloudWriterStoreTest {
    private fun store(dir: File,vault: VaultMaterial)=CloudWriterStore(dir,vault) { /* JVM tests inject directory durability separately. */ }
    private fun fixture(block: (File,VaultMaterial)->Unit) {
        val dir=Files.createTempDirectory("writer-test").toFile();val vault=VaultMaterial(UUID.randomUUID().toString(),ByteArray(32){(it+1).toByte()})
        try {block(dir,vault)}finally {vault.master.fill(0);dir.deleteRecursively()}
    }
    @Test fun recreationKeepsIdentityAndConsumesFailedAttemptSequence()=fixture {dir,vault->
        val first=store(dir,vault).reserve()
        val next=store(dir,vault).reserve()
        assertEquals(first.writerId,next.writerId);assertEquals(1,first.sequence);assertEquals(2,next.sequence)
        assertFalse(File(dir,"cloud-writer.enc").readText().contains(first.writerId))
    }
    @Test fun concurrentInstancesIssueUniqueMonotonicReservations()=fixture {dir,vault->
        val executor=Executors.newFixedThreadPool(6)
        try {
            val values=executor.invokeAll((1..30).map {Callable {store(dir,vault).reserve()}}).map {it.get()}
            assertEquals(1,values.map {it.writerId}.distinct().size)
            assertEquals((1L..30L).toList(),values.map {it.sequence}.sorted())
            assertEquals(31,store(dir,vault).reserve().sequence)
        }finally {executor.shutdownNow()}
    }
    @Test fun corruptedOrWrongVaultStateStopsInsteadOfResetting()=fixture {dir,vault->
        store(dir,vault).reserve();val file=File(dir,"cloud-writer.enc");val original=file.readBytes()
        val corrupted=original.clone();corrupted[corrupted.lastIndex]=(corrupted.last().toInt() xor 1).toByte();file.writeBytes(corrupted)
        try {store(dir,vault).reserve();fail("Corrupt state reset")}catch(_:Exception){}
        assertArrayEquals(corrupted,file.readBytes())
        file.writeBytes(original)
        try {store(dir,VaultMaterial(UUID.randomUUID().toString(),vault.master.clone())).reserve();fail("Wrong vault accepted")}catch(_:Exception){}
        assertArrayEquals(original,file.readBytes())
    }
    @Test fun directorySyncFailureDoesNotReturnReservationOrReuseSequence()=fixture {dir,vault->
        val first=store(dir,vault).reserve()
        try {CloudWriterStore(dir,vault){throw java.io.IOException("Injected sync failure")}.reserve();fail("Unsynced reservation returned")}catch(_:java.io.IOException){}
        val next=store(dir,vault).reserve()
        assertEquals(first.writerId,next.writerId);assertEquals(3,next.sequence)
    }
    @Test fun wipedKeyCannotCreateWriterState()=fixture {dir,vault->
        vault.master.fill(0)
        try {store(dir,vault).reserve();fail("Wiped key accepted")}catch(_:IllegalArgumentException){}
        assertFalse(File(dir,"cloud-writer.enc").exists())
    }
    @Test fun abandonedEncryptedPendingDoesNotAdvanceCommittedState()=fixture {dir,vault->
        val first=store(dir,vault).reserve()
        File(dir,"cloud-writer.pending").writeBytes(ByteArray(300){42})
        val second=store(dir,vault).reserve()
        assertEquals(first.writerId,second.writerId);assertEquals(2,second.sequence)
        assertFalse(File(dir,"cloud-writer.pending").exists())
    }
}