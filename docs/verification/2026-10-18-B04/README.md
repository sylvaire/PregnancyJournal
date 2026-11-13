# B04 资料导入增量验证

- 版本：0.3.0-dev / versionCode4，debug调试版；schema2。
- [完整构建与回归](构建与回归.txt)：12项JVM单元测试、20项API34设备测试通过。
- 设备用例：5项资料导入（图片/PDF认证与缩略图、源文件独立、部分失败重选、幂等执行与中断残留、源丢失、批量/大小限制、schema1升级），6项既有加密库/业务规则，9项导航及草稿回归。
- [单元测试摘要](单元测试摘要.json)、[静态检查](静态检查摘要.json)：0错误，14条既有依赖升级提示。
- [资料页空状态](资料空状态.png)：专用模拟器测试库截图；入口布局、留白与按钮无截断。缩略图固定占位，避免解码后列表高度改变。
- [手机启动检查](手机启动检查.json)：覆盖安装成功，正式MainActivity启动成功，0.3.0-dev已确认；可见正式应用与根导航，没有预设资料读取错误提示。当前不是首页标题，不将homeLoaded=false写成首页验收通过；未改变用户当前页面或导入真实文件。
- [安装包SHA256](安装包校验.json)；APK位于项目app/build/outputs/apk/debug/app-debug.apk。

数据库升级使用现有schema1 JSON建出真实SQLCipher旧库后由Room升级，检查旧档案和草稿仍在，并检查新表可查询。没有破坏性迁移。

限制：中断通过IMPORTING状态及未提交对象故障注入验证，未完成真实杀进程/重启矩阵；真实系统相机和多文件提供方、低空间、大PDF及低内存边界仍需验收。B05完整预览/检索/排序和B06回收站未实现。预约动画性能问题仍未验收。

API参考：[系统图片选择器](https://developer.android.com/training/data-storage/shared/photopicker)、[FileProvider](https://developer.android.com/reference/kotlin/androidx/core/content/FileProvider)、[Room3数据库升级](https://developer.android.com/reference/androidx/room3/RoomDatabase.Builder)。
