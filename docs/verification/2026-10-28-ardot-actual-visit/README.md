# N16 实际就诊记录对齐续作

- 日期：2026-10-28；阶段：M6；设计依据：Ardot 文件 `737849992357132` 的 N16 画板，本地设计截图 `../2026-10-25-ardot-visual/screens/N16.png`。本次未修改 Ardot 文件。
- 实现：实际就诊日期后增加独立的就诊时间、医院、检查结果字段，并保留备注及关联预约。详情页展示实际字段，编辑原预约不会覆盖它们。字段允许留空，避免把示例医疗结果当作必填事实。
- 数据与兼容：`VisitEntity` 新增三个可空字段，数据库 schema 14→15 自动迁移；旧预约数据保持原值。备份内容校验、结构化导出、摘要生成和回收站彻底删除同步处理新增字段。
- 产物：`app/src/main/java/cn/yunji/journal/feature/PrenatalScreens.kt`、`feature/JournalApp.kt`、`feature/JournalViewModel.kt`、`data/JournalRepository.kt`、`core/database/Entities.kt`、`core/database/JournalDatabase.kt`、`app/schemas/cn.yunji.journal.core.database.JournalDatabase/15.json`；[最终模拟器截图](screens/actual-visit-editor.png)。
- 验证：`assembleDebug`、`testDebugUnitTest`、`assembleDebugAndroidTest` 成功；API36 模拟器旧 schema 1–14 升级、新字段与预约分开保存、界面填写→保存→详情、最终截图各 **1/1** 通过。备份字段校验与导出映射的单元测试通过。最终截图已查看，字段与底部操作未遮挡。
- 验证边界：旧 schema 升级使用合成数据；未用小米 11 Ultra 的真实旧库升级，也未实测用户真实备份的恢复。小屏、放大字体、深色、键盘遮挡及真机视觉待验收；不能据此标记 N16 全状态完成。
- 下一步：继续矩阵所列 N22 多资料/失败态、N21 PDF 首页/横屏和 N12 多条/清理中；随后核对 N06、N09–N11、N17/N18 的剩余状态。
