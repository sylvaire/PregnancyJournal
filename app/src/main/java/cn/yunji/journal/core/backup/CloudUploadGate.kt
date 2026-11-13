package cn.yunji.journal.core.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException

/** One process-wide upload slot per runner, including preparation before a network client exists. */
class CloudUploadGate {
    private val monitor=Any()
    private var paused=false
    private var active: Ticket?=null
    inner class Ticket internal constructor() {
        internal val finished=CompletableDeferred<Unit>()
        internal var cancel: (()->Unit)?=null
        internal var cancelled=false
        fun bind(cancelOperation: ()->Unit) {
            val stop=synchronized(monitor) {
                check(active===this)
                if(cancelled || paused)true else {cancel=cancelOperation;false}
            }
            if(stop){cancelOperation();throw CancellationException("备份已暂停")}
        }
        fun close() {
            synchronized(monitor){if(active===this)active=null}
            finished.complete(Unit)
        }
    }
    fun enter(): Ticket=synchronized(monitor) {
        check(!paused) {"恢复期间已暂停云端备份"}
        check(active==null) {"已有备份任务在进行"}
        Ticket().also {active=it}
    }
    fun cancelActive() {
        val callback=synchronized(monitor){active?.let {it.cancelled=true;it.cancel}}
        callback?.invoke()
    }
    suspend fun pauseAndWait() {
        val ticket=synchronized(monitor){paused=true;active?.also {it.cancelled=true}}
        val callback=synchronized(monitor){ticket?.cancel}
        callback?.invoke()
        ticket?.finished?.await()
    }
    fun resume()=synchronized(monitor){check(active==null);paused=false}
    fun running(): Boolean=synchronized(monitor){active!=null}
}