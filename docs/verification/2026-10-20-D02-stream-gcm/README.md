# D02 100MiB原件内存失败与PJF1流式修复

2026-10-20。最终debug/androidTest构建、87项JVM通过，lint0错误/20警告。三个不重叠API34专项共77项：大文件/认证中断3项（19.953秒）、资料/备份服务/独立恢复39项（51.316秒）、真实系统保存/导航/文件结果/恢复UI35项（214.459秒）。原始证据见各日志和两个新增JVM专项XML。未发布新版本或安装手机。

## 实际失败与修复

32MiB先在原JCE实现通过，但100MiB实际在EncryptedFileStore.put阶段失败：OpenSSLAeadCipher.expand试图分配134086672字节，堆growth limit为201326592字节。initial-100m-oom.log保留失败。没有通过增大堆、缩小文件上限或删除该测试掩盖问题。

固定新增org.bouncycastle:bcprov-jdk18on:1.86轻量API，FileGcm使用AESEngine/GCMBlockCipher的processBytes/doFinal，文件输入64KiB、输出64KiB+32缓冲。没有注册全局Provider，未改变AndroidKeyStore认证、设备封装、VMK/HKDF用途或PJF1格式/AAD/nonce/tag；无旧文件重加密和schema变更。认证前明文仅写私有.partial，tag与哈希验证后才发布。派生输入密钥/流式明文缓冲清空，失败/取消清理.partial。

## 兼容与边界证据

JVM双向检查旧JCE与新实现相同密文，覆盖0/1/15/16/17/65535/65536/65537/131073字节；实际PJF1旧对象由新实现读取，新对象可由旧JCE解密。另验证认证循环中取消、认证完成但发布前取消均不产生目标/遗留.partial，原密文仍可读。

Android流式合成样本：100MiB（104857600字节）完整加密/归档/认证/独立恢复及再次解密15188ms；32MiB（33554432字节）4593ms。两次Runtime.maxMemory均201326592字节（192MiB）。这是堆上限和整链路耗时，不是峰值测量，不代表低内存/真实PDF渲染/真机吞吐。原32MiB旧实现2336ms仅历史单样本，未宣称本修订提速。归档实际GCM内部取消清理验证目录，源密文保留并可再次认证。

39项回归包含图片/PDF、预览、替换、回收站、锁定/取消及迁移。35项包含2条真实MainActivity/DocumentsUI保存与确认前重建、23项导航、8项文件结果及2项恢复UI，均无失败/忽略。仍不能泛化到所有OEM、API26、保存中重建/实际进程终止及持续内存压力。

## 依赖与来源

维护版本来源：[BC官方Java发行](https://www.bouncycastle.org/download/bouncy-castle-java/)。轻量API：[官方GCM实现](https://github.com/bcgit/bc-java/blob/main/core/src/main/java/org/bouncycastle/crypto/modes/GCMBlockCipher.java)。官方Maven制品7224011字节，下载脚本核验官方SHA256：2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f；在.local/maven固定供离线构建。许可证逐字提取自制品META-INF/LICENSE.md至docs/third-party/bcprov-LICENSE.md；debug APK也包含META-INF/LICENSE.md。没有声称全应用开源声明或P0阶段已完成。

待办：API26/低内存/优化构建R8与真实手机性能，保存中重建/实际进程终止/提供方异常/OEM矩阵，D03–D07/E完整范围。D02仍未整体验收。