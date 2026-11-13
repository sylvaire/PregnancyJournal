package cn.yunji.journal

import cn.yunji.journal.core.security.RecoveryKey
import cn.yunji.journal.core.security.VaultCrypto
import org.junit.Assert.*
import org.junit.Test

class VaultCryptoTest {
    @Test fun authenticatedRoundTripBindsIdentity() {
        val key = VaultCrypto.randomKey()
        val plain = "报告与用药原文".toByteArray()
        val envelope = VaultCrypto.encrypt(key, plain, "vault/object/1")
        assertArrayEquals(plain, VaultCrypto.decrypt(key, envelope, "vault/object/1"))
        assertTrue(runCatching { VaultCrypto.decrypt(key, envelope, "vault/object/2") }.isFailure)
        assertTrue(runCatching { VaultCrypto.decrypt(VaultCrypto.randomKey(), envelope, "vault/object/1") }.isFailure)
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 1).toByte()
        assertTrue(runCatching { VaultCrypto.decrypt(key, envelope, "vault/object/1") }.isFailure)
    }
    @Test fun newEncryptionUsesDifferentNonces() {
        val key = VaultCrypto.randomKey()
        assertFalse(VaultCrypto.encrypt(key, byteArrayOf(1), "a").contentEquals(VaultCrypto.encrypt(key, byteArrayOf(1), "a")))
    }
    @Test fun recoveryKeyChecksTranscription() {
        val raw = VaultCrypto.randomKey()
        val formatted = RecoveryKey.format(raw)
        assertArrayEquals(raw, RecoveryKey.parse(formatted))
        val corrupted = "YJ1:" + VaultCrypto.encode(raw + ByteArray(4))
        assertTrue(runCatching { RecoveryKey.parse(corrupted) }.isFailure)
        assertTrue(runCatching { RecoveryKey.parse("password") }.isFailure)
    }
    @Test fun keysAreSeparatedByPurposeAndVault() {
        val key = VaultCrypto.randomKey()
        assertFalse(VaultCrypto.derive(key, "a", "database").contentEquals(VaultCrypto.derive(key, "a", "object")))
        assertFalse(VaultCrypto.derive(key, "a", "database").contentEquals(VaultCrypto.derive(key, "b", "database")))
    }
}
