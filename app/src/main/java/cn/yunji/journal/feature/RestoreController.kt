package cn.yunji.journal.feature

import cn.yunji.journal.core.backup.RestorePreview
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Document is a bounded content URI token, never a key or a saved-state entry. */
interface RestoreAccess {
    suspend fun prepare(document: String,recoveryKey: String): RestorePreview
    suspend fun commit(preview: RestorePreview)
    suspend fun discard(preview: RestorePreview)
    fun isCommitted(preview: RestorePreview): Boolean
    suspend fun finishReopen() {}
    /** Cloud snapshots the recovery key can restore. Empty when the cloud holds nothing for this key. */
    suspend fun listCloudSnapshots(recoveryKey: String): List<cn.yunji.journal.core.backup.SnapshotRef> = emptyList()
    /** Downloads the chosen snapshot and returns a preview; throws when it cannot be restored. */
    suspend fun prepareCloud(snapshotId: String,recoveryKey: String): RestorePreview =
        throw UnsupportedOperationException("当前版本不支持从云端恢复")
}
data class RestoreUiState(val stage: String="SELECT",val document: String="",val key: String="",
    val preview: RestorePreview?=null,val acknowledged: Boolean=false,val message: String?=null,
    val cloud: Boolean=false,val cloudSnapshots: List<cn.yunji.journal.core.backup.SnapshotRef> = emptyList(),
    val snapshotId: String?=null,val finding: Boolean=false) {
    override fun toString()="RestoreUiState(<redacted>)"
}

/** UI owns confirmation; no key/document/medical summary is persisted by this controller. */
class RestoreController(private val scope: CoroutineScope,private val access: RestoreAccess) {
    private val mutable=MutableStateFlow(RestoreUiState())
    val state=mutable.asStateFlow()
    private var task: Job?=null
    private var revision=0
    fun select(document: String) {
        if(mutable.value.stage !in setOf("SELECT","KEY","ERROR"))return
        require(document.startsWith("content://") && document.length<=8192 && document.none(Char::isISOControl))
        revision++
        mutable.value=RestoreUiState(stage="KEY",document=document)
    }
    /** Enters the cloud flow: the user supplies the recovery key, then picks a remote snapshot. */
    fun startCloud() {
        if(mutable.value.stage !in setOf("SELECT","KEY","ERROR","CLOUD","CLOUD_LIST"))return
        revision++
        mutable.value=RestoreUiState(stage="CLOUD",cloud=true)
    }
    /** Looks up restorable cloud snapshots for the entered key. */
    fun findCloud() {
        val old=mutable.value
        if(old.stage!="CLOUD" || old.key.isBlank() || old.finding)return
        val generation=revision
        mutable.value=old.copy(finding=true,message=null)
        task=scope.launch {
            try {
                val found=access.listCloudSnapshots(old.key)
                if(generation!=revision)return@launch
                mutable.value=old.copy(stage=if(found.isEmpty())"CLOUD" else "CLOUD_LIST",finding=false,
                    cloudSnapshots=found,message=if(found.isEmpty())"这个恢复密钥在云端没有找到可恢复的备份。" else null)
            }catch(cancelled: CancellationException){throw cancelled}
            catch(_:Exception){if(generation==revision)mutable.value=old.copy(finding=false,message="无法读取云端备份，请检查连接与恢复密钥。")}
        }
    }
    fun selectSnapshot(snapshotId: String) {
        if(mutable.value.stage!="CLOUD_LIST")return
        if(mutable.value.cloudSnapshots.none {it.snapshotId==snapshotId})return
        mutable.value=mutable.value.copy(snapshotId=snapshotId,message=null)
    }
    fun key(value: String) {if(mutable.value.stage in setOf("KEY","CLOUD"))mutable.value=mutable.value.copy(key=value.take(256),message=null)}
    fun prepare() {
        val old=mutable.value
        val cloud=old.stage=="CLOUD_LIST" && old.snapshotId!=null
        if(!cloud && old.stage!="KEY")return
        if(old.key.isBlank())return
        val generation=revision
        mutable.value=old.copy(stage="VERIFYING",key="",message=null)
        task=scope.launch {
            var result: RestorePreview?=null
            try {
                val prepared=if(cloud) access.prepareCloud(requireNotNull(old.snapshotId),old.key)
                    else access.prepare(old.document,old.key)
                result=prepared
                currentCoroutineContext().ensureActive()
                if(generation==revision)mutable.value=RestoreUiState(stage="CONFIRM",preview=prepared)
                else cleanup(prepared)
            }catch(cancelled: CancellationException) {
                result?.let {cleanup(it)}
                throw cancelled
            }catch(_:Exception) {
                result?.let {cleanup(it)}
                if(generation==revision)mutable.value=if(cloud)
                    RestoreUiState(stage="CLOUD_LIST",cloud=true,cloudSnapshots=old.cloudSnapshots,snapshotId=old.snapshotId,
                        message="无法下载或校验云端备份，请检查连接与恢复密钥。")
                else RestoreUiState(stage="KEY",document=old.document,message="无法验证备份，请核对恢复密钥和文件完整性。")
            }
        }
    }
    fun acknowledge(value: Boolean) {if(mutable.value.stage=="CONFIRM")mutable.value=mutable.value.copy(acknowledged=value)}
    fun confirm(beforeCommit: suspend ()->Unit = {}) {
        val old=mutable.value;val preview=old.preview ?: return
        if(old.stage!="CONFIRM" || !old.acknowledged)return
        val generation=revision
        mutable.value=old.copy(stage="RESTORING",message=null)
        task=scope.launch {
            try {beforeCommit();access.commit(preview);if(generation==revision)mutable.value=RestoreUiState(stage="DONE")}
            catch(cancelled: CancellationException) {
                if(!committed(preview))cleanup(preview)
                if(generation==revision)mutable.value=if(committed(preview))RestoreUiState(stage="DONE") else RestoreUiState(stage="ERROR",message="恢复已中断，请重新打开资料库后核对。")
                throw cancelled
            }
            catch(_:Exception) {
                if(!committed(preview))cleanup(preview)
                if(generation==revision)mutable.value=if(committed(preview))RestoreUiState(stage="DONE") else RestoreUiState(stage="ERROR",message="恢复未完成，原始资料已保留，请重新打开后核对。")
            }
        }
    }
    private fun committed(preview: RestorePreview)=runCatching {access.isCommitted(preview)}.getOrDefault(false)
    private suspend fun cleanup(preview: RestorePreview): Boolean=withContext(NonCancellable) {
        try {access.discard(preview);true}catch(_:Exception){false}
    }
    fun leave() {
        val old=mutable.value
        revision++;val leavingRevision=revision;task?.cancel();task=null;mutable.value=RestoreUiState()
        if(old.stage=="CONFIRM")old.preview?.let {preview->scope.launch(NonCancellable){if(!cleanup(preview) && revision==leavingRevision)mutable.value=RestoreUiState(stage="ERROR",message="恢复暂存状态未清理，请重新打开后核对。")}}
    }
}