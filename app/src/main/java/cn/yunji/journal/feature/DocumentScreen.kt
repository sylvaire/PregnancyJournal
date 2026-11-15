package cn.yunji.journal.feature

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunji.journal.core.designsystem.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import cn.yunji.journal.data.*
import cn.yunji.journal.core.security.FileRequest
import cn.yunji.journal.core.security.LocalFileLaunch
import cn.yunji.journal.core.share.OriginalShareDialog
import cn.yunji.journal.core.share.OriginalSharer
import cn.yunji.journal.core.database.AttachmentEntity
import cn.yunji.journal.core.database.VisitEntity

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DocumentScreen(visitId: String, model: JournalViewModel,medication: Boolean=false,
    visits: List<VisitEntity> = emptyList(), coverAttachmentId: String? = null) {
    val context = LocalContext.current
    val fileLaunch=LocalFileLaunch.current
    val sharer = remember(context.applicationContext) { OriginalSharer(context.applicationContext) }
    val shareLifecycle = LocalLifecycleOwner.current.lifecycle
    val shareScope = rememberCoroutineScope()
    var shareTarget by remember { mutableStateOf<AttachmentEntity?>(null) }
    var shareBusy by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    // Returning from the chooser/target app revokes the temporary grant before deleting plaintext.
    DisposableEffect(sharer, shareLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) sharer.releaseAfterReturn()
        }
        shareLifecycle.addObserver(observer)
        onDispose { shareLifecycle.removeObserver(observer) }
    }
    val importer = remember(model) { model.documents }
    val jobs by remember(importer, visitId,medication) { if(medication)importer.medicationJobs(visitId) else importer.jobs(visitId) }.collectAsStateWithLifecycle(emptyList())
    val attachments by remember(importer, visitId,medication) { if(medication)importer.medicationAttachments(visitId) else importer.attachments(visitId) }.collectAsStateWithLifecycle(emptyList())
    val recycled by remember(importer) { importer.recycled() }.collectAsStateWithLifecycle(emptyList())
    val appState by model.state.collectAsStateWithLifecycle()
    val live = if (medication) null else appState.visits.firstOrNull { it.id == visitId }
    val currentCoverId = if (live != null) live.coverAttachmentId else coverAttachmentId
    val transfers by importer.transfers.collectAsStateWithLifecycle()
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun openDocument(id: String) { focus.clearFocus(); keyboard?.hide(); selected = id }
    fun importFiles(uris: List<Uri>){if(medication)model.importMedicationPhotos(visitId,uris) else model.importDocuments(visitId,uris)}
    var confirmation by remember { mutableStateOf<RemovalConfirmation?>(null) }
    val activeScroll = rememberLazyListState()
    val trashScroll = rememberLazyListState()
    var query by rememberSaveable(visitId) { mutableStateOf("") }
    var category by rememberSaveable(visitId) { mutableStateOf("") }
    var kind by rememberSaveable(visitId) { mutableStateOf("ALL") }
    var sort by rememberSaveable(visitId) { mutableIntStateOf(0) }
    var editing by rememberSaveable(visitId) { mutableStateOf<String?>(null) }
    val activeJobs = remember(jobs, attachments, query, category, sort) { queryDocuments(jobs, attachments, query, category, sort) }
    val visibleJobs = activeJobs.filter { job ->
        val mime = attachments.firstOrNull { it.id == job.id }?.mime
        // An unfinished/failed import has no verified MIME yet; keep recovery actions reachable.
        mime == null || kind == "ALL" || kind == "IMAGE" && mime.startsWith("image/") || kind == "PDF" && mime == "application/pdf"
    }
    val compactReadyRows = visibleJobs.count { it.state == "READY" } > 1
    val hasActive = jobs.any { it.state in setOf("QUEUED", "IMPORTING", "READY", "FAILED") }
    val trash = recycled.filter { if(medication)it.medicationId==visitId else it.visitId == visitId }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
    var retryTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var relinking by remember { mutableStateOf<String?>(null) }
    var replaceTarget by remember { mutableStateOf<String?>(null) }
    var replacePending by remember { mutableStateOf<ReplaceRequest?>(null) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var pickerError by remember { mutableStateOf<String?>(null) }
    var showImportOptions by rememberSaveable { mutableStateOf(false) }
    var expandedJobId by rememberSaveable(visitId) { mutableStateOf<String?>(null) }
    var expandedTrashId by rememberSaveable(visitId) { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val id = exporting; exporting = null
        if (uri != null && id != null) model.exportDocument(id, uri)
    }
    val replacementPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val replaceId = replaceTarget
        val retryId = retryTarget
        replaceTarget = null; retryTarget = null
        if (uri != null && replaceId != null) replacePending = ReplaceRequest(replaceId, uri, attachments.firstOrNull { it.id == replaceId }?.revision)
        else if (uri != null && retryId != null) model.retryDocument(retryId, uri)
    }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) importFiles(uris)
    }
    val pdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importFiles(uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraFile?.let { name ->
            val file = File(context.cacheDir, "capture/$name")
            if (success) importFiles(listOf(FileProvider.getUriForFile(context, "${context.packageName}.capture", file)))
            else file.delete()
        }
        cameraFile = null
    }
    LazyColumn(state = if (showTrash) trashScroll else activeScroll, contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("document-list")) {
        item { Inset { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JournalTag(selected = !showTrash && kind == "ALL", onClick = { showTrash = false; kind = "ALL" }, label = { Text(if(medication)"包装照片" else "本次资料") })
            if (!medication) {
                JournalTag(selected = !showTrash && kind == "IMAGE", onClick = { showTrash = false; kind = "IMAGE" }, label = { Text("图片") })
                JournalTag(selected = !showTrash && kind == "PDF", onClick = { showTrash = false; kind = "PDF" }, label = { Text("PDF") })
            }
            JournalTag(selected = showTrash, onClick = { showTrash = true }, label = { Text("回收站（${trash.size}）") })
        } } }
        if (!showTrash) {
            item { Inset { Text(if(medication)"包装照片仅支持图片 · 单份最多100 MiB · 一次最多20项" else "图片或 PDF · 单份最多 100 MiB · 一次最多 20 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (pickerError != null) item { Inset { JournalError(pickerError!!) } }
            if (shareError != null) item { Inset { JournalError(shareError!!) } }
            item { Inset { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text(if(medication)"搜索名称或备注" else "搜索名称、备注或医院") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("document-search"),
                    trailingIcon = { if (query.isNotEmpty()) TextButton({ query = "" }) { Text("清除") } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if(!medication) DocumentChoice(if (category.isEmpty()) "全部分类" else DOCUMENT_CATEGORIES.getValue(category), listOf("" to "全部分类") + DOCUMENT_CATEGORIES.toList(), modifier=Modifier.weight(1f)) { category = it }
                    DocumentChoice(DOCUMENT_SORTS[sort], DOCUMENT_SORTS.mapIndexed { index, label -> index.toString() to label }, modifier=Modifier.weight(1f)) { sort = it.toInt() }
                }
            } } }
            if (visibleJobs.isEmpty()) item { Inset {
                JournalEmptyState(
                    title = if (hasActive) "没有匹配的${if(medication)"包装照片" else "资料"}" else if(medication)"还没有包装照片" else "还没有产检资料",
                    subtitle = if (hasActive) "试试其他名称。" else if(medication)"拍下药盒或说明书，加密保存在本机，之后可与方案一起核对。" else "把这次产检的报告放在一起，之后更容易找到。",
                    action = if (hasActive) ({ TextButton({ query = ""; category = ""; kind = "ALL" }) { Text("清除搜索与筛选") } }) else null,
                )
            } }
            items(visibleJobs, key = { it.id }, contentType = { "document" }) { job ->
                val attachment = attachments.firstOrNull { it.id == job.id && it.deletedAt == null }
                val canOpen = attachment != null && (attachment.mime.startsWith("image/") || attachment.mime == "application/pdf")
                val openLabel = if (attachment?.mime == "application/pdf") "查看 PDF" else "查看大图"
                Inset { Column(Modifier.testTag("document-${job.id}").clickable(enabled = canOpen, onClickLabel = openLabel) { openDocument(job.id) }) { JournalCard {
                    val status = when {
                        job.state == "READY" && job.phase == "THUMBNAIL" -> "已加密保存 · 正在生成缩略图…"
                        job.state == "READY" -> "已加密保存"
                        job.state == "FAILED" -> "导入失败"
                        job.state == "QUEUED" -> "等待导入…"
                        job.phase == "READING" -> "正在读取文件…"
                        job.phase == "VERIFYING" -> "正在校验完整性…"
                        else -> "正在加密保存…"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                            if (attachment?.thumbnailId != null) {
                                val bitmap by produceState<android.graphics.Bitmap?>(null, attachment.id, attachment.thumbnailId) { value = importer.thumbnail(attachment.id) }
                                if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = "产检资料缩略图",
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                else DesignIcon("cdc29.svg")
                            } else DesignIcon("cdc29.svg")
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(attachment?.name ?: job.name, style = MaterialTheme.typography.titleMedium,
                                    maxLines = if (compactReadyRows) 1 else Int.MAX_VALUE,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false))
                                if (attachment != null && currentCoverId == attachment.id) Text("封面", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("document-cover-badge"))
                            }
                            Text(status, color = if (job.state == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (attachment != null && job.state == "READY" && compactReadyRows && expandedJobId != job.id)
                            JournalTextAction("操作", Modifier.testTag("document-more-${job.id}"),
                                onClick = { expandedJobId = job.id })
                    }
                    if (attachment != null && (!compactReadyRows || expandedJobId == job.id)) Text("${DOCUMENT_CATEGORIES[attachment.category] ?: "其他"} · ${if (attachment.mime == "application/pdf") "PDF" else "图片"} · ${formatFileSize(attachment.plainSize)}", style = MaterialTheme.typography.bodySmall)
                    if (attachment?.documentDate != null && (!compactReadyRows || expandedJobId == job.id)) Text("报告日期：${displayDate(attachment.documentDate!!)}", style = MaterialTheme.typography.bodySmall)
                    if (expandedJobId == job.id) {
                        if(!attachment?.hospital.isNullOrBlank())Text(attachment.hospital,style=MaterialTheme.typography.bodySmall)
                        if(!attachment?.note.isNullOrBlank())Text(attachment.note,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (attachment != null) Text("可导出原件；无法打开时可替换为新的加密原件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (job.error != null) Text(job.error, style = MaterialTheme.typography.bodySmall)
                    if (job.state in setOf("QUEUED", "IMPORTING") || job.phase == "THUMBNAIL") {
                        val transfer = transfers[job.id]
                        if (job.phase == "SAVING" && transfer != null && transfer.total > 0) {
                            LinearProgressIndicator(progress = { (transfer.bytes.toFloat() / transfer.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                            Text("${formatFileSize(transfer.bytes)} / ${formatFileSize(transfer.total)}", style = MaterialTheme.typography.bodySmall)
                        } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (!compactReadyRows || job.state != "READY" || expandedJobId == job.id) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (canOpen) JournalTextAction(openLabel, onClick = { openDocument(job.id) })
                        if (attachment != null) JournalTextAction("编辑", onClick = { editing = attachment.id })
                        if (job.state == "FAILED") {
                            JournalTextAction("重试", onClick = { model.retryDocument(job.id) })
                            JournalTextAction("重新选择", onClick = {
                                if (fileLaunch != null) fileLaunch(FileRequest("RETRY", visitId, medication, job.id), null)
                                else {
                                    retryTarget = job.id
                                    runCatching {
                                        replacementPicker.launch(if (medication) arrayOf("image/*") else arrayOf("image/*", "application/pdf"))
                                    }.onFailure { pickerError = "无法打开文件选择器" }
                                }
                            })
                        }
                        JournalTextAction(if (attachment != null) "删除" else "移除", danger = true, onClick = {
                            confirmation = RemovalConfirmation(job.id, false, attachment == null)
                        })
                        if (attachment != null && job.state == "READY" && !compactReadyRows) JournalTextAction(
                            if (expandedJobId == job.id) "收起" else "更多",
                            Modifier.testTag("document-more-${job.id}"),
                            onClick = { expandedJobId = if (expandedJobId == job.id) null else job.id })
                    }
                    if (attachment != null && job.state == "READY" && compactReadyRows && expandedJobId == job.id)
                        JournalTextAction("收起操作", Modifier.testTag("document-more-${job.id}"), onClick = { expandedJobId = null })
                    if (attachment != null && job.state == "READY" && expandedJobId == job.id) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!medication && currentCoverId != attachment.id) JournalTextAction("设为封面",
                            Modifier.testTag("set-cover-${attachment.id}"), onClick = { model.setDocumentCover(attachment.id, visitId) })
                        if (!medication && currentCoverId == attachment.id) JournalTextAction("取消封面",
                            Modifier.testTag("clear-cover"), onClick = { model.clearDocumentCover(visitId) })
                        if (!medication && visits.isNotEmpty()) JournalTextAction("关联产检",
                            Modifier.testTag("relink-${attachment.id}"), onClick = { relinking = attachment.id })
                        JournalTextAction("导出原件", Modifier.testTag("export-${attachment.id}"), onClick = {
                            if (fileLaunch != null) fileLaunch(FileRequest("EXPORT", visitId, medication, attachment.id, attachment.revision), exportName(attachment))
                            else {
                                exporting = attachment.id
                                runCatching { exporter.launch(exportName(attachment)) }
                                    .onFailure { pickerError = "无法打开导出位置选择器" }
                            }
                        })
                        JournalTextAction(if (shareBusy) "分享中…" else "分享原件",
                            Modifier.testTag("share-${attachment.id}"), enabled = !shareBusy,
                            onClick = { shareError = null; shareTarget = attachment })
                        JournalTextAction("替换原件", Modifier.testTag("replace-${attachment.id}"), onClick = {
                            if (fileLaunch != null) fileLaunch(FileRequest("REPLACE", visitId, medication, attachment.id, attachment.revision), null)
                            else {
                                replaceTarget = attachment.id
                                runCatching { replacementPicker.launch(if (medication) arrayOf("image/*") else arrayOf("image/*", "application/pdf")) }
                                    .onFailure { pickerError = "无法打开文件选择器" }
                            }
                        })
                    }
                } } }
            }
            item { Inset { JournalButton(if (medication) "添加包装照片" else "添加检查资料", modifier = Modifier.fillMaxWidth().testTag("document-add")) { showImportOptions = true } } }
            item { Inset { Text("加密原件和缩略图均保存在本机。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        } else {
            item { Inset { Text(if(medication)"删除的包装照片保留30天，到期后由系统安排清理。手机相册、文件原件和历史云端备份不受影响。" else "本次产检删除的资料保留30天，到期后由系统安排清理。手机相册、文件原件和历史云端备份不受影响。", style = MaterialTheme.typography.bodySmall) } }
            if (trash.isEmpty()) item { Inset { JournalEmptyState("回收站为空") } }
            items(trash, key = { it.id }, contentType = { "trash" }) { attachment ->
                val purging = jobs.any { it.id == attachment.id && it.state == "PURGING" }
                val compact = trash.size > 1 && !purging
                Inset { Column(Modifier.testTag("document-trash-${attachment.id}")) { JournalCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(attachment.name, style = MaterialTheme.typography.titleMedium,
                            maxLines = if (compact) 1 else Int.MAX_VALUE,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        if (compact) JournalTextAction(if (expandedTrashId == attachment.id) "收起" else "操作",
                            Modifier.testTag("document-trash-more-${attachment.id}"),
                            onClick = { expandedTrashId = if (expandedTrashId == attachment.id) null else attachment.id })
                    }
                    if (purging) { Text("正在彻底删除…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (!compact || expandedTrashId == attachment.id) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        JournalTextAction("恢复", enabled = !purging, onClick = { model.restoreDocument(attachment.id) })
                        JournalTextAction("彻底删除", enabled = !purging, danger = true, onClick = { confirmation = RemovalConfirmation(attachment.id, true, false) })
                    }
                } } }
            }
        }
    }
    if (showImportOptions) AlertDialog(
        onDismissRequest = { showImportOptions = false },
        title = { Text(if (medication) "添加包装照片" else "添加检查资料") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ showImportOptions = false; if(fileLaunch!=null)fileLaunch(FileRequest("IMAGES",visitId,medication),null) else runCatching { images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.onFailure { pickerError = "无法打开图片选择器" } }, Modifier.fillMaxWidth()) { Text("选图片") }
            if (!medication) TextButton({ showImportOptions = false; if(fileLaunch!=null)fileLaunch(FileRequest("PDF",visitId),null) else runCatching { pdfs.launch(arrayOf("application/pdf")) }.onFailure { pickerError = "无法打开文件选择器" } }, Modifier.fillMaxWidth()) { Text("选 PDF") }
            TextButton({
                showImportOptions = false
                if(fileLaunch!=null)fileLaunch(FileRequest("CAMERA",visitId,medication),null) else {
                    val file = File(context.cacheDir, "capture/${UUID.randomUUID()}.jpg")
                    file.parentFile?.mkdirs(); cameraFile = file.name
                    runCatching { camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.capture", file)) }
                        .onFailure { file.delete(); cameraFile = null; pickerError = "没有可用的相机应用，请选择已有图片" }
                }
            }, Modifier.fillMaxWidth()) { Text("拍照") }
        } },
        confirmButton = { TextButton({ showImportOptions = false }) { Text("取消") } },
    )
    val preview = attachments.firstOrNull { it.id == selected && it.deletedAt == null }
    if (preview != null) DocumentPreviewDialog(preview, model, close = { selected = null }, remove = { confirmation = RemovalConfirmation(preview.id, false, false) })
    attachments.firstOrNull { it.id == editing && it.deletedAt == null }?.let { attachment ->
        DocumentMetadataDialog(attachment, { editing = null }, medication = medication) { name, value,date,hospital,note,revision -> model.documents.updateMetadata(attachment.id, name, value,date,hospital,note,revision) }
    }
    relinking?.let { id ->
        val attachment = attachments.firstOrNull { it.id == id && it.deletedAt == null }
        if (attachment != null) AlertDialog(onDismissRequest = { relinking = null }, modifier = Modifier.testTag("relink-dialog"),
            title = { Text("关联到其他产检") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("只改变资料归属，原件、名称、分类和备注都不变。")
                if (visits.isEmpty()) Text("当前孕期没有其他可关联的产检。")
                visits.forEach { target ->
                    TextButton({ relinking = null; model.relinkDocument(id, target.id, attachment.revision) }, Modifier.fillMaxWidth()) {
                        Text("${displayDate(target.actualDate ?: target.scheduledDate)} · ${target.items.ifBlank { target.hospital.ifBlank { "产检记录" } }}")
                    }
                }
            } },
            confirmButton = { TextButton({ relinking = null }) { Text("取消") } })
    }
    replacePending?.let { request ->
        AlertDialog(onDismissRequest = { replacePending = null }, modifier = Modifier.testTag("replace-dialog"),
            title = { Text("替换这份资料的原件？") },
            text = { Text("新文件会重新加密并校验，名称、分类、报告日期、备注和所在产检保持不变；当前原件会从本应用删除。手机相册或原文件不受影响。") },
            confirmButton = { TextButton({ replacePending = null; model.replaceDocument(request.id, request.uri, request.revision) }, Modifier.testTag("confirm-replace-original")) { Text("替换并重新加密") } },
            dismissButton = { TextButton({ replacePending = null }) { Text("取消") } })
    }
    val request = confirmation
    if (request != null) AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(if (request.permanent) "彻底删除这份资料？" else if (request.pending) "移除这项导入？" else "将资料移入回收站？") },
        text = { Text(if (request.permanent) "这将删除本应用保存的原件与缩略图，无法恢复。手机相册和文件原件不受影响。" else if (request.pending) "停止这项导入并从列表移除，不影响手机原件。" else if(medication) "保留30天，之后由系统安排清理。可在药品包装照片或全局回收站恢复，手机相册和文件原件不受影响。" else "保留30天，之后由系统安排清理。可在本次产检或全局回收站恢复，手机原件和历史云端备份不受影响。") },
        confirmButton = { TextButton({ confirmation = null; selected = null; if (request.permanent) model.purgeDocument(request.id) else model.removeDocument(request.id) }, Modifier.testTag("confirm-document-removal")) { Text(if (request.permanent) "彻底删除" else if (request.pending) "确认移除" else "移入回收站") } },
        dismissButton = { TextButton({ confirmation = null }) { Text("取消") } })
    shareTarget?.let { target ->
        OriginalShareDialog(
            name = target.name,
            busy = shareBusy,
            onDismiss = { if (!shareBusy) shareTarget = null },
            onConfirm = {
                if (!shareBusy) {
                    shareBusy = true
                    shareError = null
                    shareScope.launch {
                        var staged: File? = null
                        try {
                            val file = sharer.prepare(exportName(target), target.mime)
                            staged = file
                            // Decrypt to the private share directory only; plaintext never reaches public storage.
                            java.io.FileOutputStream(file).use { output ->
                                importer.exportOriginalTo(target.id, output, target.revision)
                            }
                            require(sharer.launch(sharer.publish(file, target.mime))) { "没有可用的分享应用" }
                            shareTarget = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            sharer.discard(staged)
                            shareError = e.message?.takeIf { it.isNotBlank() } ?: "无法分享这份原件"
                        } finally {
                            shareBusy = false
                        }
                    }
                }
            },
        )
    }
}

private data class RemovalConfirmation(val id: String, val permanent: Boolean, val pending: Boolean)
private data class ReplaceRequest(val id: String, val uri: Uri, val revision: Long?)

internal fun exportName(attachment: AttachmentEntity): String {
    val base = attachment.name.substringBeforeLast('.').ifBlank { "资料" }
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120)
    val extension = when (attachment.mime) {
        "application/pdf" -> "pdf"; "image/png" -> "png"; "image/webp" -> "webp"
        "image/gif" -> "gif"; "image/heic" -> "heic"; else -> "jpg"
    }
    return "$base.$extension"
}

@Composable
private fun DocumentChoice(label: String, options: List<Pair<String, String>>,modifier: Modifier=Modifier, enabled: Boolean=true, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        JournalSecondaryButton(label, enabled = enabled, onClick = { expanded = true })
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) },
                trailingIcon = if(title==label) ({Text("✓")}) else null,
                modifier = Modifier.semantics {selected=title==label},
                onClick = { expanded = false; select(key) }) }
        }
    }
}

