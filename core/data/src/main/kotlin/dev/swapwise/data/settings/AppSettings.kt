package dev.swapwise.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Small key-value settings. Anything relational lives in Room instead. */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.dataStore

    /**
     * Random ID created on first use. It identifies this install for rate limiting and event de-duplication,
     * not a person; it is never linked to an account and resets if the app is reinstalled.
     */
    suspend fun installId(): String {
        store.data.first()[INSTALL_ID]?.let { return it }
        var id = ""
        store.edit { prefs -> id = prefs[INSTALL_ID] ?: UUID.randomUUID().toString().also { prefs[INSTALL_ID] = it } }
        return id
    }

    /** null = not asked yet, true = may upload anonymous swap events, false = never upload. */
    val shareStats: Flow<Boolean?> = store.data.map { it[SHARE_STATS] }

    suspend fun setShareStats(allowed: Boolean) {
        store.edit { it[SHARE_STATS] = allowed }
    }

    suspend fun factorsEtag(): String? = store.data.first()[FACTORS_ETAG]

    suspend fun setFactorsEtag(etag: String?) {
        store.edit { prefs -> if (etag == null) prefs.remove(FACTORS_ETAG) else prefs[FACTORS_ETAG] = etag }
    }

    private companion object {
        val INSTALL_ID = stringPreferencesKey("install_id")
        val SHARE_STATS = booleanPreferencesKey("share_stats")
        val FACTORS_ETAG = stringPreferencesKey("factors_etag")
    }
}
