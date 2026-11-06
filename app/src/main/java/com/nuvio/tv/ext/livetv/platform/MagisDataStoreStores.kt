package com.nuvio.tv.ext.livetv.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nuvio.tv.ext.livetv.magis.MagisConfigStore
import com.nuvio.tv.ext.livetv.magis.MagisRuntimeConfig
import com.nuvio.tv.ext.livetv.magis.MagisSessionStore
import com.nuvio.tv.ext.livetv.magis.StoredSession
import com.nuvio.tv.ext.livetv.magis.parseMagisConfig
import com.nuvio.tv.ext.livetv.magis.toJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Android persistence behind the Magis ports. Both live OUTSIDE the portable package:
 * DataStore and `Context` are Android decisions, and the mobile client persists the same state
 * its own way (it has no DataStore).
 *
 * Both follow the fork's livetv persistence conventions (`LiveTvStore`/`ProfileDataStoreFactory`
 * store settings as preference keys), but DELIBERATELY use their own standalone files: the Magis
 * device identity and config are DEVICE-scoped, not profile-scoped — two profiles sharing a
 * device must not kick each other out of the portal.
 */

/**
 * Session store. The [StoredSession.userToken] is a cache the portal can kill at any moment;
 * the `sn` is the minted device and the only thing worth persisting forever.
 */
internal class DataStoreMagisSessionStore(context: Context) : MagisSessionStore {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(FILE) },
    )

    override fun save(s: StoredSession) {
        runBlocking {
            dataStore.edit { prefs ->
                prefs[KEY_USER_ID] = s.userId
                prefs[KEY_USER_TOKEN] = s.userToken
                prefs[KEY_SN] = s.sn
            }
        }
    }

    override fun read(): StoredSession? {
        val prefs = runBlocking {
            withTimeoutOrNull(READ_TIMEOUT_MS) { dataStore.data.first() } ?: emptyPreferences()
        }
        val sn = prefs[KEY_SN] ?: return null
        return StoredSession(
            userId = prefs[KEY_USER_ID].orEmpty(),
            userToken = prefs[KEY_USER_TOKEN].orEmpty(),
            sn = sn,
        )
    }

    private companion object {
        const val FILE = "magis_session"
        const val READ_TIMEOUT_MS = 2_000L
        val KEY_USER_ID = stringPreferencesKey("magis_user_id")
        val KEY_USER_TOKEN = stringPreferencesKey("magis_user_token")
        val KEY_SN = stringPreferencesKey("magis_sn")
    }
}

/**
 * Last-known-good remote config. The stored document is the same JSON the remote serves, so a
 * read passes back through [parseMagisConfig]'s all-or-nothing validation: a corrupt or
 * partially written cache yields `null` and the BuildConfig fallback wins, never a half-valid
 * config.
 */
internal class DataStoreMagisConfigStore(context: Context) : MagisConfigStore {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(FILE) },
    )

    override fun save(config: MagisRuntimeConfig) {
        runBlocking {
            dataStore.edit { prefs -> prefs[KEY_DOC] = config.toJson() }
        }
    }

    override fun read(): MagisRuntimeConfig? {
        val prefs = runBlocking {
            withTimeoutOrNull(READ_TIMEOUT_MS) { dataStore.data.first() } ?: emptyPreferences()
        }
        return prefs[KEY_DOC]?.let { parseMagisConfig(it) }
    }

    private companion object {
        const val FILE = "magis_config"
        const val READ_TIMEOUT_MS = 2_000L
        val KEY_DOC = stringPreferencesKey("magis_config_doc")
    }
}
