package cn.yunji.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.core.backup.*
import cn.yunji.journal.core.database.*
import cn.yunji.journal.core.security.*
import cn.yunji.journal.core.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class IsolatedRestoreTest {
    @Test fun fullArchiveRestoresAllBusinessTablesAndRebuildsOnlyCompletedImportJobs()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.cacheDir,"isolated-restore-${UUID.randomUUID()}").apply {mkdirs()}
        val vault=VaultMaterial(UUID.randomUUID().toString(),VaultCrypto.randomKey())
        try {
            val recovery=RecoveryKey.create();val slot=RecoverySlots.create(vault,recovery)
            val store=EncryptedFileStore(File(root,"source"),vault)
            val bytes="synthetic authenticated original".toByteArray();val original=store.put(bytes.inputStream())
            val p=PregnancyEntity("p","合成恢复档案",createdAt=1,updatedAt=1)
            val date=PregnancyDateRevisionEntity("date",p.id,null,null,"UNKNOWN",1)
            val visit=VisitEntity("visit",p.id,"2026-10-19",zoneId="UTC",dateRevisionId=date.id,createdAt=1,updatedAt=1)
            val custom=CustomReminderEntity("custom",p.id,"合成待办",startDate="2026-10-19",localTime="09:00",repeat="WEEKLY",createdAt=1,updatedAt=1)
            val drug=MedicationEntity("drug",p.id,"合成药品","当前规格","胶囊",1)
            val plan=MedicationPlanEntity("plan",p.id,drug.id,"logical",1,"0.12500","片","DAILY_TIMES","08:00","","UTC","2026-10-19",null,"ACTIVE","","",1,medicationName="历史药名",medicationSpecification="历史规格")
            val point=MedicationOccurrenceEntity("point",p.id,plan.id,"2026-10-19","08:00",0,1,"UTC",createdAt=1)
            val intake=MedicationIntakeEntity("intake",p.id,plan.id,point.id,point.id,"TAKEN",1,"2026-10-19","UTC","0.12500","片","",1,1)
            val definition=MetricDefinitionEntity("definition",p.id,"metric",1,"CUSTOM","合成指标","单位","value",1)
            val measurement=MeasurementEntity("measurement",p.id,definition.id,1,"UTC","NONE",null,"MANUAL","",1,1)
            val value=MeasurementValueEntity(measurement.id,"value","0.12500")
            val batch=DeletionBatchEntity("batch","JOURNAL","entry",2)
            val entry=JournalEntryEntity("entry",p.id,"2026-10-19",null,"UTC",null,"","","合成回收站历史",1,1,deletedAt=2,deletionBatchId=batch.id)
            val attachment=AttachmentEntity(original.id,visit.id,"合成原件","image/png",original.plainSize,original.cipherSize,original.plainSha256,original.cipherSha256,null,null,null,null,null,1)
            val source=LogicalSnapshot(vaultId=vault.id,snapshotId=UUID.randomUUID().toString(),writerId=UUID.randomUUID().toString(),writerSequence=1,capturedSequence=42,createdAt=1,
                pregnancies=listOf(p),dateRevisions=listOf(date),visits=listOf(visit),medications=listOf(drug),plans=listOf(plan),occurrences=listOf(point),intakes=listOf(intake),
                definitions=listOf(definition),measurements=listOf(measurement),values=listOf(value),deletionBatches=listOf(batch),entries=listOf(entry),attachments=listOf(attachment),customReminders=listOf(custom))
            val archive=File(root,"complete.yunji")
            LocalBackupArchive.write(source,vault,slot,store,archive)
            val verified=LocalBackupArchive.verify(archive,recovery,root)
            val directory=verified.directory
            verified.use {
                IsolatedRestore.withDatabase(context,it){db->
                    val dao=db.journalDao()
                    assertEquals(listOf(p),dao.allPregnancies());assertEquals(listOf(date),dao.allDateRevisions());assertEquals(listOf(visit),dao.allVisits())
                    assertEquals(listOf(custom),dao.allCustomReminders())
                    assertEquals(listOf(drug),dao.allMedications());assertEquals(listOf(plan),dao.allMedicationPlans());assertEquals(listOf(point),dao.allOccurrences());assertEquals(listOf(intake),dao.allIntakes())
                    assertEquals(listOf(definition),dao.allMetricDefinitions());assertEquals(listOf(measurement),dao.allMeasurements());assertEquals(listOf(value),dao.allMeasurementValues())
                    assertEquals(listOf(batch),dao.allDeletionBatches());assertEquals(listOf(entry),dao.allJournalEntries());assertEquals(listOf(attachment),dao.allAttachments())
                    assertEquals(42L,dao.currentSequence());assertTrue(dao.allDrafts().isEmpty());assertTrue(dao.objectPins().isEmpty());assertTrue(dao.retiredObjects().isEmpty())
                    assertEquals("",dao.allImportJobs().single().sourceUri);assertEquals("READY",dao.allImportJobs().single().state)
                    val plain=File(directory,"check-original")
                    EncryptedFileStore(File(directory,"objects"),it.material).decryptTo(original,plain)
                    assertArrayEquals(bytes,plain.readBytes());plain.delete()
                }
            }
            assertFalse(directory.exists());assertTrue(vault.master.any {it!=0.toByte()})
        }finally {vault.master.fill(0);root.deleteRecursively()}
    }
}
