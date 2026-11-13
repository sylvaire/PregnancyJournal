package cn.yunji.journal.core.database

import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

class MedicationIdentityMigration: AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL("""UPDATE medication_plans SET
            medicationName=COALESCE((SELECT name FROM medications WHERE id=medicationId),''),
            medicationSpecification=COALESCE((SELECT specification FROM medications WHERE id=medicationId),''),
            medicationDosageForm=COALESCE((SELECT dosageForm FROM medications WHERE id=medicationId),'')""")
    }
}
