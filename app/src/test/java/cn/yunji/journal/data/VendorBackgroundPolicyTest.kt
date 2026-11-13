package cn.yunji.journal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorBackgroundPolicyTest {
    @Test
    fun xiaomiFamilyRequiresXiaomiSecurityCenterEntry() {
        for (manufacturer in listOf("Xiaomi", "XIAOMI", "xiaomi  ")) {
            val plan = VendorBackgroundPolicy.plan(manufacturer, "Redmi")
            assertNotNull(plan)
            assertEquals("小米/Redmi（HyperOS/MIUI）", plan!!.vendorLabel)
            assertEquals("com.miui.securitycenter", plan.autostartComponents.first().packageName)
            assertTrue(plan.hint.contains("自启动"))
        }
        assertEquals(
            "小米/Redmi（HyperOS/MIUI）",
            VendorBackgroundPolicy.plan(null, "POCO")?.vendorLabel,
        )
    }

    @Test
    fun huaweiAndHonorHaveFallbackComponents() {
        for (manufacturer in listOf("HUAWEI", "HONOR", "hihonor")) {
            val plan = VendorBackgroundPolicy.plan(manufacturer, null)
            assertNotNull(plan)
            assertTrue(plan!!.autostartComponents.size >= 2)
            assertTrue(plan.autostartComponents.any { it.packageName == "com.huawei.systemmanager" })
        }
    }

    @Test
    fun oppoFamilyMatchesBrands() {
        for (brand in listOf("OPPO", "OnePlus", "realme")) {
            val plan = VendorBackgroundPolicy.plan("OPPO", brand)
            assertNotNull("brand=$brand", plan)
            assertEquals("OPPO/一加/realme", plan!!.vendorLabel)
        }
    }

    @Test
    fun vivoFamilyMatchesIqoo() {
        assertEquals("vivo/iQOO", VendorBackgroundPolicy.plan("vivo", null)?.vendorLabel)
        assertEquals("vivo/iQOO", VendorBackgroundPolicy.plan(null, "iQOO")?.vendorLabel)
    }

    @Test
    fun samsungAndMeizuUseTheirOwnManagers() {
        assertEquals("com.samsung.android.lool", VendorBackgroundPolicy.plan("samsung", null)!!.autostartComponents.first().packageName)
        assertEquals("com.meizu.safe", VendorBackgroundPolicy.plan("Meizu", null)!!.autostartComponents.first().packageName)
    }

    @Test
    fun unknownVendorReturnsNullSoCallerFallsBackToGenericEntry() {
        assertNull(VendorBackgroundPolicy.plan("Google", "Pixel"))
        assertNull(VendorBackgroundPolicy.plan(null, null))
        assertNull(VendorBackgroundPolicy.plan("", ""))
    }

    @Test
    fun everyPlanHasNonEmptyHintAndUniqueComponents() {
        val plans = listOf("Xiaomi", "HUAWEI", "OPPO", "vivo", "samsung", "Meizu").mapNotNull {
            VendorBackgroundPolicy.plan(it, null)
        }
        assertEquals(6, plans.size)
        for (plan in plans) {
            assertTrue(plan.hint.isNotBlank())
            val keys = plan.autostartComponents.map { "${it.packageName}/${it.className}" }
            assertEquals("duplicate components for ${plan.vendorLabel}", keys.size, keys.distinct().size)
        }
    }
}
