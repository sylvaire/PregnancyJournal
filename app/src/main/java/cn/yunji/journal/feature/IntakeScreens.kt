package cn.yunji.journal.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.yunji.journal.core.database.MedicationIntakeEntity
import cn.yunji.journal.core.designsystem.*
import cn.yunji.journal.data.IntakeForm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun HomeIntakeSummary(pregnancyId: String, date: LocalDate, model: JournalViewModel, open: () -> Unit) {
    val versions by remember(pregnancyId, model) { model.intakes.versions(pregnancyId) }.collectAsStateWithLifecycle(emptyList())
    val points by remember(pregnancyId, date, model) { model.intakes.occurrences(pregnancyId, date.toString()) }.collectAsStateWithLifecycle(emptyList())
    val records by remember(pregnancyId, model) { model.intakes.history(pregnancyId) }.collectAsStateWithLifecycle(emptyList())
    var loading by remember(pregnancyId, date) { mutableStateOf(true) }
    var failed by remember(pregnancyId, date) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val zone = ZoneId.systemDefault()
    LaunchedEffect(pregnancyId, date, versions, resumed, zone) {
        if (!resumed) return@LaunchedEffect
        try { model.intakes.ensureDay(pregnancyId, date, zone); failed = false }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    val activeIds = points.filter { it.deletedAt == null && it.state == "ACTIVE" }.map { it.id }.toSet()
    val taken = records.count { it.occurrenceId in activeIds && it.status == "TAKEN" }
    val skipped = records.count { it.occurrenceId in activeIds && it.status == "SKIPPED" }
    val recordedIds=records.mapNotNull {it.occurrenceId}.toSet()
    val next=points.filter {it.id in activeIds && it.id !in recordedIds}.minByOrNull {it.scheduledAt}
    HomeIntakeSummaryCard(loading, failed, activeIds.size, taken, skipped, next?.localTime?.take(5),
        records.count { it.sourceOccurrenceId == null && it.eventDate == date.toString() }, open)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeIntakeSummaryCard(loading: Boolean, failed: Boolean, total: Int, taken: Int,
    skipped: Int, nextTime: String?, manual: Int, open: () -> Unit) {
    val ready = !loading && !failed
    val pending = (total - taken - skipped).coerceAtLeast(0)
    JournalCard(soft = ready && nextTime != null) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("今日服药记录", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleMedium)
            JournalTextAction("查看服药记录", Modifier.align(Alignment.CenterVertically), onClick = open)
        }
        when {
            loading -> Text("正在读取今日计划…", style = MaterialTheme.typography.bodyMedium)
            failed -> Text("暂时无法读取计划，请打开记录重试", style = MaterialTheme.typography.bodyMedium)
            total == 0 -> Text("今天没有固定服药计划", style = MaterialTheme.typography.bodyMedium)
            nextTime != null -> FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.Start),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("下一项 · 用药待确认", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(nextTime, style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("未记录", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$pending 项", style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            }
            else -> Text("今天的固定计划已全部记录", style = MaterialTheme.typography.bodyMedium)
        }
        if (ready && total > 0) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (stat in listOf("固定计划 $total 项", "已服用 $taken 项", "已跳过 $skipped 项")) {
                    Text(stat, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (ready && manual > 0) Text("另有补记${manual}条", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IntakeScreen(pregnancyId: String?, model: JournalViewModel, openPlans: () -> Unit, open: (String?, String?, String, String) -> Unit) {
    if (pregnancyId == null) { Inset { Text("请先创建或恢复孕期档案。") }; return }
    val versions by remember(pregnancyId, model) { model.intakes.versions(pregnancyId) }.collectAsStateWithLifecycle(emptyList())
    val drugs by remember(pregnancyId, model) { model.medications.medications(pregnancyId) }.collectAsStateWithLifecycle(emptyList())
    var date by rememberSaveable(pregnancyId) { mutableStateOf(LocalDate.now().toString()) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            val next = LocalDate.now()
            if (date == today.toString()) date = next.toString()
            today = next; zone = ZoneId.systemDefault()
            delay(30_000)
        }
    }
    val points by remember(pregnancyId, date, model) { model.intakes.occurrences(pregnancyId, date) }.collectAsStateWithLifecycle(emptyList())
    val records by remember(pregnancyId, model) { model.intakes.history(pregnancyId) }.collectAsStateWithLifecycle(emptyList())
    val allPoints by remember(pregnancyId, model) { model.intakes.plannedHistory(pregnancyId) }.collectAsStateWithLifecycle(emptyList())
    var history by rememberSaveable(pregnancyId) { mutableStateOf(false) }
    var manualOnly by rememberSaveable(pregnancyId) { mutableStateOf(false) }
    var dayQuery by rememberSaveable(pregnancyId) { mutableStateOf("") }
    var historyQuery by rememberSaveable(pregnancyId) { mutableStateOf("") }
    var datePickerExpanded by rememberSaveable(pregnancyId) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pregnancyId, date, zone, versions, attempt, resumed) {
        if (!resumed) return@LaunchedEffect
        try { error = null; model.intakes.ensureDay(pregnancyId, LocalDate.parse(date), zone) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "暂时无法读取当天计划，已有记录仍然保留" }
    }
    val active = points.filter { it.deletedAt == null && it.state == "ACTIVE" }
    val visibleToday = active.filter { point ->
        val plan = versions.firstOrNull { it.id == point.planVersionId }
        val name = plan?.medicationName?.takeIf { it.isNotBlank() }
            ?: drugs.firstOrNull { it.id == plan?.medicationId }?.name.orEmpty()
        dayQuery.isBlank() || name.contains(dayQuery.trim(), ignoreCase = true) || point.localTime.contains(dayQuery.trim())
    }
    val visibleHistory = records.filter { record ->
        val plan = versions.firstOrNull { it.id == record.planVersionId }
        val drugName = plan?.medicationName?.takeIf { it.isNotBlank() }
            ?: drugs.firstOrNull { it.id == plan?.medicationId }?.name.orEmpty()
        (!manualOnly || record.sourceOccurrenceId == null) &&
            (historyQuery.isBlank() || record.eventDate.contains(historyQuery.trim()) || drugName.contains(historyQuery.trim(), ignoreCase = true))
    }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f).testTag("intake-list"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Inset { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JournalTag(!history, { history = false; manualOnly = false }, label = { Text("今天") })
            JournalTag(history, { history = true; manualOnly = false }, label = { Text("历史") })
            JournalTag(false, openPlans, label = { Text("方案") })
        } } }
        if (!history) {
            item { Inset { OutlinedTextField(dayQuery, { dayQuery = it }, modifier = Modifier.fillMaxWidth().testTag("intake-day-search"), label = { Text("搜索服药记录") }, placeholder = { Text("输入药品名称或计划时间") }, singleLine = true, shape=RoundedCornerShape(16.dp)) } }
            item { Inset { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (date == today.toString()) "今天的服药" else "${displayDate(date)}的服药", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleLarge)
                JournalTextAction("切换日期", Modifier.align(Alignment.CenterVertically), onClick = { datePickerExpanded = !datePickerExpanded })
            } } }
            if (datePickerExpanded) {
                item { Inset { DateField("计划日期", date, { date = it }, required = true) } }
                item { Inset { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JournalTextAction("前一天", onClick = { date = LocalDate.parse(date).minusDays(1).toString() })
                    JournalTextAction("今天", onClick = { date = LocalDate.now().toString() })
                    JournalTextAction("后一天", onClick = { date = LocalDate.parse(date).plusDays(1).toString() })
                } } }
            }
            if (error != null) item { Inset { JournalError(error!!, retry = { attempt++ }) } }
            if (active.isEmpty() && error == null) item { Inset { Text(if (versions.isEmpty()) "请先在用药方案中录入方案。" else "这一天没有固定服药计划；按需或原文方案可使用补记。") } }
            if (active.isNotEmpty() && visibleToday.isEmpty() && dayQuery.isNotBlank()) item { Inset { Text("没有匹配的服药计划") } }
            items(visibleToday, key = { it.id }, contentType = { "intake-point" }) { point ->
                val plan = versions.firstOrNull { it.id == point.planVersionId }
                val drug = drugs.firstOrNull { it.id == plan?.medicationId }
                val record = records.firstOrNull { it.occurrenceId == point.id }
                var expanded by rememberSaveable(point.id) { mutableStateOf(false) }
                val name = plan?.medicationName?.takeIf { it.isNotBlank() } ?: drug?.name ?: "药品"
                val state = when (record?.status) { "TAKEN" -> "已服用"; "SKIPPED" -> "已跳过"; else -> "待确认" }
                Inset {
                    IntakeRecordCard(name,state,"计划时刻",point.localTime.take(5),"${if(record==null) "固定计划" else "计划记录"} · 第${plan?.version ?: "?"}版方案",
                        if(record?.status=="TAKEN") "实际用量" else "方案用量",
                        if(record?.status=="TAKEN") intakeDose(record.dose,record.unit) else intakeDose(plan?.dose,plan?.unit),
                        expanded,Modifier.testTag("intake-point-${point.id}")) {expanded=!expanded}
                    if (expanded) JournalCard {
                        JournalKeyValue("计划时间",displayDateTime(point.plannedDate,point.localTime))
                        JournalKeyValue("方案版本","第${plan?.version ?: "?"}版")
                        if (plan?.dose != null) JournalKeyValue("方案用量",intakeDose(plan.dose,plan.unit))
                        JournalKeyValue("计划时区",point.zoneId)
                        if (record == null) FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                            JournalTextAction("已服用", Modifier.testTag("take-${point.id}"), onClick = { open(point.id, null, "TAKEN", date) })
                            JournalTextAction("跳过", onClick = { open(point.id, null, "SKIPPED", date) })
                        } else {
                            IntakeSummary(record)
                            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                                JournalTextAction("修改记录", onClick = { open(point.id, record.id, record.status, date) })
                                JournalTextAction("删除记录", danger = true, onClick = { removing = record.id })
                            }
                        }
                    }
                }
            }
            val manualForDate = records.filter { record ->
                val plan = versions.firstOrNull { it.id == record.planVersionId }
                val name = plan?.medicationName?.takeIf { it.isNotBlank() }
                    ?: drugs.firstOrNull { it.id == plan?.medicationId }?.name.orEmpty()
                record.sourceOccurrenceId == null && record.eventDate == date &&
                    (dayQuery.isBlank() || name.contains(dayQuery.trim(), ignoreCase = true))
            }
            if (manualForDate.isNotEmpty()) item { Inset { Text("当天补记", style = MaterialTheme.typography.titleLarge) } }
            items(manualForDate, key = { "manual:${it.id}" }, contentType = { "manual-intake" }) { record ->
                val plan = versions.firstOrNull { it.id == record.planVersionId }
                var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
                val name = plan?.medicationName?.takeIf { it.isNotBlank() } ?: drugs.firstOrNull { it.id == plan?.medicationId }?.name ?: "药品"
                Inset {
                    IntakeRecordCard(name,if(record.status=="TAKEN") "已服用" else "已跳过",
                        if(record.actualAt!=null) "实际时间" else "记录日期",
                        record.actualAt?.let {displayInstant(it,record.zoneId)} ?: displayDate(record.eventDate),
                        "补记 · 第${plan?.version ?: "?"}版方案","实际用量",intakeDose(record.dose,record.unit),
                        expanded,Modifier.testTag("intake-manual-${record.id}")) {expanded=!expanded}
                    if (expanded) JournalCard {
                        IntakeSummary(record)
                        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                            JournalTextAction("修改记录", onClick = { open(null, record.id, record.status, date) })
                            JournalTextAction("删除记录", danger = true, onClick = { removing = record.id })
                        }
                    }
                }
            }
            item { Inset { Text("补记实际用药；跳过请点开对应计划。",style=MaterialTheme.typography.bodySmall) } }
            item { Inset { Text("未记录不等于漏服；请按实际情况记录，不提供补服或剂量建议。计划提醒按方案时刻与通知设置发送。", style = MaterialTheme.typography.bodySmall) } }
        } else {
            item { Inset { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("服药历史", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleLarge)
                JournalTag(manualOnly, { manualOnly = !manualOnly }, modifier = Modifier.align(Alignment.CenterVertically), label = { Text("仅看补记") })
            } } }
            item { Inset { OutlinedTextField(historyQuery, { historyQuery = it }, modifier = Modifier.fillMaxWidth().testTag("intake-history-search"), label = { Text("搜索服药记录") }, placeholder = { Text("输入日期或药品名称") }, singleLine = true, shape=RoundedCornerShape(16.dp)) } }
            if (visibleHistory.isEmpty()) item { Inset { JournalEmptyState(if (records.isEmpty()) "还没有服药记录" else "没有匹配的服药记录") } }
            items(visibleHistory, key = { it.id }, contentType = { "intake-history" }) { record ->
                val plan = versions.firstOrNull { it.id == record.planVersionId }
                val point = allPoints.firstOrNull { it.id == record.sourceOccurrenceId }
                var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
                val name = plan?.medicationName?.takeIf { it.isNotBlank() }
                    ?: drugs.firstOrNull { it.id == plan?.medicationId }?.name ?: "药品"
                val status = if (record.status == "TAKEN") "已服用" else "已跳过"
                Inset {
                    IntakeRecordCard(name,status,if(record.actualAt!=null) "实际时间" else "记录日期",
                        record.actualAt?.let {displayInstant(it,record.zoneId)} ?: displayDate(record.eventDate),
                        "${if(record.sourceOccurrenceId==null) "补记" else "计划记录"} · 第${plan?.version ?: "?"}版方案",
                        "实际用量",intakeDose(record.dose,record.unit),expanded,
                        Modifier.testTag("intake-history-${record.id}")) {expanded=!expanded}
                    if (expanded) JournalCard {
                        if (point != null) JournalKeyValue("原计划",displayDateTime(point.plannedDate,point.localTime))
                        IntakeSummary(record)
                        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                            JournalTextAction("修改记录", onClick = { open(record.sourceOccurrenceId, record.id, record.status, record.eventDate) })
                            JournalTextAction("删除记录", danger = true, onClick = { removing = record.id })
                        }
                    }
                }
            }
            item { Inset { Text("未记录不等于漏服；请按实际情况记录，不提供补服或剂量建议。计划提醒按方案时刻与通知设置发送。", style = MaterialTheme.typography.bodySmall) } }
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp)) {
        JournalButton("补记服药", enabled=versions.isNotEmpty(),onClick={open(null,null,"TAKEN",date)})
    }
    }
    removing?.let { id -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("删除这条服药记录？") },
        text = { Text("移入全局回收站保留30天。药品、方案和原计划时刻不变；如果重新记录同一计划，恢复旧记录前需处理冲突。") },
        confirmButton = { TextButton({ removing = null; model.deleteRoot("INTAKE", id) }, Modifier.testTag("confirm-intake-removal")) { Text("移入回收站") } },
        dismissButton = { TextButton({ removing = null }) { Text("取消") } }) }
}

