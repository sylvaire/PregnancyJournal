package cn.yunji.journal.core.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.FileProvider
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

@Serializable
data class FileRequest(val kind: String,val ownerId: String="",val medication: Boolean=false,
    val targetId: String?=null,val revision: Long?=null,val cameraName: String?=null,val sourceSize: Long?=null,
    /** Serialised wizard options for exports; never carries credentials or medical values. */
    val payload: String?=null)
@Serializable
data class FileResult(val id: String,val request: FileRequest,val returned: Boolean=false,
    val uris: List<String> = emptyList(),val confirm: Boolean=false,val backupStarted: Boolean=false) {
    val canApply get()=returned && !backupStarted
}
class BackupReplayException: IllegalStateException("上次备份已开始，请重新选择保存位置")

/** Only transport metadata uses this independent key. It cannot unlock the medical vault. */
@Singleton
class FileHandoff private constructor(private val context: Context,namespace: String) {
    @Inject constructor(@ApplicationContext context: Context):this(context,"")
    private val alias="${context.packageName}.file-handoff$namespace.v1"
    private val file=AtomicFile(File(context.noBackupFilesDir,"file-handoff.enc"))
    private val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    private fun key(create: Boolean): SecretKey {
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        check(create) {"文件选择状态密钥不可用"}
        return KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(): FileResult? {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return null
        val bytes=file.openRead().use {it.readBytes()};require(bytes.size in 29..200_000)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            updateAAD("file-handoff/v1".toByteArray())
        }
        val plain=cipher.doFinal(bytes,12,bytes.size-12)
        return try {Json.decodeFromString<FileResult>(plain.toString(Charsets.UTF_8))} finally {plain.fill(0)}
    }
    private fun save(value: FileResult) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key(true));updateAAD("file-handoff/v1".toByteArray())}
        val plain=Json.encodeToString(value).toByteArray()
        require(plain.size<=199_972) {"文件选择状态过大"}
        val encrypted=try {cipher.doFinal(plain)} finally {plain.fill(0)}
        val output=file.startWrite()
        try {output.write(cipher.iv);output.write(encrypted);file.finishWrite(output)} catch(e: Exception){file.failWrite(output);throw e}
    }
    /** Persist before any backup work. Recreated Activities/processes cannot write this target again. */
    @Synchronized fun startBackup(id: String) {
        val pending=requireNotNull(read())
        require(pending.id==id && pending.returned && pending.request.kind in setOf("BACKUP","SAFETY_EXPORT","EXPORT_DATA","SUMMARY_PDF"))
        if(pending.backupStarted)throw BackupReplayException()
        save(pending.copy(backupStarted=true))
    }
    @Synchronized fun begin(request: FileRequest,exportName: String?=null): Intent {
        check(read()==null) {"请先处理上一次文件选择"}
        require(request.kind in setOf("IMAGES","PDF","CAMERA","EXPORT","REPLACE","RETRY","BACKUP","RESTORE","SAFETY_EXPORT","EXPORT_DATA","SUMMARY_PDF"))
        val actual=if(request.kind=="CAMERA")request.copy(cameraName="${UUID.randomUUID()}.jpg") else request
        val intent=when(actual.kind) {
            "IMAGES"->ActivityResultContracts.PickMultipleVisualMedia(20).createIntent(context,PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            "PDF"->ActivityResultContracts.OpenMultipleDocuments().createIntent(context,arrayOf("application/pdf"))
            "CAMERA"->ActivityResultContracts.TakePicture().createIntent(context,cameraUri(requireNotNull(actual.cameraName)))
            "EXPORT"->ActivityResultContracts.CreateDocument("*/*").createIntent(context,requireNotNull(exportName))
            "RESTORE"->ActivityResultContracts.OpenDocument().createIntent(context,arrayOf("*/*"))
            "BACKUP","SAFETY_EXPORT"->ActivityResultContracts.CreateDocument("application/octet-stream").createIntent(context,requireNotNull(exportName))
            "EXPORT_DATA"->ActivityResultContracts.CreateDocument("application/zip").createIntent(context,requireNotNull(exportName))
            "SUMMARY_PDF"->ActivityResultContracts.CreateDocument("application/pdf").createIntent(context,requireNotNull(exportName))
            else->ActivityResultContracts.OpenDocument().createIntent(context,if(actual.medication)arrayOf("image/*") else arrayOf("image/*","application/pdf"))
        }
        save(FileResult(UUID.randomUUID().toString(),actual))
        return intent
    }
    fun cameraFile(name: String): File {
        require(Regex("[0-9a-fA-F-]{36}\\.jpg").matches(name))
        return File(context.cacheDir,"capture/$name").apply {parentFile!!.mkdirs()}
    }
    private fun cameraUri(name: String)=FileProvider.getUriForFile(context,"${context.packageName}.capture",cameraFile(name))
    @Synchronized fun receive(code: Int,data: Intent?,confirm: Boolean) {
        val pending=read() ?: return
        if(pending.returned)return
        val uris=when(pending.request.kind) {
            "IMAGES"->ActivityResultContracts.PickMultipleVisualMedia(20).parseResult(code,data)
            "PDF"->ActivityResultContracts.OpenMultipleDocuments().parseResult(code,data)
            "CAMERA"->if(code==Activity.RESULT_OK)listOf(cameraUri(requireNotNull(pending.request.cameraName))) else emptyList()
            else->listOfNotNull(ActivityResultContracts.OpenDocument().parseResult(code,data))
        }
        require(uris.size<=20 && uris.all {it.scheme=="content" && it.toString().toByteArray().size<=8192 && it.toString().none(Char::isISOControl)})
        if(uris.isEmpty()){clear();return}
        val flags=when(pending.request.kind){"BACKUP","SAFETY_EXPORT","EXPORT_DATA","SUMMARY_PDF"->Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION;"EXPORT"->Intent.FLAG_GRANT_WRITE_URI_PERMISSION;else->Intent.FLAG_GRANT_READ_URI_PERMISSION}
        uris.forEach {runCatching {context.contentResolver.takePersistableUriPermission(it,flags)}}
        save(pending.copy(returned=true,uris=uris.map(Uri::toString),confirm=confirm || pending.request.kind in setOf("BACKUP","RESTORE","SAFETY_EXPORT","EXPORT_DATA","SUMMARY_PDF")))
    }
    @Synchronized fun clear(keepCamera: Boolean=false) {
        val pending=runCatching {read()}.getOrNull()
        if(!keepCamera)pending?.request?.cameraName?.let {cameraFile(it).delete()}
        file.delete()
    }
    @Synchronized fun clearIf(id: String,keepCamera: Boolean=false): Boolean {
        if(read()?.id!=id)return false
        clear(keepCamera)
        return true
    }
    internal companion object {fun isolated(context: Context,namespace: String)=FileHandoff(context,".test-$namespace")}
}

/** Production Activity owns the registration; standalone UI tests retain their local launchers. */
val LocalFileLaunch=staticCompositionLocalOf<((FileRequest,String?)->Unit)?> {null}
