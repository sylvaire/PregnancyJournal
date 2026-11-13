package cn.yunji.journal.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Crash-recovery stages for a generation switch. Persisted before the irreversible step. */
enum class RestoreStage { PREPARED, SWITCHED, VERIFIED }

@Serializable
private data class RestoreJournalRecord(
    val format: Int = 1,
    val stage: String,
    val targetId: String,
    val previousId: String? = null,
    val snapshotId: String? = null,
    val updatedAt: Long = 0,
)

/**
 * Prevents an interrupted restore from leaving the app pointing at half-built data.
 *
 * - PREPARED: a validated staging generation exists but is not active; startup discards it.
 * - SWITCHED: the pointer already names the new generation; startup must verify and open it.
 * - VERIFIED: the new generation opened successfully; the previous one may be reclaimed later.
 */
class RestoreJournal(private val vaultDir: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val file = File(vaultDir, "restore-journal.json")

    fun read(): RestoreStage? = readRecord()?.let { runCatching { RestoreStage.valueOf(it.stage) }.getOrNull() }
    fun targetId(): String? = readRecord()?.targetId
    fun snapshotId(): String? = readRecord()?.snapshotId

    private fun readRecord(): RestoreJournalRecord? {
        if (!file.exists()) return null
        if (file.length() !in 1..4096) { file.delete(); return null }
        return runCatching { json.decodeFromString<RestoreJournalRecord>(file.readText(Charsets.UTF_8)) }
            .getOrElse { file.delete(); null }
            ?.takeIf { it.format == 1 && runCatching { UUID.fromString(it.targetId).toString() == it.targetId }.getOrDefault(false) }
    }

    fun write(stage: RestoreStage, targetId: String, previousId: String?, snapshotId: String?) {
        require(runCatching { UUID.fromString(targetId).toString() == targetId }.getOrDefault(false))
        val record = RestoreJournalRecord(stage = stage.name, targetId = targetId, previousId = previousId,
            snapshotId = snapshotId, updatedAt = System.currentTimeMillis())
        val text = json.encodeToString(record)
        val temp = File(vaultDir, "restore-journal.pending")
        try {
            RandomAccessFile(temp, "rw").use { out -> out.setLength(0); out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    fun clear() { file.delete() }

    /** What startup must do, expressed without side effects so it can be asserted directly. */
    sealed interface Recovery {
        data object Continue : Recovery
        data class DiscardStaging(val targetId: String) : Recovery
        data class VerifyActive(val targetId: String) : Recovery
        data class RollBack(val toId: String?) : Recovery
    }

    companion object {
        /**
         * @param activeExists whether the pointer's active generation database is currently readable
         */
        fun decide(stage: RestoreStage?, targetId: String?, pointerActiveId: String?, targetExists: Boolean, activeExists: Boolean): Recovery {
            if (stage == null) return Recovery.Continue
            return when (stage) {
                RestoreStage.PREPARED ->
                    // Never switched: the staging generation is not active, drop it and keep the old data.
                    if (pointerActiveId != targetId) Recovery.DiscardStaging(targetId ?: "") else Recovery.VerifyActive(targetId ?: "")
                RestoreStage.SWITCHED ->
                    if (pointerActiveId != targetId) Recovery.Continue
                    else if (targetExists && activeExists) Recovery.VerifyActive(targetId ?: "")
                    else Recovery.RollBack(null)
                RestoreStage.VERIFIED ->
                    if (pointerActiveId == targetId) Recovery.Continue else Recovery.Continue
            }
        }
    }
}
