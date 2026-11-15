package cn.yunji.journal.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunji.journal.core.backup.ReclaimInfo
import cn.yunji.journal.core.backup.ReclaimState
import cn.yunji.journal.core.backup.RestoreReclaim
import cn.yunji.journal.core.designsystem.JournalButton
import cn.yunji.journal.core.designsystem.JournalDivider
import cn.yunji.journal.core.designsystem.JournalError
import cn.yunji.journal.core.designsystem.JournalLoading
import cn.yunji.journal.core.designsystem.JournalSectionTitle
import cn.yunji.journal.core.designsystem.JournalCard
import cn.yunji.journal.core.designsystem.JournalField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun RecoveryScreen(controller: RecoveryController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller,lifecycle) {
        val observer=LifecycleEventObserver {_,event->when(event){Lifecycle.Event.ON_RESUME->controller.load();Lifecycle.Event.ON_PAUSE->controller.leave();else->Unit}}
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))controller.load()
        onDispose {lifecycle.removeObserver(observer);controller.leave()}
    }
    val launch=cn.yunji.journal.core.security.LocalFileLaunch.current
    RecoveryContent(state,controller::generate,controller::verify,controller::answer,controller::confirm,controller::load,
        if(launch==null)null else {{launch(cn.yunji.journal.core.security.FileRequest("BACKUP",ownerId=controller.vaultId),"孕迹加密备份-${java.time.LocalDate.now()}.yunji")}},LocalRestoreLaunch.current,extra={SafetyCopiesPanel(controller.vaultId);ReclaimPreviousPanel()})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun RecoveryContent(state: RecoveryUiState,generate: ()->Unit,verify: ()->Unit,
    answer: (Int,String)->Unit,confirm: ()->Unit,retry: ()->Unit,export: (()->Unit)?=null,restore: (()->Unit)?=null,extra: @Composable ()->Unit={}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("recovery-screen"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        when(state.stage) {
            "LOADING"->JournalLoading()
            "INTRO"->{
                JournalCard(soft = true) {
                    Text("先保存恢复密钥", style = MaterialTheme.typography.titleLarge)
                    Text("尚未确认恢复密钥", color = MaterialTheme.colorScheme.primary)
                    Text("先妥善保存恢复密钥，再导出可迁移的加密备份。", style = MaterialTheme.typography.bodySmall)
                }
                JournalCard {
                    Text("恢复密钥用于在新设备上解密备份。请抄录到安全的地方，与备份分开保管。")
                    Text("如果设备丢失且恢复密钥遗失，将无法解密备份。离开页面或锁定后，未确认的密钥会作废。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    JournalButton("生成恢复密钥",Modifier.testTag("recovery-generate"),onClick=generate)
                }
            }
            "SHOW"->{
                JournalCard {
                    Text("请完整抄录下面的密钥，包括开头的 YJ1:，并保留大小写。下一步将隐藏密钥并核对其中两组。")
                    Text("YJ1:",fontFamily=FontFamily.Monospace)
                    state.key.removePrefix("YJ1:").chunked(6).forEachIndexed {index,value->
                        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("第${index+1}组",Modifier.padding(end = 16.dp),style=MaterialTheme.typography.labelLarge)
                            Text(value,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.titleLarge)
                        }
                    }
                    Text("密钥不会随备份明文保存，请先确认已完整抄录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    JournalButton("已保存，开始核对",Modifier.testTag("recovery-verify"),onClick=verify)
                }
            }
            "VERIFY"->{
                JournalCard {
                    Text("请根据已保存的密钥填写下面两组，每组六个字符，区分大小写。")
                    state.groups.forEachIndexed {index,group->
                        JournalField(if(index==0)state.first else state.second,{answer(index,it)}, "第${group}组",
                            enabled=!state.busy,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),
                            modifier=Modifier.testTag("recovery-answer-$index"))
                    }
                    JournalButton(if(state.busy)"正在保存…" else "确认并保存",Modifier.testTag("recovery-confirm"),enabled=!state.busy && state.first.length==6 && state.second.length==6,onClick=confirm)
                }
            }
            "READY"->{
                JournalCard(soft = true) {
                    Text("备份准备就绪", style = MaterialTheme.typography.titleLarge)
                    Text("恢复密钥已确认", color = MaterialTheme.colorScheme.primary)
                    Text("完整密钥不会再次显示，请独立保管。", style = MaterialTheme.typography.bodySmall)
                }
                JournalCard {
                    Text("备份包含已保存记录、原件和回收站内容，不包含未提交草稿。导出前会校验数据，恢复密钥需单独保管。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    export?.let {JournalButton("导出加密备份",Modifier.testTag("backup-export"),onClick=it)}
                }
            }
            "ERROR"->JournalButton("重试",onClick=retry)
        }
        if(state.stage in setOf("INTRO","READY"))restore?.let {action->JournalButton("从备份恢复资料",Modifier.testTag("restore-entry"),secondary=true,onClick=action)}
        if(state.stage in setOf("INTRO","READY"))extra()
        state.message?.let {JournalError(it)}
    }
}

/** UI state of the "reclaim the generation kept by the last restore" panel. Pure data, UI-testable. */
internal data class ReclaimPanelState(
    val available: Boolean = false,
    val busy: Boolean = false,
    val confirming: Boolean = false,
    val message: String? = null,
    val canAct: Boolean = true,
)

/**
 * Offers reclaiming the generation that the last restore kept for rollback. Rendered only when the
 * core layer reports an existing previous generation (and no restore is pending). Reclaiming needs
 * an explicit confirmation that cannot be dismissed by tapping outside, and the action is disabled
 * while the app is covered by the system recents screen ([canAct]).
 */
@Composable internal fun ReclaimPreviousContent(
    state: ReclaimPanelState,
    onReclaim: ()->Unit,
    onConfirm: ()->Unit,
    onDismiss: ()->Unit,
) {
    if(!state.available && state.message==null)return
    JournalDivider(Modifier.padding(vertical = 8.dp))
    JournalSectionTitle("恢复前的旧资料")
    if(state.available){
        Text("上次恢复完成后，恢复前的旧资料仍保留在本机，用于回退核对。确认不再需要回退时，可以回收以释放空间。",style=MaterialTheme.typography.bodyMedium)
        JournalButton(if(state.busy)"正在回收…" else "回收上一代资料",Modifier.testTag("reclaim-previous"),enabled=state.canAct && !state.busy,secondary=true,onClick=onReclaim)
    }
    state.message?.let {JournalError(it)}
    if(state.confirming && state.available){
        AlertDialog(onDismissRequest={if(!state.busy)onDismiss()},title={Text("回收恢复前的旧资料？")},
            text={Text("回收会删除恢复前的旧资料代际，无法撤销；当前资料不受影响。本机安全副本与已导出的备份不受影响。请确认不再需要回退到恢复前的资料。")},
            confirmButton={TextButton(onConfirm,Modifier.testTag("reclaim-confirm"),enabled=!state.busy){Text("确认回收")}},
            dismissButton={TextButton(onDismiss,Modifier.testTag("reclaim-cancel"),enabled=!state.busy){Text("保留")}},
            properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn))
    }
}

