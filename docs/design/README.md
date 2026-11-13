# Figma 设计交付与继续入口

- 当前版本：2026-10-17，V1.0 首轮 UI，待用户评审。
- 最新修订：2026-10-22，新增 [06 · 当前实现同步板](https://www.figma.com/design/qwUxmzTnSJtzppU5eZwHcy?node-id=50-581)：依据 API36 模拟器实机截图（`UiSnapshotTest` 9 项）把已实现的 9 个界面复刻为可编辑图层与组件实例（未导入整页截图），同步清单与未覆盖项见 [同步记录](../verification/2026-10-22-figma-sync/manifest.json)。
- 2026-10-18，完成 [UI 细节检查与精修](UI细节检查-2026-10-17.md)，含16张修改前后截图、组件尺寸规则及原型保留核对。
- [Figma 设计文件](https://www.figma.com/design/qwUxmzTnSJtzppU5eZwHcy?node-id=12-138)
- [状态与节点索引](figma-state.json)：继续设计时先读此文件，再实时检查 Figma。
- [阶段记录](../阶段记录/S01-FigmaUI设计.md)：设计范围、检查结果和交接。

`figma-*.js` 和 `*-result.json` 保存制作过程与当次返回结果。画板经过重新排布、文字修复与原型连接，历史结果不是最终布局；禁止直接重放构建脚本覆盖成品。最终节点位置以 `figma-state.json` 和实时画布为准。

手机画板为顶层 Frame，以支持原型导航；五个分组背景仅作画布标题，导出时选择具体手机画板。界面由可编辑文字、自动布局、矢量和组件实例组成，没有将整页截图作为成品图层。

`previews/` 是检查用截图副本，不替代 Figma 可编辑源文件。设计稿中的校验通过、保存成功和示例用药均为演示文字，不代表应用已经实现这些功能。
