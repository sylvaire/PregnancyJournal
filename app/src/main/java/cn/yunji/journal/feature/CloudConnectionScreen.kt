package cn.yunji.journal.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunji.journal.core.designsystem.JournalButton
import cn.yunji.journal.core.designsystem.JournalCard
import cn.yunji.journal.core.designsystem.JournalSwitch

@Composable
fun CloudConnectionScreen(controller: CloudConnectionController, backup: CloudBackupController, cleanup: CloudCleanupController? = null) {
    val state by controller.state.collectAsStateWithLifecycle()
    val backupState by backup.state.collectAsStateWithLifecycle()
    val cleanupState = if (cleanup != null) cleanup.state.collectAsStateWithLifecycle().value else null
    val runCleanup: (() -> Unit)? = cleanup?.let { target -> { target.run() } }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller,backup,cleanup,lifecycle) {
        val observer=LifecycleEventObserver {_,event->when(event){
            Lifecycle.Event.ON_RESUME->{controller.load();backup.refresh();cleanup?.refresh()}
            Lifecycle.Event.ON_PAUSE->{controller.leave();backup.leave();cleanup?.leave()}
            else->Unit}}
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)){controller.load();backup.refresh();cleanup?.refresh()}
        onDispose {lifecycle.removeObserver(observer);controller.leave();backup.leave();cleanup?.leave()}
    }
    CloudConnectionContent(state,controller::update,controller::test,controller::save,controller::cancel,controller::disconnect,controller::forgetCleanup,
        {controller.browse()},backupState,backup::upload,backup::cancel,
        setAuto=backup::setAutoEnabled,setWifi=backup::setWifiOnly,makePrimary={backup.setPrimary(true)},
        cleanup=cleanupState,runCleanup=runCleanup)
    state.browserPath?.let {path ->
        AlertDialog(onDismissRequest=controller::closeBrowser,title={Text("选择备份父目录")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(path.isEmpty())"服务根目录" else path.joinToString("/"),maxLines=2,overflow=TextOverflow.Ellipsis)
            if(state.testing)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(path.isNotEmpty())TextButton(controller::parentDirectory,enabled=!state.testing){Text("上一级目录")}
            if(!state.directoryComplete && !state.testing)Text(state.message ?: "部分子目录不可读取，请核对云盘权限。")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=320.dp)) {
                items(state.directoryNames,key={it},contentType={"directory"}) {name->TextButton({controller.chooseDirectory(name)},enabled=!state.testing){Text(name,maxLines=2,overflow=TextOverflow.Ellipsis)}}
                if(state.directoryNames.isEmpty() && !state.testing && state.directoryComplete)item {Text("没有可显示的子目录，可使用当前目录。")}
            }
        }},confirmButton={TextButton(controller::useDirectory,enabled=!state.testing && state.message==null){Text("使用此目录")}},dismissButton={TextButton(controller::closeBrowser){Text("取消")}})
    }
}

