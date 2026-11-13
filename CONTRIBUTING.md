# 参与贡献

感谢关注孕迹（PregnancyJournal）。本项目是一个注重隐私的孕期记录 Android 应用，
欢迎提交问题与改进建议。

## 报告问题

提交 Issue 时请说明：

- 应用版本（设置页底部可见，例如 `0.26.0-dev`）与 schema 版本
- 设备型号、Android 版本、是否 16 KiB 页设备
- 复现步骤、期望结果、实际结果
- 相关的 `adb logcat` 片段（**请先移除个人信息**）

## 提交改动

1. 先阅读 [`AGENTS.md`](AGENTS.md) 与 [`docs/项目进度.md`](docs/项目进度.md)，了解当前阶段与约定。
2. 涉及功能或数据结构的改动，先确认对应的实施文档与阶段记录。
3. 提交前在本地执行：

```bash
# 单元测试
./gradlew --gradle-user-home .gradle-user testDebugUnitTest --offline

# 静态检查
./gradlew --gradle-user-home .gradle-user lintDebug --offline

# 仪器测试（需要已连接设备或模拟器）
adb shell am instrument -w -r cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner
```

4. 新增行为请附带测试；修复缺陷请说明根因与回归方式。

## 严禁提交的内容

**任何**真实用户数据或凭据都不得进入仓库，包括但不限于：

- 恢复密钥（`YJ1:` 开头的字符串）
- 云盘地址中的账号、应用密码、Token
- 真实孕期/产检/医疗记录与附件原件
- 设备序列号、局域网 IP、adb 配对码、设备指纹
- 签名密钥（`*.jks` / `*.keystore`）与 `local.properties`

测试请使用合成数据；凭据通过环境变量或运行时输入提供，不写入源码与文档。
`.gitignore` 已排除 `.local/`（本地测试工作区）与常见密钥文件。

## 代码风格

- 遵循现有文件的命名、注释密度与写法；注释说明「为什么」而非「做了什么」。
- 与加密、备份、恢复相关的改动需格外谨慎，避免降低现有安全边界。
- 不引入会削弱 `FLAG_SECURE`、应用锁或凭据处理的行为。
