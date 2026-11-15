package cn.yunji.journal

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunji.journal.core.backup.RestorePreview
import cn.yunji.journal.core.backup.SafetyCopy
import cn.yunji.journal.core.backup.SnapshotRef
import cn.yunji.journal.core.database.AttachmentEntity
import cn.yunji.journal.core.designsystem.*
import cn.yunji.journal.core.share.OriginalShareDialog
import cn.yunji.journal.data.DrugForm
import cn.yunji.journal.feature.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** 本轮尚缺页面、分支与弹窗的可复现证据，只使用独立数据库及合成文件。 */
class UiReviewRemainingPagesTest : PageMatrixBaseTest() {
    @Test fun homeMissingDateAndNoPlanStayQuietWhileValidAgeRemainsProminent() {
        launchApp()
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("今日服药记录"))
        ui.waitUntil(10_000) { ui.onAllNodesWithText("今天没有固定服药计划").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("今天没有固定服药计划"))
        ui.onNodeWithText("今天没有固定服药计划").assertIsDisplayed()
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("待补充日期"))
        ui.onNodeWithText("待补充日期").assertExists()
        capture("home-no-plan")
        val edd = LocalDate.now().plusDays(120)
        runBlocking { repository.savePregnancy(pregnancyId, "合成孕期", null, edd, "", "") }
        val dateLabel = "预产期 ${displayDate(edd.toString())} · 医生确认"
        ui.waitUntil(10_000) { ui.onAllNodesWithText(dateLabel).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("home-list").performScrollToNode(hasText(dateLabel))
        ui.onNodeWithText(dateLabel).assertIsDisplayed()
        capture("home-valid-age-no-plan")
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("今天没有固定服药计划"))
        ui.onNodeWithText("今天没有固定服药计划").assertIsDisplayed()
    }

    @Test fun homeMedicationSummaryHasOneIntegratedPendingEntry() {
        seedMedicationPlan()
        launchApp()
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("今日服药记录"))
        ui.waitUntil(10_000) {ui.onAllNodesWithText("下一项",substring=true).fetchSemanticsNodes().isNotEmpty()}
        ui.onAllNodesWithText("用药待确认",substring=true).assertCountEquals(1)
        ui.onNodeWithText("查看服药记录").assertExists()
        capture("home-medication-summary")
        ui.onNodeWithText("查看服药记录").performScrollTo().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("服药记录").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("今天的服药"))
        ui.onNodeWithText("今天的服药").assertExists()
        ui.onNodeWithText("补记服药").assertExists()
    }

    @Test fun timelineSeparatesUpcomingAppointmentsFromRecordsAndCancelledBookings() {
        seedShortVisit(items="较远的合成预约")
        runBlocking {
            repository.saveVisit(null,pregnancyId,LocalDate.now().plusDays(2),null,"","最近的合成预约","")
            val cancelled=repository.saveVisit(null,pregnancyId,LocalDate.now().plusDays(1),null,"","已取消的合成预约","")
            repository.setVisitCancelled(cancelled,true)
        }
        seedJournalEntry(text="合成回顾备注")
        val pregnancy=runBlocking {db.journalDao().pregnancy(pregnancyId)}!!
        page("孕期时间线") {TimelineScreen(pregnancy,"",model,{},{})}
        ui.waitUntil(10_000) {ui.onAllNodesWithText("即将到来的预约").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasText("最近的合成预约",substring=true))
        val recent=ui.onNodeWithText("最近的合成预约",substring=true).fetchSemanticsNode().positionInRoot.y
        if(context.resources.configuration.fontScale<=1.3f && context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            val later=ui.onNodeWithText("较远的合成预约",substring=true).fetchSemanticsNode().positionInRoot.y
            org.junit.Assert.assertTrue("最近预约应先显示",recent<later)
        } else {
            ui.onNodeWithTag("timeline-list").performScrollToNode(hasText("较远的合成预约",substring=true))
            ui.onNodeWithText("较远的合成预约",substring=true).assertIsDisplayed()
        }
        capture("timeline-upcoming")
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasText("历史与记录"))
        ui.onNodeWithText("历史与记录").assertIsDisplayed()
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasText("已取消的合成预约",substring=true))
        ui.onNodeWithText("取消一次产检",substring=true).assertExists()
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasText("合成回顾备注"))
        ui.onNodeWithText("合成回顾备注").assertIsDisplayed()
        capture("timeline-recorded")
    }

    @Test fun documentCategoryAndSortShowTheirCurrentSelection() {
        val document=importFile()
        page("产检资料") {DocumentScreen(document.visitId!!,model)}
        ui.onNodeWithText("全部分类").performScrollTo().performClick()
        ui.onNode(hasText("全部分类") and isSelected()).assertExists()
        capture("document-selected-category",dialog=true)
        ui.onNodeWithText("其他").performClick()
        ui.onNodeWithText("其他").performScrollTo().performClick()
        ui.onNode(hasText("其他") and isSelected()).assertExists()
        ui.onNode(hasText("全部分类") and hasClickAction()).performClick()
        ui.onNodeWithText("最近导入").performScrollTo().assertIsDisplayed().performClick()
        ui.onNode(hasText("最近导入") and isSelected()).assertExists()
        capture("document-selected-sort",dialog=true)
        ui.onNodeWithText("名称顺序").performClick()
        ui.onNodeWithText("名称顺序").performScrollTo().assertIsDisplayed().performClick()
        ui.onNode(hasText("名称顺序") and isSelected()).assertExists()
    }

    @Test fun profileGroupsRemainReachable() {
        launchApp()
        openTab("我的","profiles-root-list")
        ui.onNodeWithText("档案与记录").assertIsDisplayed()
        capture("profiles-records")
        ui.onNodeWithText("数据保护").performScrollTo().assertIsDisplayed()
        capture("profiles-protection")
        listOf("全局回收站","云盘连接","本地备份","导出资料","偏好与提醒","提醒设置").forEach {
            ui.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        capture("profiles-preferences")
    }

    @Test fun medicationEditor() {
        val plan = seedMedicationPlan()
        runBlocking { model.editMedication(plan, pregnancyId).join() }
        page("修改用药方案") {
            val form by model.medicationForm.collectAsStateWithLifecycle()
            MedicationEditorScreen(form, model, false)
        }
        capture("medication-editor-top")
        assertSingleLineFieldLabel("每次剂量", "medication-dose")
        assertSingleLineFieldLabel("单位", "medication-unit")
        capture("medication-dose-labels")
        ui.onNodeWithText("保存用药方案").performScrollTo().assertIsDisplayed()
        capture("medication-editor-bottom")
    }

    @Test fun medicationHistory() {
        val plan = seedMedicationPlan()
        val logicalId = runBlocking { db.journalDao().medicationPlan(plan)!!.logicalPlanId }
        page("用药历史版本") { MedicationHistoryScreen(logicalId, model) }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("第1版 · 进行中").fetchSemanticsNodes().isNotEmpty() }
        capture("medication-history")
    }

    @Test fun intakeEditor() {
        seedMedicationPlan()
        runBlocking { model.editIntake(pregnancyId, null, null, "TAKEN", LocalDate.now().toString()).join() }
        page("补记服药") {
            val form by model.intakeForm.collectAsStateWithLifecycle()
            IntakeEditorScreen(form, model, false)
        }
        capture("intake-editor-top")
        assertSingleLineFieldLabel("实际剂量", "intake-dose")
        assertSingleLineFieldLabel("单位", "intake-unit")
        capture("intake-dose-labels")
        ui.onNodeWithText("保存服药记录").performScrollTo().assertIsDisplayed()
        capture("intake-editor-bottom")
    }

    @Test fun packagingPhotosAndImportOptions() {
        val photo = importFile(medication = true)
        page("药品包装照片") { DocumentScreen(photo.medicationId!!, model, medication = true) }
        ui.onNodeWithTag("document-list").performScrollToNode(hasText("合成包装照片"))
        ui.onNodeWithText("合成包装照片").assertIsDisplayed()
        capture("drug-photos")
        ui.onNodeWithTag("document-list").performScrollToNode(hasTestTag("document-add"))
        settle()
        capture("drug-photo-add-before-click")
        ui.onNodeWithTag("document-add").assertIsDisplayed().performClick()
        ui.onNode(hasText("添加包装照片") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        ui.onNodeWithText("选图片").assertIsDisplayed()
        capture("drug-photo-import-options", dialog = true)
        ui.onNodeWithText("选 PDF").assertDoesNotExist()
    }

    @Test fun imagePreview() {
        val photo = importFile(medication = true)
        page("药品包装照片") { DocumentPreviewDialog(photo, model, {}, {}) }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("document-image").fetchSemanticsNodes().isNotEmpty() }
        capture("image-preview", dialog = true)
    }

    @Test fun pdfPreview() {
        val pdf = importFile(pdf = true)
        page("产检资料") { DocumentPreviewDialog(pdf, model, {}, {}) }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("document-pdf").fetchSemanticsNodes().isNotEmpty() }
        if(context.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            ui.onNodeWithTag("pdf-viewport").assertHeightIsAtLeast(80.dp)
            ui.onNodeWithTag("pdf-zoom-menu").assertIsDisplayed().performClick()
            ui.onNodeWithText("放大").performClick()
            ui.onNodeWithText("200%").assertIsDisplayed()
            ui.onNodeWithTag("pdf-zoom-menu").performClick()
            ui.onNodeWithText("适合页面").performClick()
            ui.onNodeWithText("缩放").assertIsDisplayed()
        }
        capture("pdf-preview", dialog = true)
    }

    @Test fun allDocuments() {
        importFile()
        page("全部检查资料") { AllDocumentsScreen(pregnancyId, model, {}) }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("合成检查资料", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("all-documents")
    }

    @Test fun measurementTrendAndDefinitionDialog() {
        val pregnancy = runBlocking {
            repeat(3) { index ->
                val form = repository.measurements.form(pregnancyId)
                repository.measurements.save(form.copy(date = LocalDate.now().minusDays((index + 1).toLong()).toString(),
                    time = "09:00", values = mapOf("value" to (56 + index).toString())))
            }
            db.journalDao().pregnancy(pregnancyId)
        }
        page("健康指标") { MeasurementScreen(pregnancy, model, {}) }
        ui.onNodeWithText("分组趋势").performClick()
        ui.onNodeWithTag("measurement-list").performScrollToNode(hasText("3条原始读数"))
        capture("measurement-trend")
        ui.onNodeWithTag("measurement-list").performScrollToNode(hasText("最近一条：56 kg",substring=true))
        ui.onNodeWithText("最近一条：56 kg",substring=true).assertIsDisplayed()
        capture("measurement-trend-values")
        ui.onNodeWithTag("measurement-list").performScrollToNode(hasContentDescription("测量散点趋势，详细数值见下方列表"))
        ui.onNodeWithContentDescription("测量散点趋势，详细数值见下方列表").performTouchInput {click(androidx.compose.ui.geometry.Offset(width-16f,height-16f))}
        ui.onNodeWithText("测量值：56 kg").assertIsDisplayed()
        capture("measurement-trend-detail",dialog=true)
        ui.onNodeWithText("关闭").performClick()
        ui.onNodeWithTag("measurement-list").performScrollToNode(hasText("自定义指标"))
        ui.onNodeWithText("自定义指标").performClick()
        capture("metric-definition", dialog = true)
    }

    @Test fun restoreStates() {
        var state by mutableStateOf(RestoreUiState(stage = "CLOUD"))
        page("恢复备份") { key(state.stage) { RestoreContent(state, {}, {}, {}, {}, {}, {}, {}) } }
        capture("restore-cloud-key")
        ui.runOnIdle { state = RestoreUiState(stage = "CLOUD_LIST", cloud = true, cloudSnapshots = listOf(
            SnapshotRef("synthetic-snapshot", System.currentTimeMillis(), "synthetic-writer", 8, emptySet()))) }
        capture("restore-cloud-list")
        val preview = RestorePreview("synthetic-generation", "synthetic-snapshot", 1, 1, 2, 3, 4, 5, 6, 7, "synthetic-writer", 8)
        ui.runOnIdle { state = RestoreUiState(stage = "CONFIRM", preview = preview) }
        capture("restore-confirm-top")
        ui.onNodeWithTag("restore-confirm").performScrollTo().assertIsNotEnabled()
        capture("restore-confirm-bottom")
        ui.runOnIdle { state = RestoreUiState(stage = "VERIFYING") }
        capture("restore-verifying")
        ui.onNodeWithTag("restore-cancel").assertIsDisplayed()
        ui.runOnIdle { state = RestoreUiState(stage = "RESTORING") }
        capture("restore-restoring")
        ui.runOnIdle { state = RestoreUiState(stage = "DONE") }
        capture("restore-done")
        ui.runOnIdle { state = RestoreUiState(stage = "ERROR", message = "备份校验未通过，当前资料已保留。") }
        capture("restore-error")
    }

    @Test fun selectedBackupRemainsVisibleAtKeyEntryAndConfirmation() {
        val name="合成产检资料备份-2026-11-15.yunji"
        var state by mutableStateOf(RestoreUiState(stage="KEY",documentName=name,documentSize=1024*1024))
        page("恢复备份") {RestoreContent(state,{},{},{},{},{},{},{})}
        ui.onNodeWithText(name).performScrollTo().assertIsDisplayed()
        capture("restore-selected-file")
        ui.onNodeWithTag("restore-key").performScrollTo().assertIsDisplayed()
        ui.runOnIdle {state=state.copy(stage="CONFIRM",preview=RestorePreview("synthetic-generation","synthetic-snapshot",1,1,2,3,4,5,6,7,"synthetic-writer",8))}
        ui.onNodeWithText(name).performScrollTo().assertIsDisplayed()
        capture("restore-confirm-file")
    }

    @Test fun recoveryVerificationAndSafetyCopies() {
        var verifying by mutableStateOf(true)
        var confirming by mutableStateOf(false)
        page("本地加密备份") {
            if (verifying) RecoveryContent(RecoveryUiState(stage = "VERIFY", groups = listOf(2, 5)), {}, {}, { _, _ -> }, {}, {})
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                SafetyCopiesContent(listOf(SafetyCopy("synthetic-copy", 1024 * 1024, System.currentTimeMillis())),
                    loading = false, busy = false, message = null, refresh = {}, export = {}, delete = {})
                ReclaimPreviousContent(ReclaimPanelState(available = true, confirming = confirming), {}, {}, {})
            }
        }
        capture("recovery-verify")
        ui.onNodeWithTag("recovery-confirm").assertIsNotEnabled()
        ui.runOnIdle { verifying = false }
        capture("safety-copy-reclaim")
        ui.runOnIdle { confirming = true }
        ui.onNodeWithTag("reclaim-cancel").assertIsDisplayed()
        ui.runOnIdle { verifying = true }
    }

    @Test fun welcomeAndDisabledComponents() {
        var welcome by mutableStateOf(true)
        page(null) {
            if (welcome) WelcomeScreen({}) else Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("禁用操作对照")
                JournalTextAction("删除（禁用）", enabled = false, danger = true, onClick = {})
                JournalTextAction("删除（可用）", danger = true, onClick = {})
                JournalTag(true, {}, { Text("已选中（禁用）") }, enabled = false)
                JournalTag(true, {}, { Text("已选中（可用）") })
            }
        }
        capture("welcome")
        ui.runOnIdle { welcome = false }
        capture("disabled-components")
        ui.onNodeWithText("删除（禁用）").assertIsNotEnabled()
    }

    @Test fun originalSharingKeepsLongNameAndDisclosureReachable() {
        val name = "合成检查资料名称".repeat(20)
        page("产检资料") { OriginalShareDialog(name, false, {}, {}) }
        ui.onNodeWithTag("confirm-share").assertIsDisplayed()
        ui.onNodeWithText("只授予本次只读访问", substring = true).performScrollTo().assertIsDisplayed()
        capture("original-share-long-name", dialog = true)
        assertNoTruncation(hasText("只授予本次只读访问", substring = true), "分享授权与清理说明")
    }

    @Test fun longDrugListShowsActionsNextToChosenDrug() {
        runBlocking { repeat(12) { repository.medications.saveDrug(DrugForm(pregnancyId = pregnancyId, name = "合成药品${it + 1}")) } }
        val drugs = runBlocking { db.journalDao().allMedications() }
        page("管理药品") { DrugCatalogScreen(pregnancyId, model, {}, {}) }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("drug-${drugs[0].id}").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("drug-${drugs[0].id}").performClick().assertIsSelected()
        ui.onNodeWithTag("edit-drug-${drugs[0].id}").performScrollTo().assertIsDisplayed()
        capture("drug-catalog-long")
        val last = drugs.last()
        ui.onNodeWithTag("drug-catalog").performScrollToNode(hasTestTag("drug-${last.id}"))
        ui.onNodeWithTag("drug-${last.id}").performClick().assertIsSelected()
        ui.onNodeWithTag("drug-catalog").performScrollToNode(hasTestTag("edit-drug-${last.id}"))
        ui.onNodeWithTag("edit-drug-${last.id}").assertIsDisplayed()
        capture("drug-catalog-selected-last")
    }

    private fun importFile(medication: Boolean = false, pdf: Boolean = false): AttachmentEntity {
        val source = if (pdf) File(context.cacheDir, "capture/${UUID.randomUUID()}.pdf").also { file ->
            file.parentFile!!.mkdirs()
            val document = PdfDocument()
            try {
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 900, 1).create())
                page.canvas.drawText("Synthetic report", 40f, 100f, Paint().apply { textSize = 26f })
                document.finishPage(page)
                file.outputStream().use(document::writeTo)
            } finally { document.close() }
        } else createPngSource()
        return try { runBlocking {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.capture", source)
            if (medication) {
                val drug = repository.medications.saveDrug(DrugForm(pregnancyId = pregnancyId, name = "合成药品"))
                model.documents.enqueueMedication(drug, listOf(uri), schedule = false)
            } else model.documents.enqueue(seedShortVisit(), listOf(uri), schedule = false)
            val id = db.journalDao().pendingImports().single().id
            model.documents.process(id)
            model.documents.updateMetadata(id, if (medication) "合成包装照片" else "合成检查资料", if (medication) "PACKAGE" else "OTHER")
            db.journalDao().attachment(id)!!
        } } finally { if (pdf) source.delete() }
    }

    private fun page(title: String?, content: @Composable () -> Unit) {
        ui.setContent { PregnancyTheme { Surface(Modifier.fillMaxSize()) { JournalPage(title, back = if (title == null) null else ({})) { content() } } } }
        settle()
    }

    private fun assertSingleLineFieldLabel(label: String, fieldTag: String) {
        ui.onNodeWithTag(fieldTag).performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
            val layouts = mutableListOf<TextLayoutResult>()
            read(layouts)
            org.junit.Assert.assertTrue(layouts.isNotEmpty())
            layouts.forEach { org.junit.Assert.assertEquals("浮动标签不得换行侵占上方内容：$label", 1, it.lineCount) }
        }
    }

    private fun capture(name: String, dialog: Boolean = false) {
        settle()
        val bitmap = if (dialog) requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else ui.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.getExternalFilesDir(null), "verification/ui-review-remaining").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
