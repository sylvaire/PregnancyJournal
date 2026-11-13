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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.database.JournalDatabase
import cn.yunji.journal.core.designsystem.PregnancyTheme
import cn.yunji.journal.core.security.VaultCrypto
import cn.yunji.journal.data.ActivePregnancyPreferences
import cn.yunji.journal.data.JournalRepository
import cn.yunji.journal.feature.JournalApp
import cn.yunji.journal.feature.JournalViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.YearMonth
import java.util.UUID

/**
 * A02/A03/E01 遗留验收：系统字体 200% + 深色 + 横屏 组合下的可达性、不截断与语义断言。
 *
 * 本类只在设备已切到该组合时运行；普通 full suite 下 8 项都会在 @Before 被 Assume 跳过，
 * 不影响既有基线。配置与证据命令见 docs/verification/2026-10-22-A02-ui-matrix/README.md。
 */
class ExtremeLayoutMatrixTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: JournalDatabase
    private lateinit var file: File
    private lateinit var model: JournalViewModel
    private lateinit var repository: JournalRepository
    private lateinit var pregnancyId: String
    private lateinit var documentRoot: File
    private val documentSources = mutableListOf<File>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        Log.i("ExtremeMatrix", "setUp start")
        assumeTrue("需要系统字体200%+深色+横屏组合，当前=${configSummary()}", extremeConfig())
        file = File(context.cacheDir, "extreme-matrix-${UUID.randomUUID()}.db")
        db = JournalDatabase.open(context, file, VaultCrypto.randomKey())
        val repo = JournalRepository(db, Clock.systemDefaultZone())
        repository = repo
        val id = runBlocking { repo.savePregnancy(null, "极端布局孕期", null, null, "", "") }
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
        documentRoot = File(context.cacheDir, "extreme-documents-${UUID.randomUUID()}").apply { mkdirs() }
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
                override suspend fun upload() = error("Cloud upload must not run in layout matrix tests")
            }
        }
        val fixtureRecovery by lazy {
            val material=cn.yunji.journal.core.security.VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
            cn.yunji.journal.data.RecoverySettings(importContext,material)
        }
        model = JournalViewModel(repo, SavedStateHandle(), preferences, dagger.Lazy { fixtureImporter }, dagger.Lazy { fixtureReminders },dagger.Lazy {fixtureCloud},dagger.Lazy {fixtureRecovery},dagger.Lazy {fixtureBackup})
        ui.setContent { PregnancyTheme { JournalApp(model) } }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("今天，慢慢来").fetchSemanticsNodes().isNotEmpty() }
        settle()
        Log.i("ExtremeMatrix", "setUp done")
    }

    @After fun tearDown() {
        Log.i("ExtremeMatrix", "tearDown start")
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED)
        if (::model.isInitialized) runBlocking { model.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.let { job -> job.cancel(); job.join() } }
        if (::db.isInitialized) db.close()
        if (::documentRoot.isInitialized) documentRoot.deleteRecursively()
        documentSources.forEach { it.delete() }
        if (::file.isInitialized) listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    // ---------- 1. 四个一级入口 + 底部导航在极端组合下可达 ----------
    @Test fun t1FourRootEntrancesAndBottomBarReachable() {
        assertExtreme()
        val entries = listOf("今天" to "home-list", "产检" to "visits-list", "记录" to "records-hub-list", "我的" to "profiles-root-list")
        entries.forEach { (label, pageTag) ->
            val tab = ui.onNode(hasText(label) and hasClickAction())
            tab.assertIsDisplayed()
            tab.performClick(); settle()
            ui.waitUntil(10_000) { ui.onAllNodesWithTag(pageTag).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag(pageTag).assertIsDisplayed()
            tab.assertIsDisplayed()
            assertNoTruncation(hasText(label), "底部导航/页面标题“$label”")
            // 首页大标题只在“今天”tab 下存在，必须在该 tab 内检查，否则切到其它 tab 后节点已不存在。
            if (pageTag == "home-list") assertNoTruncation(hasText("今天，慢慢来"), "首页标题")
            screenshot("t1-$pageTag.png", pageTag)
        }
        // 任意子页面返回根页后仍应回到同一 tab。
        ui.onNode(hasText("记录") and hasClickAction()).performClick(); settle()
        ui.onNodeWithText("日常记录", substring = false).performClick(); settle()
        ui.onNodeWithContentDescription("返回上一级").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("records-hub-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(hasText("记录") and hasClickAction()).assertIsDisplayed()
    }

    // ---------- 2. 主页长列表动作可达（LazyColumn 屏幕外条目） ----------
    @Test fun t2HomeLongListActionsReachable() {
        assertExtreme()
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("新建产检预约"))
        ui.onNodeWithText("新建产检预约").assertIsDisplayed()
        ui.onNodeWithText("新建产检预约").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("新建产检预约").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("保存预约").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("管理我的孕期"))
        ui.onNodeWithText("管理我的孕期").assertIsDisplayed()
        ui.onNodeWithText("管理我的孕期").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("管理孕期").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("profiles-list").performScrollToNode(hasText("编辑"))
        ui.onNodeWithText("编辑").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.onNodeWithTag("home-list").assertExists()
    }

    // ---------- 3. 管理孕期列表 + 编辑长表单字段可达 ----------
    @Test fun t3PregnancyEditorLongFormReachable() {
        assertExtreme()
        openPregnancyManager()
        ui.onNodeWithTag("profiles-list").performScrollToNode(hasText("编辑"))
        ui.onNodeWithText("编辑").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("编辑孕期档案").fetchSemanticsNodes().isNotEmpty() }
        val labels = listOf("档案名称", "医生确认的预产期（选填）", "末次月经日期（选填）", "建档医院（选填）", "备注（选填）", "保存档案")
        labels.forEach { label ->
            ui.onNodeWithTag("pregnancy-editor-list").performScrollToNode(hasText(label))
            ui.onNodeWithText(label).assertIsDisplayed()
            // 惰性列表只会保留视口附近的项：滚动到每个标签时就地检查它，避免滚到末尾后再找顶部标签。
            if (label == "档案名称") assertNoTruncation(hasText(label), "档案名称字段标签")
        }
        screenshot("t3-pregnancy-editor.png", "编辑孕期档案")
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("profiles-list").fetchSemanticsNodes().isNotEmpty() }
    }

    // ---------- 4. 新建孕期长表单填写并保存 ----------
    @Test fun t4CreatePregnancyLongFormSaves() {
        assertExtreme()
        Log.i("ExtremeMatrix", "t4: 1 open manager")
        openPregnancyManager()
        Log.i("ExtremeMatrix", "t4: 2 click 创建新的孕期档案")
        ui.onNodeWithTag("profiles-list").performScrollToNode(hasText("创建新的孕期档案"))
        ui.onNodeWithText("创建新的孕期档案").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("创建孕期档案").fetchSemanticsNodes().isNotEmpty() }
        Log.i("ExtremeMatrix", "t4: 3 editor opened, scroll to 档案名称")
        ui.onNodeWithTag("pregnancy-editor-list").performScrollToNode(hasText("档案名称"))
        Log.i("ExtremeMatrix", "t4: 4 type title")
        // 新建档案默认名称为“我的孕期”，必须整体替换而不是追加，否则保存后的标题会包含默认名。
        ui.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextReplacement("极端布局新档案")
        // 200% 字体 + 横屏时输入法占去大半高度，先收起再滚动，避免视图区过窄导致滚动定位无法完成。
        hideKeyboard()
        assertTrue("档案名称输入框应包含刚输入的文字",
            ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains("极端布局新档案") == true
            })
        screenshot("t4-editor-filled.png", "创建孕期档案")
        Log.i("ExtremeMatrix", "t4: 5 scroll to 保存档案")
        ui.onNodeWithTag("pregnancy-editor-list").performScrollToNode(hasText("保存档案"))
        Log.i("ExtremeMatrix", "t4: 6 click save")
        ui.onNodeWithText("保存档案").assertIsDisplayed().performClick(); settle()
        Log.i("ExtremeMatrix", "t4: 7 wait db")
        ui.waitUntil(10_000) { runBlocking { db.journalDao().allPregnancies().size >= 2 } }
        assertTrue("保存后应写入第二个孕期档案，实际=${runBlocking { db.journalDao().allPregnancies() }.map { it.title }}",
            runBlocking { db.journalDao().allPregnancies() }.any { it.title == "极端布局新档案" })
        Log.i("ExtremeMatrix", "t4: 8 done")
    }

    // ---------- 5. 新建产检长表单填写并保存（横屏 200% 字体下的长表单） ----------
    @Test fun t5CreateVisitLongFormSaves() {
        assertExtreme()
        openTab("产检", "visits-list")
        ui.onNodeWithTag("visits-list").performScrollToNode(hasText("新建产检预约"))
        ui.onNodeWithText("新建产检预约").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("新建产检预约").fetchSemanticsNodes().isNotEmpty() }
        listOf("预约日期", "预约时间（选填）", "医院（选填）", "检查项目（选填）", "准备事项（选填）").forEach { label ->
            ui.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        ui.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextInput("极端布局医院")
        // 同上：极端配置下输入法会挤掉底部按钮的可视区域，先收起输入法再滚动到底部。
        hideKeyboard()
        ui.onNodeWithText("保存预约").performScrollTo().assertIsDisplayed().performClick(); settle()
        ui.waitUntil(10_000) { runBlocking { db.journalDao().allVisits().size == 3 } }
        assertTrue("保存的产检应带医院名",
            runBlocking { db.journalDao().allVisits() }.any { it.hospital == "极端布局医院" })
    }

    // ---------- 6. 资料列表 / 大图预览 / 元数据编辑长表单（E01 遗留重点） ----------
    @Test fun t6DocumentsListPreviewAndMetadataForm() {
        assertExtreme()
        val visit = runBlocking { db.journalDao().allVisits().first() }
        val source = File(context.cacheDir, "capture/${UUID.randomUUID()}.png").apply { parentFile!!.mkdirs() }
        documentSources.add(source)
        val bitmap = Bitmap.createBitmap(900, 1400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val paint = Paint().apply { color = Color.DKGRAY; textSize = 42f; isAntiAlias = true }
        val canvas = Canvas(bitmap)
        repeat(10) { canvas.drawText("Extreme layout report ${it + 1}", 60f, 120f + it * 110f, paint) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val documentId = runBlocking {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.capture", source)
            model.documents.enqueue(visit.id, listOf(uri), schedule = false)
            val job = db.journalDao().pendingImports().single()
            model.documents.process(job.id)
            model.documents.updateMetadata(job.id, "极端布局报告", "OTHER", java.time.LocalDate.now().toString(), "极端布局医院", "长表单备注")
            job.id
        }
        openTab("产检", "visits-list")
        ui.onNodeWithTag("visits-list").performScrollToNode(hasText("月末测试", substring = true))
        ui.onNodeWithText("月末测试", substring = true).performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("visit-detail-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("visit-detail-list").performScrollToNode(hasText("产检资料"))
        ui.onNodeWithText("产检资料").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("document-list").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("搜索名称、备注或医院").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("document-search").performScrollTo().assertIsDisplayed()
        // 屏幕外的惰性列表条目不会被组合：必须在 document-list 内滚动到目标再断言。
        ui.waitUntil(15_000) {
            runCatching { ui.onNodeWithTag("document-list").performScrollToNode(hasText("极端布局报告")) }.isSuccess
        }
        ui.onNodeWithText("极端布局报告").assertIsDisplayed()
        screenshot("t6-documents-list.png", "产检资料")
        assertNoTruncation(hasText("极端布局报告"), "资料名称")
        ui.onNodeWithTag("document-list").performScrollToNode(hasText("查看大图"))
        ui.onNodeWithText("查看大图").assertIsDisplayed().performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("document-image").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("document-image").assertIsDisplayed()
        screenshot("t6-document-preview.png", "document-preview")
        ui.onNodeWithText("返回", substring = false).performClick(); settle()
        ui.onNodeWithTag("document-list").performScrollToNode(hasText("编辑"))
        ui.onNodeWithText("编辑").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("document-name").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("document-name").assertIsDisplayed()
        ui.onNodeWithTag("document-note").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("编辑资料").assertExists()
        ui.onNodeWithTag("document-name").performTextReplacement("极端布局报告改")
        hideKeyboard()
        ui.onNodeWithText("保存").performClick(); settle()
        ui.waitUntil(10_000) { runBlocking { db.journalDao().attachment(documentId)?.name == "极端布局报告改" } }
        assertTrue("资料元数据长表单应保存成功", runBlocking { db.journalDao().attachment(documentId)?.name == "极端布局报告改" })
    }

    // ---------- 7. 日常记录长表单（新增 + 保存） ----------
    @Test fun t7JournalEditorLongFormSaves() {
        assertExtreme()
        openTab("记录", "records-hub-list")
        ui.onNodeWithText("日常记录", substring = false).performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("timeline-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("写一条日常备注").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("journal-text").fetchSemanticsNodes().isNotEmpty() }
        listOf("发生日期", "journal-time", "journal-symptom", "journal-mood", "journal-text").forEach { marker ->
            val node = if (marker.startsWith("journal-")) ui.onNodeWithTag(marker) else ui.onNodeWithText(marker)
            node.performScrollTo().assertIsDisplayed()
        }
        ui.onNodeWithTag("journal-text").performScrollTo().performTextInput("极端布局下的长表单正文")
        ui.onNodeWithTag("journal-mood").performScrollTo().performTextInput("平静")
        // 200% 字体 + 横屏时输入法会占去大半高度，先收起再断言底部按钮完全可见。
        hideKeyboard()
        screenshot("t7-journal-editor.png", "写日常记录")
        val save = ui.onNodeWithText("保存日常记录")
        save.performScrollTo()
        save.assertIsDisplayed()
        save.performClick(); settle()
        ui.waitUntil(10_000) { runBlocking { db.journalDao().allJournalEntries().isNotEmpty() } }
        assertTrue("日常记录应保存成功",
            runBlocking { db.journalDao().allJournalEntries() }.any { it.text == "极端布局下的长表单正文" })
    }

    // ---------- 8. TalkBack 语义级断言（contentDescription / 可聚焦 / 可读文本） ----------
    @Test fun t8SemanticsForTalkBack() {
        assertExtreme()
        openTab("记录", "records-hub-list")
        ui.onNodeWithText("日常记录", substring = false).performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("timeline-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("返回上一级").assertIsDisplayed()
        ui.onNodeWithText("写一条日常备注").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("journal-text").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("journal-text").assert(hasSetTextAction())
        ui.onNodeWithTag("journal-mood").assert(hasSetTextAction())
        ui.onNodeWithText("保存日常记录").performScrollTo().assert(hasClickAction())
        val audited = auditClickableSemantics("写日常记录页")
        assertTrue("页面上应存在可点击节点，实际 $audited", audited >= 2)
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("timeline-list").fetchSemanticsNodes().isNotEmpty() }
        val timelineAudited = auditClickableSemantics("孕期时间线页")
        assertTrue("时间线页可点击节点过少，实际 $timelineAudited", timelineAudited >= 2)
    }

    // ---------- 基础设施 ----------
    private fun extremeConfig(): Boolean {
        val c = context.resources.configuration
        return c.fontScale >= 1.9f &&
            (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES &&
            c.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun configSummary(): String {
        val c = context.resources.configuration
        return "fontScale=${c.fontScale}, night=${if ((c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) "yes" else "no"}, orientation=${if (c.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"}"
    }

    private fun assertExtreme() {
        val c = context.resources.configuration
        assertEquals("系统字体缩放必须是200%", 2.0f, c.fontScale, 0.05f)
        assertEquals("系统必须处于深色模式", Configuration.UI_MODE_NIGHT_YES, c.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals("系统必须处于横屏", Configuration.ORIENTATION_LANDSCAPE, c.orientation)
    }

    private fun openTab(label: String, pageTag: String) {
        ui.onNode(hasText(label) and hasClickAction()).performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag(pageTag).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag(pageTag).assertExists()
    }

    /** 从“我的”一级入口进入管理孕期（profiles-root-list → profiles-list）。 */
    private fun openPregnancyManager() {
        openTab("我的", "profiles-root-list")
        ui.onNodeWithTag("profiles-root-list").performScrollToNode(hasText("管理孕期"))
        ui.onNodeWithText("管理孕期").assertIsDisplayed().performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("profiles-list").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun hideKeyboard() {
        ui.activityRule.scenario.onActivity { activity ->
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        settle()
    }

    private fun settle() {
        ui.waitForIdle()
        val target = SystemClock.uptimeMillis() + 350
        ui.waitUntil(2_000) { SystemClock.uptimeMillis() >= target }
    }

    /**
     * 断言文本没有因 maxLines/高度/宽度约束被截断（基于 Compose 的 TextLayoutResult）。
     * Compose 的 didOverflowWidth/Height 是严格浮点比较，亚像素取整（例如 CJK 标题多出 0.5px）
     * 会被误报为“截断”；这里保留 1px 取整容差，同时仍能发现真正的裁剪与 maxLines 截断。
     */
    private fun assertNoTruncation(matcher: SemanticsMatcher, label: String) {
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
                // Compose 的 didOverflowWidth = “节点宽度 < 段落排版宽度”，而段落宽度取的是布局约束上限
                // （wrap-content 的 Text 也会拿到父级的 maxWidth），因此它本身会把正常换行/包裹误报为截断。
                // 真正的水平裁剪只发生在节点已经用满可用宽度、段落仍超出时。
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
    private fun auditClickableSemantics(page: String): Int {
        val nodes = ui.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        nodes.forEach { node ->
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            assertTrue("$page: 可点击节点缺少可读文本/描述，bounds=${node.boundsInRoot}",
                texts.any { it.isNotBlank() } || descriptions.any { it.isNotBlank() })
        }
        return nodes.size
    }

    private fun screenshot(name: String, tag: String) {
        val dir = File(context.getExternalFilesDir(null), "verification/extreme").apply { mkdirs() }
        val out = File(dir, name)
        out.outputStream().use {
            ui.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}


