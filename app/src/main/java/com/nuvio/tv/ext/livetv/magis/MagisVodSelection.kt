package com.nuvio.tv.ext.livetv.magis

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Pure selection heuristics for the Magis VOD flow, ported 1:1 from the VERIFIED plugin core
 * (`esencial-play-providers/lib/flat-magis-core.js`: `magisPortalQuery`, `magisTokens`,
 * `magisSearchItems`, `magisSelectCandidate`, `magisEpisodeId`, `magisScoreMedia`,
 * `magisBestMedia`, `magisIsCfl`, `magisVodCdn`) and the addon's `src/magis/resolve.ts` for the
 * SLB cache. These heuristics are measured against the real portal. The 2026-06 candidate fix
 * (coverage minimum, ±1-year gate, portal-score tie-break) is a VERIFIED parity change applied
 * to the plugin core in parallel — keep the two implementations identical.
 */

/** The portal's series `programType` values (`MAGIS_SERIES_TYPES` in the reference). */
internal val MAGIS_SERIES_TYPES = setOf("teleplay", "series", "variety")

/**
 * The JS reference's `flatStr`: trims strings, coerces numbers, empty for null/missing. The
 * `isNull` guard matters because org.json's `optString` renders `JSONObject.NULL` as `"null"`.
 */
internal fun JSONObject.flatStr(key: String): String =
    if (isNull(key)) "" else optString(key).trim()

/** The reference's `magisObjects`: keep only real objects from a JSON array. */
internal fun magisObjects(array: JSONArray?): List<JSONObject> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

/**
 * Search query for `v3/searchByName`: everything before the first `:`/`,`/`–`/`—`/`|`, unless
 * that head is shorter than 3 characters — then the whole title travels.
 */
internal fun magisPortalQuery(title: String): String {
    val head = title.split(':', ',', '–', '—', '|').first().trim()
    return if (head.length >= 3) head else title.trim()
}

/**
 * Title tokens for candidate scoring: lowercase, NFKD-normalized with combining marks stripped
 * (so `Amélie` matches `amelie`), alphanumeric runs of 3+ characters, deduplicated.
 */
internal fun magisTokens(value: String): Set<String> {
    val normalized = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFKD)
        .replace(Regex("[\\u0300-\\u036f]"), "")
    return Regex("[a-z0-9]{3,}").findAll(normalized).map { it.value }.toSet()
}

/**
 * The search response's item list, unwrapped through every shape the portal has answered with:
   direct `searchItem` → grouped `searchItemList[].itemList` → `assetList` → `list`.
 */
internal fun magisSearchItems(response: JSONObject): List<JSONObject> {
    magisObjects(response.optJSONArray("searchItem")).takeIf { it.isNotEmpty() }?.let { return it }
    val grouped = magisObjects(response.optJSONArray("searchItemList"))
        .flatMap { magisObjects(it.optJSONArray("itemList")) }
    if (grouped.isNotEmpty()) return grouped
    return magisObjects(response.optJSONArray("assetList") ?: response.optJSONArray("list"))
}

/**
 * Candidate selection. PARITY with `esencial-play-providers/lib/flat-magis-core.js`
 * (`magisSelectCandidate`): the plugin received the SAME fix in parallel — these two
 * implementations are the same decision written twice, keep the rule identical.
 *
 * Rules, in order:
 *  1. Filter by `programType` compatible with the media type (falling back to ALL items when
 *     the type filter empties the pool), drop items without `contentId`.
 *  2. YEAR gate: when [year] is known and the candidate carries a parseable `releaseTime`,
 *     require the candidate within ±1 year (release-date drift). An unverifiable year (missing
 *     or garbage `releaseTime`) does NOT reject — the gate only fires on evidence.
 *  3. COVERAGE gate: score by COVERAGE of the requested title (ratio of wanted tokens present,
 *     `name` → `viewPoint` → `alias`) and require [MAGIS_MIN_COVERAGE]. This is the Dune/Dunas
 *     fix: a weak single candidate must be REJECTED so the caller falls back to the original
 *     title — returning nothing beats returning another film.
 *  4. Among the survivors: highest coverage wins, the portal's own relevance `score` breaks
 *     ties. `maxWithOrNull` keeps the FIRST maximal element, the same item the reference's
 *     stable descending sort leaves at index 0.
 *
 * `null` when nothing passes — never the best of a bad lot.
 */
/**
 * Minimum COVERAGE of the requested title a candidate must reach to be accepted. PARITY with
 * the plugin's `MAGIS_MIN_COVERAGE` in `esencial-play-providers/lib/flat-magis-core.js`: the
 * value kept is 0.6 — the plugin's gate — raised from the earlier 0.5 draft when the two
 * implementations were aligned (2026-10-09, option 4). Diff these constants together.
 */
