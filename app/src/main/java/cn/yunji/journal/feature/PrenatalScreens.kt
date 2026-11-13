@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.yunji.journal.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.yunji.journal.core.database.VisitEntity
import cn.yunji.journal.core.designsystem.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun visitStatus(visit: VisitEntity): String = when (visit.status) {
    "COMPLETED" -> "已完成"
    "CANCELLED" -> "已取消"
    else -> if (visit.scheduledDate < LocalDate.now().toString()) "待补录就诊情况" else "待进行"
}

@Composable
internal fun VisitsScreen(state: JournalState, onAdd: () -> Unit, onSelect: (String) -> Unit) {
    var calendar by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("ALL") }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var day by rememberSaveable { mutableStateOf("") }
    var monthPickerExpanded by rememberSaveable { mutableStateOf(false) }
    val month = YearMonth.parse(monthText)
    val visits = state.visits.filter { it.pregnancyId == state.active?.id }
    val shown = visits.filter { (status == "ALL" || it.status == status) &&
        (!calendar || it.scheduledDate.startsWith(monthText) && (day.isBlank() || it.scheduledDate == day)) }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(modifier = Modifier.testTag("visits-list"), contentPadding = PaddingValues(bottom = if (calendar) 96.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader(if (calendar) "产检日历" else "产检", if (calendar) "按日期查看预约与实际就诊" else "预约、实际就诊和取消记录，清楚留存") }
        if (!calendar) item { Inset { JournalButton("新建产检预约", enabled = state.active != null, onClick = onAdd) } }
        item { Inset { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JournalTag(selected = !calendar, onClick = { calendar = false }, label = { Text("列表") })
            JournalTag(selected = calendar, onClick = { calendar = true }, label = { Text("日历") })
        } } }
        item { Inset { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL" to "全部", "SCHEDULED" to "待进行", "COMPLETED" to "已完成", "CANCELLED" to "已取消").forEach { (key, label) ->
                JournalTag(selected = status == key, onClick = { status = key }, label = { Text(label) })
            }
        } } }
        if (calendar) item { Inset { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleLarge)
            TextButton({ monthPickerExpanded = !monthPickerExpanded }) { Text("切换月份") }
        } } }
        if (calendar && monthPickerExpanded) item { Inset { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ monthText = month.minusMonths(1).toString(); day = "" }) { Text("上月") }
            TextButton({ monthText = YearMonth.now().toString(); day = "" }) { Text("本月") }
            TextButton({ monthText = month.plusMonths(1).toString(); day = "" }) { Text("下月") }
        } } }
        if (calendar) item { Inset { Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) { listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } }
            val offset = month.atDay(1).dayOfWeek.value - 1
            repeat((offset + month.lengthOfMonth() + 6) / 7) { week ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { weekday ->
                        val number = week * 7 + weekday - offset + 1
                        Box(Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.Center) {
                            if (number in 1..month.lengthOfMonth()) {
                                val date = month.atDay(number).toString()
                                val hasVisit = visits.any { it.scheduledDate == date && it.status != "CANCELLED" }
                                TextButton({ day = if (day == date) "" else date }, Modifier.fillMaxSize(), contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.textButtonColors(containerColor = if (day == date) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(number.toString(), color = if (day == date) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        Box(Modifier.size(4.dp).background(if (hasVisit) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, CircleShape))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text(if (day.isBlank()) "按预约日期显示 · 圆点表示未取消的产检" else "$day · 再点一次日期可查看整月", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } } }
        if (shown.isEmpty()) item { Inset {
            JournalEmptyState(
                title = if (visits.isEmpty()) "还没有产检记录" else "当前筛选下没有记录",
                subtitle = if (visits.isEmpty()) "添加下一次预约，或补录之前的产检。" else "试试其他日期或查看全部状态。",
            )
        } }
        items(shown, key = { it.id }, contentType = { "visit" }) { v -> Inset {
            RecordRow("${displayDate(v.scheduledDate)} · ${v.items.ifBlank { "产检预约" }}", "${v.scheduledTime ?: "时间待定"} · ${visitStatus(v)}", "ic-calendar-check.svg") { onSelect(v.id) }
        } }
    }
    if (calendar) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.background)
        .padding(horizontal = 24.dp, vertical = 8.dp)) {
        JournalButton("新建产检预约", enabled = state.active != null, onClick = onAdd)
    }
    }
}

