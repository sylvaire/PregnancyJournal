package cn.yunji.journal.feature

import cn.yunji.journal.core.network.*
import cn.yunji.journal.data.CloudConnectionAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import javax.net.ssl.SSLException

data class CloudUiState(val endpoint: String="https://dav.jianguoyun.com/dav/",val username: String="",val password: String="",val folder: String="",
    val loading: Boolean=true,val testing: Boolean=false,val saved: Boolean=false,val hasSavedPassword: Boolean=false,
    val capabilities: CloudCapabilities?=null,val canSave: Boolean=false,val message: String?=null,val pendingName: String?=null,
    val browserPath: List<String>?=null,val directoryNames: List<String> = emptyList(),val directoryComplete: Boolean=true) {
    override fun toString()="CloudUiState(<redacted>)"
}

/** No password or cloud form goes into SavedStateHandle, navigation arguments, logs, or plaintext preferences. */
class CloudConnectionController(private val scope: CoroutineScope,private val repository: CloudConnectionAccess) {
    private val mutable=MutableStateFlow(CloudUiState())
    val state=mutable.asStateFlow()
    private var loaded=false
    private var revision=0L
    private var testedRevision: Long?=null
    private var task: Job?=null
    private var loading: Job?=null
    fun load() {
        if(loading?.isActive==true)return
        loading=scope.launch {
            try {
                val pending=repository.pendingCleanup()
                if(loaded){mutable.value=mutable.value.copy(pendingName=pending?.name);return@launch}
                val saved=repository.load()
                mutable.value=if(saved==null)CloudUiState(loading=false,pendingName=pending?.name) else CloudUiState(
                    endpoint=saved.connection.endpoint,username=saved.connection.username,folder=saved.connection.folder.joinToString("/"),
                    loading=false,saved=true,hasSavedPassword=true,capabilities=saved.capabilities,pendingName=pending?.name)
                loaded=true
            }catch(e:CancellationException){throw e}
            catch(_:Exception){mutable.value=mutable.value.copy(loading=false,message="无法读取已保存的连接，原文件保留。可以重新填写并测试后保存。");loaded=true}
        }
    }
    fun update(field: String,value: String) {
        if(mutable.value.testing || mutable.value.loading)return
        val current=mutable.value
        if(value.length>if(field=="password" || field=="endpoint")2048 else 1024)return
        val next=when(field){"endpoint"->current.copy(endpoint=value);"username"->current.copy(username=value);"password"->current.copy(password=value);"folder"->current.copy(folder=value);else->return}
        if(next==current)return
        revision++;testedRevision=null
        mutable.value=next.copy(canSave=false,capabilities=null,message=null)
    }
    private suspend fun connection(form: CloudUiState): CloudConnection {
        val folder=form.folder.trim().trim('/').takeIf(String::isNotEmpty)?.split('/') ?: emptyList()
        return CloudCredentials.resolve(form.endpoint,form.username,form.password,folder,if(form.password.isEmpty())repository.load()?.connection else null)
    }
    fun test() {
        if(mutable.value.loading || mutable.value.testing)return
        val form=mutable.value;val captured=revision
        mutable.value=form.copy(testing=true,message=null,canSave=false,capabilities=null);testedRevision=null
        task=scope.launch {
            try {
                val result=repository.probe(connection(form))
                if(revision==captured) {
                    testedRevision=captured
                    mutable.value=mutable.value.copy(capabilities=result,canSave=true,
                        pendingName=result.testName.takeIf {result.cleanupPending},message=if(result.verified)"连接可读写，测试文件已回读校验。请保存连接。" else "目录可读取，但不能完成写入校验。可保存用于读取，尚不能备份。")
                }
            }catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.value=mutable.value.copy(message=message(e),canSave=false)}
            finally {
                mutable.value=mutable.value.copy(testing=false)
                withContext(NonCancellable){runCatching {repository.pendingCleanup()}.getOrNull()?.let {mutable.value=mutable.value.copy(pendingName=it.name)}}
            }
        }
    }
    fun browse(path: List<String>?=null) {
        if(mutable.value.loading || mutable.value.testing)return
        val form=mutable.value
        val requested=path ?: form.folder.trim().trim('/').takeIf(String::isNotEmpty)?.split('/') ?: emptyList()
        mutable.value=form.copy(testing=true,browserPath=requested,directoryNames=emptyList(),message=null)
        task=scope.launch {
            try {
                val (names,complete)=repository.browse(connection(form).copy(folder=requested).normalized())
                mutable.value=mutable.value.copy(directoryNames=names,directoryComplete=complete)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.value=mutable.value.copy(message=message(e),directoryComplete=false)}
            finally {mutable.value=mutable.value.copy(testing=false)}
        }
    }
    fun chooseDirectory(name: String) {
        val current=mutable.value
        if(name !in current.directoryNames || current.browserPath==null || current.testing)return
        browse(current.browserPath+name)
    }
    fun parentDirectory() {mutable.value.browserPath?.takeIf {it.isNotEmpty()}?.let {browse(it.dropLast(1))}}
    fun useDirectory() {
        if(mutable.value.testing)return
        val selected=mutable.value.browserPath ?: return
        if(selected.joinToString("/").length>1024){mutable.value=mutable.value.copy(message="目录路径过长，请选择更浅的目录。");return}
        update("folder",selected.joinToString("/"))
        closeBrowser()
    }
    fun closeBrowser() {cancel();mutable.value=mutable.value.copy(browserPath=null,directoryNames=emptyList(),message=null)}
    fun cancel() {task?.cancel();repository.cancelProbe()}
    fun save() {
        if(mutable.value.testing || testedRevision!=revision || !mutable.value.canSave)return
        val form=mutable.value;val caps=form.capabilities ?: return
        mutable.value=form.copy(testing=true)
        task=scope.launch {
            try {
                repository.save(connection(form),caps)
                mutable.value=mutable.value.copy(password="",saved=true,hasSavedPassword=true,canSave=false,message="连接已加密保存。尚未执行备份。")
            }catch(e:CancellationException){throw e}
            catch(_:Exception){mutable.value=mutable.value.copy(message="连接未保存，原连接仍保留。请解锁后重试。")}
            finally {mutable.value=mutable.value.copy(testing=false)}
        }
    }
    fun disconnect() {
        if(mutable.value.testing)return
        mutable.value=mutable.value.copy(testing=true)
        task=scope.launch {try {repository.disconnect();revision++;testedRevision=null;mutable.value=CloudUiState(loading=false,message="已清除本机连接，云端文件未更改。",pendingName=repository.pendingCleanup()?.name)}catch(e:CancellationException){throw e}catch(_:Exception){mutable.value=mutable.value.copy(message="连接未清除，请稍后重试。")}
            finally {mutable.value=mutable.value.copy(testing=false)}}
    }
    fun forgetCleanup() {
        if(mutable.value.testing)return
        mutable.value=mutable.value.copy(testing=true)
        task=scope.launch {try {repository.forgetCleanup();mutable.value=mutable.value.copy(pendingName=null,message="已清除本机测试标记，云端遗留项目需自行核对。")}
            catch(e:CancellationException){throw e}catch(_:Exception){mutable.value=mutable.value.copy(message="测试标记未清除，请稍后重试。")}
            finally {mutable.value=mutable.value.copy(testing=false)}}
    }
    fun leave() {
        loading?.cancel();cancel();revision++;testedRevision=null
        mutable.value=mutable.value.copy(password="",canSave=false,message=null,browserPath=null,directoryNames=emptyList())
    }
    internal fun message(error: Exception)=when(error) {
        is DavException->if(error.status==429 && error.retryAfterSeconds!=null)"请求过于频繁，请${error.retryAfterSeconds}秒后再试。" else error.message ?: "云盘请求未完成"
        is SSLException->"无法验证服务证书，请核对 HTTPS 地址或联系云盘服务商。"
        is IOException->"网络或回读校验未完成，原连接保留。请稍后重新测试。"
        // Kotlin 的 require() 无消息时 message 为英文 "Failed requirement."，
        // 直接展示会让用户看到不可读的内部提示。
        is IllegalArgumentException->error.message?.takeIf {it.isNotBlank() && !it.startsWith("Failed requirement")}
            ?: "云盘返回的内容不符合预期，请核对服务地址、父目录与服务商限制。"
        else->"云盘协议检查未通过，原连接保留。请核对目录与服务能力。"
    }
}
