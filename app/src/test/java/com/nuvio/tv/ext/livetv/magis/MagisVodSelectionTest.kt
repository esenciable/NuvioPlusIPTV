package com.nuvio.tv.ext.livetv.magis

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure selection heuristics for the Magis VOD flow, ported 1:1 from the VERIFIED plugin core
 * (`esencial-play-providers/lib/flat-magis-core.js`): tokens, candidate selection, episode id,
 * media scoring and the VOD CDN choice. Nothing here touches the network.
 */
class MagisVodSelectionTest {

    // --- magisPortalQuery --------------------------------------------------------

    @Test
    fun `portalQuery takes the head before separators`() {
        assertEquals("Coco", magisPortalQuery("Coco: Un mundo mejor"))
        assertEquals("Breaking Bad", magisPortalQuery("Breaking Bad, temporadas 1-5"))
        assertEquals("Casa de Papel", magisPortalQuery("Casa de Papel – parte 2"))
        assertEquals("Casa de Papel", magisPortalQuery("Casa de Papel — parte 2"))
        assertEquals("One Piece", magisPortalQuery("One Piece | Wano"))
    }

    @Test
    fun `portalQuery falls back to the whole title when the head is shorter than 3`() {
        // "El" is 2 chars → the FULL trimmed title travels (reference: `String(title).trim()`).
        assertEquals("El Chapo", magisPortalQuery("El Chapo"))
        assertEquals("Yo: diagnóstico", magisPortalQuery("Yo: diagnóstico"))
    }

    // --- magisTokens -------------------------------------------------------------

    @Test
    fun `tokens are lowercase alphanumeric runs of 3 or more`() {
        assertEquals(setOf("coco", "mundo"), magisTokens("Coco y el Mundo"))
        // Words shorter than 3 are dropped.
        assertEquals(setOf("elsa"), magisTokens("yo, el, Elsa"))
    }

    @Test
    fun `tokens strip diacritics via NFKD`() {
        assertEquals(setOf("amelie", "paris"), magisTokens("Amélie à París"))
    }

    // --- magisSearchItems --------------------------------------------------------

    @Test
    fun `searchItems prefers the direct searchItem list`() {
        val response = JSONObject()
            .put("searchItem", JSONArray().put(JSONObject().put("contentId", "a")))
            .put("searchItemList", JSONArray().put(JSONObject().put("itemList", JSONArray())))
        val items = magisSearchItems(response)
        assertEquals(1, items.size)
        assertEquals("a", items[0].optString("contentId"))
    }

    @Test
    fun `searchItems flattens grouped searchItemList`() {
        val response = JSONObject().put(
            "searchItemList",
            JSONArray()
                .put(JSONObject().put("itemList", JSONArray().put(JSONObject().put("contentId", "g1"))))
                .put(JSONObject().put("itemList", JSONArray().put(JSONObject().put("contentId", "g2")))),
        )
        val items = magisSearchItems(response)
        assertEquals(listOf("g1", "g2"), items.map { it.optString("contentId") })
    }

    @Test
    fun `searchItems falls back to assetList and then list`() {
        val asset = JSONObject().put("assetList", JSONArray().put(JSONObject().put("contentId", "x")))
        assertEquals(listOf("x"), magisSearchItems(asset).map { it.optString("contentId") })
        val list = JSONObject().put("list", JSONArray().put(JSONObject().put("contentId", "y")))
        assertEquals(listOf("y"), magisSearchItems(list).map { it.optString("contentId") })
    }

    @Test
    fun `searchItems is empty for an empty response`() {
        assertTrue(magisSearchItems(JSONObject()).isEmpty())
    }

    // --- magisSelectCandidate ----------------------------------------------------

    private fun item(contentId: String, name: String, programType: String = "") = JSONObject()
        .put("contentId", contentId)
        .put("name", name)
        .put("programType", programType)

    @Test
    fun `selectCandidate picks the item with the most title-token hits`() {
        val items = listOf(
            item("c1", "Coco"),                       // 1 hit (coco)
            item("c2", "Coco y el Mundo de Coco"),    // 3 hits (coco, mundo... "coco" once — tokens dedupe)
            item("c3", "Otra cosa"),
        )
        val best = magisSelectCandidate(items, "Coco y el Mundo", isSeries = false)
        assertEquals("c2", best?.optString("contentId"))
    }

    @Test
    fun `selectCandidate filters series types when resolving a series`() {
        val items = listOf(
            item("movie1", "Breaking Bad", programType = "movie"),
            item("series1", "Breaking Bad", programType = "series"),
            item("teleplay1", "Breaking Bad", programType = "teleplay"),
            item("variety1", "Breaking Bad", programType = "variety"),
        )
        val best = magisSelectCandidate(items, "Breaking Bad", isSeries = true)
        assertEquals("series1", best?.optString("contentId"))
    }

