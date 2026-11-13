package cn.yunji.journal.core.network

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class CloudConnection(val endpoint: String,val username: String,val password: String,val folder: List<String> = emptyList()) {
    override fun toString()="CloudConnection(<redacted>)"
    fun normalized(): CloudConnection {
        val url=requireNotNull(endpoint.trim().toHttpUrlOrNull()) {"请填写有效的HTTPS服务地址"}
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query==null && url.fragment==null && endpoint.length<=2048) {"请使用不带账号、查询参数或片段的HTTPS地址"}
        val user=username.trim()
        require(user.isNotBlank()) {"请填写账号"}
        require(user.length<=256 && ':' !in user && user.none(Char::isISOControl)) {"请检查账号，不能包含冒号或控制字符"}
        require(password.isNotEmpty() && password.length<=2048 && password.none(Char::isISOControl)) {"请填写有效的应用密码"}
        require(folder.size<=24 && folder.joinToString("/").length<=1024 && folder.all {it.isNotBlank() && it.length<=255 && it!="." && it!=".." && '/' !in it && '\\' !in it && it.none(Char::isISOControl)}) {"目录路径过长或无效，请使用不含上级目录和控制字符的相对路径"}
        val base=url.newBuilder().apply {if(!url.encodedPath.endsWith('/'))addPathSegment("")}.build()
        return copy(endpoint=base.toString(),username=user)
    }
}

@Serializable
data class CloudCapabilities(val readable: Boolean=true,val writable: Boolean=false,val verified: Boolean=false,
    val canCreateDirectory: Boolean=false,val canDelete: Boolean=false,val headAvailable: Boolean=false,
    val completeListing: Boolean=false,val existingVaultCount: Int=0,val containsThisVault: Boolean=false,
    val cleanupPending: Boolean=false,val testName: String="")

@Serializable
data class SavedCloudConnection(val format: Int=1,val connection: CloudConnection,val capabilities: CloudCapabilities,val testedAt: Long)

object CloudCredentials {
    fun resolve(endpoint: String,username: String,password: String,folder: List<String>,saved: CloudConnection?): CloudConnection {
        val candidate=CloudConnection(endpoint,username,password.ifEmpty {"pending"},folder).normalized()
        if(password.isNotEmpty())return candidate
        require(saved!=null) {"请填写应用密码"}
        require(saved.endpoint==candidate.endpoint && saved.username==candidate.username) {"服务地址或账号已改变，请重新输入应用密码"}
        return candidate.copy(password=saved.password).normalized()
    }
}
