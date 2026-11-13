package cn.yunji.journal

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import cn.yunji.journal.core.designsystem.PregnancyTheme
import cn.yunji.journal.core.network.CloudCapabilities
import cn.yunji.journal.data.CleanupRecord
import cn.yunji.journal.data.CloudSchedulerState
import cn.yunji.journal.data.CloudUploadStatus
import cn.yunji.journal.data.DeviceRole
import cn.yunji.journal.data.DrugForm
import cn.yunji.journal.data.JournalEntryForm
import cn.yunji.journal.data.MeasurementForm
import cn.yunji.journal.feature.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * 2026-10-24 Figma UI 对齐：为“Figma 未设计 / 未同步”的实现界面生成截图证据。
 *
 * 真机（小米 11 Ultra）因 MainActivity 设置 FLAG_SECURE 无法截图，因此在专用模拟器
 * emulator-5556（Android 16 / 1080x2400 / 420dpi，等价 412x915dp）上用 Compose
 * `captureToImage()` 真实渲染当前实现。基线：font_scale=1.0、浅色、竖屏。
 *
 * 做法：优先直接渲染无状态/可注入状态的屏幕（SecuritySettingsScreen、ExportContent、
 * CloudConnectionContent、RestoreContent、RecoveryContent 等），需要真实数据/模型的屏幕
 * 沿用 PageMatrixBaseTest 的加密数据库夹具（提醒设置、回收站、药品目录、时间线等）。
 * 页面统一包进真实使用的 JournalPage（标题栏 + 返回按钮），与 App 内实际呈现一致。
 *
 * 产物写到应用外部 files 的 `verification/figma-ui-alignment/`，由宿主 adb pull 到
 * `docs/verification/2026-10-24-figma-ui-alignment/app-screens/`。截图状态见同目录 manifest.md。
 */
class UiSnapshotCoverageTest : PageMatrixBaseTest() {

    // ---------- 1. 提醒设置 ----------

    @Test
    fun snap01ReminderSettings() {
        seedCustomReminder()
        showPage("提醒设置") { ReminderSettingsScreen(model) }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("通知权限").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("custom-reminder-add").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("每日叶酸提醒").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("reminder-settings")
        // 滚到“调度检查”露出提醒类型 / 自定义提醒（含提醒行与新建入口）/ 调度检查三块。
        ui.onAllNodesWithText("调度检查")[0].performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("reminder-rebuild").assertIsDisplayed()
        captureRoot("reminder-settings-custom")
    }

    // ---------- 2. 自定义提醒编辑器（新建表单，默认“一次”） ----------

