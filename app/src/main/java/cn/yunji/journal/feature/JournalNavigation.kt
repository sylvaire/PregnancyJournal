@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.yunji.journal.feature

import androidx.activity.BackEventCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import cn.yunji.journal.BuildConfig
import cn.yunji.journal.core.designsystem.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object TodayRoute : NavKey
@Serializable data object VisitsRoute : NavKey
@Serializable data object ProfilesRoute : NavKey
@Serializable data object RecordsRoute : NavKey
@Serializable data class TimelineRoute(val type: String = "") : NavKey
@Serializable data class TimelineSourceRoute(val pregnancyId: String,val type: String,val id: String) : NavKey
@Serializable data class JournalEntryEditorRoute(val pregnancyId: String,val id: String? = null) : NavKey
@Serializable data object PregnancyManagerRoute : NavKey
@Serializable data object TrashRoute : NavKey
@Serializable data object MedicationsRoute : NavKey
@Serializable data object DrugCatalogRoute : NavKey
@Serializable data class DrugEditorRoute(val pregnancyId: String,val id: String?=null): NavKey
@Serializable data class DrugPhotosRoute(val id: String): NavKey
@Serializable data object ReminderSettingsRoute : NavKey
@Serializable data object CloudConnectionRoute : NavKey
@Serializable data object RecoveryRoute : NavKey
@Serializable data object ExportRoute : NavKey
@Serializable data class MedicationEditorRoute(val pregnancyId: String, val id: String? = null) : NavKey
@Serializable data class MedicationHistoryRoute(val id: String,val versionId: String?=null) : NavKey
@Serializable data object IntakesRoute : NavKey
@Serializable data object MeasurementsRoute : NavKey
@Serializable data class MeasurementEditorRoute(val pregnancyId: String, val id: String? = null) : NavKey
@Serializable data class IntakeEditorRoute(val pregnancyId: String, val occurrenceId: String? = null, val recordId: String? = null, val status: String = "TAKEN", val date: String? = null) : NavKey
@Serializable data class PregnancyHistoryRoute(val id: String) : NavKey
@Serializable data class PregnancyEditorRoute(val id: String? = null) : NavKey
@Serializable data class VisitEditorRoute(val pregnancyId: String, val id: String? = null) : NavKey
@Serializable data class VisitDetailRoute(val id: String) : NavKey
@Serializable data class VisitDocumentsRoute(val id: String) : NavKey
@Serializable data class VisitRecordRoute(val pregnancyId: String, val id: String) : NavKey

internal fun isRoot(key: Any?) = key == TodayRoute || key == VisitsRoute || key == ProfilesRoute || key==RecordsRoute

