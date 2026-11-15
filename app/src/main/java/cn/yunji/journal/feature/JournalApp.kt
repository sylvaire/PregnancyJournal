@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.yunji.journal.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import cn.yunji.journal.core.designsystem.journalPressable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import cn.yunji.journal.BuildConfig
import cn.yunji.journal.core.database.PregnancyEntity
import cn.yunji.journal.core.database.VisitEntity
import cn.yunji.journal.core.designsystem.*
import cn.yunji.journal.domain.PregnancyRules
import cn.yunji.journal.domain.TimelineRules
import cn.yunji.journal.domain.TimelineEvent
import cn.yunji.journal.data.CloudUploadStatusStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import java.time.ZoneId
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun WelcomeScreen(onCreate: () -> Unit) {
    LazyColumn(modifier = Modifier.testTag("welcome-list"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PageHeader("孕迹", "把每一段变化，好好留存") }
        item { Inset {
            JournalCard(soft = true) {
                Spacer(Modifier.height(4.dp))
                DesignIcon("5fbe7.svg", size = 64.dp)
                // 设计稿把主副标题各断成两行（412 dp 基准）。系统字体放大到 200% 时 40 sp 会变 80 sp，
                // 硬换行会让每行只剩三四个字，故仅在标准字号下保留设计断行，放大后交给自然换行。
                val fontScale = LocalDensity.current.fontScale
                Text(
                    if (fontScale > 1.3f) "轻轻记录，安心留存" else "轻轻记录\n安心留存",
                    style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (fontScale > 1.3f) "产检、报告与日常变化，在这里慢慢连成你的孕期故事。"
                    else "产检、报告与日常变化，\n在这里慢慢连成你的孕期故事。",
                    style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
            }
        } }
        item { Inset { RecordRow("孕期档案，离线也可查看", "日期依据留存 · 本机加密保存", "ic-user.svg") } }
        item { Inset { RecordRow("每次产检，都有记录可循", "预约与已完成产检，清楚分开", "ic-calendar-check.svg") } }
        item { Inset { JournalButton("创建我的孕期档案", onClick = onCreate) } }
        item { Inset { Text("无需注册账号 · 数据保存在本机", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

@Composable internal fun Inset(content: @Composable () -> Unit) { Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { content() } }

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun HomeScreen(state: JournalState, onProfile: () -> Unit, onNewVisit: () -> Unit, onVisit: (String) -> Unit,
    model: JournalViewModel? = null, onIntakes: () -> Unit = {}, onVisits: () -> Unit = {}, onDocuments: () -> Unit = {},
    onMeasurements: () -> Unit = {}, onJournal: () -> Unit = {}, onDocument: (String) -> Unit = {}, onBackup: () -> Unit = {},
    onEditPregnancy: () -> Unit = onProfile) {
    val pregnancy = state.active ?: return
    val context=LocalContext.current
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); delay(30_000) } }
    val age = PregnancyRules.age(today, pregnancy.lmpDate?.let(LocalDate::parse), pregnancy.confirmedEdd?.let(LocalDate::parse))
    val edd = PregnancyRules.estimatedEdd(pregnancy.lmpDate?.let(LocalDate::parse), pregnancy.confirmedEdd?.let(LocalDate::parse))
    val invalidFutureEdd = pregnancy.confirmedEdd != null && age == null && edd?.isAfter(today.plusDays(280)) == true
    val invalidFutureLmp = pregnancy.confirmedEdd == null && pregnancy.lmpDate != null && age == null
    val visits = state.visits.filter { it.pregnancyId == pregnancy.id && it.status == "SCHEDULED" }
        .sortedWith(compareBy<VisitEntity> {it.scheduledDate}.thenBy {it.scheduledTime ?: ""})
    val next = visits.firstOrNull { it.scheduledDate >= today.toString() } ?: visits.firstOrNull()
    val recentDocument: TimelineEvent? = if(model!=null) {
        val flow=remember(pregnancy.id,model) {model.journalEntries.timeline(pregnancy.id)
            .map { source -> source?.let {TimelineRules.build(it,ZoneId.systemDefault()).firstOrNull {event->event.type=="DOCUMENT"}} }
            .catch {emit(null)} }
        flow.collectAsStateWithLifecycle(null).value
    } else null
    val backup=remember(context) {CloudUploadStatusStore(context).load()}
    LazyColumn(modifier = Modifier.testTag("home-list"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
                .heightIn(min = 48.dp).testTag("home-profile-switch")
                .clickable(role = Role.Button, onClickLabel = "切换孕期", onClick = onProfile),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("今天，慢慢来", style = MaterialTheme.typography.headlineMedium)
                    Text("${today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))} · ${pregnancy.title}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                DesignIcon("ic-user.svg")
            }
        }
        item { Inset { JournalCard(soft = age != null) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when { age != null -> age.label(); invalidFutureEdd -> "请核对预产期"; invalidFutureLmp -> "请核对末次月经日期"; else -> "待补充日期" },
                    Modifier.align(Alignment.CenterVertically),
                    style = if (age != null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary)
                if (age != null || pregnancy.status != "ONGOING") Text(when {
                    pregnancy.status != "ONGOING" -> "已结束的孕期档案"
                    else -> when {age!!.days<98->"孕早期";age.days<196->"孕中期";else->"孕晚期"}
                }, Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(when { invalidFutureEdd -> "已保存的预产期距今天超过40周，请检查年份和月份"; invalidFutureLmp -> "末次月经日期晚于今天，请检查年份和月份"; edd != null -> "预产期 ${displayDate(edd.toString())} · ${if (pregnancy.confirmedEdd != null) "医生确认" else "估算"}"; else -> "可稍后填写医生确认的预产期" }, style = MaterialTheme.typography.bodySmall)
            if (invalidFutureEdd || invalidFutureLmp) JournalTextAction("修改日期", onClick = onEditPregnancy)
            else if (age == null && edd == null) JournalTextAction("补充日期", onClick = onEditPregnancy)
            if (age != null) Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth((age.days/280f).coerceIn(0f,1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary,RoundedCornerShape(3.dp)))
            }
        } } }
        item { Inset { FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("今天的安排",Modifier.align(Alignment.CenterVertically),style=MaterialTheme.typography.titleLarge)
            JournalTextAction("查看全部", Modifier.align(Alignment.CenterVertically), onClick = onVisits)
        } } }
        item { Inset { if (next == null) RecordRow("还没有产检预约", "添加下一次预约，留住后续安排", "ic-calendar-check.svg", onNewVisit)
            else RecordRow("${if (next.scheduledDate < today.toString()) "待处理的产检" else if (next.scheduledDate == today.toString()) "今天的产检" else "下一次产检"} · ${displayDate(next.scheduledDate)}", "${next.scheduledTime ?: "时间待定"} · ${next.hospital.ifBlank { "医院待补充" }}", "122c6.svg") { onVisit(next.id) } } }
        if (model != null) item { Inset { HomeIntakeSummary(pregnancy.id, today, model, onIntakes) } }
        item { Inset { Text("记录与资料",style=MaterialTheme.typography.titleLarge) } }
        item { Inset { HomeQuickActions(onNewVisit, onDocuments, onMeasurements, onJournal) } }
        recentDocument?.let { document -> item(key = "recent-document") { Inset {
            RecordRow("最近资料 · ${document.title}", "${displayDate(document.date)} · 已保存到本机", "ic-file-text.svg") {onDocument(document.id)}
        } } }
        item { Inset { Text(
            if(backup.hasSuccess) "最近云端完整备份：${displayInstant(backup.lastSuccessAt,ZoneId.systemDefault().id)}" else "尚无云端完整备份 · 查看备份状态",
            // 保持 bodySmall 视觉密度，但用 clickable(minimumInteractiveComponentSize 补足触摸目标)
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = "查看备份状态", role = Role.Button, onClick = onBackup)
                .padding(vertical = 14.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) } }
        item { Inset { RecordRow("管理我的孕期", "修改日期与切换档案", "ic-user.svg", onProfile) } }

    }
}

@Composable
private fun HomeQuickActions(onNewVisit: () -> Unit, onDocuments: () -> Unit, onMeasurements: () -> Unit, onJournal: () -> Unit) {
    val actions = listOf(
        Triple("新建产检预约", "ic-calendar-check.svg", onNewVisit),
        Triple("检查资料", "cdc29.svg", onDocuments),
        Triple("健康指标", "ic-activity.svg", onMeasurements),
        Triple("写备注", "ic-notebook.svg", onJournal),
    )
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minimumWidth = with(density) {
            actions.maxOf { measurer.measure(AnnotatedString(it.first), style).size.width }.toDp()
        } + 56.dp
        val columns = ((maxWidth + 8.dp) / (minimumWidth + 8.dp)).toInt().coerceIn(1, 2)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, icon, open) -> HomeQuickAction(label, icon, Modifier.weight(1f), open) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable private fun HomeQuickAction(label:String,icon:String,modifier:Modifier=Modifier,onClick:()->Unit) {
    // 横排图标与文字减少纵向占用；列数按文字实测宽度计算，大字时整项换行。
    val interaction = remember { MutableInteractionSource() }
    Surface(modifier=modifier.heightIn(min=64.dp)
            .journalPressable(interaction)
            .clickable(interactionSource=interaction, indication=null, onClickLabel=label, role=Role.Button, onClick=onClick),
        shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
            DesignIcon(icon)
            Text(label,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable internal fun ProfilesScreen(state: JournalState, busy: Boolean, onAdd: () -> Unit, onEdit: (String) -> Unit, onSelect: (String) -> Unit, onStatus: (String, String) -> Unit, onHistory: (String) -> Unit, onDelete: (String) -> Unit = {}) {
    var ending by remember { mutableStateOf<PregnancyEntity?>(null) }
    LazyColumn(modifier = Modifier.testTag("profiles-list"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.active?.let { active -> item(key = "active-profile") { Inset { JournalCard(soft = true) {
            Text("当前档案", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            JournalContentHeading(active.title, when (active.status) { "ONGOING" -> "进行中"; "ENDED" -> "已结束"; else -> "已归档" })
        } } } }
        item { Inset { Text("孕期档案", style = MaterialTheme.typography.titleMedium) } }
        items(state.pregnancies, key = { it.id }, contentType = { "pregnancy" }) { p -> Inset {
            RecordRow(p.title, if (p.id == state.active?.id) "当前档案" else "切换到此档案", "ic-user.svg") {
                if (!busy) { if (p.id == state.active?.id) onEdit(p.id) else onSelect(p.id) }
            }
        } }
        state.active?.let { active ->
            item { Inset { RecordRow("查看日期依据历史", "可核对原始日期与修订", "ic-history.svg") { onHistory(active.id) } } }
            item { Inset { RecordRow("编辑", "名称、医院和备注", "ic-edit.svg") { if (!busy) onEdit(active.id) } } }
            item { Inset { RecordRow(if (active.status == "ONGOING") "结束本次孕期" else if (active.status == "ARCHIVED") "移出归档" else "归档",
                "资料保留，可随时查看", "ic-shield.svg") {
                if (!busy) { if (active.status == "ONGOING") ending = active
                    else onStatus(active.id, if (active.status == "ARCHIVED") "ENDED" else "ARCHIVED") }
            } } }
            item { Inset { RecordRow("删除孕期", "进入回收站，需再次确认", "ic-trash.svg") { if (!busy) onDelete(active.id) } } }
        }
        item { Inset { JournalButton("创建新的孕期档案", onClick = onAdd) } }
        item { Inset { Text("孕迹 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
    ending?.let { p -> AlertDialog(onDismissRequest = { ending = null }, title = { Text("结束本次孕期？") }, text = { Text("已有记录会完整保留，你仍可以查看和补录。") },
        confirmButton = { TextButton({ onStatus(p.id, "ENDED"); ending = null }) { Text("确认结束") } }, dismissButton = { TextButton({ ending = null }) { Text("取消") } }) }
}

@Composable internal fun PregnancyEditor(form: PregnancyForm, busy: Boolean, update: (PregnancyForm) -> Unit, save: () -> Unit) {
    LazyColumn(modifier = Modifier.testTag("pregnancy-editor-list"), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Inset { Text("日期可以稍后补充，返回会保留草稿。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { Inset { FormField("档案名称", form.title, { update(form.copy(title = it)) }, 100) } }
        item { Inset { Text("选择日期依据", style = MaterialTheme.typography.titleLarge) } }
        item { Inset { DateField("医生确认的预产期（选填）", form.edd, { update(form.copy(edd = it)) }, clearLabel = "清除预产期") } }
        item { Inset { DateField("末次月经日期（选填）", form.lmp, { update(form.copy(lmp = it)) }, clearLabel = "清除末次月经日期") } }
        item { Inset { JournalCard(soft = true) {
            DesignIcon("42641.svg")
            Text(if (form.edd.isNotBlank()) "已填写医生确认的预产期" else "日期也可以稍后补充", style = MaterialTheme.typography.labelMedium)
            Text("首页将以确认的预产期优先计算孕周。后续修改会保留原始日期依据。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } } }
        item { Inset { FormField("建档医院（选填）", form.hospital, { update(form.copy(hospital = it)) }, 120) } }
        item { Inset { FormField("备注（选填）", form.note, { update(form.copy(note = it)) }, 10_000, false) } }
        item { Inset { JournalButton(if (busy) "正在保存…" else "保存档案", enabled = !busy && form.title.isNotBlank(), onClick = save) } }
    }
}

@Composable internal fun FormField(label: String, value: String, change: (String) -> Unit, max: Int, single: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth()) {
    // 长表单里键盘弹出会把当前字段顶到可视区外；获得焦点时主动滚入视野，
    // 避免用户在半遮挡状态下猜字段位置。
    val bring = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused) {
            // 等 IME inset 动画推进后再滚动，落点不会抖动。
            delay(150)
            runCatching { bring.bringIntoView() }
        }
    }
    JournalField(value, { if (it.length <= max) change(it) }, label,
        modifier = modifier.bringIntoViewRequester(bring).onFocusChanged { focused = it.isFocused },
        singleLine = single, maxLines = if (single) 1 else 5)
}

@Composable internal fun DateField(label: String, value: String, update: (String) -> Unit, required: Boolean = false, maxDate: LocalDate? = null,enabled: Boolean=true, clearLabel: String = "清除日期") {
    var show by remember { mutableStateOf(false) }
    Column {
        OutlinedButton({ show = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled=enabled,shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.bodySmall); Text(value.takeIf(String::isNotBlank)?.let(::displayDate) ?: "选择日期", style = MaterialTheme.typography.bodyLarge) }
            DesignIcon("42641.svg")
        }
        if (!required && value.isNotBlank()) JournalTextAction(clearLabel, enabled = enabled, onClick = { update("") })
    }
    if (show && enabled) ChinesePickerContent {
        val initial = value.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = maxDate == null || !java.time.Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(maxDate)
            })
        val calendarMaxHeight=with(LocalDensity.current) {
            (androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()-100.dp).coerceAtLeast(120.dp)
        }
        DatePickerDialog(onDismissRequest = { show = false }, confirmButton = { TextButton({ state.selectedDateMillis?.let { update(java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; show = false }) { Text("确定") } }, dismissButton = { TextButton({ show = false }) { Text("取消") } }) {
          Box(Modifier.heightIn(max=calendarMaxHeight).testTag("date-picker-scroll")
              .verticalScroll(rememberScrollState())) {
            ChinesePickerContent {
                DatePicker(state,headline={
                    val selected=state.selectedDateMillis?.let {java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()}
                    Text(selected?.format(DateTimeFormatter.ofPattern("yyyy年M月d日",java.util.Locale.CHINA)) ?: "未选择日期",
                        Modifier.padding(start=24.dp,end=12.dp,bottom=12.dp),
                        style=if(LocalDensity.current.fontScale>1.3f) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall)
                })
            }
          }
        }
    }
}

@Composable internal fun VisitEditor(form: VisitForm, busy: Boolean, update: (VisitForm) -> Unit, save: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Inset { Text("时间不确定可留空，返回会保留草稿。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Inset { DateField("预约日期", form.date, { update(form.copy(date = it)) }, true) }
        Inset { VisitTimeField(form.time) { update(form.copy(time = it)) } }
        Inset { FormField("医院（选填）", form.hospital, { update(form.copy(hospital = it)) }, 120) }
        Inset { FormField("检查项目（选填）", form.items, { update(form.copy(items = it)) }, 10_000, false) }
        Inset { FormField("准备事项（选填）", form.preparation, { update(form.copy(preparation = it)) }, 10_000, false) }
        Inset { JournalButton(if (busy) "正在保存…" else "保存预约", enabled = !busy && form.date.isNotBlank(), onClick = save) }
    }
}

@Composable internal fun VisitDetail(visit: VisitEntity?, busy: Boolean, onEdit: () -> Unit, onComplete: () -> Unit, onCancel: (Boolean) -> Unit, onDocuments: () -> Unit, onDelete: () -> Unit = {},
    onSetReminder: (String, String) -> Unit = { _, _ -> }, onClearReminder: () -> Unit = {}) {
    var confirmingCancel by remember { mutableStateOf(false) }
    var editingReminder by remember(visit?.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LazyColumn(modifier = Modifier.testTag("visit-detail-list"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (visit == null) item { Inset { Text("记录不存在或已删除") } }
        else {
            item { Inset { JournalCard(soft = true) {
                Text(visitStatus(visit), style = MaterialTheme.typography.bodySmall)
                Text(visit.items.ifBlank { "产检预约" }, style = MaterialTheme.typography.headlineMedium)
                JournalKeyValue("预约时间", displayDateTime(visit.scheduledDate, visit.scheduledTime) + if (visit.scheduledTime == null) " · 时间待定" else "")
                JournalKeyValue("预约医院", visit.hospital.ifBlank { "医院待补充" })
                if (visit.actualDate != null) Text("实际就诊 ${displayDate(visit.actualDate!!)}${visit.actualTime?.let { " $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                if (!visit.actualHospital.isNullOrBlank()) Text("就诊医院：${visit.actualHospital}", style = MaterialTheme.typography.bodySmall)
            } } }
            if (visit.status == "SCHEDULED") item { Inset {
                VisitReminderCard(visit.reminderDate, visit.reminderTime, busy,
                    onEdit = { editingReminder = true }, onClear = onClearReminder)
            } }
            item { Inset { Text("准备事项", style = MaterialTheme.typography.titleLarge) } }
            item { Inset { Text(visit.preparation.ifBlank { "暂无准备事项，以医院通知为准" }) } }
            if (!visit.actualResult.isNullOrBlank()) {
                item { Inset { Text("检查结果", style = MaterialTheme.typography.titleLarge) } }
                item { Inset { Text(visit.actualResult) } }
            }
            if (visit.advice.isNotBlank()) {
                item { Inset { Text("就诊备注与医嘱", style = MaterialTheme.typography.titleLarge) } }
                item { Inset { Text(visit.advice) } }
            }
            item { Inset { JournalButton("产检资料", enabled = !busy, secondary = true, onClick = onDocuments) } }
            if (visit.status != "CANCELLED") {
                item { Inset { JournalButton("修改预约", enabled = !busy, secondary = true, onClick = onEdit) } }
                item { Inset { JournalButton(if (visit.status == "COMPLETED") "修改就诊记录" else "记录实际就诊", enabled = !busy, onClick = onComplete) } }
            }
            if (visit.status == "CANCELLED") item { Inset { JournalButton("恢复预约", enabled = !busy, onClick = { onCancel(false) }) } }
            item { Inset { JournalCard {
                JournalSectionTitle("记录管理")
                if (visit.status == "SCHEDULED") {
                    JournalSecondaryButton("取消这次预约", Modifier.testTag("visit-cancel"), enabled = !busy,
                        onClick = { confirmingCancel = true })
                }
                JournalDangerButton("删除产检记录", Modifier.testTag("visit-delete"), enabled = !busy, onClick = onDelete)
            } } }
        }
    }
    if (confirmingCancel) AlertDialog(onDismissRequest = { confirmingCancel = false }, title = { Text("取消这次预约？") }, text = { Text("预约资料会保留，之后仍可恢复。") },
        confirmButton = { TextButton({ confirmingCancel = false; onCancel(true) }) { Text("确认取消") } }, dismissButton = { TextButton({ confirmingCancel = false }) { Text("暂不取消") } })
    if (editingReminder && visit != null) {
        val defaultDate = visit.reminderDate ?: runCatching { LocalDate.parse(visit.scheduledDate).minusDays(1).toString() }.getOrDefault(LocalDate.now().toString())
        val defaultTime = visit.reminderTime ?: visit.scheduledTime ?: "09:00"
        var date by rememberSaveable(visit.id, editingReminder) { mutableStateOf(defaultDate) }
        var time by rememberSaveable(visit.id, editingReminder) { mutableStateOf(defaultTime) }
        val valid = runCatching { LocalDate.parse(date); require(Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(time)) }.isSuccess
        AlertDialog(onDismissRequest = { editingReminder = false }, modifier = Modifier.testTag("visit-reminder-dialog"),
            title = { Text("设置产检提醒") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("预约：${visit.scheduledDate} ${visit.scheduledTime ?: "时间待定"}")
                DateField("提醒日期", date, { date = it }, required = true)
                JournalField(time, { time = it }, "提醒时间", supportingText = "24小时制", isError = !valid,
                    modifier = Modifier.testTag("visit-reminder-time"))
                if (visit.scheduledTime == null) Text("无具体预约时间时，请自行选择提醒时刻；不会假设午夜提醒。", style = MaterialTheme.typography.bodySmall)
                Text("提醒时间按本机当前时区计算；通知权限关闭时仍保留应用内待办。锁屏默认隐藏医院和具体安排。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } },
            confirmButton = { TextButton({
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                onSetReminder(date, time); editingReminder = false
            }, enabled = valid, modifier = Modifier.testTag("confirm-visit-reminder")) { Text("保存提醒") } },
            dismissButton = { TextButton({ editingReminder = false }) { Text("取消") } })
    }
}
