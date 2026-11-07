package com.nuvio.tv.ext.livetv.premiumtv

import com.nuvio.tv.ext.livetv.domain.NativeLiveCatalogLoader
import com.nuvio.tv.ext.livetv.domain.NativeLiveResolver
import com.nuvio.tv.livetv.model.LiveChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * PremiumTV: the provider's IPTV list as a native live source that needs NO setup.
 *
 * There is no protocol here — no portal, no session, no crypto, no rate limit. The app reads the
 * playlist we regenerate ourselves (`nuvio-iptv`), which is the same provider list with real
 * `tvg-id`s for the channels that have them and a `url-tvg` header pointing at our guide.
 *
 * This is the SECOND entry of [com.nuvio.tv.ext.livetv.data.NativeLiveSources], beside Magis: the
 * seam that absorbed one provider absorbs the next one without touching the Live TV screen.
 *
 * Design kept from the fork's first PremiumTV integration (nuvio-es): a stable channel id derived
 * from the STREAM URL — not from the list position, so favourites survive a re-fetch — and the
 * per-entry `#EXTVLCOPT` headers read from the list itself instead of a blanket default.
 */
internal object PremiumTvSource {

    const val SOURCE_ID = "premiumtv"
    const val SOURCE_NAME = "PremiumTV"

    /** Marker scheme: PremiumTV entries carry a real URL, so nothing is ever marked. */
    const val MARKER_SCHEME = "premiumtv://native-live"

    /** Our regenerated copy of the provider's list (tvg-ids + `url-tvg`), refreshed every 12 h. */
    const val FEED_URL = "https://esenciable.github.io/nuvio-iptv/esencial.m3u"

    /** The default when an entry declares no user agent of its own (the list's own common case). */
    private const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    fun keyOf(url: String): String = "$SOURCE_ID:${stableId(url)}"

    fun channelFrom(entry: PremiumTvEntry, index: Int): LiveChannel {
        val group = entry.group.ifBlank { "Otros" }
        return LiveChannel(
            key = keyOf(entry.url),
            sourceId = SOURCE_ID,
            sourceName = SOURCE_NAME,
            name = entry.name,
            tvgId = entry.tvgId?.takeIf { it.isNotBlank() },
            tvgName = null,
            logo = entry.logo?.takeIf { it.isNotBlank() },
            groupId = "$SOURCE_ID::$group",
            group = group,
            number = index + 1,
            url = entry.url,
            headers = entry.headers,
            catchup = null,
            drm = null,
        )
    }

    /**
     * The per-entry headers, with the precedence the fork measured:
     *  - `User-Agent`: the entry's own `#EXTVLCOPT:http-user-agent`, else Chrome.
     *  - `Referer`: the entry's own `#EXTVLCOPT:http-referrer`; if it declares none, ONLY the
     *    Samsung TV Plus hosts get `https://www.samsung.com/`. Sending that referrer to every
     *    other channel is the bug the reference plugin had: it breaks channels that validate
     *    their own.
     */
    fun headersFor(url: String, userAgent: String?, referrer: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["User-Agent"] = userAgent?.takeIf { it.isNotBlank() } ?: CHROME_UA
        val declared = referrer?.takeIf { it.isNotBlank() }
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        if (declared != null) {
            out["Referer"] = declared
        } else if (host.contains("jmp2.uk") || host.contains("samsung")) {
            out["Referer"] = "https://www.samsung.com/"
        }
        return out
    }

