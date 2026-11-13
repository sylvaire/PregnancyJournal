package cn.yunji.journal

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.security.FileHandoff
import cn.yunji.journal.core.security.FileRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

class FileHandoffTest {
    private class Fixture: AutoCloseable {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val token=UUID.randomUUID().toString()
        val root=File(base.cacheDir,"handoff-$token").apply {mkdirs()}
        val context=object: ContextWrapper(base) {override fun getNoBackupFilesDir()=root}
        fun open()=FileHandoff.isolated(context,token)
        override fun close() {
            runCatching {open().clear()}
            KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry("${base.packageName}.file-handoff.test-$token.v1")}
            root.deleteRecursively()
        }
    }
    @Test fun resultSurvivesOwnerRecreationAndIsEncryptedUntilUnlockedConsumerRuns() {
        Fixture().use {f ->
            val first=f.open();val request=FileRequest("REPLACE",targetId="synthetic-target",revision=7)
            first.begin(request)
            val uri=Uri.parse("content://synthetic.provider/private-report.pdf")
            first.receive(Activity.RESULT_OK,Intent().setData(uri),true)
            val restored=requireNotNull(f.open().read())
            assertEquals(request,restored.request);assertTrue(restored.confirm);assertTrue(restored.returned)
            assertEquals(listOf(uri.toString()),restored.uris)
            val stored=File(f.root,"file-handoff.enc").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(stored.contains("private-report"));assertFalse(stored.contains("synthetic-target"))
            f.open().clear();assertNull(f.open().read())
        }
    }
    @Test fun secondLaunchCannotOverwriteUnconsumedResult() {
        Fixture().use {f ->val store=f.open();store.begin(FileRequest("PDF","first"));val before=store.read()
            assertTrue(runCatching {store.begin(FileRequest("PDF","second"))}.isFailure);assertEquals(before,store.read())}
    }
    @Test fun cancellationDropsOnlyPendingRequest() {
        Fixture().use {f ->val store=f.open();store.begin(FileRequest("PDF","owner"));store.receive(Activity.RESULT_CANCELED,null,true);assertNull(store.read())}
    }
    @Test fun cancellationDeletesOnlyOwnedCameraTemporaryFile() {
        Fixture().use {f ->val store=f.open();store.begin(FileRequest("CAMERA","owner"))
            val name=requireNotNull(store.read()?.request?.cameraName);val camera=store.cameraFile(name);camera.writeText("synthetic pixels")
            store.receive(Activity.RESULT_CANCELED,null,true);assertFalse(camera.exists());assertNull(store.read())
            assertTrue(runCatching {store.cameraFile("../../outside.jpg")}.isFailure)}
    }
    @Test fun nonContentResultIsRejectedWithoutReplacingRequest() {
        Fixture().use {f ->val store=f.open();store.begin(FileRequest("RETRY",targetId="job"));val before=store.read()
            assertTrue(runCatching {store.receive(Activity.RESULT_OK,Intent().setData(Uri.parse("file:///private")),true)}.isFailure)
            assertEquals(before,store.read())}
    }
    @Test fun repeatedDeliveryDoesNotReplaceFirstResultOrConfirmationRequirement() {
        Fixture().use {f ->val store=f.open();store.begin(FileRequest("EXPORT",targetId="document"),"synthetic.pdf")
            store.receive(Activity.RESULT_OK,Intent().setData(Uri.parse("content://synthetic/first")),true);val first=store.read()
            store.receive(Activity.RESULT_OK,Intent().setData(Uri.parse("content://synthetic/second")),false);assertEquals(first,store.read())}
    }
    @Test fun backupUsesCreateDocumentAndKeepsVaultBindingAndConfirmationAfterReopen() {
        Fixture().use {f->
            val vault=UUID.randomUUID().toString();val request=FileRequest("BACKUP",ownerId=vault)
            val intent=f.open().begin(request,"synthetic.yunji")
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.action);assertEquals("application/octet-stream",intent.type)
            assertEquals("synthetic.yunji",intent.getStringExtra(Intent.EXTRA_TITLE))
            f.open().receive(Activity.RESULT_OK,Intent().setData(Uri.parse("content://synthetic/new-backup")),false)
            val result=requireNotNull(f.open().read())
            assertEquals(request,result.request);assertTrue(result.confirm);assertTrue(result.returned)
        }
    }
    @Test fun startedBackupCannotBeClaimedOrAppliedAgainAfterReopen() {
        Fixture().use {f->
            val handoff=f.open();handoff.begin(FileRequest("BACKUP",ownerId=UUID.randomUUID().toString()),"synthetic.yunji")
            handoff.receive(Activity.RESULT_OK,Intent().setData(Uri.parse("content://synthetic/new-backup")),false)
            val selected=requireNotNull(handoff.read());assertTrue(selected.canApply)
            assertTrue(runCatching {handoff.startBackup("stale-request")}.isFailure)
            assertEquals(selected,handoff.read())
            handoff.startBackup(selected.id)
            val reopened=requireNotNull(f.open().read())
            assertTrue(reopened.backupStarted);assertFalse(reopened.canApply)
            assertEquals(selected.uris,reopened.uris)
            assertTrue(runCatching {f.open().startBackup(selected.id)}.exceptionOrNull() is cn.yunji.journal.core.security.BackupReplayException)
            assertEquals(reopened,f.open().read())
            assertFalse(f.open().clearIf("stale-request"));assertEquals(reopened,f.open().read())
            assertTrue(f.open().clearIf(selected.id));assertNull(f.open().read())
        }
    }
    @Test fun restoreUsesReadOnlyPickerAndAlwaysRequiresExplicitRecoveryFlow() {
        Fixture().use {f->
            val handoff=f.open();val intent=handoff.begin(FileRequest("RESTORE",ownerId="synthetic-vault"))
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.action)
            handoff.receive(Activity.RESULT_OK,Intent().setData(Uri.parse("content://synthetic/backup.yunji")),false)
            val result=requireNotNull(f.open().read())
            assertEquals("RESTORE",result.request.kind);assertEquals("synthetic-vault",result.request.ownerId)
            assertTrue(result.confirm);assertTrue(result.returned)
            assertFalse(File(f.root,"file-handoff.enc").readBytes().toString(Charsets.ISO_8859_1).contains("backup.yunji"))
        }
    }    @Test fun safetyCopyExportBindsSourceVersionAndCannotReplayAfterStart() {
        Fixture().use {f->
            val request=FileRequest("SAFETY_EXPORT",ownerId="synthetic-vault",targetId=UUID.randomUUID().toString(),revision=123,sourceSize=456)
            val intent=f.open().begin(request,"synthetic-safety.yunji")
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.action)
            f.open().receive(Activity.RESULT_OK,Intent().setData(Uri.parse("content://synthetic/new-safety-copy")),false)
            val pending=requireNotNull(f.open().read())
            assertEquals(request,pending.request);assertTrue(pending.confirm)
            f.open().startBackup(pending.id)
            assertFalse(requireNotNull(f.open().read()).canApply)
            assertTrue(runCatching {f.open().startBackup(pending.id)}.exceptionOrNull() is cn.yunji.journal.core.security.BackupReplayException)
        }
    }}
