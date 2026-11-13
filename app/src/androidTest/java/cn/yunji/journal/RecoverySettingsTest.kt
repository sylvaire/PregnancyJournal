package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.data.RecoverySettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class RecoverySettingsTest {
    @Test fun encryptedSlotSurvivesReopenWithoutPlainRecoveryOrPublicMetadata() {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val root=File(base.cacheDir,"recovery-test-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object: ContextWrapper(base){override fun getNoBackupFilesDir()=root}
        val material=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        try {
            val key=RecoveryKey.create();val slot=RecoverySlots.create(material,key)
            val store=RecoverySettings(context,material);assertNull(store.load());store.save(slot)
            val file=File(root,"vault/recovery-slot.enc");val bytes=file.readBytes()
            val text=bytes.toString(Charsets.ISO_8859_1)
            listOf(key,slot.vaultId,slot.wrappedMaster,"YJ-RECOVERY-1").forEach {assertFalse(text.contains(it))}
            assertEquals(slot,RecoverySettings(context,material).load())
            val wrong=RecoverySettings(context,VaultMaterial(material.id,VaultCrypto.randomKey()))
            assertTrue(runCatching {wrong.load()}.isFailure);assertArrayEquals(bytes,file.readBytes())
            file.writeBytes(bytes.copyOf().apply {this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()})
            assertTrue(runCatching {store.load()}.isFailure)
        }finally {material.master.fill(0);root.deleteRecursively()}
    }
}
