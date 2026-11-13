package cn.yunji.journal

import cn.yunji.journal.core.backup.CloudUploadGate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CloudUploadGateTest {
    @Test fun admissionAlreadyExclusiveDuringPreparation() {
        val gate=CloudUploadGate();val ticket=gate.enter()
        assertTrue(gate.running());assertTrue(runCatching {gate.enter()}.isFailure)
        ticket.close();assertFalse(gate.running());gate.enter().close()
    }
    @Test fun restorePauseCancelsAndWaitsForFinallyCleanup()=runBlocking {
        val gate=CloudUploadGate();val ticket=gate.enter();var cancelled=false
        ticket.bind {cancelled=true}
        val pause=async(start=CoroutineStart.UNDISPATCHED){gate.pauseAndWait()}
        assertTrue(cancelled);assertFalse(pause.isCompleted)
        assertTrue(runCatching {gate.enter()}.isFailure)
        ticket.close();withTimeout(1000){pause.await()}
        assertTrue(runCatching {gate.enter()}.isFailure)
        gate.resume();gate.enter().close()
    }
    @Test fun preparationCannotBindNetworkAfterRestorePause()=runBlocking {
        val gate=CloudUploadGate();val ticket=gate.enter();var cancelled=false
        val pause=async(start=CoroutineStart.UNDISPATCHED){gate.pauseAndWait()}
        assertTrue(runCatching {ticket.bind {cancelled=true}}.exceptionOrNull() is CancellationException)
        assertTrue(cancelled);assertFalse(pause.isCompleted)
        ticket.close();withTimeout(1000){pause.await()}
    }
    @Test fun userCancellationBeforeClientBindingIsNotLost() {
        val gate=CloudUploadGate();val ticket=gate.enter();var cancelled=false
        gate.cancelActive()
        assertTrue(runCatching {ticket.bind {cancelled=true}}.exceptionOrNull() is CancellationException)
        assertTrue(cancelled)
        assertTrue(runCatching {gate.resume()}.isFailure)
        ticket.close();gate.resume();gate.enter().close()
    }
}