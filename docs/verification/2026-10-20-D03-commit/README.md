# D03 认证提交格式首项验证

2026-10-20：CloudCommit新增4项测试通过，当前完整JVM共91项、0失败；testDebugUnitTest/lintDebug构建通过。此次未重跑设备测试，既有D02设备结果不扩大为D03验证。

YJ-COMMIT-1公开字段为format、snapshotId、cipherSize、cipherSha256、authenticated。后者采用既有PJE1 AES256-GCM封装与base64url，密钥用途cloud-commit，AAD为cloud-commit/v1/<vaultId>/<snapshotId>。加密正文保存vaultId、snapshotId、writerId、writerSequence、capturedSequence、createdAt及相同的密文长度/哈希，必须与认证解密后的LogicalSnapshot逐项相等。公开哈希仅完整性预筛，不是防伪认证。

输入上限8192字节，公开JSON深度最多4、严格UTF8/版本/字段、规范小写UUID、64位小写SHA256、清单长度32..16MiB+32；writerSequence必须正数。文件名传入的snapshotId必须一致，复制有效提交到另一路径不能生成新快照。派生密钥/明文正文缓冲及时擦除，不输出业务记录。未更改已有D02清单或PJF1格式。

测试覆盖完整往返、公开业务元数据隐藏、修改普通哈希并替换有效清单不可伪造、错资料库/重命名/截断、超长/深嵌套与非法序号。上传/逐项下载校验/commit最终发布、持久writer/序号、状态页、假服务故障注入与真实服务尚未接入；本项不可被展示为云端备份成功。