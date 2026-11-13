package cn.yunji.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cn.yunji.journal.data.CloudCleanupStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** The orphan ledger must survive process restarts, otherwise a reboot could erase a grace period. */
class CloudCleanupStoreTest {
    private lateinit var context: Context
    private lateinit var store: CloudCleanupStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("cloud-cleanup-ledger", Context.MODE_PRIVATE).edit().clear().commit()
        store = CloudCleanupStore(context)
    }

    @After fun tearDown() {
        context.getSharedPreferences("cloud-cleanup-ledger", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun orphanTimestampsPersistAcrossInstances() {
        val id = UUID.randomUUID().toString()
        store.record(mapOf(id to 1_700_000_000_000))
        // A new instance stands in for a restarted process.
        val reloaded = CloudCleanupStore(context).orphanedSince()
        assertEquals(1_700_000_000_000, reloaded[id])
    }

    @Test fun recordMergesInsteadOfReplacingAndKeepsTheOriginalTimestamp() {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        store.record(mapOf(first to 100))
        store.record(mapOf(second to 200))
        assertEquals(mapOf(first to 100L, second to 200L), store.orphanedSince())
        // Re-recording an existing object must not extend or reset its grace period.
        store.record(mapOf(first to 999))
        assertEquals(100L, store.orphanedSince()[first])
    }

    @Test fun forgetRemovesOnlyNamedEntriesAndRemainsIdempotent() {
        val keep = UUID.randomUUID().toString()
        val drop = UUID.randomUUID().toString()
        store.record(mapOf(keep to 100, drop to 200))
        store.forget(listOf(drop))
        assertEquals(mapOf(keep to 100L), store.orphanedSince())
        store.forget(listOf(drop, UUID.randomUUID().toString()))
        assertEquals(mapOf(keep to 100L), store.orphanedSince())
    }

    @Test fun emptyOperationsDoNotDisturbExistingEntries() {
        val id = UUID.randomUUID().toString()
        store.record(mapOf(id to 42))
        store.record(emptyMap())
        store.forget(emptyList())
        assertEquals(mapOf(id to 42L), store.orphanedSince())
        store.clear()
        assertTrue(store.orphanedSince().isEmpty())
    }

    @Test fun ledgerIsBoundedSoALongLivedVaultCannotGrowForever() {
        // 5200 entries at distinct timestamps: the newest 5000 must be retained.
        val entries = (1..5200).associate { UUID.randomUUID().toString() to it.toLong() }
        store.record(entries)
        val stored = store.orphanedSince()
        assertEquals(5000, stored.size)
        assertTrue("The newest timestamps must be kept", stored.values.min() > 200)
        assertTrue("The oldest timestamps must be dropped", stored.values.max() == 5200L)
    }
}
