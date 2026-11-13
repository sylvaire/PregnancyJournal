package cn.yunji.journal

import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.data.RecoverySettingsAccess
import cn.yunji.journal.feature.RecoveryController
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecoveryControllerTest {
    private class Settings: RecoverySettingsAccess {
        var slot: RecoverySlot?=null
        val vault=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        override suspend fun configured()=slot!=null
        override suspend fun confirm(setup: RecoveryKeySetup,answers: List<String>) {slot=setup.confirm(vault,answers)}
    }
    @Test fun verificationHidesKeyAllowsTypoRetryAndSavesOnlyAfterMatchingAnswers()=runTest {
        val settings=Settings();val controller=RecoveryController(this,settings)
        controller.load();runCurrent();controller.generate()
        val key=controller.state.value.key;val groups=key.substring(4).chunked(6)
        controller.verify();assertEquals("",controller.state.value.key)
        controller.answer(0,"!!!!!!");controller.confirm();runCurrent();assertNull(settings.slot)
        val requested=controller.state.value.groups
        requested.forEachIndexed {index,group->controller.answer(index,groups[group-1])}
        val correct=groups[requested[0]-1]
        controller.answer(0,(if(correct[0]=='A')"B" else "A")+correct.substring(1))
        controller.confirm();runCurrent();assertNull(settings.slot)
        assertEquals("VERIFY",controller.state.value.stage);assertFalse(controller.state.value.busy)
        assertNotNull(controller.state.value.message)
        controller.answer(0,correct)
        controller.confirm();runCurrent()
        assertEquals("READY",controller.state.value.stage);assertEquals("",controller.state.value.first)
        val opened=RecoverySlots.open(requireNotNull(settings.slot),key)
        try {assertArrayEquals(settings.vault.master,opened.master)}finally {opened.master.fill(0)}
    }
    @Test fun leaveDiscardsUnconfirmedKeyAndAnswersWithoutSaving()=runTest {
        val settings=Settings();val controller=RecoveryController(this,settings)
        controller.load();runCurrent();controller.generate();val key=controller.state.value.key
        controller.verify();controller.answer(0,"ABC123");controller.leave()
        assertEquals("",controller.state.value.key);assertEquals("",controller.state.value.first)
        assertNull(settings.slot)
        controller.load();runCurrent();controller.generate();assertNotEquals(key,controller.state.value.key)
        controller.leave()
    }
}
