package cn.yunji.journal.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.yunji.journal.core.designsystem.JournalButton
import cn.yunji.journal.core.designsystem.JournalError
import cn.yunji.journal.core.designsystem.JournalCard
import cn.yunji.journal.core.designsystem.JournalKeyValue
import cn.yunji.journal.core.designsystem.JournalField
import cn.yunji.journal.core.designsystem.JournalLoading
import cn.yunji.journal.core.designsystem.JournalSectionTitle
import cn.yunji.journal.core.designsystem.JournalTextAction
import cn.yunji.journal.core.designsystem.JournalCheckboxRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val LocalRestoreLaunch=androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> {null}

/** Stateless secure-host content. Activity owns file grants, lifecycle cancellation and replacing its old model. */
@Composable internal fun RestoreContent(state: RestoreUiState,chooseFile: ()->Unit,keyChanged: (String)->Unit,
    prepare: ()->Unit,acknowledge: (Boolean)->Unit,confirm: ()->Unit,cancel: ()->Unit,reopen: ()->Unit,hostBusy: Boolean=false,
    startCloud: ()->Unit={},findCloud: ()->Unit={},selectSnapshot: (String)->Unit={}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("restore-screen"),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        when(state.stage) {
            "SELECT"->{
                JournalCard(soft = true) {
                    Text("从加密备份找回资料",style=MaterialTheme.typography.titleLarge)
                    Text("选择孕迹导出的 .yunji 文件，并输入与备份对应的恢复密钥。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("恢复会整体替换当前记录，不会合并。确认恢复前，会为当前资料制作并校验本机安全副本。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                JournalButton("选择加密备份",Modifier.testTag("restore-choose"),enabled=!hostBusy,onClick=chooseFile)
                JournalButton("从云端备份恢复",Modifier.testTag("restore-cloud"),enabled=!hostBusy,secondary=true,onClick=startCloud)
            }
            "KEY"->{
                JournalCard {
                    JournalSectionTitle("输入恢复密钥")
                    JournalKeyValue("已选备份", state.documentName ?: "已选择的加密备份")
                    Text(state.documentSize?.let {android.text.format.Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current,it)} ?: "文件大小暂不可用",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("请输入完整的 YJ1: 恢复密钥，区分大小写。离开页面后需要重新输入。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    JournalField(state.key, keyChanged, "恢复密钥",
                        visualTransformation=PasswordVisualTransformation(),
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),
                        modifier=Modifier.testTag("restore-key"))
                }
                JournalButton("校验备份",Modifier.testTag("restore-verify"),enabled=state.key.isNotBlank(),onClick=prepare)
                JournalTextAction("重新选择文件", onClick=chooseFile)
            }
            "CLOUD"->{
                JournalCard {
                    JournalSectionTitle("从云端备份恢复")
                    Text("输入该备份对应的恢复密钥，应用只会列出这个密钥能够打开的云端资料库。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    JournalField(state.key, keyChanged, "恢复密钥", enabled=!state.finding,
                        visualTransformation=PasswordVisualTransformation(),
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),
                        modifier=Modifier.testTag("restore-cloud-key"))
                }
                if(state.finding)JournalLoading(Modifier.testTag("restore-cloud-progress"))
                JournalButton(if(state.finding)"正在查找…" else "查找云端备份",Modifier.testTag("restore-cloud-find"),enabled=state.key.isNotBlank() && !state.finding,onClick=findCloud)
                Text("需要已保存且通过校验的云盘连接。下载过程中不会替换当前资料。",style=MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                JournalTextAction("返回", Modifier.testTag("restore-cloud-cancel"), onClick=cancel)
            }
            "CLOUD_LIST"->{
                JournalCard(soft = true) {
                    Text("选择要恢复的云端备份",style=MaterialTheme.typography.titleLarge)
                    Text("显示的是这个恢复密钥能打开的云端快照。恢复会整体替换当前记录。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.cloudSnapshots.forEach {snapshot->
                    val date=displayInstant(snapshot.createdAt, ZoneId.systemDefault().id)
                    JournalButton(if(state.snapshotId==snapshot.snapshotId)"已选择：$date（序号 ${snapshot.capturedSequence}）" else "$date（序号 ${snapshot.capturedSequence}）",
                        Modifier.testTag("restore-cloud-${snapshot.snapshotId}"),enabled=!hostBusy,secondary=true,onClick={selectSnapshot(snapshot.snapshotId)})
                }
                JournalButton("下载并校验",Modifier.testTag("restore-cloud-download"),
                    enabled=state.snapshotId!=null && state.key.isNotBlank() && !hostBusy,onClick=prepare)
                Text("下载完成后仍需在下一步确认才会替换资料。",style=MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                JournalTextAction("返回", Modifier.testTag("restore-cloud-back"), onClick=cancel)
            }
            "VERIFYING"->{
                JournalCard {
                    JournalLoading(Modifier.testTag("restore-progress"))
                    JournalSectionTitle("正在检查备份和原件")
                    Text("此时不会替换当前资料。文件较大时请稍候。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    JournalTextAction("取消校验", Modifier.testTag("restore-cancel"), onClick=cancel)
                }
            }
            "CONFIRM"->state.preview?.let {preview->
                val date=displayInstant(preview.createdAt, ZoneId.systemDefault().id)
                JournalCard(soft = true) {
                    Text("核对要恢复的资料",style=MaterialTheme.typography.titleLarge)
                    state.documentName?.let {JournalKeyValue("备份文件",it)}
                    Text("备份时间：$date", color = MaterialTheme.colorScheme.primary)
                }
                JournalCard {
                    listOf("孕期档案" to preview.pregnancies,"产检记录" to preview.visits,"资料原件" to preview.documents,
                        "用药方案" to preview.medicationPlans,"服药记录" to preview.intakes,"健康记录" to preview.measurements,
                        "日常备注" to preview.journalEntries,"自定义提醒" to preview.customReminders).forEach {(label,count)->
                        JournalKeyValue(label, count.toString())
                    }
                }
                JournalCard {
                    Text("数量包含备份中的回收站记录。当前未提交的草稿不会进入安全副本。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("如当前资料尚未设置恢复密钥，安全副本将使用本次输入的密钥；已有恢复密钥保持不变。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                JournalCheckboxRow(state.acknowledged, acknowledge, "我理解当前记录会被整体替换，并保留本机安全副本。",
                    modifier = Modifier.testTag("restore-acknowledge"))
                JournalButton("确认恢复",Modifier.testTag("restore-confirm"),enabled=state.acknowledged,onClick=confirm)
                JournalTextAction("取消", enabled=true, onClick=cancel)
            }
            "RESTORING"->{
                JournalCard {
                    JournalLoading(Modifier.testTag("restore-progress"))
                    JournalSectionTitle("正在恢复并检查资料")
                    Text("正在保护当前资料并完成恢复，请保持应用打开。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            "DONE"->{
                JournalCard(soft = true) {
                    Text("资料恢复完成",style=MaterialTheme.typography.titleLarge)
                    Text("本机安全副本已保留。自动云端备份保持关闭，请核对资料后再确认主设备。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                JournalButton("打开恢复后的资料",Modifier.testTag("restore-reopen"),enabled=!hostBusy,onClick=reopen)
            }
            "ERROR"->{
                JournalCard {
                    Text("恢复需要重新核对",style=MaterialTheme.typography.titleLarge)
                }
                JournalButton("重新选择备份",Modifier.testTag("restore-choose"),enabled=!hostBusy,onClick=chooseFile)
                JournalTextAction("打开资料库核对", onClick=reopen)
            }
        }
        state.message?.let {JournalError(it)}
    }
}

