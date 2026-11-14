package cn.yunji.journal.feature

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import cn.yunji.journal.core.designsystem.journalPressable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.designsystem.*
import cn.yunji.journal.data.JournalEntryForm
import cn.yunji.journal.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 方案 A（设计 12-Timeline）：筛选收敛为 全部 / 产检 / 日常 三项。
 * 资料 / 方案变更 / 服药 / 健康事件不再有专属筛选，但仍会在「全部」中以记录行降级展示，
 * 行内点击可打开原始记录；各模块入口（记录枢纽/我的）保持不变。
 */
private val TIMELINE_FILTERS = linkedMapOf("" to "全部", "VISIT" to "产检", "JOURNAL" to "日常")
private val TIMELINE_TYPE_LABELS = mapOf("VISIT" to "产检", "JOURNAL" to "日常", "DOCUMENT" to "资料",
    "PLAN" to "方案变更", "INTAKE" to "服药", "MEASUREMENT" to "健康记录")
private val TIMELINE_ROW_ICONS = mapOf("VISIT" to "ic-calendar-check.svg", "DOCUMENT" to "ic-file-text.svg",
    "MEASUREMENT" to "ic-activity.svg", "PLAN" to "ic-pill.svg", "INTAKE" to "ic-notebook.svg")
internal const val TIMELINE_TAGLINE = "把一次次记录，连成自己的故事"
private data class TimelineLoad(val loading: Boolean=true,val snapshot: TimelineSnapshot?=null,val events: List<TimelineEvent> = emptyList(),val failed: Boolean=false)

@Composable
private fun timelineState(pregnancyId: String,model: JournalViewModel): State<TimelineLoad> {
    val zone=ZoneId.systemDefault()
    return remember(pregnancyId,model,zone) {
        model.journalEntries.timeline(pregnancyId).map { source -> TimelineLoad(false,source,source?.let {TimelineRules.build(it,zone)}.orEmpty()) }
            .catch {emit(TimelineLoad(loading=false,failed=true))}.flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(TimelineLoad())
}

@Composable
internal fun RecordsHub(title: String?,canCreate: Boolean,timeline: ()->Unit,journal: ()->Unit,medications: ()->Unit,intakes: ()->Unit,health: ()->Unit,documents: ()->Unit,newJournal: ()->Unit) {
    var category by rememberSaveable { mutableStateOf("全部") }
    var query by rememberSaveable { mutableStateOf("") }
    val destinations = listOf(
        Triple("日常记录", "症状、心情与日常备注", "日常") to journal,
        Triple("孕期时间线", "按发生日期查看全部记录", "全部") to timeline,
        Triple("健康指标", "测量记录与分组趋势", "健康") to health,
        Triple("全部资料", "查看报告日期与原件", "全部") to documents,
        Triple("用药方案", "药品、方案与历史版本", "用药") to medications,
        Triple("服药记录", "按日计划、确认与补记", "用药") to intakes,
    )
    val icons = mapOf("孕期时间线" to "5fbe7.svg", "日常记录" to "ic-notebook.svg", "全部资料" to "ic-file-text.svg",
        "用药方案" to "ic-pill.svg", "服药记录" to "ic-notebook.svg", "健康指标" to "ic-activity.svg")
    val visible = destinations.filter { (entry, _) ->
        (category == "全部" || entry.third == category) &&
            (query.isBlank() || entry.first.contains(query.trim(), ignoreCase = true) || entry.second.contains(query.trim(), ignoreCase = true))
    }
    LazyColumn(modifier=Modifier.testTag("records-hub-list"),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {PageHeader("记录",title ?: "创建孕期后，记录会按档案分别整理")}
        item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=24.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("全部", "日常", "用药", "健康").forEach { name -> JournalTag(category == name, { category = name }, { Text(name) }) }
        } }
        item { Inset { OutlinedTextField(query, { query = it }, modifier=Modifier.fillMaxWidth().testTag("records-search"),
            placeholder={Text("搜索记录分类")}, singleLine=true, shape=RoundedCornerShape(16.dp)) } }
        item { Inset { Text("记录分类", style=MaterialTheme.typography.titleMedium) } }
        if (visible.isEmpty()) item { Inset { JournalEmptyState("没有找到相关分类", "试试其他关键词或切换分类") } }
        items(visible.take(4), key={it.first.first}) { (entry, open) -> Inset {
            RecordRow(entry.first, entry.second, icons.getValue(entry.first), open)
        } }
        if (query.isBlank() && category == "全部") item { Inset { JournalButton("写一条日常备注",enabled=canCreate,onClick=newJournal) } }
        if (visible.size > 4) {
            item { Inset { Text("更多分类", style=MaterialTheme.typography.titleMedium) } }
            items(visible.drop(4), key={it.first.first}) { (entry, open) -> Inset {
                RecordRow(entry.first, entry.second, icons.getValue(entry.first), open)
            } }
        }
    }
}

