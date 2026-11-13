package cn.yunji.journal

import cn.yunji.journal.core.network.*
import cn.yunji.journal.data.*
import cn.yunji.journal.feature.CloudConnectionController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CloudConnectionControllerTest {
    private class Access: CloudConnectionAccess {
        var saved: SavedCloudConnection?=null
        var calls=0;var cancelled=0;var hold: CompletableDeferred<Unit>?=null
        override fun cancelProbe(){cancelled++}
        override suspend fun load()=saved
        override suspend fun probe(input: CloudConnection): CloudCapabilities {calls++;hold?.await();return CloudCapabilities(writable=true,verified=true)}
        override suspend fun browse(input: CloudConnection)=when(input.folder){emptyList<String>()->listOf("家庭") to true;listOf("家庭")->listOf("孕期资料") to true;else->emptyList<String>() to true}
        override suspend fun save(connection: CloudConnection,capabilities: CloudCapabilities){saved=SavedCloudConnection(connection=connection,capabilities=capabilities,testedAt=1)}
        override suspend fun disconnect(){saved=null}
        override suspend fun pendingCleanup(): ProbeCleanup?=null
        override suspend fun forgetCleanup()=Unit
    }
    @Test fun changingEndpointCannotSendSavedPasswordToProbe()=runTest {
        val access=Access().apply {saved=SavedCloudConnection(connection=CloudConnection("https://first.invalid/","synthetic","secret"),capabilities=CloudCapabilities(),testedAt=1)}
        val controller=CloudConnectionController(this,access);controller.load();runCurrent()
        assertEquals("",controller.state.value.password)
        controller.update("endpoint","https://different.invalid/");controller.test();runCurrent()
        assertEquals(0,access.calls);assertFalse(controller.state.value.canSave);assertEquals("secret",access.saved?.connection?.password)
    }
    @Test fun savingRequiresUnchangedSuccessfulTestAndThenClearsTypedPassword()=runTest {
        val access=Access();val controller=CloudConnectionController(this,access);controller.load();runCurrent()
        controller.update("username","synthetic");controller.update("password","synthetic-secret");controller.save();runCurrent();assertNull(access.saved)
        controller.test();runCurrent();assertTrue(controller.state.value.canSave)
        controller.update("folder","changed");controller.save();runCurrent();assertNull(access.saved)
        controller.test();runCurrent();controller.save();runCurrent()
        assertEquals(listOf("changed"),access.saved?.connection?.folder);assertEquals("",controller.state.value.password)
        assertTrue(controller.state.value.hasSavedPassword);assertFalse(controller.state.value.canSave)
    }
    @Test fun leavingCancelsProbeAndClearsUnsavedPasswordWithoutSaving()=runTest {
        val access=Access().apply {hold=CompletableDeferred()};val controller=CloudConnectionController(this,access)
        controller.load();runCurrent();controller.update("username","synthetic");controller.update("password","typed-secret")
        controller.test();runCurrent();assertTrue(controller.state.value.testing)
        controller.leave();runCurrent()
        assertEquals("",controller.state.value.password);assertFalse(controller.state.value.testing);assertFalse(controller.state.value.canSave)
        assertTrue(access.cancelled>0);assertNull(access.saved)
    }
    @Test fun directoryBrowsingCommitsOnlyAfterUseAndNeverRunsWriteProbe()=runTest {
        val access=Access().apply {saved=SavedCloudConnection(connection=CloudConnection("https://first.invalid/","synthetic","secret"),capabilities=CloudCapabilities(),testedAt=1)}
        val controller=CloudConnectionController(this,access);controller.load();runCurrent()
        controller.browse();runCurrent();controller.chooseDirectory("家庭");runCurrent()
        assertEquals("",controller.state.value.folder);controller.closeBrowser();runCurrent();assertEquals("",controller.state.value.folder)
        controller.browse();runCurrent();controller.chooseDirectory("家庭");runCurrent();controller.chooseDirectory("孕期资料");runCurrent();controller.useDirectory();runCurrent()
        assertEquals("家庭/孕期资料",controller.state.value.folder);assertNull(controller.state.value.browserPath);assertEquals(0,access.calls)
    }
    @Test fun internalRequirementFailuresAreNeverShownInEnglish()=runTest {
        val access=Access()
        val controller=CloudConnectionController(this,access)
        // require() 无消息时 Kotlin 抛 IllegalArgumentException("Failed requirement.")。
        val shown=controller.message(IllegalArgumentException("Failed requirement."))
        assertFalse("不应把英文内部提示直接展示给用户：$shown",shown.contains("Failed requirement"))
        assertTrue("应给出可操作的中文说明：$shown",shown.contains("云盘"))
        // 有业务含义的消息仍然原样保留。
        assertEquals("请填写账号",controller.message(IllegalArgumentException("请填写账号")))
    }
}
