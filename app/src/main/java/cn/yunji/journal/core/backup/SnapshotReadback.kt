package cn.yunji.journal.core.backup
import cn.yunji.journal.core.database.JournalDatabase
object SnapshotReadback {
    suspend fun verify(db: JournalDatabase,snapshot: LogicalSnapshot) {
        val dao=db.journalDao()
        require(dao.allPregnancies().toSet()==snapshot.pregnancies.toSet())
        require(dao.allDateRevisions().toSet()==snapshot.dateRevisions.toSet())
        require(dao.allVisits().toSet()==snapshot.visits.toSet())
        require(dao.allCustomReminders().toSet()==snapshot.customReminders.toSet())
        require(dao.allAttachments().toSet()==snapshot.attachments.toSet())
        require(dao.allDeletionBatches().toSet()==snapshot.deletionBatches.toSet())
        require(dao.allMedications().toSet()==snapshot.medications.toSet())
        require(dao.allMedicationPlans().toSet()==snapshot.plans.toSet())
        require(dao.allOccurrences().toSet()==snapshot.occurrences.toSet())
        require(dao.allIntakes().toSet()==snapshot.intakes.toSet())
        require(dao.allMetricDefinitions().toSet()==snapshot.definitions.toSet())
        require(dao.allMeasurements().toSet()==snapshot.measurements.toSet())
        require(dao.allMeasurementValues().toSet()==snapshot.values.toSet())
        require(dao.allJournalEntries().toSet()==snapshot.entries.toSet())
        require(dao.currentSequence()==snapshot.capturedSequence)
    }
}