@Composable
internal fun CloudConnectionContent(state: CloudUiState,update: (String,String)->Unit,test: ()->Unit,save: ()->Unit,cancel: ()->Unit,disconnect: ()->Unit,forget: ()->Unit,browse: (()->Unit)?=null,
    backup: CloudBackupUiState?=null,upload: (()->Unit)?=null,abortBackup: (()->Unit)?=null,
    setAuto: (Boolean)->Unit={},setWifi: (Boolean)->Unit={},makePrimary: ()->Unit={},
    cleanup: CloudCleanupUiState?=null,runCleanup: (()->Unit)?=null) {
    var reveal by remember {mutableStateOf(false)}
    var removing by remember {mutableStateOf(false)}
    var forgetting by remember {mutableStateOf(false)}
    var cleaning by remember {mutableStateOf(false)}
    val enabled=!state.loading && !state.testing
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("cloud-connection"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        JournalCard(soft = true) {
            Text(if (state.saved) "加密备份已准备好" else "连接你的云盘", style = MaterialTheme.typography.titleLarge)
            Text(if (state.saved) "连接设置已加密保存在本机" else "先测试并保存连接，再决定何时上传备份", color = MaterialTheme.colorScheme.primary)
            Text("先加密再上传；恢复密钥请独立保管。", style = MaterialTheme.typography.bodySmall)
        }
        if (state.saved && backup != null) JournalCard {
            Text("备份状态", style = MaterialTheme.typography.titleMedium)
            Text(if (backup.status.hasSuccess) "最近上传：${formatStatusTime(backup.status.lastSuccessAt)}" else "最近上传：暂无")
            Text(if (backup.scheduler.autoEnabled) "自动备份：已开启" else "自动备份：已关闭", style = MaterialTheme.typography.bodySmall)
            if (cleanup != null) Text(if (cleanup.record.hasRecord && cleanup.record.ran) "云端清理：最近一次已完成" else "云端清理：等待有效备份", style = MaterialTheme.typography.bodySmall)
            // 上传结果/前置提示紧贴按钮，避免落在页面下方另一个卡片里而被视口裁掉。
            backup.message?.let { Text(it, Modifier.testTag("cloud-backup-message"),
                color = if (backup.status.lastError != null && it == backup.status.lastError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall) }
            if (upload != null) JournalButton("立即上传备份", enabled = (state.capabilities?.verified == true || backup.status.hasSuccess) && !backup.running && !state.testing,
                onClick = upload)
        }
        Text("连接设置", style = MaterialTheme.typography.titleMedium)
        Text("支持坚果云及其他HTTPS WebDAV服务。连接设置只保存在本机，保存连接不会启动备份。")
        OutlinedTextField(state.endpoint,{update("endpoint",it)},label={Text("服务地址")},singleLine=true,enabled=enabled,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth().testTag("cloud-endpoint"))
        OutlinedTextField(state.username,{update("username",it)},label={Text("账号")},singleLine=true,enabled=enabled,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth().testTag("cloud-username"))
        OutlinedTextField(state.password,{update("password",it)},label={Text("应用密码")},singleLine=true,enabled=enabled,
            visualTransformation=if(reveal)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
            supportingText={Text(if(state.hasSavedPassword)"留空沿用已保存密码；更换服务或账号后请重填。" else "坚果云用第三方应用密码；离开页面会清除未保存密码。")},
            trailingIcon={TextButton({reveal=!reveal},enabled=enabled){Text(if(reveal)"隐藏" else "显示")}},modifier=Modifier.fillMaxWidth().testTag("cloud-password"))
        OutlinedTextField(state.folder,{update("folder",it)},label={Text("备份父目录（选填）")},singleLine=true,enabled=enabled,
            supportingText={Text("例如 家庭/孕期资料。坚果云等服务商禁止在根目录写入与删除文件，请填写一个已存在的子目录；留空仅适用于允许根目录读写的服务。")},modifier=Modifier.fillMaxWidth().testTag("cloud-folder"))
        if(browse!=null)TextButton(browse,enabled=enabled){Text("浏览云盘目录")}
        Text("测试会创建、回读并清理随机测试项目，不备份或覆盖已有资料。未能清理时会保留标记供下次重试。",style=MaterialTheme.typography.bodySmall)
        if(state.loading || state.testing) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(if(state.loading)"读取本机连接设置…" else "正在处理连接设置…")}
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            JournalButton("测试连接",Modifier.weight(1f),enabled=enabled,onClick=test)
            JournalButton("保存连接",Modifier.weight(1f),enabled=enabled && state.canSave,secondary=true,onClick=save)
        }
        if(state.testing)TextButton(cancel){Text("取消")}
        state.message?.let {Text(it,Modifier.testTag("cloud-message"),style=MaterialTheme.typography.bodyMedium)}
        state.capabilities?.let {caps ->
            JournalCard {
            Text("连接检查",style=MaterialTheme.typography.titleLarge)
            listOf("读取目录" to caps.readable,"写入与回读校验" to (caps.writable && caps.verified),"创建子目录" to caps.canCreateDirectory,"清理测试项目" to caps.canDelete).forEach {(label,ok)->
                Row(Modifier.fillMaxWidth()) {Text(label,Modifier.weight(1f));Text(if(ok)"可用" else "未通过",color=if(ok)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            if(!caps.completeListing)Text("部分目录项目无法读取，不能据此进行自动清理。")
            if(caps.existingVaultCount>0)Text("专用备份目录已有${caps.existingVaultCount}项内容。保存连接不会覆盖它们，启用备份前需核对资料库。")
            if(caps.writable && !caps.canCreateDirectory)Text("当前目录可写，但无法创建子目录。请检查权限或在云盘预先建立需要的目录。")
            }
        }
        state.pendingName?.let {name ->
            Text("上次测试可能遗留临时项目：$name。使用原连接重新测试可尝试清理。",style=MaterialTheme.typography.bodySmall)
            TextButton({forgetting=true},enabled=enabled){Text("清除本机测试标记")}
        }
        if(backup!=null && upload!=null) {
            JournalCard {
            Text("云端备份",style=MaterialTheme.typography.titleLarge)
            val status=backup.status
            Text(if(status.hasSuccess)"最近成功：${formatStatusTime(status.lastSuccessAt)}" else "尚未成功上传过备份",
                Modifier.testTag("cloud-backup-last"))
            if(status.lastAttemptAt>0)Text("最近尝试：${formatStatusTime(status.lastAttemptAt)} · ${phaseLabel(status.lastPhase)}",style=MaterialTheme.typography.bodySmall)
            status.lastError?.takeIf {!backup.running}?.let {Text("上次未能完成：$it",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(backup.running){LinearProgressIndicator(Modifier.fillMaxWidth());Text(backup.progress ?: "正在备份…")}
            if (backup.running && abortBackup != null) JournalButton("取消备份", secondary = true, onClick = abortBackup)
            }
            JournalCard {
            Text("自动备份",style=MaterialTheme.typography.titleMedium)
            val schedulerState=backup.scheduler
            Text(if(schedulerState.role==cn.yunji.journal.data.DeviceRole.PRIMARY)"本机：主设备（唯一自动上传设备）" else "本机：非主设备，不会自动上传",
                Modifier.testTag("cloud-role"))
            if(schedulerState.blockedByOtherWriter) Text("检测到其他设备的新备份，自动备份已暂停。请确认主设备后继续。",
                color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("cloud-blocked"))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("允许本机自动备份")
                JournalSwitch(schedulerState.autoEnabled && schedulerState.role==cn.yunji.journal.data.DeviceRole.PRIMARY,
                    onCheckedChange=setAuto, enabled=!backup.running, modifier=Modifier.testTag("cloud-auto"))
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("仅 Wi-Fi 上传")
                JournalSwitch(schedulerState.wifiOnly,onCheckedChange=setWifi,enabled=!backup.running,modifier=Modifier.testTag("cloud-wifi"))
            }
            if(schedulerState.role!=cn.yunji.journal.data.DeviceRole.PRIMARY || schedulerState.blockedByOtherWriter)
                TextButton(makePrimary,enabled=!backup.running,modifier=Modifier.testTag("cloud-make-primary")){Text("将此设备设为主设备")}
            backup.autoBlockedReason?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Text("本机修改会在短暂合并后触发唯一备份任务；失败按指数退避重试。恢复到新设备时自动备份默认关闭，需明确选择主设备。",style=MaterialTheme.typography.bodySmall)
            }
            if(cleanup!=null && runCleanup!=null) {
                JournalCard {
                Text("云端清理",style=MaterialTheme.typography.titleMedium)
                val record=cleanup.record
                Text(if(record.hasRecord) "上次尝试：${formatCleanupTime(record.attemptedAt)} · ${if(record.ran) "清理完成" else "未清理"}"
                    else "尚未清理，备份成功后每天最多自动清理一次",Modifier.testTag("cloud-cleanup-last"))
                if(record.hasRecord && record.ran) Text("保留快照 ${record.kept} · 删除提交 ${record.commitsDeleted} · 删除对象 ${record.objectsDeleted} · 新登记孤立 ${record.orphansTracked}",
                    style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("cloud-cleanup-counts"))
                if(record.hasRecord && !record.ran && record.message.isNotBlank()) Text("未清理原因：${record.message}",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("cloud-cleanup-skip"))
                if(cleanup.running){LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在清理云端备份…",modifier=Modifier.testTag("cloud-cleanup-progress"))}
                if(!cleanup.running)cleanup.message?.let {Text(it,Modifier.testTag("cloud-cleanup-message"))}
                val cleanupBlocked=when {
                    !state.saved->"请先保存云盘连接"
                    state.capabilities?.verified!=true->"当前云盘连接未通过写入与回读校验，不能清理云端备份"
                    state.testing||state.loading->"正在处理连接设置，暂不能清理"
                    backup.scheduler.blockedByOtherWriter->"检测到其他设备的备份，已暂停自动清理"
                    backup.running->"正在备份，完成后再清理"
                    else->null
                }
                cleanupBlocked?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("cloud-cleanup-blocked"))}
                JournalButton("立即清理",enabled=cleanupBlocked==null && !cleanup.running,onClick={cleaning=true})
                Text("只删除云端按保留策略淘汰的旧快照与无引用对象，不删除本机资料、历史资料库或其他设备目录。",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        TextButton({removing=true},enabled=enabled){Text(if(state.saved)"清除本机连接" else "重置连接设置",color=MaterialTheme.colorScheme.error)}
        Text("服务证书必须有效；应用不会忽略证书错误。云盘端仍能看到加密文件大小、数量和访问时间。",style=MaterialTheme.typography.bodySmall)
    }
    if(removing)AlertDialog(onDismissRequest={removing=false},title={Text("清除本机连接？")},text={Text("本机凭据会移除，云盘文件和本机孕期记录保持不变。")},confirmButton={TextButton({removing=false;disconnect()}){Text("清除")}},dismissButton={TextButton({removing=false}){Text("取消")}})
    if(forgetting)AlertDialog(onDismissRequest={forgetting=false},title={Text("清除测试标记？")},text={Text("这不会删除云端临时项目；请在云盘核对并自行清理。此操作只影响本机测试标记。")},confirmButton={TextButton({forgetting=false;forget()}){Text("清除标记")}},dismissButton={TextButton({forgetting=false}){Text("取消")}})
    if(cleaning && runCleanup!=null)AlertDialog(onDismissRequest={cleaning=false},
        title={Text("立即清理云端备份？")},
        text={Text("只删除云端按保留策略淘汰的旧快照与无引用对象，不删除本机资料、历史资料库或其他设备目录。清理失败不会影响已有备份。")},
        confirmButton={TextButton({cleaning=false;runCleanup()},modifier=Modifier.testTag("cloud-cleanup-confirm")){Text("开始清理")}},
        dismissButton={TextButton({cleaning=false}){Text("取消")}})
}

private fun formatStatusTime(value: Long): String = displayInstant(value, java.time.ZoneId.systemDefault().id)

private fun phaseLabel(phase: String): String = when(phase) {
    "STARTED"->"已开始"
    "SEQUENCE_RESERVED","CAPTURED"->"已预留备份序号"
    "ARCHIVE_BUILDING"->"正在制作快照"
    "SELF_CHECKED"->"快照自检通过"
    "CHECKING_DESTINATION"->"正在核对云端目录"
    "UPLOADING_OBJECTS"->"正在上传原件"
    "VERIFYING"->"正在回读校验"
    "COMMITTING"->"正在发布提交"
    "COMMITTED"->"提交成功"
    "FAILED"->"未完成"
    else->phase
}
