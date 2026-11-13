package cn.yunji.journal.feature

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunji.journal.core.database.MedicationPlanEntity
import cn.yunji.journal.core.database.MedicationEntity
import cn.yunji.journal.core.designsystem.*
import cn.yunji.journal.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun MedicationListScreen(pregnancyId: String?, model: JournalViewModel, add: () -> Unit, edit: (String) -> Unit, history: (String) -> Unit,catalog: ()->Unit) {
    if (pregnancyId == null) { Inset { Text("请先创建或恢复孕期档案，再记录用药方案。") }; return }
    val plans by remember(pregnancyId, model) { model.medications.plans(pregnancyId) }.collectAsStateWithLifecycle(emptyList())
    val drugs by remember(pregnancyId, model) { model.medications.medications(pregnancyId) }.collectAsStateWithLifecycle(emptyList())
    val busy by model.busy.collectAsStateWithLifecycle()
    var statusChange by remember { mutableStateOf<Pair<String,String>?>(null) }
    LazyColumn(modifier = Modifier.testTag("medication-list"), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Inset { Text("记录医生交代的方案，可在服药记录中确认、跳过或补记。用药提醒按方案时刻生成，受通知权限与系统省电策略影响。", style = MaterialTheme.typography.bodySmall) } }
        item { Inset { JournalButton("新增用药方案", enabled = !busy, onClick = add) } }
        item {Inset {JournalButton("管理药品",secondary=true,onClick=catalog)}}
        if (plans.isEmpty()) item { Inset { JournalCard(soft = true) { Text("还没有用药方案", style = MaterialTheme.typography.titleLarge); Text("药品名称、用量和医嘱都可以在这里留存。") } } }
        items(plans, key = { it.logicalPlanId }, contentType = { "medication-plan" }) { plan ->
            val drug = drugs.firstOrNull { it.id == plan.medicationId }
            Inset { JournalCard {
                Text(plan.medicationName.ifBlank {drug?.name ?: "药品"}, style = MaterialTheme.typography.titleLarge)
                Text(listOf(plan.medicationSpecification,plan.medicationDosageForm).filter {it.isNotBlank()}.joinToString(" · "))
                Text("${medicationStatusLabel(plan.status)} · 第${plan.version}版", color = MaterialTheme.colorScheme.primary)
                PlanSummary(plan)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (plan.status != "ENDED") JournalTextAction("修改方案", enabled = !busy, onClick = { edit(plan.id) })
                    JournalTextAction("历史版本", onClick = { history(plan.logicalPlanId) })
                    if (plan.status != "ENDED") JournalTextAction(if (plan.status == "ACTIVE") "暂停" else "恢复方案",
                        enabled = !busy,
                        onClick = { statusChange = plan.id to if (plan.status == "ACTIVE") "PAUSED" else "ACTIVE" })
                }
                if (plan.status != "ENDED") JournalTextAction("结束方案", enabled = !busy, danger = true, onClick = { statusChange = plan.id to "ENDED" })
            } }
        }
    }
    statusChange?.let { (id, status) ->
        val label = when(status) { "PAUSED" -> "暂停"; "ACTIVE" -> "恢复"; else -> "结束" }
        AlertDialog(onDismissRequest = { statusChange = null }, title = { Text("${label}记录中的方案？") },
            text = { Text(if (status == "ENDED") "将记录为已结束，历史版本保留。需要再次记录时请新增方案；这里只记录状态，不代替医生的用药安排。" else "状态变更会保留为新版本，之前的内容仍可查看；这里只记录状态，不代替医生的用药安排。") },
            confirmButton = { TextButton({ statusChange = null; model.medicationStatus(id, status) }, Modifier.testTag("confirm-medication-status")) { Text("确认$label") } },
            dismissButton = { TextButton({ statusChange = null }) { Text("取消") } })
    }
}

