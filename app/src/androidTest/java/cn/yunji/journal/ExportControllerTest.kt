package cn.yunji.journal

import cn.yunji.journal.core.export.ExportScope
import cn.yunji.journal.data.ExportAccess
import cn.yunji.journal.data.ExportRequest
import cn.yunji.journal.data.ExportRequestCodec
import cn.yunji.journal.data.ExportResult
import cn.yunji.journal.feature.ExportController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Wizard gating. Instrumented only because the destination type is an Android `Uri`; the logic under test
 * is otherwise device-independent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportControllerTest {
    private class FakeAccess : ExportAccess {
        var previewCalls = 0
        var dataCalls = 0
        var summaryCalls = 0
        var failNext = false
        var lastRequest: ExportRequest? = null
        override suspend fun preview(request: ExportRequest): Map<String, Int> {
            previewCalls++; lastRequest = request
            return mapOf("visits" to 2, "journal_entries" to 3)
        }
        override suspend fun exportData(destination: android.net.Uri, request: ExportRequest): ExportResult {
            dataCalls++; lastRequest = request
            return if (failNext) ExportResult(false, "导出未完成") else ExportResult(true, "导出完成", "共 5 条记录")
        }
        override suspend fun exportSummary(destination: android.net.Uri, request: ExportRequest): ExportResult {
            summaryCalls++; lastRequest = request
            return if (failNext) ExportResult(false, "摘要未生成") else ExportResult(true, "就诊摘要已生成", "共 3 页")
        }
    }

    private class Recorder { val started = mutableListOf<Triple<String, String, String>>() }

    private fun controller(scope: TestScope, access: FakeAccess, recorder: Recorder) =
        ExportController(scope, access) { kind, payload, name -> recorder.started += Triple(kind, payload, name) }

    private val destination: android.net.Uri = android.net.Uri.parse("content://synthetic/export")

    @Test fun exportCannotStartBeforeThePlaintextConfirmation() = runTest {
        val access = FakeAccess(); val recorder = Recorder()
        val subject = controller(this, access, recorder)
        subject.startDataExport()
        assertTrue("no picker may open before confirmation", recorder.started.isEmpty())
        assertTrue(requireNotNull(subject.state.value.message).contains("明文"))

        subject.acknowledgePlaintext(true)
        subject.startDataExport()
        assertEquals(1, recorder.started.size)
        assertEquals("EXPORT_DATA", recorder.started.single().first)
        assertTrue(recorder.started.single().third.endsWith(".zip"))
    }

    @Test fun summaryUsesPdfNameAndSameConfirmationRule() = runTest {
        val access = FakeAccess(); val recorder = Recorder()
        val subject = controller(this, access, recorder)
        subject.acknowledgePlaintext(true)
        subject.startSummaryPdf()
        assertEquals("SUMMARY_PDF", recorder.started.single().first)
        assertTrue(recorder.started.single().third.endsWith(".pdf"))
        val decoded = ExportRequestCodec.decode(recorder.started.single().second)
        assertEquals(ExportScope.ALL_SECTIONS, decoded.sections)
    }

    @Test fun invalidRangeBlocksBothExportsAndIsReported() = runTest {
        val access = FakeAccess(); val recorder = Recorder()
        val subject = controller(this, access, recorder)
        subject.acknowledgePlaintext(true)
        subject.updateRange("2026-12-31", "2026-01-01")
        assertFalse(subject.state.value.rangeValid)
        subject.startDataExport(); subject.startSummaryPdf()
        assertTrue(recorder.started.isEmpty())
        assertTrue(requireNotNull(subject.state.value.message).contains("日期范围"))

        subject.updateRange("2026-13-01", "")
        assertFalse("an impossible calendar date must be rejected", subject.state.value.rangeValid)
    }

    @Test fun atLeastOneSectionMustRemainSelected() = runTest {
        val access = FakeAccess(); val recorder = Recorder()
        val subject = controller(this, access, recorder)
        val first = ExportScope.ALL_SECTIONS.first()
        ExportScope.ALL_SECTIONS.drop(1).forEach { subject.toggleSection(it) }
        assertEquals("only the first section should remain", setOf(first), subject.state.value.sections)
        subject.toggleSection(first)
        assertEquals("the last section cannot be removed", setOf(first), subject.state.value.sections)
        assertTrue(requireNotNull(subject.state.value.message).contains("至少选择一个"))
    }

    @Test fun previewReportsCountsAndPassesTheChosenScope() = runTest {
        val access = FakeAccess(); val recorder = Recorder()
        val subject = controller(this, access, recorder)
        subject.updateRange("2026-01-01", "2026-06-30")
        subject.setTrash(true)
        subject.setOriginals(false)
        // Sections start fully selected; toggling removes one.
        subject.toggleSection("measurements")
        subject.preview()
        advanceUntilIdle()
        assertEquals(1, access.previewCalls)
        assertEquals(5, subject.state.value.preview.values.sum())
        val sent = requireNotNull(access.lastRequest)
        assertEquals("2026-01-01", sent.from); assertEquals("2026-06-30", sent.to)
        assertTrue(sent.includeTrash); assertFalse(sent.includeOriginals)
        assertFalse("the toggled-off section must not be exported", "measurements" in sent.sections)
        assertTrue("other sections must stay selected", "visits" in sent.sections)
    }

    @Test fun applyingTheChosenDestinationReportsSuccessAndFailure() = runTest {
        val access = FakeAccess(); val recorder = Recorder()
        val subject = controller(this, access, recorder)
        subject.acknowledgePlaintext(true)
        val payload = ExportRequestCodec.encode(subject.request())

        subject.apply(destination, "SUMMARY_PDF", payload)
        advanceUntilIdle()
        assertEquals(1, access.summaryCalls)
        assertFalse(subject.state.value.busy)
        assertTrue(requireNotNull(subject.state.value.message).contains("摘要"))
        assertEquals("SUMMARY_PDF", subject.state.value.lastResult)

        access.failNext = true
        subject.apply(destination, "EXPORT_DATA", payload)
        advanceUntilIdle()
        assertEquals(1, access.dataCalls)
        assertNull("a failed export must not be recorded as a result", subject.state.value.lastResult)
        assertEquals("导出未完成", subject.state.value.message)
    }

    @Test fun invalidHandoffNeverExpandsScopeOrWritesDestination() = runTest {
        val access = FakeAccess(); val recorder = Recorder()
        val subject = controller(this, access, recorder)
        val valid = ExportRequestCodec.encode(ExportRequest(sections = setOf("visits"), includeOriginals = false))
        val invalid = listOf(null, "not-json", "{}", valid.replace("\"from\":null", "\"from\":\"2026-13-01\""),
            valid.dropLast(1) + ",\"unknown\":true}")
        for (payload in invalid) {
            for (kind in listOf("EXPORT_DATA", "SUMMARY_PDF")) {
                subject.apply(destination, kind, payload)
                advanceUntilIdle()
                assertEquals(0, access.dataCalls)
                assertEquals(0, access.summaryCalls)
                assertFalse(subject.state.value.busy)
                assertNull(subject.state.value.lastResult)
                assertEquals("导出设置已失效，请重新选择范围和保存位置", subject.state.value.message)
            }
        }
        subject.apply(destination, "UNKNOWN", valid)
        advanceUntilIdle()
        assertEquals(0, access.dataCalls)
        subject.apply(destination, "EXPORT_DATA", valid)
        advanceUntilIdle()
        assertEquals(1, access.dataCalls)
        assertEquals(setOf("visits"), requireNotNull(access.lastRequest).sections)
        assertFalse(requireNotNull(access.lastRequest).includeOriginals)
    }
    @Test fun missingLauncherIsReportedInsteadOfCrashing() = runTest {
        val access = FakeAccess()
        val subject = ExportController(this, access, null)
        subject.acknowledgePlaintext(true)
        subject.startDataExport()
        assertTrue(requireNotNull(subject.state.value.message).contains("无法选择保存位置"))
    }
}
