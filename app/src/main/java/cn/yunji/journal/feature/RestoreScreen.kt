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
                Text("从加密备份找回资料",style=MaterialTheme.typography.titleLarge)
                Text("选择孕迹导出的 .yunji 文件，并输入与备份对应的恢复密钥。")
                Text("恢复会整体替换当前记录，不会合并。确认恢复前，会为当前资料制作并校验本机安全副本。")
                JournalButton("选择加密备份",Modifier.testTag("restore-choose"),enabled=!hostBusy,onClick=chooseFile)
                JournalButton("从云端备份恢复",Modifier.testTag("restore-cloud"),enabled=!hostBusy,secondary=true,onClick=startCloud)
            }
            "KEY"->{
                Text("输入恢复密钥",style=MaterialTheme.typography.titleLarge)
                Text("请输入完整的 YJ1: 恢复密钥，区分大小写。离开页面后需要重新输入。")
                OutlinedTextField(state.key,keyChanged,label={Text("恢复密钥")},singleLine=true,
                    visualTransformation=PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),
                    modifier=Modifier.fillMaxWidth().testTag("restore-key"))
                JournalButton("校验备份",Modifier.testTag("restore-verify"),enabled=state.key.isNotBlank(),onClick=prepare)
                TextButton(chooseFile){Text("重新选择文件")}
            }
            "CLOUD"->{
                Text("从云端备份恢复",style=MaterialTheme.typography.titleLarge)
                Text("输入该备份对应的恢复密钥，应用只会列出这个密钥能够打开的云端资料库。")
                OutlinedTextField(state.key,keyChanged,label={Text("恢复密钥")},singleLine=true,enabled=!state.finding,
                    visualTransformation=PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),
                    modifier=Modifier.fillMaxWidth().testTag("restore-cloud-key"))
                if(state.finding)CircularProgressIndicator(Modifier.testTag("restore-cloud-progress"))
                JournalButton(if(state.finding)"正在查找…" else "查找云端备份",Modifier.testTag("restore-cloud-find"),enabled=state.key.isNotBlank() && !state.finding,onClick=findCloud)
                Text("需要已保存且通过校验的云盘连接。下载过程中不会替换当前资料。",style=MaterialTheme.typography.bodySmall)
                TextButton(cancel,Modifier.testTag("restore-cloud-cancel")){Text("返回")}
            }
            "CLOUD_LIST"->{
                Text("选择要恢复的云端备份",style=MaterialTheme.typography.titleLarge)
                Text("显示的是这个恢复密钥能打开的云端快照。恢复会整体替换当前记录。")
                state.cloudSnapshots.forEach {snapshot->
                    val date=displayInstant(snapshot.createdAt, ZoneId.systemDefault().id)
                    JournalButton(if(state.snapshotId==snapshot.snapshotId)"已选择：$date（序号 ${snapshot.capturedSequence}）" else "$date（序号 ${snapshot.capturedSequence}）",
                        Modifier.testTag("restore-cloud-${snapshot.snapshotId}"),enabled=!hostBusy,secondary=true,onClick={selectSnapshot(snapshot.snapshotId)})
                }
                JournalButton("下载并校验",Modifier.testTag("restore-cloud-download"),
                    enabled=state.snapshotId!=null && state.key.isNotBlank() && !hostBusy,onClick=prepare)
                Text("下载完成后仍需在下一步确认才会替换资料。",style=MaterialTheme.typography.bodySmall)
                TextButton(cancel,Modifier.testTag("restore-cloud-back")){Text("返回")}
            }
            "VERIFYING"->{
                CircularProgressIndicator(Modifier.testTag("restore-progress"))
                Text("正在检查备份和原件",style=MaterialTheme.typography.titleLarge)
                Text("此时不会替换当前资料。文件较大时请稍候。")
                TextButton(cancel,Modifier.testTag("restore-cancel")){Text("取消校验")}
            }
            "CONFIRM"->state.preview?.let {preview->
                Text("核对要恢复的资料",style=MaterialTheme.typography.titleLarge)
                val date=displayInstant(preview.createdAt, ZoneId.systemDefault().id)
                Text("备份时间：$date")
                OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    listOf("孕期档案" to preview.pregnancies,"产检记录" to preview.visits,"资料原件" to preview.documents,
                        "用药方案" to preview.medicationPlans,"服药记录" to preview.intakes,"健康记录" to preview.measurements,
                        "日常备注" to preview.journalEntries,"自定义提醒" to preview.customReminders).forEach {(label,count)->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label);Text(count.toString())}
                    }
                }}
                Text("数量包含备份中的回收站记录。当前未提交的草稿不会进入安全副本。")
                Text("如当前资料尚未设置恢复密钥，安全副本将使用本次输入的密钥；已有恢复密钥保持不变。")
                Row(Modifier.fillMaxWidth().testTag("restore-acknowledge").toggleable(state.acknowledged,role=Role.Checkbox,onValueChange=acknowledge).padding(vertical=8.dp)) {
                    Checkbox(state.acknowledged,onCheckedChange=null)
                    Spacer(Modifier.width(8.dp))
                    Text("我理解当前记录会被整体替换，并保留本机安全副本。")
                }
                JournalButton("确认恢复",Modifier.testTag("restore-confirm"),enabled=state.acknowledged,onClick=confirm)
                TextButton(cancel,Modifier.fillMaxWidth()){Text("取消")}
            }
            "RESTORING"->{
                CircularProgressIndicator(Modifier.testTag("restore-progress"))
                Text("正在恢复并检查资料",style=MaterialTheme.typography.titleLarge)
                Text("正在保护当前资料并完成恢复，请保持应用打开。")
            }
            "DONE"->{
                Text("资料恢复完成",style=MaterialTheme.typography.titleLarge)
                Text("本机安全副本已保留。自动云端备份保持关闭，请核对资料后再确认主设备。")
                JournalButton("打开恢复后的资料",Modifier.testTag("restore-reopen"),enabled=!hostBusy,onClick=reopen)
            }
            "ERROR"->{
                Text("恢复需要重新核对",style=MaterialTheme.typography.titleLarge)
                JournalButton("重新选择备份",Modifier.testTag("restore-choose"),enabled=!hostBusy,onClick=chooseFile)
                TextButton(reopen){Text("打开资料库核对")}
            }
        }
        state.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
}

