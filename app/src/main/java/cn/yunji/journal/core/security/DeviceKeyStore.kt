package cn.yunji.journal.core.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class VaultMaterial(val id: String, val master: ByteArray) {
    fun key(purpose: String) = VaultCrypto.derive(master, id, purpose)
}
class VaultLockedException: IllegalStateException("资料需要先解锁")

/** The committed wrapper, rather than a preferences flag, is the strict-mode authority. */
@Singleton
class DeviceKeyStore private constructor(context: Context, namespace: String) {
    @Inject constructor(@ApplicationContext context: Context): this(context, "")
    private val keyStore=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    private val alias="${context.packageName}.vault-wrap$namespace.v1"
    private val root=File(context.noBackupFilesDir,"vault").apply {mkdirs()}
    private val file=AtomicFile(File(root,"device-key.json"))
    @Serializable private data class Wrapped(val format: Int=1,val vaultId: String,val nonce: String,val ciphertext: String,
        val mode: String="NORMAL",val keyAlias: String?=null)
    class Authentication internal constructor(val cipher: Cipher,internal val operation: String,internal val original: String,
        internal val vaultId: String,internal val targetAlias: String,internal val sourceMaster: ByteArray?=null): AutoCloseable {
        override fun close(){sourceMaster?.fill(0)}
    }
    private fun read(): Wrapped? = if(file.baseFile.exists() || File(file.baseFile.path+".bak").exists())
        file.openRead().use {Json.decodeFromString<Wrapped>(it.readBytes().toString(Charsets.UTF_8))} else null
    private fun aad(record: Wrapped)=if(record.format==1) "device/v1/${record.vaultId}" else "device/v2/${record.mode}/${record.vaultId}"
    private fun encoded(record: Wrapped)=Json.encodeToString(record)
    private fun validate(record: Wrapped) {
        require(record.format in 1..2 && record.mode in setOf("NORMAL","STRICT")) {"密钥格式不兼容"}
        require(if(record.mode=="STRICT")record.format==2 && record.keyAlias?.startsWith("$alias.strict.")==true else record.keyAlias==null || record.keyAlias==alias) {"密钥封装配置无效"}
    }
    private fun commit(record: Wrapped) {
        val output=file.startWrite()
        try {output.write(encoded(record).toByteArray(Charsets.UTF_8));file.finishWrite(output)}
        catch(e: Exception){file.failWrite(output);throw e}
    }
    @Synchronized fun strict(): Boolean = read()?.also(::validate)?.mode=="STRICT"
    @Synchronized fun open(): VaultMaterial {
        val record=read()
        if(record!=null) {
            validate(record)
            if(record.mode=="STRICT")throw VaultLockedException()
            val key=keyStore.getKey(alias,null) as? SecretKey ?: error("设备密钥不可用，请使用恢复密钥；原资料已保留")
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,VaultCrypto.decode(record.nonce)))
            cipher.updateAAD(aad(record).toByteArray())
            return VaultMaterial(record.vaultId,cipher.doFinal(VaultCrypto.decode(record.ciphertext)))
        }
        require(root.listFiles().orEmpty().none {it.name.endsWith(".db") || it.name=="objects"}) {"存在旧资料但密钥缺失，请恢复已有资料"}
        val material=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        wrapNormal(material)
        return material
    }
    private fun key(name: String, authentication: Boolean): SecretKey {
        (keyStore.getKey(name,null) as? SecretKey)?.let {return it}
        val spec=KeyGenParameterSpec.Builder(name,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if(authentication) {
            spec.setUserAuthenticationRequired(true)
            if(Build.VERSION.SDK_INT>=30)spec.setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            else spec.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {init(spec.build())}.generateKey()
    }
    private fun wrapNormal(material: VaultMaterial) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key(alias,false))
        val basis=Wrapped(2,material.id,"","",mode="NORMAL")
        cipher.updateAAD(aad(basis).toByteArray())
        commit(basis.copy(nonce=VaultCrypto.encode(cipher.iv),ciphertext=VaultCrypto.encode(cipher.doFinal(material.master))))
    }
    @Synchronized fun prepareEnableStrict(): Authentication {
        val material=open()
        try {
            val previous=requireNotNull(read()); val target="$alias.strict.${UUID.randomUUID()}"
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,key(target,true))
            // Per-use keys reject updateAAD before BiometricPrompt authorizes this operation.
            return Authentication(cipher,"ENABLE",encoded(previous),material.id,target,material.master)
        } catch(e: Exception){material.master.fill(0);throw e}
    }
    @Synchronized fun prepareStrictUnlock(disable: Boolean=false): Authentication {
        val record=requireNotNull(read());validate(record);require(record.mode=="STRICT")
        val name=requireNotNull(record.keyAlias)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,keyStore.getKey(name,null) as? SecretKey ?: error("认证密钥不可用，原资料已保留"),GCMParameterSpec(128,VaultCrypto.decode(record.nonce)))
        return Authentication(cipher,if(disable)"DISABLE" else "UNLOCK",encoded(record),record.vaultId,name)
    }
    @Synchronized fun complete(request: Authentication, authenticatedCipher: Cipher): VaultMaterial {
        require(authenticatedCipher===request.cipher) {"认证操作已变化，请重试"}
        val current=requireNotNull(read());require(encoded(current)==request.original) {"密钥配置已变化，请重新认证"}
        try {
            authenticatedCipher.updateAAD((if(request.operation=="ENABLE")"device/v2/STRICT/${request.vaultId}" else aad(current)).toByteArray())
            if(request.operation=="ENABLE") {
                val source=requireNotNull(request.sourceMaster)
                val wrapped=Wrapped(2,request.vaultId,VaultCrypto.encode(authenticatedCipher.iv),VaultCrypto.encode(authenticatedCipher.doFinal(source)),"STRICT",request.targetAlias)
                commit(wrapped)
                keyStore.deleteEntry(alias)
                return VaultMaterial(request.vaultId,source.copyOf())
            }
            val material=VaultMaterial(current.vaultId,authenticatedCipher.doFinal(VaultCrypto.decode(current.ciphertext)))
            if(request.operation=="DISABLE") {
                try {wrapNormal(material);keyStore.deleteEntry(request.targetAlias)} catch(e: Exception){material.master.fill(0);throw e}
            }
            return material
        } finally {request.close()}
    }
    @Synchronized fun cancel(request: Authentication) {
        request.close()
        if(request.operation=="ENABLE" && read()?.keyAlias!=request.targetAlias)runCatching {keyStore.deleteEntry(request.targetAlias)}
    }
    internal companion object {
        fun isolated(context: Context,namespace: String)=DeviceKeyStore(context,".test-$namespace")
    }
}
