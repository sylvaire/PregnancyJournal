package cn.yunji.journal.core.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/** A user-authored reminder, anchored to a local date and time. */
@Serializable
@Entity(tableName = "custom_reminders", indices = [Index(value = ["pregnancyId", "startDate"])], foreignKeys = [
    ForeignKey(entity = PregnancyEntity::class, parentColumns = ["id"], childColumns = ["pregnancyId"]),
])
data class CustomReminderEntity(
    @PrimaryKey val id: String,
    val pregnancyId: String,
    val title: String,
    val note: String = "",
    val startDate: String,
    val localTime: String,
    val repeat: String = "NONE",
    val endDate: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val deletedAt: Long? = null,
)
