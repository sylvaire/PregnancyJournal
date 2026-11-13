package cn.yunji.journal

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

@HiltAndroidApp
class PregnancyApplication : Application() {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        // No vault key or database is needed to remove workspaces left by a killed process.
        cleanupScope.launch {
            runCatching { cn.yunji.journal.data.ExportService.recover(this@PregnancyApplication) }
                .onFailure { Log.w("PregnancyJournal", "导出暂存目录稍后重试清理", it) }
            runCatching { cn.yunji.journal.core.backup.RestoreWizard.recoverTemporary(
                File(noBackupFilesDir, "vault/restore-validation")) }
                .onFailure { Log.w("PregnancyJournal", "恢复验证暂存目录稍后重试清理", it) }
        }
        cn.yunji.journal.data.ImportWorker.recover(this)
        cn.yunji.journal.data.TrashWorker.recover(this)
        // An alarm may cold-start only this Application. Rebuilding here would invalidate
        // the alarm generation while ReminderAlarmReceiver is still resolving the event.
    }
}
