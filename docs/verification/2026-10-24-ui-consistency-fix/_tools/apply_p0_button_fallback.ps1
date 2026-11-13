$ErrorActionPreference = 'Stop'
$feature = 'D:\Devtools\Code\CodexWorkspace\PregnancyJournal\app\src\main\java\cn\yunji\journal\feature'

function Replace-Exact([string]$file, [string]$old, [string]$new) {
    $path = Join-Path $feature $file
    $text = [System.IO.File]::ReadAllText($path)
    $count = ([regex]::Matches($text, [regex]::Escape($old))).Count
    if ($count -ne 1) { throw "expected 1 occurrence in $file but found $count for: $($old.Substring(0,[Math]::Min(80,$old.Length)))" }
    $text = $text.Replace($old, $new)
    [System.IO.File]::WriteAllText($path, $text)
    Write-Host "OK $file"
}

# ---------------- RecoveryScreen ----------------
Replace-Exact 'RecoveryScreen.kt' @'
import cn.yunji.journal.core.backup.RestoreReclaim
'@ @'
import cn.yunji.journal.core.backup.RestoreReclaim
import cn.yunji.journal.core.designsystem.JournalButton
'@

Replace-Exact 'RecoveryScreen.kt' @'
                Button(generate,Modifier.fillMaxWidth().testTag("recovery-generate")){Text("生成恢复密钥")}
'@ @'
                JournalButton("生成恢复密钥",Modifier.testTag("recovery-generate"),onClick=generate)
'@

Replace-Exact 'RecoveryScreen.kt' @'
                Button(verify,Modifier.fillMaxWidth().testTag("recovery-verify")){Text("已保存，开始核对")}
'@ @'
                JournalButton("已保存，开始核对",Modifier.testTag("recovery-verify"),onClick=verify)
'@

Replace-Exact 'RecoveryScreen.kt' @'
                Button(confirm,Modifier.fillMaxWidth().testTag("recovery-confirm"),enabled=!state.busy && state.first.length==6 && state.second.length==6){Text(if(state.busy)"正在保存…" else "确认并保存")}
'@ @'
                JournalButton(if(state.busy)"正在保存…" else "确认并保存",Modifier.testTag("recovery-confirm"),enabled=!state.busy && state.first.length==6 && state.second.length==6,onClick=confirm)
'@

Replace-Exact 'RecoveryScreen.kt' @'
                export?.let {Button(it,Modifier.fillMaxWidth().testTag("backup-export")){Text("导出加密备份")}}
'@ @'
                export?.let {JournalButton("导出加密备份",Modifier.testTag("backup-export"),onClick=it)}
'@

Replace-Exact 'RecoveryScreen.kt' @'
            "ERROR"->Button(retry){Text("重试")}
'@ @'
            "ERROR"->JournalButton("重试",onClick=retry)
'@

Replace-Exact 'RecoveryScreen.kt' @'
        if(state.stage in setOf("INTRO","READY"))restore?.let {action->OutlinedButton(action,Modifier.fillMaxWidth().testTag("restore-entry")){Text("从备份恢复资料")}}
'@ @'
        if(state.stage in setOf("INTRO","READY"))restore?.let {action->JournalButton("从备份恢复资料",Modifier.testTag("restore-entry"),secondary=true,onClick=action)}
'@

Replace-Exact 'RecoveryScreen.kt' @'
        OutlinedButton(onReclaim,Modifier.fillMaxWidth().testTag("reclaim-previous"),enabled=state.canAct && !state.busy){
            Text(if(state.busy)"正在回收…" else "回收上一代资料")
        }
'@ @'
        JournalButton(if(state.busy)"正在回收…" else "回收上一代资料",Modifier.testTag("reclaim-previous"),enabled=state.canAct && !state.busy,secondary=true,onClick=onReclaim)
'@

# ---------------- RestoreScreen ----------------
Replace-Exact 'RestoreScreen.kt' @'
import androidx.compose.ui.unit.dp
'@ @'
import androidx.compose.ui.unit.dp
import cn.yunji.journal.core.designsystem.JournalButton
'@

Replace-Exact 'RestoreScreen.kt' @'
                Button(chooseFile,Modifier.fillMaxWidth().testTag("restore-choose"),enabled=!hostBusy){Text("选择加密备份")}
'@ @'
                JournalButton("选择加密备份",Modifier.testTag("restore-choose"),enabled=!hostBusy,onClick=chooseFile)
'@

Replace-Exact 'RestoreScreen.kt' @'
                OutlinedButton(startCloud,Modifier.fillMaxWidth().testTag("restore-cloud"),enabled=!hostBusy){Text("从云端备份恢复")}
