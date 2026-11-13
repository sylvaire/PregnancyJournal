package cn.yunji.journal.core.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.util.UUID

/** Injectable chooser launcher; production uses [ContextShareLauncher], device tests record the intent. */
fun interface ShareLauncher {
    /** Returns false when no target activity accepted the chooser. */
    fun launch(invocation: ShareInvocation): Boolean
}

/** Single read grant, one stream, explicit chooser; the receiver never gets a write or persisted URI. */
class ContextShareLauncher(private val context: Context) : ShareLauncher {
    override fun launch(invocation: ShareInvocation): Boolean = try {
        context.startActivity(invocation.chooser())
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

fun ShareInvocation.toIntent(): Intent = Intent(action).apply {
    type = this@toIntent.type
    putExtra(Intent.EXTRA_STREAM, stream.toUri())
    // Inside apply, `flags` would resolve to Intent.flags (0); read the invocation's flags explicitly.
    addFlags(this@toIntent.flags)
    // Android also grants EXTRA_STREAM holders via ClipData; keep exactly the same single URI.
    clipData = ClipData.newRawUri("原件", stream.toUri())
}

fun ShareInvocation.chooser(title: String = "分享原件"): Intent =
    Intent.createChooser(toIntent(), title).also { it.addFlags(this.flags) }

/**
 * Stages one decrypted original in `filesDir/share`, exposes it through the dedicated
 * `${applicationId}.share` provider and releases it when the user returns to the app.
 *
 * Only the original's URIs are ever published here; the vault, preview cache and camera cache are
 * not reachable through the provider paths.
 */
class OriginalSharer(
    private val context: Context,
    private val launcher: ShareLauncher = ContextShareLauncher(context),
) {
    private val temporary = ShareTemporaryFiles(File(context.filesDir, ShareOriginalPolicy.DIRECTORY_NAME))
    private val release = ShareRelease(
        revoke = { uri -> context.revokeUriPermission(uri.toUri(), ShareOriginalPolicy.flags()) },
        delete = { temporary.clearAll() },
    )
    private var pending: String? = null

    fun authority(): String = ShareOriginalPolicy.authority(context.packageName)

    /** Creates the target file inside the share directory; the caller writes the decrypted bytes. */
    fun prepare(displayName: String, mime: String): File =
        temporary.prepare(ShareOriginalPolicy.temporaryName(displayName, mime, UUID.randomUUID().toString()))

    /** Publishes the staged file as a provider URI. Only files inside the share directory are accepted. */
    fun publish(file: File, mime: String): ShareInvocation {
        require(temporary.contains(file)) { "分享文件不在临时目录" }
        require(file.isFile) { "分享文件不存在" }
        val uri: Uri = FileProvider.getUriForFile(context, authority(), file)
        require(uri.authority == authority()) { "分享地址不正确" }
        val invocation = ShareOriginalPolicy.invocation(uri.toString(), mime)
        pending = invocation.stream
        return invocation
    }

    fun launch(invocation: ShareInvocation): Boolean = launcher.launch(invocation)

    /** Revoke then delete; called on ON_RESUME (i.e. after the target app returns control). */
    fun releaseAfterReturn(): ShareReleaseResult {
        val uri = pending
        pending = null
        return release.afterReturn(uri)
    }

    /** Failure path before/without a published URI: removes the staged file and any residue. */
    fun discard(file: File?) {
        val removed = file?.let { temporary.delete(it) } ?: false
        if (!removed) temporary.clearAll()
    }

    /** Startup safety net: no pending grant survives a process restart, so only residue is cleared. */
    fun clearResidual(): ShareReleaseResult {
        val uri = pending
        pending = null
        return release.afterReturn(uri)
    }
}
