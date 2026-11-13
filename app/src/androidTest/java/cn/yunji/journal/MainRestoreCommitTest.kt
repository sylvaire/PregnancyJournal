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

/** Real DocumentsUI selection and production Main; dedicated emulator, destructive commits require an explicit secondary test user. */
class MainRestoreCommitTest {
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
    @Test fun actualMainCommitsReopensAndRestoresAgain()=runBlocking {
        val user=android.os.Process.myUid()/100000
        assumeTrue(Build.MODEL=="sdk_gphone64_x86_64" && Build.VERSION.SDK_INT==34)
        assumeTrue(user>0 && InstrumentationRegistry.getArguments().getString("isolatedRestoreUser")==user.toString())
        val context=ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(!DeviceKeyStore(context).strict() && !cn.yunji.journal.data.SecurityStore(context).lockEnabled())
        val handoff=FileHandoff(context)
        assumeTrue(handoff.read()==null)
        val vault=File(context.noBackupFilesDir,"vault")
        assumeTrue(RestoreJournal(vault).read()==null)
        val root=File(context.cacheDir,"main-commit-${UUID.randomUUID()}").apply {mkdirs()}
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val modelField=MainActivity::class.java.getDeclaredField("model\$delegate").apply {isAccessible=true}
        val epochField=MainActivity::class.java.getDeclaredField("navigationEpoch\$delegate").apply {isAccessible=true}
        try {
            repeat(2) {round->
                val source=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
                val recovery=RecoveryKey.create()
                val id="synthetic-${UUID.randomUUID()}"
                val name="Yunji-Restore-${UUID.randomUUID()}.yunji"
                var uri: android.net.Uri?=null
                try {
                    val archive=File(root,"source-$round.yunji")
                    val snapshot=LogicalSnapshot(vaultId=source.id,snapshotId=UUID.randomUUID().toString(),writerId=UUID.randomUUID().toString(),writerSequence=1,capturedSequence=1,createdAt=1,
                        pregnancies=listOf(PregnancyEntity(id,"合成恢复档案",createdAt=1,updatedAt=1)))
                    LocalBackupArchive.write(snapshot,source,RecoverySlots.create(source,recovery),EncryptedFileStore(File(root,"objects-$round"),source),archive)
                    uri=requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/");put(MediaStore.MediaColumns.IS_PENDING,1)
                    }))
                    requireNotNull(context.contentResolver.openOutputStream(requireNotNull(uri))).use {out->archive.inputStream().use {it.copyTo(out)}}
                    context.contentResolver.update(requireNotNull(uri),ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
                    clickText("我的");clickText("本地备份");clickText("从备份恢复资料")
                    var oldModel: Any?=null;var oldEpoch: Any?=null
                    scenario.onActivity {oldModel=(modelField.get(it) as androidx.compose.runtime.State<*>).value;oldEpoch=(epochField.get(it) as androidx.compose.runtime.IntState).intValue}
                    assertNotNull(oldModel)
                    clickText("选择加密备份")
                    waitFor("DocumentsUI"){it.packageName?.toString()?.contains("documentsui")==true}
                    SystemClock.sleep(400)
                    if(find {it.text?.toString()==name}==null) {
                        find {it.contentDescription?.toString() in setOf("Show roots","显示根目录","显示导航抽屉")}?.let {click(it)}
                        find {it.text?.toString() in setOf("Downloads","下载")}?.let {click(it)}
                    }
                    clickText(name)
                    waitFor("recovery key page"){it.text?.toString()=="输入恢复密钥"}
                    val field=waitFor("password field"){it.className?.toString()=="android.widget.EditText" && it.isPassword}
                    assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,recovery)}))
                    clickText("校验备份")
                    waitFor("preview"){it.text?.toString()=="核对要恢复的资料"}
                    clickText("我理解当前记录会被整体替换，并保留本机安全副本。")
                    clickText("确认恢复")
                    waitFor("restore done"){it.text?.toString()=="资料恢复完成"}
                    clickText("打开恢复后的资料")
                    var ready=false
                    val deadline=SystemClock.elapsedRealtime()+20000
                    while(!ready && SystemClock.elapsedRealtime()<deadline) {
                        scenario.onActivity {activity->
                            val current=(modelField.get(activity) as androidx.compose.runtime.State<*>).value as? cn.yunji.journal.feature.JournalViewModel
                            ready=current?.state?.value?.let {!it.loading && it.pregnancies.map {p->p.id}==listOf(id)}==true
                            if(ready){assertNotSame(oldModel,current);assertNotEquals(oldEpoch,(epochField.get(activity) as androidx.compose.runtime.IntState).intValue)}
                        }
                        if(!ready)SystemClock.sleep(100)
                    }
                    assertTrue("Restored model must expose only the selected synthetic archive",ready)
                    assertNull(handoff.read());assertNull(RestoreJournal(vault).read())
                    // A persisted completion must also finish through Main's ordinary/recreated open path.
                    val pendingJournal=RestoreJournal(vault)
                    pendingJournal.write(RestoreStage.VERIFIED,requireNotNull(VaultLayout(vault).activeGenerationId()),null,snapshot.snapshotId)
                    scenario.recreate()
                    val reopenDeadline=SystemClock.elapsedRealtime()+20000
                    while(pendingJournal.read()!=null && SystemClock.elapsedRealtime()<reopenDeadline)SystemClock.sleep(100)
                    assertNull("Main reopen must finish persisted restore completion",pendingJournal.read())
                } finally {
                    uri?.let {context.contentResolver.delete(it,null,null)}
                    source.master.fill(0)
                }
            }
        }finally {scenario.close();root.deleteRecursively()}
    }
}