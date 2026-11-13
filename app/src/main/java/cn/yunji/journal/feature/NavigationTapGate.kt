package cn.yunji.journal.feature

/** Suppress repeated identical actions without dropping a deliberate move to a different destination. */
internal class NavigationTapGate(private val nanoTime: ()->Long=System::nanoTime) {
    private var previous: Any?=null
    private var at=0L
    fun accept(action: Any): Boolean {
        val now=nanoTime()
        if(previous==action && now-at in 0 until 320_000_000L)return false
        previous=action;at=now
        return true
    }
}
