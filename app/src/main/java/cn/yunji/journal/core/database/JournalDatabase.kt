package cn.yunji.journal.core.database

import android.content.Context
import androidx.room3.Database
import androidx.room3.AutoMigration
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.withWriteTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import java.io.File

@Database(entities = [PregnancyEntity::class, PregnancyDateRevisionEntity::class, VisitEntity::class,
    ChangeEntity::class, DraftEntity::class, ImportJobEntity::class, AttachmentEntity::class, DeletionBatchEntity::class, MedicationEntity::class, MedicationPlanEntity::class, MedicationOccurrenceEntity::class, MedicationIntakeEntity::class,
    MetricDefinitionEntity::class, MeasurementEntity::class, MeasurementValueEntity::class, JournalEntryEntity::class, CustomReminderEntity::class, BackupObjectPin::class, RetiredObject::class],
    version = 15, autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10,spec = MedicationIdentityMigration::class), AutoMigration(from = 10, to = 11), AutoMigration(from = 11, to = 12), AutoMigration(from = 12, to = 13), AutoMigration(from = 13, to = 14), AutoMigration(from = 14, to = 15)], exportSchema = true)
abstract class JournalDatabase : RoomDatabase() {
    internal val mutationMutex = Mutex()
    internal var restoreSeal: String? = null
    abstract fun journalDao(): JournalDao

    companion object {
        fun open(context: Context, file: File, key: ByteArray): JournalDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder<JournalDatabase>(context, file.absolutePath)
                .setDriver(SQLCipherDriver(key.copyOf(), null, null))
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        }
    }
}

/** Keep transaction entry ordered across foreground edits and background cleanup on all drivers. */
internal suspend fun <T> JournalDatabase.journalWrite(block: suspend () -> T): T =
    mutationMutex.withLock {
        check(restoreSeal==null) {"正在恢复资料，当前修改未保存，请稍后重试"}
        withWriteTransaction { block() }
    }


/** A seal rejects queued/future mutations of this old resource graph, rather than replaying stale edits. */
internal class RestoreWriteSeal(private val db: JournalDatabase,private val token: String) {
    private var retired=false
    /** After closing the old resource graph, leave it permanently sealed. */
    suspend fun retire() {db.mutationMutex.withLock {retired=true}}
    suspend fun abort() {
        db.mutationMutex.withLock {
            if(!retired && db.restoreSeal==token)db.restoreSeal=null
        }
    }
}

internal suspend fun JournalDatabase.sealForRestore(expectedSequence: Long): RestoreWriteSeal = mutationMutex.withLock {
    require(expectedSequence>=0)
    check(restoreSeal==null) {"已有恢复操作正在进行"}
    check(journalDao().currentSequence()==expectedSequence) {"安全备份后资料已有修改，请重新制作安全备份"}
    val token=java.util.UUID.randomUUID().toString()
    restoreSeal=token
    RestoreWriteSeal(this,token)
}
