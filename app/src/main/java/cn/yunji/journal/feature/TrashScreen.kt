package cn.yunji.journal.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunji.journal.core.database.AttachmentEntity
import cn.yunji.journal.core.database.VisitEntity
import cn.yunji.journal.core.designsystem.*
import cn.yunji.journal.data.DeletionImpact
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun RootDeletionDialog(kind: String, id: String, model: JournalViewModel, close: () -> Unit) {
    val impact = remember(kind, id) { kotlinx.coroutines.flow.MutableStateFlow<DeletionImpact?>(null) }
    val failed = remember(kind, id) { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val busy by model.busy.collectAsStateWithLifecycle()
    LaunchedEffect(kind, id) {
        try { impact.value = model.trash.impact(kind, id) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed.value = true }
    }
    AlertDialog(onDismissRequest = close, modifier = Modifier.testTag("root-deletion-dialog"), title = { Text(when(kind){"PREGNANCY"->"删除这个孕期？";"MEDICATION"->"删除这个药品？";else->"删除这次产检？"}) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val counts = impact.collectAsState().value
            val hasFailed = failed.collectAsState().value
            if (counts == null) Text(if (hasFailed) "暂时无法核对影响范围，请稍后重试。" else "正在核对影响范围…")
            else {
                Text(if (kind == "PREGNANCY") "将移入回收站：${counts.visits}次产检、${counts.documents}份资料、${counts.medications}种药品、${counts.plans}项用药方案、${counts.intakes}条服药记录、${counts.measurements}条测量记录、${counts.entries}条日常记录。此前单独删除的项目不会在恢复孕期时一起恢复。"
                    else if(kind=="MEDICATION") "本药品及${counts.plans}项用药方案、${counts.intakes}条服药记录、${counts.documents}份包装照片将移入回收站；此前单独删除的项目不会一并恢复。仍有历史方案引用时，药品保留到所属孕期整体清理。"
                    else "产检记录将移入回收站，${counts.documents}份报告保留，可在全局回收站的“保留的资料”查看；恢复产检后重新关联。")
                if (counts.pending > 0) Text("${counts.pending}项未完成导入将停止，可在恢复后重新选择文件。")
                Text("回收站保留30天，之后由系统安排清理；不删除手机相册原件或历史云端备份。")
            }
        } },
        confirmButton = { val counts = impact.collectAsState().value; TextButton({ close(); model.deleteRoot(kind, id) }, enabled = counts != null && !busy,
            modifier = Modifier.testTag("confirm-root-deletion")) { Text("移入全局回收站") } },
        dismissButton = { TextButton(close) { Text("取消") } })
}

