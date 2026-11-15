package cn.yunji.journal

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.PregnancyEntity
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.EncryptedFileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real DocumentsUI selection and production Main; dedicated emulator, prepare/cancel only. */
class MainRestorePickerTest {
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun find(predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo? {
        val queue=java.util.ArrayDeque<AccessibilityNodeInfo>();automation.rootInActiveWindow?.let(queue::add)
        var count=0
        while(queue.isNotEmpty() && count++<5000){val node=queue.removeFirst();if(node.isVisibleToUser && predicate(node))return node
            repeat(node.childCount){node.getChild(it)?.let(queue::add)}}
        return null
    }
    private fun waitFor(step: String,predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo {
        val end=SystemClock.elapsedRealtime()+20000
        while(SystemClock.elapsedRealtime()<end){find(predicate)?.let {return it};SystemClock.sleep(100)}
        throw AssertionError("Restore UI step missing: $step")
    }
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo?=node
        repeat(8){val n=current ?: return@repeat;if(n.isEnabled && n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;current=n.parent}
        return false
    }
    private fun clickText(label: String) {
        val end=SystemClock.elapsedRealtime()+20000;var last=0L
        while(SystemClock.elapsedRealtime()<end) {
            find {it.text?.toString()==label}?.let {if(click(it))return}
            if(SystemClock.elapsedRealtime()-last>500){find {it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);last=SystemClock.elapsedRealtime()}
            SystemClock.sleep(100)
        }
        throw AssertionError("Restore button missing: $label")
    }
    @Test fun actualSystemSelectionValidatesThenCancellationKeepsCurrentVault()=runBlocking {journey(false)}
    @Test fun recreationKeepsSelectedFileButNeverRestoresEnteredKey()=runBlocking {journey(true)}
    private suspend fun journey(recreate: Boolean) {
        assumeTrue(Build.MODEL in setOf("sdk_gphone64_x86_64","sdk_gphone16k_x86_64") && Build.VERSION.SDK_INT in 34..36)
        val context=ApplicationProvider.getApplicationContext<Context>()
        val keys=DeviceKeyStore(context)
        assumeTrue(!keys.strict() && !cn.yunji.journal.data.SecurityStore(context).lockEnabled())
        val handoff=FileHandoff(context);assumeTrue(handoff.read()==null)
        val vault=File(context.noBackupFilesDir,"vault");assumeTrue(RestoreJournal(vault).read()==null)
        val layout=VaultLayout(vault);val before=layout.activeGenerationId()
        val settings=File(layout.activeDirectory(),"recovery-slot.enc");val settingsBefore=settings.takeIf {it.exists()}?.readBytes()
        val root=File(context.cacheDir,"main-restore-picker-${UUID.randomUUID()}").apply {mkdirs()}
        val source=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey());val recovery=RecoveryKey.create()
        val name="Yunji-Restore-${UUID.randomUUID()}.yunji"
        var scenario: ActivityScenario<MainActivity>?=null;var uri: android.net.Uri?=null;var requestId: String?=null
        try {
            val snapshot=LogicalSnapshot(vaultId=source.id,snapshotId=UUID.randomUUID().toString(),writerId=UUID.randomUUID().toString(),writerSequence=1,capturedSequence=1,createdAt=1,
                pregnancies=listOf(PregnancyEntity("ui-synthetic","合成恢复预览",createdAt=1,updatedAt=1)))
            val archive=File(root,"source.yunji")
            LocalBackupArchive.write(snapshot,source,RecoverySlots.create(source,recovery),EncryptedFileStore(File(root,"source-objects"),source),archive)
            uri=requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/");put(MediaStore.MediaColumns.IS_PENDING,1)
            }))
            requireNotNull(context.contentResolver.openOutputStream(requireNotNull(uri))).use {output->archive.inputStream().use {it.copyTo(output)}}
            context.contentResolver.update(requireNotNull(uri),ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            scenario=ActivityScenario.launch(MainActivity::class.java)
            clickText("我的");clickText("本地备份");clickText("从备份恢复资料");clickText("选择加密备份")
            waitFor("DocumentsUI"){it.packageName?.toString()?.contains("documentsui")==true}
            requestId=requireNotNull(handoff.read()).id
            SystemClock.sleep(400)
            if(find {it.text?.toString()==name}==null) {
                find {it.contentDescription?.toString() in setOf("Show roots","显示根目录","显示导航抽屉")}?.let {click(it)}
                find {it.text?.toString() in setOf("Downloads","下载")}?.let {click(it)}
            }
            clickText(name)
            waitFor("recovery key page"){it.text?.toString()=="输入恢复密钥"}
            waitFor("selected backup name"){it.text?.toString()==name}
            fun assertMetadata() {
                requireNotNull(scenario).onActivity {activity ->
                    val property=MainActivity::class.java.getDeclaredField("restoreController").apply {isAccessible=true}
                    val state=(property.get(activity) as cn.yunji.journal.feature.RestoreController).state.value
                    assertEquals(name,state.documentName)
                    assertEquals(archive.length(),state.documentSize)
                }
            }
            assertMetadata()
            val field=waitFor("password field"){it.className?.toString()=="android.widget.EditText" && it.isPassword}
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,recovery)}))
            if(recreate) {
                requireNotNull(scenario).recreate()
                waitFor("recreated key page"){it.text?.toString()=="输入恢复密钥"}
                waitFor("recreated backup name"){it.text?.toString()==name}
                assertMetadata()
                requireNotNull(scenario).onActivity {activity->
                    val property=MainActivity::class.java.getDeclaredField("restoreController").apply {isAccessible=true}
                    val controller=property.get(activity) as cn.yunji.journal.feature.RestoreController
                    assertTrue("Entered key must not survive Activity recreation",controller.state.value.key.isEmpty())
                }
                val recreated=waitFor("fresh password field"){it.className?.toString()=="android.widget.EditText" && it.isPassword}
                assertTrue(recreated.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,recovery)}))
            }
            clickText("校验备份")
            waitFor("preview"){it.text?.toString()=="核对要恢复的资料"}
            waitFor("preview backup name"){it.text?.toString()==name}
            clickText("取消")
            val deadline=SystemClock.elapsedRealtime()+20000
            while(SystemClock.elapsedRealtime()<deadline && (handoff.read()!=null || RestoreJournal(vault).read()!=null))SystemClock.sleep(100)
            assertNull(handoff.read());assertNull(RestoreJournal(vault).read())
            assertEquals(before,layout.activeGenerationId())
            if(settingsBefore==null)assertFalse(settings.exists()) else assertArrayEquals(settingsBefore,settings.readBytes())
        }finally {
            try {scenario?.close()}finally {
                requestId?.let {handoff.clearIf(it)}
                uri?.let {context.contentResolver.delete(it,null,null)}
                source.master.fill(0);root.deleteRecursively()
            }
        }
    }
}
