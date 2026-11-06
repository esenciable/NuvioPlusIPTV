package com.nuvio.tv.ext.livetv.platform

import com.nuvio.tv.ext.livetv.magis.MAGIS_CONFIG_URL
import com.nuvio.tv.ext.livetv.magis.MAGIS_REFRESH_INTERVAL_MS
import com.nuvio.tv.ext.livetv.magis.MAGIS_REFRESH_TICK_MS
import com.nuvio.tv.ext.livetv.magis.MagisConfigProvider
import com.nuvio.tv.ext.livetv.magis.MagisConfigStore
import com.nuvio.tv.ext.livetv.magis.parseMagisConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches `magis-config.json` over OkHttp, validates it all-or-nothing, adopts it into
 * [provider] and persists it as the new last-known-good in [store].
 *
 * Lives outside the portable `magis` package: the contract (validation, ordering, TTLs) is in
 * `magis/MagisRemoteConfig.kt`; what is here is the HTTP transport, which the mobile client
 * replaces with its own.
 *
 * Guarantees (per the doc contract in `odd/tasks/magis-remote-config.md`):
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
