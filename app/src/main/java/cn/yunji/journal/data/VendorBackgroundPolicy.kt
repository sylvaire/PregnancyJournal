package cn.yunji.journal.data

/**
 * 厂商 ROM 对后台任务的限制差异（自启动、省电策略、后台冻结）。
 *
 * 这里只做静态查表：输入 `Build.MANUFACTURER` / `Build.BRAND`，输出需要引导用户打开的
 * 系统设置入口与解释文案。不依赖 Android 框架，便于 JVM 单元测试覆盖匹配规则。
 */
data class AutostartComponent(val packageName: String, val className: String)

data class VendorBackgroundPlan(
    val vendorLabel: String,
    val autostartComponents: List<AutostartComponent>,
    val hint: String,
)

object VendorBackgroundPolicy {
    private val xiaomi = listOf(
        AutostartComponent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    )
    private val huawei = listOf(
        AutostartComponent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        AutostartComponent("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    )
    private val oppo = listOf(
        AutostartComponent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        AutostartComponent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    )
    private val vivo = listOf(
        AutostartComponent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        AutostartComponent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
    )
    private val samsung = listOf(
        AutostartComponent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
    )
    private val meizu = listOf(
        AutostartComponent("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
    )

    /**
     * 返回厂商后台限制的引导信息；识别不到已知厂商时返回 null（只用通用电池优化入口）。
     */
    fun plan(manufacturer: String?, brand: String?): VendorBackgroundPlan? {
        val m = (manufacturer ?: "").trim().lowercase()
        val b = (brand ?: "").trim().lowercase()
        fun matches(vararg keys: String): Boolean = keys.any { key ->
            m == key || b == key || m.startsWith(key) || b.startsWith(key)
        }
        return when {
            matches("xiaomi", "redmi", "poco") -> VendorBackgroundPlan(
                vendorLabel = "小米/Redmi（HyperOS/MIUI）",
                autostartComponents = xiaomi,
                hint = "小米/Redmi 需要在「安全中心 → 应用管理 → 自启动」允许孕迹，并把「省电策略」设为「无限制」；否则用药提醒可能被推迟或被后台冻结。",
            )
            matches("huawei", "honor", "hihonor") -> VendorBackgroundPlan(
                vendorLabel = "华为/荣耀",
                autostartComponents = huawei,
                hint = "华为/荣耀需要在「应用启动管理」中把孕迹设为手动管理，并允许自启动、关联启动；否则提醒任务可能不触发。",
            )
            matches("oppo", "oneplus", "realme") -> VendorBackgroundPlan(
                vendorLabel = "OPPO/一加/realme",
                autostartComponents = oppo,
                hint = "OPPO/一加/realme 需要在「自启动管理」中允许孕迹，并把耗电管理设为「不限制」。",
            )
            matches("vivo", "iqoo") -> VendorBackgroundPlan(
                vendorLabel = "vivo/iQOO",
                autostartComponents = vivo,
                hint = "vivo/iQOO 需要在「自启动」中允许孕迹，并在电池「后台耗电管理」中允许后台运行。",
            )
            matches("samsung") -> VendorBackgroundPlan(
                vendorLabel = "三星",
                autostartComponents = samsung,
                hint = "三星需要在电池设置中把孕迹设为「不受限制」，并允许后台活动。",
            )
            matches("meizu") -> VendorBackgroundPlan(
                vendorLabel = "魅族",
                autostartComponents = meizu,
                hint = "魅族需要在「后台管理」中允许孕迹自启动与后台运行。",
            )
            else -> null
        }
    }
}
