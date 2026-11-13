package cn.yunji.journal.feature

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * P1-7：展示层日期/时间格式统一（表单输入/选择器/导出/备份等数据层仍使用 ISO 字符串，不在本文件处理）。
 *
 * - [displayDate]：同年 `M月d日`，跨年 `yyyy年M月d日`，解析失败原样返回；
 * - [displayDateTime]：有 time → `M月d日 HH:mm`（跨年带年份），无 time → 仅日期（调用方保留「时间未记录/时间待定」等语义文案）；
 * - [displayInstant]：epoch 毫秒 + 时区 → `M月d日 HH:mm`（同年）/ `yyyy年M月d日 HH:mm`（跨年）。
 */
private val dateSameYear = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val dateOtherYear = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
private val dateTimeSameYear = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
private val dateTimeOtherYear = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)

internal fun parseDisplayDate(iso: String): LocalDate? =
    runCatching { LocalDate.parse(iso.trim()) }.getOrNull()

/** 展示层日期：`2026-10-17` → `10月17日`；跨年 → `2025年10月17日`；无法解析时原样返回。 */
fun displayDate(iso: String): String {
    val date = parseDisplayDate(iso) ?: return iso
    return date.format(if (date.year == LocalDate.now().year) dateSameYear else dateOtherYear)
}

/** 展示层日期+时间：`10月17日 09:30`；time 为空时只显示日期（调用方自行追加「时间未记录/时间待定」）。 */
fun displayDateTime(isoDate: String, time: String?): String {
    val date = displayDate(isoDate)
    val clock = time?.trim().orEmpty()
    return if (clock.isEmpty()) date else "$date $clock"
}

/** 展示层时间戳：epoch 毫秒 + 时区 → `10月17日 09:30`（同年）或 `2025年10月17日 09:30`（跨年）。 */
fun displayInstant(epochMillis: Long, zoneId: String): String {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    val value = Instant.ofEpochMilli(epochMillis).atZone(zone)
    return value.format(if (value.year == LocalDate.now().year) dateTimeSameYear else dateTimeOtherYear)
}