@Composable
fun JournalApp(model: JournalViewModel,externalStack: NavBackStack<NavKey>?=null,onSecurity: (()->Unit)?=null) {
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    var deletingRoot by remember { mutableStateOf<Pair<String, String>?>(null) }
    val stack = externalStack ?: rememberNavBackStack(TodayRoute)
    // Root tabs retain their own list/calendar state even when replaced in the back stack.
    val tabsState = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val navigationGate=remember {NavigationTapGate()}
    fun acceptNavigation(action: Any): Boolean {
        if(!navigationGate.accept(action))return false
        keyboard?.hide()
        return true
    }
    val navigationScope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    fun push(key: NavKey) {
        if (busy || preparing || stack.lastOrNull() == key || !acceptNavigation("push" to key)) return
        // A notice from the previous screen must not cover actions in a newly opened editor.
        snackbar.currentSnackbarData?.dismiss()
        preparing = true
        navigationScope.launch {
            try {
                val prepared = when (key) {
                    is DrugEditorRoute -> {model.editDrug(key.pregnancyId,key.id).join();model.hasDrugEditor(key.pregnancyId,key.id)}
                    is JournalEntryEditorRoute -> {model.editJournalEntry(key.pregnancyId,key.id).join();model.hasJournalEntryEditor(key.pregnancyId,key.id)}
                    is MeasurementEditorRoute -> { model.editMeasurement(key.pregnancyId,key.id).join(); model.hasMeasurementEditor(key.pregnancyId,key.id) }
                    is IntakeEditorRoute -> { model.editIntake(key.pregnancyId, key.occurrenceId, key.recordId, key.status, key.date).join(); model.hasIntakeEditor(key.pregnancyId, key.occurrenceId, key.recordId) }
                    is MedicationEditorRoute -> { model.editMedication(key.id, key.pregnancyId).join(); model.hasMedicationEditor(key.id, key.pregnancyId) }
                    is PregnancyEditorRoute -> { model.editPregnancy(key.id).join(); model.hasPregnancyEditor(key.id) }
                    is VisitEditorRoute -> { model.editVisit(key.id, key.pregnancyId).join(); model.hasVisitEditor(key.id, key.pregnancyId) }
                    is VisitRecordRoute -> { model.editVisit(key.id, key.pregnancyId, actual = true).join(); model.hasVisitEditor(key.id, key.pregnancyId, actual = true) }
                    else -> true
                }
                if (prepared) stack.add(key)
            } finally { preparing = false }
        }
    }
    fun pop(force: Boolean = false) {
        if (stack.size > 1 && (force || !busy && !preparing && acceptNavigation("back"))) {
            model.flushDraft()
            stack.removeAt(stack.lastIndex)
        }
    }
    fun switchTab(key: NavKey) {
        if (busy || preparing || stack.lastOrNull() == key || !acceptNavigation("tab" to key)) return
        stack.clear(); stack.add(key)
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) model.flushDraft() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model) {
        model.events.collect { event ->
            when (event) {
                JournalEvent.Saved -> { pop(force = true); launch { snackbar.showSnackbar("已保存到本机") } }
                is JournalEvent.Error -> launch { snackbar.showSnackbar(event.message) }
                is JournalEvent.Notice -> launch { snackbar.showSnackbar(event.message) }
                is JournalEvent.RootRemoved -> {
                    snackbar.currentSnackbarData?.dismiss()
                    if (event.kind == "VISIT" && stack.lastOrNull() == VisitDetailRoute(event.rootId)) pop(force = true)
                    launch {
                        if (snackbar.showSnackbar("已移入全局回收站", actionLabel = "撤销", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) model.restoreBatch(event.batch)
                    }
                }
                is JournalEvent.DocumentRemoved -> { snackbar.currentSnackbarData?.dismiss(); launch {
                    val result = snackbar.showSnackbar(event.message, actionLabel = if (event.id != null) "撤销" else null,
                        duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed && event.id != null) model.restoreDocument(event.id)
                } }
            }
        }
    }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    Box(Modifier.fillMaxSize().clipToBounds()) {
        if (state.loading || state.error != null) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                if (state.error != null) Box(Modifier.padding(24.dp)) { JournalError(state.error!!) } else JournalLoading()
            }
        } else NavDisplay(
            backStack = stack, modifier = Modifier.fillMaxSize(), onBack = { pop() }, sizeTransform = null,
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            transitionSpec = {
                if (initialState.metadata["root"] == true && targetState.metadata["root"] == true)
                    fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                else (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it * direction } togetherWith
                    slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it * direction / 4 })
                    .apply { targetContentZIndex = 1f }
            },
            popTransitionSpec = {
                (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it * direction / 4 } togetherWith
                    slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { it * direction }).apply { targetContentZIndex = -1f }
            },
            predictivePopTransitionSpec = { edge ->
                val sign = if (edge == BackEventCompat.EDGE_RIGHT) -1 else 1
                (slideInHorizontally(tween(320)) { -it * sign / 4 } togetherWith
                    slideOutHorizontally(tween(320)) { it * sign }).apply { targetContentZIndex = -1f }
            },
            entryProvider = entryProvider {
                entry<TodayRoute>(metadata = mapOf("root" to true)) {
                    tabsState.SaveableStateProvider("today") {
                        JournalPage(root = TodayRoute, hasProfile = state.active != null, switchTab = ::switchTab) {
                            if (state.active == null) WelcomeScreen { push(PregnancyEditorRoute()) }
                            else HomeScreen(state, { push(PregnancyManagerRoute) }, { push(VisitEditorRoute(state.active!!.id)) }, { push(VisitDetailRoute(it)) }, model,
                                onIntakes={push(IntakesRoute)},onVisits={switchTab(VisitsRoute)},onDocuments={push(TimelineRoute("DOCUMENT"))},
                                onMeasurements={push(MeasurementsRoute)},onJournal={push(JournalEntryEditorRoute(state.active!!.id))},
                                onDocument={push(TimelineSourceRoute(state.active!!.id,"DOCUMENT",it))},onBackup={push(CloudConnectionRoute)},
                                onEditPregnancy={push(PregnancyEditorRoute(state.active!!.id))})
                        }
                    }
                }
                entry<VisitsRoute>(metadata = mapOf("root" to true)) {
                    tabsState.SaveableStateProvider("visits") {
                        JournalPage(root = VisitsRoute, hasProfile = state.active != null, switchTab = ::switchTab) {
                            VisitsScreen(state, { state.active?.let { push(VisitEditorRoute(it.id)) } }, { push(VisitDetailRoute(it)) })
                        }
                    }
                }
                entry<ProfilesRoute>(metadata = mapOf("root" to true)) {
                    tabsState.SaveableStateProvider("profiles") {
                        JournalPage(root = ProfilesRoute, hasProfile = state.active != null, switchTab = ::switchTab) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("profiles-root-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                PageHeader("我的", "孕期档案与本机资料")
                                Inset { RecordRow("管理孕期", "切换、编辑与归档", "d9f3c.svg") { push(PregnancyManagerRoute) } }
                                Inset { RecordRow("全局回收站", "恢复删除项目 · 查看保留的资料", "ic-trash.svg") { push(TrashRoute) } }
                                Inset { RecordRow("提醒设置", "通知权限、精确提醒与未来7天重建", "ic-bell.svg") { push(ReminderSettingsRoute) } }
                                if(onSecurity!=null)Inset {RecordRow("安全设置","应用锁、严格模式与立即锁定","ic-shield.svg",onSecurity)}
                                  Inset {RecordRow("云盘连接","配置 WebDAV 与检查备份目录","ic-cloud.svg") {push(CloudConnectionRoute)}}
                                  Inset {RecordRow("本地备份","导出加密备份与保存恢复密钥","ic-backup.svg") {push(RecoveryRoute)}}
                                  Inset {RecordRow("导出资料","结构化数据包与就诊摘要 PDF","ic-file-text.svg") {push(ExportRoute)}}
                                Inset { RecordRow("用药方案", "药品、方案与历史版本", "ic-pill.svg") { push(MedicationsRoute) } }
                                Inset { RecordRow("服药记录", "按日计划、确认与补记", "ic-notebook.svg") { push(IntakesRoute) } }
                                Inset { RecordRow("健康指标", "测量记录与分组趋势", "ic-activity.svg") { push(MeasurementsRoute) } }
                                Inset { Text("孕迹 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                entry<RecordsRoute>(metadata=mapOf("root" to true)) {
                    tabsState.SaveableStateProvider("records") {
                        JournalPage(root=RecordsRoute,hasProfile=state.active!=null,switchTab=::switchTab) {
                            RecordsHub(state.active?.title,state.active!=null,{push(TimelineRoute())},{push(TimelineRoute("JOURNAL"))},
                                {push(MedicationsRoute)},{push(IntakesRoute)},{push(MeasurementsRoute)},{push(TimelineRoute("DOCUMENT"))},
                                {state.active?.let { push(JournalEntryEditorRoute(it.id)) }})
                        }
                    }
                }
                entry<TimelineRoute> { route ->
                    val pregnancy = state.active
                    if (route.type == "DOCUMENT") {
                        // 方案 A：资料类型不再进入 Timeline 筛选；「全部资料」改为独立资料列表页。
                        JournalPage(title = "全部资料", back = { pop() }) {
                            AllDocumentsScreen(pregnancy?.id, model) { id ->
                                pregnancy?.let { push(TimelineSourceRoute(it.id, "DOCUMENT", id)) }
                            }
                        }
                    } else {
                        val title = if (route.type == "JOURNAL") "日常记录" else "孕期时间线"
                        JournalPage(title = title, subtitle = if (route.type == "JOURNAL") null else TIMELINE_TAGLINE, back = { pop() }) {
                            key(pregnancy?.id) {
                                TimelineScreen(pregnancy, route.type, model,
                                    { pregnancy?.let { push(JournalEntryEditorRoute(it.id)) } }) { event ->
                                    pregnancy?.let { p ->
                                        when (event.type) {
                                            "VISIT" -> push(VisitDetailRoute(event.id))
                                            "PLAN" -> push(MedicationHistoryRoute(requireNotNull(event.parentId), event.id))
                                            else -> push(TimelineSourceRoute(p.id, event.type, event.id))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                entry<TimelineSourceRoute> { source ->
                    JournalPage(title="原始记录",back={pop()}) {
                        TimelineSourceScreen(source.pregnancyId,source.type,source.id,model) {
                            when(source.type) {
                                "JOURNAL" -> push(JournalEntryEditorRoute(source.pregnancyId,source.id))
                                "MEASUREMENT" -> push(MeasurementEditorRoute(source.pregnancyId,source.id))
                                "INTAKE" -> push(IntakeEditorRoute(source.pregnancyId,recordId=source.id))
                            }
                        }
                    }
                }
                entry<JournalEntryEditorRoute> { route ->
                    LaunchedEffect(route) {if(!model.hasJournalEntryEditor(route.pregnancyId,route.id))model.editJournalEntry(route.pregnancyId,route.id)}
                    val form by model.journalEntryForm.collectAsStateWithLifecycle()
                    val ready by model.formReady.collectAsStateWithLifecycle()
                    JournalPage(title=if(route.id==null)"写日常记录" else "修改日常记录",back={pop()}) {
                        if(ready) JournalEntryEditorScreen(form,model,busy) else JournalLoading(text = "正在准备…")
                    }
                }
                entry<PregnancyManagerRoute> {
                    JournalPage(title = "管理孕期", back = { pop() }) {
                        ProfilesScreen(state, busy, { push(PregnancyEditorRoute()) }, { push(PregnancyEditorRoute(it)) }, model::select,
                            { id, status -> model.changeStatus(id, status) }, { push(PregnancyHistoryRoute(it)) }, { deletingRoot = "PREGNANCY" to it })
                    }
                }
                entry<PregnancyHistoryRoute> { key ->
                    JournalPage(title = "日期依据历史", back = { pop() }) { PregnancyHistoryScreen(state, key.id) }
                }
                entry<PregnancyEditorRoute> { key ->
                    LaunchedEffect(key) { if (!model.hasPregnancyEditor(key.id)) model.editPregnancy(key.id) }
                    val form by model.pregnancyForm.collectAsStateWithLifecycle()
                    val ready by model.formReady.collectAsStateWithLifecycle()
                    JournalPage(title = if (key.id == null) "创建孕期档案" else "编辑孕期档案", back = { pop() }) {
                        if (ready) PregnancyEditor(form, busy, model::updatePregnancy, model::savePregnancy) else JournalLoading(text = "正在准备…")
                    }
                }
                entry<VisitEditorRoute> { key ->
                    LaunchedEffect(key) { if (!model.hasVisitEditor(key.id, key.pregnancyId)) model.editVisit(key.id, key.pregnancyId) }
                    val form by model.visitForm.collectAsStateWithLifecycle()
                    val ready by model.formReady.collectAsStateWithLifecycle()
                    JournalPage(title = if (key.id == null) "新建产检预约" else "修改产检预约", back = { pop() }) {
                        if (ready) VisitEditor(form, busy, model::updateVisit, model::saveVisit) else JournalLoading(text = "正在准备…")
                    }
                }
                entry<VisitRecordRoute> { key ->
                    LaunchedEffect(key) { if (!model.hasVisitEditor(key.id, key.pregnancyId, actual = true)) model.editVisit(key.id, key.pregnancyId, actual = true) }
                    val form by model.visitForm.collectAsStateWithLifecycle()
                    val ready by model.formReady.collectAsStateWithLifecycle()
                    JournalPage(title = "实际就诊记录", back = { pop() }) {
                        if (ready) ActualVisitEditor(form, busy, model::updateVisit, model::saveActualVisit) else JournalLoading(text = "正在准备…")
                    }
                }
                entry<VisitDetailRoute> { key ->
                    val visit = state.visits.firstOrNull { it.id == key.id }
                    JournalPage(title = "产检详情", back = { pop() }) {
                        VisitDetail(visit, busy, { visit?.let { push(VisitEditorRoute(it.pregnancyId, it.id)) } },
                            { visit?.let { push(VisitRecordRoute(it.pregnancyId, it.id)) } }, { cancelled -> model.cancelVisit(key.id, cancelled) },
                            { push(VisitDocumentsRoute(key.id)) }, { deletingRoot = "VISIT" to key.id },
                            { date, time -> model.setVisitReminder(key.id, date, time) }, { model.clearVisitReminder(key.id) })
                    }
                }
                entry<VisitDocumentsRoute> { key ->
                    val visit = state.visits.firstOrNull { it.id == key.id }
                    JournalPage(title = "产检资料", back = { pop() }) {
                        DocumentScreen(key.id, model,
                            visits = state.visits.filter { it.id != key.id && it.pregnancyId == visit?.pregnancyId && it.deletedAt == null },
                            coverAttachmentId = visit?.coverAttachmentId)
                    }
                }
                entry<TrashRoute> {
                    JournalPage(title = "全局回收站", back = { pop() }) { TrashScreen(model) }
                }
                entry<ReminderSettingsRoute> {
                    JournalPage(title = "提醒设置", back = { pop() }) { ReminderSettingsScreen(model) }
                }
                  entry<CloudConnectionRoute> {JournalPage(title="云盘连接",back={pop()}) {CloudConnectionScreen(model.cloud,model.cloudBackup,model.cloudCleanup)}}
                  entry<RecoveryRoute> {JournalPage(title="本地加密备份",back={pop()}) {RecoveryScreen(model.recovery)}}
                  entry<ExportRoute> {JournalPage(title="导出资料",back={pop()}) {ExportScreen(model.export)}}
                entry<DrugCatalogRoute> {JournalPage(title="管理药品",back={pop()}) {
                    DrugCatalogScreen(state.active?.id,model,{id->state.active?.let {push(DrugEditorRoute(it.id,id))}},{push(DrugPhotosRoute(it))})
                }}
                entry<DrugEditorRoute> {route ->
                    LaunchedEffect(route){if(!model.hasDrugEditor(route.pregnancyId,route.id))model.editDrug(route.pregnancyId,route.id)}
                    val form by model.drugForm.collectAsStateWithLifecycle();val ready by model.formReady.collectAsStateWithLifecycle()
                    JournalPage(title=if(route.id==null)"新增药品" else "编辑药品",back={pop()}){if(ready)DrugEditorScreen(form,model,busy) else JournalLoading(text = "正在准备…")}
                }
                entry<DrugPhotosRoute> {route ->JournalPage(title="药品包装照片",back={pop()}){DocumentScreen(route.id,model,medication=true)}}
                entry<MedicationsRoute> {
                    JournalPage(title = "用药方案", back = { pop() }) {
                        MedicationListScreen(state.active?.id, model, { state.active?.let { push(MedicationEditorRoute(it.id)) } },
                            { state.active?.let { p -> push(MedicationEditorRoute(p.id, it)) } }, { push(MedicationHistoryRoute(it)) },{push(DrugCatalogRoute)})
                    }
                }
                entry<MedicationEditorRoute> { key ->
                    LaunchedEffect(key) { if (!model.hasMedicationEditor(key.id, key.pregnancyId)) model.editMedication(key.id, key.pregnancyId) }
                    val form by model.medicationForm.collectAsStateWithLifecycle()
                    val ready by model.formReady.collectAsStateWithLifecycle()
                    JournalPage(title = if (key.id == null) "新增用药方案" else "修改用药方案", back = { pop() }) {
                        if (ready) MedicationEditorScreen(form, model, busy) else JournalLoading(text = "正在准备…")
                    }
                }
                entry<MedicationHistoryRoute> { key -> JournalPage(title = "用药历史版本", back = { pop() }) { MedicationHistoryScreen(key.id, model,key.versionId) } }
                entry<IntakesRoute> {
                    JournalPage(title = "服药记录", back = { pop() }) {
                        IntakeScreen(state.active?.id, model, openPlans = { push(MedicationsRoute) }) { point, record, status, date -> state.active?.let { push(IntakeEditorRoute(it.id, point, record, status, date)) } }
                    }
                }
                entry<MeasurementsRoute> {
                    JournalPage(title = "健康指标", back = { pop() }) {
                        MeasurementScreen(state.active, model) { id -> state.active?.let { push(MeasurementEditorRoute(it.id,id)) } }
                    }
                }
                entry<MeasurementEditorRoute> { key ->
                    LaunchedEffect(key) { if (!model.hasMeasurementEditor(key.pregnancyId,key.id)) model.editMeasurement(key.pregnancyId,key.id) }
                    val form by model.measurementForm.collectAsStateWithLifecycle()
                    val ready by model.formReady.collectAsStateWithLifecycle()
                    JournalPage(title = if (key.id == null) "记录测量" else "修改测量", back = { pop() }) {
                        if (ready) MeasurementEditorScreen(form, model, busy) else JournalLoading(text = "正在准备…")
                    }
                }
                entry<IntakeEditorRoute> { key ->
                    LaunchedEffect(key) { if (!model.hasIntakeEditor(key.pregnancyId, key.occurrenceId, key.recordId)) model.editIntake(key.pregnancyId, key.occurrenceId, key.recordId, key.status, key.date) }
                    val form by model.intakeForm.collectAsStateWithLifecycle()
                    val ready by model.formReady.collectAsStateWithLifecycle()
                    JournalPage(title = if (key.recordId != null) "修改服药记录" else if (key.occurrenceId == null) "补记服药" else "记录服药", back = { pop() }) {
                        if (ready) IntakeEditorScreen(form, model, busy) else JournalLoading(text = "正在准备…")
                    }
                }
            })
        deletingRoot?.let { (kind, id) -> RootDeletionDialog(kind, id, model, { deletingRoot = null }) }
        if (preparing) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).size(20.dp), strokeWidth = 2.dp)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding()
            .padding(horizontal = 12.dp).padding(bottom = if (isRoot(stack.lastOrNull())) 92.dp else 12.dp)) {
            // 默认 Snackbar 是直角矩形，与全站 20 dp 圆角卡片不一致。
            Snackbar(snackbarData = it, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

/** Each scene owns its insets and bars, so the parent never resizes halfway through a pop. */
@Composable
internal fun JournalPage(title: String? = null, root: NavKey? = null, hasProfile: Boolean = false,
    back: (() -> Unit)? = null, subtitle: String? = null, switchTab: (NavKey) -> Unit = {}, content: @Composable () -> Unit) {
    Scaffold(modifier = Modifier.testTag(title ?: root.toString()), containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { if (title != null) JournalPageHeader(title = title, subtitle = subtitle ?: if (back != null) "返回上一级" else null, onBack = back) },
        bottomBar = { if (root != null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            listOf(Triple(TodayRoute, "今天", "acf3f.svg"), Triple(VisitsRoute, "产检", "da483.svg"),Triple(RecordsRoute,"记录","cdc29.svg"), Triple(ProfilesRoute, "我的", "d9f3c.svg")).forEach { (key, label, icon) ->
                NavigationBarItem(selected = root == key, onClick = { switchTab(key) }, icon = { DesignIcon(icon, tint = LocalContentColor.current) }, label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer, unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
            }
        } }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content() } }
}

