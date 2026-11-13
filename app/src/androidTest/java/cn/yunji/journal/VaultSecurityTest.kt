package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.database.journalWrite
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Independent directories AND aliases: these tests cannot mutate the user's vault. */
class VaultSecurityTest {
    private class Fixture: AutoCloseable {
        val base=ApplicationProvider.getApplicationContext<Context>()
        val token=UUID.randomUUID().toString()
        val root=File(base.cacheDir,"security-$token").apply {mkdirs()}
        val context=object: ContextWrapper(base) {
            override fun getNoBackupFilesDir()=root
            override fun getApplicationContext(): Context=this
            override fun getSharedPreferences(name: String,mode: Int)=base.getSharedPreferences("$token-$name",mode)
        }
        val keys=DeviceKeyStore.isolated(context,token)
        val wrapper get()=File(root,"vault/device-key.json")
        val alias get()="${base.packageName}.vault-wrap.test-$token.v1"
        override fun close() {
            val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
            store.aliases().toList().filter {it==alias || it.startsWith("$alias.strict.")}.forEach(store::deleteEntry)
            listOf("security-state","reminder-state","cloud-scheduler","cloud-upload-state").forEach {base.deleteSharedPreferences("$token-$it")}
            root.deleteRecursively()
        }
    }

    @Test fun normalWrapperReopensSameVaultAndNeverContainsMasterPlaintext() {
        Fixture().use {f ->
            val first=f.keys.open();val second=f.keys.open()
            assertEquals(first.id,second.id);assertArrayEquals(first.master,second.master)
            assertFalse(f.keys.strict())
            assertFalse(f.wrapper.readText().contains(VaultCrypto.encode(first.master)))
            first.master.fill(0);second.master.fill(0)
        }
    }
    @Test fun missingWrapperWithExistingDatabaseNeverCreatesReplacement() {
        Fixture().use {f ->
            f.keys.open().master.fill(0);f.wrapper.delete()
            val database=File(f.root,"vault/journal.db").apply {writeText("synthetic existing bytes")}
            assertTrue(runCatching {f.keys.open()}.isFailure)
            assertFalse(f.wrapper.exists());assertEquals("synthetic existing bytes",database.readText())
        }
    }
    @Test fun missingDeviceKeyPreservesCommittedWrapper() {
        Fixture().use {f ->
            f.keys.open().master.fill(0);val before=f.wrapper.readBytes()
            KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(f.alias)}
            assertTrue(runCatching {f.keys.open()}.isFailure)
            assertArrayEquals(before,f.wrapper.readBytes())
        }
    }
    @Test fun corruptWrapperFailsClosedWithoutOverwritingIt() {
        Fixture().use {f ->
            f.keys.open().master.fill(0);f.wrapper.writeText("broken synthetic wrapper")
            assertTrue(runCatching {f.keys.open()}.isFailure)
            assertEquals("broken synthetic wrapper",f.wrapper.readText())
        }
    }
    @Test fun strictMetadataCannotFallBackToOrdinaryWrapper() {
        Fixture().use {f ->
            f.keys.open().master.fill(0)
            val json=org.json.JSONObject(f.wrapper.readText()).put("mode","STRICT").put("keyAlias","${f.alias}.strict.synthetic")
            f.wrapper.writeText(json.toString());assertTrue(f.keys.strict())
            val failure=runCatching {f.keys.open()}.exceptionOrNull()
            assertTrue(failure is VaultLockedException)
        }
    }
    @Test fun clearingSessionWipesPreviouslyHeldMaster() {
        val session=VaultSession();val first=VaultMaterial("first",VaultCrypto.randomKey());val next=VaultMaterial("next",VaultCrypto.randomKey())
        session.set(first);session.set(next);assertTrue(first.master.all {it==0.toByte()})
        session.clear();assertNull(session.material());assertTrue(next.master.all {it==0.toByte()})
    }

    @androidx.test.filters.LargeTest
    @Test fun strictCommitWithoutSystemAuthenticationCannotReplaceNormalWrapper() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT>=30 && context.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure)
        Fixture().use {f ->
            val material=f.keys.open();val before=f.wrapper.readBytes()
            val request=f.keys.prepareEnableStrict()
            try {
                assertTrue(runCatching {f.keys.complete(request,request.cipher)}.isFailure)
                assertArrayEquals(before,f.wrapper.readBytes());assertFalse(f.keys.strict())
                assertArrayEquals(material.master,f.keys.open().master)
            } finally {f.keys.cancel(request);material.master.fill(0)}
        }
    }

    /** Read-only live-vault probe; explicitly limited to the project's synthetic emulator vault. */
    @androidx.test.filters.LargeTest
    @Test fun liveStrictVaultHasNoOrdinaryKeyAndDefersRuntimeUntilAuthentication() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish") &&
            androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("liveVaultProbe")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val keys=DeviceKeyStore(context);assertTrue(keys.strict())
        assertTrue(runCatching {keys.open()}.exceptionOrNull() is VaultLockedException)
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        assertFalse(store.containsAlias("${context.packageName}.vault-wrap.v1"))
        val settings=cn.yunji.journal.data.SecurityStore(context)
        val access=AccessState(context,settings,keys)
        val runtime=VaultRuntime(context,keys,VaultSession(),java.time.Clock.systemUTC(),cn.yunji.journal.data.ReminderStore(context),cn.yunji.journal.data.ReminderNotifier(context),access)
        assertTrue(runCatching {runtime.resources()}.exceptionOrNull() is VaultLockedException)
        assertFalse(runtime.hasResources())
    }
    @Test fun runtimeReopensForeignGenerationAndRevocationWipesRecoveredSession()=kotlinx.coroutines.runBlocking {
        Fixture().use {f->
            val device=f.keys.open()
            val restored=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
            val vaultDir=File(f.root,"vault")
            val layout=cn.yunji.journal.core.backup.VaultLayout(vaultDir)
            val id=layout.newGenerationId()
            val dbFile=layout.generationDatabase(id).apply {parentFile!!.mkdirs()}
            File(dbFile.parentFile,"objects").mkdirs()
            val key=restored.key("database")
            val db=try {cn.yunji.journal.core.database.JournalDatabase.open(f.context,dbFile,key)}finally {key.fill(0)}
            val pregnancy=cn.yunji.journal.core.database.PregnancyEntity("synthetic-runtime","合成恢复",createdAt=1,updatedAt=1)
            try {db.journalWrite {db.journalDao().putPregnancy(pregnancy)}}finally {db.close()}
            cn.yunji.journal.core.backup.GenerationKeyStore(vaultDir).install(id,device,restored)
            layout.writePointer(cn.yunji.journal.core.backup.VaultPointer(activeId=id))
            val settings=cn.yunji.journal.data.SecurityStore(f.context)
            val access=AccessState(f.context,settings,f.keys)
            val runtime=VaultRuntime(f.context,f.keys,VaultSession(),java.time.Clock.systemUTC(),cn.yunji.journal.data.ReminderStore(f.context),cn.yunji.journal.data.ReminderNotifier(f.context),access)
            try {
                val resources=runtime.resources()
                assertEquals(restored.id,resources.material.id)
                assertEquals(listOf(pregnancy),resources.database.journalDao().allPregnancies())
                assertEquals(File(dbFile.parentFile,"objects"),resources.importer.objectDirectory)
                val held=resources.material.master
                runtime.revoke();assertTrue(held.all {it==0.toByte()});assertFalse(runtime.hasResources())
                val deviceAgain=f.keys.open()
                try {assertEquals(device.id,deviceAgain.id);assertArrayEquals(device.master,deviceAgain.master)}finally {deviceAgain.master.fill(0)}
            }finally {runtime.revoke();device.master.fill(0);restored.master.fill(0)}
        }
    }    @Test fun runtimeStartupVerifiesInterruptedForeignGeneration()=startupValidation(false)
    @Test fun runtimeStartupRollsBackDamagedManifest()=startupValidation(true)
    private fun startupValidation(corrupt: Boolean)=kotlinx.coroutines.runBlocking {
        Fixture().use {f->
            val device=f.keys.open();val restored=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
            val root=File(f.root,"vault");val layout=cn.yunji.journal.core.backup.VaultLayout(root)
            val old=cn.yunji.journal.core.database.PregnancyEntity("old","合成旧档案",createdAt=1,updatedAt=1)
            val fresh=cn.yunji.journal.core.database.PregnancyEntity("new","合成恢复档案",createdAt=1,updatedAt=1)
            suspend fun create(file: File,material: VaultMaterial,pregnancy: cn.yunji.journal.core.database.PregnancyEntity) {
                file.parentFile!!.mkdirs();val key=material.key("database")
                val db=try {cn.yunji.journal.core.database.JournalDatabase.open(f.context,file,key)}finally {key.fill(0)}
                try {db.journalWrite {db.journalDao().putPregnancy(pregnancy)}}finally {db.close()}
            }
            create(layout.legacyDatabase(),device,old)
            val id=layout.newGenerationId();val target=layout.generationDatabase(id)
            create(target,restored,fresh);File(target.parentFile,"objects").mkdirs()
            cn.yunji.journal.core.backup.GenerationKeyStore(root).install(id,device,restored)
            val snapshot=cn.yunji.journal.core.backup.LogicalSnapshot(vaultId=restored.id,snapshotId=UUID.randomUUID().toString(),writerId=UUID.randomUUID().toString(),writerSequence=1,capturedSequence=0,createdAt=1,pregnancies=listOf(fresh))
            val bytes=cn.yunji.journal.core.backup.BackupManifest.encode(snapshot,restored)
            if(corrupt)bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
            File(target.parentFile,"restore-manifest.enc").writeBytes(bytes)
            layout.writePointer(cn.yunji.journal.core.backup.VaultPointer(activeId=id))
            val journal=cn.yunji.journal.core.backup.RestoreJournal(root)
            journal.write(cn.yunji.journal.core.backup.RestoreStage.SWITCHED,id,null,snapshot.snapshotId)
            val settings=cn.yunji.journal.data.SecurityStore(f.context)
            val runtime=VaultRuntime(f.context,f.keys,VaultSession(),java.time.Clock.systemUTC(),cn.yunji.journal.data.ReminderStore(f.context),cn.yunji.journal.data.ReminderNotifier(f.context),AccessState(f.context,settings,f.keys))
            try {
                val resources=runtime.resources()
                assertEquals(listOf(if(corrupt)old else fresh),resources.database.journalDao().allPregnancies())
                if(corrupt){assertNull(layout.activeGenerationId());assertNull(journal.read())}
                else {assertEquals(id,layout.activeGenerationId());assertEquals(cn.yunji.journal.core.backup.RestoreStage.VERIFIED,journal.read())}
                assertTrue(target.isFile);assertTrue(layout.legacyDatabase().isFile)
            }finally {runtime.revoke();device.master.fill(0);restored.master.fill(0)}
        }
    }    @Test fun coordinatedRestorePreservesSafetyCopyAndClosesOldGraph()=kotlinx.coroutines.runBlocking {
        Fixture().use {f->
            val settings=cn.yunji.journal.data.SecurityStore(f.context)
            val runtime=VaultRuntime(f.context,f.keys,VaultSession(),java.time.Clock.systemUTC(),cn.yunji.journal.data.ReminderStore(f.context),cn.yunji.journal.data.ReminderNotifier(f.context),AccessState(f.context,settings,f.keys))
            val source=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
            val device=f.keys.open()
            try {
                val old=runtime.resources()
                val oldId=old.repository.savePregnancy(null,"合成旧资料",null,null,"","")
                val recovery=RecoveryKey.create()
                cn.yunji.journal.data.RecoverySettings(f.context,old.material).save(cn.yunji.journal.core.backup.RecoverySlots.create(old.material,recovery))
                val savedConnection=cn.yunji.journal.core.network.SavedCloudConnection(
                    connection=cn.yunji.journal.core.network.CloudConnection("https://example.invalid/","synthetic","not-a-real-secret"),
                    capabilities=cn.yunji.journal.core.network.CloudCapabilities(),testedAt=1)
                cn.yunji.journal.data.CloudConnectionStore(f.context,old.material).save(savedConnection)
                val oldCredentialFile=File(f.root,"vault/cloud-connection.enc")
                val oldCredentialBytes=oldCredentialFile.readBytes()
                val sourceRecovery=RecoveryKey.create()
                val slot=cn.yunji.journal.core.backup.RecoverySlots.create(source,sourceRecovery)
                val fresh=cn.yunji.journal.core.database.PregnancyEntity("restored","合成新资料",createdAt=1,updatedAt=1)
                val snapshot=cn.yunji.journal.core.backup.LogicalSnapshot(vaultId=source.id,snapshotId=UUID.randomUUID().toString(),writerId=UUID.randomUUID().toString(),writerSequence=1,capturedSequence=1,createdAt=1,pregnancies=listOf(fresh))
                val archive=File(f.root,"source.yunji")
                cn.yunji.journal.core.backup.LocalBackupArchive.write(snapshot,source,slot,cn.yunji.journal.core.storage.EncryptedFileStore(File(f.root,"source-objects"),source),archive)
                val vaultDir=File(f.root,"vault")
                val wizard=cn.yunji.journal.core.backup.RestoreWizard(cn.yunji.journal.core.backup.VaultLayout(vaultDir),cn.yunji.journal.core.backup.RestoreJournal(vaultDir),File(f.root,"temporary"))
                val preview=runtime.prepareRestore(wizard,archive,sourceRecovery)
                assertTrue(runCatching {runtime.commitPreparedRestore(wizard,preview){throw java.io.IOException("Synthetic background stop failure")}}.isFailure)
                assertTrue(runtime.resources()===old)
                assertTrue(old.material.master.any {it!=0.toByte()})
                assertNull(cn.yunji.journal.core.backup.VaultLayout(vaultDir).activeGenerationId())
                var stopped=false
                val safety=runtime.commitPreparedRestore(wizard,preview){stopped=true}
                assertTrue(stopped);assertTrue(safety.file.isFile)
                assertTrue(old.material.master.all {it==0.toByte()})
                var staleWriteExecuted=false
                assertTrue(runCatching {old.database.journalWrite {staleWriteExecuted=true}}.isFailure)
                assertFalse(staleWriteExecuted)
                val current=runtime.resources()
                assertEquals(source.id,current.material.id)
                assertEquals(listOf(fresh),current.database.journalDao().allPregnancies())
                val scheduler=cn.yunji.journal.data.CloudSchedulerStore(f.context)
                assertEquals(cn.yunji.journal.data.DeviceRole.SECONDARY,scheduler.load().role)
                assertFalse(scheduler.load().autoEnabled)
                assertEquals(slot,cn.yunji.journal.data.RecoverySettings(f.context,current.material).load())
                assertNull(cn.yunji.journal.data.CloudConnectionStore(f.context,current.material).load())
                assertArrayEquals(oldCredentialBytes,oldCredentialFile.readBytes())
                scheduler.setRole(cn.yunji.journal.data.DeviceRole.PRIMARY,1);scheduler.setAutoEnabled(true)
                runtime.revoke();runtime.resources()
                assertEquals(cn.yunji.journal.data.DeviceRole.PRIMARY,scheduler.load().role)
                assertTrue(scheduler.load().autoEnabled)
                cn.yunji.journal.core.backup.LocalBackupArchive.verify(safety.file,recovery,f.root).use {verified->assertEquals(oldId,verified.snapshot.pregnancies.single().id)}
            }finally {runtime.revoke();device.master.fill(0);source.master.fill(0)}
        }
    }}
