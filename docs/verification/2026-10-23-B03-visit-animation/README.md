# B03 新建产检预约进入动画帧级复核

日期：2026-10-23。设备：专用 `PJ_API36_16K`（API36 / 16KiB 页）模拟器；应用 `performance` 变体为 R8 优化、非调试构建，使用调试签名仅供本地测量。测试数据为可清理的合成孕期和预约，不含个人医疗数据。

## 发现与改动

原导航在下级进入时，对正在退出的整页同时应用水平位移与 260ms 的透明度变化；返回与预测返回也对底层整页应用透明度变化。首次进入预约页的 [逐帧数据](cold-entry-framestats.txt) 中，较慢帧的 UI 线程段约 2–8ms，而绘制/GPU 完成段可达约 15–82ms。这个观察提示整页混合可能增加绘制压力，但不能仅凭该字段证明唯一根因。

实验仅去掉**底层页面**的透明度叠加，保留进入页、返回页的水平滑动方向与 320ms 时长，根 Tab 160/120ms 淡入淡出不变。产物：`JournalNavigation.kt`。实验前后均使用同一模拟器、相同 R8 `performance` 变体和 ADB 点击路径，先测冷进程首次进入，再测暖进入/返回。帧统计窗口为点击后约 650ms，包含动画和 UI 空闲帧；百分位数字是 `gfxinfo` 直方图桶，不是精确单帧耗时。

| 场景 | 原动画 | 去底层透明度 |
|---|---:|---:|
| 冷进程首次进入，6 次 | 20/116 帧被标记卡顿；P99 桶范围 150–300ms | 15/147 帧被标记卡顿；P99 桶范围 105–150ms |
| 暖进入，12 次 | 14/288；P99 桶 18–36ms | 15/290；P99 桶 18–36ms |
| 暖返回，12 次 | 13/301；P99 桶 18–48ms | 13/300；P99 桶 19–34ms |

原始样本：[原冷进入](original-cold-entry-gfxinfo.csv)、[实验冷进入](no-alpha-cold-entry-gfxinfo.csv)、[原暖进入](warm-entry-gfxinfo.csv)、[实验暖进入](no-alpha-warm-entry-gfxinfo.csv)、[原暖返回](warm-return-gfxinfo.csv)、[实验暖返回](no-alpha-warm-return-gfxinfo.csv)。逐帧快照另见 [原冷进入](cold-entry-framestats.txt)、[实验冷进入](no-alpha-cold-entry-framestats.txt) 和 [实验暖进入](warm-entry-framestats.txt)。采样顺序未随机化，模拟器负载和 JIT/缓存状态无法完全一致，因此这是工程优化信号，**不证明小米真机卡顿已经消失**。

## 回归与清理

- `:app:assemblePerformance`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:testDebugUnitTest`、`:app:lintDebug` 均构建成功；JVM 340 项/0 失败、lint 0 错误/25 条当前报告项。
- [导航定向回归](navigation-regression.log) `OK (4 tests)`：进入/返回方向、预测返回取消与提交、预约草稿返回、管理页父级返回。
- 测量结束重新安装当前 debug 包；[合成数据核对与清理](fixture-cleanup.log) `OK (1 test)`，测试状态文件和提醒调度键均为空。

小米 14 当前不在线，未完成同机同场景复测；首次进入仍有卡顿帧，B03 性能问题保持待验收。
