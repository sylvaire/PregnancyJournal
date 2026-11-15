package cn.yunji.journal

import cn.yunji.journal.feature.*
import cn.yunji.journal.core.backup.RestorePreview
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RestoreControllerTest {
    @Test fun selectedFileMetadataSurvivesValidationAndIsClearedOnLeave()=fixture {controller,_->
        controller.select("content://synthetic/backup", "合成备份.yunji", 4096)
        assertEquals("合成备份.yunji",controller.state.value.documentName)
        assertEquals(4096L,controller.state.value.documentSize)
        controller.key("synthetic-key");controller.prepare()
        assertEquals("CONFIRM",controller.state.value.stage)
        assertEquals("合成备份.yunji",controller.state.value.documentName)
        assertEquals("",controller.state.value.document)
        controller.leave()
        assertNull(controller.state.value.documentName);assertNull(controller.state.value.documentSize)
    }
    @Test fun replacingFileDoesNotKeepPreviousMetadataOrKey()=fixture {controller,_->
        controller.select("content://synthetic/first", "第一份.yunji", 100)
        controller.key("synthetic-key")
        controller.select("content://synthetic/second", "\n第二份.yunji\r", -1)
        assertEquals("第二份.yunji",controller.state.value.documentName)
        assertNull(controller.state.value.documentSize);assertEquals("",controller.state.value.key)
    }
    @Test fun failedValidationKeepsSelectedFileContext() {
        val scope=CoroutineScope(Dispatchers.Unconfined+Job())
        val access=object:RestoreAccess {
            override suspend fun prepare(document:String,recoveryKey:String):RestorePreview=error("synthetic failure")
            override suspend fun commit(preview:RestorePreview)=Unit
            override suspend fun discard(preview:RestorePreview)=Unit
            override fun isCommitted(preview:RestorePreview)=false
        }
        try {
            val controller=RestoreController(scope,access)
            controller.select("content://synthetic/backup", "合成备份.yunji", 4096)
            controller.key("synthetic-key");controller.prepare()
            assertEquals("KEY",controller.state.value.stage)
            assertEquals("合成备份.yunji",controller.state.value.documentName)
            assertEquals(4096L,controller.state.value.documentSize)
            assertEquals("",controller.state.value.key)
        }finally {scope.cancel()}
    }
    private val preview=RestorePreview("generation","snapshot",1,1,2,3,4,5,6,7,"writer",8)
    private inner class Access: RestoreAccess {
        var commits=0;var discarded=0;var committed=false;var failCommit=false;var cancelCommit=false
        override suspend fun prepare(document: String,recoveryKey: String)=preview
        override suspend fun commit(preview: RestorePreview){commits++;if(cancelCommit)throw CancellationException("Synthetic cancel");if(failCommit)throw IllegalStateException("Synthetic failure");committed=true}
        override suspend fun discard(preview: RestorePreview){discarded++}
        override fun isCommitted(preview: RestorePreview)=committed
    }
    private fun fixture(block: (RestoreController,Access)->Unit) {
        val scope=CoroutineScope(Dispatchers.Unconfined+Job());val access=Access()
        try {block(RestoreController(scope,access),access)}finally {scope.cancel()}
    }
    private fun ready(controller: RestoreController) {controller.select("content://synthetic/backup");controller.key("synthetic-key");controller.prepare()}
    @Test fun keyDisappearsBeforePreviewAndStateStringNeverExposesIt()=fixture {controller,_->
        controller.select("content://synthetic/backup");controller.key("sensitive-synthetic-key")
        assertFalse(controller.state.value.toString().contains("sensitive-synthetic-key"))
        controller.prepare();assertEquals("CONFIRM",controller.state.value.stage);assertEquals("",controller.state.value.key)
    }
    @Test fun explicitAcknowledgementRequiredAndDuplicateConfirmIgnored()=fixture {controller,access->
        ready(controller);controller.confirm();assertEquals(0,access.commits)
        controller.acknowledge(true);controller.confirm();controller.confirm()
        assertEquals(1,access.commits);assertEquals("DONE",controller.state.value.stage)
    }
    @Test fun leaveDiscardsPreparedPreviewAndClearsUi()=fixture {controller,access->
        ready(controller);controller.leave();assertEquals(1,access.discarded);assertEquals("SELECT",controller.state.value.stage)
        assertNull(controller.state.value.preview)
    }
    @Test fun commitErrorCleansUncommittedPreviewWithoutLeakingDetails()=fixture {controller,access->
        ready(controller);access.failCommit=true;controller.acknowledge(true);controller.confirm()
        assertEquals("ERROR",controller.state.value.stage);assertEquals(1,access.discarded)
        assertFalse(controller.state.value.message.orEmpty().contains("Synthetic failure"))
    }
    @Test fun actualCommittedPointerWinsOverLateFailure()=fixture {controller,access->
        ready(controller);access.failCommit=true;access.committed=true;controller.acknowledge(true);controller.confirm()
        assertEquals("DONE",controller.state.value.stage);assertEquals(0,access.discarded)
    }
    @Test fun cancelledUncommittedSwitchDiscardsPreview()=fixture {controller,access->
        ready(controller);access.cancelCommit=true;controller.acknowledge(true);controller.confirm()
        assertEquals("ERROR",controller.state.value.stage);assertEquals(1,access.discarded)
    }
    @Test fun cancelledButAlreadyCommittedSwitchRemainsDone()=fixture {controller,access->
        ready(controller);access.cancelCommit=true;access.committed=true;controller.acknowledge(true);controller.confirm()
        assertEquals("DONE",controller.state.value.stage);assertEquals(0,access.discarded)
    }    @Test fun asynchronousHostPreparationFreezesConfirmationBeforeCommit()=fixture {controller,access->
        ready(controller);controller.acknowledge(true)
        val gate=CompletableDeferred<Unit>()
        controller.confirm {gate.await()}
        assertEquals("RESTORING",controller.state.value.stage);assertEquals(0,access.commits)
        controller.acknowledge(false);controller.confirm()
        gate.complete(Unit)
        assertEquals(1,access.commits);assertEquals("DONE",controller.state.value.stage)
    }
    @Test fun hostPreparationFailureNeverCallsCommit()=fixture {controller,access->
        ready(controller);controller.acknowledge(true)
        controller.confirm {throw IllegalStateException("Synthetic model preparation failure")}
        assertEquals(0,access.commits);assertEquals(1,access.discarded);assertEquals("ERROR",controller.state.value.stage)
    }

    // --- cloud flow ---

    private inner class CloudAccess: RestoreAccess {
        var listed=0;var prepared=0;var discarded=0;val askedKeys=mutableListOf<String>()
        var snapshots: List<cn.yunji.journal.core.backup.SnapshotRef> = listOf(
            cn.yunji.journal.core.backup.SnapshotRef("snap-a",1_700_000_000_000,"writer",9,emptySet()),
            cn.yunji.journal.core.backup.SnapshotRef("snap-b",1_600_000_000_000,"writer",3,emptySet()))
        var failList=false;var failDownload=false
        override suspend fun prepare(document: String,recoveryKey: String)=preview
        override suspend fun commit(preview: RestorePreview){}
        override suspend fun discard(preview: RestorePreview){discarded++}
        override fun isCommitted(preview: RestorePreview)=false
        override suspend fun listCloudSnapshots(recoveryKey: String): List<cn.yunji.journal.core.backup.SnapshotRef> {
            listed++;askedKeys+=recoveryKey
            if(failList)throw IllegalStateException("Synthetic cloud failure")
            return snapshots
        }
        override suspend fun prepareCloud(snapshotId: String,recoveryKey: String): RestorePreview {
            prepared++;askedKeys+=recoveryKey
            if(failDownload)throw IllegalStateException("Synthetic download failure")
            return preview.copy(snapshotId=snapshotId)
        }
    }
    private fun cloud(block: (RestoreController,CloudAccess)->Unit) {
        val scope=CoroutineScope(Dispatchers.Unconfined+Job());val access=CloudAccess()
        try {block(RestoreController(scope,access),access)}finally {scope.cancel()}
    }

    @Test fun cloudFlowRequiresKeyThenExplicitSnapshotBeforeDownloading()=cloud {controller,access->
        controller.startCloud();assertEquals("CLOUD",controller.state.value.stage)
        controller.findCloud();assertEquals("no lookup without a key",0,access.listed)

        controller.key("YJ1:synthetic")
        controller.findCloud()
        assertEquals(1,access.listed);assertEquals("CLOUD_LIST",controller.state.value.stage)
        assertEquals(listOf("snap-a","snap-b"),controller.state.value.cloudSnapshots.map {it.snapshotId})

        // Download stays blocked until a snapshot is chosen.
        controller.prepare();assertEquals(0,access.prepared)
        controller.selectSnapshot("snap-b");controller.prepare()
        assertEquals(1,access.prepared);assertEquals("snap-b",controller.state.value.preview?.snapshotId)
        assertEquals("CONFIRM",controller.state.value.stage)
        assertEquals("the key must be wiped before the preview is shown","",controller.state.value.key)
    }

    @Test fun cloudListingRejectsUnknownSnapshotsAndSurvivesLookupFailure()=cloud {controller,access->
        controller.startCloud();controller.key("YJ1:synthetic")
        access.failList=true
        controller.findCloud()
        assertEquals("CLOUD",controller.state.value.stage)
        assertNotNull(controller.state.value.message)
        assertTrue(controller.state.value.key.isNotBlank())

        access.failList=false
        controller.findCloud();assertEquals("CLOUD_LIST",controller.state.value.stage)
        controller.selectSnapshot("not-in-the-list")
        assertNull("an unlisted snapshot must not be selectable",controller.state.value.snapshotId)
    }

    @Test fun emptyCloudResultKeepsTheUserInTheLookupStage()=cloud {controller,access->
        access.snapshots=emptyList()
        controller.startCloud();controller.key("YJ1:synthetic");controller.findCloud()
        assertEquals("CLOUD",controller.state.value.stage)
        assertTrue(controller.state.value.cloudSnapshots.isEmpty())
        assertTrue(requireNotNull(controller.state.value.message).contains("没有找到"))
    }

    @Test fun failedCloudDownloadReturnsToTheListWithTheChoiceKept()=cloud {controller,access->
        controller.startCloud();controller.key("YJ1:synthetic");controller.findCloud()
        controller.selectSnapshot("snap-a");access.failDownload=true
        controller.prepare()
        assertEquals("CLOUD_LIST",controller.state.value.stage)
        assertEquals("snap-a",controller.state.value.snapshotId)
        assertEquals("the snapshot list must survive a failed download",
            listOf("snap-a","snap-b"),controller.state.value.cloudSnapshots.map {it.snapshotId})
        assertFalse(controller.state.value.message.orEmpty().contains("Synthetic download failure"))
    }

    @Test fun leavingTheCloudFlowClearsKeyAndSelection()=cloud {controller,access->
        controller.startCloud();controller.key("YJ1:synthetic");controller.findCloud();controller.selectSnapshot("snap-a")
        controller.leave()
        assertEquals("SELECT",controller.state.value.stage)
        assertEquals("",controller.state.value.key)
        assertNull(controller.state.value.snapshotId)
        assertTrue(controller.state.value.cloudSnapshots.isEmpty())
    }
}
