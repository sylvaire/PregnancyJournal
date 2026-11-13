package cn.yunji.journal.feature

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.designsystem.JournalEmptyState
import cn.yunji.journal.core.security.*
import kotlinx.coroutines.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val LocalSafetyActionsAllowed=staticCompositionLocalOf {true}

@Composable internal fun SafetyCopiesPanel(vaultId: String,copyStore: SafetyCopyStore?=null) {
    val context=LocalContext.current.applicationContext
    val store=remember(context,copyStore){copyStore ?: SafetyCopyStore(File(context.noBackupFilesDir,"vault"))}
    val scope=rememberCoroutineScope()
    var copies by remember {mutableStateOf<List<SafetyCopy>>(emptyList())}
    var loading by remember {mutableStateOf(true)}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var selected by remember {mutableStateOf<SafetyCopy?>(null)}
    var active by remember {mutableStateOf(false)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun refresh() {scope.launch {
        loading=true
        try {copies=withContext(Dispatchers.IO){store.list()};message=null}
        catch(e: CancellationException){throw e}
        catch(_:Exception){copies=emptyList();message="暂时无法读取安全副本，请完成恢复后重试。"}
        finally {loading=false}
    }}
    DisposableEffect(lifecycle,store) {
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_RESUME){active=true;refresh()}
            if(event==Lifecycle.Event.ON_PAUSE){active=false;selected=null}
        }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)){active=true;refresh()}
        onDispose {lifecycle.removeObserver(observer)}
    }
    val launch=LocalFileLaunch.current
    val allowed=LocalSafetyActionsAllowed.current
    LaunchedEffect(allowed){if(!allowed)selected=null}
    SafetyCopiesContent(copies,loading,busy,message,::refresh,
        export={copy->launch?.invoke(FileRequest("SAFETY_EXPORT",ownerId=vaultId,targetId=copy.id,revision=copy.modifiedAt,sourceSize=copy.size),"孕迹安全副本-${copy.id.take(8)}.yunji")},
        delete={if(allowed && active)selected=it},canExport=launch!=null && active && allowed)
    selected?.takeIf {active && allowed}?.let {copy->AlertDialog(
        onDismissRequest={if(!busy)selected=null},
        title={Text("删除这份安全副本？")},
        text={Text("删除后无法撤销，将无法再用这份本机副本找回恢复前的资料。当前资料和已导出的文件保留。请先确认已另存需要的副本。")},
        confirmButton={TextButton(enabled=!busy,onClick={
            if(!allowed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))return@TextButton
            busy=true
            scope.launch {
                try {withContext(Dispatchers.IO){store.delete(copy)};selected=null;refresh()}
                catch(e:CancellationException){throw e}
                catch(_:Exception){selected=null;message="副本未能删除或已变化，请刷新后核对。"}
                finally {busy=false}
            }
        }){Text("确认删除")}},
        dismissButton={TextButton(enabled=!busy,onClick={selected=null}){Text("保留")}},
        properties=DialogProperties(securePolicy=SecureFlagPolicy.SecureOn))}
}

@Composable internal fun SafetyCopiesContent(copies: List<SafetyCopy>,loading: Boolean,busy: Boolean,message: String?,refresh: ()->Unit,
    export: (SafetyCopy)->Unit,delete: (SafetyCopy)->Unit,canExport: Boolean=true) {
    HorizontalDivider()
    Text("恢复前的安全副本",style=MaterialTheme.typography.titleMedium)
    Text("恢复前自动保留在本机，卸载应用会一并移除。每份副本需要当时对应的恢复密钥，可能与当前密钥不同。",style=MaterialTheme.typography.bodySmall)
    if(loading)Text("正在读取安全副本…")
    else if(copies.isEmpty() && message==null)JournalEmptyState("还没有安全副本")
    copies.forEach {copy->
        Card(Modifier.fillMaxWidth().testTag("safety-${copy.id}")) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            val date=DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm",Locale.CHINA).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(copy.modifiedAt))
            Text(date,style=MaterialTheme.typography.titleSmall)
            Text("${android.text.format.Formatter.formatFileSize(LocalContext.current,copy.size)} · ${copy.id.take(8)}",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={export(copy)},enabled=!busy && canExport,modifier=Modifier.testTag("safety-export-${copy.id}")){Text("导出副本")}
                TextButton(onClick={delete(copy)},enabled=!busy,modifier=Modifier.testTag("safety-delete-${copy.id}")){Text("删除")}
            }
        }}
    }
    message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    TextButton(refresh,enabled=!loading && !busy){Text("刷新安全副本")}
}
