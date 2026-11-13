package cn.yunji.journal.feature

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.designsystem.*
import cn.yunji.journal.data.MeasurementForm
import cn.yunji.journal.domain.MeasurementRules
import cn.yunji.journal.domain.PregnancyRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

internal fun measurementKeyLabel(key: String) = when(key) { "systolic" -> "收缩压"; "diastolic" -> "舒张压"; "pulse" -> "心率（可选）"; else -> "测量值" }
private fun scenarioLabel(row: MeasurementEntity) = when(row.scenario) { "FASTING" -> "空腹"; "POST_MEAL" -> "餐后${row.postMealMinutes}分钟"; "OTHER" -> "其他场景"; else -> "" }
private fun measuredLabel(row: MeasurementEntity) = displayInstant(row.measuredAt, row.zoneId)

@Composable
internal fun MeasurementScreen(pregnancy: PregnancyEntity?, model: JournalViewModel, edit: (String?) -> Unit) {
    if (pregnancy == null) { Inset { Text("请先创建或恢复孕期档案。") }; return }
    val id = pregnancy.id
    val definitions by remember(id,model) { model.measurements.definitions(id) }.collectAsStateWithLifecycle(emptyList())
    val records by remember(id,model) { model.measurements.records(id) }.collectAsStateWithLifecycle(emptyList())
    val values by remember(id,model) { model.measurements.values(id) }.collectAsStateWithLifecycle(emptyList())
    val busy by model.busy.collectAsStateWithLifecycle()
    var loading by remember(id) { mutableStateOf(true) }
    var failure by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { try { model.measurements.ensureBuiltIns(id) } catch(e: CancellationException) { throw e } catch(_: Exception) { failure="暂时无法读取指标，请返回后重试" } finally { loading=false } }
    var trends by rememberSaveable(id) { mutableStateOf(false) }
    var days by rememberSaveable(id) { mutableIntStateOf(30) }
    var gestational by rememberSaveable(id) { mutableStateOf(false) }
    var selectedGroup by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var selected by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var definitionEditorId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val definitionEditor = definitions.firstOrNull {it.id==definitionEditorId}
    var newDefinition by rememberSaveable(id) { mutableStateOf(false) }
    val filtered = records.filter { days == 0 || it.measuredAt >= Instant.now().minusSeconds(days * 86400L).toEpochMilli() }
    val groups = remember(definitions,filtered,values) { MeasurementRules.groups(definitions,filtered,values).entries.toList() }
    fun groupId(g: MeasurementRules.Group) = listOf(g.logicalId,g.unit,g.scenario,g.minutes,g.key).joinToString("|")
    val group = groups.firstOrNull { groupId(it.key) == selectedGroup } ?: groups.firstOrNull()
    val latestDefinitions = definitions.groupBy { it.logicalId }.values.map { rows -> rows.maxBy { it.version } }
    LazyColumn(Modifier.testTag("measurement-list"), contentPadding=PaddingValues(bottom=32.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Inset { JournalButton("记录测量", enabled=!busy && !loading && failure==null, onClick={ edit(null) }) } }
        item { Inset { Column { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            JournalTag(!trends,{trends=false},{Text("原始记录")}); JournalTag(trends,{trends=true},{Text("分组趋势")})
        }
            TextButton({newDefinition=true}) { Text("自定义指标") }
        } } }
        if (loading) item { Inset { CircularProgressIndicator() } }
        failure?.let { message -> item { Inset { Text(message, color=MaterialTheme.colorScheme.error) } } }
        if (!trends) {
            if (records.isEmpty() && !loading) item { Inset { JournalCard(soft=true) { Text("还没有测量记录"); Text("体重、血压、血糖及自定义指标均可记录。") } } }
            items(records,key={it.id},contentType={"measurement"}) { row ->
                val def = definitions.firstOrNull { it.id==row.definitionId }
                Inset { JournalCard {
                    Text(def?.name ?: "指标",style=MaterialTheme.typography.titleLarge)
                    Text(values.filter { it.measurementId==row.id }.joinToString(" · ") { "${measurementKeyLabel(it.valueKey)} ${it.decimalValue} ${if(it.valueKey=="pulse") "次/分" else def?.unit.orEmpty()}" })
                    Text(listOf(measuredLabel(row),scenarioLabel(row)).filter { it.isNotBlank() }.joinToString(" · "))
                    Row { TextButton({selected=row.id}) {Text("详情")}; TextButton({edit(row.id)}){Text("修改")}; TextButton({deleting=row.id}){Text("删除")} }
                } }
            }
            items(latestDefinitions.filter {it.kind=="CUSTOM"},key={"definition-${it.id}"},contentType={"definition"}) { def -> Inset { TextButton({definitionEditorId=def.id}) {Text("管理指标：${def.name} · ${def.unit}（第${def.version}版）")} } }
        } else {
            item { Inset { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(7,30,0).forEach { range -> JournalTag(days==range,{days=range},{Text(if(range==0) "全部" else "近${range}天")}) } } } }
            item { Inset { Text("按指标、单位、测量场景及餐后分钟分别呈现。点之间不补零、不推断诊断。", style=MaterialTheme.typography.bodySmall) } }
            if(groups.isEmpty()) item { Inset {Text("此范围没有测量数据。") } }
            item { Inset { Column { groups.forEach { (key,points) -> JournalTag(group?.key==key,{selectedGroup=groupId(key)},{Text("${points.last().definition.name} · ${if(key.key=="pulse") "次/分" else key.unit} · ${measurementKeyLabel(key.key)} ${scenarioLabel(points.last().measurement)}")}) } } } }
            group?.let { entry ->
                val points=entry.value
                item { Inset { JournalCard {
                    Text("${points.size}条原始读数",style=MaterialTheme.typography.titleLarge)
                    MeasurementPlot(points){selected=it}
                    JournalTag(gestational,{gestational=!gestational},{Text(if(gestational) "按当前孕期依据显示孕周" else "按日期显示")})
                    Text("最小 ${points.minOf { it.value.toBigDecimal() }.toPlainString()} · 最大 ${points.maxOf { it.value.toBigDecimal() }.toPlainString()} ${if(entry.key.key=="pulse") "次/分" else entry.key.unit}")
                    Text("图上展示最近最多120条，下面保留范围内全部读数；同一时刻多次测量均保留。",style=MaterialTheme.typography.bodySmall)
                } } }
                items(points.asReversed(),key={"point-${it.measurement.id}"},contentType={"trend-value"}) { point ->
                    val date=Instant.ofEpochMilli(point.measurement.measuredAt).atZone(ZoneId.of(point.measurement.zoneId)).toLocalDate()
                    val age=if(gestational) PregnancyRules.age(date,pregnancy.lmpDate?.let(LocalDate::parse),pregnancy.confirmedEdd?.let(LocalDate::parse))?.label() ?: "孕周未知" else ""
                    Inset { TextButton({selected=point.measurement.id}) {Text("${measuredLabel(point.measurement)} $age · ${point.value}")} }
                }
            }
        }
    }
    if(newDefinition || definitionEditor!=null) DefinitionDialog(id,definitionEditor,model,{newDefinition=false;definitionEditorId=null})
    selected?.let { recordId -> records.firstOrNull {it.id==recordId}?.let { row ->
        val def=definitions.firstOrNull {it.id==row.definitionId}
        AlertDialog(onDismissRequest={selected=null},title={Text(def?.name ?: "测量详情")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(measuredLabel(row)); Text("时区：${row.zoneId}"); if(scenarioLabel(row).isNotBlank()) Text(scenarioLabel(row))
            values.filter {it.measurementId==row.id}.forEach {Text("${measurementKeyLabel(it.valueKey)}：${it.decimalValue} ${if(it.valueKey=="pulse") "次/分" else def?.unit.orEmpty()}")}
            Text("原指标第${def?.version ?: 1}版 · 来源：${row.source.ifBlank {"未填写"}}")
            if(row.note.isNotBlank()) Text(row.note)
            Text("录入时间：${displayInstant(row.recordedAt, row.zoneId)}")
        }},confirmButton={TextButton({selected=null}){Text("关闭")}})
    } }
    deleting?.let { recordId -> AlertDialog(onDismissRequest={deleting=null},title={Text("删除这条测量？")},text={Text("移入全局回收站，可恢复；30天后安排清理。")},confirmButton={TextButton({deleting=null;model.deleteRoot("MEASUREMENT",recordId)},Modifier.testTag("confirm-measurement-delete")){Text("删除")}},dismissButton={TextButton({deleting=null}){Text("取消")}}) }
}

