package cn.yunji.journal.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunji.journal.core.designsystem.JournalButton
import cn.yunji.journal.core.designsystem.JournalCard
import cn.yunji.journal.core.designsystem.JournalSwitch
import cn.yunji.journal.core.designsystem.RecordRow

@Composable
fun ExportScreen(controller: ExportController) {
    val state by controller.state.collectAsStateWithLifecycle()
    ExportContent(state, controller::updateRange, controller::toggleSection, controller::setOriginals,
        controller::setTrash, controller::acknowledgePlaintext, controller::preview,
        controller::startDataExport, controller::startSummaryPdf, controller::cancel)
}

@Composable
internal fun ExportContent(state: ExportUiState, updateRange: (String, String) -> Unit,
    toggleSection: (String) -> Unit, setOriginals: (Boolean) -> Unit, setTrash: (Boolean) -> Unit,
    acknowledge: (Boolean) -> Unit, preview: () -> Unit, exportData: () -> Unit, exportSummary: () -> Unit,
    cancel: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("export-screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        var scopeExpanded by rememberSaveable { mutableStateOf(false) }
        Text("导出的文件为明文，请自行妥善保管。", style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(state.from, { updateRange(it, state.to) }, label = { Text("开始日期（选填）") },
            placeholder = { Text("不限") }, singleLine = true, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("export-from"))
        OutlinedTextField(state.to, { updateRange(state.from, it) }, label = { Text("结束日期（选填）") },
            placeholder = { Text("不限") }, singleLine = true, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("export-to"))
        if (!state.rangeValid) Text("日期范围无效，请检查起止日期。", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        Text("导出范围", style = MaterialTheme.typography.titleMedium)
        RecordRow("已选择 ${state.sections.size} 类资料", if (state.sections.isEmpty()) "请选择要导出的记录" else state.sections.take(3).joinToString("、", transform = ::sectionLabel),
            "ic-file-text.svg", { scopeExpanded = !scopeExpanded }, Modifier.testTag("export-scope-toggle"))
        if (scopeExpanded) JournalCard {
            Text("选择记录类型", style = MaterialTheme.typography.titleMedium)
            cn.yunji.journal.core.export.ExportScope.ALL_SECTIONS.forEach { section ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(sectionLabel(section))
                    Checkbox(section in state.sections, { toggleSection(section) }, enabled = !state.busy,
                        modifier = Modifier.testTag("export-section-$section"))
                }
            }
        }
        JournalCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("包含原件")
            JournalSwitch(state.includeOriginals, setOriginals, enabled = !state.busy, modifier = Modifier.testTag("export-originals"))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("包含回收站记录")
            JournalSwitch(state.includeTrash, setTrash, enabled = !state.busy, modifier = Modifier.testTag("export-trash"))
        }
        }

        JournalCard(soft = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.plaintextAcknowledged, acknowledge, enabled = !state.busy,
                modifier = Modifier.testTag("export-ack"))
            Text("我了解导出内容为明文，将自行妥善保管")
        }
        }
        state.message?.let { Text(it, color = if (state.lastResult != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("export-message")) }
        state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("export-detail")) }

        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            JournalButton("取消导出", secondary = true, onClick = cancel)
        }
        Text("导出方式", style = MaterialTheme.typography.titleMedium)
        JournalButton("导出结构化数据包", Modifier.testTag("export-data"), enabled = state.canStart, onClick = exportData)
        JournalButton("生成就诊摘要 PDF", Modifier.testTag("export-summary"), enabled = state.canStart, secondary = true, onClick = exportSummary)
        TextButton(preview, enabled = !state.loading && !state.busy, modifier = Modifier.testTag("export-preview")) {
            Text(if (state.loading) "正在统计…" else "统计当前范围")
        }
        if (state.preview.isNotEmpty()) {
            val total = state.preview.values.sum()
            Text("当前范围共 $total 条记录", Modifier.testTag("export-preview-total"))
            state.preview.filterValues { it > 0 }.forEach { (name, count) ->
                Text("$name：$count", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("摘要包含范围、生成时间与“用户整理记录”标记，缺失的字段显示为“未填写”；不会包含草稿、云盘凭据或恢复密钥。",
            style = MaterialTheme.typography.bodySmall)
    }
}

private fun sectionLabel(section: String) = when (section) {
    "pregnancies" -> "孕期档案"
    "date_revisions" -> "日期依据历史"
    "visits" -> "产检记录"
    "attachments" -> "资料与原件"
    "deletion_batches" -> "删除批次"
    "medications" -> "药品"
    "medication_plans" -> "用药方案"
    "medication_occurrences" -> "服药计划点"
    "medication_intakes" -> "服药记录"
    "metric_definitions" -> "指标定义"
    "measurements" -> "健康测量"
    "measurement_values" -> "测量数值"
    "journal_entries" -> "日常记录"
    "custom_reminders" -> "自定义提醒"
    else -> section
}
