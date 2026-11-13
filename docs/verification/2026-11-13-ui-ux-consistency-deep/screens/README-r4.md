# 第三轮（r4）截图与验证说明

## 可截图的页面

| 文件 | 页面 | 验证的改动 |
|---|---|---|
| `r4-01-recovery-card.png` | 本地加密备份（RecoveryScreen READY 阶段） | 说明文字 + 「导出加密备份」按钮收进 `JournalCard`；安全副本区用 `JournalDivider`/`JournalSectionTitle`/`JournalTextAction`（删除为红色） |

## 无法截图的页面（FLAG_SECURE）

| 页面 | 原因 | 替代验证 |
|---|---|---|
| 安全设置（SecurityScreen） | 该页自身触发安全保护，`-PvisualVerification=true` 不足以解除（`secureFlagRequired` 只覆盖 MainActivity 的窗口标志） | 代码级核对：`JournalCard` 分组结构 + `testTag` 全部保留 |
| 恢复资料（RestoreScreen） | 同上，恢复流程涉及密钥输入 | 代码级核对：7 个 stage 全部卡片化，`restore-key`/`restore-cloud-key`/`restore-progress`/`restore-confirm` 等 `testTag` 保留 |

两页的 `OutlinedTextField` 均已降为 0，JVM 365/0、lint 0 错误/42 警告（无新增）。
