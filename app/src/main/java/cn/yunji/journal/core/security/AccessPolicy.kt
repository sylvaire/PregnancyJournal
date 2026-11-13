package cn.yunji.journal.core.security

/**
 * Pure lock-timing and authorization rules used by [AccessState].
 *
 * Kept free of Android state so the complete lock / return / notification matrix can be
 * asserted on the JVM (device tests cover the platform wiring). [AccessState] only supplies
 * the platform facts: foreground, device lock, configured timeout and strict-mode metadata.
 */
internal object AccessPolicy {
    const val NOTIFICATION_COMMIT_LEASE_MILLIS = 120_000L

    /** Lock as soon as the app leaves the foreground (immediate lock or strict mode). */
    fun locksOnBackground(strict: Boolean, timeoutMinutes: Int): Boolean =
        strict || timeoutMinutes == 0

    /** Whether a return after [awayMillis] away must re-authenticate. */
    fun locksOnReturn(strict: Boolean, timeoutMinutes: Int, awayMillis: Long): Boolean =
        locksOnBackground(strict, timeoutMinutes) ||
            awayMillis >= timeoutMinutes.toLong() * 60_000L

    fun needsAuthentication(
        deviceLocked: Boolean,
        lockEnabled: Boolean,
        strict: Boolean,
        authenticated: Boolean,
    ): Boolean = deviceLocked || (lockEnabled || strict) && !authenticated

    /** Notifications may only commit a change right after a fresh foreground verification. */
    fun canCommitNotification(
        foreground: Boolean,
        authenticated: Boolean,
        deviceLocked: Boolean,
        verifiedAgoMillis: Long?,
    ): Boolean = foreground && authenticated && !deviceLocked &&
        verifiedAgoMillis != null && verifiedAgoMillis in 0..NOTIFICATION_COMMIT_LEASE_MILLIS

    fun privateNotifications(
        foreground: Boolean,
        authenticated: Boolean,
        deviceLocked: Boolean,
    ): Boolean = !foreground || deviceLocked || !authenticated
}
