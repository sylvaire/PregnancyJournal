package cn.yunji.journal

import android.os.SystemClock
import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunji.journal.core.database.JournalDatabase
import cn.yunji.journal.core.designsystem.PregnancyTheme
import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.data.ActivePregnancyPreferences
import cn.yunji.journal.data.JournalRepository
import cn.yunji.journal.feature.JournalApp
import cn.yunji.journal.feature.JournalViewModel
import cn.yunji.journal.feature.VisitForm
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** All UI tests use a private throwaway database and in-memory preferences, including on real phones. */
class NavigationExperienceTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: JournalDatabase
    private lateinit var file: File
    private lateinit var model: JournalViewModel
    private lateinit var repository: JournalRepository
    private lateinit var pregnancyId: String
    private lateinit var documentRoot: File
    private val documentSources = mutableListOf<File>()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        file = File(context.cacheDir, "m2-navigation-${UUID.randomUUID()}.db")
        db = JournalDatabase.open(context, file, VaultCrypto.randomKey())
        val repo = JournalRepository(db, Clock.systemDefaultZone())
        repository = repo
        val id = runBlocking { repo.savePregnancy(null, "导航测试孕期", null, null, "", "") }
        pregnancyId = id
        runBlocking {
            val month = YearMonth.now()
            repo.saveVisit(null, id, month.atEndOfMonth(), null, "", "月末测试", "")
            repo.saveVisit(null, id, month.plusMonths(1).atDay(1), null, "", "下月测试", "")
        }
        val preferences = object : ActivePregnancyPreferences {
            override val activePregnancy = MutableStateFlow<String?>(id)
            override suspend fun selectPregnancy(id: String) { activePregnancy.value = id }
        }
        documentRoot = File(context.cacheDir, "navigation-documents-${UUID.randomUUID()}").apply { mkdirs() }
        val importContext = object : android.content.ContextWrapper(context) { override fun getNoBackupFilesDir() = documentRoot }
        val fixtureImporter by lazy {
            cn.yunji.journal.data.DocumentImporter(importContext, db,
                cn.yunji.journal.core.security.VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey()), Clock.systemUTC())
        }
        val fixtureReminders by lazy {
            cn.yunji.journal.data.ReminderScheduler(context, db, Clock.systemUTC(),
                cn.yunji.journal.data.ReminderStore(context), cn.yunji.journal.data.ReminderNotifier(context)).also { it.requestRebuildEnabled = false }
        }
        val fixtureCloud by lazy {
            val material=cn.yunji.journal.core.security.VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
            cn.yunji.journal.data.CloudConnectionRepository(cn.yunji.journal.data.CloudConnectionStore(importContext,material),material.id,Clock.systemUTC())
        }
        val fixtureBackup by lazy {
            object : cn.yunji.journal.data.CloudBackupAccess {
                override fun status() = cn.yunji.journal.data.CloudUploadStatus()
                override fun isRunning() = false
                override suspend fun upload() = error("Cloud upload must not run in navigation tests")
            }
        }
        val fixtureRecovery by lazy {
            val material=cn.yunji.journal.core.security.VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
            cn.yunji.journal.data.RecoverySettings(importContext,material)
        }
        val fixtureCleanupStatus by lazy { cn.yunji.journal.data.CloudCleanupStatusStore(importContext) }
        val fixtureCleanupAccess by lazy {
            object : cn.yunji.journal.data.CloudCleanupAccess {
                override suspend fun runOnce() = error("Cloud cleanup must not run in navigation tests")
            }
        }
        val fixtureExport by lazy {
            object : cn.yunji.journal.data.ExportAccess {
                override suspend fun preview(request: cn.yunji.journal.data.ExportRequest): Map<String, Int> =
                    error("Export must not run in navigation tests")
                override suspend fun exportData(destination: android.net.Uri, request: cn.yunji.journal.data.ExportRequest): cn.yunji.journal.data.ExportResult =
                    error("Export must not run in navigation tests")
                override suspend fun exportSummary(destination: android.net.Uri, request: cn.yunji.journal.data.ExportRequest): cn.yunji.journal.data.ExportResult =
                    error("Export must not run in navigation tests")
            }
        }
        model = JournalViewModel(repo, SavedStateHandle(), preferences, dagger.Lazy { fixtureImporter }, dagger.Lazy { fixtureReminders },dagger.Lazy {fixtureCloud},dagger.Lazy {fixtureRecovery},dagger.Lazy {fixtureBackup},dagger.Lazy {fixtureExport},dagger.Lazy {fixtureCleanupAccess},dagger.Lazy {fixtureCleanupStatus})
        ui.setContent { PregnancyTheme { JournalApp(model) } }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("今天，慢慢来").fetchSemanticsNodes().isNotEmpty() }
        settle()
    }

    @After fun tearDown() {
        // Dispose UI flow collectors before closing their private SQLCipher database.
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
        if (::model.isInitialized) runBlocking { model.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.let { job -> job.cancel(); job.join() } }
        if (::db.isInitialized) db.close()
        if (::documentRoot.isInitialized) documentRoot.deleteRecursively()
        documentSources.forEach { it.delete() }
        if (::file.isInitialized) listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    @Test fun journalDraftSourceEditDeleteUndoAndTimelineFilterSurviveBack() {
        ui.onNodeWithText("记录",substring=false).performClick();settle()
        ui.onNodeWithText("日常记录",substring=false).performClick();settle()
        // 方案 A：时间线底部 CTA 文案（编辑器标题/保存按钮仍是「写日常记录/保存日常记录」）。
        ui.onNodeWithText("写一条日常备注",substring=false).performClick();settle()
        ui.onNodeWithTag("journal-text").performScrollTo().performTextInput("合成日常正文")
        ui.onNodeWithTag("journal-mood").performScrollTo().performTextInput("平静")
        ui.onNodeWithContentDescription("返回上一级").performClick();settle()
        ui.onNodeWithText("写一条日常备注",substring=false).performClick();settle()
        ui.onNodeWithTag("journal-text").performScrollTo().assertTextContains("合成日常正文")
        clickSaveAfterKeyboardSettles("保存日常记录")
        ui.waitUntil(5000){runBlocking {db.journalDao().allJournalEntries().size==1}}
        val row=runBlocking {db.journalDao().allJournalEntries().single()}
        assertTrue(row.entryTime==null)
        val tag="source-JOURNAL:${row.id}"
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasTestTag(tag))
        ui.onNodeWithTag(tag).performClick();settle()
        ui.onNodeWithText("${cn.yunji.journal.feature.displayDate(row.entryDate)} · 时间未记录").assertExists()
        ui.onNodeWithText("查看完整原始内容").performClick();settle()
        ui.onNodeWithText("心情：平静").assertExists()
        ui.onNodeWithText("修改原始记录").performScrollTo().performClick();settle()
        ui.onNodeWithTag("journal-text").performScrollTo().performTextReplacement("修订合成正文")
        settle()
        clickSaveAfterKeyboardSettles("保存日常记录")
        ui.waitUntil(5000){runBlocking {db.journalDao().journalEntry(row.id)?.text=="修订合成正文"}}
        ui.onNodeWithTag("source-delete").performScrollTo().performClick();settle()
        ui.onNodeWithTag("confirm-source-delete").performClick();settle()
        ui.onNodeWithText("撤销",substring=false).performClick();settle()
        ui.waitUntil(5000){runBlocking {db.journalDao().journalEntry(row.id)?.deletedAt==null}}
        ui.onNodeWithContentDescription("返回上一级").performClick();settle()
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasTestTag("timeline-filter-JOURNAL"))
        ui.onNodeWithTag("timeline-filter-JOURNAL").assertIsSelected()
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasText("修订合成正文"))
        ui.onNodeWithText("修订合成正文",substring=false).assertExists()
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val shot=File(context.getExternalFilesDir(null),"verification/c04-journal.png").apply {parentFile!!.mkdirs()}
        shot.outputStream().use {ui.onNodeWithTag("日常记录").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    /**
     * 方案 A（设计 12-Timeline）时间线深链重写：
     * - 时间线只保留 全部/产检/日常 三个筛选，VISIT/JOURNAL 行可打开原始记录，底部 CTA 为「写一条日常备注」；
     * - 旧的 PLAN/INTAKE/MEASUREMENT/DOCUMENT 专属筛选与深链移除（这些事件仍在「全部」以记录行降级展示，
     *   各自的原始记录页仍由模块自身测试覆盖）；
     * - 资料的元数据编辑 / 原件预览覆盖迁移到「记录枢纽 → 全部资料」路由的独立用例
     *   [recordsHubAllDocumentsKeepsMetadataEditAndOriginalPreview]，不降低覆盖。
     */
    @Test fun timelineShowsVisitAndJournalRecordsWithThreeFilters() {
        val visit=runBlocking {db.journalDao().allVisits().first()}
        val today=LocalDate.now().toString()
        val journalId=runBlocking {
            repository.savePregnancy(pregnancyId,"导航测试孕期",null,LocalDate.now().plusDays(120),"","")
            repository.journalEntries.save(cn.yunji.journal.data.JournalEntryForm(
                pregnancyId=pregnancyId,date=today,time="09:30",zoneId="Asia/Shanghai",
                symptom="合成时间线症状",mood="平静",text="合成时间线正文"))
        }
        ui.onNodeWithText("记录",substring=false).performClick();settle()
        ui.onNodeWithText("孕期时间线").performClick();settle()
        ui.waitUntil(10_000){ui.onAllNodes(hasTestTag("timeline-list")).fetchSemanticsNodes().isNotEmpty()}
        ui.waitUntil(10_000){ui.onAllNodesWithText("写一条日常备注").fetchSemanticsNodes().isNotEmpty()}
        // 头部 tagline（返回键保留，副标题按设计改为 tagline）
        ui.onNodeWithText("把一次次记录，连成自己的故事").assertExists()
        // 3 个筛选保留；旧类型专属筛选移除
        listOf("","VISIT","JOURNAL").forEach { key ->
            ui.onNodeWithTag("timeline-list").performScrollToNode(hasTestTag("timeline-filter-$key"))
            ui.onNodeWithTag("timeline-filter-$key").assertExists()
        }
        listOf("DOCUMENT","PLAN","INTAKE","MEASUREMENT").forEach { key ->
            assertTrue("时间线不应再有 $key 专属筛选",ui.onAllNodesWithTag("timeline-filter-$key").fetchSemanticsNodes().isEmpty())
        }
        // 日期分组右侧的孕周（测试孕期 EDD=今天+120 天，事件日期=今天 -> 孕22周+6天）
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasText("孕",substring=true))
        ui.waitUntil(5_000){ui.onAllNodesWithText("孕22周 + 6天").fetchSemanticsNodes().isNotEmpty()}
        // 全部（默认）视图：产检记录行可点开产检详情
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasTestTag("timeline-VISIT:${visit.id}"))
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasTestTag("source-VISIT:${visit.id}"))
        ui.onNodeWithTag("source-VISIT:${visit.id}").performClick();settle()
        ui.onNodeWithTag("产检详情").assertExists()
        ui.onNodeWithContentDescription("返回上一级").performClick();settle()
        // 产检筛选：只剩 VISIT 行
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasTestTag("timeline-filter-VISIT"))
        ui.onNodeWithTag("timeline-filter-VISIT").performScrollTo().performClick();settle()
        ui.onNodeWithTag("timeline-filter-VISIT").assertIsSelected()
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasTestTag("timeline-VISIT:${visit.id}"))
        assertTrue("产检筛选下不应出现日常记录行",ui.onAllNodesWithTag("timeline-JOURNAL:$journalId").fetchSemanticsNodes().isEmpty())
        // 日常筛选：杏色备注卡，可打开原始记录
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasTestTag("timeline-filter-JOURNAL"))
        ui.onNodeWithTag("timeline-filter-JOURNAL").performScrollTo().performClick();settle()
        ui.onNodeWithTag("timeline-filter-JOURNAL").assertIsSelected()
        ui.onNodeWithTag("timeline-list").performScrollToNode(hasTestTag("source-JOURNAL:$journalId"))
        ui.onNodeWithTag("source-JOURNAL:$journalId").performClick();settle()
        // N04 改版后症状与心情合并为一行摘要；未展开时不出现“症状：”前缀。
        ui.onNodeWithText("合成时间线症状 · 平静").assertExists()
        // 原始记录页与仍在组合中的时间线卡可能同时在语义树里，因此这里只断言内容存在、不限定唯一节点。
        assertTrue("原始记录页应显示日常正文",ui.onAllNodesWithText("合成时间线正文").fetchSemanticsNodes().isNotEmpty())
        // 展开“查看完整原始内容”后仍应保留带前缀的原始字段。
        ui.onNodeWithText("查看完整原始内容").performClick(); settle()
        ui.onNodeWithText("症状：合成时间线症状").assertExists()
        ui.onNodeWithContentDescription("返回上一级").performClick();settle()
        // 底部 CTA 进入日常记录编辑器（编辑器标题/保存按钮文案不变）
        ui.onNodeWithText("写一条日常备注").performClick();settle()
        ui.waitUntil(10_000){ui.onAllNodes(hasTestTag("journal-text")).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("写日常记录").assertExists()
        ui.onNodeWithContentDescription("返回上一级").performClick();settle()
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val shot=File(context.getExternalFilesDir(null),"verification/c04-timeline.png").apply {parentFile!!.mkdirs()}
        shot.outputStream().use {ui.onNodeWithTag("孕期时间线").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    /**
     * 资料元数据编辑 / 原件预览覆盖迁移：方案 A 下「全部资料」不再走 TimelineScreen，
     * 改为记录枢纽 → 全部资料 → 资料行 → 原始记录页（编辑资料信息/查看原件）。
     */
    @Test fun recordsHubAllDocumentsKeepsMetadataEditAndOriginalPreview() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val visit=runBlocking {db.journalDao().allVisits().first()}
        val file=File(context.cacheDir,"capture/${UUID.randomUUID()}.png").apply {parentFile!!.mkdirs()}
        documentSources.add(file)
        val bitmap=android.graphics.Bitmap.createBitmap(100,160,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        file.outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val documentId=runBlocking {
            model.documents.enqueue(visit.id,listOf(androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.capture",file)),schedule=false)
            val id=db.journalDao().pendingImports().single().id
            model.documents.process(id)
            model.documents.updateMetadata(id,"合成全部资料报告","LAB",LocalDate.now().minusDays(1).toString(),"测试医院","合成报告备注")
            id
        }
        ui.onNodeWithText("记录",substring=false).performClick();settle()
        ui.onNodeWithText("全部资料").performClick();settle()
        ui.waitUntil(10_000){ui.onAllNodes(hasTestTag("all-documents-list")).fetchSemanticsNodes().isNotEmpty()}
        val tag="source-DOCUMENT:$documentId"
        ui.onNodeWithTag("all-documents-list").performScrollToNode(hasTestTag(tag))
        ui.onNodeWithTag(tag).performClick();settle()
        ui.onNodeWithText("医院：测试医院").assertExists()
        ui.onNodeWithText("编辑资料信息").performScrollTo().performClick();settle()
        ui.onNodeWithTag("document-note").performScrollTo().performTextReplacement("尚未保存合成备注")
        runBlocking {model.documents.updateMetadata(documentId,"合成全部资料报告","LAB",note="外部更新合成备注")}
        settle()
        ui.onNodeWithText("保存",substring=false).performClick();settle()
        ui.waitUntil(5000){ui.onAllNodesWithText("资料已变化，请关闭后重新打开编辑").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("document-note").assertTextContains("尚未保存合成备注")
        ui.onNodeWithText("取消",substring=false).performClick();settle()
        ui.onNodeWithText("编辑资料信息").performScrollTo().performClick();settle()
        ui.onNodeWithTag("document-note").performScrollTo().performTextReplacement("重新打开后保存合成备注")
        ui.onNodeWithText("保存",substring=false).performClick();settle()
        ui.waitUntil(5000){runBlocking {db.journalDao().attachment(documentId)?.note=="重新打开后保存合成备注"}}
        ui.onNodeWithText("查看原件").performScrollTo().performClick();settle()
        ui.waitUntil(10000){ui.onAllNodesWithTag("document-image").fetchSemanticsNodes().isNotEmpty()}
        // 同上：全屏 Dialog 用 fullscreen-navigate-up 精确定位。
        ui.onNodeWithTag("fullscreen-navigate-up").performClick();settle()
        ui.onNodeWithTag("原始记录").assertExists()
        ui.onNodeWithContentDescription("返回上一级").performClick();settle()
        ui.onNodeWithTag("全部资料").assertExists()
        ui.onNodeWithContentDescription("返回上一级").performClick();settle()
        ui.onNodeWithTag("records-hub-list").assertExists()
    }

    @Test fun measurementDraftTrendDetailDeleteAndUndoKeepExactValue() {
        ui.onNodeWithText("我的").performClick(); settle()
        ui.onNodeWithText("健康指标").performScrollTo().performClick(); settle()
        ui.onNodeWithText("记录测量").performClick(); settle()
        // 全量矩阵高负载下测量编辑器首帧可能稍慢；先等待字段组合再输入（仅同步，不放宽断言）。
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("measurement-value").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("measurement-value").performTextInput("60.125000")
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithText("记录测量").performClick(); settle()
        ui.onNodeWithTag("measurement-value").assertTextContains("60.125000")
        ui.onNodeWithText("保存测量").performScrollTo().performClick(); settle()
        ui.waitUntil(5_000) {runBlocking {db.journalDao().allMeasurements().size==1}}
        val record=runBlocking {db.journalDao().allMeasurements().single()}
        ui.onNodeWithText("测量值 60.125 kg").assertExists()
        ui.onNodeWithText("分组趋势").performClick(); settle()
        ui.onNodeWithText("1条原始读数").assertExists()
        ui.onNodeWithText("原始记录").performClick(); settle()
        ui.onNodeWithText("详情",substring=false).performClick(); settle()
        ui.onNodeWithText("测量值：60.125 kg").assertExists()
        ui.onNodeWithText("关闭",substring=false).performClick(); settle()
        ui.onNodeWithText("删除",substring=false).performClick(); settle()
        ui.onNodeWithTag("confirm-measurement-delete").performClick(); settle()
        ui.onNodeWithText("撤销").performClick(); settle()
        ui.waitUntil(5_000) {runBlocking {db.journalDao().measurement(record.id)?.deletedAt==null}}
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val shot=File(context.getExternalFilesDir(null),"verification/c03-measurement.png").apply {parentFile!!.mkdirs()}
        shot.outputStream().use {ui.onNodeWithTag("健康指标").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun scheduledIntakeEditDeleteUndoAndManualHistoryKeepOriginalPlan() {
        val date = java.time.LocalDate.now()
        val planId = runBlocking {
            val id = model.medications.save(cn.yunji.journal.data.MedicationForm(pregnancyId = pregnancyId, name = "合成服药测试药品", dose = "0.125", unit = "片", times = "08:00", startDate = date.toString()))
            val plan = requireNotNull(db.journalDao().medicationPlan(id))
            db.journalDao().updateMedicationPlan(plan.copy(effectiveAt = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
            model.intakes.ensureDay(pregnancyId, date, java.time.ZoneId.systemDefault())
            id
        }
        val point = runBlocking { db.journalDao().occurrencesForDate(pregnancyId, date.toString()).single() }
        ui.onNodeWithText("我的").performClick(); settle()
        ui.onNodeWithText("服药记录").performScrollTo().performClick(); settle()
        listOf("今天", "历史", "方案", "补记").forEach { ui.onNodeWithText(it, substring = false).assertExists() }
        ui.waitUntil(10_000) {
            runCatching { ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("intake-point-${point.id}")) }.isSuccess
        }
        val dayShot = File(ApplicationProvider.getApplicationContext<android.content.Context>().getExternalFilesDir(null), "verification/c02-day.png").apply { parentFile!!.mkdirs() }
        dayShot.outputStream().use { ui.onNodeWithTag("服药记录").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithText("方案", substring = false).performClick(); settle()
        ui.onNodeWithText("用药方案", substring = false).assertExists()
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithTag("intake-day-search").performTextReplacement("不匹配的药名")
        ui.onNodeWithText("没有匹配的服药计划").assertExists()
        ui.onNodeWithTag("intake-day-search").performTextReplacement("")
        // Wait for the asynchronous list refresh, then expand the planned row.
        ui.waitUntil(10_000) {
            runCatching {
                ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("intake-point-${point.id}"))
            }.isSuccess
        }
        ui.onNodeWithTag("intake-point-${point.id}").performClick(); settle()
        ui.onNodeWithTag("take-${point.id}").assertExists()
        ui.onNodeWithTag("take-${point.id}").performClick(); settle()
        ui.onNodeWithTag("intake-dose").performScrollTo().performTextReplacement("0.25")
        settle()
        clickSaveAfterKeyboardSettles("保存服药记录")
        try {
            ui.waitUntil(10_000) { runBlocking { db.journalDao().allIntakes().size == 1 } }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val count = runBlocking { db.journalDao().allIntakes().size }
            val ready = model.formReady.value
            val busy = model.busy.value
            val pointState = runBlocking { db.journalDao().occurrence(point.id)?.state }
            throw AssertionError("服药保存未完成：记录数=$count；ready=$ready；busy=$busy；计划状态=$pointState；界面=${ui.onRoot().printToString()}", timeout)
        }
        val record = runBlocking { db.journalDao().allIntakes().single() }
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("历史", substring = false))
        ui.onNodeWithText("历史", substring = false).performClick(); settle()
        val compactShot = File(ApplicationProvider.getApplicationContext<android.content.Context>().getExternalFilesDir(null), "verification/c02-history-collapsed.png").apply { parentFile!!.mkdirs() }
        compactShot.outputStream().use { ui.onNodeWithTag("服药记录").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithTag("intake-history-${record.id}").performScrollTo().performClick(); settle()
        ui.onNodeWithText("实际用量 0.25 片").assertExists()
        ui.onNodeWithText("修改记录").performScrollTo().performClick(); settle()
        ui.onNodeWithTag("intake-dose").performScrollTo().performTextReplacement("0.5")
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithText("修改记录").performScrollTo().performClick(); settle()
        ui.onNodeWithTag("intake-dose").assertTextContains("0.5")
        clickSaveAfterKeyboardSettles("保存服药记录")
        ui.waitUntil(10_000) { runBlocking { db.journalDao().intake(record.id)?.dose == "0.5" } }
        ui.onNodeWithText("删除记录").performScrollTo().performClick(); settle()
        ui.onNodeWithTag("confirm-intake-removal").performClick(); settle()
        ui.onNodeWithText("撤销").performClick(); settle()
        ui.waitUntil(10_000) { runBlocking { db.journalDao().intake(record.id)?.deletedAt == null } }
        ui.onNodeWithText("补记服药").performScrollTo().performClick(); settle()
        val manualShot = File(ApplicationProvider.getApplicationContext<android.content.Context>().getExternalFilesDir(null), "verification/c02-manual-editor.png").apply { parentFile!!.mkdirs() }
        manualShot.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        clickSaveAfterKeyboardSettles("保存服药记录")

        ui.waitUntil(5_000) { runBlocking { db.journalDao().allIntakes().size == 2 } }
        assertTrue(runBlocking { db.journalDao().allIntakes().all { it.planVersionId == planId } })
        assertTrue(runBlocking { db.journalDao().medicationPlan(planId)?.dose == "0.125" })
        ui.onNodeWithTag("intake-history-${record.id}").performScrollTo().performClick(); settle()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shot = File(context.getExternalFilesDir(null), "verification/c02-history.png").apply { parentFile!!.mkdirs() }
        shot.outputStream().use { ui.onNodeWithTag("服药记录").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithText("今天", substring = false).performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("今日服药记录"))
        ui.waitUntil(5_000) { ui.onAllNodesWithText("固定计划1项 · 已服用1项 · 已跳过0项 · 未记录0项").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("另有补记1条").assertExists()
        val homeShot = File(context.getExternalFilesDir(null), "verification/c02-home.png")
        homeShot.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun medicationDraftEditsAndHistoryRetainOldDose() {
        ui.onNodeWithText("我的").performClick(); settle()
        ui.onNodeWithText("用药方案").performScrollTo().performClick(); settle()
        ui.onNodeWithText("新增用药方案").performClick(); settle()
        ui.onNodeWithTag("medication-name").performTextInput("合成测试药品")
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithText("新增用药方案").performClick(); settle()
        ui.onNodeWithTag("medication-name").assertTextContains("合成测试药品")
        ui.onNodeWithTag("medication-dose").performScrollTo().performTextInput("0.125")
        ui.onNodeWithTag("medication-unit").performTextInput("片")
        ui.onNodeWithText("固定时刻，例如08:00,20:00").performScrollTo().performTextInput("08:00,20:00")
        ui.onNodeWithText("保存用药方案").performScrollTo().performClick(); settle()
        // 全量矩阵负载下数据库写入可能晚于 5s；这里等待产品结果而非固定时长。
        ui.waitUntil(20_000) { runBlocking { db.journalDao().allMedicationPlans().size == 1 } }
        ui.onNodeWithText("每次 0.125 片").assertExists()
        ui.onNodeWithText("修改方案").performScrollTo().performClick(); settle()
        ui.onNodeWithTag("medication-dose").performScrollTo().performTextReplacement("0.25")
        ui.onNodeWithTag("medication-dose").assertTextContains("0.25")
        ui.onNodeWithText("保存用药方案").performScrollTo().performClick(); settle()
        ui.waitUntil(20_000) { runBlocking { db.journalDao().allMedicationPlans().size == 2 } }
        ui.onNodeWithText("历史版本").performScrollTo().performClick(); settle()
        ui.onNodeWithText("每次 0.25 片").assertExists()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("每次 0.125 片"))
        ui.onNodeWithText("每次 0.125 片").assertExists()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shot = File(context.getExternalFilesDir(null), "verification/c01-history.png").apply { parentFile!!.mkdirs() }
        shot.outputStream().use { ui.onNodeWithTag("用药历史版本").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithText("暂停", substring = false).performScrollTo().performClick(); settle()
        ui.onNodeWithTag("confirm-medication-status").performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().allMedicationPlans().any { it.version == 3 && it.status == "PAUSED" } } }
        ui.onNodeWithText("恢复方案").assertExists()
        assertTrue(runBlocking { db.journalDao().allMedicationPlans().first { it.version == 1 }.dose == "0.125" })
    }

    @Test fun drugCatalogEditKeepsPlanSnapshotAndPackagePhotosFollowDrugTrash() {
        val planId = runBlocking {
            model.medications.save(cn.yunji.journal.data.MedicationForm(pregnancyId = pregnancyId, name = "合成原药品", specification = "原规格",
                dose = "0.125", unit = "片", times = "08:00", startDate = java.time.LocalDate.now().toString()))
        }
        val drugId = runBlocking { requireNotNull(db.journalDao().medicationPlan(planId)).medicationId }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "capture/合成药盒.png").apply { parentFile!!.mkdirs() }
        documentSources.add(source)
        val bitmap = android.graphics.Bitmap.createBitmap(120, 160, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        runBlocking {
            model.documents.enqueueMedication(drugId, listOf(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.capture", source)), schedule = false)
            val job = db.journalDao().allImportJobs().single { it.medicationId == drugId }
            model.documents.process(job.id)
            model.documents.updateMetadata(job.id, "合成药盒正面", "PACKAGE", note = "合成药盒信息")
        }
        ui.onNodeWithText("我的").performClick(); settle()
        ui.onNodeWithText("用药方案").performScrollTo().performClick(); settle()
        ui.onNodeWithText("管理药品").performClick(); settle()
        ui.onNodeWithTag("drug-$drugId").assertExists()
        ui.onNodeWithTag("edit-drug-$drugId").performClick(); settle()
        ui.onNodeWithTag("drug-name").performScrollTo().performTextReplacement("合成改名药品")
        ui.onNodeWithTag("drug-specification").performScrollTo().performTextReplacement("新规格")
        // The save button sits at the bottom of the form: on a device with different insets an unscrolled
        // click lands outside the viewport and is silently dropped.
        ui.onNodeWithTag("save-drug").performScrollTo().performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().medication(drugId)?.name == "合成改名药品" } }
        ui.onNodeWithText("合成改名药品").assertExists()
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithText("合成原药品").assertExists()
        ui.onNodeWithText("原规格").assertExists()
        ui.onNodeWithText("历史版本").performScrollTo().performClick(); settle()
        ui.onNodeWithText("合成原药品").assertExists()
        ui.onNodeWithText("原规格").assertExists()
        val shot = File(context.getExternalFilesDir(null), "verification/c01left-history.png").apply { parentFile!!.mkdirs() }
        shot.outputStream().use { ui.onNodeWithTag("用药历史版本").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithText("管理药品").performClick(); settle()
        ui.onNodeWithTag("drug-photos-$drugId").performClick(); settle()
        ui.onNodeWithText("合成药盒正面").assertExists()
        val photoShot = File(context.getExternalFilesDir(null), "verification/c01left-photos.png").apply { parentFile!!.mkdirs() }
        photoShot.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithTag("delete-drug-$drugId").performClick(); settle()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("root-deletion-dialog").fetchSemanticsNodes().isNotEmpty() }
        ui.mainClock.advanceTimeBy(600); ui.waitForIdle()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("本药品及1项用药方案、0条服药记录、1份包装照片将移入回收站；此前单独删除的项目不会一并恢复。仍有历史方案引用时，药品保留到所属孕期整体清理。").fetchSemanticsNodes().isNotEmpty() }
        val deleteShot = File(context.getExternalFilesDir(null), "verification/c01left-delete-scope.png").apply { parentFile!!.mkdirs() }
        captureNode(deleteShot, "root-deletion-dialog")
        ui.onNodeWithTag("confirm-root-deletion").performClick(); settle()
        ui.waitUntil(10_000) { runBlocking { db.journalDao().medication(drugId)?.deletedAt != null } }
        assertTrue(runBlocking { db.journalDao().medicationPlan(planId)?.deletedAt != null })
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithText("还没有用药方案").assertExists()
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("全局回收站"))
        ui.onNodeWithText("全局回收站").performClick(); settle()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("全局回收站").fetchSemanticsNodes().isNotEmpty() }
        ui.mainClock.advanceTimeBy(600); ui.waitForIdle()
        val medicationBatchId = runBlocking { db.journalDao().trashBatches().first().single { it.kind == "MEDICATION" }.id }
        val compactTrashShot = File(context.getExternalFilesDir(null), "verification/c01left-trash-compact.png").apply { parentFile!!.mkdirs() }
        compactTrashShot.outputStream().use { ui.onNodeWithTag("全局回收站").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithText("产检", substring = false).performClick(); settle()
        ui.onNodeWithTag("trash-batch-$medicationBatchId").assertDoesNotExist()
        ui.onNodeWithText("全部", substring = false).performClick(); settle()
        ui.onNodeWithTag("global-trash-search").performTextInput("不存在的项目")
        ui.onNodeWithTag("trash-batch-$medicationBatchId").assertDoesNotExist()
        ui.onNodeWithTag("global-trash-search").performTextReplacement("合成改名药品")
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("trash-batch-$medicationBatchId").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("global-trash-search").performTextReplacement("")
        ui.onNodeWithTag("trash-batch-$medicationBatchId").performScrollTo().performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("药品 · 1份照片 · 1项方案 · 0条服药记录").fetchSemanticsNodes().isNotEmpty() }
        val trashShot = File(context.getExternalFilesDir(null), "verification/c01left-trash.png").apply { parentFile!!.mkdirs() }
        trashShot.outputStream().use { ui.onNodeWithTag("全局回收站").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.mainClock.advanceTimeBy(600); ui.waitForIdle()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("历史方案仍引用此药品，保留至所属孕期整体清理").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10_000) { runCatching { ui.onNodeWithTag("restore-batch-$medicationBatchId").assertIsEnabled(); true }.getOrDefault(false) }
        ui.onNodeWithTag("restore-batch-$medicationBatchId").performClick(); settle()
        ui.waitUntil(10_000) { runBlocking { db.journalDao().medication(drugId)?.deletedAt == null } }
        assertTrue(runBlocking { db.journalDao().medicationPlan(planId)?.deletedAt == null })
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithText("用药方案").performScrollTo().performClick(); settle()
        ui.onNodeWithText("合成原药品").assertExists()
    }

    @Test fun standaloneDeletedDocumentCompactRowRestores() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val visit = runBlocking { db.journalDao().allVisits().first() }
        val source = File(context.cacheDir, "capture/${UUID.randomUUID()}.png").apply { parentFile!!.mkdirs() }
        documentSources.add(source)
        val bitmap = android.graphics.Bitmap.createBitmap(100, 160, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val id = runBlocking {
            model.documents.enqueue(visit.id, listOf(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.capture", source)), schedule = false)
            db.journalDao().pendingImports().single().id
        }
        runBlocking {
            model.documents.process(id)
            model.documents.updateMetadata(id, "单独删除的报告", "OTHER", null, null, null)
            model.documents.trash(id)
        }
        ui.onNodeWithText("我的").performClick(); settle()
        ui.onNodeWithText("全局回收站").performClick(); settle()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("trash-document-$id").fetchSemanticsNodes().isNotEmpty() }
        val standaloneShot = File(context.getExternalFilesDir(null), "verification/trash-standalone.png").apply { parentFile!!.mkdirs() }
        standaloneShot.outputStream().use { ui.onNodeWithTag("全局回收站").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithTag("trash-document-$id").performClick(); settle()
        ui.onNodeWithText("恢复", substring = false).performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().attachment(id)?.deletedAt == null } }
        ui.onNodeWithTag("trash-document-$id").assertDoesNotExist()
    }

    @Test fun globalTrashKeepsVisitReportsAndRestoresLastPregnancy() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val visit = runBlocking { db.journalDao().allVisits().first() }
        val source = File(context.cacheDir, "capture/${UUID.randomUUID()}.png").apply { parentFile!!.mkdirs() }
        documentSources.add(source)
        val bitmap = android.graphics.Bitmap.createBitmap(100, 160, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val documentId = runBlocking {
            model.documents.enqueue(visit.id, listOf(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.capture", source)), schedule = false)
            val job = db.journalDao().pendingImports().single()
            model.documents.process(job.id)
            job.id
        }
        ui.onNodeWithText("产检").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("月末测试", substring = true))
        ui.onNodeWithText("月末测试", substring = true).performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("删除产检记录"))
        ui.onNodeWithText("删除产检记录").performClick(); settle()
        ui.onNodeWithTag("confirm-root-deletion").assertIsEnabled().performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().visit(visit.id)?.deletedAt != null } }
        ui.onNodeWithTag("产检详情").assertDoesNotExist()
        ui.onNodeWithText("我的").performClick(); settle()
        ui.onNodeWithText("全局回收站").performClick(); settle()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("保留的资料（1）").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("保留的资料（1）").performClick(); settle()
        ui.onNodeWithTag("trash-retained-$documentId").performClick(); settle()
        ui.onNodeWithText("查看资料").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("document-image").fetchSemanticsNodes().isNotEmpty() }
        // 原件预览顶栏已统一为 JournalFullScreenHeader；全屏 Dialog 叠在页面顶栏之上，
        // 用 fullscreen-navigate-up 精确定位 Dialog 内那一个。
        ui.onNodeWithTag("fullscreen-navigate-up").performClick(); settle()
        ui.onNodeWithText("删除的项目（1）").performClick(); settle()
        val visitBatchId = runBlocking { db.journalDao().trashBatches().first().single { it.kind == "VISIT" }.id }
        ui.onNodeWithTag("trash-batch-$visitBatchId").performClick(); settle()
        ui.onNodeWithText("恢复", substring = false).performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().visit(visit.id)?.deletedAt == null } }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("保留的资料（0）").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("保留的资料（0）").assertExists()
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithText("管理孕期").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("删除孕期"))
        ui.onNodeWithText("删除孕期").performClick(); settle()
        ui.onNodeWithText("2次产检", substring = true).assertExists()
        val shot = File(context.getExternalFilesDir(null), "verification/b06-delete-scope.png").apply { parentFile!!.mkdirs() }
        captureNode(shot, "root-deletion-dialog")
        ui.onNodeWithTag("confirm-root-deletion").performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().pregnancy(pregnancyId)?.deletedAt != null } }
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithText("管理孕期", substring = false).assertExists()
        ui.onNodeWithText("今天", substring = false).performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("创建我的孕期档案"))
        ui.onNodeWithText("创建我的孕期档案", substring = false).assertExists()
        ui.onNodeWithText("我的", substring = false).performClick(); settle()
        ui.onNodeWithText("全局回收站").performClick(); settle()
        val pregnancyBatchId = runBlocking { db.journalDao().trashBatches().first().single { it.kind == "PREGNANCY" }.id }
        ui.onNodeWithTag("trash-batch-$pregnancyBatchId").performClick(); settle()
        val trashShot = File(context.getExternalFilesDir(null), "verification/b06-global-trash.png").apply { parentFile!!.mkdirs() }
        trashShot.outputStream().use { ui.onNodeWithTag("全局回收站").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithText("彻底删除", substring = false).performClick(); settle()
        ui.onNodeWithTag("confirm-global-removal").assertExists()
        val confirmShot = File(context.getExternalFilesDir(null), "verification/b06-global-trash-confirm.png").apply { parentFile!!.mkdirs() }
        captureNode(confirmShot, "global-removal-dialog")
        ui.onNodeWithText("取消", substring = false).performClick(); settle()
        ui.onNodeWithText("恢复", substring = false).performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().pregnancy(pregnancyId)?.deletedAt == null } }
        assertTrue(runBlocking { db.journalDao().attachment(documentId)?.deletedAt == null })
        ui.onNodeWithText("全局回收站为空").assertExists()
    }

    @Test fun pdfNavigationMetadataSearchAndBackgroundCleanup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val visit = runBlocking { db.journalDao().allVisits().first() }
        val source = File(context.cacheDir, "capture/${UUID.randomUUID()}.pdf").apply { parentFile!!.mkdirs() }
        documentSources.add(source)
        val pdf = android.graphics.pdf.PdfDocument()
        try {
            repeat(3) { index ->
                val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(600, 900, index + 1).create())
                page.canvas.drawColor(android.graphics.Color.WHITE)
                page.canvas.drawText("Synthetic report page ${index + 1}", 50f, 100f,
                    android.graphics.Paint().apply { textSize = 32f; color = android.graphics.Color.BLACK })
                pdf.finishPage(page)
            }
            source.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.capture", source)
        val id = runBlocking {
            model.documents.enqueue(visit.id, listOf(uri), schedule = false)
            val job = db.journalDao().pendingImports().single()
            model.documents.process(job.id)
            job.id
        }
        ui.onNodeWithText("产检", useUnmergedTree = true).performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("月末测试", substring = true))
        ui.onNodeWithText("月末测试", substring = true).performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("产检资料"))
        ui.onNodeWithText("产检资料").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("编辑").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("编辑"))
        ui.onNodeWithText("编辑").performClick(); settle()
        ui.onNodeWithTag("document-name").performTextReplacement("超声复查报告")
        ui.onNodeWithText("其他").performClick(); settle()
        ui.onNodeWithText("超声", substring = false).performClick(); settle()
        ui.onNodeWithText("保存").performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().attachment(id)?.category == "ULTRASOUND" } }
        // Scroll the target nodes themselves: which container is "first" differs between API levels.
        ui.waitUntil(10_000) { ui.onAllNodesWithText("搜索名称、备注或医院").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("搜索名称、备注或医院").performScrollTo()
        // Focus explicitly first: on a slower/target-API device the injected text can otherwise be dropped.
        ui.onNodeWithTag("document-search").performClick()
        ui.onNodeWithTag("document-search").performTextInput("不存在")
        // The empty-state card is a lazy item: scroll the list until it is actually composed. Whether it is
        // already on screen depends on the device's insets, so waiting for it alone is not reliable.
        ui.onNodeWithTag("document-list").performScrollToNode(hasText("没有匹配的资料"))
        ui.onNodeWithText("没有匹配的资料").assertExists()
        // The 28 sp JournalPageHeader is taller than the old 20 sp TopAppBar, so with the IME open the
        // lazy viewport is short enough that the empty card's clear button can sit just outside it:
        // scroll the button itself into view before tapping, otherwise the tap is injected off-list.
        ui.onNodeWithText("清除搜索与筛选").performScrollTo().performClick(); settle()
        // The document card is a lazy item below the search block; scroll the list until it is composed
        // instead of assuming it is already inside the composition window.
        ui.onNodeWithTag("document-list").performScrollToNode(hasText("查看 PDF"))
        ui.waitUntil(10_000) { ui.onAllNodesWithText("查看 PDF").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("查看 PDF").performScrollTo()
        ui.onNodeWithText("查看 PDF").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("document-pdf").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("1 / 3").assertExists()
        val firstPage = File(context.getExternalFilesDir(null), "verification/b05-pdf-first-page.png").apply { parentFile!!.mkdirs() }
        captureNode(firstPage, "document-preview")
        ui.onNodeWithText("上一页").assertIsNotEnabled()
        ui.onNodeWithText("下一页").performClick(); settle()
        ui.onNodeWithText("2 / 3").assertExists()
        ui.onNodeWithTag("pdf-page-count").performClick(); settle()
        ui.onNodeWithTag("pdf-page-input").performTextReplacement("3")
        ui.onNodeWithText("跳转", substring = false).performClick(); settle()
        ui.onNodeWithText("下一页").assertIsNotEnabled()
        ui.onNodeWithText("放大").performClick(); settle()
        ui.onNodeWithText("200% · 复位").assertExists()
        val screenshot = File(context.getExternalFilesDir(null), "verification/b05-pdf.png").apply { parentFile!!.mkdirs() }
        captureNode(screenshot, "document-preview")
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        ui.waitUntil(5_000) { File(documentRoot, "document-viewers").listFiles().orEmpty().isEmpty() }
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("document-pdf").fetchSemanticsNodes().isNotEmpty() }; settle()
        ui.onNodeWithText("3 / 3").assertExists()
        ui.onNodeWithTag("preview-delete").performClick(); settle()
        ui.onNodeWithTag("confirm-document-removal").performClick(); settle()
        ui.waitUntil(5_000) { File(documentRoot, "document-viewers").listFiles().orEmpty().isEmpty() }
    }

    @Test fun imageZoomDeleteUndoAndRecycleRestore() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val visit = runBlocking { db.journalDao().allVisits().first() }
        val source = File(context.cacheDir, "capture/${UUID.randomUUID()}.png").apply { parentFile!!.mkdirs() }
        documentSources.add(source)
        val bitmap = android.graphics.Bitmap.createBitmap(900, 1400, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; textSize = 42f; isAntiAlias = true }
        val canvas = android.graphics.Canvas(bitmap)
        repeat(15) { canvas.drawText("Synthetic report line ${it + 1}", 60f, 90f + it * 80f, paint) }
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val id = runBlocking {
            model.documents.enqueue(visit.id, listOf(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.capture", source)), schedule = false)
            val id = db.journalDao().pendingImports().single().id
            model.documents.process(id); id
        }
        ui.onNodeWithText("产检").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("月末测试", substring = true))
        ui.onNodeWithText("月末测试", substring = true).performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("产检资料"))
        ui.onNodeWithText("产检资料").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("查看大图").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("查看大图"))
        ui.onNodeWithText("查看大图").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("document-image").fetchSemanticsNodes().isNotEmpty() }
        val fullPreviewShot = File(context.getExternalFilesDir(null), "verification/b05-image-preview.png").apply { parentFile!!.mkdirs() }
        captureNode(fullPreviewShot, "document-preview")
        ui.onNodeWithTag("document-image").performTouchInput { doubleClick(center) }; settle()
        ui.onNodeWithTag("document-image").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "300%"))
        val screenshot = File(context.getExternalFilesDir(null), "verification/b05-zoom.png").apply { parentFile!!.mkdirs() }
        captureNode(screenshot, "document-image")
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        ui.waitUntil(5_000) { File(documentRoot, "document-viewers").listFiles().orEmpty().isEmpty() }
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("document-image").fetchSemanticsNodes().isNotEmpty() }; settle()
        ui.onNodeWithText("复位").performClick(); settle()
        ui.onNodeWithTag("document-image").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "100%"))
        ui.onNodeWithTag("document-image").performTouchInput {
            pinch(center + androidx.compose.ui.geometry.Offset(-70f, 0f), center + androidx.compose.ui.geometry.Offset(70f, 0f),
                center + androidx.compose.ui.geometry.Offset(-150f, 0f), center + androidx.compose.ui.geometry.Offset(150f, 0f))
        }; settle()
        val magnification = ui.onNodeWithTag("document-image").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].removeSuffix("%").toInt()
        assertTrue("双指放大应增加缩放比例", magnification > 100)
        if (Build.VERSION.SDK_INT >= 28) {
            // 拖动后像素比较同样依赖 captureToImage；API 26/27 读不到 dialog 窗口像素。
            val beforePan = ui.onNodeWithTag("document-image").captureToImage().asAndroidBitmap()
            ui.onNodeWithTag("document-image").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(80f, 80f)) }; settle()
            val afterPan = ui.onNodeWithTag("document-image").captureToImage().asAndroidBitmap()
            assertTrue("拖动应改变可见报告区域", !beforePan.sameAs(afterPan))
        }
        ui.onNodeWithTag("preview-delete").performClick(); settle()
        ui.onNodeWithText("移入回收站").performClick(); settle()
        ui.onNodeWithText("撤销").performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().attachment(id)?.deletedAt == null } }
        ui.onNodeWithText("删除").performScrollTo().performClick(); settle()
        ui.onNodeWithText("移入回收站").performClick(); settle()
        ui.onNodeWithText("回收站（1）").performClick(); settle()
        ui.onNodeWithText("恢复").performClick(); settle()
        ui.onNodeWithText("本次资料").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("查看大图"))
        ui.onNodeWithText("查看大图").assertIsDisplayed()
        ui.onNodeWithText("删除").performScrollTo().performClick(); settle()
        ui.onNodeWithTag("confirm-document-removal").performClick(); settle()
        ui.onNodeWithText("回收站（1）").performClick(); settle()
        ui.onNodeWithText("彻底删除").performClick(); settle()
        ui.onNodeWithTag("confirm-document-removal").performClick(); settle()
        ui.waitUntil(10_000) { runBlocking { db.journalDao().importJob(id)?.state == "PURGED" } }
        ui.onNodeWithText("回收站为空").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithTag("产检详情").assertIsDisplayed()
        assertTrue(File(documentRoot, "document-viewers").listFiles().orEmpty().isEmpty())
    }

    @Test fun documentEntryReturnsToItsVisitAndShowsImportChoices() {
        ui.onNodeWithText("产检").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("月末测试", substring = true))
        ui.onNodeWithText("月末测试", substring = true).performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("产检资料"))
        ui.onNodeWithText("产检资料").performClick(); settle()
        ui.onNodeWithText("还没有产检资料").assertIsDisplayed()
        ui.onNodeWithTag("document-list").performScrollToNode(hasTestTag("document-add"))
        ui.onNodeWithTag("document-add").performClick(); settle()
        ui.onNodeWithText("选图片").assertIsDisplayed()
        ui.onNodeWithText("选 PDF").assertIsDisplayed()
        ui.onNodeWithText("拍照").assertIsDisplayed()
        ui.onNodeWithText("取消").performClick(); settle()
        val screenshot = File(ui.activity.getExternalFilesDir(null), "verification/b04-empty.png")
        screenshot.parentFile!!.mkdirs()
        screenshot.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithTag("产检详情").assertIsDisplayed()
    }

    @Test fun reminderSettingsShowsPermissionAndTypeControls() {
        ui.onNodeWithText("我的").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("提醒设置"))
        ui.onNodeWithText("提醒设置").performClick(); settle()
        ui.onNodeWithTag("reminder-permission").assertExists()
        // N06 改版后“通知权限”同时是首屏状态入口与下方详细控制区标题，允许存在多个可见节点。
        assertTrue("提醒设置应显示通知权限入口", ui.onAllNodesWithText("通知权限").fetchSemanticsNodes().isNotEmpty())
        ui.onNodeWithTag("reminder-visits").assertExists()
        ui.onNodeWithTag("reminder-medications").assertExists()
        ui.onNodeWithTag("reminder-rebuild").assertExists()
        ui.onNodeWithText("提醒只在本机生成，不上传药名、医院或检查内容。系统省电、强行停止或拒绝权限时可能延迟或不显示；应用内待办始终保留。").assertExists()
        val settingsShot = File(ApplicationProvider.getApplicationContext<android.content.Context>().getExternalFilesDir(null), "verification/c05-reminder-settings.png").apply { parentFile!!.mkdirs() }
        settingsShot.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun cloudConnectionValidatesBeforeNetworkAndClearsUnsavedPasswordOnReturn() {
        ui.onNodeWithText("我的").performClick();settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("云盘连接"))
        ui.onNodeWithText("云盘连接").performClick();settle()
        ui.waitUntil(5_000){!model.cloud.state.value.loading}
        ui.onNodeWithTag("cloud-connection").assertIsDisplayed()
        val image=File(ApplicationProvider.getApplicationContext<android.content.Context>().getExternalFilesDir(null),"verification/d01-cloud-connection.png").apply {parentFile!!.mkdirs()}
        image.outputStream().use {ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        ui.onNodeWithText("测试连接").performScrollTo().performClick();settle()
        // Bug #4: 账号留空时提示“请填写账号”，不再与“非法字符”共用文案。
        ui.waitUntil(10_000) { ui.onAllNodesWithText("请填写账号").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("请填写账号").assertExists()
        ui.onNodeWithTag("cloud-username").performScrollTo().performTextInput("bad:name")
        ui.waitUntil(5_000) { ui.onAllNodesWithText("bad:name").fetchSemanticsNodes().isNotEmpty() }
        // 确保输入已进入控制器状态，再触发校验（全量负载下文本/状态同步可能晚于控件显示）。
        ui.waitUntil(10_000) { model.cloud.state.value.username == "bad:name" }
        ui.onNodeWithText("测试连接").performScrollTo().performClick();settle()
        // 以控制器状态断言非法字符文案（全量负载下 UI 文本节点可能延迟；空白账号用例已覆盖文案渲染）。
        ui.waitUntil(15_000) { model.cloud.state.value.message.orEmpty().contains("冒号") }
        assertTrue(model.cloud.state.value.message.orEmpty().contains("请检查账号"))
        ui.onNodeWithText("保存连接").assertIsNotEnabled()
        ui.onNodeWithTag("cloud-password").performScrollTo().performTextInput("synthetic-unsaved-password")
        ui.onNodeWithContentDescription("返回上一级").performClick();settle()
        assertEquals("",model.cloud.state.value.password)
    }

    @Test fun visitReminderCanBeSetAndCleared() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS) }
        }
        val visit = runBlocking { db.journalDao().allVisits().sortedBy { it.scheduledDate }.first() }
        ui.onNodeWithText("产检").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("月末测试", substring = true))
        ui.onNodeWithText("月末测试", substring = true).performClick(); settle()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("visit-reminder-set").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("visit-reminder-set"))
        ui.onNodeWithTag("visit-reminder-set").performClick(); settle()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("confirm-visit-reminder").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("confirm-visit-reminder").performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().visit(visit.id)?.reminderDate != null } }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("提醒已设置").fetchSemanticsNodes().isEmpty() }
        val saved = runBlocking { requireNotNull(db.journalDao().visit(visit.id)) }
        assertEquals(visit.scheduledDate, saved.reminderDate?.let { java.time.LocalDate.parse(it).plusDays(1).toString() })
        assertEquals(visit.scheduledTime ?: "09:00", saved.reminderTime)
        val reminderShot = File(context.getExternalFilesDir(null), "verification/c05-visit-reminder.png").apply { parentFile!!.mkdirs() }
        reminderShot.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("clear-visit-reminder"))
        ui.onNodeWithTag("clear-visit-reminder").performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().visit(visit.id)?.reminderDate == null } }
    }

    @Test fun documentCoverAndRelinkKeepOwnershipVisible() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val visits = runBlocking { db.journalDao().allVisits().sortedBy { it.scheduledDate } }
        val first = visits.first(); val second = visits.last()
        val source = File(context.cacheDir, "capture/封面关联合成资料.png").apply { parentFile!!.mkdirs() }
        documentSources.add(source)
        val bitmap = android.graphics.Bitmap.createBitmap(900, 1400, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.DKGRAY; textSize = 40f; isAntiAlias = true }
        val canvas = android.graphics.Canvas(bitmap)
        repeat(8) { canvas.drawText("Synthetic cover line ${it + 1}", 60f, 120f + it * 140f, paint) }
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val id = runBlocking {
            model.documents.enqueue(first.id, listOf(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.capture", source)), schedule = false)
            val job = db.journalDao().pendingImports().single().id
            model.documents.process(job); job
        }
        ui.onNodeWithText("产检").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("月末测试", substring = true))
        ui.onNodeWithText("月末测试", substring = true).performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("产检资料"))
        ui.onNodeWithText("产检资料").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("document-more-$id"))
        ui.onNodeWithTag("document-more-$id").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("set-cover-$id").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("set-cover-$id"))
        ui.onNodeWithTag("set-cover-$id").assertIsDisplayed()
        ui.onNodeWithTag("set-cover-$id").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().visit(first.id)?.coverAttachmentId == id } }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("封面", substring = false).fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("已设为本次产检封面").fetchSemanticsNodes().isNotEmpty() }
        // The snackbar can cover the bottom-row action on slow devices; dismiss it through its real semantics.
        ui.onNodeWithText("已设为本次产检封面").onParent().onParent()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("已设为本次产检封面").fetchSemanticsNodes().isEmpty() }
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("clear-cover"))
        val coverShot = File(context.getExternalFilesDir(null), "verification/b05-cover.png").apply { parentFile!!.mkdirs() }
        coverShot.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("relink-$id"))
        ui.onNodeWithTag("relink-$id").performClick(); settle()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("relink-dialog").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("下月测试", substring = true).performScrollTo().performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().attachment(id)?.visitId == second.id } }
        assertTrue(runBlocking { db.journalDao().visit(first.id)?.coverAttachmentId == null })
        assertTrue(runBlocking { db.journalDao().importJob(id)?.visitId == second.id })
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("下月测试", substring = true))
        ui.onNodeWithText("下月测试", substring = true).performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("产检资料"))
        ui.onNodeWithText("产检资料").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("封面关联合成资料", substring = true))
        ui.onNodeWithText("封面关联合成资料", substring = true).assertExists()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("document-more-$id"))
        ui.onNodeWithTag("document-more-$id").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("set-cover-$id"))
        ui.onNodeWithTag("set-cover-$id").performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().visit(second.id)?.coverAttachmentId == id } }
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("clear-cover"))
        ui.onNodeWithTag("clear-cover").performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().visit(second.id)?.coverAttachmentId == null } }
    }

    @Test fun retainedDocumentRelinksToRemainingVisit() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val visits = runBlocking { db.journalDao().allVisits().sortedBy { it.scheduledDate } }
        val first = visits.first(); val second = visits.last()
        val source = File(context.cacheDir, "capture/保留资料关联.png").apply { parentFile!!.mkdirs() }
        documentSources.add(source)
        val bitmap = android.graphics.Bitmap.createBitmap(100, 160, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val id = runBlocking {
            model.documents.enqueue(first.id, listOf(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.capture", source)), schedule = false)
            val job = db.journalDao().pendingImports().single().id
            model.documents.process(job)
            model.trash.delete("VISIT", first.id, schedule = false)
            job
        }
        ui.onNodeWithText("我的").performClick(); settle()
        ui.onNodeWithText("全局回收站").performClick(); settle()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("保留的资料（1）").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("保留的资料（1）").performClick(); settle()
        val retainedShot = File(context.getExternalFilesDir(null), "verification/trash-retained.png").apply { parentFile!!.mkdirs() }
        retainedShot.outputStream().use { ui.onNodeWithTag("全局回收站").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithTag("trash-retained-$id").performClick(); settle()
        ui.onNodeWithText("重新关联产检").performClick(); settle()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("retained-relink-dialog").fetchSemanticsNodes().isNotEmpty() }
        val relinkShot = File(context.getExternalFilesDir(null), "verification/b05-retained-relink.png").apply { parentFile!!.mkdirs() }
        captureNode(relinkShot, "retained-relink-dialog")
        ui.onNodeWithText("下月测试", substring = true).performScrollTo().performClick(); settle()
        ui.waitUntil(5_000) { runBlocking { db.journalDao().attachment(id)?.visitId == second.id } }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("保留的资料（0）").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun manageAndEditorReturnToTheirActualParent() {
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("管理我的孕期"))
        ui.onNodeWithText("管理我的孕期").performClick(); settle()
        ui.onNodeWithTag("管理孕期").assertIsDisplayed()
        ui.onNodeWithText("编辑").performClick(); settle()
        ui.onNodeWithTag("编辑孕期档案").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithTag("管理孕期").assertIsDisplayed()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }; settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("今天，慢慢来"))
        ui.onNodeWithText("今天，慢慢来").assertIsDisplayed()
        ui.onNodeWithTag("管理孕期").assertDoesNotExist()
    }

    @Test fun rootSwitchRetainsVisitFilterAndDoesNotCreateNestedProfileRoot() {
        ui.onNodeWithText("产检", useUnmergedTree = false).performClick(); settle()
        ui.onNodeWithText("已取消").performClick(); settle()
        ui.onNodeWithText("我的", useUnmergedTree = false).performClick(); settle()
        ui.onNodeWithText("管理孕期").performClick(); settle()
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithTag("ProfilesRoute").assertIsDisplayed()
        ui.onNodeWithText("产检", useUnmergedTree = false).performClick(); settle()
        ui.onNodeWithText("已取消").assertIsSelected()
    }

    @Test fun openingAndReturningWithoutChangesDoesNotCreateDraft() {
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("新建产检预约"))
        ui.onNodeWithText("新建产检预约").performClick(); settle()
        // The editor form loads asynchronously under full-matrix load; wait for it before asserting.
        ui.waitUntil(10_000) { ui.onAllNodesWithText("医院（选填）").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("医院（选填）").assertExists()
        ui.runOnIdle { assertTrue(model.hasVisitEditor(null, pregnancyId)) }
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("今天，慢慢来"))
        ui.onNodeWithText("今天，慢慢来").assertIsDisplayed()
        assertTrue(runBlocking { repository.draft("visit", "new:$pregnancyId") } == null)
    }

    @Test fun bookingDraftSurvivesTopBarReturn() {
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("新建产检预约"))
        ui.onNodeWithText("新建产检预约").performClick(); settle()
        // The form loads asynchronously; scroll only after the target field exists, and scroll the field
        // itself instead of guessing which scrollable contains it.
        ui.waitUntil(10_000) { ui.onAllNodesWithText("检查项目（选填）").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("检查项目（选填）").performScrollTo()
        ui.onNodeWithText("检查项目（选填）").performTextInput("导航草稿")
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("今天，慢慢来"))
        ui.onNodeWithText("今天，慢慢来").assertIsDisplayed()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("新建产检预约"))
        ui.onNodeWithText("新建产检预约").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("导航草稿").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("导航草稿").performScrollTo()
        ui.onNodeWithText("导航草稿").assertExists()
    }

    @Test fun forwardMovesInAndBackMovesOutWithoutChangingDirection() {
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("管理我的孕期"))
        ui.mainClock.autoAdvance = false
        try {
            ui.onNodeWithText("管理我的孕期").performClick()
            ui.mainClock.advanceTimeBy(80)
            val entering = ui.onNodeWithTag("管理孕期").fetchSemanticsNode().boundsInRoot.left
            ui.mainClock.advanceTimeBy(80)
            val enteredMore = ui.onNodeWithTag("管理孕期").fetchSemanticsNode().boundsInRoot.left
            assertTrue("进入页面应从右向左移入", entering > enteredMore && enteredMore > 0f)
            ui.mainClock.advanceTimeBy(400)
            SystemClock.sleep(350)
            ui.onNodeWithContentDescription("返回上一级").performClick()
            ui.mainClock.advanceTimeBy(80)
            val leaving = ui.onNodeWithTag("管理孕期").fetchSemanticsNode().boundsInRoot.left
            ui.mainClock.advanceTimeBy(80)
            val leavingMore = ui.onNodeWithTag("管理孕期").fetchSemanticsNode().boundsInRoot.left
            assertTrue("返回页面应向右移出", leavingMore > leaving && leaving >= 0f)
            ui.mainClock.advanceTimeBy(400)
            ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("今天，慢慢来"))
        ui.onNodeWithText("今天，慢慢来").assertIsDisplayed()
        } finally { ui.mainClock.autoAdvance = true }
    }

    @Test fun calendarMonthAndCancellationRemainConsistentAfterReturning() {
        ui.onNodeWithText("产检", useUnmergedTree = false).performClick(); settle()
        ui.onNodeWithText("日历").performClick(); settle()
        ui.onNodeWithText("新建产检预约").assertIsDisplayed()
        val calendarShot = File(ApplicationProvider.getApplicationContext<android.content.Context>().getExternalFilesDir(null), "verification/visit-calendar.png").apply { parentFile!!.mkdirs() }
        calendarShot.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("月末测试", substring = true))
        ui.onNodeWithText("月末测试", substring = true).assertExists()
        ui.onNodeWithText("下月测试", substring = true).assertDoesNotExist()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("切换月份", substring = false))
        ui.onNodeWithText("切换月份", substring = false).performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("下月", substring = false))
        ui.onNodeWithText("下月", substring = false).performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("下月测试", substring = true))
        ui.onNodeWithText("下月测试", substring = true).performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("取消这次预约"))
        ui.onNodeWithText("取消这次预约").performClick()
        ui.onNodeWithText("确认取消").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("已取消", substring = false))
        ui.onNodeWithText("已取消", substring = false).assertExists()
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithText("下月测试", substring = true).assertExists()
    }

    @Test fun actualVisitFieldsSaveAndShowWithoutChangingAppointment() {
        val bookingDate = java.time.LocalDate.now().minusDays(1)
        val visitId = runBlocking { repository.saveVisit(null, pregnancyId, bookingDate, java.time.LocalTime.of(8, 0),
            "原预约医院", "实际字段测试", "") }
        ui.onNodeWithText("产检", useUnmergedTree = false).performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("实际字段测试", substring = true))
        ui.onNodeWithText("实际字段测试", substring = true).performClick(); settle()
        ui.onNodeWithText("记录实际就诊").performScrollTo().performClick(); settle()
        ui.onNodeWithTag("actual-visit-time").performTextInput("09:30")
        ui.onNodeWithTag("actual-visit-hospital").performScrollTo().performTextInput("实际测试医院")
        ui.onNodeWithTag("actual-visit-result").performScrollTo().performTextInput("合成检查结果")
        clickSaveAfterKeyboardSettles("保存就诊记录")
        ui.waitUntil(10_000) { runBlocking { repository.visit(visitId)?.actualResult == "合成检查结果" } }
        val saved = requireNotNull(runBlocking { repository.visit(visitId) })
        assertEquals("原预约医院", saved.hospital)
        assertEquals("08:00", saved.scheduledTime)
        assertEquals("实际测试医院", saved.actualHospital)
        assertEquals("09:30", saved.actualTime)
        ui.onNodeWithText("就诊医院：实际测试医院").assertExists()
        ui.onNodeWithText("合成检查结果").performScrollTo().assertExists()
    }

    @Test fun oldVersionBookingDraftIsRecoveredWithoutMixingPregnancies() {
        runBlocking { repository.saveDraft("visit", "new", Json.encodeToString(VisitForm(pregnancyId = pregnancyId, items = "旧版预约草稿"))) }
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("新建产检预约"))
        ui.onNodeWithText("新建产检预约").performClick(); settle()
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("旧版预约草稿"))
        ui.onNodeWithText("旧版预约草稿").assertExists()
        runBlocking {
            org.junit.Assert.assertNull(repository.draft("visit", "new"))
            org.junit.Assert.assertNotNull(repository.draft("visit", "new:$pregnancyId"))
        }
    }

    @Test fun predictiveBackCancellationKeepsPageAndCommitReturnsOnce() {
        ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("管理我的孕期"))
        ui.onNodeWithText("管理我的孕期").performClick(); settle()
        ui.mainClock.autoAdvance = false
        try {
            val dispatcher = ui.activity.onBackPressedDispatcher
            ui.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT)) }
            ui.mainClock.advanceTimeByFrame()
            ui.runOnUiThread { dispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 300f, .65f, BackEventCompat.EDGE_LEFT)) }
            ui.mainClock.advanceTimeBy(64)
            assertTrue("手势进度应推动当前页面", ui.onNodeWithTag("管理孕期").fetchSemanticsNode().boundsInRoot.left > 0f)
            ui.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
            ui.mainClock.advanceTimeBy(600)
            ui.onNodeWithTag("管理孕期").assertIsDisplayed()
            org.junit.Assert.assertEquals(0f, ui.onNodeWithTag("管理孕期").fetchSemanticsNode().boundsInRoot.left, 1f)
            ui.runOnUiThread { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT)) }
            ui.mainClock.advanceTimeByFrame()
            ui.runOnUiThread { dispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 300f, .7f, BackEventCompat.EDGE_LEFT)); dispatcher.onBackPressed() }
            ui.mainClock.advanceTimeBy(600)
            ui.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("今天，慢慢来"))
        ui.onNodeWithText("今天，慢慢来").assertIsDisplayed()
            ui.onNodeWithTag("管理孕期").assertDoesNotExist()
        } finally { ui.mainClock.autoAdvance = true }
    }

    /**
     * Evidence-only capture. Compose test cannot read pixels from dialog windows below API 28, so on
     * those devices the file records the skip instead of failing the behaviour assertions around it.
     */
    private fun captureNode(target: File, tag: String) {
        if (Build.VERSION.SDK_INT < 28) {
            target.writeText("skipped on API ${Build.VERSION.SDK_INT}: dialog capture requires API 28+\n")
            return
        }
        target.outputStream().use {
            ui.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }


    /**
     * Compose requests the soft keyboard asynchronously: right after a text field gains focus the IME
     * can still be hidden, and on this device a cold input method shows up more than a second later.
     * When the IME appears while the test is already scrolling a form to its save button, the form
     * viewport shrinks underneath the tap: the button slides below the keyboard window and the real
     * touch is delivered to the keyboard instead of the app, so the save callback never runs (the form
     * keeps the typed values and the model stays idle). This reproduced with a cold IME after
     * `LockEntryMatrixTest` and left `all_intakes` empty for the 10 s save wait.
     *
     * Dismiss the keyboard, wait until the IME inset and the button bounds have stopped changing, and
     * only then scroll the button into the visible viewport and tap it. The product assertions and the
     * real touch click are unchanged; only the input transport is synchronised with the IME.
     */
    private fun clickSaveAfterKeyboardSettles(label: String) {
        val activity = ui.activity
        val decor = activity.window.decorView
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WindowInsetsControllerCompat(activity.window, decor).hide(WindowInsetsCompat.Type.ime())
        }
        var previous = ""
        var unchangedSince = SystemClock.uptimeMillis()
        val deadline = unchangedSince + 15_000
        while (true) {
            ui.waitForIdle()
            val imeVisible = ViewCompat.getRootWindowInsets(decor)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val bounds = ui.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            val signature = "$imeVisible|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
            val now = SystemClock.uptimeMillis()
            if (signature != previous) { previous = signature; unchangedSince = now }
            if (now - unchangedSince > 700) break
            if (now > deadline) throw AssertionError("软键盘或表单布局未在超时内稳定：ime=$imeVisible bounds=$bounds")
            SystemClock.sleep(80)
        }
        ui.onNodeWithText(label).performScrollTo()
        settle()
        ui.onNodeWithText(label).assertIsDisplayed().performClick()
        settle()
    }

    private fun settle() {
        ui.waitForIdle()
        val target = SystemClock.uptimeMillis() + 350
        ui.waitUntil(2_000) { SystemClock.uptimeMillis() >= target }
    }
}

