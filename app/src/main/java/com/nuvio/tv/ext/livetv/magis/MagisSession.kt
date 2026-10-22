package com.nuvio.tv.ext.livetv.magis

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * The Magis portal's session as seen from the device. Port of the TS reference
 * (`kino-light-addon/src/magis/session.ts`, anonymous branch) plus the recovery logic the
 * gateway learned in production (materialized in `kino-light-main`'s `MagisSession`).
 *
 * The device mints ITS OWN anonymous device (`v3/snToken` → `sn` → `v8/active`); with that
 * there's already catalog and live. What can never be done is making up an `sn`: the portal
 * answers `snToken已经失效`. And two identities over the SAME `sn` mutually kick each other out
 * on every activation, so the minted `sn` gets saved and reused forever.
 */
internal class MagisSession(
    private val portal: MagisPortalLike,
    private val store: MagisSessionStore,
) {

    /** Activating is "read-modify-write" over the store: two coroutines at once would mint two
     *  devices and one would kick the other out. */
    private val lock = Mutex()

    val userId: String get() = store.read()?.userId.orEmpty()
    val userToken: String get() = store.read()?.userToken.orEmpty()

    /** This device's minted device. [MagisPortalClient] reads it on every call. */
    val sn: String get() = store.read()?.sn.orEmpty()

    /**
     * Leaves the device with a usable anonymous session. No-op if there's already a token;
     * otherwise it first tries to reactivate the stored device and only mints a new one when
     * the stored device itself is dead.
     */
    suspend fun ensureAnonymous(): MagisResult<Unit> = lock.withLock {
        if (!store.read()?.userToken.isNullOrBlank()) return@withLock MagisResult.Ok(Unit)

        val storedSn = store.read()?.sn.orEmpty()
        if (storedSn.isNotBlank()) {
            val r = activate(snToken = "")
            if (r is MagisResult.Ok) return@withLock r
            // Only these two codes mean "that device no longer works": anything else (network,
            // portal down) does NOT authorize minting another one — minting extras wastes devices.
            //   aaa100080: invalid snToken/sn.
            //   aaa100082: that device ended up bound to an account and only accepts login.
            if (!(r is MagisResult.PortalError && r.code in INVALID_SN)) return@withLock r
        }
        mintDevice()
    }

    /**
     * Runs [block] and, if the portal rejects it, reauthenticates and retries it ONCE.
     *
     * Reauthenticates on **any** [MagisResult.PortalError], not just the known session-dead
     * codes `aaa100027` ("token caducado") / `aaa100028` ("no logueado"): the portal also kills
     * the session when another device logs in with the same identity, and it announces that
     * with a code that isn't documented. Discriminating finely leaves the saved session dead
     * and EVERY call that follows failing forever; the cost of getting it wrong the other way
     * is one extra call.
     *
     * A [MagisResult.RedError] does NOT reauthenticate: the portal didn't say anything, it's down.
     */
    suspend fun <T> withValidSession(block: suspend () -> MagisResult<T>): MagisResult<T> {
        val first = block()
        if (first !is MagisResult.PortalError) return first
        val reauth = reauthenticate()
        if (reauth !is MagisResult.Ok) return first
        return block()
    }

    // --- inside the lock -------------------------------------------------

    /**
     * `v3/snToken` with a hardware fingerprint → `sn = md5(snToken + salt)` → `v8/active`.
     * The fingerprint is randomized on every minting: the portal hands out one device per
     * fingerprint, and two devices with the same fingerprint would be the same device.
     */
    private suspend fun mintDevice(): MagisResult<Unit> {
        val r = portal.call("v3/snToken", MagisDevice.hardwareFingerprint(), baseFields = false)
        val j = r.getOrNull() ?: return r.map { }
        val snToken = j.optString("snToken").takeIf { it.isNotBlank() }
            ?: return MagisResult.PortalError("snToken_failed", "el portal no devolvió snToken")
        val sn = MagisDevice.snFromSnToken(snToken, portalSn = j.optString("sn"))
        // The `sn` is saved BEFORE activating because it's what the device dict has to carry in
        // that same call (`MagisPortalClient` reads it from the store).
        store.save(StoredSession(userId = "", userToken = "", sn = sn))
        return activate(snToken)
    }

    /** `v8/active`: with an empty [snToken] it reactivates the device that's already saved. */
    private suspend fun activate(snToken: String): MagisResult<Unit> {
        val bean = mapOf(
            "snToken" to snToken,
            "authVersion" to "",
            "authCode" to "",
            "preCode" to "",
            "macAddr" to MagisDevice.FIXED_MAC,
            "reserve1" to "",
            "openNum" to 4,
            "channel" to "default",
            // Only STBs with /system/etc/.UCERT carry these.
            "matadata" to "",
            "signdata" to "",
        )
        val r = portal.call("v8/active", bean, baseFields = false)
        val j = r.getOrNull() ?: return r.map { }
        if (j.optString("userToken").isBlank()) {
            return MagisResult.PortalError("active_sin_token", "activación sin userToken")
        }
        saveFromResponse(j)
        return MagisResult.Ok(Unit)
    }

    private suspend fun reauthenticate(): MagisResult<Unit> {
        // What's saved no longer works; if the token isn't cleared, `ensureAnonymous`'s fast
        // path would take it as good.
        store.read()?.let { previous ->
            store.save(previous.copy(userId = "", userToken = ""))
        }
        // Deliberately OUTSIDE the session mutex: `ensureAnonymous` takes it itself, and
        // kotlinx Mutex is not reentrant — holding it here hung this call forever (the Live
        // TV screen stayed on "Looking for channels..." with no error). The clear-then-mint
        // sequence is safe unheld: the store's read-modify-write is guarded inside
        // `ensureAnonymous`'s lock, and clearing the token early only makes the next
        // `ensureAnonymous` see the same dead session this one already saw.
        return ensureAnonymous()
    }

    private fun saveFromResponse(j: JSONObject) {
        val previous = store.read()
        store.save(
            StoredSession(
                userId = j.optString("userId"),
                userToken = j.optString("userToken"),
                // The device belongs to the DEVICE: neither reactivation nor a kicked session
                // change the `sn`.
                sn = previous?.sn.orEmpty(),
            ),
        )
    }

    private companion object {
        /** Codes that mean the stored DEVICE (not just the token) is unusable. */
        val INVALID_SN = setOf("aaa100080", "aaa100082")
    }
}

/**
 * The minted device's session, as saved on the device. The [userToken] is EPHEMERAL: the
 * portal kills it whenever it wants with no warning, so this is a cache, not a source of
 * truth — the one that detects it died is [MagisSession.withValidSession]. The [sn] is the
 * minted device and is the ONLY thing that's really valuable here: a made-up `sn` doesn't
 * activate (`snToken已经失效`), and two identities over the same `sn` mutually kick each
 * other out of the portal.
 */
internal data class StoredSession(
    val userId: String,
    val userToken: String,
    val sn: String,
)

/** Persistence for [MagisSession], kept as an interface so tests (and alternate stores) can swap it. */
internal interface MagisSessionStore {
    fun save(s: StoredSession)
    fun read(): StoredSession?
}

/**
 * DataStore-backed store, following the fork's livetv persistence conventions
 * (`LiveTvStore`/`ProfileDataStoreFactory` store settings as preference keys). The Magis
 * device identity is DEVICE-scoped, not profile-scoped — two profiles sharing a device must
 * not kick each other out of the portal — so it gets its own standalone DataStore file
 * instead of the per-profile factory.
 *
 * TODO(M2/M3 DI): provide through Hilt (`@Singleton`, `@ApplicationContext`), like
 *  `LiveTvEpgModule` does for the EPG bindings.
 */
internal class DataStoreMagisSessionStore(context: Context) : MagisSessionStore {

    private val dataStore: DataStore<Preferences> = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(FILE) },
    )

    override fun save(s: StoredSession) {
        kotlinx.coroutines.runBlocking {
            dataStore.edit { prefs ->
                prefs[KEY_USER_ID] = s.userId
                prefs[KEY_USER_TOKEN] = s.userToken
                prefs[KEY_SN] = s.sn
            }
        }
    }

    override fun read(): StoredSession? {
        val prefs = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(READ_TIMEOUT_MS) { dataStore.data.first() }
                ?: emptyPreferences()
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