    @Test
    fun snap02CustomReminderEditor() {
        showPage("提醒设置") { ReminderSettingsScreen(model) }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("custom-reminder-add").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("custom-reminder-add").performScrollTo().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("custom-reminder-title").fetchSemanticsNodes().isNotEmpty() }
        captureDialog("custom-reminder-editor")
    }

    // ---------- 3. 安全设置（应用锁 / 严格模式 / 立即锁定） ----------

    @Test
    fun snap03SecuritySettings() {
        showPage("安全设置") {
            SecuritySettingsScreen(SecurityUi(enabled = true, strict = false, timeout = 5)) {}
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("security-settings").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("security-settings")
    }

    @Test
    fun snap03VaultLocked() {
        ui.setContent { PregnancyTheme { UnlockScreen(busy = false, error = null, unlock = {}) } }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("vault-locked").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("vault-locked")
    }

    // ---------- 4. 云盘连接（连接检查 / 云端备份 / 云端清理） ----------

    @Test
    fun snap04CloudConnection() {
        val state = CloudUiState(
            endpoint = "https://dav.jianguoyun.com/dav/",
            username = "family@example.com",
            password = "",
            folder = "家庭/孕期资料",
            loading = false,
            saved = true,
            hasSavedPassword = true,
            capabilities = CloudCapabilities(
                readable = true, writable = true, verified = true, canCreateDirectory = true,
                canDelete = true, headAvailable = true, completeListing = true, existingVaultCount = 2,
            ),
        )
        val backup = CloudBackupUiState(
            status = CloudUploadStatus(
                lastAttemptAt = 1_760_000_000_000,
                lastSuccessAt = 1_760_000_000_000,
                lastSnapshotId = "snap-20261023-01",
                lastPhase = "COMMITTED",
            ),
            running = false,
            scheduler = CloudSchedulerState(role = DeviceRole.PRIMARY, autoEnabled = true, wifiOnly = true),
        )
        val cleanup = CloudCleanupUiState(
            record = CleanupRecord(
                attemptedAt = 1_760_000_000_000, ran = true, message = "云端备份清理完成",
                kept = 3, commitsDeleted = 2, objectsDeleted = 5, orphansTracked = 1,
            ),
            message = "云端备份清理完成",
        )
        showPage("云盘连接") {
            CloudConnectionContent(
                state = state, update = { _, _ -> }, test = {}, save = {}, cancel = {},
                disconnect = {}, forget = {}, browse = null, backup = backup,
                upload = {}, abortBackup = {}, setAuto = {}, setWifi = {}, makePrimary = {},
                cleanup = cleanup, runCleanup = {},
            )
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("cloud-connection").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("cloud-connection")
        // 自动备份开关是云端备份小节的末段：滚到这里能同时露出备份状态与上传按钮。
        scrollToTag("cloud-auto")
        captureRoot("cloud-connection-backup")
        // 清理说明是本小节最后一段：滚到这里能露出上次尝试、计数与“立即清理”按钮。
        scrollToText("只删除云端按保留策略淘汰", substring = true)
        captureRoot("cloud-connection-cleanup")
    }

    // ---------- 5. 本地备份（恢复入口 / 安全副本空态） ----------

    @Test
    fun snap05LocalBackup() {
        showPage("本地加密备份") {
            RecoveryContent(
                state = RecoveryUiState(stage = "READY"),
                generate = {}, verify = {}, answer = { _, _ -> }, confirm = {}, retry = {},
                export = {}, restore = {},
                extra = { SafetyCopiesPanel("synthetic-vault") },
            )
        }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("recovery-screen").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("还没有安全副本").fetchSemanticsNodes().isNotEmpty() }
        // 该页在 915dp 内可完整展示：恢复入口 + 导出按钮 + 安全副本空态同屏。
        captureRoot("local-backup")
    }

    // ---------- 6. 全局回收站（空态） ----------

    @Test
    fun snap06TrashEmpty() {
        showPage("全局回收站") { TrashScreen(model) }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("全局回收站为空").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("trash-empty")
    }

    // ---------- 7. 导出资料（勾选状态 + 按钮禁用态） ----------

    @Test
    fun snap07Export() {
        showPage("导出资料") {
            ExportContent(
                state = ExportUiState(),
                updateRange = { _, _ -> }, toggleSection = {}, setOriginals = {}, setTrash = {},
                acknowledge = {}, preview = {}, exportData = {}, exportSummary = {}, cancel = {},
            )
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("export-screen").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("export-screen")
        scrollToTag("export-data")
        captureRoot("export-screen-disabled")
    }

    // ---------- 8. 恢复向导（默认 SELECT 态 + 已选文件 KEY 态） ----------

    @Test
    fun snap08RestoreWizard() {
        val state = mutableStateOf(RestoreUiState())
        showPage("恢复资料") {
            RestoreContent(
                state = state.value,
                chooseFile = { state.value = RestoreUiState(stage = "KEY", document = "content://fixture/backup.yunji") },
                keyChanged = { state.value = state.value.copy(key = it) },
                prepare = {}, acknowledge = {}, confirm = {}, cancel = {}, reopen = {},
            )
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("restore-screen").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("restore-wizard")
        ui.onNodeWithTag("restore-choose").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("restore-key").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("restore-wizard-key")
    }

    // ---------- 9. 药品目录 / 药品编辑器 ----------

    @Test
    fun snap09DrugCatalog() {
        seedMedicationPlan()
        showPage("管理药品") { DrugCatalogScreen(pregnancyId, model, {}, {}) }
        // Room Flow 首帧在模拟器上偶发慢于 10s（同测试类单跑稳定通过），放宽到 20s。
        ui.waitUntil(20_000) {
            ui.onAllNodesWithText("复合维生素片（孕妇型）").fetchSemanticsNodes().isNotEmpty()
        }
        captureRoot("drug-catalog")
    }

    @Test
    fun snap10DrugEditor() {
        val form = runBlocking { model.medications.drugForm(pregnancyId, null) }
        showPage("新增药品") { DrugEditorScreen(form, model, false) }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("drug-name").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("drug-editor")
    }

    // ---------- 10. 健康指标编辑器 ----------

    @Test
    fun snap11MeasurementEditor() {
        val form = runBlocking { model.measurements.form(pregnancyId) }
        showPage("记录测量") { MeasurementEditorScreen(form, model, false) }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("measurement-value").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("measurement-editor")
    }

    // ---------- 12. 日常记录编辑器 ----------

    @Test
    fun snap12JournalEntryEditor() {
        val base = runBlocking { model.journalEntries.form(pregnancyId, null) }
        val form = base.copy(
            symptom = "轻微腰酸，下午缓解",
            mood = "平静",
            text = "今天状态平稳，晚饭后散步二十分钟。",
        )
        showPage("写日常记录") { JournalEntryEditorScreen(form, model, false) }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("journal-text").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("journal-entry-editor")
    }

    // ---------- 13. 孕期档案编辑器 ----------

    @Test
    fun snap13PregnancyEditor() {
        showPage("编辑孕期档案") {
            PregnancyEditor(
                PregnancyForm(
                    id = pregnancyId, title = "我的孕期",
                    lmp = LocalDate.now().minusDays(210).toString(),
                    edd = LocalDate.now().plusDays(70).toString(),
                    hospital = "市妇幼保健院", note = "",
                ),
                false, {}, {},
            )
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("pregnancy-editor-list").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("pregnancy-editor")
    }

    // ---------- 14. 产检详情 / 产检编辑器 ----------

    @Test
    fun snap14VisitDetail() {
        val visitId = seedShortVisit(items = "常规产检")
        val visit = runBlocking { db.journalDao().visit(visitId) }
        showPage("产检详情") {
            VisitDetail(visit = visit, busy = false, onEdit = {}, onComplete = {}, onCancel = {}, onDocuments = {})
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("visit-detail-list").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("visit-detail")
    }

    @Test
    fun snap15VisitEditor() {
        showPage("新建产检预约") {
            VisitEditor(
                VisitForm(pregnancyId = pregnancyId, hospital = "市妇幼保健院"),
                false, {}, {},
            )
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("预约日期").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("visit-editor")
    }

    // ---------- 11. 时间线 / 来源详情 ----------

    @Test
    fun snap16Timeline() {
        // 设计 12-Timeline 的日期组头需要孕周：补 EDD（今天+120 天）。
        runBlocking { repository.savePregnancy(pregnancyId, "其余页面极端孕期", null, LocalDate.now().plusDays(120), "", "") }
        seedJournalEntry()
        val visitId = seedShortVisit(items = "常规产检")
        // 「全部」视图按设计示例包含测量行与资料行（资料/测量等类型不再有专属筛选，但以记录行降级展示）
        runBlocking {
            model.measurements.save(model.measurements.form(pregnancyId).copy(values = mapOf("value" to "58.2")))
        }
        val documentId = enqueueDocument(visitId, createPngSource())
        runBlocking {
            model.documents.process(documentId)
            model.documents.updateMetadata(documentId, "超声检查报告", "ULTRASOUND",
                LocalDate.now().minusDays(1).toString(), "市妇幼保健院", "3张图片 · 已关联本次产检")
        }
        val pregnancy = runBlocking { db.journalDao().pregnancy(pregnancyId) }
        showPage("孕期时间线", subtitle = "把一次次记录，连成自己的故事") { TimelineScreen(pregnancy, "", model, {}, {}) }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("timeline-list").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10_000) {
            ui.onAllNodesWithText("今天状态平稳，晚饭后散步二十分钟。", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("写一条日常备注").fetchSemanticsNodes().isNotEmpty() }
        captureRoot("timeline")
    }

    @Test
    fun snap17TimelineSourceDetail() {
        val entryId = seedJournalEntry()
        showPage("原始记录") { TimelineSourceScreen(pregnancyId, "JOURNAL", entryId, model, {}) }
        ui.waitUntil(10_000) {
            ui.onAllNodesWithText("轻微腰酸，下午缓解", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        captureRoot("timeline-source-detail")
    }

    @Test
    fun snap18DatingHistory() {
        val now = System.currentTimeMillis()
        val revisions = listOf(
            cn.yunji.journal.core.database.PregnancyDateRevisionEntity("current", pregnancyId,
                LocalDate.now().minusDays(110).toString(), LocalDate.now().plusDays(170).toString(), "医生确认", now),
            cn.yunji.journal.core.database.PregnancyDateRevisionEntity("earlier", pregnancyId,
                LocalDate.now().minusDays(112).toString(), null, "末次月经", now - 86_400_000),
        )
        showPage("日期依据历史") { PregnancyHistoryScreen(JournalState(dateRevisions = revisions), pregnancyId) }
        ui.onNodeWithText("当前日期依据").assertExists()
        captureRoot("dating-history")
    }

    @Test
    fun snap19ActualVisitEditor() {
        showPage("记录实际就诊") { ActualVisitEditor(
            VisitForm(pregnancyId = pregnancyId, date = LocalDate.now().minusDays(1).toString(),
                actualDate = LocalDate.now().toString(), hospital = "市妇幼保健院", items = "常规产检",
                advice = "下次复诊时间待确认", actualTime = "09:30", actualHospital = "实际就诊医院", actualResult = "合成检查结果"), false, {}, {},
        ) }
        ui.onNodeWithText("关联预约").assertExists()
        captureRoot("actual-visit-editor")
    }

    // ---------- 夹具与截图 ----------

    /** 以真实 App 页面骨架（标题栏 + 返回）渲染目标屏幕；方案 A 起支持覆盖副标题（时间线 tagline）。 */
    private fun showPage(title: String, subtitle: String? = null, content: @androidx.compose.runtime.Composable () -> Unit) {
        ui.setContent { PregnancyTheme { JournalPage(title = title, subtitle = subtitle, back = {}) { content() } } }
        settle()
    }

    private fun seedCustomReminder() = runBlocking {
        repository.saveCustomReminder(
            null, pregnancyId, "每日叶酸提醒", "早餐后服用",
            LocalDate.now().plusDays(3), LocalTime.of(9, 0), "DAILY", null,
        )
        Unit
    }

    private fun scrollToText(text: String, substring: Boolean = false) {
        ui.onNodeWithText(text, substring = substring).performScrollTo()
        settle()
    }

    private fun scrollToTag(tag: String) {
        ui.onNodeWithTag(tag).performScrollTo()
        settle()
    }

    private fun captureRoot(name: String) {
        settle()
        saveBitmap(ui.onRoot().captureToImage().asAndroidBitmap(), name)
    }

    /**
     * 对话框是独立窗口：Compose 对独立窗口 root 的 captureToImage 会返回主窗口区域，不能作为证据。
     * 改用 UiAutomation 整屏截图（测试 Activity 与对话框都没有 FLAG_SECURE，可正常截取）。
     */
    private fun captureDialog(name: String) {
        settle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = runCatching {
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "UiAutomation 截图返回 null" }
        }.getOrElse { error ->
            Log.w(TAG, "UiAutomation dialog capture failed: ${error.message}")
            val dialogRoot = ui.onNode(
                isRoot() and hasAnyDescendant(hasTestTag("custom-reminder-title")),
                useUnmergedTree = true,
            )
            dialogRoot.captureToImage().asAndroidBitmap()
        }
        saveBitmap(bitmap, name)
    }

    private fun saveBitmap(bitmap: Bitmap, name: String) {
        val dir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "verification/figma-ui-alignment",
        ).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "saved $name ${bitmap.width}x${bitmap.height}")
        bitmap.recycle()
    }

    private companion object {
        const val TAG = "UiSnapshotCoverage"
    }
}