@Composable
internal fun DocumentMetadataDialog(attachment: AttachmentEntity, close: () -> Unit, medication: Boolean = false, save: suspend (String, String,String,String,String,Long) -> Unit) {
    var name by rememberSaveable(attachment.id) { mutableStateOf(attachment.name) }
    var category by rememberSaveable(attachment.id) { mutableStateOf(attachment.category) }
    var date by rememberSaveable(attachment.id) {mutableStateOf(attachment.documentDate.orEmpty())}
    var hospital by rememberSaveable(attachment.id) {mutableStateOf(attachment.hospital)}
    var note by rememberSaveable(attachment.id) {mutableStateOf(attachment.note)}
    val editingRevision=rememberSaveable(attachment.id){attachment.revision}
    var saving by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    val valid = name.trim().isNotEmpty() && name.length <= 160 && !name.any { it.isISOControl() || it == '/' || it == '\\' }
    AlertDialog(onDismissRequest = {if(!saving)close()}, title = { Text(if(medication)"编辑包装照片" else "编辑资料") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            JournalField(name, { name = it }, if(medication)"照片名称" else "资料名称", isError = !valid,
                supportingText = if (valid) "${name.length}/160" else "请填写名称，不能包含斜杠或控制字符",
                modifier = Modifier.testTag("document-name"), enabled = !saving)
            if(!medication) {
                DocumentChoice(DOCUMENT_CATEGORIES[category] ?: "其他", DOCUMENT_CATEGORIES.toList(),enabled=!saving) { category = it }
                DateField("报告日期（选填）",date,{date=it},enabled=!saving)
                JournalField(hospital,{hospital=it}, "医院（选填）", isError=hospital.length>120, enabled=!saving,
                    modifier=Modifier.testTag("document-hospital"))
            }
            JournalField(note,{note=it}, if(medication)"备注（选填，例如药盒信息）" else "备注（选填）", singleLine=false, minLines=2,
                isError=note.length>10_000, enabled=!saving, modifier=Modifier.testTag("document-note"))
            error?.let {JournalError(it)}
        }
    }, confirmButton = { TextButton({saving=true;scope.launch {
        try {save(name.trim(),category,date,hospital,note,editingRevision);close()}
        catch(e:CancellationException){throw e}
        catch(_:java.time.format.DateTimeParseException){error="请选择有效报告日期"}
        catch(e:IllegalArgumentException){error=e.message ?: "请核对资料信息"}
        catch(_:Exception){error="保存失败，填写内容已保留，请重试"}
        finally {saving=false}
    }}, enabled = !saving && valid && hospital.length<=120 && note.length<=10_000) { Text(if(saving)"保存中…" else "保存") } }, dismissButton = { TextButton(close,enabled=!saving) { Text("取消") } })
}
private fun formatFileSize(size: Long): String = if (size < 1024 * 1024) "${maxOf(1, (size + 1023) / 1024)} KB" else String.format(java.util.Locale.ROOT, "%.1f MB", size / 1048576.0)
