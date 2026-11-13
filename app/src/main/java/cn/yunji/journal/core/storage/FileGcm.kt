package cn.yunji.journal.core.storage

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.OutputStream

/** Bounded-memory AES-256-GCM for PJF1 objects; no global JCE provider registration. */
internal class FileGcm(encrypt: Boolean,key: ByteArray,nonce: ByteArray,aad: String): AutoCloseable {
    private val cipher=GCMBlockCipher.newInstance(AESEngine.newInstance())
    private val output=ByteArray(64*1024+32)
    init {require(key.size==32 && nonce.size==12);cipher.init(encrypt,AEADParameters(KeyParameter(key),128,nonce,aad.toByteArray(Charsets.UTF_8)))}
    fun update(input: ByteArray,count: Int,destination: OutputStream) {
        require(count in 0..64*1024 && cipher.getOutputSize(count)<=output.size)
        val size=cipher.processBytes(input,0,count,output,0)
        destination.write(output,0,size)
    }
    fun finish(destination: OutputStream) {val size=cipher.doFinal(output,0);destination.write(output,0,size)}
    override fun close(){output.fill(0);cipher.reset()}
}
