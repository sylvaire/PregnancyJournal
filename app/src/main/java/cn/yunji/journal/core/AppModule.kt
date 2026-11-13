package cn.yunji.journal.core

import android.content.Context
import cn.yunji.journal.core.database.JournalDatabase
import cn.yunji.journal.core.security.DeviceKeyStore
import cn.yunji.journal.core.security.VaultMaterial
import cn.yunji.journal.data.ActivePregnancyPreferences
import cn.yunji.journal.data.AppPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides fun recovery(@ApplicationContext context: Context,runtime: cn.yunji.journal.core.security.VaultRuntime): cn.yunji.journal.data.RecoverySettings =
        cn.yunji.journal.data.RecoverySettings(context,runtime.resources().material)
    @Provides fun preferences(preferences: AppPreferences): ActivePregnancyPreferences = preferences
    @Provides @Singleton fun clock(): Clock = Clock.systemDefaultZone()
    @Provides fun material(runtime: cn.yunji.journal.core.security.VaultRuntime): VaultMaterial = runtime.resources().material
    @Provides fun database(runtime: cn.yunji.journal.core.security.VaultRuntime): JournalDatabase = runtime.resources().database
    @Provides fun repository(runtime: cn.yunji.journal.core.security.VaultRuntime): cn.yunji.journal.data.JournalRepository = runtime.resources().repository
    @Provides fun importer(runtime: cn.yunji.journal.core.security.VaultRuntime): cn.yunji.journal.data.DocumentImporter = runtime.resources().importer
    @Provides fun reminders(runtime: cn.yunji.journal.core.security.VaultRuntime): cn.yunji.journal.data.ReminderScheduler = runtime.resources().reminders
    @Provides fun cloud(@ApplicationContext context: Context,runtime: cn.yunji.journal.core.security.VaultRuntime,clock: Clock): cn.yunji.journal.data.CloudConnectionRepository {
        val material=runtime.resources().material
        return cn.yunji.journal.data.CloudConnectionRepository(cn.yunji.journal.data.CloudConnectionStore(context,material),material.id,clock)
    }
    @Provides @Singleton fun uploadStatus(@ApplicationContext context: Context): cn.yunji.journal.data.CloudUploadStatusStore =
        cn.yunji.journal.data.CloudUploadStatusStore(context)
    @Provides @Singleton fun cloudSchedulerStore(@ApplicationContext context: Context): cn.yunji.journal.data.CloudSchedulerStore =
        cn.yunji.journal.data.CloudSchedulerStore(context)
    @Provides @Singleton fun cloudCleanupStore(@ApplicationContext context: Context): cn.yunji.journal.data.CloudCleanupStore =
        cn.yunji.journal.data.CloudCleanupStore(context)
    @Provides @Singleton fun cloudCleanup(@ApplicationContext context: Context,runtime: cn.yunji.journal.core.security.VaultRuntime,
        clock: Clock,scheduler: cn.yunji.journal.data.CloudSchedulerStore,
        ledger: cn.yunji.journal.data.CloudCleanupStore): cn.yunji.journal.data.CloudCleanupAccess =
        cn.yunji.journal.data.CloudCleanupRunner(context,runtime,clock,scheduler,ledger)
    @Provides @Singleton fun cloudBackup(@ApplicationContext context: Context,runtime: cn.yunji.journal.core.security.VaultRuntime,
        clock: Clock,status: cn.yunji.journal.data.CloudUploadStatusStore,
        scheduler: cn.yunji.journal.data.CloudSchedulerStore): cn.yunji.journal.data.CloudBackupAccess =
        cn.yunji.journal.data.CloudBackupRunner(context,runtime,clock,status,scheduler)
    @Provides @Singleton fun restore(@ApplicationContext context: Context,runtime: cn.yunji.journal.core.security.VaultRuntime,
        cloud: cn.yunji.journal.data.CloudBackupAccess): cn.yunji.journal.feature.RestoreAccess =
        cn.yunji.journal.data.LocalRestoreAccess(context,runtime,cloud as cn.yunji.journal.data.RestoreCloudControl)
    @Provides @Singleton fun export(@ApplicationContext context: Context,runtime: cn.yunji.journal.core.security.VaultRuntime,
        clock: Clock): cn.yunji.journal.data.ExportAccess =
        cn.yunji.journal.data.ExportRunner(context,runtime,clock)}
