package cn.yunji.journal.feature

import cn.yunji.journal.core.backup.RecoveryKeySetup
import cn.yunji.journal.data.RecoverySettingsAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecoveryUiState(val stage: String="LOADING",val key: String="",val groups: List<Int> = emptyList(),
    val first: String="",val second: String="",val busy: Boolean=false,val message: String?=null) {
    override fun toString()="RecoveryUiState(<redacted>)"
}
class RecoveryController(private val scope: CoroutineScope,private val settings: RecoverySettingsAccess) {
    val vaultId get()=settings.vaultId
    private val mutable=MutableStateFlow(RecoveryUiState())
    val state=mutable.asStateFlow()
    private var setup: RecoveryKeySetup?=null
    private var task: Job?=null
    private var revision=0
    fun load() {
        leave();val generation=revision
        task=scope.launch {
            try {val configured=settings.configured();if(generation==revision)mutable.value=RecoveryUiState(stage=if(configured)"READY" else "INTRO")}
            catch(e: CancellationException){throw e}
            catch(_: Exception){if(generation==revision)mutable.value=RecoveryUiState(stage="ERROR",message="恢复设置无法读取，请解锁后重试。")}
        }
    }
    fun generate() {
        if(mutable.value.stage!="INTRO")return
        val value=RecoveryKeySetup();setup=value
        mutable.value=RecoveryUiState(stage="SHOW",key=value.keyText())
    }
    fun verify() {
        if(mutable.value.stage!="SHOW")return
        mutable.value=RecoveryUiState(stage="VERIFY",groups=requireNotNull(setup).requestedGroups)
    }
    fun answer(index: Int,value: String) {
        if(mutable.value.stage!="VERIFY" || mutable.value.busy)return
        val filtered=value.filter {it.isLetterOrDigit() && it.code<128 || it=='-' || it=='_'}.take(6)
        mutable.value=if(index==0)mutable.value.copy(first=filtered,message=null) else mutable.value.copy(second=filtered,message=null)
    }
    fun confirm() {
        val current=mutable.value
        if(current.stage!="VERIFY" || current.busy || current.first.length!=6 || current.second.length!=6)return
        val pending=setup ?: return;val generation=revision
        mutable.value=current.copy(busy=true,message=null)
        task=scope.launch {
            try {settings.confirm(pending,listOf(current.first,current.second));if(generation==revision){setup=null;mutable.value=RecoveryUiState(stage="READY")}}
            catch(e: CancellationException){throw e}
            catch(_: Exception){if(generation==revision){
                if(pending.isActive())mutable.value=current.copy(message="密钥片段不匹配，请核对保存的内容。")
                else {setup=null;mutable.value=RecoveryUiState(stage="INTRO",message="保存未完成，请重新生成并保存恢复密钥。")}
            }}
        }
    }
    fun leave() {revision++;task?.cancel();task=null;setup?.close();setup=null;mutable.value=RecoveryUiState()}
}
