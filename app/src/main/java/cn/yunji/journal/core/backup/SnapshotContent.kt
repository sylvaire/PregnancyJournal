package cn.yunji.journal.core.backup

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Versioned content contract, independent of the current device clock or reminder permissions. */
object SnapshotContent {
    private val decimal=Regex("[0-9]{1,12}(\\.[0-9]{1,6})?")
    private fun text(value: String,max: Int,required: Boolean=false) {require(value.length<=max && '\u0000' !in value && (!required || value.isNotBlank())) {"备份文本无效"}}
    private fun date(value: String?) {if(value!=null){text(value,32,true);LocalDate.parse(value)}}
    private fun time(value: String?) {if(value!=null){text(value,32,true);LocalTime.parse(value)}}
    private fun zone(value: String) {text(value,128,true);ZoneId.of(value)}
    private fun revision(value: Long) {require(value in 1 until Long.MAX_VALUE) {"备份版本号无效"}}
    private fun deleted(value: Long?) {require(value==null || value>=0)}
    private fun dose(value: String?,unit: String?) {
        require((value==null)==(unit==null))
        if(value!=null){require(decimal.matches(value) && value.any {it in '1'..'9'});text(requireNotNull(unit),20,true)}
    }
    fun validate(s: LogicalSnapshot) {
        s.pregnancies.forEach {a->text(a.title,100,true);text(a.hospital,120);text(a.note,10000);date(a.lmpDate);date(a.confirmedEdd)
            require(a.status in setOf("ONGOING","ENDED","ARCHIVED"));revision(a.revision);deleted(a.deletedAt)}
        s.dateRevisions.forEach {a->date(a.lmpDate);date(a.confirmedEdd);require(a.source in setOf("UNKNOWN","LMP","DOCTOR"))}
        s.visits.forEach {a->date(a.scheduledDate);time(a.scheduledTime);zone(a.zoneId);date(a.actualDate);time(a.actualTime);date(a.reminderDate);time(a.reminderTime)
            require((a.reminderDate==null)==(a.reminderTime==null));require(a.status in setOf("SCHEDULED","COMPLETED","CANCELLED"))
            text(a.hospital,120);text(a.actualHospital.orEmpty(),120);listOf(a.items,a.preparation,a.advice,a.actualResult.orEmpty()).forEach {text(it,10000)};revision(a.revision);deleted(a.deletedAt)}
        s.customReminders.forEach {a->
            text(a.title,100,true);text(a.note,1000);date(a.startDate);time(a.localTime);date(a.endDate)
            require(a.repeat in setOf("NONE","DAILY","WEEKLY"))
            require(a.endDate==null || !LocalDate.parse(a.endDate).isBefore(LocalDate.parse(a.startDate)))
            revision(a.revision);deleted(a.deletedAt)
        }
        s.medications.forEach {a->text(a.name,100,true);text(a.specification,120);text(a.dosageForm,40);revision(a.revision);deleted(a.deletedAt)}
        s.plans.forEach {a->text(a.logicalPlanId,160,true);dose(a.dose,a.unit);zone(a.zoneId);date(a.startDate);date(a.endDate)
            require(a.endDate==null || !LocalDate.parse(a.endDate).isBefore(LocalDate.parse(a.startDate)))
            require(a.status in setOf("ACTIVE","PAUSED","ENDED"));require(a.frequencyType in setOf("DAILY_TIMES","WEEKLY_TIMES","ONCE","AS_NEEDED","TEXT_ONLY"))
            text(a.times,155);text(a.weekdays,13);text(a.instructions,10000);text(a.source,240)
            text(a.medicationName,100);text(a.medicationSpecification,120);text(a.medicationDosageForm,40)
            val times=if(a.times.isEmpty())emptyList() else a.times.split(',')
            require(times.size<=12 && times.distinct().size==times.size);times.forEach(::time)
            val scheduled=a.frequencyType in setOf("DAILY_TIMES","WEEKLY_TIMES","ONCE")
            require(!scheduled || times.isNotEmpty());require(a.frequencyType!="ONCE" || times.size==1)
            val weekdays=if(a.weekdays.isEmpty())emptyList() else a.weekdays.split(',')
            require(weekdays.distinct().size==weekdays.size && weekdays.all {it in setOf("1","2","3","4","5","6","7")})
            require(a.frequencyType!="WEEKLY_TIMES" || weekdays.isNotEmpty());revision(a.revision);deleted(a.deletedAt)}
        s.occurrences.forEach {a->date(a.plannedDate);time(a.localTime);zone(a.zoneId);require(a.ordinal in 0..11 && a.state in setOf("ACTIVE","CANCELLED"));revision(a.revision);deleted(a.deletedAt)}
        s.intakes.forEach {a->date(a.eventDate);zone(a.zoneId);dose(a.dose,a.unit);text(a.note,10000)
            require(a.status in setOf("TAKEN","SKIPPED","REMOVED"));require(a.status!="TAKEN" || a.actualAt!=null)
            require(a.status!="REMOVED" || a.deletedAt!=null);revision(a.revision);deleted(a.deletedAt)}
        s.definitions.forEach {a->text(a.logicalId,160,true);text(a.name,40,true);text(a.unit,20,true)
            require(a.kind in setOf("WEIGHT","PRESSURE","GLUCOSE","CUSTOM"));revision(a.revision);deleted(a.deletedAt)}
        s.measurements.forEach {a->zone(a.zoneId);text(a.source,120);text(a.note,10000)
            require(a.scenario in setOf("NONE","FASTING","POST_MEAL","OTHER"))
            require(if(a.scenario=="POST_MEAL")a.postMealMinutes in 1..1440 else a.postMealMinutes==null)
            revision(a.revision);deleted(a.deletedAt)}
        s.entries.forEach {a->date(a.entryDate);time(a.entryTime);zone(a.zoneId);a.offsetSeconds?.let {ZoneOffset.ofTotalSeconds(it)}
            text(a.symptom,1000);text(a.mood,40);text(a.text,10000);revision(a.revision);deleted(a.deletedAt)}
        s.attachments.forEach {a->text(a.name,160,true);require(a.name.none {it=='/' || it=='\\' || it.isISOControl()})
            require(a.mime in setOf("application/pdf","image/jpeg","image/png","image/gif","image/webp","image/heic"))
            require(a.category in setOf("OTHER","ULTRASOUND","LAB","SCREENING","PRESCRIPTION","RECORD","PACKAGE"))
            require(a.medicationId==null || a.category=="PACKAGE" && a.mime.startsWith("image/"))
            date(a.documentDate);text(a.hospital,120);text(a.note,10000);revision(a.revision);deleted(a.deletedAt)}
        s.deletionBatches.forEach {a->text(a.rootId,160,true);require(a.kind in setOf("PREGNANCY","VISIT","MEDICATION","INTAKE","MEASUREMENT","JOURNAL"));deleted(a.deletedAt)}
    }
}
