package cn.yunji.journal

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.database.JournalDatabase
import cn.yunji.journal.core.designsystem.PregnancyTheme
import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.core.security.VaultMaterial
import cn.yunji.journal.data.ActivePregnancyPreferences
import cn.yunji.journal.data.CloudBackupAccess
import cn.yunji.journal.data.CloudConnectionRepository
import cn.yunji.journal.data.CloudConnectionStore
import cn.yunji.journal.data.CloudUploadStatus
import cn.yunji.journal.data.DocumentImporter
import cn.yunji.journal.data.DrugForm
import cn.yunji.journal.data.JournalEntryForm
import cn.yunji.journal.data.JournalRepository
import cn.yunji.journal.data.MedicationForm
import cn.yunji.journal.data.RecoverySettings
import cn.yunji.journal.data.ReminderNotifier
import cn.yunji.journal.data.ReminderScheduler
import cn.yunji.journal.data.ReminderStore
import cn.yunji.journal.feature.JournalApp
import cn.yunji.journal.feature.JournalViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * E01/A02 其余页面极端配置矩阵与空/错/加载状态矩阵的共享夹具。
 *
 * 本类不是可执行测试：ExtremeLayoutMatrixPagesTest 只在“系统字体200% + 深色 + 横屏”下运行，
 * UiStateMatrixTest 不限制设备配置。复用的断言与证据写法见
 * docs/verification/2026-10-22-A02-ui-matrix/ 与 docs/verification/2026-10-22-E01-pages-matrix/。
 */
abstract class PageMatrixBaseTest {
    @get:Rule
    val ui = createAndroidComposeRule<ComponentActivity>()

    protected lateinit var db: JournalDatabase
    protected lateinit var repository: JournalRepository
    protected lateinit var model: JournalViewModel
    protected lateinit var pregnancyId: String
    private lateinit var file: File
    private lateinit var documentRoot: File
    private val documentSources = mutableListOf<File>()

    protected val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 子类返回 true 时，非“字体200%+深色+横屏”的环境会在 @Before 里被 Assume 跳过。 */
    protected open fun requiresExtremeConfiguration(): Boolean = false

