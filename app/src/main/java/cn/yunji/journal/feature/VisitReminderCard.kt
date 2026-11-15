package cn.yunji.journal.feature

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.yunji.journal.core.designsystem.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VisitReminderCard(date: String?, time: String?, busy: Boolean,
    onEdit: () -> Unit, onClear: () -> Unit) {
    val configured = date != null && time != null
    JournalCard {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            JournalSectionTitle("提醒", Modifier.align(Alignment.CenterVertically))
            Text(if (configured) "已设置" else "未设置",
                Modifier.align(Alignment.CenterVertically)
                    .then(if (configured) Modifier else Modifier.testTag("visit-reminder-value")),
                style = MaterialTheme.typography.bodySmall,
                color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (configured) {
            FlowRow(Modifier.fillMaxWidth().testTag("visit-reminder-value"),
                horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.align(Alignment.CenterVertically).padding(end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("提醒日期", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(displayDate(requireNotNull(date)), style = MaterialTheme.typography.bodyLarge)
                }
                Column(Modifier.align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("提醒时间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(requireNotNull(time), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (!configured && date == null) {
            JournalSecondaryButton("设置提醒", Modifier.testTag("visit-reminder-set"), enabled = !busy, onClick = onEdit)
        } else {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                JournalTextAction(if (configured) "修改提醒" else "设置提醒", Modifier.testTag("visit-reminder-set"),
                    enabled = !busy, onClick = onEdit)
                TextButton(onClear, Modifier.heightIn(min = 48.dp).testTag("clear-visit-reminder"), enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Text("关闭提醒", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
