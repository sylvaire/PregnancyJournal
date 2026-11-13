package cn.yunji.journal.core.share

import android.content.Intent

/**
 * Pure rules for the single-original share path.
 *
 * The share provider is deliberately separate from `${applicationId}.capture`: it can only reach
 * `filesDir/share`, never the encrypted vault, the preview cache or the camera cache. Everything in
 * this object is free of Android platform calls (only compile-time constants are read) so the
 * security-relevant rules are covered by JVM tests; the provider/chooser glue lives in
 * [OriginalSharer] and is covered by device tests.
 */
object ShareOriginalPolicy {
    const val AUTHORITY_SUFFIX = ".share"
    const val DIRECTORY_NAME = "share"
    const val MAX_NAME_CHARS = 100
    const val MAX_TYPE_CHARS = 160
    const val MAX_CONTENT_URI_BYTES = 8192
    const val MAX_TEMP_MILLIS = 24L * 60 * 60 * 1000

    private val APPLICATION_ID = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val MIME = Regex("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*")
    private val UNSAFE_NAME_CHARS = Regex("[\\\\/:*?\"<>|]|\\p{Cntrl}|\\p{Cf}")

    /** `${applicationId}.share`; a suffix-appended authority cannot be confused with the capture provider. */
    fun authority(applicationId: String): String {
        require(APPLICATION_ID.matches(applicationId)) { "应用包名不正确" }
        return applicationId + AUTHORITY_SUFFIX
    }

    /** Read-only, non-persistable grant: the receiver may read this one URI while the grant is active. */
    fun flags(): Int = Intent.FLAG_GRANT_READ_URI_PERMISSION

    fun isReadOnlyGrant(flags: Int): Boolean =
        flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
            flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0 &&
            flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0

    /** Concrete `type/subtype` for `ACTION_SEND`; parameters are dropped so providers resolve normally. */
    fun shareType(mime: String): String {
        val type = mime.trim().substringBefore(';').trim()
        require(type.length <= MAX_TYPE_CHARS && MIME.matches(type)) { "分享类型不正确" }
        return type
    }

    /** A single `content://` stream plus exactly one read grant; no write and no persistable bits. */
    fun invocation(uri: String, mime: String): ShareInvocation {
        require(uri.startsWith("content://")) { "分享地址不正确" }
        require(uri.toByteArray().size <= MAX_CONTENT_URI_BYTES) { "分享地址不正确" }
        require(uri.none { it.isISOControl() || it == ' ' }) { "分享地址不正确" }
        require(uri.substringAfter("content://").substringBefore('/').length in 3..255) { "分享地址不正确" }
        return ShareInvocation(Intent.ACTION_SEND, shareType(mime), uri, flags())
    }

    /**
     * Removes path separators, control characters and trailing dots from the user-visible name; the
     * share file always lives directly under the share directory, so this is defence in depth.
     */
    fun safeBaseName(displayName: String): String {
        val base = displayName.substringBeforeLast('.', displayName)
            .replace(UNSAFE_NAME_CHARS, "_")
            .replace(Regex("\\.{2,}"), "_")
            .replace(Regex("_+"), "_")
            .trim()
            .trim('.')
            .take(MAX_NAME_CHARS)
        return base.ifBlank { "原件" }
    }

    fun extensionFor(mime: String): String = when (shareType(mime)) {
        "application/pdf" -> "pdf"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        "image/jpeg" -> "jpg"
        else -> "bin"
    }

    /** `名称-<随机令牌>.<后缀>`: unique per share, yet recognisable in the target application. */
    fun temporaryName(displayName: String, mime: String, token: String): String {
        require(token.matches(Regex("[0-9a-fA-F-]{8,64}"))) { "临时名称不正确" }
        return "${safeBaseName(displayName)}-$token.${extensionFor(mime)}"
    }
}

/** Platform-free description of the created SEND intent, so launch policy can be asserted off-device. */
data class ShareInvocation(val action: String, val type: String, val stream: String, val flags: Int)
