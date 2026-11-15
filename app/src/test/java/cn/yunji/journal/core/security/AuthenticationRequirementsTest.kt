package cn.yunji.journal.core.security

import org.junit.Assert.*
import org.junit.Test

class AuthenticationRequirementsTest {
    @Test fun secureScreenIsRequiredEvenIfVendorReportsBiometricsAvailable() {
        listOf(true,false).forEach {available ->
            assertTrue(authenticationRequirementMessage(false,available,false)!!.contains("设置锁屏密码"))
        }
    }
    @Test fun legacyStrictModeExplainsItsStrongerRequirement() {
        assertTrue(authenticationRequirementMessage(true,false,true)!!.contains("强生物识别"))
        assertFalse(authenticationRequirementMessage(true,false,false)!!.contains("强生物识别"))
    }
    @Test fun eligibleDeviceMayProceed() {
        assertNull(authenticationRequirementMessage(true,true,false))
        assertNull(authenticationRequirementMessage(true,true,true))
    }
}
