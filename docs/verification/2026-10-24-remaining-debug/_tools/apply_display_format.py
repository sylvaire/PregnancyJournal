"""One-shot mechanical replacements for task-13 P1-7 (display date/time unification).

Each entry must match exactly once; the script aborts without writing when any entry is missing/ambiguous.
"""
import io
import os
import sys

ROOT = r"D:\Devtools\Code\CodexWorkspace\PregnancyJournal"
FEATURE = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'cn', 'yunji', 'journal', 'feature')
TESTS = os.path.join(ROOT, 'app', 'src', 'androidTest', 'java', 'cn', 'yunji', 'journal')

REPLACEMENTS = [
    # ---------------- JournalApp.kt ----------------
    ('JournalApp.kt',
     'else RecordRow("${next.scheduledDate} · ${next.items.ifBlank { "产检预约" }}",',
     'else RecordRow("${displayDate(next.scheduledDate)} · ${next.items.ifBlank { "产检预约" }}",'),
    ('JournalApp.kt',
     'Text("${visit.scheduledDate} · ${visit.scheduledTime ?: "时间待定"}")',
     'Text(displayDateTime(visit.scheduledDate, visit.scheduledTime) + if (visit.scheduledTime == null) " · 时间待定" else "")'),
    ('JournalApp.kt',
     'if (visit.actualDate != null) Text("实际就诊 ${visit.actualDate}", style = MaterialTheme.typography.bodySmall)',
     'if (visit.actualDate != null) Text("实际就诊 ${displayDate(visit.actualDate!!)}", style = MaterialTheme.typography.bodySmall)'),
    ('JournalApp.kt',
     'val reminder = if (visit.reminderDate != null && visit.reminderTime != null) "${visit.reminderDate} ${visit.reminderTime}" else "未设置"',
     'val reminder = if (visit.reminderDate != null && visit.reminderTime != null) displayDateTime(visit.reminderDate!!, visit.reminderTime) else "未设置"'),
    # ---------------- PrenatalScreens.kt ----------------
    ('PrenatalScreens.kt',
     'RecordRow("${v.scheduledDate} · ${v.items.ifBlank { "产检预约" }}", "${v.scheduledTime ?: "时间待定"} · ${visitStatus(v)}", "122c6.svg") { onSelect(v.id) }',
     'RecordRow("${displayDate(v.scheduledDate)} · ${v.items.ifBlank { "产检预约" }}", "${v.scheduledTime ?: "时间待定"} · ${visitStatus(v)}", "122c6.svg") { onSelect(v.id) }'),
    ('PrenatalScreens.kt',
     'Text("末次月经：${revision.lmpDate ?: "未填写"}")',
     'Text("末次月经：${revision.lmpDate?.let(::displayDate) ?: "未填写"}")'),
    ('PrenatalScreens.kt',
     'Text(Instant.ofEpochMilli(revision.effectiveAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)',
     'Text(displayInstant(revision.effectiveAt, ZoneId.systemDefault().id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)'),
    # ---------------- IntakeScreens.kt ----------------
    ('IntakeScreens.kt',
     'Text("计划 ${point.plannedDate} ${point.localTime} · 第${plan?.version ?: ""}版")',
     'Text("计划 ${displayDateTime(point.plannedDate, point.localTime)} · 第${plan?.version ?: ""}版")'),
    ('IntakeScreens.kt',
     'Text("${record.eventDate} · 第${plan?.version ?: ""}版 · ${if (record.sourceOccurrenceId == null) "补记" else "计划记录"}", style = MaterialTheme.typography.bodySmall)',
     'Text("${displayDate(record.eventDate)} · 第${plan?.version ?: ""}版 · ${if (record.sourceOccurrenceId == null) "补记" else "计划记录"}", style = MaterialTheme.typography.bodySmall)'),
    ('IntakeScreens.kt',
     'if (point != null) Text("原计划 ${point.plannedDate} ${point.localTime}", style = MaterialTheme.typography.bodySmall)',
     'if (point != null) Text("原计划 ${displayDateTime(point.plannedDate, point.localTime)}", style = MaterialTheme.typography.bodySmall)'),
    ('IntakeScreens.kt',
     'if (record.actualAt != null) Text("实际 ${Instant.ofEpochMilli(record.actualAt).atZone(ZoneId.of(record.zoneId)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")',
     'if (record.actualAt != null) Text("实际 ${displayInstant(record.actualAt, record.zoneId)}")'),
    ('IntakeScreens.kt',
     'if (form.plannedDate != null) Text("原计划 ${form.plannedDate} ${form.plannedTime} · ${form.plannedZoneId}")',
     'if (form.plannedDate != null) Text("原计划 ${displayDateTime(form.plannedDate!!, form.plannedTime)} · ${form.plannedZoneId}")'),
    # ---------------- MedicationScreens.kt ----------------
    ('MedicationScreens.kt',
     'Text("记录生效于 ${Instant.ofEpochMilli(plan.effectiveAt).atZone(ZoneId.of(plan.zoneId)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"))}", style = MaterialTheme.typography.bodySmall)',
     'Text("记录生效于 ${displayInstant(plan.effectiveAt, plan.zoneId)}", style = MaterialTheme.typography.bodySmall)'),
    ('MedicationScreens.kt',
     'Text("${plan.startDate} 至 ${plan.endDate ?: "未设结束日期"}", style = MaterialTheme.typography.bodySmall)',
     'Text("${displayDate(plan.startDate)} 至 ${plan.endDate?.let(::displayDate) ?: "未设结束日期"}", style = MaterialTheme.typography.bodySmall)'),
    # ---------------- DocumentScreen.kt ----------------
    ('DocumentScreen.kt',
     'if(attachment?.documentDate!=null)Text("报告日期：${attachment.documentDate}",style=MaterialTheme.typography.bodySmall)',
     'if(attachment?.documentDate!=null)Text("报告日期：${displayDate(attachment.documentDate!!)}",style=MaterialTheme.typography.bodySmall)'),
    ('DocumentScreen.kt',
     'Text("${target.actualDate ?: target.scheduledDate} · ${target.items.ifBlank { target.hospital.ifBlank { "产检记录" } }}")',
     'Text("${displayDate(target.actualDate ?: target.scheduledDate)} · ${target.items.ifBlank { target.hospital.ifBlank { "产检记录" } }}")'),
    # ---------------- ReminderSettingsScreen.kt ----------------
    ('ReminderSettingsScreen.kt',
     'Text("${row.startDate} ${row.localTime} · $repeatText", style = MaterialTheme.typography.bodySmall)',
     'Text("${displayDateTime(row.startDate, row.localTime)} · $repeatText", style = MaterialTheme.typography.bodySmall)'),
    ('ReminderSettingsScreen.kt',
     'TextButton(onClick={focusManager.clearFocus();pickDate(date){date=it}}) {Text("提醒日期 $date")}',
     'TextButton(onClick={focusManager.clearFocus();pickDate(date){date=it}}) {Text("提醒日期 ${displayDate(date)}")}'),
    ('ReminderSettingsScreen.kt',
     'TextButton(onClick={focusManager.clearFocus();pickDate(end){end=it}}){Text(if(end==null)"设置结束日期" else "结束 $end")}',
     'TextButton(onClick={focusManager.clearFocus();pickDate(end){end=it}}){Text(if(end==null)"设置结束日期" else "结束 ${end?.let(::displayDate)}")}'),
    # ---------------- TimelineScreens.kt ----------------
    ('TimelineScreens.kt',
     'Text("${event.date} · ${event.time ?: "时间未记录"}")',
     'Text("${displayDate(event.date)} · ${event.time ?: "时间未记录"}")'),
    ('TimelineScreens.kt',
     'Text("报告日期：${doc.documentDate ?: "未填写"}")',
     'Text("报告日期：${doc.documentDate?.let(::displayDate) ?: "未填写"}")'),
    ('TimelineScreens.kt',
     '    val label=runCatching {LocalDate.parse(date).format(DateTimeFormatter.ofPattern("M月d日"))}.getOrDefault(date)\n',
     '    val label=displayDate(date)\n'),
    ('TimelineScreens.kt',
     'private fun displayInstant(at: Long,zoneId: String)=Instant.ofEpochMilli(at).atZone(ZoneId.of(zoneId)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"))',
     ''),
    # ---------------- CloudConnectionScreen.kt ----------------
    ('CloudConnectionScreen.kt',
     'private fun formatStatusTime(value: Long): String =\n    java.time.Instant.ofEpochMilli(value).atZone(java.time.ZoneId.systemDefault())\n        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))',
     'private fun formatStatusTime(value: Long): String = displayInstant(value, java.time.ZoneId.systemDefault().id)'),
    # ---------------- CloudCleanupController.kt ----------------
    ('CloudCleanupController.kt',
     'internal fun formatCleanupTime(value: Long, zone: ZoneId = ZoneId.systemDefault()): String =\n    Instant.ofEpochMilli(value).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))',
     'internal fun formatCleanupTime(value: Long, zone: ZoneId = ZoneId.systemDefault()): String = displayInstant(value, zone.id)'),
    # ---------------- RestoreScreen.kt ----------------
    ('RestoreScreen.kt',
     'val date=Instant.ofEpochMilli(snapshot.createdAt).atZone(ZoneId.systemDefault())\n                        .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))',
     'val date=displayInstant(snapshot.createdAt, ZoneId.systemDefault().id)'),
    ('RestoreScreen.kt',
     'val date=Instant.ofEpochMilli(preview.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))',
     'val date=displayInstant(preview.createdAt, ZoneId.systemDefault().id)'),
    # ---------------- MeasurementScreens.kt ----------------
    ('MeasurementScreens.kt',
     'private fun measuredLabel(row: MeasurementEntity) = Instant.ofEpochMilli(row.measuredAt).atZone(ZoneId.of(row.zoneId)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"))',
     'private fun measuredLabel(row: MeasurementEntity) = displayInstant(row.measuredAt, row.zoneId)'),
    ('MeasurementScreens.kt',
     'Text("录入时间：${Instant.ofEpochMilli(row.recordedAt).atZone(ZoneId.of(row.zoneId)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"))}")',
     'Text("录入时间：${displayInstant(row.recordedAt, row.zoneId)}")'),
    # ---------------- NavigationExperienceTest.kt (display assertion format) ----------------
    ('NavigationExperienceTest.kt',
     'ui.onNodeWithText("${row.entryDate} · 时间未记录").assertExists()',
     'ui.onNodeWithText("${cn.yunji.journal.feature.displayDate(row.entryDate)} · 时间未记录").assertExists()'),
]


def main():
    payload = {}
    errors = []
    for name, old, new in REPLACEMENTS:
        path = os.path.join(TESTS if name.endswith('Test.kt') else FEATURE, name)
        if path not in payload:
            with io.open(path, 'r', encoding='utf-8', newline='') as f:
                payload[path] = f.read()
        content = payload[path]
        count = content.count(old)
        if count != 1:
            errors.append('%s: expected 1 match, found %d for %r' % (name, count, old[:80]))
            continue
        payload[path] = content.replace(old, new, 1)
    if errors:
        print('ABORT (no files written):')
        for e in errors:
            print(' -', e)
        return 1
    for path, content in payload.items():
        with io.open(path, 'w', encoding='utf-8', newline='') as f:
            f.write(content)
        print('patched', os.path.basename(path))
    return 0


if __name__ == '__main__':
    sys.exit(main())
