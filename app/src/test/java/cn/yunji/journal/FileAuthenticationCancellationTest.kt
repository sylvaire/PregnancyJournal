package cn.yunji.journal

import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

class FileAuthenticationCancellationTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun cancellationDuringAuthenticationNeverPublishesPlaintextOrDamagesOriginal() {
        val root=temporary.newFolder();val material=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        try {
            val store=EncryptedFileStore(File(root,"objects"),material)
            val original=store.put(ByteArray(1024*1024){(it%251).toByte()}.inputStream())
            val destination=File(root,"plain");var blocks=0
            assertTrue(runCatching {store.decryptTo(original,destination,checkpoint={if(++blocks==4)throw CancellationException()})}.exceptionOrNull() is CancellationException)
            assertFalse(destination.exists());assertTrue(root.listFiles().orEmpty().none {it.name.endsWith(".partial")})
            store.decryptTo(original,destination)
            assertEquals(original.plainSize,destination.length())
            assertEquals(original.cipherSize,store.path(original.id).length())
        }finally {material.master.fill(0)}
    }
    @Test fun cancellationImmediatelyBeforePublicationAlsoRemovesAuthenticatedTemporaryFile() {
        val root=temporary.newFolder();val material=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        try {
            val store=EncryptedFileStore(File(root,"objects"),material)
            val bytes=byteArrayOf(1,2,3);val original=store.put(bytes.inputStream());val destination=File(root,"plain")
            assertTrue(runCatching {store.decryptTo(original,destination,checkpoint={
                if(root.listFiles().orEmpty().any {it.name.endsWith(".partial") && it.length()==bytes.size.toLong()})throw CancellationException()
            })}.isFailure)
            assertFalse(destination.exists());assertTrue(root.listFiles().orEmpty().none {it.name.endsWith(".partial")})
        }finally {material.master.fill(0)}
    }
}
