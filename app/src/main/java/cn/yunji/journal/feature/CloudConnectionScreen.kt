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
import cn.yunji.journal.core.designsystem.JournalTextAction
import cn.yunji.journal.core.designsystem.JournalCard
import cn.yunji.journal.core.designsystem.JournalKeyValue
import cn.yunji.journal.core.designsystem.JournalSettingRow
import cn.yunji.journal.core.designsystem.JournalField
import cn.yunji.journal.core.designsystem.JournalSectionTitle
import cn.yunji.journal.core.designsystem.JournalLoading
import cn.yunji.journal.core.designsystem.JournalError

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
            if(path.isNotEmpty()) JournalTextAction("上一级目录", enabled=!state.testing, onClick=controller::parentDirectory)
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
    var cleanupDetails by remember {mutableStateOf(false)}
    val enabled=!state.loading && !state.testing
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("cloud-connection"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        JournalCard(soft = state.saved) {
            Text(if (state.saved) "云盘连接已保存" else "连接你的云盘", style = MaterialTheme.typography.titleLarge)
            Text(if (state.saved) "连接设置已加密保存在本机" else "先测试并保存连接，再决定何时上传备份", color = MaterialTheme.colorScheme.primary)
            Text("先加密再上传；恢复密钥请独立保管。", style = MaterialTheme.typography.bodySmall)
        }
        if (backup != null && (state.saved || upload != null)) JournalCard {
            JournalSectionTitle("云端备份")
            val status=backup.status
            Text(if(status.hasSuccess)"最近成功：${formatStatusTime(status.lastSuccessAt)}" else "尚未成功上传过备份",
                Modifier.testTag("cloud-backup-last"))
            if(status.lastAttemptAt>0)Text("最近尝试：${formatStatusTime(status.lastAttemptAt)} · ${phaseLabel(status.lastPhase)}",style=MaterialTheme.typography.bodySmall)
            status.lastError?.takeIf {!backup.running}?.let {JournalError("上次未能完成：$it")}
            if(backup.running){LinearProgressIndicator(Modifier.fillMaxWidth());Text(backup.progress ?: "正在备份…")}
            if (backup.running && abortBackup != null) JournalButton("取消备份", secondary = true, onClick = abortBackup)
            Text(if (backup.scheduler.autoEnabled) "自动备份：已开启" else "自动备份：已关闭", style = MaterialTheme.typography.bodySmall)
            if (cleanup != null) Text(if (cleanup.record.hasRecord && cleanup.record.ran) "云端清理：最近一次已完成" else "云端清理：等待有效备份", style = MaterialTheme.typography.bodySmall)
            // 上传结果/前置提示紧贴按钮，避免落在页面下方另一个卡片里而被视口裁掉。
            backup.message?.let { Text(it, Modifier.testTag("cloud-backup-message"),
                color = if (backup.status.lastError != null && it == backup.status.lastError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall) }
            if (state.saved && upload != null) JournalButton("立即上传备份", enabled = (state.capabilities?.verified == true || backup.status.hasSuccess) && !backup.running && !state.testing,
                onClick = upload)
        }
        JournalCard {
            JournalSectionTitle("连接设置")
            Text("支持坚果云及其他 HTTPS WebDAV 服务。连接设置只保存在本机，保存连接不会启动备份。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            JournalField(state.endpoint, { update("endpoint", it) }, "服务地址", enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.testTag("cloud-endpoint"))
            JournalField(state.username, { update("username", it) }, "账号", enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.testTag("cloud-username"))
            JournalField(state.password, { update("password", it) }, "应用密码", enabled = enabled,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = if (state.hasSavedPassword) "留空沿用已保存密码；更换服务或账号后请重填。"
                    else "坚果云用第三方应用密码；离开页面会清除未保存密码。",
                trailingIcon = { JournalTextAction(if (reveal) "隐藏" else "显示", enabled = enabled, onClick = { reveal = !reveal }) },
                modifier = Modifier.testTag("cloud-password"))
            JournalField(state.folder, { update("folder", it) }, "备份父目录（选填）", enabled = enabled,
                supportingText = "例如 家庭/孕期资料。坚果云等服务商禁止在根目录写入与删除文件，请填写一个已存在的子目录；留空仅适用于允许根目录读写的服务。",
                modifier = Modifier.testTag("cloud-folder"))
            if (browse != null) JournalTextAction("浏览云盘目录", enabled = enabled, onClick = browse)
        }
        // 原来「4 个字段 + 说明 + 加载 + 两个按钮 + 结果」共 12 个元素挤在同一张卡里，
        // 视觉上层级混乱、错误信息也容易被误读成字段校验。这里按「填写」与「执行」分开。
        JournalCard {
            JournalSectionTitle("连接测试")
            Text("测试会创建、回读并清理随机测试项目，不备份或覆盖已有资料。未能清理时会保留标记供下次重试。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.loading || state.testing) {
                JournalLoading(text = if (state.loading) "读取本机连接设置…" else "正在处理连接设置…")
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                JournalButton("测试连接", enabled = enabled, onClick = test)
                JournalButton("保存连接", enabled = enabled && state.canSave, secondary = true, onClick = save)
            }
            if (state.testing) JournalTextAction("取消", onClick = cancel)
            state.message?.let {
                Text(it, Modifier.testTag("cloud-message"), style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.capabilities?.let {caps ->
            JournalCard {
            JournalSectionTitle("连接检查")
            listOf("读取目录" to caps.readable,"写入与回读校验" to (caps.writable && caps.verified),"创建子目录" to caps.canCreateDirectory,"清理测试项目" to caps.canDelete).forEach {(label,ok)->
                JournalKeyValue(label, if(ok) "可用" else "未通过", emphasize = ok)
            }
            if(!caps.completeListing)Text("部分目录项目无法读取，不能据此进行自动清理。")
            if(caps.existingVaultCount>0)Text("专用备份目录已有${caps.existingVaultCount}项内容。保存连接不会覆盖它们，启用备份前需核对资料库。")
            if(caps.writable && !caps.canCreateDirectory)Text("当前目录可写，但无法创建子目录。请检查权限或在云盘预先建立需要的目录。")
            }
        }
        state.pendingName?.let {name ->
            Text("上次测试可能遗留临时项目：$name。使用原连接重新测试可尝试清理。",style=MaterialTheme.typography.bodySmall)
            JournalTextAction("清除本机测试标记", enabled=enabled, onClick={forgetting=true})
        }
        if(backup!=null && upload!=null) {
            JournalCard {
            JournalSectionTitle("自动备份")
            val schedulerState=backup.scheduler
            Text(if(schedulerState.role==cn.yunji.journal.data.DeviceRole.PRIMARY)"本机：主设备（唯一自动上传设备）" else "本机：非主设备，不会自动上传",
                Modifier.testTag("cloud-role"))
            if(schedulerState.blockedByOtherWriter) Box(Modifier.testTag("cloud-blocked")) {
                JournalError("检测到其他设备的新备份，自动备份已暂停。请确认主设备后继续。")
            }
            JournalSettingRow("允许本机自动备份", schedulerState.autoEnabled && schedulerState.role==cn.yunji.journal.data.DeviceRole.PRIMARY,
                setAuto, enabled=!backup.running, switchModifier=Modifier.testTag("cloud-auto"))
            JournalSettingRow("仅 Wi-Fi 上传", schedulerState.wifiOnly, setWifi,
                enabled=!backup.running, switchModifier=Modifier.testTag("cloud-wifi"))
            if(schedulerState.role!=cn.yunji.journal.data.DeviceRole.PRIMARY || schedulerState.blockedByOtherWriter)
                JournalTextAction("将此设备设为主设备", Modifier.testTag("cloud-make-primary"), enabled=!backup.running, onClick=makePrimary)
            backup.autoBlockedReason?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Text("修改资料后会自动备份；失败时会稍后重试。换机恢复后，请先核对资料，再开启自动备份。",style=MaterialTheme.typography.bodySmall)
            }
            if(cleanup!=null && runCleanup!=null) {
                JournalCard {
                JournalSectionTitle("云端清理")
                val record=cleanup.record
                Text(if(record.hasRecord) "上次尝试：${formatCleanupTime(record.attemptedAt)} · ${if(record.ran) "清理完成" else "未清理"}"
                    else "尚未清理，备份成功后每天最多自动清理一次",Modifier.testTag("cloud-cleanup-last"))
                if(record.hasRecord && record.ran) {
                    Text("已保留 ${record.kept} 份备份，清理完成。", modifier=Modifier.testTag("cloud-cleanup-summary"))
                    JournalTextAction(if(cleanupDetails) "收起清理详情" else "查看清理详情", Modifier.testTag("cloud-cleanup-details"), onClick={cleanupDetails=!cleanupDetails})
                    if(cleanupDetails) Text("保留快照 ${record.kept} · 删除提交 ${record.commitsDeleted} · 删除对象 ${record.objectsDeleted} · 新登记孤立 ${record.orphansTracked}",
                        style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("cloud-cleanup-counts"))
                }
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
                cleanupBlocked?.let {JournalError(it, Modifier.testTag("cloud-cleanup-blocked"))}
                JournalButton("立即清理",enabled=cleanupBlocked==null && !cleanup.running,onClick={cleaning=true})
                Text("清理过期的云端备份和不再使用的备份文件。本机资料、历史资料库及其他设备目录会保留。",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        JournalTextAction(if(state.saved)"清除本机连接" else "重置连接设置", enabled = enabled, danger = true, onClick = { removing = true })
        Text("服务证书必须有效；应用不会忽略证书错误。云盘端仍能看到加密文件大小、数量和访问时间。",style=MaterialTheme.typography.bodySmall)
    }
    if(removing)AlertDialog(onDismissRequest={removing=false},title={Text("清除本机连接？")},text={Text("本机凭据会移除，云盘文件和本机孕期记录保持不变。")},confirmButton={TextButton({removing=false;disconnect()}){Text("清除")}},dismissButton={TextButton({removing=false}){Text("取消")}})
    if(forgetting)AlertDialog(onDismissRequest={forgetting=false},title={Text("清除测试标记？")},text={Text("这不会删除云端临时项目；请在云盘核对并自行清理。此操作只影响本机测试标记。")},confirmButton={TextButton({forgetting=false;forget()}){Text("清除标记")}},dismissButton={TextButton({forgetting=false}){Text("取消")}})
    if(cleaning && runCleanup!=null)AlertDialog(onDismissRequest={cleaning=false},
        title={Text("立即清理云端备份？")},
        text={Text("仅清理按保留规则过期的云端备份和不再使用的备份文件。本机资料、历史资料库和其他设备目录会保留；清理失败不会影响已有备份。")},
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