/**
 * 设计 12-Timeline 主体：3 个筛选 + 按日期分组（右侧孕周）+ 72 dp 记录行/杏色日常备注卡 + 底部主 CTA。
 * 头部（28 sp 标题/副标题）由 [JournalPage] 提供；此处不再放搜索框与说明段。
 */
@Composable
internal fun TimelineScreen(pregnancy: PregnancyEntity?,initialType: String,model: JournalViewModel,add: ()->Unit,open: (TimelineEvent)->Unit) {
    if(pregnancy==null) {Inset {Text("请先创建或恢复孕期档案。")};return}
    val state by timelineState(pregnancy.id,model)
    var type by rememberSaveable(pregnancy.id,initialType){mutableStateOf(initialType)}
    val list=rememberLazyListState()
    val events=remember(state.events,type){state.events.filter {type.isEmpty() || it.type==type}}
    val entries=remember(state.snapshot){state.snapshot?.entries.orEmpty().associateBy {it.id}}
    Column(Modifier.fillMaxSize().testTag("timeline-screen")) {
        LazyColumn(state=list,modifier=Modifier.weight(1f).testTag("timeline-list"),
            contentPadding=PaddingValues(horizontal=24.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item(key="filters") {Row(Modifier.fillMaxWidth().testTag("timeline-filters"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TIMELINE_FILTERS.forEach {(key,label)->JournalTag(selected=type==key,onClick={type=key},label={Text(label)},modifier=Modifier.testTag("timeline-filter-$key"))}
            }}
            if(state.loading) item {JournalLoading()}
            else if(state.failed) item {Inset { JournalError("时间线暂时无法读取，请返回后重试。原记录未改变。") }}
            else if(state.snapshot==null) item {Text("所属孕期已删除或不可用。")}
            else if(events.isEmpty()) item {JournalEmptyState("还没有这类记录","可写一条日常记录，或从产检、用药和健康指标录入。")}
            events.groupBy {it.date}.forEach {(date,rows)->
                item(key="day-$date",contentType="day") {DateGroupHeader(date,gestationalLabel(pregnancy,date))}
                items(rows,key={it.key},contentType={it.type}) {event ->
                    TimelineEntry(event=event,journalEntry=entries[event.id],open={open(event)})
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp)) {
            JournalButton("写一条日常备注",modifier=Modifier.testTag("timeline-add"),onClick=add)
        }
    }
}

/** 日期组头：左侧「M月d日」，右侧该日期的孕周（按当前孕期预产期/末次月经推算）。 */
@Composable
private fun DateGroupHeader(date: String, gestational: String?) {
    val label=displayDate(date)
    Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text(label,style=MaterialTheme.typography.titleLarge)
        // 设计 12-Timeline：右侧孕周与日期同处一行，颜色为 primary #356B53（像素取色实测）。
        if(gestational!=null) Text(gestational,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
    }
}

/** 按事件日期与当前孕期推算孕周：优先使用医生确认预产期（EDD），否则 LMP+280 天；无日期依据时返回 null。 */
private fun gestationalLabel(pregnancy: PregnancyEntity,date: String): String? {
    val day=runCatching {LocalDate.parse(date)}.getOrNull()?:return null
    val edd=pregnancy.confirmedEdd?.takeIf {it.isNotBlank()}?.let {runCatching {LocalDate.parse(it)}.getOrNull()}
        ?:pregnancy.lmpDate?.takeIf {it.isNotBlank()}?.let {runCatching {LocalDate.parse(it).plusDays(280)}.getOrNull()}
        ?:return null
    val gestationDays=280L-ChronoUnit.DAYS.between(day,edd)
    if(gestationDays<0) return null
    val weeks=gestationDays/7
    val days=gestationDays%7
    return if(days==0L) "孕${weeks}周" else "孕${weeks}周 + ${days}天"
}

@Composable
private fun TimelineEntry(event: TimelineEvent,journalEntry: JournalEntryEntity?,open: ()->Unit) {
    Column(Modifier.fillMaxWidth().testTag("timeline-${event.key}")) {
        if(event.type=="JOURNAL") JournalNoteCard(event,journalEntry,open)
        else TimelineRecordRow(title=timelineTitle(event),subtitle=timelineSubtitle(event),
            icon=TIMELINE_ROW_ICONS[event.type]?:"ic-info.svg",onClick=open,modifier=Modifier.testTag("source-${event.key}"))
    }
}

/** 设计记录行：白卡、圆角 20、min 72 dp、44 dp soft 图标容器、16 sp 标题 + 12 sp muted 副标题 + chevron。 */
@Composable
internal fun TimelineRecordRow(title: String,subtitle: String,icon: String,onClick: ()->Unit,modifier: Modifier=Modifier) {
    // 与 RecordRow 同款：Surface 不带 onClick，改由 clickable 提供动作 + onClickLabel，
    // 否则 TalkBack 只念标题、不说明会到哪里。（semantics{onClick(action=null)} 会覆盖原点击，勿用。）
    val interaction = remember { MutableInteractionSource() }
    Surface(modifier=modifier.fillMaxWidth()
            .journalPressable(interaction)
            .clickable(interactionSource=interaction, indication=null, onClickLabel=title, role=Role.Button, onClick=onClick),
        shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min=72.dp).padding(horizontal=16.dp,vertical=12.dp),
            horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(44.dp),contentAlignment=Alignment.Center) {DesignIcon(icon,size=24.dp)}
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            DesignIcon("cfd63.svg",size=20.dp)
        }
    }
}