@Composable
internal fun MedicationEditorScreen(form: MedicationForm, model: JournalViewModel, busy: Boolean) {
    val drugs by remember(form.pregnancyId, model) { model.medications.medications(form.pregnancyId) }.collectAsStateWithLifecycle(emptyList())
    var chooseDrug by remember { mutableStateOf(false) }
    var chooseFrequency by remember { mutableStateOf(false) }
    var attempted by rememberSaveable(form.id, form.pregnancyId) { mutableStateOf(false) }
    fun update(value: MedicationForm) { model.updateMedication(value) }
    val error = runCatching { MedicationRules.normalize(form); null }.getOrElse { it.message ?: "请检查填写内容" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("按医嘱记录，不提供药品或剂量建议。返回会保留草稿。", style = MaterialTheme.typography.bodySmall)
        if (form.id == null && drugs.isNotEmpty()) Box {
            JournalSecondaryButton(if (form.medicationId == null) "选择已有药品" else "更换药品", onClick = { chooseDrug = true })
            DropdownMenu(chooseDrug, { chooseDrug = false }) {
                DropdownMenuItem(text = { Text("录入新药品") }, onClick = { chooseDrug = false; update(form.copy(medicationId = null, name = "", specification = "", dosageForm = "",expectedMedicationRevision=null)) })
                drugs.forEach { drug -> DropdownMenuItem(text = { Text(listOf(drug.name, drug.specification).filter { it.isNotBlank() }.joinToString(" · ")) }, onClick = {
                    chooseDrug = false; update(form.copy(medicationId = drug.id, name = drug.name, specification = drug.specification, dosageForm = drug.dosageForm,expectedMedicationRevision=drug.revision))
                }) }
            }
        }
        JournalCard {
            JournalField(form.name, { update(form.copy(name = it.take(100))) }, "药品名称",
                readOnly = form.medicationId != null, modifier = Modifier.testTag("medication-name"))
            JournalField(form.specification, { update(form.copy(specification = it.take(120))) }, "规格（选填）",
                readOnly = form.medicationId != null)
            JournalField(form.dosageForm, { update(form.copy(dosageForm = it.take(40))) }, "剂型（选填）",
                readOnly = form.medicationId != null)
        }
        if (form.medicationId != null) Text("本次方案保留当前药品信息，旧方案与服药依据不会被改写。基础信息可在管理药品中编辑。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JournalField(form.dose, { update(form.copy(dose = it.take(20))) }, "每次剂量（选填）",
                modifier = Modifier.weight(1f).testTag("medication-dose"))
            JournalField(form.unit, { update(form.copy(unit = it.take(20))) }, "单位（选填）",
                modifier = Modifier.weight(1f).testTag("medication-unit"))
        }
        Text("剂量和单位需同时填写，也可同时留空。", style = MaterialTheme.typography.bodySmall)
        Box {
            JournalSecondaryButton(MEDICATION_FREQUENCIES.getValue(form.frequency), onClick = { chooseFrequency = true })
            DropdownMenu(chooseFrequency, { chooseFrequency = false }) { MEDICATION_FREQUENCIES.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { chooseFrequency = false; update(form.copy(frequency = key)) }) } }
        }
        if (form.frequency in setOf("DAILY_TIMES", "WEEKLY_TIMES", "ONCE")) FormField("固定时刻，例如08:00,20:00", form.times, { update(form.copy(times = it)) }, 100)
        else Text("此类方案仅记录内容，不生成固定服药时刻。", style = MaterialTheme.typography.bodySmall)
        if (form.frequency == "WEEKLY_TIMES") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..7).forEach { day ->
                val selected = day.toString() in form.weekdays.split(',')
                JournalTag(selected, { val values = form.weekdays.split(',').filter { it.isNotBlank() }.toMutableSet(); if (selected) values.remove(day.toString()) else values.add(day.toString()); update(form.copy(weekdays = values.sorted().joinToString(","))) }, label = { Text("周${listOf("一","二","三","四","五","六","日")[day-1]}") })
            }
        }
        DateField(if (form.frequency == "ONCE") "用药日期" else "开始日期", form.startDate, { update(form.copy(startDate = it)) }, required = true)
        if (form.frequency != "ONCE") {
            DateField("结束日期（选填）", form.endDate, { update(form.copy(endDate = it)) })
            if (form.endDate.isNotEmpty()) JournalTextAction("清除结束日期", onClick = { update(form.copy(endDate = "")) })
        }
        FormField("医嘱来源（选填）", form.source, { update(form.copy(source = it)) }, 240)
        FormField("医嘱原文与备注", form.instructions, { update(form.copy(instructions = it)) }, 10_000, false)
        Text("录入时区：${form.zoneId} · 用药提醒按本机时区计算", style = MaterialTheme.typography.bodySmall)
        if (attempted && error != null) JournalError(error, Modifier.testTag("medication-validation"))
        JournalButton(if (busy) "正在保存…" else "保存用药方案", enabled = !busy, onClick = { attempted = true; if (error == null) model.saveMedication() })
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun MedicationHistoryScreen(id: String, model: JournalViewModel,versionId: String?=null) {
    val history by remember(id, model) { model.medications.history(id) }.collectAsStateWithLifecycle(emptyList())
    val pregnancyId = history.firstOrNull()?.pregnancyId
    val drugs by remember(pregnancyId, model) { pregnancyId?.let { model.medications.medications(it) } ?: flowOf(emptyList<MedicationEntity>()) }.collectAsStateWithLifecycle(emptyList())
    val drug = drugs.firstOrNull { it.id == history.firstOrNull()?.medicationId }
    val list=rememberLazyListState()
    LaunchedEffect(versionId,history,drug?.id) {
        val index=history.indexOfFirst {it.id==versionId}
        if(versionId!=null && index>=0) list.scrollToItem(index+if(drug!=null)2 else 1)
    }
    LazyColumn(state=list,contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (drug != null) item { Inset { Text("当前药品信息：${listOf(drug.name, drug.specification).filter { it.isNotBlank() }.joinToString(" · ")}", style = MaterialTheme.typography.titleLarge) } }
        item { Inset { Text("每次修改或状态变更单独留存，旧版内容不会被覆盖。下方每版保留当时的药品名称、规格与剂型。", style = MaterialTheme.typography.bodySmall) } }
        items(history, key = { it.id }, contentType = { "plan-version" }) { plan -> Inset { Column(Modifier.testTag("plan-version-${plan.id}")) { JournalCard(soft=plan.id==versionId) {
            Text(plan.medicationName.ifBlank {drug?.name ?: "药品"},style=MaterialTheme.typography.titleMedium)
            if(plan.medicationSpecification.isNotBlank() || plan.medicationDosageForm.isNotBlank())Text(listOf(plan.medicationSpecification,plan.medicationDosageForm).filter {it.isNotBlank()}.joinToString(" · "))
            Text("第${plan.version}版 · ${medicationStatusLabel(plan.status)}", style = MaterialTheme.typography.titleLarge)
            Text("记录生效于 ${displayInstant(plan.effectiveAt, plan.zoneId)}", style = MaterialTheme.typography.bodySmall)
            PlanSummary(plan)
            Text("录入时区：${plan.zoneId}", style = MaterialTheme.typography.bodySmall)
        } } } }
    }
}

@Composable private fun PlanSummary(plan: MedicationPlanEntity) {
    Text(if (plan.dose == null) "剂量未填写" else "每次 ${plan.dose} ${plan.unit}")
    Text(listOfNotNull(MEDICATION_FREQUENCIES[plan.frequencyType], plan.times.takeIf { it.isNotBlank() }?.replace(",", "、"), plan.weekdays.takeIf { it.isNotBlank() }?.split(',')?.joinToString("、") { "周${listOf("一","二","三","四","五","六","日")[it.toInt()-1]}" }).joinToString(" · "))
    Text("${displayDate(plan.startDate)} 至 ${plan.endDate?.let(::displayDate) ?: "未设结束日期"}", style = MaterialTheme.typography.bodySmall)
    if (plan.source.isNotBlank()) Text("来源：${plan.source}")
    if (plan.instructions.isNotBlank()) Text(plan.instructions)
}
