package cn.yunji.journal.core.backup

/** Linear graph checks before staging a database. Content/enum/file authentication follows separately. */
object SnapshotRelations {
    private val identifiers=Regex("[A-Za-z0-9_-]{1,160}")
    private val hashes=Regex("[0-9a-f]{64}")
    private val decimals=Regex("[0-9]{1,12}(\\.[0-9]{1,6})?")
    fun validate(s: LogicalSnapshot) {
        require(s.formatVersion==1 && s.schemaVersion in LogicalSnapshot.OLDEST_READABLE_SCHEMA..LogicalSnapshot.SCHEMA_VERSION) {"不支持的备份版本"}
        require(s.writerSequence>0 && s.capturedSequence>=0 && s.createdAt>=0)
        val groups=listOf(s.pregnancies,s.dateRevisions,s.visits,s.attachments,s.deletionBatches,s.medications,s.plans,s.occurrences,s.intakes,s.definitions,s.measurements,s.values,s.entries,s.customReminders)
        require(groups.sumOf {it.size.toLong()}<=200_000) {"备份记录数量超过限制"}
        listOf(s.vaultId,s.snapshotId,s.writerId).forEach(::id)
        fun <T> index(rows: List<T>,key: (T)->String): Map<String,T> {
            val result=HashMap<String,T>()
            rows.forEach {row->val name=key(row);id(name);require(result.put(name,row)==null) {"备份记录标识重复"}}
            return result
        }
        val pregnancies=index(s.pregnancies){it.id};val dates=index(s.dateRevisions){it.id};val visits=index(s.visits){it.id}
        index(s.customReminders){it.id}
        val files=index(s.attachments){it.id};val batches=index(s.deletionBatches){it.id};val drugs=index(s.medications){it.id}
        val plans=index(s.plans){it.id};val points=index(s.occurrences){it.id};val definitions=index(s.definitions){it.id}
        val measures=index(s.measurements){it.id};index(s.intakes){it.id};index(s.entries){it.id}
        fun batch(value: String?) {require(value==null || value in batches) {"删除批次引用缺失"}}
        s.pregnancies.forEach {batch(it.deletionBatchId)}
        s.dateRevisions.forEach {require(it.pregnancyId in pregnancies)}
        s.visits.forEach {row->require(row.pregnancyId in pregnancies)
            require(row.dateRevisionId==null || dates[row.dateRevisionId]?.pregnancyId==row.pregnancyId)
            require(row.coverAttachmentId==null || files[row.coverAttachmentId]?.let {it.visitId==row.id && it.deletedAt==null}==true)
            batch(row.deletionBatchId)}
        s.medications.forEach {require(it.pregnancyId in pregnancies);batch(it.deletionBatchId)}
        require(s.plans.map {it.logicalPlanId to it.version}.distinct().size==s.plans.size)
        s.plans.forEach {require(it.version>0 && it.pregnancyId in pregnancies && drugs[it.medicationId]?.pregnancyId==it.pregnancyId);batch(it.deletionBatchId)}
        s.occurrences.forEach {require(plans[it.planVersionId]?.pregnancyId==it.pregnancyId);batch(it.deletionBatchId)}
        require(s.intakes.mapNotNull {it.occurrenceId}.distinct().size==s.intakes.count {it.occurrenceId!=null})
        s.intakes.forEach {row->require(plans[row.planVersionId]?.pregnancyId==row.pregnancyId)
            require(row.sourceOccurrenceId==null || points[row.sourceOccurrenceId]?.let {it.planVersionId==row.planVersionId && it.pregnancyId==row.pregnancyId}==true)
            require(row.occurrenceId==null || row.occurrenceId==row.sourceOccurrenceId);batch(row.deletionBatchId)}
        require(s.definitions.map {it.logicalId to it.version}.distinct().size==s.definitions.size)
        s.definitions.forEach {require(it.version>0 && it.pregnancyId in pregnancies);batch(it.deletionBatchId)}
        s.measurements.forEach {require(definitions[it.definitionId]?.pregnancyId==it.pregnancyId);batch(it.deletionBatchId)}
        val valueKeys=HashSet<Pair<String,String>>()
        s.values.forEach {require(it.measurementId in measures && valueKeys.add(it.measurementId to it.valueKey))
            require(it.valueKey.length in 1..64 && decimals.matches(it.decimalValue)) {"备份数值无效"}}
        val definitionKeys=s.definitions.associate {definition->
            val keys=definition.valueKeys.split(",")
            require(keys.size in 1..16 && keys.distinct().size==keys.size && keys.all {identifiers.matches(it) && it.length<=64})
            definition.id to keys.toSet()
        }
        val actualKeys=s.values.groupBy {it.measurementId}.mapValues {(_,rows)->rows.map {it.valueKey}.toSet()}
        s.measurements.forEach {row->
            val definition=requireNotNull(definitions[row.definitionId])
            val allowed=requireNotNull(definitionKeys[definition.id])
            val required=if(definition.kind=="PRESSURE") setOf("systolic","diastolic") else allowed
            val actual=actualKeys[row.id].orEmpty()
            // Permanent deletion keeps a scrubbed tombstone but deliberately removes its values.
            val purged=row.deletedAt!=null && batches[row.deletionBatchId]?.let {it.state=="PURGED" && it.kind=="MEASUREMENT" && it.rootId==row.id}==true
            require(if(purged) actual.isEmpty() else actual.all {it in allowed} && actual.containsAll(required)) {"备份测量值缺失或不匹配"}
        }
        s.entries.forEach {require(it.pregnancyId in pregnancies);batch(it.deletionBatchId)}
        s.customReminders.forEach {require(it.pregnancyId in pregnancies)}
        val objectMetadata=HashMap<String,List<Any>>()
        fun registerObject(objectId: String,plain: Long,cipher: Long,plainHash: String,cipherHash: String) {
            id(objectId);objectInfo(plain,cipher,plainHash,cipherHash)
            val metadata=listOf(plain,cipher,plainHash,cipherHash)
            val previous=objectMetadata.putIfAbsent(objectId,metadata)
            require(previous==null || previous==metadata) {"同一备份对象的校验信息冲突"}
        }
        s.attachments.forEach {row->require((row.visitId==null)!=(row.medicationId==null))
            require(row.visitId==null || row.visitId in visits);require(row.medicationId==null || row.medicationId in drugs)
            registerObject(row.objectId ?: row.id,row.plainSize,row.cipherSize,row.plainSha256,row.cipherSha256)
            if(row.thumbnailId==null)require(listOf(row.thumbnailSize,row.thumbnailCipherSize,row.thumbnailSha256,row.thumbnailCipherSha256).all {it==null})
            else {registerObject(row.thumbnailId,requireNotNull(row.thumbnailSize),requireNotNull(row.thumbnailCipherSize),requireNotNull(row.thumbnailSha256),requireNotNull(row.thumbnailCipherSha256))}
            batch(row.deletionBatchId)}
        s.deletionBatches.forEach {require(it.state in setOf("DELETED","RESTORED","PURGED")) {"清理中的资料暂不能制作快照"}}
    }
    private fun id(value: String) {require(identifiers.matches(value)) {"备份标识无效"}}
    private fun objectInfo(plain: Long,cipher: Long,plainHash: String,cipherHash: String) {
        require(plain in 0..100L*1024*1024 && cipher==plain+32) {"备份对象长度无效"}
        require(hashes.matches(plainHash) && hashes.matches(cipherHash)) {"备份对象校验信息无效"}
    }
}
