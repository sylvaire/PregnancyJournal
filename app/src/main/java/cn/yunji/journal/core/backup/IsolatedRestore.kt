package cn.yunji.journal.core.backup

import android.content.Context
import cn.yunji.journal.core.database.*
import java.io.File

/** Restores only inside an authenticated archive's private staging directory. Never opens the live DB. */
object IsolatedRestore {
    /** Scoped bridge for RestoreWizard. Database is closed before its file is copied to a generation. */
    fun buildDatabase(context: Context,handle: VerifiedArchiveHandle): File = kotlinx.coroutines.runBlocking {
        val view=LocalBackupArchive.VerifiedArchive(handle.snapshot,handle.material,handle.directory,handle.slot)
        withDatabase(context,view){ /* Validate all tables before closing/checkpointing the isolated DB. */ }
        File(handle.directory,"restored.db")
    }
    suspend fun <T> withDatabase(context: Context,verified: LocalBackupArchive.VerifiedArchive,block: suspend (JournalDatabase)->T): T {
        val snapshot=verified.snapshot
        SnapshotRelations.validate(snapshot)
        require(verified.material.master.any {it!=0.toByte()} && verified.directory.isDirectory)
        val file=File(verified.directory,"restored.db")
        require(!file.exists()) {"临时恢复库已存在"}
        val key=verified.material.key("database")
        val db=try {JournalDatabase.open(context,file,key)}finally {key.fill(0)}
        try {
            val dao=db.journalDao()
            db.journalWrite {
                snapshot.pregnancies.forEach {dao.putPregnancy(it)}
                snapshot.dateRevisions.forEach {dao.putDateRevision(it)}
                snapshot.visits.forEach {dao.putVisit(it)}
                snapshot.customReminders.forEach {dao.putCustomReminder(it)}
                snapshot.deletionBatches.forEach {dao.putDeletionBatch(it)}
                snapshot.medications.forEach {dao.insertMedication(it)}
                snapshot.plans.forEach {dao.insertMedicationPlan(it)}
                snapshot.occurrences.forEach {dao.putOccurrence(it)}
                snapshot.intakes.forEach {dao.insertIntake(it)}
                snapshot.definitions.forEach {dao.insertMetricDefinition(it)}
                snapshot.measurements.forEach {dao.insertMeasurement(it)}
                dao.insertMeasurementValues(snapshot.values)
                snapshot.entries.forEach {dao.insertJournalEntry(it)}
                snapshot.attachments.forEach {a->
                    dao.putAttachment(a)
                    dao.putImportJob(ImportJobEntity(a.id,a.visitId,"",a.name,a.createdAt,
                        state=if(a.deletedAt==null)"READY" else "DELETED",phase="DONE",medicationId=a.medicationId))
                }
                // Internal change history is a fresh baseline; business version/date histories above are retained.
                if(snapshot.capturedSequence>0)dao.recordChange(ChangeEntity(sequence=snapshot.capturedSequence,
                    entityType="vault",entityId=snapshot.vaultId,revision=1,operation="RESTORE_BASELINE",createdAt=snapshot.createdAt))
            }
            // A successful insert transaction alone does not prove complete restoration.
            SnapshotReadback.verify(db,snapshot)
            return block(db)
        } finally {db.close()}
    }
}
