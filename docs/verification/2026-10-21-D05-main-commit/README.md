# D05 正式 Main 确认恢复与再次恢复

2026-10-21，专用 API34 模拟器独立 Android 用户（非 Owner）中，新增 MainRestoreCommitTest 1 项通过，23.901 秒；本轮应用与测试构建通过。生产代码未修改，未重复运行此前 180 项 JVM 或 lint，不将历史结果计为本轮结果。未发布版本或安装手机。

用两份不同 vault 的随机合成备份，连续经过正式 Main、本地备份入口、DocumentsUI、密码输入、校验预览、替换确认、完成与重新打开。每次断言新 ViewModel 只包含所选档案、旧 ViewModel 已替换、导航 epoch 已变化、文件交接与恢复日志已清空。两次操作之间实际重建 Activity，第二次仍可进入恢复和完成切换。真实 Runtime、SQLCipher、安全副本与后台停机参与流程，没有替换成假恢复服务。

测试具有专用模拟器/API34/非 Owner UID/显式用户参数四重条件。只创建和删除本测试拥有的 Downloads 文件及私有暂存，合成 VMK 在 finally 擦除。Owner 和手机资料没有清除或替换。独立用户留作后续隔离验证，结束时切回 Owner。

首轮反射读取 Compose 委托字段不匹配，改读对应 State；其后因重建后重复点击“我的”使测试匹配到不可点击标题，删除重复动作。最终版本完成两次恢复并通过；未把失败运行算作通过。

证据：device-test.log、build.log；测试源码 app/src/androidTest/java/cn/yunji/journal/MainRestoreCommitTest.kt。日志不输出恢复密钥或医疗记录。

仍待验证：严格模式重新认证后的后台恢复、提交中重建/杀进程、真实提供方阻塞取消、授权引用释放、独立解密两次安全副本、旧代际/副本管理、云端快照下载恢复。本次不代表 D05 或 V1.0 全部验收。