/**
 * Production wiring for [ReclaimPreviousContent]: inspects the vault on resume, reclaims only through
 * [RestoreReclaim] (which re-checks the journal and the active generation), and re-reads state after
 * any attempt so a partial failure shows the truth instead of an optimistic message.
 */
@Composable internal fun ReclaimPreviousPanel() {
    val context=LocalContext.current.applicationContext
    val reclaimer=remember(context){RestoreReclaim(File(context.noBackupFilesDir,"vault"))}
    val scope=rememberCoroutineScope()
    val allowed=LocalSafetyActionsAllowed.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var info by remember(reclaimer){mutableStateOf<ReclaimInfo?>(null)}
    var busy by remember(reclaimer){mutableStateOf(false)}
    var confirming by remember(reclaimer){mutableStateOf(false)}
    var message by remember(reclaimer){mutableStateOf<String?>(null)}
    fun refresh() {
        scope.launch {
            try {
                val next=withContext(Dispatchers.IO){reclaimer.inspect()}
                info=next
                if(next.state==ReclaimState.AVAILABLE){message=null;confirming=false}
                else {confirming=false;message=next.message ?: message}
            } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(_:Exception){info=null;confirming=false;message="暂时无法读取旧代际状态，现有资料已保留。"}
        }
    }
    DisposableEffect(reclaimer,lifecycle) {
        val observer=LifecycleEventObserver {_,event->when(event){
            Lifecycle.Event.ON_RESUME->refresh()
            Lifecycle.Event.ON_PAUSE->confirming=false
            else->Unit}}
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))refresh()
        onDispose {lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(allowed){if(!allowed)confirming=false}
    val available=info?.state==ReclaimState.AVAILABLE
    ReclaimPreviousContent(
        state=ReclaimPanelState(available=available,busy=busy,confirming=confirming,message=message ?: info?.message,canAct=allowed),
        onReclaim={if(allowed && !busy)confirming=true},
        onConfirm={
            if(allowed && !busy){
                busy=true;confirming=false
                scope.launch {
                    try{withContext(Dispatchers.IO){reclaimer.reclaim()};message="已回收恢复前的旧资料代际，当前资料不受影响。"}
                    catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
                    catch(_:Exception){message="暂时无法完成回收，当前资料与旧代际均已保留，请稍后重试。"}
                    finally{busy=false;refresh()}
                }
            }
        },
        onDismiss={if(!busy)confirming=false},
    )
}