'@ @'
                JournalButton("从云端备份恢复",Modifier.testTag("restore-cloud"),enabled=!hostBusy,secondary=true,onClick=startCloud)
'@

Replace-Exact 'RestoreScreen.kt' @'
                Button(prepare,Modifier.fillMaxWidth().testTag("restore-verify"),enabled=state.key.isNotBlank()){Text("校验备份")}
'@ @'
                JournalButton("校验备份",Modifier.testTag("restore-verify"),enabled=state.key.isNotBlank(),onClick=prepare)
'@

Replace-Exact 'RestoreScreen.kt' @'
                Button(findCloud,Modifier.fillMaxWidth().testTag("restore-cloud-find"),enabled=state.key.isNotBlank() && !state.finding){Text(if(state.finding)"正在查找…" else "查找云端备份")}
'@ @'
                JournalButton(if(state.finding)"正在查找…" else "查找云端备份",Modifier.testTag("restore-cloud-find"),enabled=state.key.isNotBlank() && !state.finding,onClick=findCloud)
'@

Replace-Exact 'RestoreScreen.kt' @'
                    OutlinedButton({selectSnapshot(snapshot.snapshotId)},Modifier.fillMaxWidth().testTag("restore-cloud-${snapshot.snapshotId}"),
                        enabled=!hostBusy) {
                        Text(if(state.snapshotId==snapshot.snapshotId)"已选择：$date（序号 ${snapshot.capturedSequence}）" else "$date（序号 ${snapshot.capturedSequence}）")
                    }
'@ @'
                    JournalButton(if(state.snapshotId==snapshot.snapshotId)"已选择：$date（序号 ${snapshot.capturedSequence}）" else "$date（序号 ${snapshot.capturedSequence}）",
                        Modifier.testTag("restore-cloud-${snapshot.snapshotId}"),enabled=!hostBusy,secondary=true,onClick={selectSnapshot(snapshot.snapshotId)})
'@

Replace-Exact 'RestoreScreen.kt' @'
                Button(prepare,Modifier.fillMaxWidth().testTag("restore-cloud-download"),
                    enabled=state.snapshotId!=null && state.key.isNotBlank() && !hostBusy){Text("下载并校验")}
'@ @'
                JournalButton("下载并校验",Modifier.testTag("restore-cloud-download"),
                    enabled=state.snapshotId!=null && state.key.isNotBlank() && !hostBusy,onClick=prepare)
'@

Replace-Exact 'RestoreScreen.kt' @'
                Button(confirm,Modifier.fillMaxWidth().testTag("restore-confirm"),enabled=state.acknowledged){Text("确认恢复")}
'@ @'
                JournalButton("确认恢复",Modifier.testTag("restore-confirm"),enabled=state.acknowledged,onClick=confirm)
'@

Replace-Exact 'RestoreScreen.kt' @'
                Button(reopen,Modifier.fillMaxWidth().testTag("restore-reopen"),enabled=!hostBusy){Text("打开恢复后的资料")}
'@ @'
                JournalButton("打开恢复后的资料",Modifier.testTag("restore-reopen"),enabled=!hostBusy,onClick=reopen)
'@

Replace-Exact 'RestoreScreen.kt' @'
                Button(chooseFile,Modifier.fillMaxWidth().testTag("restore-choose"),enabled=!hostBusy){Text("重新选择备份")}
'@ @'
                JournalButton("重新选择备份",Modifier.testTag("restore-choose"),enabled=!hostBusy,onClick=chooseFile)
'@

# ---------------- ExportScreen ----------------
Replace-Exact 'ExportScreen.kt' @'
import androidx.lifecycle.compose.collectAsStateWithLifecycle
'@ @'
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunji.journal.core.designsystem.JournalButton
'@

Replace-Exact 'ExportScreen.kt' @'
            OutlinedButton(cancel, Modifier.fillMaxWidth()) { Text("取消导出") }
'@ @'
            JournalButton("取消导出", secondary = true, onClick = cancel)
'@

Replace-Exact 'ExportScreen.kt' @'
        Button(exportData, Modifier.fillMaxWidth().testTag("export-data"), enabled = state.canStart) { Text("导出结构化数据包") }
'@ @'
        JournalButton("导出结构化数据包", Modifier.testTag("export-data"), enabled = state.canStart, onClick = exportData)
'@

Replace-Exact 'ExportScreen.kt' @'
        OutlinedButton(exportSummary, Modifier.fillMaxWidth().testTag("export-summary"), enabled = state.canStart) { Text("生成就诊摘要 PDF") }
'@ @'
        JournalButton("生成就诊摘要 PDF", Modifier.testTag("export-summary"), enabled = state.canStart, secondary = true, onClick = exportSummary)
'@

Write-Host 'ALL BUTTON REPLACEMENTS DONE'