/**
 * 设计杏色日常备注卡（JOURNAL only）：浅色 Apricot #FAEAD8 / Amber #80582C，
 * 深色改走 [LocalNoteCardColors] 的暗色对（#3A2E1F / #F0D5B4），避免深色主题下出现近白亮斑。
 */
@Composable
private fun JournalNoteCard(event: TimelineEvent,entry: JournalEntryEntity?,open: ()->Unit) {
    val lines=entry?.text.orEmpty().trim().lines().map {it.trim()}.filter {it.isNotBlank()}
    val title=lines.firstOrNull()?.take(80) ?: entry?.symptom?.takeIf {it.isNotBlank()}?.take(80) ?: "日常备注"
    val body=when {
        lines.size>1 -> lines.drop(1).joinToString("\n")
        !entry?.symptom.isNullOrBlank() && entry?.symptom!=title -> entry!!.symptom
        !entry?.mood.isNullOrBlank() -> entry!!.mood
        else -> ""
    }
    val note = LocalNoteCardColors.current
    val interaction = remember { MutableInteractionSource() }
    Surface(modifier=Modifier.fillMaxWidth().testTag("source-${event.key}")
            .journalPressable(interaction)
            .clickable(interactionSource=interaction, indication=null, onClickLabel="打开日常备注", role=Role.Button, onClick=open),
        shape=RoundedCornerShape(16.dp),color=note.container) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium,color=note.content,maxLines=3,overflow=TextOverflow.Ellipsis)
            if(body.isNotBlank()) Text(body,style=MaterialTheme.typography.bodyMedium,color=note.content,maxLines=3,overflow=TextOverflow.Ellipsis)
            Text("日常备注 · ${event.time ?: "只有日期，没有具体时间"}",style=MaterialTheme.typography.bodySmall,color=note.content)
        }
    }
}

