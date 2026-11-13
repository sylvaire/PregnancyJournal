# implementation-board 来源说明

## 为什么不是 9 张当前导出

计划导出 `06 · 当前实现（2026-10-22 同步）` 板（`nodeId 50:581`）下的 A1–A9 九屏。

2026-10-24 实际检查结果：**该板在当前 Figma 画布中不存在**。

证据（详见 `../manifest.json` 的 `implementationBoard` 段）：

1. `mcp__figma__get_metadata(nodeId=50:581)` → `The provided node ID was not found in the file. The node may have been deleted...`（Figma Debug UUID `ed17b42c-02d6-48c3-90bd-9c15de757f76`）。
2. `mcp__figma__get_metadata(pageId=0:1)` 的顶层节点清单（2026-10-24）只有：
   - `7:41` 孕迹 · 设计基础、`7:2461` 孕迹 · 基础组件
   - `12:22` / `12:279` / `14:251` / `14:560` / `16:468` 五个分组画板
   - 20 个手机屏 `12:26`…`16:644`
   - `22:575` 孕迹 · 精修图标
   没有「06 · 当前实现」板，也没有 `50:584/50:585/50:653/50:712/50:812/51:751/51:783/51:822/51:847/51:893`。
3. `docs/design/figma-state.json` 与 `docs/verification/2026-10-22-figma-sync/manifest.json` 仍登记该板（当时位置 y=7100）→ 索引过期或画布在 2026-10-22 之后被删除/回退；本次为只读任务，未做任何画布修改，无法确认原因。

## 本目录文件

| 文件 | 来源 | 说明 |
|---|---|---|
| `board-06-preview-2026-10-22-historical.png` | 复制自 `docs/verification/2026-10-22-figma-sync/board-06-preview.png`（1300×1122） | 2026-10-22 同步当时的板级预览，含 A1–A9 九帧。**不是 2026-10-24 的现导**，仅作历史参照 |
| （无） | — | A1–A9 单独 PNG 无法导出（节点不存在） |

九帧的对应实机截图（2026-10-22 采集）仍在 `docs/verification/2026-10-22-figma-sync/screens/`，可作为那时实现样貌的对照；但它们不是 Figma 导出。

## 后续

- 若用户/Lead 需要“实现同步板”，需在 Figma 中重建该板或从文件历史版本恢复（本次不改画布）。
- 设计侧素材请用 `../screens/` 的 20 屏导出（19/20 成功，第 20 屏因 MCP Starter 计划限流失败，见 `../manifest.json`）。
