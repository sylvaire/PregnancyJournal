package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.network.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class CloudConnectionStoreTest {
    private class Fixture: AutoCloseable {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val root=File(base.cacheDir,"cloud-store-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object: ContextWrapper(base) {override fun getNoBackupFilesDir()=root}
        val material=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        val store=CloudConnectionStore(context,material)
        val file get()=File(root,"vault/cloud-connection.enc")
        val config=CloudConnection("https://synthetic.invalid/dav/","synthetic-account","synthetic-password",listOf("synthetic-folder"))
        override fun close(){material.master.fill(0);root.deleteRecursively()}
    }
    @Test fun credentialsAndEndpointAreEncryptedAndReopenWithSameVault() {
        Fixture().use {f ->
            val saved=SavedCloudConnection(connection=f.config,capabilities=CloudCapabilities(verified=true),testedAt=7)
            f.store.save(saved)
            val bytes=f.file.readBytes().toString(Charsets.ISO_8859_1)
            listOf("synthetic-account","synthetic-password","synthetic.invalid","synthetic-folder").forEach {assertFalse(bytes.contains(it))}
            assertEquals(saved,CloudConnectionStore(f.context,f.material).load())
            assertFalse(saved.toString().contains("synthetic-password"))
        }
    }
    @Test fun wrongVaultAndTamperingNeverOverwriteSavedConnection() {
        Fixture().use {f ->
            f.store.save(SavedCloudConnection(connection=f.config,capabilities=CloudCapabilities(),testedAt=1));val original=f.file.readBytes()
            val wrong=CloudConnectionStore(f.context,VaultMaterial(f.material.id,VaultCrypto.randomKey()))
            assertTrue(runCatching {wrong.load()}.isFailure);assertArrayEquals(original,f.file.readBytes())
            f.file.writeBytes(original.copyOf().apply {this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()})
            val damaged=f.file.readBytes();assertTrue(runCatching {f.store.load()}.isFailure);assertArrayEquals(damaged,f.file.readBytes())
        }
    }
    @Test fun cancelledProbeMarkerSurvivesAndDisconnectDoesNotTouchItOrMedicalFiles() {
        Fixture().use {f ->
            f.store.save(SavedCloudConnection(connection=f.config,capabilities=CloudCapabilities(),testedAt=1))
            val pending=ProbeCleanup(f.config.endpoint,f.config.username,f.config.folder,".yunji-probe-${UUID.randomUUID()}")
            f.store.beginProbe(pending)
            val medical=File(f.root,"vault/synthetic-medical.bin").apply {writeText("synthetic ciphertext")}
            f.store.disconnect();assertNull(f.store.load());assertEquals(pending,CloudConnectionStore(f.context,f.material).pending())
            assertEquals("synthetic ciphertext",medical.readText());f.store.probeCleaned();assertNull(f.store.pending())
        }
    }
    @Test fun revokedMasterCannotWriteNewCredentialsOverExistingConnection() {
        Fixture().use {f ->
            val saved=SavedCloudConnection(connection=f.config,capabilities=CloudCapabilities(),testedAt=1)
            f.store.save(saved);val before=f.file.readBytes();f.material.master.fill(0)
            assertTrue(runCatching {f.store.save(saved)}.isFailure);assertArrayEquals(before,f.file.readBytes())
        }
    }
    @Test fun rateLimitIsEncryptedAcrossReopenAndExplicitResetClearsIt() {
        Fixture().use {f ->
            val value=CloudCooldown("https://synthetic-private.invalid:443",12345)
            f.store.saveCooldown(value)
            assertEquals(value,CloudConnectionStore(f.context,f.material).cooldown())
            assertFalse(File(f.root,"vault/cloud-cooldown.enc").readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-private"))
            f.store.disconnect();assertNull(f.store.cooldown())
        }
    }
}
