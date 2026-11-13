package cn.yunji.journal.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task-6 (A03): the lock / return / notification matrix as pure JVM rules.
 *
 * These are the rules the device tests exercise through [AccessState]; keeping the matrix in
 * [AccessPolicy] lets every edge (exact timeout boundary, strict mode, clock regression,
 * notification lease) be repeated deterministically without waiting on a real clock.
 */
class AccessPolicyTest {

    @Test
    fun backgroundLockAppliesOnlyWhenImmediateOrStrict() {
        assertTrue(AccessPolicy.locksOnBackground(strict = false, timeoutMinutes = 0))
        assertTrue(AccessPolicy.locksOnBackground(strict = true, timeoutMinutes = 5))
        assertFalse(AccessPolicy.locksOnBackground(strict = false, timeoutMinutes = 1))
        assertFalse(AccessPolicy.locksOnBackground(strict = false, timeoutMinutes = 60))
    }

    @Test
    fun returnWithinGraceDoesNotLock() {
        assertFalse(AccessPolicy.locksOnReturn(strict = false, timeoutMinutes = 1, awayMillis = 0))
        assertFalse(AccessPolicy.locksOnReturn(strict = false, timeoutMinutes = 1, awayMillis = 59_999))
        assertFalse(AccessPolicy.locksOnReturn(strict = false, timeoutMinutes = 5, awayMillis = 299_999))
    }

    @Test
    fun returnAtExactTimeoutBoundaryLocks() {
        assertTrue(AccessPolicy.locksOnReturn(strict = false, timeoutMinutes = 1, awayMillis = 60_000))
        assertTrue(AccessPolicy.locksOnReturn(strict = false, timeoutMinutes = 5, awayMillis = 300_000))
    }

    @Test
    fun returnAfterGraceLocks() {
        assertTrue(AccessPolicy.locksOnReturn(strict = false, timeoutMinutes = 1, awayMillis = 60_001))
        assertTrue(AccessPolicy.locksOnReturn(strict = false, timeoutMinutes = 60, awayMillis = 60L * 60_000))
    }

    @Test
    fun immediateLockModeLocksOnReturnRegardlessOfAwayTime() {
        assertTrue(AccessPolicy.locksOnReturn(strict = false, timeoutMinutes = 0, awayMillis = 0))
        assertTrue(AccessPolicy.locksOnReturn(strict = false, timeoutMinutes = 0, awayMillis = -5_000))
    }

    @Test
    fun strictModeLocksOnReturnRegardlessOfConfiguredTimeout() {
        assertTrue(AccessPolicy.locksOnReturn(strict = true, timeoutMinutes = 5, awayMillis = 0))
        assertTrue(AccessPolicy.locksOnReturn(strict = true, timeoutMinutes = 60, awayMillis = -1))
    }

    @Test
    fun negativeAwayTimeFromClockRegressionDoesNotLock() {
        assertFalse(AccessPolicy.locksOnReturn(strict = false, timeoutMinutes = 1, awayMillis = -1))
        assertFalse(AccessPolicy.locksOnReturn(strict = false, timeoutMinutes = 5, awayMillis = -60_000))
    }

    @Test
    fun needsAuthenticationWhenDeviceLockedEvenWithoutAppLock() {
        assertTrue(AccessPolicy.needsAuthentication(deviceLocked = true, lockEnabled = false, strict = false, authenticated = false))
        assertTrue(AccessPolicy.needsAuthentication(deviceLocked = true, lockEnabled = false, strict = false, authenticated = true))
    }

    @Test
    fun needsAuthenticationOnlyWhenAppLockConfiguredOrStrict() {
        assertFalse(AccessPolicy.needsAuthentication(deviceLocked = false, lockEnabled = false, strict = false, authenticated = false))
        assertTrue(AccessPolicy.needsAuthentication(deviceLocked = false, lockEnabled = true, strict = false, authenticated = false))
        assertTrue(AccessPolicy.needsAuthentication(deviceLocked = false, lockEnabled = false, strict = true, authenticated = false))
        assertFalse(AccessPolicy.needsAuthentication(deviceLocked = false, lockEnabled = true, strict = true, authenticated = true))
    }

    @Test
    fun notificationCommitLeaseAcceptsOnlyFreshVerification() {
        assertTrue(AccessPolicy.canCommitNotification(foreground = true, authenticated = true, deviceLocked = false, verifiedAgoMillis = 0))
        assertTrue(AccessPolicy.canCommitNotification(foreground = true, authenticated = true, deviceLocked = false, verifiedAgoMillis = 119_999))
        assertTrue(AccessPolicy.canCommitNotification(foreground = true, authenticated = true, deviceLocked = false, verifiedAgoMillis = 120_000))
        assertFalse(AccessPolicy.canCommitNotification(foreground = true, authenticated = true, deviceLocked = false, verifiedAgoMillis = 120_001))
        assertFalse(AccessPolicy.canCommitNotification(foreground = true, authenticated = true, deviceLocked = false, verifiedAgoMillis = null))
        assertFalse(AccessPolicy.canCommitNotification(foreground = true, authenticated = true, deviceLocked = false, verifiedAgoMillis = -1))
    }

    @Test
    fun notificationCommitRequiresForegroundUnlockedAndAuthenticated() {
        assertFalse(AccessPolicy.canCommitNotification(foreground = false, authenticated = true, deviceLocked = false, verifiedAgoMillis = 0))
        assertFalse(AccessPolicy.canCommitNotification(foreground = true, authenticated = false, deviceLocked = false, verifiedAgoMillis = 0))
        assertFalse(AccessPolicy.canCommitNotification(foreground = true, authenticated = true, deviceLocked = true, verifiedAgoMillis = 0))
        assertTrue(AccessPolicy.canCommitNotification(foreground = true, authenticated = true, deviceLocked = false, verifiedAgoMillis = 1_000))
    }

    @Test
    fun notificationsStayPrivateOutsideTheUnlockedForeground() {
        assertTrue(AccessPolicy.privateNotifications(foreground = false, authenticated = true, deviceLocked = false))
        assertTrue(AccessPolicy.privateNotifications(foreground = true, authenticated = false, deviceLocked = false))
        assertTrue(AccessPolicy.privateNotifications(foreground = true, authenticated = true, deviceLocked = true))
        assertFalse(AccessPolicy.privateNotifications(foreground = true, authenticated = true, deviceLocked = false))
    }
}
