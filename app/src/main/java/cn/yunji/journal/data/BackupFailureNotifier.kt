package cn.yunji.journal.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.yunji.journal.MainActivity
import cn.yunji.journal.R

/** A generic, private notice: errors, endpoints and medical content never enter the notification. */
internal class BackupFailureNotifier(private val context: Context) {
    companion object {
        const val CHANNEL = "backup-status"
        const val ID = 700_004
        const val FAILURE_THRESHOLD = 3
    }

    fun postIfRequired(state: CloudSchedulerStore): Boolean {
        val current = state.load()
        if (current.failureNoticeShown ||
            (current.failures < FAILURE_THRESHOLD && !current.blockedByOtherWriter)) return false
        if (!post()) return false
        state.markFailureNoticeShown()
        return true
    }

    fun post(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "备份状态", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "自动备份持续失败时提醒检查；不显示医疗内容或云端地址"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        val open = PendingIntent.getActivity(context, ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("孕迹提醒")
            .setContentText("打开应用，在备份设置查看")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
        val notice = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("自动备份需要检查")
            .setContentText("云端备份连续失败，请在备份设置查看")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(ID, notice) }.isSuccess
    }

    fun clear() { NotificationManagerCompat.from(context).cancel(ID) }
}