@Composable
private fun MeasurementPlot(all: List<MeasurementRules.Point>, select: (String)->Unit) {
    val points=all.takeLast(120)
    val color=MaterialTheme.colorScheme.primary
    val axis=MaterialTheme.colorScheme.outlineVariant
    // Plot coordinates may use doubles, while persisted/displayed values stay exact decimal strings.
    val min=points.minOf {it.value.toDouble()}; val max=points.maxOf {it.value.toDouble()}
    val start=points.first().measurement.measuredAt; val end=points.last().measurement.measuredAt
    fun location(index: Int,width: Float,height: Float): Offset {
        val x=if(end==start) .5 else (points[index].measurement.measuredAt-start).toDouble()/(end-start)
        val y=if(max==min) .5 else (points[index].value.toDouble()-min)/(max-min)
        return Offset(16f+(width-32f)*x.toFloat(),16f+(height-32f)*(1-y.toFloat()))
    }
    Canvas(Modifier.fillMaxWidth().height(160.dp).semantics {contentDescription="测量散点趋势，详细数值见下方列表"}.pointerInput(points){detectTapGestures {tap -> points.indices.minByOrNull { (location(it,size.width.toFloat(),size.height.toFloat())-tap).getDistance() }?.let {select(points[it].measurement.id)} }}) {
        drawLine(axis,Offset(16f,size.height-16f),Offset(size.width-16f,size.height-16f))
        points.indices.forEach {drawCircle(color,5.dp.toPx(),location(it,size.width,size.height))}
    }
    Text("${measuredLabel(points.first().measurement)} 至 ${measuredLabel(points.last().measurement)}",style=MaterialTheme.typography.bodySmall)
}

