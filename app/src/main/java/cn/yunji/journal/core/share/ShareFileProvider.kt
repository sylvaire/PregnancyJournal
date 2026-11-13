package cn.yunji.journal.core.share

import androidx.core.content.FileProvider
import java.io.File

/**
 * Independent provider for temporary single-original shares.
 *
 * It is separate from the camera provider so revoking/cleaning share grants can never affect the
 * capture path, and it is only exposed through `res/xml/share_paths.xml` (`filesDir/share`).
 * [onCreate] runs when the app process starts, clearing plaintext left behind by a previous process.
 */
class ShareFileProvider : FileProvider() {
    override fun onCreate(): Boolean {
        val ready = super.onCreate()
        runCatching {
            val application = requireNotNull(context)
            ShareTemporaryFiles(File(application.filesDir, ShareOriginalPolicy.DIRECTORY_NAME)).clearAll()
        }
        return ready
    }
}
