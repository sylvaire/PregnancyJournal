package cn.yunji.journal

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.yunji.journal.core.designsystem.PregnancyTheme
import cn.yunji.journal.core.export.ExportScope
import cn.yunji.journal.feature.ExportContent
import cn.yunji.journal.feature.ExportUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic callbacks only: the screen must explain the plaintext consequence and gate both exports. */
class ExportUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun exportsStayDisabledUntilThePlaintextConsequenceIsAcknowledged() {
        var data = 0; var summary = 0; var acknowledged = 0
        ui.setContent { PregnancyTheme { ExportContent(ExportUiState(), { _, _ -> }, {}, {}, {}, { acknowledged++ },
            {}, { data++ }, { summary++ }, {}) } }
        ui.onNodeWithTag("export-data").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("export-summary").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("export-ack").performScrollTo().performClick()
        assertEquals(1, acknowledged)
        // The state is owned by the caller, so the buttons remain gated until it confirms.
        ui.onNodeWithTag("export-data").performScrollTo().assertIsNotEnabled()
        assertEquals(0, data); assertEquals(0, summary)
        ui.onNodeWithText("我了解导出内容为明文，将自行妥善保管").performScrollTo().assertExists()
    }

    @Test fun acknowledgedStateEnablesBothExportsAndShowsScopeAndCounts() {
        var data = 0; var summary = 0
        val state = ExportUiState(plaintextAcknowledged = true, preview = mapOf("visits" to 2, "journal_entries" to 3))
        ui.setContent { PregnancyTheme { ExportContent(state, { _, _ -> }, {}, {}, {}, {}, {}, { data++ }, { summary++ }, {}) } }
        ui.onNodeWithTag("export-data").performScrollTo().assertIsEnabled().performClick()
        ui.onNodeWithTag("export-summary").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, data); assertEquals(1, summary)
        ui.onNodeWithTag("export-preview-total").performScrollTo().assertExists()
        ui.onNodeWithText("当前范围共 5 条记录").performScrollTo().assertExists()
        // Every business section must be offered.
        ui.onNodeWithTag("export-scope-toggle").performScrollTo().performClick()
        ExportScope.ALL_SECTIONS.forEach { ui.onNodeWithTag("export-section-$it").performScrollTo().assertExists() }
        ui.onNodeWithText("孕期档案").performScrollTo().assertExists()
    }

    @Test fun invalidRangeIsShownAndBlocksStarting() {
        var data = 0
        val invalid = ExportUiState(from = "2026-12-31", to = "2026-01-01", plaintextAcknowledged = true)
        assertTrue("state must report the invalid range", !invalid.rangeValid)
        ui.setContent { PregnancyTheme { ExportContent(invalid, { _, _ -> }, {}, {}, {}, {}, {}, { data++ }, {}, {}) } }
        ui.onNodeWithText("日期范围无效，请检查起止日期。").performScrollTo().assertExists()
        ui.onNodeWithTag("export-data").performScrollTo().assertIsNotEnabled()
        assertEquals(0, data)
    }

    @Test fun togglingASectionReportsTheSectionToTheCaller() {
        var toggled: String? = null
        val state = ExportUiState(plaintextAcknowledged = true)
        ui.setContent { PregnancyTheme { ExportContent(state, { _, _ -> }, { toggled = it }, {}, {}, {}, {}, {}, {}, {}) } }
        ui.onNodeWithTag("export-scope-toggle").performScrollTo().performClick()
        ui.onNodeWithTag("export-section-visits").performScrollTo().performClick()
        assertEquals("visits", toggled)
    }
}
