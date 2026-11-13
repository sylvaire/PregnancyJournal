package cn.yunji.journal.data

import cn.yunji.journal.core.database.*
import kotlinx.serialization.Serializable
import java.time.*
import java.util.UUID

@Serializable
data class JournalEntryForm(val requestId: String = UUID.randomUUID().toString(), val pregnancyId: String = "",
    val id: String? = null, val expectedRevision: Long? = null, val date: String = "", val time: String = "",
    val zoneId: String = ZoneId.systemDefault().id, val offsetSeconds: Int? = null,
    val symptom: String = "", val mood: String = "", val text: String = "") {
    val draftKey get() = id ?: "new:$pregnancyId"
}

class JournalEntryRepository(private val db: JournalDatabase, private val clock: Clock) {
    private val dao = db.journalDao()
    fun timeline(id: String) = dao.timeline(id)
    private suspend fun parent(id: String) { require(dao.pregnancy(id)?.let {it.deletedAt==null}==true) {"请先创建或恢复孕期"} }
    suspend fun form(pregnancyId: String, id: String?): JournalEntryForm {
        parent(pregnancyId)
        val old=id?.let {requireNotNull(dao.journalEntry(it))}
        require(old==null || old.pregnancyId==pregnancyId && old.deletedAt==null)
        return JournalEntryForm(requestId=old?.id ?: UUID.randomUUID().toString(),pregnancyId=pregnancyId,id=id,expectedRevision=old?.revision,
            date=old?.entryDate ?: clock.instant().atZone(ZoneId.systemDefault()).toLocalDate().toString(),time=old?.entryTime.orEmpty(),
            zoneId=old?.zoneId ?: ZoneId.systemDefault().id,offsetSeconds=old?.offsetSeconds,
            symptom=old?.symptom.orEmpty(),mood=old?.mood.orEmpty(),text=old?.text.orEmpty())
    }
    suspend fun save(f: JournalEntryForm): String = db.journalWrite {
        UUID.fromString(f.requestId);parent(f.pregnancyId)
        val old=f.id?.let {requireNotNull(dao.journalEntry(it))}
        if(old==null) dao.journalEntry(f.requestId)?.let {
            require(it.pregnancyId==f.pregnancyId && it.deletedAt==null) {"记录已删除，请重新打开"}
            dao.clearDraft("journalEntry",f.draftKey);return@journalWrite it.id
        }
        require(old==null || old.pregnancyId==f.pregnancyId && old.deletedAt==null && old.revision==f.expectedRevision) {"记录已变化，请重新打开后修改"}
        require(f.symptom.length<=1000 && f.mood.length<=40 && f.text.length<=10_000) {"症状最多1000字、心情40字、备注10000字"}
        require(f.symptom.isNotBlank() || f.mood.isNotBlank() || f.text.isNotBlank()) {"请至少填写症状、心情或日常备注中的一项"}
        val date=runCatching {LocalDate.parse(f.date)}.getOrElse {throw IllegalArgumentException("请选择有效发生日期")}
        val zone=runCatching {ZoneId.of(f.zoneId)}.getOrElse {throw IllegalArgumentException("记录时区无效，请重新打开")}
        require(date<=clock.instant().atZone(zone).toLocalDate()) {"发生日期不能晚于今天"}
        val time=f.time.trim().takeIf {it.isNotEmpty()}?.let {raw->runCatching {LocalTime.parse(raw)}.getOrElse {throw IllegalArgumentException("请输入24小时制时刻，如08:30，或留空")}}
        val offset=time?.let {
            val local=date.atTime(it); val offsets=zone.rules.getValidOffsets(local)
            require(offsets.isNotEmpty()) {"该时区不存在此时刻，请核对或设为时间未记录"}
            val selected=offsets.firstOrNull {it.totalSeconds==f.offsetSeconds} ?: offsets.first()
            require(local.toInstant(selected)<=clock.instant()) {"发生时间不能晚于现在"}
            selected.totalSeconds
        }
        val row=JournalEntryEntity(old?.id ?: f.requestId,f.pregnancyId,date.toString(),time?.toString(),zone.id,offset,f.symptom.trim(),f.mood.trim(),f.text,
            old?.createdAt ?: clock.millis(),clock.millis(),(old?.revision ?: 0)+1)
        if(old==null)dao.insertJournalEntry(row) else dao.updateJournalEntry(row)
        dao.recordChange(ChangeEntity(entityType="journalEntry",entityId=row.id,revision=row.revision,operation="UPSERT",createdAt=clock.millis()))
        dao.clearDraft("journalEntry",f.draftKey);row.id
    }
}
