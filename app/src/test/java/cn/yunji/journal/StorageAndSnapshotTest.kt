package cn.yunji.journal

import cn.yunji.journal.core.backup.ProbeSnapshot
import cn.yunji.journal.core.backup.SnapshotCodec
import cn.yunji.journal.core.database.PregnancyEntity
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.EncryptedFileStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageAndSnapshotTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun streamingFileRoundTripAndTamperDoesNotPublishPreview() {
        val vault = VaultMaterial("test-vault", VaultCrypto.randomKey())
        val store = EncryptedFileStore(temp.newFolder("objects"), vault)
        val plain = ByteArray(150_000) { (it % 251).toByte() }
        val objectInfo = store.put(plain.inputStream())
        val output = java.io.File(temp.root, "preview")
        store.decryptTo(objectInfo, output)
        assertArrayEquals(plain, output.readBytes())
        val damaged = store.path(objectInfo.id).readBytes()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        store.path(objectInfo.id).writeBytes(damaged)
        val badPreview = java.io.File(temp.root, "bad-preview")
        assertTrue(runCatching { store.decryptTo(objectInfo, badPreview) }.isFailure)
        assertFalse(badPreview.exists())
        assertFalse(temp.root.listFiles().orEmpty().any { it.name.endsWith("partial") })
    }
    @Test fun fileIdentityRejectsPathTraversal() {
        val store = EncryptedFileStore(temp.newFolder(), VaultMaterial("v", VaultCrypto.randomKey()))
        assertTrue(runCatching { store.path("../secret") }.isFailure)
    }
    @Test fun snapshotRestoresWithoutOriginalDeviceKeyAndRejectsWrongRecovery() {
        val vault = VaultMaterial("v", VaultCrypto.randomKey())
        val key = RecoveryKey.create()
        val snapshot = ProbeSnapshot(vaultId = "v", capturedSequence = 12,
            pregnancies = listOf(PregnancyEntity("p", "中文档案", createdAt = 1, updatedAt = 1)), dateRevisions = emptyList(), visits = emptyList())
        val encrypted = SnapshotCodec.encode(snapshot, vault, key)
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("中文档案"))
        assertEquals(snapshot, SnapshotCodec.decode(encrypted, key))
        assertTrue(runCatching { SnapshotCodec.decode(encrypted, RecoveryKey.create()) }.isFailure)
        assertTrue(runCatching { SnapshotCodec.decode(encrypted.copyOf(encrypted.size - 1), key) }.isFailure)
    }
}
