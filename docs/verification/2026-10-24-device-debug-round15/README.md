# Bug #5 / Bug #6 修复与真机验证（2026-10-24，round 15）

- 设备：Xiaomi 11 Ultra（M2102K1C / Android 13），无线调试 `<局域网地址>`；应用内截图受 FLAG_SECURE 限制，证据为 uiautomator 语义树 + `run-as` 文件系统检查。
- 应用 APK：2026-10-24 18:26 构建（含修复）。

## Bug #5：彻底删除后 legacy 对象文件未回收（已修复并真机验证）

根因：恢复前资料库为 **legacy 布局**（`vault/journal.db` + `vault/objects`）；从 legacy 布局恢复时 `RestoreWizard.commit` 把 pointer 从 `activeId=null` 切到新代际，`previousId=null`。既有回收逻辑只处理 `previousId` 指向的代际目录，`vault/journal.db`、`vault/journal.db.lck`、`vault/objects/` 从不被清理；此后「彻底删除」只作用于当前代际的对象（`gen/<id>/objects`），legacy 密文原件永久残留。

修复：
1. `VaultLayout` 新增 `legacyLeftovers()` 与 `reclaimLegacyLayout()`：当 pointer 已指向有效代际且存在 legacy 文件时，先把 `journal.db`/`journal.db.lck`/`objects` **重命名进 `vault/legacy.reclaim/`** 并 fsync，再删除 trash；中断后下次调用可续做（与既有代际回收同样的 rename→sync→delete 顺序，active 代际永不被触碰）。
2. `RestoreReclaim.inspect()`：active 代际存在但 `previousId=null` 且有 legacy 残留时返回 `AVAILABLE`（`availableLegacy`），恢复页显示「恢复前的旧资料」面板；`reclaim()` 在回收 previous 代际的同时回收 legacy 残留。
3. 单元测试：`RestoreReclaimTest` 新增 3 例（legacy 残留可展示并回收且 active 代际完好、中断的 legacy 回收可续做、pending restore 仍阻止回收）；整类 **19/19 通过**。

真机验证（本轮）：
- 修复前安装：`vault/objects` 4 个密文文件 + `vault/journal.db` 长期残留；
- 安装修复版后：本地备份页出现「恢复前的旧资料」面板 → 「回收上一代资料」→ 确认回收 → 提示「已回收恢复前的旧资料代际，当前资料不受影响」；
- `run-as` 检查：`objects-gone`、`legacy-db-gone`、`trash-gone`，`vault/` 仅剩 `active-generation.json/gen/backup-work/recovery-slot.enc/restore-*`，active 代际数据库完好；
- 随后创建/彻底删除孕期：`gen/<id>/objects` 为空、回收站为空，证明当前代际的对象回收正常。

证据：`dumps/01-recovery-legacy-panel.xml`、`02-reclaim-confirm.xml`、`03-reclaim-dialog.xml`、`04-reclaimed-message.xml`、`06-trash-empty-after-purge.xml`。

## Bug #6：回收站「删除于」ISO 日期（已修复并真机验证）

- 修复：`TrashScreen.deletionDate()` 由 `LocalDate.toString()` 改为共享 `displayDate()`（同年 `M月d日`、跨年 `yyyy年M月d日`）。
- 真机验证：新建孕期移入回收站后显示 **「删除于 10月24日」**（`dumps/05-trash-date-chinese.xml`）；随后彻底删除、回收站为空。

## 回归

- JVM：**342/342 通过**（含 RestoreReclaimTest 19 例）。
- 全量仪器矩阵（修复后）：**OK (369 tests)，0 失败，EXIT=0**（`_tools/lead-full-matrix-round15.log`）。