internal const val MAGIS_MIN_COVERAGE = 0.6

/** The candidate's release year out of the portal's ISO `releaseTime` (`2021-10-22` → 2021). */
internal fun magisReleaseYear(releaseTime: String): Int? {
    if (releaseTime.length < 4) return null
    return releaseTime.substring(0, 4).toIntOrNull()?.takeIf { it > 0 }
}

internal fun magisSelectCandidate(
    items: List<JSONObject>,
    title: String,
    isSeries: Boolean,
    year: Int? = null,
): JSONObject? {
    val wanted = magisTokens(title)
    fun displayName(item: JSONObject): String = sequenceOf("name", "viewPoint", "alias")
        .map { item.flatStr(it) }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
    fun coverage(item: JSONObject): Double {
        if (wanted.isEmpty()) return 0.0
        val itemTokens = magisTokens(displayName(item))
        return wanted.count { itemTokens.contains(it) }.toDouble() / wanted.size
    }
    fun yearMatches(item: JSONObject): Boolean {
        if (year == null) return true
        val itemYear = magisReleaseYear(item.flatStr("releaseTime")) ?: return true
        return Math.abs(itemYear - year) <= 1
    }
    val compatible = items.filter { item ->
        val programType = item.flatStr("programType")
        if (isSeries) programType in MAGIS_SERIES_TYPES
        else programType.isEmpty() || programType !in MAGIS_SERIES_TYPES
    }
    val pool = compatible.ifEmpty { items }
    return pool
        .filter { it.flatStr("contentId").isNotEmpty() }
        .filter { yearMatches(it) }
        .mapNotNull { item -> coverage(item).takeIf { it >= MAGIS_MIN_COVERAGE }?.let { item to it } }
        .maxWithOrNull(compareBy({ it.second }, { it.first.optDouble("score", 0.0) }))
        ?.first
}

/**
 * The episode's `contentId` inside `v4/getItemData`'s `assetData.simpleProgramList`: the entry
 * whose `seriesNumber` equals [wanted], or the first one when [wanted] is 0. `null` when the
 * portal has no such episode.
 */
internal fun magisEpisodeId(detail: JSONObject, wanted: Int): String? {
    val assetData = detail.optJSONObject("assetData") ?: JSONObject()
    val episodes = magisObjects(assetData.optJSONArray("simpleProgramList"))
    val selected = if (wanted > 0) {
        episodes.firstOrNull { it.optDouble("seriesNumber", Double.NaN) == wanted.toDouble() }
    } else {
        episodes.firstOrNull()
    }
    return selected?.let { it.flatStr("contentId").takeIf { id -> id.isNotEmpty() } }
}

/**
 * Qué temporada representa este detalle, según la lista del propio portal (port of the addon's
 * `seasonOfDetail` in `kino-light-addon/src/magis/provider.ts`; the same fix is landing in
 * parallel on `esencial-play-providers/lib/flat-magis-core.js` + `flat/magis.js` — these
 * implementations are one decision written twice, keep the rules identical).
 *
 * La entrada de `assetData.sameSeasonSeriesList` cuyo `contentId` es el pedido. Una lista VACÍA
 * significa temporada 1 — una serie de una sola temporada no aparece en su propia lista —, no "no
 * sé cuál es". `null` cuando la lista no identifica al detalle pedido.
 */
internal fun magisSeasonOfDetail(detail: JSONObject, seriesContentId: String): Int? {
    val assetData = detail.optJSONObject("assetData") ?: JSONObject()
    val seasons = magisObjects(assetData.optJSONArray("sameSeasonSeriesList"))
    if (seasons.isEmpty()) return 1
    val own = seasons.firstOrNull { it.flatStr("contentId") == seriesContentId } ?: return null
    val seasonNumber = own.optDouble("seasonNumber", Double.NaN)
    return if (seasonNumber.isNaN()) null else seasonNumber.toInt()
}

/**
 * El contentId de la temporada pedida, tal como lo publica el portal (port of the addon's
 * `contentIdForSeason`, same parity note as [magisSeasonOfDetail]). `null` cuando la lista no
 * trae esa temporada.
 */
internal fun magisContentIdForSeason(detail: JSONObject, wanted: Int): String? {
    val assetData = detail.optJSONObject("assetData") ?: JSONObject()
    val seasons = magisObjects(assetData.optJSONArray("sameSeasonSeriesList"))
    val match = seasons.firstOrNull { it.optDouble("seasonNumber", Double.NaN) == wanted.toDouble() }
    return match?.let { it.flatStr("contentId").takeIf { id -> id.isNotEmpty() } }
}

