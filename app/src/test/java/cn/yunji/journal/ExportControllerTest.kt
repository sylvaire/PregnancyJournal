package cn.yunji.journal

import cn.yunji.journal.data.ExportAccess
import cn.yunji.journal.data.ExportRequest
import cn.yunji.journal.data.ExportResult
import cn.yunji.journal.feature.ExportController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

/** 导出向导：明文确认门槛、两种导出方式都走 SAF 启动器。 */
class ExportControllerTest {

    private class Access : ExportAccess {
        override suspend fun preview(request: ExportRequest): Map<String, Int> = mapOf("pregnancies" to 1)
        override suspend fun exportData(
            destination: android.net.Uri,
            request: ExportRequest,
        ): ExportResult = ExportResult(true, "结构化导出完成", "1 条")

        override suspend fun exportSummary(
            destination: android.net.Uri,
            request: ExportRequest,
        ): ExportResult = ExportResult(true, "摘要已生成", "1 页")
    }

    private fun controller(launcher: ((String, String, String) -> Unit)?) =
        ExportController(CoroutineScope(Dispatchers.Unconfined), Access(), launcher)

    @Test fun bothExportKindsLaunchTheSystemPicker() {
        val launched = mutableListOf<Triple<String, String, String>>()
        val controller = controller { kind, payload, name -> launched += Triple(kind, payload, name) }
        controller.acknowledgePlaintext(true)

        controller.startDataExport()
        controller.startSummaryPdf()

        assertEquals(listOf("EXPORT_DATA", "SUMMARY_PDF"), launched.map { it.first })
        assertTrue("数据包文件名应以 .zip 结尾", launched[0].third.endsWith(".zip"))
        assertTrue("摘要文件名应以 .pdf 结尾", launched[1].third.endsWith(".pdf"))
    }

    @Test fun plaintextConfirmationIsRequiredBeforeAnyLaunch() {
        var launched = 0
        val controller = controller { _, _, _ -> launched++ }
        controller.startDataExport()
        controller.startSummaryPdf()
        assertEquals(0, launched)
        assertTrue(controller.state.value.message.orEmpty().contains("明文"))
    }

    @Test fun missingLauncherReportsInsteadOfSilentlyFailing() {
        // Activity 在 ViewModel 之后才设置 launcher；此时必须给出可读提示而不是无响应。
        val controller = controller(null)
        controller.acknowledgePlaintext(true)
        controller.startSummaryPdf()
        assertTrue(
            "缺少启动器时应提示无法选择保存位置",
            controller.state.value.message.orEmpty().contains("保存位置"),
        )
    }

    @Test fun returningFromSystemPickerKeepsScopeAndConfirmationUsable() {
        // 系统保存器会让应用进入后台并触发 leave()；若把“明文确认”一并清掉，
        // 返回后第二次导出会静默失效（用户看不到按钮为何没反应）。
        val launched = mutableListOf<String>()
        val controller = controller { kind, _, _ -> launched += kind }
        controller.acknowledgePlaintext(true)
        controller.startSummaryPdf()
        assertEquals(listOf("SUMMARY_PDF"), launched)

        controller.leave()

        assertTrue("返回后仍应保留明文确认", controller.state.value.plaintextAcknowledged)
        assertTrue("返回后导出仍应可用", controller.state.value.canStart)
        controller.startDataExport()
        assertEquals(listOf("SUMMARY_PDF", "EXPORT_DATA"), launched)
    }

    @Test fun resetClearsEverythingAtSessionBoundary() {
        val controller = controller { _, _, _ -> }
        controller.acknowledgePlaintext(true)
        controller.reset()
        assertFalse(controller.state.value.plaintextAcknowledged)
        assertEquals(cn.yunji.journal.core.export.ExportScope.ALL_SECTIONS, controller.state.value.sections)
    }
}
