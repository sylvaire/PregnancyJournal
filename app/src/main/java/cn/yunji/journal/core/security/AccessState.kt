package cn.yunji.journal.core.security

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import cn.yunji.journal.data.SecurityStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessState private constructor(private val context: Context,private val settings: SecurityStore,private val keys: DeviceKeyStore,private val elapsed: ()->Long) {
    @Inject constructor(@ApplicationContext context: Context,settings: SecurityStore,keys: DeviceKeyStore):this(context,settings,keys,SystemClock::elapsedRealtime)
    @Volatile var foreground=false
        private set
    @Volatile var authenticated=false
        private set
    private var backgroundAt: Long?=null
    @Volatile private var verifiedAt: Long?=null
    @Synchronized fun resumed() {
        backgroundAt?.let { at ->
            if (AccessPolicy.locksOnReturn(keys.strict(), settings.timeoutMinutes(), elapsed() - at)) authenticated = false
        }
        foreground=true;backgroundAt=null
        if(deviceLocked())authenticated=false
    }
    @Synchronized fun stopped() {foreground=false;backgroundAt=elapsed();if(AccessPolicy.locksOnBackground(keys.strict(), settings.timeoutMinutes()))authenticated=false}
    @Synchronized fun unlock(verified: Boolean=false) {authenticated=true;foreground=true;backgroundAt=null;if(verified)verifiedAt=elapsed()}
    @Synchronized fun lock() {authenticated=false;verifiedAt=null}
    fun canCommitNotification()=AccessPolicy.canCommitNotification(foreground,authenticated,deviceLocked(),verifiedAt?.let {elapsed()-it})
    fun deviceLocked()=context.getSystemService(KeyguardManager::class.java).isDeviceLocked
    fun needsAuthentication()=AccessPolicy.needsAuthentication(deviceLocked(),settings.lockEnabled(),keys.strict(),authenticated)
    fun privateNotifications(): Boolean=AccessPolicy.privateNotifications(foreground,authenticated,deviceLocked())
    internal companion object {fun testing(context: Context,settings: SecurityStore,keys: DeviceKeyStore,elapsed: ()->Long)=AccessState(context,settings,keys,elapsed)}
}
