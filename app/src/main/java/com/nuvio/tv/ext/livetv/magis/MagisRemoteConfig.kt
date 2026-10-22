package com.nuvio.tv.ext.livetv.magis

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The single source of truth for the Magis portal's rotating values (`magis-config.json` at the
 * root of `esenciable/esencial-providers`, served by raw). The plugin reads the SAME file, so
 * plugin and fork cannot drift apart; a rotation is fixed with one push, no new APK.
 *
 * Port of the doc contract (`odd/tasks/magis-remote-config.md`):
 *
 *  - **Validation is ALL-OR-NOTHING**: every field must be valid or the whole document is
 *    rejected and the previous config is kept — never a half-valid config.
 *  - **Resolution order, first valid wins**: last-known-good on disk -> remote fetch in the
 *    background (NON-blocking) -> `BuildConfig` as the final fallback. Startup never waits for
 *    the network.
 *  - A failed fetch clears NOTHING and arms NO "attempted" TTL: only a valid result counts, and
 *    a *successful* refresh runs at most once every few hours per process.
 */
internal const val MAGIS_CONFIG_URL =
    "https://raw.githubusercontent.com/esenciable/esencial-providers/main/magis-config.json"

/** A *successful* remote refresh happens at most once per process every few hours. */
internal const val MAGIS_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L

/** How often the background loop wakes up to consider a refresh (failed fetches retry here). */
internal const val MAGIS_REFRESH_TICK_MS = 15 * 60 * 1000L

// --- parsing / validation ----------------------------------------------------

/**
 * Parses and validates `magis-config.json`. ALL-OR-NOTHING: returns `null` (rejecting the whole
 * document) if ANY field is invalid —
 *   `schema == 1`; `hosts` non-empty with no scheme and no `/` in any entry; `appId` non-blank;
 *   `apkVersion` and `apkVerHeader` digits only; `spkgVer` non-blank;
 *   `threeDesKeyHex` matching `^[0-9a-f]{48}$`.
 * The caller keeps the previous config on `null`.
 */
internal fun parseMagisConfig(json: String): MagisRuntimeConfig? {
    val doc = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (doc.optInt("schema", -1) != 1) return null

    val hosts = mutableListOf<String>()
    val hostArray = doc.optJSONArray("hosts") ?: return null
    if (hostArray.length() == 0) return null
    for (i in 0 until hostArray.length()) {
        val host = hostArray.optString(i).trim()
        if (host.isEmpty() || host.contains("://") || host.contains('/')) return null
        hosts.add(host)
    }

    val appId = doc.optString("appId").trim()
    if (appId.isEmpty()) return null

    val apkVersion = doc.optString("apkVersion").trim()
    if (!apkVersion.matches(DIGITS)) return null

    val apkVerHeader = doc.optString("apkVerHeader").trim()
    if (!apkVerHeader.matches(DIGITS)) return null

    val spkgVer = doc.optString("spkgVer").trim()
    if (spkgVer.isEmpty()) return null

    val key = doc.optString("threeDesKeyHex").trim()
    if (!key.matches(KEY_48_HEX)) return null

    return MagisRuntimeConfig(
        hosts = hosts,
        appId = appId,
        apkVersion = apkVersion,
        apkVerHeader = apkVerHeader,
        spkgVer = spkgVer,
        threeDesKeyHex = key,
    )
}

private val DIGITS = Regex("""\d+""")
private val KEY_48_HEX = Regex("""^[0-9a-f]{48}$""")

internal fun MagisRuntimeConfig.toJson(): String = JSONObject().apply {
    put("schema", 1)
    put("hosts", JSONArray(hosts))
    put("appId", appId)
    put("apkVersion", apkVersion)
    put("apkVerHeader", apkVerHeader)
    put("spkgVer", spkgVer)
    put("threeDesKeyHex", threeDesKeyHex)
}.toString()

// --- holder ------------------------------------------------------------------

/**
 * The best-known Magis config at any moment, held in a `@Volatile` field so the portal, the
 * crypto and the live client read it PER USE and a rotation lands without an app restart.
 * Initialized (before any network wait) with the disk cache or, failing that, `BuildConfig`.
 */
internal class MagisConfigProvider(initial: MagisRuntimeConfig) {

    @Volatile
    var current: MagisRuntimeConfig = initial
        private set

    fun update(config: MagisRuntimeConfig) {
        current = config
    }
}

