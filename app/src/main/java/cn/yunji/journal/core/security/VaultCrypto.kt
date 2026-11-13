package cn.yunji.journal.core.security

import com.google.crypto.tink.subtle.Hkdf
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, authenticated envelopes. The caller supplies the complete object identity as AAD. */
object VaultCrypto {
    private val random = SecureRandom()
    private val magic = byteArrayOf(0x50, 0x4a, 0x45, 0x01)
    const val NONCE_BYTES = 12
    fun randomKey(): ByteArray = ByteArray(32).also(random::nextBytes)
    fun nonce(): ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)

    fun derive(master: ByteArray, vaultId: String, purpose: String): ByteArray {
        require(master.size == 32)
        return Hkdf.computeHkdf("HmacSha256", master, vaultId.toByteArray(Charsets.UTF_8),
            "pregnancy-journal/v1/$purpose".toByteArray(Charsets.UTF_8), 32)
    }

    fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: String): Cipher {
        require(key.size == 32 && nonce.size == NONCE_BYTES)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad.toByteArray(Charsets.UTF_8))
        }
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: String): ByteArray {
        val nonce = nonce()
        return magic + nonce + cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, envelope: ByteArray, aad: String): ByteArray {
        require(envelope.size >= 32) { "加密文件不完整" }
        require(envelope.copyOfRange(0, 4).contentEquals(magic)) { "不支持的加密格式" }
        return cipher(Cipher.DECRYPT_MODE, key, envelope.copyOfRange(4, 16), aad)
            .doFinal(envelope, 16, envelope.size - 16)
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}

object RecoveryKey {
    fun create(): String = format(VaultCrypto.randomKey())
    fun format(raw: ByteArray): String {
        require(raw.size == 32)
        return "YJ1:" + VaultCrypto.encode(raw + VaultCrypto.sha256(raw).copyOf(4))
    }
    fun parse(value: String): ByteArray {
        val text = value.trim()
        require(text.startsWith("YJ1:") && text.length < 100) { "恢复密钥格式不正确" }
        val decoded = runCatching { VaultCrypto.decode(text.substring(4)) }
            .getOrElse { throw IllegalArgumentException("恢复密钥格式不正确") }
        require(decoded.size == 36) { "恢复密钥长度不正确" }
        val raw = decoded.copyOf(32)
        require(MessageDigest.isEqual(decoded.copyOfRange(32, 36), VaultCrypto.sha256(raw).copyOf(4))) {
            "恢复密钥校验失败，请检查是否抄录完整"
        }
        return raw
    }
}
