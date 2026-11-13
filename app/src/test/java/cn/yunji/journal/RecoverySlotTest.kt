package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.security.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecoverySlotTest {
    private fun vault()=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
    @Test fun authenticatedRoundTripHasNoPlainRecoveryOrMasterKey() {
        val vault=vault();val recovery=RecoveryKey.create()
        val slot=RecoverySlots.create(vault,recovery)
        val bytes=RecoverySlots.encode(slot)
        val opened=RecoverySlots.open(RecoverySlots.decode(bytes),recovery)
        try {assertEquals(vault.id,opened.id);assertArrayEquals(vault.master,opened.master)} finally {opened.master.fill(0)}
        assertFalse(bytes.toString(Charsets.UTF_8).contains(recovery))
        assertFalse(bytes.toString(Charsets.UTF_8).contains(VaultCrypto.encode(vault.master)))
        assertFalse(slot.toString().contains(slot.wrappedMaster))
    }
    @Test fun wrongKeyIdentityAndCiphertextCannotUnwrapMaster() {
        val recovery=RecoveryKey.create();val slot=RecoverySlots.create(vault(),recovery)
        assertTrue(runCatching {RecoverySlots.open(slot,RecoveryKey.create())}.isFailure)
        assertTrue(runCatching {RecoverySlots.open(slot.copy(vaultId=UUID.randomUUID().toString()),recovery)}.isFailure)
        assertTrue(runCatching {RecoverySlots.open(slot.copy(slotId=UUID.randomUUID().toString()),recovery)}.isFailure)
        val bytes=VaultCrypto.decode(slot.wrappedMaster);bytes[20]=(bytes[20].toInt() xor 1).toByte()
        assertTrue(runCatching {RecoverySlots.open(slot.copy(wrappedMaster=VaultCrypto.encode(bytes)),recovery)}.isFailure)
    }
    @Test fun recreatedSlotHasIndependentIdentityAndRejectsUnsupportedOrUnboundedInput() {
        val vault=vault();val key=RecoveryKey.create()
        val a=RecoverySlots.create(vault,key);val b=RecoverySlots.create(vault,key)
        assertNotEquals(a.slotId,b.slotId);assertNotEquals(a.wrappedMaster,b.wrappedMaster)
        assertTrue(runCatching {RecoverySlots.decode(ByteArray(4097))}.isFailure)
        assertTrue(runCatching {RecoverySlots.decode(byteArrayOf(0xc3.toByte(),0x28))}.isFailure)
        assertTrue(runCatching {RecoverySlots.encode(a.copy(algorithm="password-sha256"))}.isFailure)
        assertTrue(runCatching {RecoverySlots.encode(a.copy(vaultId="../vault"))}.isFailure)
        assertTrue(runCatching {RecoverySlots.encode(a.copy(wrappedMaster="A".repeat(1000)))}.isFailure)
        val json=RecoverySlots.encode(a).toString(Charsets.UTF_8)
        assertTrue(runCatching {RecoverySlots.decode(json.replace("YJ-RECOVERY-1","YJ-RECOVERY-2").toByteArray())}.isFailure)
    }
}
