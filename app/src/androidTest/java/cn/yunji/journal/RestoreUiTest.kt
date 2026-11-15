package cn.yunji.journal

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.yunji.journal.core.backup.RestorePreview
import cn.yunji.journal.feature.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Dedicated emulator only; pure screen callbacks never restore a real vault. */
class RestoreUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val preview=RestorePreview("synthetic-generation","synthetic-snapshot",1,1,2,3,4,5,6,7,"synthetic-writer",8)
    @Test fun selectedFileContextRemainsBesideTheKeyAndUpdatesWhenReplaced() {
        val state=mutableStateOf(RestoreUiState(stage="KEY",documentName="第一份合成备份.yunji"))
        rule.setContent {MaterialTheme {RestoreContent(state.value,{},{},{},{},{},{},{})}}
        rule.onNodeWithText("第一份合成备份.yunji").assertIsDisplayed()
        rule.onNodeWithText("文件大小暂不可用").assertExists()
        rule.onNodeWithTag("restore-key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        rule.runOnIdle {state.value=state.value.copy(documentName="第二份合成备份.yunji",documentSize=4096)}
        rule.onNodeWithText("第一份合成备份.yunji").assertDoesNotExist()
        rule.onNodeWithText("第二份合成备份.yunji").assertIsDisplayed()
        rule.onNodeWithText("文件大小暂不可用").assertDoesNotExist()
    }
    @Test fun previewRequiresExplicitAcknowledgementAndHidesControlsWhenBusy() {
        val state=mutableStateOf(RestoreUiState(stage="CONFIRM",preview=preview));var confirms=0
        rule.setContent {MaterialTheme {RestoreContent(state.value,{},{},{},{state.value=state.value.copy(acknowledged=it)},
            {confirms++;state.value=RestoreUiState(stage="RESTORING")},{},{})}}
        rule.onNodeWithTag("restore-confirm").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("restore-acknowledge").performScrollTo().performClick()
        rule.onNodeWithTag("restore-confirm").performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle {assertEquals(1,confirms)}
        rule.onNodeWithTag("restore-confirm").assertDoesNotExist()
        rule.onNodeWithTag("restore-key").assertDoesNotExist()
        rule.onNodeWithTag("restore-progress").assertExists()
    }
    @Test fun passwordFieldIsMaskedAndRemovedDuringVerification() {
        val state=mutableStateOf(RestoreUiState(stage="KEY"))
        rule.setContent {MaterialTheme {RestoreContent(state.value,{}, {state.value=state.value.copy(key=it)},
            {state.value=state.value.copy(stage="VERIFYING",key="")},{},{},{},{})}}
        rule.onNodeWithTag("restore-verify").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("restore-key").performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).performTextInput("synthetic-key")
        rule.onNodeWithTag("restore-verify").performScrollTo().performClick()
        rule.onNodeWithTag("restore-key").assertDoesNotExist()
        rule.runOnIdle {assertEquals("",state.value.key)}
    }
    @Test fun completedScreenOffersExplicitReopen() {
        var opened=0
        rule.setContent {MaterialTheme {RestoreContent(RestoreUiState(stage="DONE"),{},{},{},{},{},{},{opened++})}}
        rule.onNodeWithTag("restore-reopen").performScrollTo().performClick()
        rule.runOnIdle {assertEquals(1,opened)}
        rule.onNodeWithTag("restore-confirm").assertDoesNotExist()
    }

    @Test fun cloudEntryIsOfferedNextToTheLocalFileChoice() {
        var cloud=0
        rule.setContent {MaterialTheme {RestoreContent(RestoreUiState(stage="SELECT"),{},{},{},{},{},{},{},startCloud={cloud++})}}
        rule.onNodeWithTag("restore-cloud").performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle {assertEquals(1,cloud)}
        rule.onNodeWithTag("restore-choose").assertExists()
    }

    @Test fun cloudLookupNeedsAKeyAndIsHiddenWhileFinding() {
        val state=mutableStateOf(RestoreUiState(stage="CLOUD",cloud=true));var finds=0
        rule.setContent {MaterialTheme {RestoreContent(state.value,{}, {state.value=state.value.copy(key=it)},{},{},{},{},{},
            startCloud={},findCloud={finds++},selectSnapshot={})}}
        rule.onNodeWithTag("restore-cloud-find").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("restore-cloud-key").performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).performTextInput("YJ1:synthetic")
        rule.onNodeWithTag("restore-cloud-find").performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle {assertEquals(1,finds)}
        // While a lookup is running the field is disabled and progress is visible.
        rule.runOnIdle {state.value=state.value.copy(finding=true)}
        rule.onNodeWithTag("restore-cloud-key").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("restore-cloud-progress").assertExists()
        rule.onNodeWithTag("restore-cloud-find").assertIsNotEnabled()
    }

    @Test fun cloudSnapshotMustBeChosenBeforeDownloading() {
        val snapshots=listOf(
            cn.yunji.journal.core.backup.SnapshotRef("snap-a",1_700_000_000_000,"writer",9,emptySet()),
            cn.yunji.journal.core.backup.SnapshotRef("snap-b",1_600_000_000_000,"writer",3,emptySet()))
        val state=mutableStateOf(RestoreUiState(stage="CLOUD_LIST",cloud=true,cloudSnapshots=snapshots,key="YJ1:synthetic"))
        var prepared=0;var chosen:String?=null
        rule.setContent {MaterialTheme {RestoreContent(state.value,{},{},{prepared++},{},{},{},{},
            startCloud={},findCloud={},selectSnapshot={chosen=it;state.value=state.value.copy(snapshotId=it)})}}
        rule.onNodeWithTag("restore-cloud-download").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("restore-cloud-snap-a").performScrollTo().performClick()
        rule.runOnIdle {assertEquals("snap-a",chosen)}
        rule.onNodeWithTag("restore-cloud-download").performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle {assertEquals(1,prepared)}
    }
}
