package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.ext.livetv.domain.NativeLiveResolver
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The playback side of the la18hd ("Partidos de Hoy") source: it turns a channel's
 * `la18:<channelId>` marker into the signed HLS URL the fubo18 CDN serves.
 *
 * ### Where the signed URL comes from
 *
 * `https://la18hd.su/vivo/canales.php?stream=<id>` answers an HTML player page whose config
 * script carries the playlist address with its token: `"src":"https://<sub>.fubo18.com/<ch>/mono.m3u8?token=…"`.
 * The page has one gate, measured on 2026-10-26: the request must carry `sec-fetch-dest: iframe`
 * (or an Android user agent — ExoPlayer's own does). The playlist and its MPEG-TS segments answer
 * plain: no referer, no origin, nothing to freeze into a DataSource.
 *
 * ### Why resolution happens here and not in the catalog
 *
 * The token is minted per request and lives FIVE HOURS — long for a signature, short for a URL
 * stored in a channel list that survives restarts. The list keeps the marker; resolution mints
 * seconds before playback. The mint is cached per channel for [CACHE_TTL_MS]: a re-resolve
 * (pause, live-again, reconnect) must not buy a fresh token every time — the old one is still
 * good, and every mint is traffic a small site pays for. Concurrent resolves of the same channel
 * share one mint.
 *
 * ### The channel id travels in the key
 *
 * Like the Magis playCode, the la18 channel id lives in the channel key (`…#<channelId>`), so a
 * RESOLVED channel — whose URL is now the signed playlist — can always be re-marked: the next
 * play mints a fresh token instead of replaying one that has been sitting in the player.
 */
class La18StreamResolver(
    private val now: () -> Long = System::currentTimeMillis,
    private val httpGet: (url: String) -> String = ::download,
    /** The relay's base address and key, when a build ships them (the addon-like path). */
    private val workerBase: String = BuildConfig.LA18_WORKER_BASE.trim().trimEnd('/'),
    private val workerKey: String = BuildConfig.LA18_WORKER_KEY.trim(),
) : NativeLiveResolver {

    override val sourceId: String get() = SOURCE_ID
    override val markerScheme: String get() = MARKER_SCHEME

    /** channelId -> (minted at, playlist URL). Failures are never cached. */
    private val cache = HashMap<String, Pair<Long, String>>()

    /** Guards the cache and the per-channel mint map. */
    private val mutex = Mutex()

    /** One mint per channel at a time: N viewers on the same row buy one page fetch. */
    private val mints = HashMap<String, Mutex>()

    override fun reMarker(channel: LiveChannel): LiveChannel {
        val id = idOf(channel) ?: return channel
        return channel.copy(url = MARKER_SCHEME + id, headers = emptyMap())
    }

    override suspend fun resolve(channel: LiveChannel): LiveChannel? {
        val id = idOf(channel) ?: return null
        // The relay is the addon-like path: the URL is a direct https address the player opens
        // like any playlist channel's — the worker mints from its own egress and replays the
        // segments, so the viewer's IP reputation never touches the CDN. No cache, no expiry:
        // the worker re-mints under its hour and the URL stays valid across re-resolves.
        if (workerBase.isNotBlank() && workerKey.isNotBlank()) {
            return channel.copy(url = "$workerBase/la18/stream/$id.m3u8?key=$workerKey", headers = emptyMap())
        }
        val url = mint(id) ?: return null
        return channel.copy(url = url, headers = emptyMap())
    }

    private suspend fun mint(id: String): String? {
        val at = now()
        mutex.withLock {
            cache[id]?.takeIf { at - it.first < CACHE_TTL_MS }?.let { return it.second }
            mints.getOrPut(id) { Mutex() }
        }
        return mints.getValue(id).withLock {
            // Re-check inside: another coroutine may have minted while we waited.
            now().let { n -> cache[id]?.takeIf { n - it.first < CACHE_TTL_MS } }?.let { return it.second }
            val body = runCatching { httpGet(pageUrl(id)) }.getOrNull() ?: return null
            val url = playlistUrlOf(body) ?: return null
            cache[id] = now() to url
            url
        }
    }

    companion object {
        const val SOURCE_ID = "la18"
        const val MARKER_SCHEME = "la18:"
        const val PAGE_URL = "https://la18hd.su/vivo/canales.php"

        /**
         * The token lives five hours, measured; the cache is a fraction of that — long enough to
         * spare the site on re-resolves, short enough that a half-day-old pause mints fresh.
         */
        const val CACHE_TTL_MS = 60 * 60_000L

        /** The one gate the page has, measured: the request must look like the site's own iframe. */
        const val GATE_HEADER = "sec-fetch-dest"
        const val GATE_VALUE = "iframe"

        /** The player page for one channel id. */
        fun pageUrl(channelId: String): String = "$PAGE_URL?stream=${channelId.trim()}"

        /**
         * The channel id in a channel key (`…#<id>` after the la18 event part) or a placeholder
         * URL. Null for keys that carry none — a key built before the id joined, or another feed's.
         */
        fun idOf(channel: LiveChannel): String? {
            if (channel.sourceId != SOURCE_ID) return null
            channel.url.takeIf { it.startsWith(MARKER_SCHEME) }?.let {
                return it.removePrefix(MARKER_SCHEME).takeIf { s -> s.isNotBlank() }
            }
            return channel.key.substringAfterLast('#', "").takeIf { s ->
                s.isNotEmpty() && s.none { c -> c == '/' || c == '?' }
            }
        }

        /**
         * The signed playlist inside the page's config script. Only the exact shape counts — an
         * `https` URL ending in `.m3u8` with a `token` query, quoted as JSON — because the page
         * also carries ad scripts that mention any address they like.
         */
        fun playlistUrlOf(page: String): String? =
            SRC_CONFIG.find(page)?.groupValues?.get(1)?.takeIf { it.startsWith("https://") }

        private val SRC_CONFIG = Regex(
            """"src"\s*:\s*"(https://[^"]+?\.m3u8\?token=[^"]+)"""",
        )

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        fun download(url: String): String {
            val req = Request.Builder().url(url)
                .header(GATE_HEADER, GATE_VALUE)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
                )
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                return r.body?.string() ?: throw java.io.IOException("empty answer")
            }
        }
    }
}