private fun timelineTitle(event: TimelineEvent): String = when(event.type) {
    "MEASUREMENT" -> {
        val value=event.summary.substringBefore(" · ").trim()
        if(value.isBlank() || value==event.title) "记录${event.title}" else "记录${event.title} · $value"
    }
    "VISIT" -> when(event.title) {"已就诊"->"完成一次产检";"已取消预约"->"取消一次产检";"产检预约"->"预约一次产检";else->event.title}
    else -> event.title
}

private fun timelineSubtitle(event: TimelineEvent): String = when(event.type) {
    "VISIT" -> event.summary.ifBlank {"产检记录"}
    "DOCUMENT" -> listOf(event.summary,
        if(event.parentId!=null && !event.summary.contains("已关联本次产检"))"已关联本次产检" else "").filter {it.isNotBlank()}
        .joinToString(" · ").ifBlank {"资料"}
    "MEASUREMENT","PLAN","INTAKE" -> listOfNotNull(event.time,TIMELINE_TYPE_LABELS[event.type]).joinToString(" · ")
    else -> listOfNotNull(event.time,event.summary.takeIf {it.isNotBlank()}).joinToString(" · ")
        .ifBlank {TIMELINE_TYPE_LABELS[event.type].orEmpty()}
}

/**
 * 「全部资料」：方案 A 下资料类型不再进入 Timeline 筛选，记录枢纽的「全部资料」入口改为独立资料列表页，
 * 复用 TimeLineSnapshot 的 DOCUMENT 事件（跨产检/药品包装照片），点击进入原始记录页查看/编辑元数据与原件。
 */
