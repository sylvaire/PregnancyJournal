package cn.yunji.journal

import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.crypto.Cipher

class FileGcmCompatibilityTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun chunkedGcmMatchesIndependentJceAtBlockAndBufferBoundaries() {
        for(length in listOf(0,1,15,16,17,65535,65536,65537,131073)) {
            val key=VaultCrypto.randomKey();val nonce=VaultCrypto.nonce();val aad="synthetic compatibility"
            val plain=ByteArray(length){(it%251).toByte()}
            val encrypted=ByteArrayOutputStream()
            FileGcm(true,key,nonce,aad).use {cipher->plain.asList().chunked(65536).forEach {chunk->val bytes=chunk.toByteArray();cipher.update(bytes,bytes.size,encrypted)};cipher.finish(encrypted)}
            val reference=VaultCrypto.cipher(Cipher.ENCRYPT_MODE,key,nonce,aad).doFinal(plain)
            assertArrayEquals(reference,encrypted.toByteArray())
            val decoded=ByteArrayOutputStream()
            FileGcm(false,key,nonce,aad).use {cipher->reference.asList().chunked(65536).forEach {chunk->val bytes=chunk.toByteArray();cipher.update(bytes,bytes.size,decoded)};cipher.finish(decoded)}
            assertArrayEquals(plain,decoded.toByteArray());key.fill(0)
        }
    }
    @Test fun existingPjf1ObjectsDecryptAndNewObjectsRemainReadableByLegacyJce() {
        val directory=temporary.newFolder();val material=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        val key=material.key("object")
        try {
            val store=EncryptedFileStore(directory,material);val plain="synthetic legacy object".toByteArray()
            val id=UUID.randomUUID().toString();val nonce=VaultCrypto.nonce()
            val legacy=byteArrayOf(0x50,0x4a,0x46,0x01)+nonce+VaultCrypto.cipher(Cipher.ENCRYPT_MODE,key,nonce,"object/v1/${material.id}/$id/document").doFinal(plain)
            store.path(id).writeBytes(legacy)
            val info=StoredObject(id,plain.size.toLong(),legacy.size.toLong(),VaultCrypto.hex(VaultCrypto.sha256(plain)),VaultCrypto.hex(VaultCrypto.sha256(legacy)))
            val output=File(directory,"legacy-plain");store.decryptTo(info,output);assertArrayEquals(plain,output.readBytes())
            val newInfo=store.put(plain.inputStream());val bytes=store.path(newInfo.id).readBytes()
            val decrypted=VaultCrypto.cipher(Cipher.DECRYPT_MODE,key,bytes.copyOfRange(4,16),"object/v1/${material.id}/${newInfo.id}/document").doFinal(bytes,16,bytes.size-16)
            assertArrayEquals(plain,decrypted)
        }finally {key.fill(0);material.master.fill(0)}
    }
}
