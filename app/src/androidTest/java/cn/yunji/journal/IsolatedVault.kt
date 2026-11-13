package cn.yunji.journal

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import cn.yunji.journal.core.security.AccessState
import cn.yunji.journal.core.security.DeviceKeyStore
import cn.yunji.journal.core.security.VaultRuntime
import cn.yunji.journal.core.security.VaultSession
import cn.yunji.journal.data.ReminderNotifier
import cn.yunji.journal.data.ReminderStore
import cn.yunji.journal.data.SecurityStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyStore
import java.time.Clock

/**
 * Task-6: an isolated on-device vault that survives the instrumentation process.
 *
 * Data lives under filesDir (not cache) so a second `am instrument` run reads exactly what the
 * previous process left behind. The wrapper keeps noBackupFilesDir / SharedPreferences namespaced
 * away from the real app vault, so process-recovery tests never touch real user data.
 */
internal class IsolatedVault private constructor(private val base: Context, private val token: String) : AutoCloseable {
    val root: File = File(base.filesDir, "task6-recovery-$token").apply { mkdirs() }

    val context: Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("$token-$name", mode)
    }

    val keys: DeviceKeyStore = DeviceKeyStore.isolated(context, token)

    val runtime: VaultRuntime = VaultRuntime(
        context, keys, VaultSession(), Clock.systemUTC(),
        ReminderStore(context), ReminderNotifier(context),
        AccessState(context, SecurityStore(context), keys),
    )

    suspend fun repository() = runtime.resources().repository

    /** Reopening the same token reuses the Keystore wrapper and the on-disk vault. */
    fun reopen(): IsolatedVault = IsolatedVault(base, token)

    override fun close() {
        runBlocking { runtime.revoke() }
    }

    companion object {
        /** Clean slate for a phase-1 run: removes the vault, Keystore alias and namespaced prefs. */
        fun fresh(base: Context, token: String): IsolatedVault {
            deleteDeviceState(base, token)
            return IsolatedVault(base, token)
        }

        fun existing(base: Context, token: String) = IsolatedVault(base, token)

        fun marker(base: Context, token: String): File =
            File(base.filesDir, "task6-markers/$token.marker").apply { parentFile?.mkdirs() }

        private fun deleteDeviceState(base: Context, token: String) {
            File(base.filesDir, "task6-recovery-$token").deleteRecursively()
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    deleteEntry("${base.packageName}.vault-wrap.test-$token.v1")
                }
            }
            listOf("security-state", "reminder-state", "cloud-scheduler", "cloud-upload-state")
                .forEach { base.deleteSharedPreferences("$token-$it") }
        }
    }
}
