package cn.yunji.journal

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.backup.SafetyCopyStore
import cn.yunji.journal.core.security.*
import cn.yunji.journal.feature.SafetyCopiesPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class SafetyCopiesUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    @Test fun exportKeepsMetadataAndDeletionRequiresConfirmationAndPreservesOtherCopy() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val vault=File(context.cacheDir,"safety-ui-${UUID.randomUUID()}").apply {mkdirs()}
        val root=File(vault,"restore-safety").apply {mkdirs()}
        val first=File(root,"${UUID.randomUUID()}.yunji").apply {writeText("synthetic first")}
        val second=File(root,"${UUID.randomUUID()}.yunji").apply {writeText("synthetic second")}
        val store=SafetyCopyStore(vault);val copy=store.list().single {it.id==first.nameWithoutExtension}
        var request: FileRequest?=null
        val allowed=androidx.compose.runtime.mutableStateOf(true)
        try {
            rule.setContent {MaterialTheme {CompositionLocalProvider(LocalFileLaunch provides {value,_->request=value},cn.yunji.journal.feature.LocalSafetyActionsAllowed provides allowed.value) {
                Column(Modifier.verticalScroll(rememberScrollState())) {SafetyCopiesPanel("synthetic-vault",store)}
            }}}
            rule.waitUntil(10000){rule.onAllNodesWithTag("safety-export-${copy.id}").fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithTag("safety-export-${copy.id}").performScrollTo().performClick()
            rule.runOnIdle {assertEquals("SAFETY_EXPORT",request?.kind);assertEquals(copy.id,request?.targetId);assertEquals(copy.size,request?.sourceSize);assertEquals(copy.modifiedAt,request?.revision);assertTrue(first.exists())}
            rule.onNodeWithTag("safety-delete-${copy.id}").performScrollTo().performClick()
            rule.onNodeWithText("保留").performClick()
            rule.runOnIdle {assertTrue(first.exists())}
            rule.onNodeWithTag("safety-delete-${copy.id}").performScrollTo().performClick()
            rule.runOnIdle {allowed.value=false}
            rule.onNodeWithText("删除这份安全副本？").assertDoesNotExist()
            rule.runOnIdle {assertTrue(first.exists());allowed.value=true}
            rule.onNodeWithText("删除这份安全副本？").assertDoesNotExist()
            rule.onNodeWithTag("safety-delete-${copy.id}").performScrollTo().performClick()
            rule.onNodeWithText("确认删除").performClick()
            rule.waitUntil(10000){!first.exists()}
            rule.runOnIdle {assertTrue(second.exists())}
        }finally {vault.deleteRecursively()}
    }
}
