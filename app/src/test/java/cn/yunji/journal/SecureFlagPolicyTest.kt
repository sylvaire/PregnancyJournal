package cn.yunji.journal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FLAG_SECURE must stay on for every normal build (debug/release/performance) and be lifted only for
 * the explicit real-device screenshot verification build (`-PvisualVerification=true`).
 */
class SecureFlagPolicyTest {
    @Test fun normalBuildsKeepTheSecureFlag() {
        assertTrue(secureFlagRequired(visualVerification = false))
    }

    @Test fun verificationBuildMayExposeContentForScreenshots() {
        assertFalse(secureFlagRequired(visualVerification = true))
    }

    @Test fun currentBuildDefaultsToSecure() {
        assertTrue("unit tests are built without -PvisualVerification", secureFlagRequired())
    }
}
