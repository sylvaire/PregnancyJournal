package cn.yunji.journal

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException

/** Synthetic streams only; stress tests are restricted to the dedicated emulator model. */
@LargeTest
class LargeBackupArchiveTest {
    private class Fixture: AutoCloseable {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.cacheDir,"large-backup-${UUID.randomUUID()}").apply {mkdirs()}
        val material=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        val recovery=RecoveryKey.create();val slot=RecoverySlots.create(material,recovery)
        val store=EncryptedFileStore(File(root,"source"),material)
        fun create(size: Long): Pair<File,StoredObject> {
            val input=object: InputStream(){
                var remaining=size
                override fun read(): Int=if(remaining-- > 0)42 else -1
                override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
                    if(length==0)return 0
                    if(remaining<=0)return -1
                    val count=minOf(remaining,length.toLong()).toInt();java.util.Arrays.fill(bytes,offset,offset+count,42.toByte());remaining-=count;return count
                }
            }
            val objectInfo=store.put(input)
            val pregnancy=PregnancyEntity("p","合成大文件档案",createdAt=1,updatedAt=1)
            val visit=VisitEntity("visit",pregnancy.id,"2026-10-20",zoneId="UTC",createdAt=1,updatedAt=1)
            val attachment=AttachmentEntity(objectInfo.id,visit.id,"合成大原件","application/pdf",objectInfo.plainSize,objectInfo.cipherSize,objectInfo.plainSha256,objectInfo.cipherSha256,null,null,null,null,null,1)
            val snapshot=LogicalSnapshot(vaultId=material.id,snapshotId=UUID.randomUUID().toString(),writerId=UUID.randomUUID().toString(),writerSequence=1,capturedSequence=0,createdAt=1,
                pregnancies=listOf(pregnancy),visits=listOf(visit),attachments=listOf(attachment))
            val archive=File(root,"large.yunji")
            LocalBackupArchive.write(snapshot,material,slot,store,archive)
            return archive to objectInfo
        }
        override fun close(){material.master.fill(0);root.deleteRecursively()}
    }
    @Test fun thirtyTwoMiBOriginalAuthenticatesAndRestoresFromStreamedArchive()=runBlocking {verifySize(32L*1024*1024)}
    @Test fun hundredMiBSupportedLimitAuthenticatesAndRestoresFromStreamedArchive()=runBlocking {verifySize(100L*1024*1024)}
    private suspend fun verifySize(size: Long) {
        assumeTrue(Build.MODEL in setOf("sdk_gphone64_x86_64","sdk_gphone16k_x86_64") ||
            (Build.VERSION.SDK_INT==26 && Build.MODEL=="Android SDK built for x86_64"))
        Fixture().use {f->
            val started=SystemClock.elapsedRealtime()
            val (archive,info)=f.create(size)
            LocalBackupArchive.verify(archive,f.recovery,f.root).use {verified->
                IsolatedRestore.withDatabase(f.context,verified){db->
                    val a=requireNotNull(db.journalDao().attachment(info.id));assertEquals(size,a.plainSize)
                    assertEquals(info.plainSha256,a.plainSha256)
                    val plain=File(verified.directory,"read-check")
                    try {EncryptedFileStore(File(verified.directory,"objects"),verified.material).decryptTo(info,plain);assertEquals(size,plain.length())}finally {plain.delete()}
                }
            }
            assertTrue(f.root.listFiles().orEmpty().none {it.name.startsWith("backup-verify-")})
            println("Synthetic archive bytes=$size elapsedMs=${SystemClock.elapsedRealtime()-started} maxHeapBytes=${Runtime.getRuntime().maxMemory()}")
        }
    }
    @Test fun cancellationInsideGcmAuthenticationRemovesArchiveStagingButKeepsSource() {
        assumeTrue(Build.MODEL in setOf("sdk_gphone64_x86_64","sdk_gphone16k_x86_64") ||
            (Build.VERSION.SDK_INT==26 && Build.MODEL=="Android SDK built for x86_64"))
        Fixture().use {f->
            val (archive,info)=f.create(1024L*1024)
            var cancelledInsideAuthentication=false
            val failure=runCatching {LocalBackupArchive.verify(archive,f.recovery,f.root){
                if(f.root.listFiles().orEmpty().filter {it.name.startsWith("backup-verify-")}.any {directory->directory.listFiles().orEmpty().any {it.name.startsWith("authenticated-original.") && it.name.endsWith(".partial")}}) {
                    cancelledInsideAuthentication=true;throw CancellationException()
                }
            }}.exceptionOrNull()
            assertTrue(cancelledInsideAuthentication);assertTrue(failure is CancellationException)
            assertTrue(f.root.listFiles().orEmpty().none {it.name.startsWith("backup-verify-")})
            assertEquals(info.cipherSize,f.store.path(info.id).length())
            LocalBackupArchive.verify(archive,f.recovery,f.root).close()
        }
    }
}