    @Before
    fun setUpFixture() {
        if (requiresExtremeConfiguration()) assumeTrue("需要系统字体200%+深色+横屏组合，当前=${configSummary()}", extremeConfig())
        Log.i(MATRIX_TAG, "setUp start")
        file = File(context.cacheDir, "pages-matrix-${UUID.randomUUID()}.db")
        db = JournalDatabase.open(context, file, VaultCrypto.randomKey())
        val repo = JournalRepository(db, Clock.systemDefaultZone())
        repository = repo
        pregnancyId = runBlocking { repo.savePregnancy(null, "其余页面极端孕期", null, null, "", "") }
        val preferences = object : ActivePregnancyPreferences {
            override val activePregnancy = MutableStateFlow<String?>(pregnancyId)
            override suspend fun selectPregnancy(id: String) { activePregnancy.value = id }
        }
        documentRoot = File(context.cacheDir, "pages-documents-${UUID.randomUUID()}").apply { mkdirs() }
        val importContext = object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir() = documentRoot
        }
        val fixtureImporter by lazy {
            DocumentImporter(importContext, db, VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey()), Clock.systemUTC())
        }
        val fixtureReminders by lazy {
            ReminderScheduler(context, db, Clock.systemUTC(), ReminderStore(context), ReminderNotifier(context))
                .also { it.requestRebuildEnabled = false }
        }
        val fixtureCloud by lazy {
            val material = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
            CloudConnectionRepository(CloudConnectionStore(importContext, material), material.id, Clock.systemUTC())
        }
        val fixtureBackup by lazy {
            object : CloudBackupAccess {
                override fun status() = CloudUploadStatus()
                override fun isRunning() = false
                override suspend fun upload() = error("Cloud upload must not run in page matrix tests")
            }
        }
        val fixtureRecovery by lazy {
            val material = VaultMaterial(UUID.randomUUID().toString(), VaultCrypto.randomKey())
            RecoverySettings(importContext, material)
        }
        model = JournalViewModel(repository, SavedStateHandle(), preferences, dagger.Lazy { fixtureImporter },
            dagger.Lazy { fixtureReminders }, dagger.Lazy { fixtureCloud }, dagger.Lazy { fixtureRecovery }, dagger.Lazy { fixtureBackup })
        Log.i(MATRIX_TAG, "setUp done")
    }

    @After
    fun tearDownFixture() {
        Log.i(MATRIX_TAG, "tearDown start")
        if (::model.isInitialized) {
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
            runBlocking { model.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.let { job -> job.cancel(); job.join() } }
        }
        if (::db.isInitialized) db.close()
        if (::documentRoot.isInitialized) documentRoot.deleteRecursively()
        documentSources.forEach { it.delete() }
        if (::file.isInitialized) listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
        Log.i(MATRIX_TAG, "tearDown done")
    }

    // ---------- 启动与导航 ----------

    protected fun launchApp() {
        ui.setContent { PregnancyTheme { JournalApp(model) } }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("今天，慢慢来").fetchSemanticsNodes().isNotEmpty() }
        settle()
    }

    protected fun openTab(label: String, pageTag: String) {
        ui.onNode(hasText(label) and hasClickAction()).performClick()
        settle()
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag(pageTag)).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag(pageTag).assertIsDisplayed()
    }

    /** 从“我的”一级入口滚动到某个 RecordRow（提醒设置/用药方案/服药记录/健康指标…）并进入。 */
    protected fun openProfileRow(label: String) {
        openTab("我的", "profiles-root-list")
        ui.onNodeWithTag("profiles-root-list").performScrollToNode(hasText(label))
        ui.onNodeWithText(label).assertIsDisplayed().performClick()
        settle()
    }

    /** 产检列表 → 产检详情 → 产检资料（资料列表空/加载/错误状态用）。 */
    protected fun openVisitDocuments(visitItems: String) {
        openTab("产检", "visits-list")
        ui.onNodeWithTag("visits-list").performScrollToNode(hasText(visitItems, substring = true))
        ui.onNodeWithText(visitItems, substring = true).assertIsDisplayed().performClick()
        settle()
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("visit-detail-list")).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("visit-detail-list").performScrollToNode(hasText("产检资料"))
        ui.onNodeWithText("产检资料").assertIsDisplayed().performClick()
        settle()
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("document-list")).fetchSemanticsNodes().isNotEmpty() }
        settle()
    }

    /** 在带 testTag 的惰性列表里滚动到指定文本（极端配置下屏幕外的条目不会被组合）。 */
    protected fun scrollListTo(listTag: String, text: String, substring: Boolean = false) {
        ui.onNodeWithTag(listTag).performScrollToNode(hasText(text, substring = substring))
        settle()
    }

    // ---------- 数据种子 ----------

    protected fun seedShortVisit(items: String = "常规产检"): String = runBlocking {
        repository.saveVisit(null, pregnancyId, LocalDate.now().plusDays(10), null, "", items, "")
    }

    protected fun seedCompletedLongVisit(): String = runBlocking {
        val id = repository.saveVisit(null, pregnancyId, LocalDate.now().minusDays(3), LocalTime.of(9, 30),
            "市妇幼保健院（东院区）", "糖耐量试验（OGTT）", "空腹8小时；携带母子健康手册。")
        repository.completeVisit(id, LocalDate.now().minusDays(3), "血糖正常；继续补充叶酸，四周后复查。")
        id
    }

    protected fun seedJournalEntry(time: String = "09:30", text: String = "今天状态平稳，晚饭后散步二十分钟。"): String = runBlocking {
        val now=java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
        val requested=time.takeIf(String::isNotBlank)?.let(java.time.LocalTime::parse)
        val safeTime=if(requested!=null && requested.isAfter(now.toLocalTime()))
            now.toLocalTime().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString() else time
        repository.journalEntries.save(JournalEntryForm(pregnancyId = pregnancyId, date = now.toLocalDate().toString(),
            time = safeTime, zoneId = "Asia/Shanghai", symptom = "轻微腰酸，下午缓解", mood = "平静",
            text = text))
    }

    protected fun seedMedicationPlan(): String = runBlocking {
        val drugName = "复合维生素片（孕妇型）"
        val drugId = repository.medications.saveDrug(DrugForm(pregnancyId = pregnancyId,
            name = drugName, specification = "0.5g×60片/盒", dosageForm = "口服片剂"))
        repository.medications.save(MedicationForm(pregnancyId = pregnancyId, medicationId = drugId,
            name = drugName, specification = "0.5g×60片/盒", dosageForm = "口服片剂", dose = "1", unit = "片",
            frequency = "DAILY_TIMES", times = "08:00,20:00", startDate = LocalDate.now().toString(),
            instructions = "随餐或餐后服用", source = "产科门诊医嘱", zoneId = "Asia/Shanghai"))
    }

    protected fun createPngSource(): File {
        val dir = File(context.cacheDir, "capture").apply { mkdirs() }
        val source = File(dir, "${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawText("报告", 40f, 120f, Paint().apply { color = Color.DKGRAY; textSize = 40f; isAntiAlias = true })
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        documentSources.add(source)
        return source
    }

    /** 只入队不处理：资料列表保持 QUEUED（加载中）状态。 */
    protected fun enqueueDocument(visitId: String, source: File): String = runBlocking {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.capture", source)
        model.documents.enqueue(visitId, listOf(uri), schedule = false)
        db.journalDao().pendingImports().single().id
    }

    // ---------- 断言与证据 ----------

    protected fun settle() {
        ui.waitForIdle()
        val target = SystemClock.uptimeMillis() + 350
        ui.waitUntil(2_000) { SystemClock.uptimeMillis() >= target }
    }

    protected fun hideKeyboard() {
        ui.activityRule.scenario.onActivity { activity ->
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        settle()
    }

    protected fun extremeConfig(): Boolean {
        val c = context.resources.configuration
        return c.fontScale >= 1.9f &&
            (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES &&
            c.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    protected fun configSummary(): String {
        val c = context.resources.configuration
        val night = (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val orientation = if (c.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        return "fontScale=${c.fontScale}, night=${if (night) "yes" else "no"}, orientation=$orientation"
    }

    protected fun assertExtreme() {
        val c = context.resources.configuration
        org.junit.Assert.assertEquals("系统字体缩放必须是200%", 2.0f, c.fontScale, 0.05f)
        org.junit.Assert.assertEquals("系统必须处于深色模式", Configuration.UI_MODE_NIGHT_YES, c.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        org.junit.Assert.assertEquals("系统必须处于横屏", Configuration.ORIENTATION_LANDSCAPE, c.orientation)
    }

    private val allTextNodes: SemanticsMatcher
        get() = SemanticsMatcher("有文本的节点") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true
        }

    /** 对页面上当前已组合的全部文本节点做“不截断”断言。 */
    protected fun assertNoTruncationOnAllTexts(label: String) = assertNoTruncation(allTextNodes, label)

    /**
     * 断言文本没有因 maxLines/高度/宽度约束被截断（基于 Compose 的 TextLayoutResult）。
     * 容差与 LayoutInput/Constraints 输出沿用 A02 的实现，见 docs/verification/2026-10-22-A02-ui-matrix/README.md。
     */
    protected fun assertNoTruncation(matcher: SemanticsMatcher, label: String) {
        val nodes = ui.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
        var checked = 0
        nodes.forEachIndexed { index, node ->
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@forEachIndexed
            val output = mutableListOf<TextLayoutResult>()
            action.invoke(output)
            checked++
            output.forEach { layout ->
                val tag = node.config.getOrNull(SemanticsProperties.TestTag)
                val paragraph = layout.multiParagraph
                val constraints = layout.layoutInput.constraints
                val usedFullWidth = constraints.hasBoundedWidth && layout.size.width >= constraints.maxWidth - 1
                val clippedWidth = layout.didOverflowWidth && usedFullWidth
                val clippedHeight = layout.size.height + 1 < paragraph.height
                val exceededMaxLines = paragraph.didExceedMaxLines
                assertFalse(
                    "$label 文本被截断/溢出: '${layout.layoutInput.text}' node=$index tag=$tag bounds=${node.boundsInRoot} " +
                        "size=${layout.size} paragraph=${paragraph.width}x${paragraph.height} lines=${layout.lineCount} " +
                        "exceedMaxLines=$exceededMaxLines clippedW=$clippedWidth(usedFullWidth=$usedFullWidth) " +
                        "clippedH=$clippedHeight maxLines=${layout.layoutInput.maxLines} " +
                        "softWrap=${layout.layoutInput.softWrap} layoutConstraints=$constraints",
                    exceededMaxLines || clippedWidth || clippedHeight
                )
            }
        }
        assertTrue("$label 未找到可检查的文本布局节点(nodes=${nodes.size}, checked=$checked)", checked > 0)
    }

    /** TalkBack 最低要求：每个可点击节点都有可读文本或 contentDescription。 */
    protected fun auditClickableSemantics(page: String): Int {
        val nodes = ui.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        nodes.forEach { node ->
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            assertTrue("$page: 可点击节点缺少可读文本/描述，bounds=${node.boundsInRoot}",
                texts.any { it.isNotBlank() } || descriptions.any { it.isNotBlank() })
        }
        return nodes.size
    }

    /** 在列表内向下滚动并逐屏检查已组合文本，覆盖首屏之外的惰性条目。 */
    protected fun scrollAndAudit(listTag: String, label: String, rounds: Int) {
        repeat(rounds) { index ->
            assertNoTruncationOnAllTexts("$label(第${index + 1}屏)")
            if (index < rounds - 1) {
                ui.onNodeWithTag(listTag).performTouchInput { swipeUp() }
                settle()
            }
        }
    }

    protected fun screenshot(name: String, tag: String) {
        val dir = File(context.getExternalFilesDir(null), "verification/pages-matrix").apply { mkdirs() }
        val out = File(dir, name)
        out.outputStream().use {
            ui.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private companion object {
        const val MATRIX_TAG = "PageMatrix"
    }
}
