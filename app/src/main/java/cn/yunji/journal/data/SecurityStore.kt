package cn.yunji.journal.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Non-medical lock settings. The VMK and biometric wrappers live in Keystore/vault files. */
@Singleton
class SecurityStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("security-state", Context.MODE_PRIVATE)
    @Synchronized fun lockEnabled(): Boolean = prefs.getBoolean("lock-enabled", false)
    @Synchronized fun setLockEnabled(enabled: Boolean) { check(prefs.edit().putBoolean("lock-enabled",enabled).commit()) {"安全设置未保存，请重试"} }
    /** 0 means lock as soon as the app leaves the foreground. */
    @Synchronized fun timeoutMinutes(): Int = prefs.getInt("lock-timeout", 0)
    @Synchronized fun setTimeoutMinutes(minutes: Int) { require(minutes in setOf(0,1,5));check(prefs.edit().putInt("lock-timeout",minutes).commit()) }
    @Synchronized fun strict(): Boolean = prefs.getBoolean("strict-mode", false)
    @Synchronized fun setStrict(enabled: Boolean) { prefs.edit { putBoolean("strict-mode", enabled) } }
    @Synchronized fun lastBackground(): Long = prefs.getLong("last-background", 0L)
    @Synchronized fun setLastBackground(at: Long) { prefs.edit { putLong("last-background", at) } }
    @Synchronized fun lastUnlock(): Long = prefs.getLong("last-unlock", 0L)
    @Synchronized fun setLastUnlock(at: Long) { prefs.edit { putLong("last-unlock", at) } }
}