    @Test
    fun `selectCandidate rejects series types when resolving a movie`() {
        val items = listOf(
            item("series1", "Coco", programType = "series"),
            item("movie1", "Coco", programType = "movie"),
            item("untyped", "Coco"),
        )
        val best = magisSelectCandidate(items, "Coco", isSeries = false)
        // Both compatible items tie on score; `maxByOrNull` (like the reference's stable sort)
        // keeps the FIRST maximal — `movie1` travels first in the pool.
        assertEquals("movie1", best?.optString("contentId"))
    }

    @Test
    fun `selectCandidate falls back to all items when none is type-compatible`() {
        val items = listOf(item("s1", "Coco", programType = "series"))
        val best = magisSelectCandidate(items, "Coco", isSeries = false)
        assertEquals("s1", best?.optString("contentId"))
    }

    @Test
    fun `selectCandidate ignores items without contentId`() {
        val items = listOf(
            JSONObject().put("name", "Coco"), // no contentId
            item("c1", "Coco"),
        )
        val best = magisSelectCandidate(items, "Coco", isSeries = false)
        assertEquals("c1", best?.optString("contentId"))
    }

    @Test
    fun `selectCandidate returns null on empty or unusable results`() {
        assertNull(magisSelectCandidate(emptyList(), "Coco", isSeries = false))
        assertNull(magisSelectCandidate(listOf(JSONObject().put("name", "Coco")), "Coco", isSeries = false))
    }

    @Test
    fun `selectCandidate scores with name then viewPoint then alias`() {
        val byAlias = listOf(item("c1", ""), JSONObject().put("contentId", "c2").put("alias", "Coco"))
        assertEquals("c2", magisSelectCandidate(byAlias, "Coco", isSeries = false)?.optString("contentId"))
        val byViewPoint = listOf(item("c1", ""), JSONObject().put("contentId", "c2").put("viewPoint", "Coco"))
        assertEquals("c2", magisSelectCandidate(byViewPoint, "Coco", isSeries = false)?.optString("contentId"))
    }

    // --- coverage / year / portal-score selection rules (parity with flat-magis-core.js) ---

    private fun itemWith(contentId: String, name: String, releaseTime: String? = null, score: Int? = null, programType: String = "") =
        JSONObject().put("contentId", contentId).put("name", name).put("programType", programType).apply {
            releaseTime?.let { put("releaseTime", it) }
            score?.let { put("score", it) }
        }

    @Test
    fun `selectCandidate rejects a candidate sharing only one word of the title`() {
        // The Dune/Dunas bug: the portal's ONLY candidate shares a single token with the
        // requested title — coverage 1/3 is under the minimum, so it must be REJECTED (null),
        // never accepted blindly because it is the only one.
        val items = listOf(itemWith("tuareg", "Tuaregs, los guerreros de las dunas"))
        assertNull(magisSelectCandidate(items, "Los Puentes de Madison", isSeries = false))
    }

    @Test
    fun `selectCandidate rejects a candidate from the wrong year and accepts within one`() {
        val wrong = listOf(itemWith("tuareg", "Dune", releaseTime = "2013-08-03"))
        assertNull(magisSelectCandidate(wrong, "Dune", isSeries = false, year = 1984))
        // ±1 year of release-date drift is accepted.
        val close = listOf(itemWith("d84", "Dune", releaseTime = "1983-12-01"))
        assertEquals("d84", magisSelectCandidate(close, "Dune", isSeries = false, year = 1984)?.optString("contentId"))
        val otherSide = listOf(itemWith("d85", "Dune", releaseTime = "1985-01-07"))
        assertEquals("d85", magisSelectCandidate(otherSide, "Dune", isSeries = false, year = 1984)?.optString("contentId"))
    }

    @Test
    fun `selectCandidate does not apply a year the portal cannot verify`() {
        // No releaseTime on the candidate: the year gate cannot fire, selection proceeds.
        val noDate = listOf(itemWith("nodate", "Dune"))
        assertEquals("nodate", magisSelectCandidate(noDate, "Dune", isSeries = false, year = 1984)?.optString("contentId"))
        // No requested year: no gate at all.
        val wrong = listOf(itemWith("tuareg", "Dune", releaseTime = "2013-08-03"))
        assertEquals("tuareg", magisSelectCandidate(wrong, "Dune", isSeries = false, year = null)?.optString("contentId"))
        // A releaseTime the parser cannot read is treated like a missing one.
        val garbage = listOf(itemWith("g", "Dune", releaseTime = "no-se-que"))
        assertEquals("g", magisSelectCandidate(garbage, "Dune", isSeries = false, year = 1984)?.optString("contentId"))
    }

