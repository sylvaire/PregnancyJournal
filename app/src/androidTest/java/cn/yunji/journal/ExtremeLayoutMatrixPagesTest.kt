package cn.yunji.journal

import androidx.compose.ui.test.*
import cn.yunji.journal.core.designsystem.PregnancyTheme
import cn.yunji.journal.feature.SecuritySettingsScreen
import cn.yunji.journal.feature.SecurityUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E01/A02 遗留验收续：首页/时间线、用药、设置（提醒/安全）、产检详情与编辑表单在
 * “系统字体200% + 深色 + 横屏”组合下的可达、不截断与语义断言。
 *
 * 资料列表与大图预览/元数据长表单已由 A02 的 ExtremeLayoutMatrixTest 覆盖（8/8），
 * 本类只在设备已切到该组合时运行，普通 full suite 下全部在 @Before 被 Assume 跳过。
 * 命令与证据见 docs/verification/2026-10-22-E01-pages-matrix/README.md。
 */
class ExtremeLayoutMatrixPagesTest : PageMatrixBaseTest() {
    override fun requiresExtremeConfiguration() = true

    // ---------- p1. 四个一级入口 + 底部导航 + 首页长列表可达 ----------
    @Test fun p1HomeAndRootEntrancesExtreme() {
        assertExtreme()
        seedShortVisit()
        launchApp()
        assertNoTruncationOnAllTexts("首页首屏")
        val entries = listOf("今天" to "home-list", "产检" to "visits-list", "记录" to "records-hub-list", "我的" to "profiles-root-list")
        entries.forEach { (label, pageTag) ->
            val tab = ui.onNode(hasText(label) and hasClickAction())
            tab.assertIsDisplayed()
            assertNoTruncation(hasText(label), "底部导航“$label”")
            tab.performClick(); settle()
            ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag(pageTag)).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag(pageTag).assertIsDisplayed()
            if (pageTag == "home-list") {
                ui.onNodeWithTag("home-list").performScrollToNode(hasText("管理我的孕期"))
                ui.onNodeWithText("管理我的孕期").assertIsDisplayed()
                assertNoTruncationOnAllTexts("首页底部")
            }
            screenshot("p1-$pageTag.png", pageTag)
        }
        ui.onNode(hasText("今天") and hasClickAction()).performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("home-list")).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("home-list").performScrollToNode(hasText("新建产检预约"))
        ui.onNodeWithText("新建产检预约").assertIsDisplayed()
        val audited = auditClickableSemantics("首页")
        assertTrue("首页可点击节点过少，实际 $audited", audited >= 3)
    }

    // ---------- p2. 时间线长内容（产检 + 日常记录）按设计重做后的不截断 ----------
    @Test fun p2TimelineLongContentExtreme() {
        assertExtreme()
        // 设计 12-Timeline 的日期组头需要孕周：给孕期补 EDD（今天+120 天 => 事件当天的孕周约 22 周）。
        runBlocking { repository.savePregnancy(pregnancyId, "其余页面极端孕期", null, java.time.LocalDate.now().plusDays(120), "", "") }
        seedShortVisit()
        seedJournalEntry()
        seedJournalEntry(time = "", text = "第二条：没有记录发生时间，用来检查杏色备注卡与记录行的排版。")
        launchApp()
        openTab("记录", "records-hub-list")
        ui.onNodeWithTag("records-hub-list").performScrollToNode(hasText("孕期时间线"))
        ui.onNodeWithText("孕期时间线").performScrollTo().assertIsDisplayed().performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("timeline-list")).fetchSemanticsNodes().isNotEmpty() }
        // 方案 A：底部主 CTA「写一条日常备注」（不再有顶部 CTA/搜索框/说明段）
        ui.waitUntil(10_000) { ui.onAllNodesWithText("写一条日常备注").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("有种子数据时不应显示时间线空态", 0, ui.onAllNodesWithText("还没有这类记录").fetchSemanticsNodes().size)
        scrollAndAudit("timeline-list", "孕期时间线", rounds = 3)
        ui.onNodeWithText("写一条日常备注").assertIsDisplayed()
        // 日期组头 + 孕周文本（设计：日期左侧、孕周右侧）
        scrollListTo("timeline-list", "孕", substring = true)
        assertNoTruncation(hasText("孕", substring = true), "日期组头孕周")
        // 杏色日常备注卡（JOURNAL）在极端配置下也要完整可达
        scrollListTo("timeline-list", "日常备注 · ", substring = true)
        assertNoTruncation(hasText("日常备注 · ", substring = true), "杏色备注卡说明")
        screenshot("p2-timeline.png", "timeline-list")
    }

    // ---------- p3. 用药方案列表 + 编辑长表单 + 服药记录列表 ----------
    @Test fun p3MedicationPagesExtreme() {
        assertExtreme()
        val drugName = "复合维生素片（孕妇型）"
        seedMedicationPlan()
        launchApp()
        openProfileRow("用药方案")
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("medication-list")).fetchSemanticsNodes().isNotEmpty() }
        scrollListTo("medication-list", drugName, substring = true)
        ui.onNodeWithText(drugName, substring = true).assertIsDisplayed()
        ui.onNodeWithTag("medication-list").performScrollToNode(hasText("新增用药方案"))
        ui.onNodeWithText("新增用药方案").assertIsDisplayed().performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("medication-name")).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("medication-name").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("medication-dose").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("medication-unit").performScrollTo().assertIsDisplayed()
        hideKeyboard()
        ui.onNodeWithText("保存用药方案").performScrollTo().assertIsDisplayed()
        assertNoTruncationOnAllTexts("用药方案编辑页")
        val audited = auditClickableSemantics("用药方案编辑页")
        assertTrue("用药方案编辑页可点击节点过少，实际 $audited", audited >= 2)
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("medication-list")).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("profiles-root-list")).fetchSemanticsNodes().isNotEmpty() }
        openProfileRow("服药记录")
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("intake-list")).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("补记服药").assertIsDisplayed()
        assertNoTruncationOnAllTexts("服药记录首屏")
        screenshot("p3-intakes.png", "服药记录")
    }

    // ---------- p4. 设置：提醒设置页 ----------
    @Test fun p4ReminderSettingsExtreme() {
        assertExtreme()
        launchApp()
        openProfileRow("提醒设置")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("通知权限").fetchSemanticsNodes().isNotEmpty() }
        listOf("通知权限", "提醒时间", "提醒类型", "未来7天提醒").forEach { label ->
            ui.onNode(hasText(label) and !hasClickAction()).performScrollTo().assertIsDisplayed()
            assertNoTruncation(hasText(label), "提醒设置“$label”标题")
        }
        listOf("reminder-permission", "reminder-exact", "reminder-visits", "reminder-medications", "reminder-rebuild", "reminder-battery", "open-app-details").forEach { tag ->
            ui.onNodeWithTag(tag).assertExists()
        }
        ui.onNodeWithText("刷新未来7天提醒").performScrollTo().assertIsDisplayed()
        assertNoTruncationOnAllTexts("提醒设置页")
        screenshot("p4-reminders.png", "提醒设置")
    }

    // ---------- p5. 设置：安全设置（独立渲染，避免依赖系统生物识别） ----------
    @Test fun p5SecuritySettingsExtreme() {
        assertExtreme()
        ui.setContent {
            PregnancyTheme { SecuritySettingsScreen(SecurityUi(enabled = true, strict = false, timeout = 5)) {} }
        }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("应用锁").fetchSemanticsNodes().isNotEmpty() }
        listOf("应用锁", "离开应用后锁定", "严格模式").forEach { label ->
            ui.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            assertNoTruncation(hasText(label), "安全设置“$label”标题")
        }
        listOf("关闭应用锁", "开启严格模式", "立即锁定").forEach { label ->
            ui.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        assertNoTruncationOnAllTexts("安全设置页")
        assertNoTruncation(hasText("立即锁定"), "立即锁定按钮")
        screenshot("p5-security.png", "security-settings")
    }

    // ---------- p6. 产检详情 + 编辑表单 ----------
    @Test fun p6PrenatalListDetailAndEditorExtreme() {
        assertExtreme()
        seedCompletedLongVisit()
        launchApp()
        openTab("产检", "visits-list")
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("visits-list")).fetchSemanticsNodes().isNotEmpty() }
        assertNoTruncationOnAllTexts("产检列表首屏")
        scrollListTo("visits-list", "糖耐量试验（OGTT）", substring = true)
        ui.onNodeWithText("糖耐量试验（OGTT）", substring = true).assertIsDisplayed().performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("visit-detail-list")).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("visit-detail-list").performScrollToNode(hasText("就诊备注与医嘱"))
        ui.onNodeWithText("就诊备注与医嘱").assertIsDisplayed()
        assertNoTruncation(hasText("血糖正常；继续补充叶酸，四周后复查。"), "就诊备注正文")
        ui.onNodeWithTag("visit-detail-list").performScrollToNode(hasText("准备事项"))
        ui.onNodeWithText("准备事项").assertIsDisplayed()
        screenshot("p6-visit-detail.png", "visit-detail-list")
        ui.onNodeWithTag("visit-detail-list").performScrollToNode(hasText("修改预约"))
        ui.onNodeWithText("修改预约").assertIsDisplayed().performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("预约日期").fetchSemanticsNodes().isNotEmpty() }
        listOf("预约日期", "预约时间（选填）", "医院（选填）", "检查项目（选填）", "准备事项（选填）").forEach { label ->
            ui.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        hideKeyboard()
        ui.onNodeWithText("保存预约").performScrollTo().assertIsDisplayed()
        assertNoTruncationOnAllTexts("修改产检预约页")
        screenshot("p6-visit-editor.png", "修改产检预约")
    }
}
