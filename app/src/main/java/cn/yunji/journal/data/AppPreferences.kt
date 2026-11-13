package cn.yunji.journal.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

private val Context.preferences by preferencesDataStore("journal-preferences")

interface ActivePregnancyPreferences {
    val activePregnancy: Flow<String?>
    suspend fun selectPregnancy(id: String)
}

/** Only non-medical preferences belong here; sensitive drafts live in the encrypted database. */
@Singleton
class AppPreferences @Inject constructor(@param:ApplicationContext private val context: Context) : ActivePregnancyPreferences {
    private val activeKey = stringPreferencesKey("active-pregnancy-id")
    override val activePregnancy = context.preferences.data.map { it[activeKey] }
    override suspend fun selectPregnancy(id: String) { context.preferences.edit { it[activeKey] = id } }
}