    @Test
    fun `selectCandidate breaks coverage ties with the portal score`() {
        val items = listOf(
            itemWith("weak", "Dune", score = 5),
            itemWith("strong", "Dune", score = 99),
        )
        assertEquals("strong", magisSelectCandidate(items, "Dune", isSeries = false)?.optString("contentId"))
    }

    @Test
    fun `selectCandidate prefers coverage over the portal score`() {
        val items = listOf(
            itemWith("full", "Coco y el Mundo", score = 1),
            itemWith("partial", "Coco", score = 99),
        )
        assertEquals("full", magisSelectCandidate(items, "Coco y el Mundo", isSeries = false)?.optString("contentId"))
    }

    @Test
    fun `selectCandidate returns null when every candidate is rejected, not the best of a bad lot`() {
        // All wrong year: nothing usable → null, so the caller can try the original title.
        val wrongYears = listOf(
            itemWith("a", "Dune", releaseTime = "2013-08-03"),
            itemWith("b", "Dune", releaseTime = "2021-10-22"),
        )
        assertNull(magisSelectCandidate(wrongYears, "Dune", isSeries = false, year = 1984))
        assertNull(magisSelectCandidate(emptyList(), "Dune", isSeries = false, year = 1984))
    }

    @Test
    fun `selectCandidate still filters by programType for a series`() {
        val items = listOf(
            itemWith("movie", "Dune", programType = "movie", score = 99),
            itemWith("series", "Dune", programType = "series", score = 1),
        )
        assertEquals("series", magisSelectCandidate(items, "Dune", isSeries = true)?.optString("contentId"))
    }

    @Test
    fun `selectCandidate enforces the plugin's 0_6 coverage minimum`() {
        // Parity: MAGIS_MIN_COVERAGE matches the plugin's MAGIS_MIN_COVERAGE (0.6) in
        // flat-magis-core.js — 2/4 tokens (0.5) is UNDER the gate, 3/4 (0.75) passes.
        val half = listOf(itemWith("half", "Guerra Galaxias Amor"))
        assertNull(magisSelectCandidate(half, "Guerra de las Galaxias El Imperio", isSeries = false))
        val threeQuarters = listOf(itemWith("threeq", "Guerra Galaxias Imperio Amor"))
        assertEquals(
            "threeq",
            magisSelectCandidate(threeQuarters, "Guerra de las Galaxias El Imperio", isSeries = false)?.optString("contentId"),
        )
    }

    @Test
    fun `releaseTime year parses the ISO date head and rejects garbage`() {
        assertEquals(2021, magisReleaseYear("2021-10-22"))
        assertEquals(2013, magisReleaseYear("2013-08-03"))
        assertEquals(1984, magisReleaseYear("1984"))
        assertNull(magisReleaseYear(""))
        assertNull(magisReleaseYear("no-se-que"))
    }

    // --- magisEpisodeId ----------------------------------------------------------

    @Test
    fun `episodeId picks the episode by seriesNumber`() {
        val detail = JSONObject().put(
            "assetData",
            JSONObject().put(
                "simpleProgramList",
                JSONArray()
                    .put(JSONObject().put("seriesNumber", 1).put("contentId", "ep1"))
                    .put(JSONObject().put("seriesNumber", 2).put("contentId", "ep2")),
            ),
        )
        assertEquals("ep2", magisEpisodeId(detail, wanted = 2))
        assertEquals("ep1", magisEpisodeId(detail, wanted = 0))
    }

    @Test
    fun `episodeId returns null when the episode is missing`() {
        val detail = JSONObject().put(
            "assetData",
            JSONObject().put(
                "simpleProgramList",
                JSONArray().put(JSONObject().put("seriesNumber", 1).put("contentId", "ep1")),
            ),
        )
        assertNull(magisEpisodeId(detail, wanted = 5))
        assertNull(magisEpisodeId(JSONObject(), wanted = 1))
    }

    // --- media scoring / best media ----------------------------------------------

    @Test
    fun `scoreMedia prefers h264 over other codecs and mp4 over other containers`() {
        val h264mp4 = JSONObject().put("encodeFormat", "h264").put("videoFormat", "mp4").put("height", 1080)
        val h264ts = JSONObject().put("encodeFormat", "h264").put("videoFormat", "ts").put("height", 1080)
        val h265mp4 = JSONObject().put("encodeFormat", "h265").put("videoFormat", "mp4").put("height", 2160)
        assertTrue(magisScoreMedia(h264mp4) < magisScoreMedia(h264ts))
        assertTrue(magisScoreMedia(h264ts) < magisScoreMedia(h265mp4))
    }