/**
 * Startup resolution, first-valid-wins and NEVER waiting for the network: the disk cache (last
 * known good) beats the `BuildConfig` fallback. The remote fetch only happens later, in the
 * background ([MagisRemoteConfig]).
 */
internal fun MagisConfigStore.readOr(fallback: MagisRuntimeConfig): MagisRuntimeConfig = read() ?: fallback

// --- persistence -------------------------------------------------------------

/** Persistence for the last-known-good remote config. Kept as an interface so tests swap it. */
internal interface MagisConfigStore {
    fun save(config: MagisRuntimeConfig)
    fun read(): MagisRuntimeConfig?
}

/**
 * DataStore-backed store for the last-known-good config, following the fork's livetv
 * persistence conventions and the device-scoped standalone-file pattern of
 * [DataStoreMagisSessionStore] (`magis_session`): the Magis config is DEVICE-scoped, not
 * profile-scoped, so it gets its own DataStore file — deliberately NOT the session's.
 *
 * The stored document is the same JSON the remote serves, so a read passes back through
 * [parseMagisConfig]'s all-or-nothing validation: a corrupt or partially written cache yields
 * `null` and the `BuildConfig` fallback wins, never a half-valid config.
 */
internal class DataStoreMagisConfigStore(context: Context) : MagisConfigStore {

    private val dataStore: DataStore<Preferences> = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile(FILE) },
    )

    override fun save(config: MagisRuntimeConfig) {
        kotlinx.coroutines.runBlocking {
            dataStore.edit { prefs -> prefs[KEY_DOC] = config.toJson() }
        }
    }

    override fun read(): MagisRuntimeConfig? {
        val prefs = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(READ_TIMEOUT_MS) { dataStore.data.first() }
                ?: emptyPreferences()
        }
        return prefs[KEY_DOC]?.let { parseMagisConfig(it) }
    }

    private companion object {
        const val FILE = "magis_config"
        const val READ_TIMEOUT_MS = 2_000L
        val KEY_DOC = stringPreferencesKey("magis_config_doc")
    }
}

// --- background refresh ------------------------------------------------------

/**
 * Fetches `magis-config.json` over OkHttp, validates it all-or-nothing, adopts it into
 * [provider] and persists it as the new last-known-good in [store].
 *
 * Guarantees (per the doc contract):
 *  - fetching starts only from the background loop [start]: startup never waits for the network;
 *  - a FAILED fetch (network error, non-2xx, invalid JSON, rejected document) keeps the current
 *    config untouched and arms NO TTL — only a VALID result arms [MAGIS_REFRESH_INTERVAL_MS];
 *  - an adopted config equal to the current one is not re-saved to disk.
 */
internal class MagisRemoteConfig(
    private val provider: MagisConfigProvider,
    private val store: MagisConfigStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val url: String = MAGIS_CONFIG_URL,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private val inFlight = Mutex()

    /** Armed ONLY by a valid result: a failed fetch leaves it alone so the next tick retries. */
    @Volatile
    private var lastGoodFetchMs = 0L

    /**
     * Runs the non-blocking refresh loop in [scope]: the first fetch fires immediately
     * (asynchronously), then a tick every [MAGIS_REFRESH_TICK_MS]. A valid refresh is gated to
     * once per [MAGIS_REFRESH_INTERVAL_MS]; a failed one retries on the next tick.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                refreshIfNeeded()
                delay(MAGIS_REFRESH_TICK_MS)
            }
        }
    }

    /**
     * One refresh attempt, at most once per interval for VALID results. Safe to call
     * concurrently: the second caller sees the first's armed timestamp and returns.
     */
    suspend fun refreshIfNeeded() {
        if (nowMs() - lastGoodFetchMs < MAGIS_REFRESH_INTERVAL_MS) return
        inFlight.withLock {
            if (nowMs() - lastGoodFetchMs < MAGIS_REFRESH_INTERVAL_MS) return@withLock
            // Failed fetch or rejected document: nothing armed, nothing cleared. The previous
            // config (disk cache or BuildConfig fallback) stays exactly as it was. (No non-local
            // `return` from inside `withLock` after a suspension point: that shape hangs the
            // coroutine — observed as a 5s test timeout with the `?: return` variant.)
            val raw = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrNull() }
            val config = raw?.let { parseMagisConfig(it) }
            if (config == null) return@withLock
            lastGoodFetchMs = nowMs()
            if (config != provider.current) {
                provider.update(config)
                runCatching { store.save(config) }
            }
        }
    }

    private fun fetch(): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "magis-config HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
}
