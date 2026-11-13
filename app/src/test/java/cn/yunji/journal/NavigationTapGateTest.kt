package cn.yunji.journal

import cn.yunji.journal.feature.NavigationTapGate
import org.junit.Assert.*
import org.junit.Test

class NavigationTapGateTest {
    @Test fun fastTabThenDifferentPageIsAcceptedButDuplicatePageTapIsSuppressed() {
        var now=0L;val gate=NavigationTapGate {now}
        assertTrue(gate.accept("tab" to "profiles"))
        now=10_000_000
        assertTrue(gate.accept("push" to "backup"))
        now=20_000_000
        assertFalse(gate.accept("push" to "backup"))
        now=330_000_000
        assertTrue(gate.accept("push" to "backup"))
    }
    @Test fun repeatedBackKeepsProtectionWhileDifferentActionsRemainResponsive() {
        var now=1L;val gate=NavigationTapGate {now}
        assertTrue(gate.accept("back"));now+=10_000_000
        assertFalse(gate.accept("back"))
        assertTrue(gate.accept("tab" to "today"))
        assertTrue(gate.accept("tab" to "profiles"))
    }
}