@Composable
internal fun TrashScreen(model: JournalViewModel) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val trash = remember(model) { model.trash }
    val batches by remember(trash) { trash.batches }.collectAsStateWithLifecycle(emptyList())
    val documents by remember(model) { model.documents.recycled() }.collectAsStateWithLifecycle(emptyList())
    val retained by remember(trash) { trash.retained }.collectAsStateWithLifecycle(emptyList())
    val jobs by remember(trash) { trash.jobs }.collectAsStateWithLifecycle(emptyList())
    var showRetained by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("全部") }
    var query by rememberSaveable { mutableStateOf("") }
    var expandedBatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedRetainedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var relinking by remember { mutableStateOf<AttachmentEntity?>(null) }
    var relinkTargets by remember { mutableStateOf<List<VisitEntity>>(emptyList()) }
    var confirmation by remember { mutableStateOf<TrashConfirmation?>(null) }
    LaunchedEffect(relinking) { relinkTargets = relinking?.let { trash.relinkTargets(it) } ?: emptyList() }
    val deletedScroll = rememberLazyListState()
    val retainedScroll = rememberLazyListState()
    val standalone = documents.filter { it.deletionBatchId == null }
    val count = batches.size + standalone.size
    val filteredBatches = batches.filter { batch ->
        (category == "全部" || category == "孕期" && batch.kind == "PREGNANCY" || category == "产检" && batch.kind == "VISIT") &&
            (query.isBlank() || batch.title.contains(query.trim(), ignoreCase = true))
    }
    val filteredDocuments = standalone.filter { document ->
        (category == "全部" || category == "资料") &&
            (query.isBlank() || document.name.contains(query.trim(), ignoreCase = true))
    }
    val filteredRetained = retained.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    LazyColumn(state = if (showRetained) retainedScroll else deletedScroll, contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Inset { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JournalTag(!showRetained, { showRetained = false }, label = { Text("删除的项目（$count）") })
            JournalTag(showRetained, { showRetained = true }, label = { Text("保留的资料（${retained.size}）") })
        } } }
        if (!showRetained) item { Inset { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "孕期", "产检", "资料").forEach { option ->
                JournalTag(category == option, { category = option }, label = { Text(option) })
            }
        } } }
        item { Inset { OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().testTag("global-trash-search"),
            label = { Text("搜索回收站") }, placeholder = { Text("输入项目名称") }, singleLine = true,
        ) } }
        if (showRetained) {
            if (filteredRetained.isEmpty()) item { Inset { JournalEmptyState(if (query.isBlank()) "暂无保留的资料" else "没有匹配的资料") } }
            items(filteredRetained, key = { it.id }, contentType = { "retained" }) { document -> Inset { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordRow(document.name, if (document.mime == "application/pdf") "PDF · 原产检已删除" else "图片 · 原产检已删除", "cdc29.svg",
                    { focus.clearFocus(); keyboard?.hide(); expandedRetainedId = if (expandedRetainedId == document.id) null else document.id },
                    Modifier.testTag("trash-retained-${document.id}"))
                if (expandedRetainedId == document.id) JournalCard {
                    Text("原产检已删除，资料仍加密保存在本机。", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton({ selected = document.id }) { Text("查看资料") }
                        TextButton({ relinking = document }) { Text("重新关联产检") }
                        TextButton({ confirmation = TrashConfirmation(document.id, batch = false, permanent = false) }) { Text("删除资料", color = MaterialTheme.colorScheme.error) }
                    }
                }
            } } }
            item { Inset { Text("删除产检后保留的报告仍加密保存在本机。恢复产检后会重新出现在原产检中。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        } else {
            item { Inset { Text("删除的项目", style = MaterialTheme.typography.titleLarge) } }
            if (filteredBatches.isEmpty() && filteredDocuments.isEmpty()) item { Inset { JournalEmptyState(if (count == 0) "全局回收站为空" else "没有匹配的项目") } }
            items(filteredBatches, key = { "batch:${it.id}" }, contentType = { "batch" }) { batch ->
                val restoreReason by produceState<String?>("正在核对恢复条件", batch, batches) { value = trash.restoreReason(batch) }
                val restorable = restoreReason == null
                val purgeReason by produceState<String?>(null,batch,batches) {value=trash.purgeReason(batch)}
                Inset { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordRow(
                        batch.title,
                        "${when (batch.kind) { "PREGNANCY" -> "孕期"; "MEDICATION" -> "药品"; "JOURNAL" -> "日常记录"; "MEASUREMENT" -> "测量记录"; "INTAKE" -> "服药记录"; else -> "产检记录" }} · 删除于 ${deletionDate(batch.deletedAt)}",
                        "ic-file-text.svg",
                        { focus.clearFocus(); keyboard?.hide(); expandedBatchId = if (expandedBatchId == batch.id) null else batch.id },
                        Modifier.testTag("trash-batch-${batch.id}"),
                    )
                    if (expandedBatchId == batch.id) JournalCard {
                    Text(batch.title, style = MaterialTheme.typography.titleMedium)
                    Text(when (batch.kind) { "PREGNANCY" -> "孕期 · ${batch.visitCount}次产检 · ${batch.documentCount}份资料 · ${batch.planCount}项用药方案 · ${batch.intakeCount}条服药记录 · ${batch.measurementCount}条测量记录 · ${batch.entryCount}条日常记录";"MEDICATION"->"药品 · ${batch.documentCount}份照片 · ${batch.planCount}项方案 · ${batch.intakeCount}条服药记录"; "JOURNAL" -> "日常记录"; "MEASUREMENT" -> "测量记录"; "INTAKE" -> "服药记录"; else -> "产检记录 · 报告另行保留" }, style = MaterialTheme.typography.bodySmall)
                    Text("删除于 ${deletionDate(batch.deletedAt)}", style = MaterialTheme.typography.bodySmall)
                    if (batch.state == "PURGING") { Text("正在彻底清理，完成前会保留此状态"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else if (!restorable) Text(restoreReason.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    purgeReason?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                    Row {
                        TextButton({ model.restoreBatch(batch.id) }, enabled = restorable, modifier = Modifier.testTag("restore-batch-${batch.id}")) { Text("恢复") }
                        TextButton({ if (batch.state == "PURGING") model.purgeBatch(batch.id) else confirmation = TrashConfirmation(batch.id, batch = true, permanent = true, pregnancy = batch.kind == "PREGNANCY", intake = batch.kind == "INTAKE", measurement = batch.kind == "MEASUREMENT", journal = batch.kind == "JOURNAL",medication=batch.kind=="MEDICATION") },enabled=purgeReason==null) {
                            Text(if (batch.state == "PURGING") "重试清理" else "彻底删除", color = if (purgeReason == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    }
                } }
            }
            items(filteredDocuments, key = { "document:${it.id}" }, contentType = { "deleted-document" }) { document ->
                val purging = jobs.any { it.id == document.id && it.state == "PURGING" }
                val parentAvailable by produceState(false, document, batches) { value = trash.documentParentAvailable(document) }
                Inset { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordRow(document.name, "资料 · 删除于 ${deletionDate(document.deletedAt!!)}", "cdc29.svg",
                        { focus.clearFocus(); keyboard?.hide(); expandedDocumentId = if (expandedDocumentId == document.id) null else document.id },
                        Modifier.testTag("trash-document-${document.id}"))
                    if (expandedDocumentId == document.id) JournalCard {
                        if (!parentAvailable) Text(if(document.medicationId!=null)"请先恢复所属药品或孕期" else "请先恢复所属孕期", style = MaterialTheme.typography.bodySmall)
                        if (purging) { Text("正在彻底清理…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        Row {
                            TextButton({ model.restoreDocument(document.id) }, enabled = parentAvailable && !purging) { Text("恢复") }
                            TextButton({ if (purging) model.purgeDocument(document.id) else confirmation = TrashConfirmation(document.id, false, true) }) { Text(if (purging) "重试清理" else "彻底删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                } }
            }
            item { Inset { Text("保留30天，到期后由系统安排清理。整体恢复不会恢复此前单独删除的项目；本机删除不清理历史云端备份。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
    retained.firstOrNull { it.id == selected }?.let { document ->
        DocumentPreviewDialog(document, model, { selected = null }, { confirmation = TrashConfirmation(document.id, false, false) })
    }
    relinking?.let { document ->
        AlertDialog(onDismissRequest = { relinking = null }, modifier = Modifier.testTag("retained-relink-dialog"),
            title = { Text("关联到其他产检") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("原产检已删除。选择当前孕期的一次产检，资料原件、名称和备注保持不变。")
                if (relinkTargets.isEmpty()) Text("没有可关联的产检；请先恢复所属孕期或创建产检。")
                relinkTargets.forEach { target ->
                    TextButton({ relinking = null; model.relinkDocument(document.id, target.id, document.revision) }, Modifier.fillMaxWidth()) {
                        Text("${target.actualDate ?: target.scheduledDate} · ${target.items.ifBlank { target.hospital.ifBlank { "产检记录" } }}")
                    }
                }
            } },
            confirmButton = { TextButton({ relinking = null }) { Text("取消") } })
    }
    confirmation?.let { request ->
        AlertDialog(onDismissRequest = { confirmation = null }, modifier = Modifier.testTag("global-removal-dialog"), title = { Text(if (request.permanent) "确认彻底删除？" else "将资料移入回收站？") },
            text = { Text(when {
                !request.permanent -> "保留30天，之后由系统安排清理，可在清理前恢复。手机原件不受影响。"
                request.pregnancy -> "将清除此孕期的记录、药品、全部用药方案版本、服药记录、健康测量、指标定义、日常记录和所属资料，包括此前已单独删除的资料，无法恢复。手机原件和历史云端备份不受影响。"
                request.journal -> "将清除此条日常记录的日期、症状、心情及正文，无法恢复。"
                request.medication -> "将清除此药品的基础信息和全部包装照片，无法恢复。手机原件和历史云端备份不受影响。"
                request.measurement -> "将清除此条测量的数值、时间、来源及备注，无法恢复；指标定义保留。"
                request.intake -> "将清除此条服药记录的内容，无法恢复，不会删除药品或方案历史。"
                request.batch -> "只彻底删除这次产检的内容，保留的报告仍可查看；无法恢复产检。"
                else -> "将删除本应用保存的原件与缩略图，无法恢复。手机原件和历史云端备份不受影响。"
            }) },
            confirmButton = { TextButton({ confirmation = null; selected = null
                if (!request.permanent) model.removeDocument(request.id) else if (request.batch) model.purgeBatch(request.id) else model.purgeDocument(request.id)
            }, Modifier.testTag("confirm-global-removal")) { Text(if (request.permanent) "彻底删除" else "移入回收站", color = if (request.permanent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
            dismissButton = { TextButton({ confirmation = null }) { Text("取消") } })
    }
}

private data class TrashConfirmation(val id: String, val batch: Boolean, val permanent: Boolean, val pregnancy: Boolean = false, val intake: Boolean = false, val measurement: Boolean = false, val journal: Boolean = false,val medication: Boolean=false)
/** Bug #6/P1-7: trash timestamps follow the shared Chinese display format instead of raw ISO. */
private fun deletionDate(value: Long) = displayDate(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate().toString())

