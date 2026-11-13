package cn.yunji.journal.feature

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import cn.yunji.journal.core.designsystem.*
import cn.yunji.journal.data.BackgroundAssist
import cn.yunji.journal.data.ReminderWorker
import cn.yunji.journal.core.database.CustomReminderEntity
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ReminderSettingsScreen(model: JournalViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notifications by remember { mutableStateOf(model.notificationsAllowed()) }
    var exact by remember { mutableStateOf(model.exactRemindersAllowed()) }
    var visits by remember { mutableStateOf(model.visitsReminderEnabled()) }
    var medications by remember { mutableStateOf(model.medicationsReminderEnabled()) }
    var batteryOk by remember { mutableStateOf(BackgroundAssist.ignoringBatteryOptimizations(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh) {
        val allowed=model.notificationsAllowed()
        if(allowed && !notifications)ReminderWorker.enqueue(context)
        notifications=allowed
        exact=model.exactRemindersAllowed()
        batteryOk=BackgroundAssist.ignoringBatteryOptimizations(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val allowed=model.notificationsAllowed()
        if(allowed && !notifications)ReminderWorker.enqueue(context)
        notifications=allowed
    }
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsState()
    val customReminders by model.customReminders.collectAsState()
    var editingReminder by remember { mutableStateOf<CustomReminderEntity?>(null) }
    var showReminderEditor by remember { mutableStateOf(false) }
    var deletingReminder by remember { mutableStateOf<CustomReminderEntity?>(null) }
    val permissionTarget = remember { BringIntoViewRequester() }
    val visitTarget = remember { BringIntoViewRequester() }
    val medicationTarget = remember { BringIntoViewRequester() }
    val customTarget = remember { BringIntoViewRequester() }
    val scheduleTarget = remember { BringIntoViewRequester() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Inset { JournalCard(soft = true) {
            Text("提醒状态", style = MaterialTheme.typography.titleLarge)
            Text(if (notifications) "通知权限已允许" else "通知权限未允许", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(if (notifications && exact) "可以查看未来安排与自定义提醒" else "仍可查看应用内待办",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        Inset { RecordRow("通知权限", if (notifications) "已允许 · 查看通知和系统设置" else "打开系统设置以接收通知", "ic-bell.svg", { scope.launch { permissionTarget.bringIntoView() } }) }
        Inset { RecordRow("产检提醒", if (visits) "按预约日期安排 · 已开启" else "按预约日期安排 · 已关闭", "ic-calendar-check.svg", { scope.launch { visitTarget.bringIntoView() } }) }
        Inset { RecordRow("用药提醒", if (medications) "按方案和服药时间安排 · 已开启" else "按方案和服药时间安排 · 已关闭", "ic-pill.svg", { scope.launch { medicationTarget.bringIntoView() } }) }
        Inset { RecordRow("自定义提醒", "一次、每天或每周", "ic-bell-plus.svg", { scope.launch { customTarget.bringIntoView() } }) }
        Inset { RecordRow("调度检查", "查看未来 7 天的安排", "ic-clock.svg", { scope.launch { scheduleTarget.bringIntoView() } }) }
        Inset { Text("提醒状态以手机系统授权为准。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Inset { Text("详细设置", style = MaterialTheme.typography.titleLarge) }
        Box(Modifier.bringIntoViewRequester(permissionTarget)) {
        Inset { JournalCard {
            Text("通知权限", style = MaterialTheme.typography.titleLarge)
            Text(if (notifications) "已允许显示通知" else "未允许；只能使用应用内待办", modifier = Modifier.testTag("reminder-permission"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!notifications) JournalTextAction("开启通知权限", Modifier.testTag("request-notification"),
                    onClick = { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) })
                if (notifications) JournalTextAction("发送测试通知", Modifier.testTag("reminder-test"),
                    onClick = { model.sendTestReminder() })
                JournalTextAction("系统通知设置", onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                })
            }
        } }
        }
        Inset { JournalCard {
            Text("提醒时间", style = MaterialTheme.typography.titleLarge)
            Text(if (exact) "可使用精确提醒" else "将使用非精确提醒，系统可能推迟", modifier = Modifier.testTag("reminder-exact"))
            if (!exact && Build.VERSION.SDK_INT >= 31) JournalTextAction("打开精确提醒系统设置", onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())) }
            })
            Text("精确提醒资格由系统和发布渠道决定；未获授权时自动降级，不需要关闭提醒。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        Inset { JournalCard {
            Text("后台可靠性", style = MaterialTheme.typography.titleLarge)
            Text(
                if (batteryOk) "已加入电池优化白名单，系统不会因省电冻结提醒" else "未加入电池优化白名单；系统省电可能推迟或跳过提醒",
                modifier = Modifier.testTag("reminder-battery"),
            )
            if (!batteryOk) JournalTextAction("允许后台运行", Modifier.testTag("request-battery-ignore"), onClick = {
                runCatching { context.startActivity(BackgroundAssist.ignoreBatteryRequestIntent(context)) }
                    .onFailure { runCatching { context.startActivity(BackgroundAssist.batterySettingsIntent()) } }
            })
            val vendor = remember { BackgroundAssist.plan() }
            if (vendor != null) {
                Text(
                    vendor.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("reminder-vendor-hint"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JournalTextAction("打开自启动设置", Modifier.testTag("open-autostart"), onClick = {
                        if (!BackgroundAssist.openAutostart(context)) BackgroundAssist.openAppDetails(context)
                    })
                    JournalTextAction("应用信息", Modifier.testTag("open-app-details"),
                        onClick = { BackgroundAssist.openAppDetails(context) })
                }
            } else {
                Text("未识别到已知厂商限制；如提醒被延迟，请在系统设置中允许孕迹后台运行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                JournalTextAction("应用信息", Modifier.testTag("open-app-details"),
                    onClick = { BackgroundAssist.openAppDetails(context) })
            }
        } }
        Box(Modifier.bringIntoViewRequester(visitTarget)) {
        Inset { JournalCard {
            Text("提醒类型", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("产检提醒"); JournalSwitch(visits, { visits = it; model.setReminderTypeEnabled(true, it) }, Modifier.testTag("reminder-visits"))
            }
            Row(Modifier.fillMaxWidth().bringIntoViewRequester(medicationTarget), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("用药提醒"); JournalSwitch(medications, { medications = it; model.setReminderTypeEnabled(null, it) }, Modifier.testTag("reminder-medications"))
            }
            Text("自动云端备份连续失败3次，或检测到其他设备写入时，会提示检查备份状态；同一轮失败只提醒一次，不在通知中显示医疗内容或云端信息。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        }
        Box(Modifier.bringIntoViewRequester(customTarget)) {
        Inset { JournalCard {
            Text("自定义提醒", style = MaterialTheme.typography.titleLarge)
            Text("可按一次、每天或每周提醒；内容保存在加密资料中。", style = MaterialTheme.typography.bodySmall)
            val active = state.active?.takeIf { it.status == "ONGOING" && it.deletedAt == null }
            if (active == null) Text("请先创建或选择进行中的孕期。", style = MaterialTheme.typography.bodySmall)
            else JournalButton("新建提醒", secondary = true, modifier = Modifier.testTag("custom-reminder-add")) {
                editingReminder = null; showReminderEditor = true
            }
            customReminders.filter { it.pregnancyId == active?.id }.forEach { row ->
                val repeatText = when(row.repeat) { "DAILY" -> "每天"; "WEEKLY" -> "每周"; else -> "仅一次" }
                Column(Modifier.testTag("custom-reminder-row-${row.id}")) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                    Text("${displayDateTime(row.startDate, row.localTime)} · $repeatText", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        JournalTextAction("编辑", Modifier.testTag("custom-reminder-edit-${row.id}"),
                            onClick = { editingReminder = row; showReminderEditor = true })
                        JournalTextAction("删除", Modifier.testTag("custom-reminder-delete-${row.id}"), danger = true,
                            onClick = { deletingReminder = row })
                    }
                }
            }
        } }
        }
        Box(Modifier.bringIntoViewRequester(scheduleTarget)) {
        Inset { JournalCard {
            Text("调度检查", style = MaterialTheme.typography.titleLarge)
            Text("未来7天事件会在应用启动、每日维护、规则变化、重启和时间/时区变化后重建。", style = MaterialTheme.typography.bodySmall)
            JournalButton("立即重建未来7天提醒", secondary = true, modifier = Modifier.testTag("reminder-rebuild")) {
                scope.launch { model.rebuildReminders() }
            }
        } }
        }
        Inset { Text("提醒只在本机生成，不上传药名、医院或检查内容。系统省电、强行停止或拒绝权限时可能延迟或不显示；应用内待办始终保留。", style = MaterialTheme.typography.bodySmall) }
        Inset { Text("未获通知权限或系统限制后台时，请在本应用内查看今天、产检、用药和此页的自定义提醒；可按上方入口在系统设置中允许自启动与后台运行（孕迹不会代您修改这些系统设置）。", style = MaterialTheme.typography.bodySmall) }
    }
    if (showReminderEditor) CustomReminderEditor(editingReminder,
        onDismiss = { showReminderEditor = false },
        onSave = { title,note,date,time,repeat,end ->
            val active = state.active ?: return@CustomReminderEditor
            model.saveCustomReminder(editingReminder?.id,active.id,title,note,date,time,repeat,end)
            showReminderEditor = false
        })
    deletingReminder?.let { row ->
        AlertDialog(onDismissRequest = { deletingReminder = null }, title = { Text("删除提醒？") },
            text = { Text("“${row.title}”将被删除，之后不会再通知。") },
            confirmButton = { TextButton(onClick = { model.deleteCustomReminder(row.id); deletingReminder = null },Modifier.testTag("custom-reminder-confirm-delete")) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deletingReminder = null }) { Text("取消") } })
    }
}

@Composable
private fun CustomReminderEditor(row: CustomReminderEntity?, onDismiss: () -> Unit,
    onSave: (String,String,String,String,String,String?) -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val initial = remember { java.time.LocalDateTime.now().plusHours(1) }
    var title by remember(row?.id) { mutableStateOf(row?.title.orEmpty()) }
    var note by remember(row?.id) { mutableStateOf(row?.note.orEmpty()) }
    var date by remember(row?.id) { mutableStateOf(row?.startDate ?: initial.toLocalDate().toString()) }
    var time by remember(row?.id) { mutableStateOf(row?.localTime ?: initial.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var repeat by remember(row?.id) { mutableStateOf(row?.repeat ?: "NONE") }
    var end by remember(row?.id) { mutableStateOf(row?.endDate) }
    var error by remember(row?.id) { mutableStateOf<String?>(null) }
    fun pickDate(current: String?, accept: (String)->Unit) {
        val initial = runCatching { LocalDate.parse(current) }.getOrDefault(LocalDate.now())
        android.app.DatePickerDialog(context,{ _,year,month,day -> accept(LocalDate.of(year,month+1,day).toString()) },
            initial.year,initial.monthValue-1,initial.dayOfMonth).show()
    }
    fun save() {
            error=when {
                title.isBlank()->"请输入提醒标题"
                runCatching {LocalDate.parse(date);LocalTime.parse(time)}.isFailure->"日期或时间无效"
                end!=null && runCatching {LocalDate.parse(end).isBefore(LocalDate.parse(date))}.getOrDefault(true)->"结束日期不能早于开始日期"
                repeat=="NONE" && runCatching {cn.yunji.journal.domain.MedicationSchedule.resolve(LocalDate.parse(date),LocalTime.parse(time),java.time.ZoneId.systemDefault()).toEpochMilli()<=System.currentTimeMillis()}.getOrDefault(true)->"单次提醒时间必须在未来"
                else->null
            }
            if(error==null)onSave(title,note,date,time,repeat,if(repeat=="NONE")null else end)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
            onDispose { }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                JournalFullScreenHeader(
                    title = if (row == null) "新建自定义提醒" else "编辑自定义提醒",
                    subtitle = "返回提醒设置 · 内容仅保存在本机",
                    onBack = onDismiss,
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    JournalField(title, { title = it.take(100) }, "提醒标题",
                        modifier = Modifier.testTag("custom-reminder-title"))
                    JournalField(note, { note = it.take(1000) }, "备注（选填）", singleLine = false, minLines = 2,
                        maxLines = 3, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { focusManager.clearFocus(); pickDate(date) { date = it } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("提醒日期", style = MaterialTheme.typography.bodySmall)
                            Text(displayDate(date), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    OutlinedButton(onClick = {
                        focusManager.clearFocus()
                        val parsed = runCatching { LocalTime.parse(time) }.getOrDefault(LocalTime.now())
                        android.app.TimePickerDialog(context, { _, hour, minute ->
                            time = LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("HH:mm"))
                        }, parsed.hour, parsed.minute, true).show()
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("提醒时间", style = MaterialTheme.typography.bodySmall)
                            Text(time, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Text("重复规则", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("NONE" to "一次", "DAILY" to "每天", "WEEKLY" to "每周").forEach { (value, label) ->
                            JournalTag(repeat == value, { repeat = value }, label = { Text(label) },
                                modifier = Modifier.testTag("custom-reminder-repeat-$value"))
                        }
                    }
                    if (repeat != "NONE") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        JournalTextAction(if (end == null) "设置结束日期" else "结束 ${end?.let(::displayDate)}",
                            onClick = { focusManager.clearFocus(); pickDate(end) { end = it } })
                        if (end != null) JournalTextAction("不限期", onClick = { end = null })
                    }
                    Text("提醒预览", style = MaterialTheme.typography.titleMedium)
                    RecordRow("${when (repeat) { "DAILY" -> "每天"; "WEEKLY" -> "每周"; else -> displayDate(date) }} $time",
                        "通知默认隐藏私人信息", "ic-bell.svg")
                    error?.let { JournalError(it) }
                    Text("可选择一次、每天或每周重复。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    JournalButton("保存提醒", modifier = Modifier.testTag("custom-reminder-save"), onClick = ::save)
                    JournalTextAction("返回提醒设置", Modifier.fillMaxWidth(), onClick = onDismiss)
                }
            }
        }
    }
}
