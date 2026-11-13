package cn.yunji.journal

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room3.Transactor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.EncryptedFileStore
import cn.yunji.journal.core.storage.StoredObject
import cn.yunji.journal.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Dedicated API34/API36 emulator only. Never changes lock/credentials, deletes a vault or runs on a phone. */
@LargeTest
class BackupSystemJourneyTest {
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun find(predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo? {
        val root=automation.rootInActiveWindow ?: return null
        val queue=java.util.ArrayDeque<AccessibilityNodeInfo>();queue.add(root)
        var count=0
        while(queue.isNotEmpty() && count++<5000) {
            val node=queue.removeFirst()
            if(node.isVisibleToUser && predicate(node))return node
            repeat(node.childCount){node.getChild(it)?.let(queue::add)}
        }
        return null
    }
    private fun waitNode(label: String,timeoutMs: Long=20000,predicate: (AccessibilityNodeInfo)->Boolean): AccessibilityNodeInfo {
        val end=SystemClock.elapsedRealtime()+timeoutMs
        while(SystemClock.elapsedRealtime()<end){find(predicate)?.let {return it};SystemClock.sleep(100)}
        throw AssertionError("未找到测试步骤：$label")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo?=node
        repeat(8){val value=current ?: return@repeat;if(value.isEnabled && value.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;current=value.parent}
        throw AssertionError("测试控件无法点击")
    }
    private fun clickText(text: String,scroll: Boolean=false) {
        val end=SystemClock.elapsedRealtime()+20000;var lastScroll=0L
        while(SystemClock.elapsedRealtime()<end) {
            find {it.text?.toString()==text || it.contentDescription?.toString()==text}?.let {click(it);return}
            if(scroll && SystemClock.elapsedRealtime()-lastScroll>600){find {it.isScrollable}?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);lastScroll=SystemClock.elapsedRealtime()}
            SystemClock.sleep(100)
        }
        throw AssertionError("未找到测试入口：$text")
    }
    private fun deleteTestOutput(context: Context,uri: Uri) {
        try {assertTrue(DocumentsContract.deleteDocument(context.contentResolver,uri))}
        catch(failure: NullPointerException) {
            // API34 provider may delete the file then crash while revoking its URI grant.
            // Accept only independently proven absence, never a generic cleanup failure.
            try {context.contentResolver.openInputStream(uri)?.use { };throw failure}
            catch(_:java.io.FileNotFoundException) { /* The test output is already absent. */ }
        }
    }
    @Test fun mainActivityCreatesBackupThroughSystemDocumentsUiAndRecoveryKeyOpensIt()=runBlocking {journey(false)}
    @Test fun selectedSystemDestinationSurvivesActivityRecreationBeforeConfirmation()=runBlocking {journey(true)}
    @Test fun hundredMiBOriginalSurvivesMainAndSystemSavePicker()=runBlocking {journey(false,100L*1024*1024)}
    private suspend fun journey(recreateBeforeConfirmation: Boolean,syntheticOriginalBytes: Long=0) {
        if(syntheticOriginalBytes>0)assumeTrue(Build.VERSION.SDK_INT==36 && Build.MODEL=="sdk_gphone16k_x86_64")
        assumeTrue((Build.MODEL=="sdk_gphone64_x86_64" && Build.VERSION.SDK_INT==34) ||
            (Build.MODEL=="sdk_gphone16k_x86_64" && Build.VERSION.SDK_INT==36))
        val context=ApplicationProvider.getApplicationContext<Context>()
        val keys=DeviceKeyStore(context)
        assumeTrue(!keys.strict() && !SecurityStore(context).lockEnabled())
        val handoff=FileHandoff(context);assumeTrue(handoff.read()==null)
        val settingFile=File(context.noBackupFilesDir,"vault/recovery-slot.enc")
        assumeTrue(!File(settingFile.path+".bak").exists())
        val previous=settingFile.takeIf {it.exists()}?.readBytes()
        val material=keys.open();val recovery=RecoveryKey.create()
        val scratch=File(context.cacheDir,"backup-journey-${UUID.randomUUID()}").apply {mkdirs()}
        var scenario: ActivityScenario<MainActivity>?=null;var outputUri: Uri?=null;var requestId: String?=null
        var resources: VaultRuntime.Resources?=null;var syntheticPregnancy: String?=null;var syntheticVisit: String?=null;var syntheticObject: String?=null
        try {
            RecoverySettings(context,material).save(RecoverySlots.create(material,recovery))
            scenario=ActivityScenario.launch(MainActivity::class.java)
            if(syntheticOriginalBytes>0){
                requireNotNull(scenario).onActivity {resources=it.runtime.resources()}
                val r=requireNotNull(resources)
                val store=EncryptedFileStore(r.importer.objectDirectory,r.material)
                val source=object: InputStream(){
                    var remaining=syntheticOriginalBytes
                    override fun read(): Int=if(remaining-- > 0)42 else -1
                    override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
                        if(length==0)return 0
                        if(remaining<=0)return -1
                        val count=minOf(remaining,length.toLong()).toInt()
                        java.util.Arrays.fill(bytes,offset,offset+count,42.toByte());remaining-=count;return count
                    }
                }
                val stored=store.put(source);syntheticObject=stored.id
                val pregnancyId=UUID.randomUUID().toString();syntheticPregnancy=pregnancyId
                val visitId=UUID.randomUUID().toString();syntheticVisit=visitId
                val now=System.currentTimeMillis()
                r.database.journalWrite {
                    val dao=r.database.journalDao()
                    dao.putPregnancy(PregnancyEntity(pregnancyId,"系统大备份合成档案",createdAt=now,updatedAt=now))
                    dao.putVisit(VisitEntity(visitId,pregnancyId,"2026-10-23",zoneId="UTC",createdAt=now,updatedAt=now))
                    dao.putAttachment(AttachmentEntity(stored.id,visitId,"合成大原件","application/pdf",stored.plainSize,stored.cipherSize,
                        stored.plainSha256,stored.cipherSha256,null,null,null,null,null,now))
                }
            }
            clickText("我的")
            clickText("本地备份",scroll=true)
            try {clickText("导出加密备份",scroll=true)}catch(e: AssertionError){
                val labels=listOf("本地加密备份","保留找回资料的钥匙","生成恢复密钥","恢复设置无法读取，请解锁后重试。")
                throw AssertionError("备份入口未显示；页面诊断="+labels.associateWith {label->find {it.text?.toString()==label}!=null},e)
            }
            waitNode("系统文件保存器"){it.packageName?.toString()?.contains("documentsui")==true}
            requestId=requireNotNull(handoff.read()).id
            val name="Yunji-Journey-${UUID.randomUUID()}.yunji"
            val field=waitNode("备份文件名"){it.className?.toString()=="android.widget.EditText"}
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,name)}))
            val save=waitNode("系统保存按钮"){
                it.isEnabled && (it.viewIdResourceName?.endsWith(":id/action_menu_select")==true || it.text?.toString() in setOf("Save","SAVE","保存"))
            }
            click(save)
            waitNode("备份再次确认"){it.text?.toString()=="开始制作加密备份？"}
            val selected=requireNotNull(handoff.read());assertEquals("BACKUP",selected.request.kind);assertEquals(material.id,selected.request.ownerId)
            requestId=selected.id;outputUri=Uri.parse(selected.uris.single())
            if(recreateBeforeConfirmation) {
                requireNotNull(scenario).recreate()
                waitNode("重建后备份确认"){it.text?.toString()=="开始制作加密备份？"}
                assertEquals(selected,handoff.read())
                requireNotNull(context.contentResolver.openInputStream(requireNotNull(outputUri))).use {assertEquals(-1,it.read())}
            }
            clickText("继续")
            waitNode("备份成功提示",if(syntheticOriginalBytes>0)120000 else 20000){it.text?.toString()?.contains("加密备份已保存，并已通过原件及恢复数据校验")==true}
            assertNull(handoff.read())
            val archive=File(scratch,"saved.yunji")
            requireNotNull(context.contentResolver.openInputStream(requireNotNull(outputUri))).use {input->archive.outputStream().use {input.copyTo(it)}}
            if(syntheticOriginalBytes>0)assertTrue("系统保存的完整归档应包含不可压缩的加密原件",archive.length()>=syntheticOriginalBytes)
            LocalBackupArchive.verify(archive,recovery,scratch).use {verified->
                assertEquals(material.id,verified.snapshot.vaultId)
                if(syntheticOriginalBytes>0){
                    val attachment=verified.snapshot.attachments.single {it.id==syntheticObject}
                    assertEquals(syntheticOriginalBytes,attachment.plainSize)
                    val restoredObject=StoredObject(attachment.objectId ?: attachment.id,attachment.plainSize,attachment.cipherSize,
                        attachment.plainSha256,attachment.cipherSha256)
                    val plain=File(scratch,"restored-original")
                    try {
                        EncryptedFileStore(File(verified.directory,"objects"),verified.material).decryptTo(restoredObject,plain)
                        assertEquals(syntheticOriginalBytes,plain.length())
                    } finally {plain.delete()}
                }
                IsolatedRestore.withDatabase(context,verified){db->
                    assertTrue(db.journalDao().allDrafts().isEmpty())
                    if(syntheticOriginalBytes>0)assertEquals(syntheticOriginalBytes,requireNotNull(db.journalDao().attachment(requireNotNull(syntheticObject))).plainSize)
                }
            }
        } finally {
            try {scenario?.close()} finally {
                try {
                    val r=resources;val objectId=syntheticObject
                    if(r!=null && objectId!=null){
                        withTimeout(30000){while(r.database.journalDao().objectPins().any {it.objectId==objectId})delay(100)}
                        r.database.useConnection(isReadOnly=false){connection->
                            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE){
                                suspend fun remove(sql: String,id: String?){if(id!=null)connection.usePrepared(sql){it.bindText(1,id);it.step()}}
                                remove("DELETE FROM attachments WHERE id=?",objectId)
                                remove("DELETE FROM prenatal_visits WHERE id=?",syntheticVisit)
                                remove("DELETE FROM pregnancies WHERE id=?",syntheticPregnancy)
                            }
                        }
                        assertNull(r.database.journalDao().attachment(objectId))
                        syntheticVisit?.let {assertNull(r.database.journalDao().visit(it))}
                        syntheticPregnancy?.let {assertNull(r.database.journalDao().pregnancy(it))}
                        assertTrue(EncryptedFileStore(r.importer.objectDirectory,r.material).path(objectId).delete())
                    }
                } finally {
                try {outputUri?.let {uri->deleteTestOutput(context,uri)}} finally {
                    try {
                        val own=requestId ?: handoff.read()?.takeIf {it.request.kind=="BACKUP" && it.request.ownerId==material.id}?.id
                        own?.let {handoff.clearIf(it)}
                    } finally {
                        try {
                            val atomic=AtomicFile(settingFile)
                            if(previous==null)atomic.delete() else {val output=atomic.startWrite();try {output.write(previous);atomic.finishWrite(output)}catch(e: Throwable){atomic.failWrite(output);throw e}}
                        } finally {material.master.fill(0);scratch.deleteRecursively()}
                    }
                }
                }
            }
        }
    }
}
