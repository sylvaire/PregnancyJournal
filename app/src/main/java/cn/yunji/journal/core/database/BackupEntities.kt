package cn.yunji.journal.core.database

import androidx.room3.Entity
import androidx.room3.Index

/** Local coordination only. Never exported as medical data or restored into another generation. */
@Entity(tableName="backup_object_pins",primaryKeys=["leaseId","objectId"],indices=[Index("objectId")])
data class BackupObjectPin(val leaseId: String,val objectId: String)

@Entity(tableName="retired_objects",primaryKeys=["objectId"])
data class RetiredObject(val objectId: String)
