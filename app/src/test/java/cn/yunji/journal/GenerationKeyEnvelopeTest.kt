package cn.yunji.journal

import cn.yunji.journal.core.backup.GenerationKeyEnvelope
import cn.yunji.journal.core.security.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GenerationKeyEnvelopeTest {
    private fun material()=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
    @Test fun differentVaultCanBeUnwrappedWithoutChangingDeviceKey() {
        val device=material();val restored=material();val before=device.master.clone()
        try {
            val generation=UUID.randomUUID().toString();val bytes=GenerationKeyEnvelope.wrap(device,generation,restored)
            val reopened=GenerationKeyEnvelope.open(device,generation,bytes)
            try {assertEquals(restored.id,reopened.id);assertArrayEquals(restored.master,reopened.master);assertArrayEquals(before,device.master)}finally {reopened.master.fill(0)}
            assertFalse(bytes.toString(Charsets.UTF_8).contains(restored.id))
        }finally {device.master.fill(0);restored.master.fill(0);before.fill(0)}
    }
    @Test fun wrongDeviceGenerationAndTamperingAreRejected() {
        val device=material();val restored=material();val other=material()
        try {
            val id=UUID.randomUUID().toString();val bytes=GenerationKeyEnvelope.wrap(device,id,restored)
            assertTrue(runCatching {GenerationKeyEnvelope.open(other,id,bytes)}.isFailure)
            assertTrue(runCatching {GenerationKeyEnvelope.open(device,UUID.randomUUID().toString(),bytes)}.isFailure)
            bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
            assertTrue(runCatching {GenerationKeyEnvelope.open(device,id,bytes)}.isFailure)
        }finally {device.master.fill(0);restored.master.fill(0);other.master.fill(0)}
    }
}