/**
 * Media score (the reference's `magisScoreMedia`): codec dominates (h264 = 0, else 2), then
 * container (mp4 = 0, else 1), then height as a small tie-break. LOWER wins.
 */
internal fun magisScoreMedia(media: JSONObject): Double {
    val codec = if (media.flatStr("encodeFormat").lowercase() == "h264") 0.0 else 2.0
    val container = if (media.flatStr("videoFormat").lowercase() == "mp4") 0.0 else 1.0
    val height = media.optDouble("height", Double.NaN).takeUnless { it.isNaN() } ?: 0.0
    return codec * 1000 + container * 100 - height / 1000
}

/**
 * The episode's best playable media: every `totalMovieList[].movieList` entry flattened, lowest
 * score wins (first on ties — `minByOrNull` matches the reference's reduce).
 */
internal fun magisBestMedia(episode: JSONObject): JSONObject? {
    val candidates = magisObjects(episode.optJSONArray("totalMovieList"))
        .flatMap { magisObjects(it.optJSONArray("movieList")) }
    return candidates.minByOrNull { magisScoreMedia(it) }
}

/** Exact `sign_type=cfl` inside a querystring (`isCfl` in the reference — split on `&`). */
internal fun magisIsCfl(value: String): Boolean =
    value.split('&').any { it.trim() == "sign_type=cfl" }

/** The VOD CDN choice: `base` (with scheme) and the `auth` querystring for `Content-Auth`. */
internal data class MagisVodCdn(val base: String, val auth: String)

/**
 * The VOD CDN out of a `v14/getSlbInfo` answer (`magisVodCdn` in the reference): the `cdn_list`
 * entry tagged `vod`, first `url_list` entry tagged `free` whose URL carries `sign_type=cfl`
 * (or whose `sign_type` field says so). `main_addr` loses one trailing slash and gains
 * `https://` when the portal sent it bare. `null` when nothing qualifies.
 */
internal fun magisVodCdn(slb: JSONObject): MagisVodCdn? {
    for (cdn in magisObjects(slb.optJSONArray("cdn_list"))) {
        if (cdn.flatStr("tag") != "vod") continue
        for (entry in magisObjects(cdn.optJSONArray("url_list"))) {
            val auth = entry.flatStr("url")
            val isFree = entry.flatStr("tag") == "free"
            val isCfl = magisIsCfl(auth) || entry.flatStr("sign_type") == "cfl"
            if (!isFree || !isCfl) continue
            val address = cdn.flatStr("main_addr").removeSuffix("/")
            if (address.isNotEmpty()) {
                val base = if (address.startsWith("http")) address else "https://$address"
                return MagisVodCdn(base = base, auth = auth)
            }
        }
    }
    return null
}

/** Container for the final URL: `ts` only when `videoFormat` says so, `mp4` otherwise. */
internal fun magisVodExtension(videoFormat: String): String =
    if (videoFormat.lowercase() == "ts") "ts" else "mp4"

/**
 * In-memory SLB cache with the ADDON's exact semantics (`src/magis/resolve.ts` `sessionSlb`):
 * one slot, keyed by the session's token owner, reusable while `now < expiresAt` where
 * `expiresAt = now + invalidTime*1000 - 300_000` (the 5-minute margin keeps an expiring answer
 * out). This cache is half the reason the native VOD resolves with fewer portal calls — the
 * addon paid `getSlbInfo` once per 4 hours, not once per title.
 */
internal class MagisVodSlbCache(private val nowMs: () -> Long = { System.currentTimeMillis() }) {

    private var tokenOwner: String? = null
    private var expiresAt: Long = 0L
    private var slb: JSONObject? = null

    /** The cached slb answer when [tokenOwner] still owns it and it has not expired. */
    fun get(tokenOwner: String): JSONObject? =
        slb?.takeIf { this.tokenOwner == tokenOwner && nowMs() < expiresAt }

    /** Caches [slb] for [tokenOwner]; the TTL comes from the answer's `invalidTime` (300 s fallback). */
    fun put(tokenOwner: String, slb: JSONObject) {
        val ttlSeconds = ttlOf(slb)
        this.tokenOwner = tokenOwner
        this.slb = slb
        expiresAt = nowMs() + ttlSeconds * 1000 - SLB_MARGIN_MS
    }

    private companion object {
        const val SLB_MARGIN_MS = 300_000L
    }
}
