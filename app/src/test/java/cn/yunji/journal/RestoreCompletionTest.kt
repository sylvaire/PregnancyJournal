package cn.yunji.journal
import cn.yunji.journal.core.backup.RestoreCompletion
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
class RestoreCompletionTest {
    private fun fixture(block: (File)->Unit) {val dir=Files.createTempDirectory("completion").toFile();try {block(dir)}finally {dir.deleteRecursively()}}
    @Test fun successfulEffectsAreNotRepeatedOnNextStartup()=fixture {dir->
        var calls=0;RestoreCompletion(dir){}.run {calls++};RestoreCompletion(dir){}.run {calls++};assertEquals(1,calls)
    }
    @Test fun failedEffectsLeaveNoReadyAndAreRetried()=fixture {dir->
        var calls=0
        assertTrue(runCatching {RestoreCompletion(dir){}.run {calls++;throw java.io.IOException("Synthetic interruption")}}.isFailure)
        assertFalse(File(dir,"device-settings.ready").exists())
        RestoreCompletion(dir){}.run {calls++};assertEquals(2,calls)
    }
    @Test fun stalePendingCannotMasqueradeAsCompleted()=fixture {dir->
        File(dir,"device-settings.pending").writeText("partial")
        var called=false;RestoreCompletion(dir){}.run {called=true}
        assertTrue(called);assertFalse(File(dir,"device-settings.pending").exists())
    }
    @Test fun malformedReadyFailsClosedWithoutReapplyingUserSettings()=fixture {dir->
        File(dir,"device-settings.ready").writeText("invalid")
        var called=false;assertTrue(runCatching {RestoreCompletion(dir){}.run {called=true}}.isFailure);assertFalse(called)
    }
}