@Composable
private fun DefinitionDialog(pregnancyId: String, previous: MetricDefinitionEntity?, model: JournalViewModel, close: ()->Unit) {
    var name by rememberSaveable(previous?.id){mutableStateOf(previous?.name.orEmpty())}
    var unit by rememberSaveable(previous?.id){mutableStateOf(previous?.unit.orEmpty())}
    var saving by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!saving)close()},title={Text(if(previous==null) "自定义指标" else "更新指标定义")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name,{name=it},label={Text("指标名称")},singleLine=true)
        OutlinedTextField(unit,{unit=it},label={Text("单位")},singleLine=true)
        Text("自定义指标使用一个数值字段。修改会保留旧版本及历史单位。",style=MaterialTheme.typography.bodySmall)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton({saving=true; scope.launch {try {model.measurements.saveDefinition(pregnancyId,name,unit,previous?.id);close()}catch(e:CancellationException){throw e}catch(e:IllegalArgumentException){error=e.message ?: "请核对指标"}catch(_:Exception){error="保存失败，原指标已保留"}finally{saving=false}}},enabled=!saving){Text("保存指标")}},dismissButton={TextButton(close,enabled=!saving){Text("取消")}})
}

@Composable
internal fun MeasurementEditorScreen(form: MeasurementForm, model: JournalViewModel, busy: Boolean) {
    val definitions by remember(form.pregnancyId,model){model.measurements.definitions(form.pregnancyId)}.collectAsStateWithLifecycle(emptyList())
    val available=definitions.groupBy {it.logicalId}.values.map {it.maxBy {d->d.version}}
    val def=definitions.firstOrNull {it.id==form.definitionId}
    var expanded by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(form.id==null) Box {
            OutlinedButton({expanded=true},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),
                shape=androidx.compose.foundation.shape.RoundedCornerShape(4.dp)){Text("指标：${def?.name ?: "选择指标"} · ${def?.unit.orEmpty()}")}
            DropdownMenu(expanded,{expanded=false}) { available.forEach { item -> DropdownMenuItem({Text("${item.name} · ${item.unit}")},{expanded=false;model.updateMeasurement(form.copy(definitionId=item.id,values=emptyMap()))}) } }
        } else Text("${def?.name.orEmpty()} · ${def?.unit.orEmpty()} · 原指标第${def?.version ?: 1}版")
        def?.valueKeys?.split(",")?.forEach {key ->
            OutlinedTextField(form.values[key].orEmpty(),{model.updateMeasurement(form.copy(values=form.values+(key to it)))},label={Text("${measurementKeyLabel(key)}（${if(key=="pulse") "次/分" else def.unit}）")},modifier=Modifier.fillMaxWidth().testTag("measurement-$key"),singleLine=true,enabled=!busy)
        }
        if(def?.kind=="GLUCOSE") {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("FASTING" to "空腹","POST_MEAL" to "餐后","OTHER" to "其他").forEach {(value,label)->JournalTag(form.scenario==value,{model.updateMeasurement(form.copy(scenario=value))},{Text(label)},enabled=!busy)} }
            if(form.scenario=="POST_MEAL") OutlinedTextField(form.minutes,{model.updateMeasurement(form.copy(minutes=it))},label={Text("餐后分钟数")},singleLine=true,enabled=!busy)
        }
        DateField("测量日期",form.date,{model.updateMeasurement(form.copy(date=it))},required=true,maxDate=LocalDate.now())
        OutlinedTextField(form.time,{model.updateMeasurement(form.copy(time=it))},label={Text("测量时间（时:分:秒）")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
        Text("测量时区：${if(form.zoneId==ZoneId.systemDefault().id)"本机当前时区" else form.zoneId}",style=MaterialTheme.typography.bodySmall)
        val offsets=runCatching {ZoneId.of(form.zoneId).rules.getValidOffsets(LocalDate.parse(form.date).atTime(LocalTime.parse(form.time)))}.getOrDefault(emptyList())
        if(offsets.size>1) Column {Text("该时刻重复出现，请选择："); offsets.forEachIndexed { index,offset -> JournalTag(offset.totalSeconds==(form.offsetSeconds?.takeIf {seconds->offsets.any {it.totalSeconds==seconds}} ?: offsets.first().totalSeconds),{model.updateMeasurement(form.copy(offsetSeconds=offset.totalSeconds))},{Text("第${index+1}次（UTC$offset）")})} }
        OutlinedTextField(form.source,{model.updateMeasurement(form.copy(source=it))},label={Text("来源（可选）")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
        Text("补充说明",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(form.note,{model.updateMeasurement(form.copy(note=it))},label={Text("备注（可选）")},modifier=Modifier.fillMaxWidth(),minLines=3,enabled=!busy)
        JournalButton("保存测量",enabled=!busy && def!=null,onClick=model::saveMeasurement)
        Text("保留原始读数，记录仅供自行整理。",style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(32.dp))
    }
}
