package cn.yunji.journal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunji.journal.core.security.*
import cn.yunji.journal.data.SecurityStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Two separate instrumentation invocations with an external force-stop between them. */
class BackupProcessBoundaryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val marker get()=File(context.cacheDir,"backup-process-boundary.fixture")
    private val sentinel="SYNTHETIC-PARTIAL-BACKUP-NO-MEDICAL-DATA".toByteArray()
    private fun guard() {
        assumeTrue((Build.MODEL=="sdk_gphone64_x86_64" && Build.VERSION.SDK_INT==34) ||
            (Build.MODEL=="sdk_gphone16k_x86_64" && Build.VERSION.SDK_INT==36))
        assumeTrue(!DeviceKeyStore(context).strict() && !SecurityStore(context).lockEnabled())
    }
    @Test fun prepareStartedBoundary() {
        guard();assumeTrue(InstrumentationRegistry.getArguments().getString("boundaryStage")=="prepare");val handoff=FileHandoff(context)
        assumeTrue(handoff.read()==null && !marker.exists())
        val material=DeviceKeyStore(context).open()
        val name="${UUID.randomUUID()}.jpg"
        val output=handoff.cameraFile(name)
        var committed=false
        try {
            output.writeBytes(sentinel)
            handoff.begin(FileRequest("BACKUP",ownerId=material.id),"synthetic.yunji")
            val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.capture",output)
            handoff.receive(Activity.RESULT_OK,Intent().setData(uri),true)
            val request=requireNotNull(handoff.read())
            handoff.startBackup(request.id)
            assertTrue(requireNotNull(handoff.read()).backupStarted)
            marker.writeText("${Process.myPid()}\n${request.id}\n$name")
            committed=true
        } finally {
            material.master.fill(0)
            if(!committed){handoff.clear();output.delete();marker.delete()}
        }
    }
    @Test fun restartedActivityRejectsStartedTargetAndPreservesBytes() {
        guard();assumeTrue(InstrumentationRegistry.getArguments().getString("boundaryStage")=="restart");assertTrue("Prepare fixture must run first",marker.exists())
        val fixture=marker.readLines();assertEquals(3,fixture.size)
        assertNotEquals("A genuinely new process is required",fixture[0].toInt(),Process.myPid())
        val handoff=FileHandoff(context);val output=handoff.cameraFile(fixture[2])
        val before=requireNotNull(handoff.read());assertEquals(fixture[1],before.id)
        assertTrue(before.backupStarted);assertFalse(before.canApply)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
                val deadline=SystemClock.elapsedRealtime()+20000
                var found=false;var recoveryText=false
                while(SystemClock.elapsedRealtime()<deadline && !found) {
                    val queue=java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                    automation.rootInActiveWindow?.let(queue::add)
                    var count=0
                    while(queue.isNotEmpty() && count++<5000) {
                        val node=queue.removeFirst()
                        if(node.isVisibleToUser && node.text?.toString()=="上次备份需要重新选择位置")found=true
                        if(node.isVisibleToUser && node.text?.toString()?.contains("请到保存位置删除本次创建的文件（如仍存在）")==true)recoveryText=true
                        assertFalse("Started backup must not offer continue",node.isVisibleToUser && node.text?.toString()=="继续")
                        repeat(node.childCount){index->node.getChild(index)?.let(queue::add)}
                    }
                    if(!found)SystemClock.sleep(100)
                }
                assertTrue("Restart must explain that a new destination is required",found)
                assertTrue("Restart must explain manual cleanup of an unverified target",recoveryText)
                assertEquals(before,handoff.read())
                assertArrayEquals(sentinel,output.readBytes())
                try {handoff.startBackup(before.id);fail("Started backup replay accepted")}catch(_:BackupReplayException){}
                assertArrayEquals(sentinel,output.readBytes())
            }
        } finally {handoff.clearIf(fixture[1]);output.delete();marker.delete()}
    }
}
