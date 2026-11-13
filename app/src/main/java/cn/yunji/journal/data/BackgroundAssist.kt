package cn.yunji.journal.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 把 [VendorBackgroundPolicy] 的查表结果落到真实系统设置入口。
 *
 * 厂商设置页组件名随时可能变化，因此每次跳转都按候选顺序 try/catch，
 * 并通过 [openAutostart] 的返回值让调用方回退到系统「应用信息」页。
 */
object BackgroundAssist {
    fun plan(): VendorBackgroundPlan? = VendorBackgroundPolicy.plan(Build.MANUFACTURER, Build.BRAND)

    fun ignoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun ignoreBatteryRequestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

    fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    fun notificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** 依次尝试当前厂商的自启动页；全部不可用时返回 false，由调用方回退。 */
    fun openAutostart(context: Context): Boolean {
        val components = plan()?.autostartComponents ?: return false
        for (candidate in components) {
            val intent = Intent().setComponent(ComponentName(candidate.packageName, candidate.className))
            val started = runCatching { context.startActivity(intent) }.isSuccess
            if (started) return true
        }
        return false
    }

    fun openAppDetails(context: Context) {
        runCatching { context.startActivity(appDetailsIntent(context)) }
    }

    private fun String.toUri(): Uri = Uri.parse(this)
}
