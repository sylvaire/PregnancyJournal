# 同类内容卡片优化验证

日期：2026-11-15。关联任务：M6-CONTENT-CARDS。

## 实施范围

| 内容 | 调整 |
|---|---|
| 用药方案及历史 | 药名与状态/版本左右分列，用量与日期按标签/数值对齐 |
| 健康测量 | 指标名称在左、原始读数在右，保留时间、场景与操作 |
| 产检记录 | 项目与时间/状态分列，保留整卡点击入口，释放通用图标占用的宽度 |
| 自定义提醒 | 名称与重复规则分列，提醒时间独立为键值行，保留编辑与删除 |

共用 `JournalContentHeading` 支持左右内容自然换行，不裁剪名称和数值。资料卡已有缩略图与独立操作区，保留现有结构。未变更数据库、计算、原值或业务回调。

## 构建与操作验证

- 最终产品构建：默认安全 debug APK、测试 APK、lint 成功，1m24s；lint 0 错误、31 警告，见 [最终构建](content-cards-final-build.log)。
- 普通字号首轮 9 项，8 通过、1 失败：[原始日志](content-cards-normal.txt)。失败为方案编辑第二次保存时键盘同步；改用该测试类既有保存助手，完整方案编辑及历史旧剂量流程 [1/1 通过](content-cards-plan-retry.txt)，23.51s，业务保存逻辑未变。
- 200% 字体、深色、横屏矩阵 [17/17 通过](content-cards-extreme.txt)，136.775s，覆盖页面入口、表单和方案历史。
- 随后仅细调提醒卡片，将日期与重复规则拆为两层，避免“每天”拆行。定向大字横屏 [2/2 通过](content-cards-final-reminder-extreme.txt)。
- 最终普通提醒复验首轮 4 项中 1 失败：[原始日志](content-cards-final-reminder.txt)。保存数据及 revision 断言已通过，失败发生在等待“提醒已保存”提示消失；测试显式推进 Compose 时钟后，[4/4 通过](content-cards-reminder-retry.txt)，41.349s，含增改删与日期/重复规则保存。未修改提示时长或产品逻辑。
- 本轮未重跑全量 JVM 或全量仪器测试，不将历史全量结果计入本轮。

## 视觉证据

普通截图均来自隔离的合成数据，已逐张检查：

- [用药方案](normal/medications.png)、[历史版本](normal/history.png)
- [健康测量](normal/measurement.png)、[产检记录](normal/visits.png)
- [最终提醒卡片](normal/reminder.png)

`extreme/medications.png`、`history.png`、`visits.png` 为初次横屏页面顶部截图，部分内容卡片位于下方，不能单凭这些截图认定卡片内容视觉通过。补充滚动定位截图与复验结果见后续验收记录。

最终补充滚动定位复验 [3/3 通过](content-cards-focused-extreme.txt)，18.142s。以下截图已打开检查，200% 字体、深色、横屏下文字左右分布清晰，卡片超出视口时可继续滚动：

- [产检卡片](extreme/02-visits-card.png)
- [方案标题与状态](extreme/05-medications-card.png)、[方案用量](extreme/05-medications-values.png)
- [最终提醒卡片](extreme/reminder-card.png)

模拟器已恢复100%字体、竖屏与自动旋转；深色主题保持原设定。

## 手机与边界

最终默认安全 APK 已通过 USB 保留资料覆盖更新手机并成功启动。未在手机新建、编辑或删除真实记录；本轮流程和截图验收使用模拟器合成资料，不等同于全部手机页面验收。不进行用户已取消的读屏或无障碍调试。
