package cn.yunji.journal

import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E01/A02 遗留验收续：空态 / 导入加载态 / 导入失败（错误+重试恢复入口）状态矩阵。
 *
 * 覆盖盘点与“已有覆盖/本次新增”对照见 docs/verification/2026-10-22-E01-pages-matrix/README.md。
 * 本类不限制设备配置（极端组合下也可运行），每个用例使用独立临时数据库。
 */
class UiStateMatrixTest : PageMatrixBaseTest() {

    // ---------- 1. 产检列表空态 ----------
    @Test fun s1VisitsEmptyState() {
        launchApp()
        openTab("产检", "visits-list")
        scrollListTo("visits-list", "还没有产检记录")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("还没有产检记录").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("还没有产检记录").assertIsDisplayed()
        ui.onNodeWithTag("visits-list").performScrollToNode(hasText("新建产检预约"))
        ui.onNodeWithText("新建产检预约").assertIsDisplayed().assert(hasClickAction())
    }

    // ---------- 2. 时间线空态（日常记录筛选下无事件；方案 A：底部 CTA + 3 筛选） ----------
    @Test fun s2TimelineEmptyState() {
        launchApp()
        openTab("记录", "records-hub-list")
        scrollListTo("records-hub-list", "日常记录")
        ui.onNodeWithText("日常记录").assertIsDisplayed().performClick(); settle()
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("timeline-list")).fetchSemanticsNodes().isNotEmpty() }
        scrollListTo("timeline-list", "还没有这类记录")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("还没有这类记录").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("还没有这类记录").assertIsDisplayed()
        ui.onNodeWithText("可写一条日常记录，或从产检、用药和健康指标录入。").assertExists()
        // 方案 A：底部主 CTA 文案按设计为「写一条日常备注」
        ui.onNodeWithText("写一条日常备注").assertExists().assert(hasClickAction())
        // 三个筛选保留（日常记录路由默认选中「日常」）
        ui.onNodeWithTag("timeline-filter-JOURNAL").assertIsSelected()
    }

    // ---------- 3. 用药方案空态 + 服药记录空态 ----------
    @Test fun s3MedicationAndIntakeEmptyStates() {
        launchApp()
        openProfileRow("用药方案")
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("medication-list")).fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("还没有用药方案").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("medication-list").performScrollToNode(hasText("还没有用药方案"))
        ui.onNodeWithText("还没有用药方案").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回上一级").performClick(); settle()
        openProfileRow("服药记录")
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("intake-list")).fetchSemanticsNodes().isNotEmpty() }
        scrollListTo("intake-list", "请先在用药方案中录入方案。")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("请先在用药方案中录入方案。").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("请先在用药方案中录入方案。").assertIsDisplayed()
    }

    // ---------- 4. 健康指标空态 ----------
    @Test fun s4MeasurementEmptyState() {
        launchApp()
        openProfileRow("健康指标")
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("measurement-list")).fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("还没有测量记录").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("measurement-list").performScrollToNode(hasText("还没有测量记录"))
        ui.onNodeWithText("还没有测量记录").assertIsDisplayed()
        ui.onNodeWithText("记录测量").assertExists()
    }

    // ---------- 5. 资料列表空态 ----------
    @Test fun s5DocumentEmptyState() {
        seedShortVisit(items = "常规产检")
        launchApp()
        openVisitDocuments("常规产检")
        scrollListTo("document-list", "还没有产检资料")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("还没有产检资料").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("还没有产检资料").assertIsDisplayed()
        ui.onNodeWithTag("document-list").performScrollToNode(hasTestTag("document-add"))
        ui.onNodeWithTag("document-add").performClick()
        ui.onNodeWithText("选图片").assertIsDisplayed()
        ui.onNodeWithText("选 PDF").assertExists()
        ui.onNodeWithText("拍照").assertExists()
    }

    // ---------- 6. 资料导入队列中（加载态） ----------
    @Test fun s6DocumentLoadingQueuedState() {
        val visitId = seedShortVisit(items = "常规产检")
        val source = createPngSource()
        enqueueDocument(visitId, source)
        launchApp()
        openVisitDocuments("常规产检")
        scrollListTo("document-list", "等待导入…")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("等待导入…").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("document-list").performScrollToNode(hasText("等待导入…"))
        ui.onNodeWithText("等待导入…").assertIsDisplayed()
        assertEquals("队列中的资料不应显示空态", 0, ui.onAllNodesWithText("还没有产检资料").fetchSemanticsNodes().size)
    }

    // ---------- 7. 资料导入失败（错误态）显示失败原因与重试/重新选择入口 ----------
    @Test fun s7DocumentFailedShowsRetryAndReselect() {
        val visitId = seedShortVisit(items = "常规产检")
        val source = createPngSource()
        val jobId = enqueueDocument(visitId, source)
        source.delete()
        runCatching { runBlocking { model.documents.process(jobId) } }
        launchApp()
        openVisitDocuments("常规产检")
        scrollListTo("document-list", "导入失败")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("导入失败").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("document-list").performScrollToNode(hasText("导入失败"))
        ui.onNodeWithText("导入失败").assertIsDisplayed()
        ui.onNodeWithTag("document-list").performScrollToNode(hasText("重新选择"))
        ui.onNodeWithText("重试").assertExists()
        ui.onNodeWithText("重新选择").assertIsDisplayed()
        ui.onNodeWithTag("document-list").performScrollToNode(hasText("图片"))
        ui.onNodeWithText("图片").performScrollTo().performClick()
        ui.onNodeWithTag("document-list").performScrollToNode(hasText("导入失败"))
        ui.onNodeWithText("导入失败").assertIsDisplayed()
        ui.onNodeWithText("重新选择").assertExists()
        screenshot("n22-import-failed.png", "document-list")
    }

    @Test fun s8MultipleDocumentsKeepCompactRowsAndReachableActions() {
        val visitId = seedShortVisit(items = "常规产检")
        val first = enqueueDocument(visitId, createPngSource())
        runBlocking { model.documents.process(first) }
        val second = enqueueDocument(visitId, createPngSource())
        runBlocking { model.documents.process(second) }
        runBlocking {
            model.documents.updateMetadata(first, "合成超声报告", "ULTRASOUND")
            model.documents.updateMetadata(second, "合成检验报告", "LAB")
        }
        launchApp()
        openVisitDocuments("常规产检")
        ui.onNodeWithTag("document-list").performScrollToNode(hasTestTag("document-$second"))
        ui.onNodeWithTag("document-$second").assertExists()
        ui.onNodeWithTag("document-list").performScrollToNode(hasTestTag("document-more-$first"))
        ui.onNodeWithTag("document-more-$first").assertIsDisplayed()
        ui.onNodeWithTag("document-$first").assertExists()
        screenshot("n22-multiple-collapsed.png", "document-list")
        ui.onNodeWithTag("document-more-$first").performClick()
        ui.onNodeWithTag("document-list").performScrollToNode(hasText("编辑"))
        ui.onNodeWithText("编辑").assertIsDisplayed()
    }

    @Test fun s9MultipleTrashedDocumentsKeepRestoreReachable() {
        val visitId = seedShortVisit(items = "常规产检")
        val first = enqueueDocument(visitId, createPngSource())
        runBlocking { model.documents.process(first) }
        val second = enqueueDocument(visitId, createPngSource())
        runBlocking { model.documents.process(second) }
        runBlocking {
            model.documents.updateMetadata(first, "合成报告甲", "ULTRASOUND")
            model.documents.updateMetadata(second, "合成报告乙", "LAB")
            model.documents.trash(first, schedule = false)
            model.documents.trash(second, schedule = false)
        }
        launchApp()
        openVisitDocuments("常规产检")
        ui.onNodeWithText("回收站（2）").performClick()
        ui.onNodeWithTag("document-list").performScrollToNode(hasTestTag("document-trash-more-$first"))
        ui.onNodeWithTag("document-trash-more-$first").assertIsDisplayed()
        screenshot("n22-multiple-trash.png", "document-list")
        ui.onNodeWithTag("document-trash-more-$first").performClick()
        ui.onNodeWithTag("document-list").performScrollToNode(hasText("恢复"))
        ui.onNodeWithText("恢复").assertIsDisplayed().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("回收站（1）").fetchSemanticsNodes().isNotEmpty() }
    }
}
