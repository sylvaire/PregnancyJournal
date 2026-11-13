package cn.yunji.journal

import cn.yunji.journal.core.backup.LogicalSnapshot
import cn.yunji.journal.core.backup.SnapshotRelations
import cn.yunji.journal.core.database.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class LogicalSnapshotTest {
    private val json=Json {encodeDefaults=true}
    private fun empty()=LogicalSnapshot(vaultId="synthetic-vault",snapshotId="synthetic-snapshot",writerId="synthetic-writer",writerSequence=1,capturedSequence=9,createdAt=10)
    @Test fun dictionaryContainsAllThirteenBusinessListsAndNoDeviceOrDraftState() {
        val fields=json.encodeToJsonElement(LogicalSnapshot.serializer(),empty()).jsonObject.keys
        val lists=setOf("pregnancies","dateRevisions","visits","attachments","deletionBatches","medications","plans","occurrences","intakes","definitions","measurements","values","entries")
        assertTrue(fields.containsAll(lists));assertEquals(13,lists.size)
        assertTrue(fields.intersect(setOf("drafts","importJobs","sourceUri","credentials","deviceKey","notifications")).isEmpty())
        assertEquals(empty(),json.decodeFromString<LogicalSnapshot>(json.encodeToString(empty())))
    }
    @Test fun historicalDrugSnapshotDeletedEntryAndExactDecimalSurviveRoundTrip() {
        val drug=MedicationEntity("drug","pregnancy","新名称","新规格","胶囊",1)
        val plan=MedicationPlanEntity(id="plan",pregnancyId="pregnancy",medicationId="drug",logicalPlanId="logical",version=1,dose="0.12500",unit="片",
            frequencyType="DAILY",times="08:00",weekdays="",zoneId="Asia/Shanghai",startDate="2026-10-01",endDate=null,status="ACTIVE",instructions="",source="合成来源",effectiveAt=1,
            medicationName="历史名称",medicationSpecification="历史规格",medicationDosageForm="片剂")
        val entry=JournalEntryEntity("entry","pregnancy","2026-10-01",null,"Asia/Shanghai",null,"合成症状","","合成备注",1,1,deletedAt=2,deletionBatchId="batch")
        val source=empty().copy(pregnancies=listOf(PregnancyEntity("pregnancy","合成档案",createdAt=1,updatedAt=1)),medications=listOf(drug),plans=listOf(plan),
            entries=listOf(entry),deletionBatches=listOf(DeletionBatchEntity("batch","JOURNAL","entry",2)),
            definitions=listOf(MetricDefinitionEntity("definition","pregnancy","metric",1,"CUSTOM","合成指标","单位","value",1)),
            measurements=listOf(MeasurementEntity("measurement","pregnancy","definition",1,"Asia/Shanghai","OTHER",null,"MANUAL","",1,1)),
            values=listOf(MeasurementValueEntity("measurement","value","0.12500")))
        val restored=json.decodeFromString<LogicalSnapshot>(json.encodeToString(source))
        SnapshotRelations.validate(restored)
        assertEquals(source,restored);assertEquals("历史名称",restored.plans.single().medicationName)
        assertEquals("0.12500",restored.values.single().decimalValue);assertEquals("batch",restored.entries.single().deletionBatchId)
        assertFalse(source.toString().contains("合成备注"))
    }
    @Test fun duplicateRecordsAndCrossPregnancyReferencesAreRejected() {
        val a=PregnancyEntity("a","合成",createdAt=1,updatedAt=1);val b=a.copy(id="b")
        assertTrue(runCatching {SnapshotRelations.validate(empty().copy(pregnancies=listOf(a,a)))}.isFailure)
        val definition=MetricDefinitionEntity("definition","a","metric",1,"CUSTOM","合成","单位","value",1)
        val measurement=MeasurementEntity("measurement","b","definition",1,"UTC","OTHER",null,"MANUAL","",1,1)
        assertTrue(runCatching {SnapshotRelations.validate(empty().copy(pregnancies=listOf(a,b),definitions=listOf(definition),measurements=listOf(measurement)))}.isFailure)
    }
    @Test fun restoredBatchHistoryIsPreservedButPurgingIsNotACompleteSnapshot() {
        val batch=DeletionBatchEntity("batch","JOURNAL","old-entry",1,state="RESTORED")
        SnapshotRelations.validate(empty().copy(deletionBatches=listOf(batch)))
        assertTrue(runCatching {SnapshotRelations.validate(empty().copy(deletionBatches=listOf(batch.copy(state="PURGING"))))}.isFailure)
    }
    @Test fun hostileExponentAndPartialObjectMetadataAreRejected() {
        val p=PregnancyEntity("pregnancy","合成",createdAt=1,updatedAt=1)
        val definition=MetricDefinitionEntity("definition",p.id,"metric",1,"CUSTOM","合成","单位","value",1)
        val measurement=MeasurementEntity("measurement",p.id,definition.id,1,"UTC","OTHER",null,"MANUAL","",1,1)
        val s=empty().copy(pregnancies=listOf(p),definitions=listOf(definition),measurements=listOf(measurement),values=listOf(MeasurementValueEntity(measurement.id,"value","1e999999999")))
        assertTrue(runCatching {SnapshotRelations.validate(s)}.isFailure)
        val drug=MedicationEntity("drug",p.id,"合成","","",1)
        val file=AttachmentEntity("file",null,"合成","image/png",1,33,"0".repeat(64),"0".repeat(64),null,null,null,null,null,1,medicationId=drug.id)
        SnapshotRelations.validate(empty().copy(pregnancies=listOf(p),medications=listOf(drug),attachments=listOf(file)))
        assertTrue(runCatching {SnapshotRelations.validate(empty().copy(pregnancies=listOf(p),medications=listOf(drug),attachments=listOf(file.copy(thumbnailId="thumbnail"))))}.isFailure)
    }
    @Test fun pressureAllowsMissingPulseButRejectsMissingDiastolicAndUnknownKeys() {
        val p=PregnancyEntity("p","合成",createdAt=1,updatedAt=1)
        val d=MetricDefinitionEntity("d",p.id,"metric",1,"PRESSURE","血压","mmHg","systolic,diastolic,pulse",1)
        val m=MeasurementEntity("m",p.id,d.id,1,"UTC","NONE",null,"MANUAL","",1,1)
        val values=listOf(MeasurementValueEntity(m.id,"systolic","120"),MeasurementValueEntity(m.id,"diastolic","80"))
        val s=empty().copy(pregnancies=listOf(p),definitions=listOf(d),measurements=listOf(m),values=values)
        SnapshotRelations.validate(s)
        assertTrue(runCatching {SnapshotRelations.validate(s.copy(values=values.take(1)))}.isFailure)
        assertTrue(runCatching {SnapshotRelations.validate(s.copy(values=values+MeasurementValueEntity(m.id,"unknown","1")))}.isFailure)
    }
    @Test fun sharedCipherObjectRequiresIdenticalMetadataIncludingThumbnailReferences() {
        val p=PregnancyEntity("p","合成",createdAt=1,updatedAt=1)
        val drug=MedicationEntity("drug",p.id,"合成","","",1)
        val a=AttachmentEntity("a",null,"合成","image/png",1,33,"0".repeat(64),"1".repeat(64),null,null,null,null,null,1,medicationId=drug.id,objectId="shared")
        val b=a.copy(id="b")
        val s=empty().copy(pregnancies=listOf(p),medications=listOf(drug),attachments=listOf(a,b))
        SnapshotRelations.validate(s)
        assertTrue(runCatching {SnapshotRelations.validate(s.copy(attachments=listOf(a,b.copy(cipherSha256="2".repeat(64)))))}.isFailure)
        val thumb=b.copy(objectId="other",thumbnailId="shared",thumbnailSize=1,thumbnailCipherSize=33,thumbnailSha256="0".repeat(64),thumbnailCipherSha256="2".repeat(64))
        assertTrue(runCatching {SnapshotRelations.validate(s.copy(attachments=listOf(a,thumb)))}.isFailure)
    }
    @Test fun purgedMeasurementTombstoneDoesNotRequireErasedValues() {
        val p=PregnancyEntity("p","合成",createdAt=1,updatedAt=1)
        val d=MetricDefinitionEntity("d",p.id,"metric",1,"WEIGHT","体重","kg","value",1)
        val m=MeasurementEntity("m",p.id,d.id,0,"UTC","NONE",null,"","",1,2,deletedAt=2,deletionBatchId="batch")
        val batch=DeletionBatchEntity("batch","MEASUREMENT",m.id,2,state="PURGED")
        val s=empty().copy(pregnancies=listOf(p),definitions=listOf(d),measurements=listOf(m),deletionBatches=listOf(batch))
        SnapshotRelations.validate(s)
        assertTrue(runCatching {SnapshotRelations.validate(s.copy(deletionBatches=listOf(batch.copy(state="DELETED"))))}.isFailure)
        assertTrue(runCatching {SnapshotRelations.validate(s.copy(deletionBatches=listOf(batch.copy(rootId="other"))))}.isFailure)
        assertTrue(runCatching {SnapshotRelations.validate(s.copy(values=listOf(MeasurementValueEntity(m.id,"value","60"))))}.isFailure)
    }
    @Test fun snapshotSchemaVersionTracksCurrentDatabaseSchema() {
        // 新增迁移时必须同步上调 LogicalSnapshot.SCHEMA_VERSION，否则备份会被标成旧版本。
        // 以导出 schema 的最大编号作为当前数据库版本的独立来源。
        val exported = java.io.File("schemas/cn.yunji.journal.core.database.JournalDatabase")
            .listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() } ?: emptyList()
        assertTrue("应存在导出的 Room schema", exported.isNotEmpty())
        assertEquals(exported.max(), LogicalSnapshot.SCHEMA_VERSION)
        val current = empty()
        assertEquals(LogicalSnapshot.SCHEMA_VERSION, current.schemaVersion)
        SnapshotRelations.validate(current)
        assertTrue(runCatching {SnapshotRelations.validate(current.copy(schemaVersion=LogicalSnapshot.SCHEMA_VERSION+1))}.isFailure)
        assertTrue(runCatching {SnapshotRelations.validate(current.copy(schemaVersion=LogicalSnapshot.OLDEST_READABLE_SCHEMA-1))}.isFailure)
    }
}
