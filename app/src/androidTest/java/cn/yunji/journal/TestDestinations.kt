package cn.yunji.journal

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Empty `content://` destinations for export / restore tests.
 *
 * Production code rejects non-content schemes and never overwrites a destination, so tests only need an
 * empty writable document. `MediaStore.Downloads` is the real provider on API 29+; Android 8.0 has no
 * Downloads collection and any reference to it fails class loading, so older images fall back to the
 * app's own share `FileProvider`. Reads, writes and the empty-target check still go through
 * `ContentResolver` in both branches, which is what the production contract is about.
 */
object TestDestinations {
    /** Creates (or reserves, when [pending]) an empty destination document. */
    fun empty(context: Context, displayName: String, pending: Boolean = false): Uri =
        if (Build.VERSION.SDK_INT >= 29) {
            Downloads.empty(context, displayName, pending)
        } else {
            providerFile(context, displayName).writeBytes(ByteArray(0))
            FileProvider.getUriForFile(context, "${context.packageName}.share", shareFile(context, displayName))
        }

    /** Removes the destination; [FileProvider] itself does not implement delete. */
    fun delete(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        } else {
            shareFile(context, uri.lastPathSegment ?: return).delete()
        }
    }

    /** Deletes the before-its-time pending marker for API 26 destinations (no-op elsewhere). */
    fun publish(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        }
    }

    private fun shareDir(context: Context): File = File(context.filesDir, "share").apply { mkdirs() }

    private fun shareFile(context: Context, name: String): File = File(shareDir(context), name)

    private fun providerFile(context: Context, displayName: String): File =
        shareFile(context, displayName).apply { parentFile?.mkdirs() }

    /** Loaded only on API 29+, so [MediaStore.Downloads] is never resolved on Android 8.0. */
    private object Downloads {
        fun empty(context: Context, displayName: String, pending: Boolean): Uri {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                if (pending) put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
                "无法创建导出目的地"
            }
            if (!pending) requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { }
            return uri
        }
    }
}
