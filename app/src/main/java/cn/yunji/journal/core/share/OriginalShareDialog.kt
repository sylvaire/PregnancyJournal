package cn.yunji.journal.core.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Plaintext disclosure before any original leaves the vault, following the D07 wording: exported
 * content is plaintext and the grant is temporary/read-only.
 */
@Composable
internal fun OriginalShareDialog(
    name: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("share-dialog"),
        title = { Text("分享这份原件？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("“$name”会以明文交给所选应用，分享后不再受本应用加密保护，请自行妥善保管。")
                Text(
                    "只授予本次只读访问，不申请长期授权；返回本应用后会删除临时副本并撤销授权。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onConfirm, enabled = !busy, modifier = Modifier.testTag("confirm-share")) {
                Text(if (busy) "准备中…" else "分享")
            }
        },
        dismissButton = {
            TextButton(onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}
