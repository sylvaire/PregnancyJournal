package cn.yunji.journal.domain

import cn.yunji.journal.core.database.*
import java.time.*

data class TimelineEvent(val type: String, val id: String, val date: String, val time: String?, val zoneId: String?,
    val title: String, val summary: String, val basis: String, val instant: Long? = null, val parentId: String? = null) {
    val key get() = "$type:$id"
}

object TimelineRules {
    fun build(s: TimelineSnapshot, displayZone: ZoneId): List<TimelineEvent> {
        if(s.pregnancy.deletedAt!=null) return emptyList()
        val pregnancyId=s.pregnancy.id
        val events=mutableListOf<TimelineEvent>()
        fun timed(type: String,id: String,at: Long,zoneId: String,title: String,summary: String,basis: String,parentId: String?=null) {
            val date=Instant.ofEpochMilli(at).atZone(ZoneId.of(zoneId))
            events+=TimelineEvent(type,id,date.toLocalDate().toString(),date.toLocalTime().withNano(0).toString(),zoneId,title,summary,basis,at,parentId)
        }
        s.visits.filter {it.visit.pregnancyId==pregnancyId}.forEach { source ->
            val v=source.visit
            if(v.deletedAt==null) {
                val completed=v.status=="COMPLETED"
                val actual=if(completed) v.actualDate else null
                val date=actual ?: v.scheduledDate
                val time=if(completed) null else v.scheduledTime
                events+=TimelineEvent("VISIT",v.id,date,time,if(time!=null)v.zoneId else null,
                    when(v.status){"COMPLETED"->"已就诊";"CANCELLED"->"已取消预约";else->"产检预约"},
                    listOf(v.items,v.hospital).filter {it.isNotBlank()}.joinToString(" · "),
                    if(completed && actual!=null) "实际就诊日期" else if(completed) "预约日期，实际日期未填写" else "预约日期",
                    time?.let {LocalDate.parse(date).atTime(LocalTime.parse(it)).atZone(ZoneId.of(v.zoneId)).toInstant().toEpochMilli()})
            }
            source.documents.filter {it.deletedAt==null && source.jobs.any {job->job.id==it.id && job.state=="READY"}}.forEach {doc ->
                val summary=listOf(doc.hospital,doc.note.take(240),if(v.deletedAt!=null) "原产检已删除，资料保留" else "").filter {it.isNotBlank()}.joinToString(" · ")
                if(doc.documentDate!=null) events+=TimelineEvent("DOCUMENT",doc.id,doc.documentDate,null,null,doc.name,summary,"报告日期",parentId=v.id)
                else timed("DOCUMENT",doc.id,doc.createdAt,displayZone.id,doc.name,summary,"导入日期，报告日期未填写",v.id)
            }
        }
        val drugs=s.drugs.filter {it.pregnancyId==pregnancyId && it.deletedAt==null}.associateBy {it.id}
        s.medicationFiles.filter {it.medication.pregnancyId==pregnancyId && it.medication.deletedAt==null}.forEach {source ->
            source.documents.filter {it.deletedAt==null && source.jobs.any {job->job.id==it.id && job.state=="READY"}}.forEach {doc ->
                timed("DOCUMENT",doc.id,doc.createdAt,displayZone.id,doc.name,"${source.medication.name} · 药品包装","包装照片导入时间",source.medication.id)
            }
        }
        val plans=s.plans.filter {it.pregnancyId==pregnancyId && it.deletedAt==null}.associateBy {it.id}
        plans.values.forEach {plan ->
            timed("PLAN",plan.id,plan.effectiveAt,plan.zoneId,"${plan.medicationName.ifBlank {drugs[plan.medicationId]?.name ?: "用药"}} · 第${plan.version}版",
                when(plan.status){"PAUSED"->"方案记录为暂停";"ENDED"->"方案记录为结束";else->"方案记录生效"},"方案版本生效时刻",plan.logicalPlanId)
        }
        s.intakes.filter {it.pregnancyId==pregnancyId && it.deletedAt==null}.forEach {row ->
            val plan=plans[row.planVersionId] ?: return@forEach
            val drug=plan.medicationName.ifBlank {drugs[plan.medicationId]?.name ?: "服药"}
            if(row.status=="TAKEN" && row.actualAt!=null) timed("INTAKE",row.id,row.actualAt,row.zoneId,"$drug · 已服用",
                listOf(if(row.dose==null)"实际剂量未填写" else "实际用量 ${row.dose} ${row.unit.orEmpty()}","原方案第${plan.version}版",row.note.take(240)).filter {it.isNotBlank()}.joinToString(" · "),"实际服药时间")
            else if(row.status=="SKIPPED") events+=TimelineEvent("INTAKE",row.id,row.eventDate,null,null,"$drug · 已跳过",listOf("原方案第${plan.version}版",row.note.take(240)).filter {it.isNotBlank()}.joinToString(" · "),"原计划日期，非实际服药时刻")
        }
        val defs=s.definitions.filter {it.pregnancyId==pregnancyId && it.deletedAt==null}.associateBy {it.id}
        s.measurements.filter {it.measurement.pregnancyId==pregnancyId && it.measurement.deletedAt==null}.forEach {source ->
            val row=source.measurement; val def=defs[row.definitionId] ?: return@forEach
            val context=when(row.scenario){"FASTING"->"空腹";"POST_MEAL"->"餐后${row.postMealMinutes}分钟";"OTHER"->"其他场景";else->""}
            timed("MEASUREMENT",row.id,row.measuredAt,row.zoneId,def.name,
                listOf(source.values.joinToString(" · "){"${when(it.valueKey){"systolic"->"收缩压 ";"diastolic"->"舒张压 ";"pulse"->"心率 ";else->""}}${it.decimalValue} ${if(it.valueKey=="pulse")"次/分" else def.unit}"},context).filter {it.isNotBlank()}.joinToString(" · "),"实际测量时间")
        }
        s.entries.filter {it.pregnancyId==pregnancyId && it.deletedAt==null}.forEach {row ->
            val at=row.entryTime?.let {LocalDate.parse(row.entryDate).atTime(LocalTime.parse(it)).toInstant(ZoneOffset.ofTotalSeconds(requireNotNull(row.offsetSeconds))).toEpochMilli()}
            events+=TimelineEvent("JOURNAL",row.id,row.entryDate,row.entryTime,row.zoneId,
                row.text.lineSequence().firstOrNull {it.isNotBlank()}?.take(60) ?: row.symptom.takeIf {it.isNotBlank()}?.take(60) ?: "心情记录",
                listOf(row.symptom.take(160),row.mood).filter {it.isNotBlank()}.joinToString(" · "),"记录的发生日期",at)
        }
        return events.sortedWith(compareByDescending<TimelineEvent>{it.date}.thenBy {it.time==null}.thenByDescending {it.instant}.thenBy {it.key})
    }
}