@Composable
internal fun VisitTimeField(value: String, update: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Column {
        OutlinedButton({ show = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Text("预约时间（选填）", style = MaterialTheme.typography.bodySmall)
                Text(value.ifBlank { "时间待定" }, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (value.isNotBlank()) TextButton({ update("") }) { Text("设为时间待定") }
    }
    if (show) {
        val initial = runCatching { LocalTime.parse(value) }.getOrNull()
        val clock = rememberTimePickerState(initialHour = initial?.hour ?: 9, initialMinute = initial?.minute ?: 0, is24Hour = true)
        AlertDialog(onDismissRequest = { show = false }, title = { Text("预约时间") }, text = { TimeInput(clock) },
            confirmButton = { TextButton({ update(String.format(Locale.ROOT, "%02d:%02d", clock.hour, clock.minute)); show = false }) { Text("确定") } },
            dismissButton = { TextButton({ show = false }) { Text("取消") } })
    }
}

@Composable
internal fun ActualVisitEditor(form: VisitForm, busy: Boolean, update: (VisitForm) -> Unit, save: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Inset { DateField("实际就诊日期", form.actualDate, { update(form.copy(actualDate = it)) }, required = true, maxDate = LocalDate.now()) } }
        item { Inset { OutlinedTextField(form.actualTime, { update(form.copy(actualTime = it.take(5))) },
            label = { Text("就诊时间（选填）") }, placeholder = { Text("例如 09:30") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("actual-visit-time")) } }
        item { Inset { FormField("就诊医院（选填）", form.actualHospital, { update(form.copy(actualHospital = it)) }, 120,
            modifier = Modifier.fillMaxWidth().testTag("actual-visit-hospital")) } }
        item { Inset { FormField("检查结果（选填）", form.actualResult, { update(form.copy(actualResult = it)) }, 10_000, false,
            modifier = Modifier.fillMaxWidth().testTag("actual-visit-result")) } }
        item { Inset { FormField("就诊备注（选填）", form.advice, { update(form.copy(advice = it)) }, 10_000, false) } }
        item { Inset { Text("关联预约", style = MaterialTheme.typography.titleMedium) } }
        item { Inset { RecordRow(form.items.ifBlank { "本次产检预约" },
            "原预约 ${displayDate(form.date)}${form.hospital.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", "ic-calendar-check.svg") } }
        item { Inset { JournalButton(if (busy) "正在保存…" else "保存就诊记录", enabled = !busy && form.actualDate.isNotBlank(), onClick = save) } }
        item { Inset { Text("以医院正式检查结果为准；原预约信息保持不变，返回会保留草稿。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

@Composable
internal fun PregnancyHistoryScreen(state: JournalState, id: String) {
    val history = state.dateRevisions.filter { it.pregnancyId == id }.sortedByDescending { it.effectiveAt }
    var filter by rememberSaveable(id) { mutableStateOf("全部") }
    var query by rememberSaveable(id) { mutableStateOf("") }
    var expandedId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val visible = history.filter { revision ->
        (filter == "全部" || filter == "预产期" && revision.confirmedEdd != null ||
            filter == "末次月经" && revision.lmpDate != null || filter == "修订" && revision.id != history.firstOrNull()?.id) &&
            (query.isBlank() || listOf(revision.lmpDate, revision.confirmedEdd, revision.source).any { it?.contains(query.trim(), ignoreCase = true) == true })
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Inset { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "预产期", "末次月经", "修订").forEach { name -> JournalTag(filter == name, { filter = name }, { Text(name) }) }
        } } }
        item { Inset { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().testTag("dating-history-search"),
            placeholder = { Text("搜索日期或依据说明") }, singleLine = true) } }
        item { Inset { Text("当前与历史依据", style = MaterialTheme.typography.titleMedium) } }
        if (history.isEmpty()) item { Inset { JournalEmptyState("暂无日期修订记录", "日期变更后会保留原始依据") } }
        else if (visible.isEmpty()) item { Inset { JournalEmptyState("没有找到相关依据", "试试其他关键词或分类") } }
        items(visible, key = { it.id }, contentType = { "dating" }) { revision -> Inset {
            val current = revision.id == history.firstOrNull()?.id
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordRow(if (current) "当前日期依据" else "历史日期依据",
                    listOfNotNull(revision.lmpDate?.let { "末次月经 ${displayDate(it)}" },
                        revision.confirmedEdd?.let { "预产期 ${displayDate(it)}" }).joinToString(" · ").ifBlank { "日期未填写" },
                    "cdc29.svg", { expandedId = if (expandedId == revision.id) null else revision.id })
                if (expandedId == revision.id) JournalCard(soft = current) {
                    Text("医生确认预产期：${revision.confirmedEdd?.let(::displayDate) ?: "未填写"}")
                    Text("末次月经：${revision.lmpDate?.let(::displayDate) ?: "未填写"}")
                    Text("依据说明：${revision.source.ifBlank { "未填写" }}")
                    Text("生效时间：${displayInstant(revision.effectiveAt, ZoneId.systemDefault().id)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        } }
        item { Inset { Text("每次日期调整会留下记录，已记录产检的孕周依据保持不变。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}
