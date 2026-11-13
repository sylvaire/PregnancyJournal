# N21 PDF 首页标准缩放复核

- 日期：2026-10-29；阶段：M6；设计依据：Ardot 文件 `737849992357132` 的 N21 预览示意；本轮未修改画板。
- 产物：现有 `NavigationExperienceTest#pdfNavigationMetadataSearchAndBackgroundCleanup` 补充首页 `1 / 3` 断言与[默认缩放截图](screens/pdf-first-page.png)，图片为合成 PDF。
- 验证：`assembleDebugAndroidTest` 成功；API36 模拟器完整 PDF 路径 **1/1** 通过，覆盖首页、翻页、跳到第 3 页、放大、后台清理及删除。首页截图已查看，内容、页码、翻页和缩放操作没有遮挡。
- 边界：截图只覆盖标准字体、浅色、竖屏与合成 PDF。横屏、大字、深色、复杂真实 PDF 和小米 11 Ultra 真机尚未验收；这次未改生产预览代码。
