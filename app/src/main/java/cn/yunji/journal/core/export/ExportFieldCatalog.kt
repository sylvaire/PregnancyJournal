package cn.yunji.journal.core.export

/** Human-readable description of one exported field. */
data class ExportField(val key: String, val label: String, val type: String, val note: String = "")

/**
 * Field dictionary for the structured export, matching the business entities field by field.
 *
 * Two rules are deliberate:
 * - the dictionary documents what is *not* exported, so a reader cannot mistake the package for a full dump;
 * - encrypted-storage columns that only make sense inside this app (cipher sizes, object pointers) are kept,
 *   because the source index needs them to link rows to files, but they are marked as internal.
 */
object ExportFieldCatalog {
    const val INTERNAL = "内部索引"

    private fun f(key: String, label: String, type: String, note: String = "") = ExportField(key, label, type, note)

    val sections: Map<String, List<ExportField>> = linkedMapOf(
        "pregnancies" to listOf(
            f("id", "档案标识", "文本"), f("title", "名称", "文本"), f("lmpDate", "末次月经日期", "日期"),
            f("confirmedEdd", "预产期", "日期"), f("hospital", "建档医院", "文本"), f("note", "备注", "文本"),
            f("status", "状态", "枚举"), f("createdAt", "创建时间", "时间戳"), f("updatedAt", "更新时间", "时间戳"),
            f("revision", "修订号", "整数"), f("deletedAt", "删除时间", "时间戳"), f("deletionBatchId", "删除批次", "文本")),
        "date_revisions" to listOf(
            f("id", "记录标识", "文本"), f("pregnancyId", "档案标识", "文本"), f("lmpDate", "末次月经日期", "日期"),
            f("confirmedEdd", "预产期", "日期"), f("source", "日期依据", "枚举"), f("effectiveAt", "生效时间", "时间戳")),
        "visits" to listOf(
            f("id", "记录标识", "文本"), f("pregnancyId", "档案标识", "文本"), f("scheduledDate", "预约日期", "日期"),
            f("scheduledTime", "预约时间", "时间"), f("zoneId", "时区", "文本"), f("hospital", "医院", "文本"),
            f("items", "检查项目", "文本"), f("preparation", "准备事项", "文本"), f("advice", "医生建议", "文本"),
            f("actualDate", "实际就诊日期", "日期"), f("actualTime", "实际就诊时间", "时间"),
            f("actualHospital", "实际就诊医院", "文本"), f("actualResult", "检查结果", "文本"),
            f("gestationalDays", "孕周天数", "整数"),
            f("dateRevisionId", "日期依据标识", "文本"), f("status", "状态", "枚举"),
            f("createdAt", "创建时间", "时间戳"), f("updatedAt", "更新时间", "时间戳"), f("revision", "修订号", "整数"),
            f("deletedAt", "删除时间", "时间戳"), f("deletionBatchId", "删除批次", "文本"),
            f("coverAttachmentId", "封面资料标识", "文本"), f("reminderDate", "提醒日期", "日期"), f("reminderTime", "提醒时间", "时间")),
        "attachments" to listOf(
            f("id", "资料标识", "文本"), f("visitId", "所属产检", "文本"), f("medicationId", "所属药品", "文本"),
            f("name", "名称", "文本"), f("mime", "类型", "文本"), f("category", "分类", "枚举"),
            f("documentDate", "报告日期", "日期"), f("hospital", "医院", "文本"), f("note", "备注", "文本"),
            f("plainSize", "原件大小", "字节"), f("plainSha256", "原件校验值", "文本"),
            f("createdAt", "导入时间", "时间戳"), f("deletedAt", "删除时间", "时间戳"),
            f("deletionBatchId", "删除批次", "文本"), f("revision", "修订号", "整数"),
            f("cipherSize", "密文长度", "字节", INTERNAL), f("cipherSha256", "密文校验值", "文本", INTERNAL),
            f("objectId", "原件对象标识", "文本", INTERNAL), f("thumbnailId", "缩略图对象标识", "文本", INTERNAL),
            f("thumbnailSize", "缩略图大小", "字节", INTERNAL), f("thumbnailCipherSize", "缩略图密文长度", "字节", INTERNAL),
            f("thumbnailSha256", "缩略图校验值", "文本", INTERNAL), f("thumbnailCipherSha256", "缩略图密文校验值", "文本", INTERNAL)),
        "deletion_batches" to listOf(
            f("id", "批次标识", "文本"), f("kind", "类型", "枚举"), f("rootId", "根记录标识", "文本"),
            f("deletedAt", "删除时间", "时间戳"), f("state", "状态", "枚举")),
        "medications" to listOf(
            f("id", "药品标识", "文本"), f("pregnancyId", "档案标识", "文本"), f("name", "药品名称", "文本"),
            f("specification", "规格", "文本"), f("dosageForm", "剂型", "文本"), f("createdAt", "创建时间", "时间戳"),
            f("deletedAt", "删除时间", "时间戳"), f("deletionBatchId", "删除批次", "文本"), f("revision", "修订号", "整数")),
        "medication_plans" to listOf(
            f("id", "方案版本标识", "文本"), f("pregnancyId", "档案标识", "文本"), f("medicationId", "药品标识", "文本"),
            f("logicalPlanId", "方案逻辑标识", "文本"), f("version", "版本", "整数"), f("dose", "剂量", "文本"),
            f("unit", "单位", "文本"), f("frequencyType", "频次类型", "枚举"), f("times", "服用时间", "文本"),
            f("weekdays", "星期", "文本"), f("zoneId", "时区", "文本"), f("startDate", "开始日期", "日期"),
            f("endDate", "结束日期", "日期"), f("status", "状态", "枚举"), f("instructions", "用法说明", "文本"),
            f("source", "方案来源", "文本"), f("effectiveAt", "生效时间", "时间戳"), f("deletedAt", "删除时间", "时间戳"),
            f("deletionBatchId", "删除批次", "文本"), f("revision", "修订号", "整数"),
            f("medicationName", "当时药品名称", "文本"), f("medicationSpecification", "当时规格", "文本"),
            f("medicationDosageForm", "当时剂型", "文本")),
        "medication_occurrences" to listOf(
            f("id", "服药点标识", "文本"), f("pregnancyId", "档案标识", "文本"), f("planVersionId", "方案版本标识", "文本"),
            f("plannedDate", "计划日期", "日期"), f("localTime", "计划时间", "时间"), f("ordinal", "次序", "整数"),
            f("scheduledAt", "计划时刻", "时间戳"), f("zoneId", "时区", "文本"), f("state", "状态", "枚举"),
            f("createdAt", "创建时间", "时间戳"), f("deletedAt", "删除时间", "时间戳"),
            f("deletionBatchId", "删除批次", "文本"), f("revision", "修订号", "整数")),
        "medication_intakes" to listOf(
            f("id", "记录标识", "文本"), f("pregnancyId", "档案标识", "文本"), f("planVersionId", "方案版本标识", "文本"),
            f("occurrenceId", "对应服药点", "文本"), f("sourceOccurrenceId", "来源服药点", "文本"),
            f("status", "状态", "枚举"), f("actualAt", "实际时刻", "时间戳"), f("eventDate", "事件日期", "日期"),
            f("zoneId", "时区", "文本"), f("dose", "剂量", "文本"), f("unit", "单位", "文本"),
            f("note", "备注", "文本"), f("recordedAt", "记录时间", "时间戳"), f("updatedAt", "更新时间", "时间戳"),
            f("revision", "修订号", "整数"), f("deletedAt", "删除时间", "时间戳"), f("deletionBatchId", "删除批次", "文本")),
        "metric_definitions" to listOf(
            f("id", "指标版本标识", "文本"), f("pregnancyId", "档案标识", "文本"), f("logicalId", "指标逻辑标识", "文本"),
            f("version", "版本", "整数"), f("kind", "类型", "枚举"), f("name", "名称", "文本"),
            f("unit", "单位", "文本"), f("valueKeys", "数值字段", "文本"), f("createdAt", "创建时间", "时间戳"),
            f("deletedAt", "删除时间", "时间戳"), f("deletionBatchId", "删除批次", "文本"), f("revision", "修订号", "整数")),
        "measurements" to listOf(
            f("id", "测量标识", "文本"), f("pregnancyId", "档案标识", "文本"), f("definitionId", "指标版本标识", "文本"),
            f("measuredAt", "测量时刻", "时间戳"), f("zoneId", "时区", "文本"), f("scenario", "场景", "枚举"),
            f("postMealMinutes", "餐后分钟", "整数"), f("source", "来源", "枚举"), f("note", "备注", "文本"),
            f("recordedAt", "记录时间", "时间戳"), f("updatedAt", "更新时间", "时间戳"), f("revision", "修订号", "整数"),
            f("deletedAt", "删除时间", "时间戳"), f("deletionBatchId", "删除批次", "文本")),
        "measurement_values" to listOf(
            f("measurementId", "测量标识", "文本"), f("valueKey", "数值字段", "文本"), f("decimalValue", "数值", "小数")),
        "journal_entries" to listOf(
            f("id", "记录标识", "文本"), f("pregnancyId", "档案标识", "文本"), f("entryDate", "日期", "日期"),
            f("entryTime", "时间", "时间"), f("zoneId", "时区", "文本"), f("offsetSeconds", "时区偏移秒", "整数"),
            f("symptom", "症状", "文本"), f("mood", "心情", "文本"), f("text", "正文", "文本"),
            f("createdAt", "创建时间", "时间戳"), f("updatedAt", "更新时间", "时间戳"), f("revision", "修订号", "整数"),
            f("deletedAt", "删除时间", "时间戳"), f("deletionBatchId", "删除批次", "文本")),
        "custom_reminders" to listOf(
            f("id", "提醒标识", "文本"), f("pregnancyId", "档案标识", "文本"), f("title", "标题", "文本"),
            f("note", "备注", "文本"), f("startDate", "开始日期", "日期"), f("localTime", "时间", "时间"),
            f("repeat", "重复规则", "枚举"), f("endDate", "结束日期", "日期"), f("enabled", "是否启用", "布尔"),
            f("createdAt", "创建时间", "时间戳"), f("updatedAt", "更新时间", "时间戳"),
            f("revision", "修订号", "整数"), f("deletedAt", "删除时间", "时间戳")),
    )

    /** Explicitly excluded so the package is never mistaken for a complete copy of the device state. */
    val exclusions: List<String> = listOf(
        "未提交的编辑草稿",
        "导入来源 URI 与导入任务错误详情",
        "云盘地址、账号与应用密码",
        "恢复密钥、恢复封装与设备密钥封装",
        "应用锁与通知等本机设置",
        "提醒调度标识与本机通知状态",
    )

    /** Field keys of one section in export order; unknown sections yield an empty list. */
    fun keys(section: String): List<String> = sections[section].orEmpty().map { it.key }
}
