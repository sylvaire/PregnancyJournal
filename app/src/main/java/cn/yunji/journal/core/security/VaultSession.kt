package cn.yunji.journal.core.security

import javax.inject.Inject
import javax.inject.Singleton

/** Holds the decrypted VMK only for the currently unlocked process; never persisted. */
@Singleton
class VaultSession @Inject constructor() {
    @Volatile private var current: VaultMaterial? = null
    fun material(): VaultMaterial? = current
    fun require(): VaultMaterial = current ?: error("资料尚未解锁")
    @Synchronized fun set(material: VaultMaterial) { if(current!==material)current?.master?.fill(0);current = material }
    @Synchronized fun clear() { val old=current;current=null;old?.master?.fill(0) }
}
