# N22 产检资料多条与失败状态续作

- 日期：2026-10-29；阶段：M6；设计依据：Ardot 文件 `737849992357132` 的 N22 画板，本地 `../2026-10-25-ardot-visual/screens/N22.png`。未修改 Ardot 画板。
- 实现：多份已保存资料默认用紧凑行显示名称、保存状态和“操作”，点击后保留原有预览、编辑、删除、封面、重新关联、导出、分享和替换功能。单份资料仍按原详细卡显示。类型尚未确认的导入中/失败项，在“图片”或“PDF”筛选时仍可见，避免隐藏重试入口；“清除搜索与筛选”同时重置类型筛选。
- 产物：`app/src/main/java/cn/yunji/journal/feature/DocumentScreen.kt`、`app/src/androidTest/java/cn/yunji/journal/UiStateMatrixTest.kt`；[多份资料截图](screens/multiple.png)、[导入失败截图](screens/failed.png)。截图均使用合成数据。
- 验证：`assembleDebug`、`testDebugUnitTest`、`assembleDebugAndroidTest` 构建通过；API36 模拟器多份资料展开操作及导入失败后切换图片筛选 **2/2**，清晰合成名称的最终多资料截图复测 **1/1**。两张截图已查看，无文字遮挡。
- 边界：本轮没有验证回收站多条/清理中、PDF 类型失败、图片与 PDF 混合排序、放大字体、深色、横屏和小米 11 Ultra 真机。设计稿的示例行比当前真实单份资料卡更简短；单份卡保留可见的预览/编辑/删除以避免降低操作可达性。
- 下一步：补 N22 回收站多条与清理中、N21 PDF 首页/横屏，再核对 N12 多条和其余页面状态。
