package com.nuvio.tv.ext.livetv.magis

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Facade over the Magis portal for LIVE TV: categories, channel pages, `v4/startPlayLive` +
 * `v14/getSlbInfo` resolution and the signed headers the CDN demands.
 *
 * PORT NOTE: verbatim from the fork's `ext/livetv/magis/MagisLiveClient.kt` (itself the port of
 * the TS reference `kino-light-addon/src/magis/live.ts`), with one adaptation: the shared
 * `slbRequestParams` and `ttlOf` live in [MagisVodSlbParams] here, and the live root constant is
 * that file's [MAGIS_LIVE_ROOT] — the portal validates the body, not the caller, so both stay
 * identical in behaviour.
 *
 * Sessions are anonymous device sessions minted by [MagisSession]; the TS reference measured
 * (2026-09-16) that anonymous activation resolves live channels fine (playCode, license, 3 CDNs).
 * No credentials, no proxy, no server: everything below runs on the device.
 *
 * There is an in-memory catalog cache because the portal has a minimum pace between calls and
 * the full catalog is several pages: with no cache, opening the channel list would cost seconds
 * every time. Resolution is NOT cached: `main_addr` rotates on every response, and an old host
 * answers 403.
 */
internal class MagisLiveClient(
    private val portal: MagisPortalLike,
    private val session: MagisSession,
    /** Runtime config, read PER USE: the remote config can swap values while the process lives. */
    private val configProvider: () -> MagisRuntimeConfig,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : MagisLiveCatalogApi, MagisLivePlaybackApi {

    private val lock = Mutex()
    private var categoriesCache: List<MagisLiveCategory> = emptyList()
    private var categoriesExpireAt = 0L
    private val channelsCache = mutableMapOf<String, Pair<Long, List<MagisLiveChannel>>>()

    /**
     * The portal's live categories (`getNextColumns` over the `masnew_live` root — the path is
     * literally unversioned, measured from the working client).
     */
    override suspend fun categories(): List<MagisLiveCategory> {
        lock.withLock {
            if (categoriesCache.isNotEmpty() && nowMs() < categoriesExpireAt) return categoriesCache
        }
        val response = session.withValidSession {
            portal.call(
                path = "getNextColumns",
                bean = mapOf(
                    "columnCode" to MAGIS_LIVE_ROOT,
                    "pageNum" to 1,
                    // 200 and not 30: the portal cuts off at the requested size without saying
                    // there's more (with 30 it returned 30 of the 38 real categories).
                    "pageSize" to 200,
                    "version" to "",
                ),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val list = response.getOrNull()?.optJSONArray("recommendList") ?: return emptyList()
        val output = mutableListOf<MagisLiveCategory>()
        list.forEachObject { c ->
            val id = c.opt("columnId")?.toString()?.toIntOrNull() ?: return@forEachObject
            val name = c.optString("name").trim().takeIf { it.isNotBlank() } ?: return@forEachObject
            output.add(MagisLiveCategory(id = id, name = name))
        }
        if (output.isNotEmpty()) {
            lock.withLock {
                categoriesCache = output
                categoriesExpireAt = nowMs() + CATALOG_TTL_MS
            }
        }
        return output
    }

    /**
     * One page of a category's channels. Page caching is keyed by `categoryId:page` because the
     * categories hold different channel lists. One session serves several pages in a row
     * (measured in the TS reference: one session per CATEGORY, not per page).
     */
    override suspend fun channels(categoryId: Int, page: Int): List<MagisLiveChannel> {
        val cacheKey = "$categoryId:$page"
        lock.withLock {
            channelsCache[cacheKey]?.takeIf { nowMs() < it.first }?.let { return it.second }
        }
        val response = session.withValidSession {
            portal.call(
                path = "v6/getLiveData",
                bean = mapOf(
                    "columnId" to categoryId,
                    "pageNum" to page,
                    "pageSize" to PAGE_SIZE,
                    "dataVersion" to "",
                    "expireTimeStr" to "",
                ),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val list = response.getOrNull()?.optJSONArray("channelList") ?: return emptyList()
        val channels = mutableListOf<MagisLiveChannel>()
        list.forEachObject { c ->
            val code = c.optString("channelCode").takeIf { it.isNotBlank() } ?: return@forEachObject
            channels.add(
                MagisLiveChannel(
                    code = code,
                    name = c.optString("name").trim(),
                    number = c.opt("channelNumber")?.toString()?.toIntOrNull() ?: 0,
                    // The portal delivers live channel art as `posterUrl`; there is no logoList
                    // on getLiveData's channel objects (measured in the TS reference).
                    logo = c.optString("posterUrl"),
                ),
            )
        }
        if (channels.isNotEmpty()) {
            lock.withLock { channelsCache[cacheKey] = (nowMs() + CATALOG_TTL_MS) to channels }
        }
        return channels
    }

    /**
     * Resolves [channelCode] into a signed playback session: `v4/startPlayLive` (playCode +
     * license) then `v14/getSlbInfo` (the CDNs and each one's `authBase`). Two calls, NOT
     * cached: `main_addr` rotates on every response and an old host answers 403.
     *
     * Portal/network failures surface as [MagisResult] errors through the
     * [MagisSession.withValidSession] chain; callers that want the reason use this method, and
     * callers that only want a URL use [resolve].
     */
    override suspend fun resolveDetailed(channelCode: String): MagisResult<MagisChannelSession> {
        val sessionResult = session.ensureAnonymous()
        if (sessionResult !is MagisResult.Ok) return sessionResult.asError()

        val play = session.withValidSession {
            portal.call(
                path = "v4/startPlayLive",
                bean = mapOf("channelCode" to channelCode, "columnId" to 0, "type" to "1"),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val playJson = play.getOrNull() ?: return play.asError()
        val signal = signalFrom(playJson)
            ?: return MagisResult.PortalError(NO_ADDRESSES, "el portal no dio direcciones para $channelCode")
        val slb = session.withValidSession {
            portal.call(
                path = "v14/getSlbInfo",
                // The CHANNEL's code, not the playCode: the portal returns THAT signal's hosts.
                bean = slbRequestParams(configProvider().apkVersion, liveCodes = listOf(channelCode)),
                userId = session.userId,
                userToken = session.userToken,
            )
        }
        val slbJson = slb.getOrNull() ?: return slb.asError()
        val cdns = liveCdns(slbJson)
        if (cdns.isEmpty()) {
            return MagisResult.PortalError(NO_CDN, "no hay entrada CDN cfl de vivo para $channelCode")
        }

        val chosen = cdns.first()
        val channelSession = MagisChannelSession(
            host = chosen.host,
            authBase = chosen.authBase,
            license = signal.second,
            // The playlist path NEVER takes the getSlbInfo path suffix of main_addr, and it MUST
            // take the playCode: the CDN serves the signal under a name that isn't always the
            // channel code (measured: `cyx-RCNHD` → `cyx-2EF7E10E40C1ac19D6A9F3ED4CD2`). Asking
            // for the channel's code would be asking for a signal the license doesn't cover: 401.
            playCode = signal.first.ifBlank { channelCode },
            ttlSeconds = ttlOf(slbJson),
            cdns = cdns,
        )
        // Same validation the TS reference does on the gateway's response: these fields travel
        // as-is against the real CDN, so an empty one here would show up as an opaque 401/403
        // far from where it originated.
        if (channelSession.license.isBlank()) {
            return MagisResult.PortalError(NO_LICENSE, "el portal dio $channelCode sin licencia")
        }
        if (channelSession.token.isBlank()) {
            return MagisResult.PortalError(NO_TOKEN, "el authBase de $channelCode no trae token=<32 hex>")
        }
        return MagisResult.Ok(channelSession)
    }

    /** Like [resolveDetailed] but `null` on any failure, for callers that only want a URL. */
    suspend fun resolve(channelCode: String): MagisChannelSession? =
        (resolveDetailed(channelCode) as? MagisResult.Ok)?.data

    /**
     * The playlist URL for a resolved channel: `http://<bare host>/live/<playCode>.m3u8`.
     * The CDN host's `main_addr` carries a PATH (`/v3/youshi/`) that must NOT travel here.
     */
    override fun playlistUrl(cdn: MagisChannelSession): String = "http://${cdn.host}/live/${cdn.playCode}.m3u8"

    /**
     * Signed headers for one origin request against [cdn]. A FRESH moment/sign2 per call — the
     * signature window is short, so callers must not reuse a header set across a long session.
     *
     * The 32-hex `token` inside `authBase` is signed with [TweakedMd5.signO3]; the resulting
     * `sign2` travels in the `Content-Auth` header together with the same authBase query.
     */
    override fun signedHeaders(cdn: MagisChannelSession): Map<String, String> {
        val moment = nowMs()
        val sign2 = TweakedMd5.signO3(cdn.token, moment)
        return mapOf(
            "Content-Auth" to
                "${cdn.authBase}&sign2_method=sign_o3&instance=0&start_moment=$moment&sign2=$sign2",
            "Content-License" to cdn.license,
            "User-Agent" to LIVE_USER_AGENT,
            "App" to configProvider().appId,
            "App-Version" to LIVE_APP_VERSION,
            "X-Buffer" to "0",
        )
    }

    internal companion object {
        /** One getLiveData page's size; the catalog loader pages until a page comes back short. */
        const val PAGE_SIZE = 100
        const val CATALOG_TTL_MS = 5 * 60 * 1000L

        /** The CDN identity headers the real app sends (TS reference `live.ts`). */
        const val LIVE_USER_AGENT = "Ranger/4.9.4-17294ac0"
        const val LIVE_APP_VERSION = "49902"

        const val NO_ADDRESSES = "live_no_addresses"
        const val NO_CDN = "live_no_cfl_cdn"
        const val NO_LICENSE = "live_no_license"
        const val NO_TOKEN = "live_no_cfl_token"
    }
}

// --- models -----------------------------------------------------------------

data class MagisLiveCategory(val id: Int, val name: String)

data class MagisLiveChannel(
    val code: String,
    val name: String,
    val number: Int,
    val logo: String,
)

data class MagisChannelCdn(
    /** Bare host of the cfl CDN that owns the authBase we signed. */
    val host: String,
    /** Query string (no scheme/path) that travels in the signed `Content-Auth` header. */
    val authBase: String,
)

data class MagisChannelSession(
    val host: String,
    val authBase: String,
    /** License from the SAME liveAddressList entry that produced the playCode — never crossed. */
    val license: String,
    /** What the signal is called ON THE CDN: not always the channel code. */
    val playCode: String,
    /** Seconds the slb answer remains usable. */
    val ttlSeconds: Long,
    /** Every servable cfl CDN in portal order: fallback authority for playlist/segment failover. */
    val cdns: List<MagisChannelCdn>,
) {
    /** The 32-hex token inside [authBase]; empty when the portal gave an unusable authBase. */
    internal val token: String
        get() = Regex("""token=([0-9A-Fa-f]{32})""").find(authBase)?.groupValues?.get(1).orEmpty()
}

// --- response translation (pure, ported 1:1 from the TS/Kotlin references) --------------

/**
 * playCode and the license come from THE SAME entry, never crossed: the license authorizes ONE
 * signal, and pairing it with another's playCode is exactly the pair the CDN rejects with 401.
 * The original app (decompiled `sources/h8/v3.java`) compares the same way: first COMPLETE
 * entry, else the first useful license.
 */
internal fun signalFrom(play: JSONObject): Pair<String, String>? {
    val list = play.optJSONArray("liveAddressList") ?: return null
    var firstLicense = ""
    for (i in 0 until list.length()) {
        val entry = list.optJSONObject(i) ?: continue
        val license = entry.optString("license")
        val playCode = entry.optString("playCode")
        if (firstLicense.isEmpty()) firstLicense = license
        if (playCode.isNotBlank() && license.isNotBlank()) return playCode to license
    }
    return if (firstLicense.isEmpty()) null else "" to firstLicense
}

/**
 * ALL servable live CDNs, each with its OWN authBase: the token travels inside that url, and
 * signing with one against another's host is the same 401 failure mode. Exact
 * `sign_type=cfl`, not a similar-looking prefix (`cflx`): `url` is a loose querystring with no
 * scheme, so it's split on `?`/`&` rather than parsed as a URL.
 */
internal fun liveCdns(slb: JSONObject): List<MagisChannelCdn> {
    val output = mutableListOf<MagisChannelCdn>()
    val cdnList = slb.optJSONArray("cdn_list") ?: return output
    for (i in 0 until cdnList.length()) {
        val cdn = cdnList.optJSONObject(i) ?: continue
        if (cdn.optString("tag") != "live") continue
        val urlList = cdn.optJSONArray("url_list") ?: continue
        val host = bareHost(cdn.optString("main_addr"))
        if (host.isEmpty()) continue
        for (j in 0 until urlList.length()) {
            val url = urlList.optJSONObject(j)?.optString("url").orEmpty()
            val isCfl = url.substringAfterLast('?').split('&').any { it.trim() == "sign_type=cfl" }
            if (isCfl) output.add(MagisChannelCdn(host = host, authBase = url))
        }
    }
    return output
}

/** Just `main_addr`'s host, with no scheme or path. */
internal fun bareHost(mainAddr: String): String = mainAddr
    .removePrefix("https://")
    .removePrefix("http://")
    .substringBefore('/')

/** Walks a `JSONArray` of objects without writing the index by hand every place. */
internal inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(action)
}
