# D05 连续恢复的两份安全副本独立还原

2026-10-21。LocalRestoreAccessTest 新增 1 项，先从含合成档案的原库恢复 A，再恢复不同 vault 的 B。每次都走生产 prepare/commit、安全副本、代际切换与收尾。测试使用独立 Context 路径、偏好前缀和 AndroidKeyStore 别名，不替换正式库，不操作手机或认证设置。

最终专项 1 项通过（14.091 秒）；补强前全组 9 项通过（32.693 秒），最终增加 SQLCipher 临时还原断言后只重跑相关 1 项，不重复计数。测试构建和 lint 通过，0 错误/24 警告。生产代码未修改，本轮不重复运行 JVM。

确认内容：
- 两份副本不同路径；第二次恢复后第一份副本字节保持不变。
- 第一份用原库恢复密钥独立认证，读到原 vault 与原档案；第二份用 A 的密钥独立认证，读到 A 的 vault 与档案。
- B 的恢复密钥不能解开这两份旧副本；第二次输入密钥没有覆盖当前库已有恢复密钥。
- 两份副本都独立导入真实临时 SQLCipher 数据库，实际查询得到对应记录。
- Runtime 撤销/重新打开后活动资料仍为 B，两份安全副本保留，恢复日志清理，两次云端暂停/恢复均发生。

首轮测试沿用已存在的合成输入文件名，被 LocalBackupArchive 的禁止覆盖保护拒绝；仅删除测试拥有的源输入后重新生成，未删除安全副本。失败运行不计通过。

证据：device-regression.log、device-final.log、build-final.log；源码 LocalRestoreAccessTest.kt 的 consecutiveRestoresRetainIndependentlyDecryptableSafetyCopiesWithTheirOwnKeys。

本用例不包含附件、严格认证或实际杀进程/断电。附件认证已有其他专项，本轮不扩大覆盖范围。安全副本发现/导出/删除界面、旧代际管理、云端下载恢复与完整中断矩阵仍待实现/验收，D05 未全部完成。
