package cn.yunji.journal.core.security

import cn.yunji.journal.core.backup.RestoreJournal
import cn.yunji.journal.core.backup.RestoreStage
import cn.yunji.journal.core.backup.VaultLayout
import cn.yunji.journal.core.backup.VaultPointer

/** What startup did with any pending restore journal. Exposed for diagnostics and tests. */
data class RestoreStartupOutcome(val action: String, val generationId: String?)

/**
 * Applies the restore journal at startup so an interrupted switch cannot leave the app on half-built data.
 * Must run before any database is opened.
 */
object RestoreStartup {
    fun reconcile(vaultDir: java.io.File, syncDirectory: (java.io.File) -> Unit = { }, verifyActive: ((String)->Unit)? = null): RestoreStartupOutcome {
        val layout = VaultLayout(vaultDir, syncDirectory)
        val journal = RestoreJournal(vaultDir)
        val stage = journal.read()
        if (stage == null) return RestoreStartupOutcome("NONE", layout.activeGenerationId())
        val target = journal.targetId()
        val pointer = runCatching { layout.activeGenerationId() }.getOrNull()
        val targetExists = target != null && runCatching { layout.generationDatabase(target).isFile }.getOrDefault(false)
        val activeExists = runCatching { layout.activeDatabase().isFile }.getOrDefault(false)
        return when (RestoreJournal.decide(stage, target, pointer, targetExists, activeExists)) {
            is RestoreJournal.Recovery.Continue -> RestoreStartupOutcome("CONTINUE", pointer)
            is RestoreJournal.Recovery.DiscardStaging -> {
                // The pointer never moved: the staging generation is not active data, so drop it.
                runCatching { layout.deleteGeneration(target ?: "") }
                journal.clear()
                RestoreStartupOutcome("DISCARDED_STAGING", pointer)
            }
            is RestoreJournal.Recovery.VerifyActive -> {
                if(verifyActive==null)RestoreStartupOutcome("VERIFY_ACTIVE",pointer)
                else try {
                    verifyActive(requireNotNull(target))
                    journal.write(RestoreStage.VERIFIED,target,layout.readPointer()?.previousId,journal.snapshotId())
                    RestoreStartupOutcome("VERIFIED_ACTIVE",target)
                }catch(cancelled: kotlinx.coroutines.CancellationException){throw cancelled}
                catch(_:Exception){rollBack(layout,journal)}
            }
            is RestoreJournal.Recovery.RollBack -> {
                rollBack(layout,journal)
            }
        }
    }
    private fun rollBack(layout: VaultLayout,journal: RestoreJournal): RestoreStartupOutcome {
        val previous=layout.readPointer()?.previousId
        if(previous!=null && layout.generationDatabase(previous).isFile) {
            layout.writePointer(VaultPointer(activeId=previous,updatedAt=System.currentTimeMillis()))
            journal.clear()
            return RestoreStartupOutcome("ROLLED_BACK",previous)
        }
        require(layout.legacyDatabase().isFile) {"恢复验证失败且旧资料库缺失，所有代际已保留"}
        // Do not swallow pointer write errors or erase the only recovery journal on failure.
        layout.writePointer(VaultPointer(activeId=null,updatedAt=System.currentTimeMillis()))
        journal.clear()
        return RestoreStartupOutcome("ROLLED_BACK_TO_LEGACY",null)
    }
}