    /**
     * A short, stable id derived from the URL: the list is re-fetched and reordered constantly, so
     * an index would rename every channel. FNV-1a 64 is used instead of SHA-256 because the same
     * source is shared with the KMP client, where `java.security` does not exist; for ~2.000 URLs
     * the collision odds are negligible.
     */
    private fun stableId(url: String): String {
        var hash = -0x340d631b7bdddcdbL
        for (char in url) {
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }
}

/** One playlist entry: what the `#EXTINF` line declares plus the URL that follows it. */
internal data class PremiumTvEntry(
    val name: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val url: String,
    val headers: Map<String, String>,
)

/**
 * Reads the feed and keeps the last good answer. A FAILED fetch NEVER arms the window, so a
 * transient failure retries on the next call instead of freezing an empty list for half an hour.
 */
internal class PremiumTvFeed(
    private val url: String = PremiumTvSource.FEED_URL,
    private val ttlMs: Long = 30 * 60 * 1000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private var lastGood: List<PremiumTvEntry>? = null
    private var lastGoodAtMs = 0L

    suspend fun load(): List<PremiumTvEntry> {
        val cached = lastGood
        if (cached != null && nowMs() - lastGoodAtMs < ttlMs) return cached

        val text = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "PremiumTV feed HTTP ${response.code}" }
                response.body?.string().orEmpty()
            }
        }.getOrNull()
        val parsed = text?.let { parsePremiumTvM3u(it) }.orEmpty()
        if (parsed.isEmpty()) return cached.orEmpty()

        lastGood = parsed
        lastGoodAtMs = nowMs()
        return parsed
    }
}

/**
 * Parses the playlist. Every line the app does not need to understand is dropped, but the
 * `#EXTVLCOPT` headers that decide whether a channel answers are KEPT: they are the difference
 * between a stream that plays and a 403.
 */
internal fun parsePremiumTvM3u(text: String): List<PremiumTvEntry> {
    val out = ArrayList<PremiumTvEntry>()
    var pending: String? = null
    var userAgent: String? = null
    var referrer: String? = null

    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXTINF") -> {
                pending = line
                userAgent = null
                referrer = null
            }
            line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                userAgent = line.substringAfter('=', "").trim()
            line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ->
                referrer = line.substringAfter('=', "").trim()
            line.startsWith("#") -> Unit
            else -> {
                val extinf = pending
                pending = null
                if (extinf == null) continue
                out += PremiumTvEntry(
                    name = extinf.nameOfEntry().ifBlank { line.substringAfterLast('/') },
                    group = extinf.attribute("group-title").orEmpty(),
                    logo = extinf.attribute("tvg-logo"),
                    tvgId = extinf.attribute("tvg-id"),
                    url = line,
                    headers = PremiumTvSource.headersFor(line, userAgent, referrer),
                )
            }
        }
    }
    return out
}

/** `key="value"` inside an `#EXTINF` line, or null when absent. */
private fun String.attribute(key: String): String? =
    Regex("(?:^|\\s)${Regex.escape(key)}=\"([^\"]*)\"").find(this)?.groupValues?.get(1)

/** The display name: everything after the first comma that is not inside quotes. */
private fun String.nameOfEntry(): String {
    var inQuotes = false
    for (index in indices) {
        val char = this[index]
        when {
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> return substring(index + 1).trim()
        }
    }
    return ""
}

/**
 * The catalog side: the whole list, one [LiveChannel] per entry, grouped by the provider's own
 * `group-title`. A channel appearing twice (the list carries alternate feeds like `[Opc.2]`)
 * keeps its own entry, because its URL — and so its id — is different.
 */
class PremiumTvChannelLoader : NativeLiveCatalogLoader {

    /** One feed per loader in production; tests swap it for one pointed at a fixture. */
    internal var feed: PremiumTvFeed = PremiumTvFeed()

    override val sourceId: String get() = PremiumTvSource.SOURCE_ID

    /** Always configured: the list is public and needs nothing from the user. */
    override val isConfigured: Boolean get() = true

    override suspend fun loadChannels(): List<LiveChannel> {
        val entries = runCatching { feed.load() }.getOrElse { return emptyList() }
        return entries.mapIndexed { index, entry -> PremiumTvSource.channelFrom(entry, index) }
    }
}

/**
 * The playback side. PremiumTV entries already carry their final URL and their headers, so there
 * is nothing to mint at play time: the resolver is the identity. It exists because the seam asks
 * for one, and because a channel picked from a playlist could not be "re-marked" otherwise.
 */
class PremiumTvStreamResolver : NativeLiveResolver {

    override val sourceId: String get() = PremiumTvSource.SOURCE_ID

    override val markerScheme: String get() = PremiumTvSource.MARKER_SCHEME

    override fun reMarker(channel: LiveChannel): LiveChannel = channel

    override suspend fun resolve(channel: LiveChannel): LiveChannel? = channel
}
