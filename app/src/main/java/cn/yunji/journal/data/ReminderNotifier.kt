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
import cn.yunji.journal.domain.ReminderEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderNotifier @Inject constructor(@param:ApplicationContext private val context: Context) {
    companion object {
        const val CHANNEL = "reminders"
        const val TEST_ID = 700_001
    }

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "孕迹提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "产检、用药和自定义本地提醒；锁屏默认隐藏具体内容"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
        })
    }

    fun permissionGranted(): Boolean = if (Build.VERSION.SDK_INT < 33) true
        else ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun isActive(key: String): Boolean = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any { it.id == ReminderContract.notificationId(key) }

    fun post(event: ReminderEvent, generation: Long): Boolean {
        if (!permissionGranted()) return false
        val content = ReminderContract.contentIntent(context, event)
        val base = ReminderContract.alarmIntent(context, event, generation)
        // Keep notification text private even if the app leaves the foreground during async posting.
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("孕迹提醒")
            .setContentText("打开孕迹解锁后查看")
            .setStyle(NotificationCompat.BigTextStyle().bigText("打开孕迹解锁后查看"))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(content)
        if (event.targetType == "MEDICATION") {
            builder.addAction(0, "已服用", action(base, ReminderContract.ACTION_TAKEN, event.key.hashCode()))
            builder.addAction(0, "稍后10分钟", action(base, ReminderContract.ACTION_SNOOZE, event.key.hashCode() + 1))
        }
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("孕迹提醒")
            .setContentText(event.publicBody)
            .setContentIntent(content)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        builder.setPublicVersion(public)
        return try {
            NotificationManagerCompat.from(context).notify(ReminderContract.notificationId(event.key), builder.build())
            true
        } catch (_: SecurityException) { false }
    }

    fun cancel(key: String) {
        NotificationManagerCompat.from(context).cancel(ReminderContract.notificationId(key))
    }

    /** Existing foreground notifications must not retain details after the UI is covered. */
    fun redactActive() {
        if(!permissionGranted())return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter {it.notification.channelId==CHANNEL && it.id!=TEST_ID}.forEach {active ->
            val replacement=Notification.Builder.recoverBuilder(context,active.notification)
                .setContentTitle("孕迹提醒").setContentText("打开孕迹解锁后查看")
                .setStyle(Notification.BigTextStyle().bigText("打开孕迹解锁后查看"))
                .setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).build()
            runCatching {manager.notify(active.tag,active.id,replacement)}
        }
    }

    fun postTest(): Boolean {
        if (!permissionGranted()) return false
        val content = PendingIntent.getActivity(context, TEST_ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("孕迹提醒测试")
            .setContentText("本机可以显示提醒；实际提醒由系统调度，可能受省电策略影响。")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        return try { NotificationManagerCompat.from(context).notify(TEST_ID, notification); true }
        catch (_: SecurityException) { false }
    }

    private fun action(base: Intent, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context,MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ReminderContract.EXTRA_KEY,base.getStringExtra(ReminderContract.EXTRA_KEY))
            .putExtra(ReminderContract.EXTRA_GENERATION,base.getLongExtra(ReminderContract.EXTRA_GENERATION,0))
            .setData(android.net.Uri.parse("yunji://reminder-action/${android.net.Uri.encode(action)}/${android.net.Uri.encode(base.getStringExtra(ReminderContract.EXTRA_KEY))}"))
        return PendingIntent.getActivity(context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
