package cn.yunji.journal

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import cn.yunji.journal.core.backup.RecoveryKeySetup
import cn.yunji.journal.core.backup.VaultLayout
import cn.yunji.journal.core.backup.VaultPointer
import cn.yunji.journal.data.RecoverySettingsAccess
import cn.yunji.journal.feature.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

@LargeTest
class RecoveryUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    @Test fun fullKeyIsHiddenBeforeVerificationAndPageDisposalClearsIt() {
        lateinit var controller: RecoveryController
        val visible=androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            val scope=rememberCoroutineScope()
            controller=androidx.compose.runtime.remember {RecoveryController(scope,object: RecoverySettingsAccess {
                override suspend fun configured()=false
                override suspend fun confirm(setup: RecoveryKeySetup,answers: List<String>)=Unit
            })}
            MaterialTheme {if(visible.value)RecoveryScreen(controller)}
        }
        rule.onNodeWithTag("recovery-generate").performScrollTo().performClick()
        rule.onNodeWithText("YJ1:").assertExists()
        rule.onNodeWithTag("recovery-verify").performScrollTo().performClick()
        rule.onNodeWithText("YJ1:").assertDoesNotExist()
        rule.runOnIdle {assertEquals("",controller.state.value.key)}
        rule.onNodeWithTag("recovery-confirm").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("recovery-answer-0").performScrollTo().performTextInput("ABC123")
        rule.runOnIdle {visible.value=false}
        rule.waitForIdle()
        rule.runOnIdle {assertEquals("",controller.state.value.first);assertEquals("",controller.state.value.key)}
    }
    @Test fun configuredPageLaunchesBackupForTheCurrentVaultWithoutShowingRecoveryKey() {
        var request: cn.yunji.journal.core.security.FileRequest?=null
        var name: String?=null
        val vault=java.util.UUID.randomUUID().toString()
        rule.setContent {
            val scope=rememberCoroutineScope()
            val controller=androidx.compose.runtime.remember {RecoveryController(scope,object: RecoverySettingsAccess {
                override val vaultId=vault
                override suspend fun configured()=true
                override suspend fun confirm(setup: RecoveryKeySetup,answers: List<String>)=Unit
            })}
            androidx.compose.runtime.CompositionLocalProvider(cn.yunji.journal.core.security.LocalFileLaunch provides {value,fileName->request=value;name=fileName}) {
                MaterialTheme {RecoveryScreen(controller)}
            }
        }
        rule.onNodeWithTag("backup-export").performScrollTo().performClick()
        rule.runOnIdle {assertEquals("BACKUP",request?.kind);assertEquals(vault,request?.ownerId);assertTrue(name?.endsWith(".yunji")==true)}
        rule.onNodeWithText("YJ1:").assertDoesNotExist()
    }

    /** The reclaim entry must not exist until the core layer reports a previous generation. */
    @Test fun reclaimPanelIsHiddenWhenNoPreviousGenerationExists() {
        rule.setContent {
            MaterialTheme {androidx.compose.foundation.layout.Column {ReclaimPreviousContent(ReclaimPanelState(),{},{},{})}}
        }
        rule.onNodeWithTag("reclaim-previous").assertDoesNotExist()
        rule.onNodeWithText("恢复前的旧资料").assertDoesNotExist()
    }

    /** Reclaiming deletes the rollback generation, so it needs an explicit notice and confirmation. */
    @Test fun reclaimNeedsExplicitConfirmationAndCanBeCancelled() {
        val state=androidx.compose.runtime.mutableStateOf(ReclaimPanelState(available=true,canAct=true))
        var confirmed=0
        rule.setContent {
            MaterialTheme {androidx.compose.foundation.layout.Column {
                ReclaimPreviousContent(state.value,
                    onReclaim={state.value=state.value.copy(confirming=true)},
                    onConfirm={confirmed++;state.value=state.value.copy(confirming=false,available=false,message="已回收恢复前的旧资料")},
                    onDismiss={state.value=state.value.copy(confirming=false)})
            }}
        }
        rule.onNodeWithTag("reclaim-previous").assertIsEnabled().performClick()
        rule.onNodeWithText("回收恢复前的旧资料？").assertExists()
        rule.onNodeWithText("无法撤销",substring=true).assertExists()
        rule.onNodeWithTag("reclaim-cancel").performClick()
        rule.onNodeWithText("回收恢复前的旧资料？").assertDoesNotExist()
        rule.onNodeWithTag("reclaim-previous").performClick()
        rule.onNodeWithTag("reclaim-confirm").performClick()
        rule.runOnIdle {assertEquals(1,confirmed)}
        rule.onNodeWithText("已回收恢复前的旧资料").assertExists()
        rule.onNodeWithTag("reclaim-previous").assertDoesNotExist()
    }

    /** While the app is covered by recents (or a reclaim is running) the destructive action is disabled. */
    @Test fun reclaimActionIsDisabledWhileCoveredOrBusy() {
        val state=androidx.compose.runtime.mutableStateOf(ReclaimPanelState(available=true,canAct=false))
        rule.setContent {
            MaterialTheme {androidx.compose.foundation.layout.Column {ReclaimPreviousContent(state.value,{},{},{})}}
        }
        rule.onNodeWithTag("reclaim-previous").assertIsNotEnabled()
        rule.runOnIdle {state.value=state.value.copy(canAct=true,busy=true)}
        rule.waitForIdle()
        rule.onNodeWithTag("reclaim-previous").assertIsNotEnabled()
    }

    /**
     * 生产接线集成（真实 [VaultLayout] + 真实 [cn.yunji.journal.core.backup.RestoreReclaim] + 真实 journal）：
     * 旧代际存在时 [RecoveryScreen] 的 `ReclaimPreviousPanel` 入口可见 → 二次确认 → 指针 previousId 清空、
     * 旧代际目录被删除、当前代际与 data 保留 → 不可回收态入口消失。
     * 真机上默认 vault 目录若已有数据则先改名备份、结束时恢复，避免覆盖真实资料。
     */
    @Test fun productionReclaimPanelReclaimsPreviousGenerationThroughRealVault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vaultDir = File(context.noBackupFilesDir, "vault")
        val backupDir = File(context.noBackupFilesDir, "vault-ui-test-backup")
        assertFalse("上次测试残留的备份目录未清理", backupDir.exists())
        if (vaultDir.exists()) assertTrue("存在真实资料库，拒绝覆盖", vaultDir.renameTo(backupDir))
        try {
            val layout = VaultLayout(vaultDir) { }
            val active = UUID.randomUUID().toString()
            val previous = UUID.randomUUID().toString()
            assertTrue(layout.generationDir(active).mkdirs())
            File(layout.generationDir(active), "journal.db").writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(File(layout.generationDir(active), "objects").mkdirs())
            assertTrue(layout.generationDir(previous).mkdirs())
            File(layout.generationDir(previous), "journal.db").writeBytes(byteArrayOf(4, 5, 6))
            assertTrue(File(layout.generationDir(previous), "objects").mkdirs())
            File(File(layout.generationDir(previous), "objects"), "old.bin").writeBytes(byteArrayOf(7))
            layout.writePointer(VaultPointer(activeId = active, previousId = previous, updatedAt = 1L))

            rule.setContent {
                val scope = rememberCoroutineScope()
                val controller = androidx.compose.runtime.remember {
                    RecoveryController(scope, object : RecoverySettingsAccess {
                        override val vaultId = UUID.randomUUID().toString()
                        override suspend fun configured() = true
                        override suspend fun confirm(setup: RecoveryKeySetup, answers: List<String>) = Unit
                    })
                }
                MaterialTheme { RecoveryScreen(controller) }
            }
            rule.waitUntil(10_000L) {
                rule.onAllNodesWithTag("reclaim-previous").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("reclaim-previous").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            rule.onNodeWithText("回收恢复前的旧资料？").assertExists()
            rule.onNodeWithTag("reclaim-confirm").performClick()
            rule.waitUntil(10_000L) {
                runCatching { layout.readPointer()?.previousId == null }.getOrDefault(false)
            }
            rule.waitForIdle()
            rule.onNodeWithText("已回收恢复前的旧资料代际，当前资料不受影响。").assertExists()
            rule.onNodeWithTag("reclaim-previous").assertDoesNotExist()
            assertFalse("旧代际目录应已删除", layout.generationDir(previous).exists())
            assertFalse("不应留下回收中目录", File(layout.generationDir(previous).path + ".reclaim").exists())
            assertEquals(active, layout.readPointer()?.activeId)
            assertTrue("当前代际数据库必须保留", File(layout.generationDir(active), "journal.db").isFile)
            assertTrue("当前代际原件目录必须保留", File(layout.generationDir(active), "objects").isDirectory)
        } finally {
            if (vaultDir.exists()) vaultDir.deleteRecursively()
            if (backupDir.exists()) backupDir.renameTo(vaultDir)
        }
    }
}
