package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.security.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecoveryKeySetupTest {
    @Test fun onlyMatchingSavedFragmentsProduceARecoverySlotAndConsumeSetup() {
        val setup=RecoveryKeySetup()
        val key=setup.keyText();val groups=key.substring(4).chunked(6)
        assertEquals(8,groups.size);assertEquals(2,setup.requestedGroups.distinct().size)
        assertTrue(setup.requestedGroups.all {it in 1..8})
        val vault=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        assertTrue(runCatching {setup.confirm(vault,listOf("", ""))}.isFailure)
        assertEquals(key,setup.keyText())
        val slot=setup.confirm(vault,setup.requestedGroups.map {groups[it-1]})
        val opened=RecoverySlots.open(slot,key)
        try {assertArrayEquals(vault.master,opened.master)} finally {opened.master.fill(0)}
        assertTrue(runCatching {setup.keyText()}.isFailure)
        assertTrue(runCatching {setup.confirm(vault,listOf("", ""))}.isFailure)
    }
    @Test fun cancelledSetupCannotRevealOrConfirmTheOldKey() {
        val setup=RecoveryKeySetup();val key=setup.keyText()
        assertFalse(setup.toString().contains(key))
        setup.close();setup.close()
        assertTrue(runCatching {setup.keyText()}.isFailure)
        val vault=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        assertTrue(runCatching {setup.confirm(vault,listOf("", ""))}.isFailure)
    }
}