@Composable private fun IntakeSummary(record: MedicationIntakeEntity) {
    if (record.actualAt != null) JournalKeyValue("实际时间",displayInstant(record.actualAt,record.zoneId))
    if (record.dose != null) JournalKeyValue("实际用量",intakeDose(record.dose,record.unit))
    JournalKeyValue("记录时区",record.zoneId)
    if (record.note.isNotBlank()) {JournalSectionTitle("备注");Text(record.note)}
}

private fun intakeDose(dose:String?,unit:String?):String = dose?.let {"$it ${unit.orEmpty()}".trim()} ?: "未记录"

/** Medication cards distribute identity/status and time/quantity across the available width. */
@Composable internal fun IntakeRecordCard(name:String,status:String,timeLabel:String,time:String,origin:String,
    doseLabel:String,dose:String,expanded:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    Surface(onClick=onClick,modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),
        color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
                Text(name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                Surface(shape=RoundedCornerShape(8.dp),
                    color=if(status=="已服用") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outlineVariant,
                    contentColor=if(status=="已服用") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
                    Text(status,Modifier.padding(horizontal=10.dp,vertical=5.dp),style=MaterialTheme.typography.labelMedium)
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(timeLabel,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(time,style=MaterialTheme.typography.bodyLarge)
                }
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(doseLabel,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign=androidx.compose.ui.text.style.TextAlign.End)
                    Text(dose,style=MaterialTheme.typography.bodyLarge,textAlign=androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(origin,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if(expanded) "收起" else "展开",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                Box(Modifier.rotate(if(expanded)270f else 90f)) {DesignIcon("cfd63.svg",size=16.dp)}
            }
        }
    }
}

@Composable
internal fun IntakeEditorScreen(form: IntakeForm, model: JournalViewModel, busy: Boolean) {
    val versions by remember(form.pregnancyId, model) { model.intakes.versions(form.pregnancyId) }.collectAsStateWithLifecycle(emptyList())
    val drugs by remember(form.pregnancyId, model) { model.medications.medications(form.pregnancyId) }.collectAsStateWithLifecycle(emptyList())
    val plan = versions.firstOrNull { it.id == form.planVersionId }
    val drug = drugs.firstOrNull { it.id == plan?.medicationId }
    var expanded by remember { mutableStateOf(false) }
    val manual = form.occurrenceId == null
    val medicineName = plan?.medicationName?.takeIf { it.isNotBlank() } ?: drug?.name ?: "药品"
    fun update(value: IntakeForm) { model.updateIntake(value) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!manual) {
            Text(medicineName, style = MaterialTheme.typography.titleLarge)
            Text("依据：第${plan?.version ?: ""}版方案。这里只记录实际情况，不提供用药建议。", style = MaterialTheme.typography.bodySmall)
        }
        if (form.plannedDate != null) Text("原计划 ${displayDateTime(form.plannedDate!!, form.plannedTime)} · ${form.plannedZoneId}")
        if (form.occurrenceId != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JournalTag(form.status == "TAKEN", { update(form.copy(status = "TAKEN")) }, label = { Text("已服用") })
            JournalTag(form.status == "SKIPPED", { update(form.copy(status = "SKIPPED")) }, label = { Text("跳过") })
        }
        if (form.status == "TAKEN") {
            DateField("实际服药日期", form.actualDate, { update(form.copy(actualDate = it)) }, required = true, maxDate = LocalDate.now())
            RecordTimeField(form.actualTime, { update(form.copy(actualTime = it)) }, "实际时间（24小时制）",
                modifier = Modifier.testTag("intake-actual-time"), enabled=!busy)
            if (manual) {
                if (form.recordId == null) Box {
                    JournalSecondaryButton("药品：$medicineName · 第${plan?.version ?: "?"}版") { expanded = true }
                    DropdownMenu(expanded, { expanded = false }) { versions.sortedByDescending { it.effectiveAt }.forEach { version ->
                        DropdownMenuItem(text = { Text("${version.medicationName.ifBlank { drugs.firstOrNull { it.id == version.medicationId }?.name ?: "药品" }} · 第${version.version}版 · ${version.dose.orEmpty()} ${version.unit.orEmpty()}") }, onClick = {
                            expanded = false; update(form.copy(planVersionId = version.id, dose = version.dose.orEmpty(), unit = version.unit.orEmpty()))
                        })
                    } }
                } else Text("药品：$medicineName · 第${plan?.version ?: "?"}版", style = MaterialTheme.typography.bodyLarge)
            }
            val offsets = runCatching { ZoneId.of(form.zoneId).rules.getValidOffsets(LocalDate.parse(form.actualDate).atTime(LocalTime.parse(form.actualTime))) }.getOrDefault(emptyList())
            if (offsets.size > 1) {
                Text("这一天该时刻出现两次，请选择实际所在时段。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { offsets.forEachIndexed { index, offset ->
                    JournalTag((offsets.firstOrNull { it.totalSeconds == form.offsetSeconds } ?: offsets.first()) == offset, { update(form.copy(offsetSeconds = offset.totalSeconds)) }, label = { Text("第${index + 1}次（UTC${offset.id}）") })
                } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JournalField(form.dose, { update(form.copy(dose = it.take(20))) }, "实际剂量",
                    modifier = Modifier.weight(1f).testTag("intake-dose"))
                JournalField(form.unit, { update(form.copy(unit = it.take(20))) }, "单位",
                    modifier = Modifier.weight(1f).testTag("intake-unit"))
            }
            if (manual) JournalField("已服用", {}, "状态", readOnly = true)
            Text(if (form.recordId == null) "用量按所选方案预填，可按实际情况修改；剂量与单位也可同时留空。" else "这里保留的是原实际记录，请按实际情况修改；剂量与单位也可同时留空。", style = MaterialTheme.typography.bodySmall)
        } else Text("仅记录此计划已跳过，不会自动推断实际服药时间或用量。")
        Text(if (form.occurrenceId == null) "补记说明" else "记录说明", style = MaterialTheme.typography.titleLarge)
        FormField("备注（选填）", form.note, { update(form.copy(note = it)) }, 10_000, false)
        if (form.occurrenceId == null) RecordRow("按实际情况保存", "补记已服用；跳过请选对应计划", "ic-check.svg")
        JournalButton(if (busy) "正在保存…" else "保存服药记录", enabled = !busy, onClick = model::saveIntake)
        Text("实际记录时区：${form.zoneId} · 返回保留加密草稿。这里只记录实际情况，不提供用药建议。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(32.dp))
    }
}

