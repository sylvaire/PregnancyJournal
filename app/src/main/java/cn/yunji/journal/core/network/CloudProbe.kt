package cn.yunji.journal.core.network

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Writes only a fresh random test path. Existing vaults are inspected, never modified here. */
object CloudProbe {
    fun run(client: WebDavClient,connection: CloudConnection,vaultId: String,active: ()->Unit={},
        beforeWrite: (String)->Unit={},afterCleanup: ()->Unit={}): CloudCapabilities {
        active();client.probe();active()
        val entries=client.list(connection.folder)
        val container=entries.firstOrNull {it.segments==connection.folder+"pregnancy-journal" && it.collection && it.status in 200..299}
        active()
        val vaults=if(container!=null)client.list(container.segments).filter {it.segments!=container.segments} else emptyList()
        var result=CloudCapabilities(completeListing=entries.all {it.status in 200..299} && vaults.all {it.status in 200..299},
            existingVaultCount=vaults.count {it.collection},containsThisVault=vaults.any {it.collection && it.segments.lastOrNull()==vaultId})
        // 探测写入放在自建子目录内，而不是直接写 connection.folder：部分服务商（如坚果云）
        // 拒绝在 WebDAV 根目录直接 PUT 文件（404）且拒绝在根目录 DELETE（403），
        // 父目录留空时会导致连接测试必然失败且无法清理。
        // 名称也不使用 "." 前缀：隐藏目录在部分服务商上可能被额外限制。
        // 目录名必须短：坚果云对目录名有长度上限（实测 ≤28 字符可建，≥32 返回 400
        // "sandbox name is too long"）；"yunji-probe-<uuid>" 共 48 字符会被直接拒绝。
        val name="yjp-${UUID.randomUUID().toString().take(8)}"
        val testDirectory=connection.folder+name
        val testFile=testDirectory+"test.bin"
        var fileAttempted=false;var directoryCreated=false;var directoryAttempted=false;var cleaned=true;var deleteVerified=false;var cleanupAllowed=true
        var directoryRemoved=false
        val bytes=ByteArray(256).also {SecureRandom().nextBytes(it)}
        beforeWrite(name)
        try {
            active()
            directoryAttempted=true
            try {client.createDirectory(testDirectory);directoryCreated=true;result=result.copy(canCreateDirectory=true)}
            catch(e: DavException){if(e.status !in setOf(403,405,501))throw e;directoryAttempted=false}
            // 只在自建目录存在后写文件：否则文件会落到父目录（根目录），在坚果云等
            // 拒绝根目录写入的服务商上产生误判为“不可写”的结果。
            if(directoryCreated) {
                active();fileAttempted=true
                try {client.put(testFile,bytes);result=result.copy(writable=true)}
                catch(e: DavException){if(e.status !in setOf(403,405))throw e;fileAttempted=false}
            }
            if(result.writable) {
            active()
            val downloaded=client.get(testFile,bytes.size+1)
            if(!MessageDigest.isEqual(bytes,downloaded))throw IOException("云盘回读校验未通过")
            result=result.copy(verified=true)
            active()
            try {result=result.copy(headAvailable=client.head(testFile)==bytes.size.toLong())}
            catch(e: DavException){if(e.status !in setOf(403,405,501))throw e}
            }
        } catch(e: DavException) {
            if(e.status in setOf(401,429,503))cleanupAllowed=false
            throw e
        } finally {
            bytes.fill(0)
            if(!cleanupAllowed)cleaned=false else {
            if(fileAttempted)try {deleteVerified=client.delete(testFile)}catch(_:Exception){cleaned=false}
            // 目录删除失败（例如坚果云对根目录 DELETE 恒返 403）只留下一个空目录。
            // 内容已清理时不应把整次测试标记为“待清理”而阻塞用户重测；
            // canDelete 仍如实为 false，UI 据此提示服务商限制。
            if(directoryCreated)directoryRemoved=runCatching {client.delete(testDirectory)}.getOrDefault(false)
            else if(directoryAttempted)cleaned=false
            }
            if(cleaned)afterCleanup()
        }
        return result.copy(canDelete=cleaned && deleteVerified && directoryRemoved && directoryCreated,
            cleanupPending=!cleaned,testName=if(cleaned)"" else name)
    }
}