@Composable
internal fun AllDocumentsScreen(pregnancyId: String?,model: JournalViewModel,open: (String)->Unit) {
    if(pregnancyId==null) {Inset {Text("请先创建或恢复孕期档案。")};return}
    val state by timelineState(pregnancyId,model)
    val documents=remember(state.events){state.events.filter {it.type=="DOCUMENT"}}
    LazyColumn(Modifier.fillMaxSize().testTag("all-documents-list"),
        contentPadding=PaddingValues(horizontal=24.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(state.loading) item {JournalLoading()}
        else if(state.failed) item {Inset { JournalError("资料列表暂时无法读取，请返回后重试。") }}
        else if(documents.isEmpty()) item {JournalEmptyState("还没有产检资料","把这次产检的报告放在一起，之后更容易找到。")}
        else documents.groupBy {it.date}.forEach {(date,rows)->
            item(key="day-$date",contentType="day") {DateGroupHeader(date,null)}
            items(rows,key={it.key},contentType={it.type}) {event ->
                TimelineRecordRow(title=event.title,subtitle=timelineSubtitle(event),icon="ic-info.svg",
                    onClick={open(event.id)},modifier=Modifier.testTag("source-${event.key}"))
            }
        }
    }
}

@Composable
internal fun JournalEntryEditorScreen(form: JournalEntryForm,model: JournalViewModel,busy: Boolean) {
    fun update(value: JournalEntryForm)=model.updateJournalEntry(value)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        JournalCard {
            DateField("发生日期",form.date,{update(form.copy(date=it))},required=true,maxDate=LocalDate.now(ZoneId.of(form.zoneId)),enabled=!busy)
            JournalField(form.time,{update(form.copy(time=it))}, "发生时间（选填，24小时制）", enabled=!busy,
                modifier=Modifier.testTag("journal-time"))
            if(form.time.isNotBlank()) JournalTextAction("设为时间未记录", enabled=!busy, onClick={update(form.copy(time="",offsetSeconds=null))})
            Text("记录时区：${form.zoneId}",style=MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val offsets=runCatching {ZoneId.of(form.zoneId).rules.getValidOffsets(LocalDate.parse(form.date).atTime(LocalTime.parse(form.time)))}.getOrDefault(emptyList())
            if(offsets.size>1) Column {Text("该时刻出现两次，请选择实际时段。");offsets.forEachIndexed {index,offset->JournalTag((offsets.firstOrNull {it.totalSeconds==form.offsetSeconds} ?: offsets.first())==offset,{update(form.copy(offsetSeconds=offset.totalSeconds))},{Text("第${index+1}次（UTC$offset）")})}}
        }
        JournalCard {
            JournalField(form.symptom,{update(form.copy(symptom=it))}, "症状（选填）",
                modifier=Modifier.testTag("journal-symptom"), isError=form.symptom.length>1000, enabled=!busy)
            JournalField(form.mood,{update(form.copy(mood=it))}, "心情（选填）",
                modifier=Modifier.testTag("journal-mood"), isError=form.mood.length>40, enabled=!busy)
            JournalField(form.text,{update(form.copy(text=it))}, "日常备注（选填）", singleLine=false, minLines=3,
                modifier=Modifier.testTag("journal-text"), isError=form.text.length>10_000, enabled=!busy)
        }
        JournalSectionTitle("保存前请确认")
        RecordRow("症状、心情、备注至少填一项", "这些内容仅供个人整理", "cdc29.svg")
        JournalButton("保存日常记录",enabled=!busy,onClick=model::saveJournalEntry)
        Text("日期可调整，发生时间可以留空；返回会保留草稿。",style=MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun TimelineSourceScreen(pregnancyId: String,type: String,id: String,model: JournalViewModel,edit: ()->Unit) {
    val state by timelineState(pregnancyId,model)
    val source=state.snapshot
    val event=state.events.firstOrNull {it.type==type && it.id==id}
    var deleting by remember {mutableStateOf(false)}
    var originalExpanded by rememberSaveable(id) { mutableStateOf(false) }
    var preview by rememberSaveable(id){mutableStateOf(false)}
    var editingDocument by rememberSaveable(id){mutableStateOf(false)}
    val document=(source?.visits?.flatMap {it.documents}.orEmpty()+source?.medicationFiles?.flatMap {it.documents}.orEmpty()).firstOrNull {it.id==id && it.deletedAt==null}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).testTag("source-detail"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        when {
            state.loading -> JournalLoading()
            state.failed -> JournalError("无法读取原记录，请返回后重试。")
            event==null || source==null -> Text("记录已删除或不可用，可返回时间线或在回收站核对。")
            else -> {
                val journal = if(type=="JOURNAL") source.entries.firstOrNull {it.id==id} else null
                JournalCard(soft = true) {
                    if(type=="JOURNAL") Text("日常",style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.primary)
                    Text(if(type=="JOURNAL") "日常记录" else event.title,style=MaterialTheme.typography.titleLarge)
                    Text("${displayDate(event.date)} · ${event.time ?: "时间未记录"}")
                    Text(event.basis,style=MaterialTheme.typography.bodySmall)
                }
                if(type=="JOURNAL") {
                    if(journal!=null && (journal.symptom.isNotBlank() || journal.mood.isNotBlank())) {
                        Text("症状与心情",style=MaterialTheme.typography.titleMedium)
                        RecordRow(listOf(journal.symptom,journal.mood).filter {it.isNotBlank()}.joinToString(" · "),
                            "按发生日期归档", "ic-clock.svg")
                    }
                    journal?.text?.takeIf {it.isNotBlank()}?.let { note ->
                        Text("日常备注",style=MaterialTheme.typography.titleMedium)
                        Text(note,style=MaterialTheme.typography.bodyMedium)
                    }
                    Text("记录操作",style=MaterialTheme.typography.titleMedium)
                    RecordRow("查看完整原始内容", "包含记录时间与来源", "ic-file-text.svg") {
                        originalExpanded = !originalExpanded
                    }
                    if(originalExpanded && journal!=null) JournalCard {
                        if(journal.symptom.isNotBlank())Text("症状：${journal.symptom}")
                        if(journal.mood.isNotBlank())Text("心情：${journal.mood}")
                        if(journal.text.isNotBlank())Text("备注：${journal.text}")
                        Text("录入时间：${displayInstant(journal.createdAt,journal.zoneId)}",style=MaterialTheme.typography.bodySmall)
                    }
                } else {
                JournalCard {
                when(type) {
                    "MEASUREMENT" -> source.measurements.firstOrNull {it.measurement.id==id}?.let {group ->
                        val row=group.measurement;val def=source.definitions.firstOrNull {it.id==row.definitionId}
                        group.values.forEach {Text("${measurementKeyLabel(it.valueKey)}：${it.decimalValue} ${if(it.valueKey=="pulse")"次/分" else def?.unit.orEmpty()}")}
                        Text(when(row.scenario){"FASTING"->"空腹";"POST_MEAL"->"餐后${row.postMealMinutes}分钟";"OTHER"->"其他场景";else->"普通测量"})
                        Text("来源：${row.source.ifBlank {"未填写"}} · 原指标第${def?.version ?: 1}版")
                        if(row.note.isNotBlank())Text(row.note)
                        Text("录入时间：${displayInstant(row.recordedAt,row.zoneId)}",style=MaterialTheme.typography.bodySmall)
                    }
                    "INTAKE" -> source.intakes.firstOrNull {it.id==id}?.let {row ->
                        Text(if(row.status=="TAKEN") {if(row.dose==null)"实际剂量未填写" else "实际用量 ${row.dose} ${row.unit.orEmpty()}"} else "此项记录为跳过，未记录服药")
                        source.plans.firstOrNull {it.id==row.planVersionId}?.let {plan ->
                            Text("原方案第${plan.version}版 · 计划每次 ${plan.dose ?: "未填写"} ${plan.unit.orEmpty()}")
                            if(plan.instructions.isNotBlank())Text("原医嘱：${plan.instructions}")
                            if(plan.source.isNotBlank())Text("医嘱来源：${plan.source}")
                        }
                        val point=source.occurrences.firstOrNull {it.id==row.sourceOccurrenceId}
                        Text(point?.let {"原计划 ${displayDate(it.plannedDate)} ${it.localTime}"} ?: "临时补记，无计划服药点")
                        if(row.note.isNotBlank())Text(row.note)
                        Text("录入时间：${displayInstant(row.recordedAt,row.zoneId)}",style=MaterialTheme.typography.bodySmall)
                    }
                    "DOCUMENT" -> document?.let {doc ->
                        Text("分类：${cn.yunji.journal.data.DOCUMENT_CATEGORIES[doc.category] ?: "其他"}")
                        Text("报告日期：${doc.documentDate?.let(::displayDate) ?: "未填写"}")
                        Text("导入时间：${displayInstant(doc.createdAt,ZoneId.systemDefault().id)}")
                        if(doc.hospital.isNotBlank())Text("医院：${doc.hospital}")
                        if(doc.note.isNotBlank())Text(doc.note)
                        JournalButton("查看原件",onClick={preview=true})
                        JournalTextAction("编辑资料信息", onClick = { editingDocument = true })
                    }
                }
                }
                }
                if(type!="DOCUMENT")JournalButton("修改原始记录",onClick={originalExpanded=false;edit()})
                JournalDangerButton("删除这条记录",Modifier.testTag("source-delete"),onClick={deleting=true})
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if(preview && document!=null && event!=null)DocumentPreviewDialog(document,model,{preview=false},{preview=false;deleting=true})
    if(editingDocument && document!=null && event!=null)DocumentMetadataDialog(document,{editingDocument=false},medication=document.medicationId!=null) {name,category,date,hospital,note,revision ->model.documents.updateMetadata(id,name,category,date,hospital,note,revision)}
    if(deleting && event!=null)AlertDialog(onDismissRequest={deleting=false},title={Text("删除这条记录？")},text={Text("移入全局回收站，可在清理前恢复；不删除手机原件。")},confirmButton={TextButton({deleting=false;if(type=="DOCUMENT")model.removeDocument(id) else model.deleteRoot(when(type){"JOURNAL"->"JOURNAL";"MEASUREMENT"->"MEASUREMENT";else->"INTAKE"},id)},Modifier.testTag("confirm-source-delete")){Text("删除")}},dismissButton={TextButton({deleting=false}){Text("取消")}})
}