    @Test
    fun `scoreMedia breaks container ties by height, taller is slightly better`() {
        // Same codec/container: the formula is -height/1000, so the TALLER media scores lower
        // (a gentle tie-break; codec/container dominate, exactly like the reference).
        val lower = JSONObject().put("encodeFormat", "h264").put("videoFormat", "mp4").put("height", 720)
        val higher = JSONObject().put("encodeFormat", "h264").put("videoFormat", "mp4").put("height", 1080)
        assertTrue(magisScoreMedia(higher) < magisScoreMedia(lower))
    }

    @Test
    fun `bestMedia flattens totalMovieList groups and picks the lowest score`() {
        val episode = JSONObject().put(
            "totalMovieList",
            JSONArray()
                .put(JSONObject().put("movieList", JSONArray().put(h265()).put(h264mp4())))
                .put(JSONObject().put("movieList", JSONArray().put(h264ts()))),
        )
        assertEquals("mp4", magisBestMedia(episode)?.optString("contentId"))
    }

    @Test
    fun `bestMedia returns null with no candidates`() {
        assertNull(magisBestMedia(JSONObject()))
        assertNull(
            magisBestMedia(JSONObject().put("totalMovieList", JSONArray().put(JSONObject().put("movieList", JSONArray())))),
        )
    }

    private fun h264mp4() = JSONObject().put("contentId", "mp4").put("encodeFormat", "h264").put("videoFormat", "mp4").put("height", 1080)
    private fun h264ts() = JSONObject().put("contentId", "ts").put("encodeFormat", "h264").put("videoFormat", "ts").put("height", 1080)
    private fun h265() = JSONObject().put("contentId", "h265").put("encodeFormat", "h265").put("videoFormat", "mp4").put("height", 2160)

    // --- vod CDN selection --------------------------------------------------------

    private fun slb(vararg cdns: JSONObject) = JSONObject().put("cdn_list", JSONArray().apply { cdns.forEach { put(it) } })
    private fun cdn(tag: String, mainAddr: String, vararg urls: JSONObject) =
        JSONObject().put("tag", tag).put("main_addr", mainAddr).put("url_list", JSONArray().apply { urls.forEach { put(it) } })
    private fun url(url: String, tag: String = "free", signType: String? = null): JSONObject {
        val o = JSONObject().put("url", url).put("tag", tag)
        signType?.let { o.put("sign_type", it) }
        return o
    }

    @Test
    fun `vodCdn picks the vod entry whose free url carries sign_type=cfl in the URL`() {
        val slb = slb(
            cdn("live", "https://live.example.com", url("a=1&sign_type=cfl")),
            cdn("vod", "https://vod.example.com/path/", url("a=1&sign_type=cfl&b=2")),
        )
        val chosen = magisVodCdn(slb)
        assertEquals("https://vod.example.com/path", chosen?.base)
        assertEquals("a=1&sign_type=cfl&b=2", chosen?.auth)
    }

    @Test
    fun `vodCdn accepts the sign_type=cfl field variant`() {
        val slb = slb(cdn("vod", "https://vod.example.com", url("a=1", signType = "cfl")))
        assertEquals("https://vod.example.com", magisVodCdn(slb)?.base)
    }

    @Test
    fun `vodCdn rejects non-free urls and non-cfl entries`() {
        val slb = slb(
            cdn("vod", "https://vod.example.com", url("a=1&sign_type=cfl", tag = "pay"), url("a=1")),
        )
        assertNull(magisVodCdn(slb))
    }

    @Test
    fun `vodCdn prefixes https when main_addr lacks the scheme`() {
        val slb = slb(cdn("vod", "vod.example.com/", url("a=1&sign_type=cfl")))
        val chosen = magisVodCdn(slb)
        assertEquals("https://vod.example.com", chosen?.base)
        assertFalse(chosen?.base?.startsWith("https://https") == true)
    }

    @Test
    fun `vodCdn skips entries without main_addr`() {
        val slb = slb(
            JSONObject().put("tag", "vod").put("url_list", JSONArray().put(url("a=1&sign_type=cfl"))),
            cdn("vod", "https://ok.example.com", url("a=1&sign_type=cfl")),
        )
        assertEquals("https://ok.example.com", magisVodCdn(slb)?.base)
    }

    @Test
    fun `vodCdn returns null without a vod entry`() {
        assertNull(magisVodCdn(slb(cdn("live", "https://live.example.com", url("a=1&sign_type=cfl")))))
        assertNull(magisVodCdn(JSONObject()))
    }

    // --- extension choice ---------------------------------------------------------

    @Test
    fun `extension is ts only for a ts videoFormat, mp4 otherwise`() {
        assertEquals("ts", magisVodExtension("ts"))
        assertEquals("ts", magisVodExtension("TS"))
        assertEquals("mp4", magisVodExtension("mp4"))
        assertEquals("mp4", magisVodExtension("m3u8"))
        assertEquals("mp4", magisVodExtension(""))
    }
}
