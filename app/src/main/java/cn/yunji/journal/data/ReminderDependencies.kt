package cn.yunji.journal.data

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderDependencies {
    fun scheduler(): ReminderScheduler
    fun notifier(): ReminderNotifier
}
