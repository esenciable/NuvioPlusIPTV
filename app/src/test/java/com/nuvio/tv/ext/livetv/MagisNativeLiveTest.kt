package com.nuvio.tv.ext.livetv

import com.nuvio.tv.ext.livetv.data.MagisChannelLoader
import com.nuvio.tv.ext.livetv.data.MagisStreamResolver
import com.nuvio.tv.ext.livetv.magis.MagisChannelCdn
import com.nuvio.tv.ext.livetv.magis.MagisChannelSession
import com.nuvio.tv.ext.livetv.magis.MagisLiveCatalogApi
import com.nuvio.tv.ext.livetv.magis.MagisLiveCategory
import com.nuvio.tv.ext.livetv.magis.MagisLiveChannel
import com.nuvio.tv.ext.livetv.magis.MagisLiveClient
import com.nuvio.tv.ext.livetv.magis.MagisLivePlaybackApi
import com.nuvio.tv.ext.livetv.magis.MagisLiveSource
import com.nuvio.tv.ext.livetv.magis.MagisResult
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native Magis source's contract in the base's own Live TV models: what a portal channel looks
 * like as a [LiveChannel] (stable key, category group, placeholder URL), how the catalog walk pages
 * and survives a dead category, and what resolution puts back into the channel.
 *
 * The portal is faked through the narrow ports, so none of this needs a session, a crypto stack or
 * a network.
 */
class MagisNativeLiveTest {

    private class FakeCatalogApi(
        private val categories: List<MagisLiveCategory> = listOf(MagisLiveCategory(1, "Abertos")),
        /** (categoryId, page) -> channels; a short page (< pageSize) ends the walk. */
        private val pages: Map<Pair<Int, Int>, List<MagisLiveChannel>> = emptyMap(),
        private val failingCategories: Set<Int> = emptySet(),
    ) : MagisLiveCatalogApi {
        val seen = mutableListOf<Pair<Int, Int>>()

        override suspend fun categories(): List<MagisLiveCategory> = categories

        override suspend fun channels(categoryId: Int, page: Int): List<MagisLiveChannel> {
            seen += categoryId to page
            if (categoryId in failingCategories) throw IllegalStateException("portal down")
            return pages[categoryId to page] ?: emptyList()
        }
    }

    private class FakePlaybackApi(
        private val result: MagisResult<MagisChannelSession> = MagisResult.Ok(
            MagisChannelSession(
                host = "cdn1.example",
                authBase = "auth=1&token=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                license = "lic-1",
                playCode = "pc-1",
                ttlSeconds = 300,
                cdns = listOf(
                    MagisChannelCdn("cdn1.example", "auth=1&token=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
                    MagisChannelCdn("cdn2.example", "auth=2&token=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"),
                ),
            ),
        ),
    ) : MagisLivePlaybackApi {
        val resolved = mutableListOf<String>()

        override suspend fun resolveDetailed(channelCode: String): MagisResult<MagisChannelSession> {
            resolved += channelCode
            return result
        }

        override fun playlistUrl(cdn: MagisChannelSession): String = "http://${cdn.host}/live/${cdn.playCode}.m3u8"

        override fun signedHeaders(cdn: MagisChannelSession): Map<String, String> =
            mapOf("Content-Auth" to cdn.authBase, "User-Agent" to MagisLiveClient.LIVE_USER_AGENT)
    }

    private fun magisChannel(
        code: String = "cyx-RCNHD",
        key: String = MagisLiveSource.channelKey(code),
        url: String = MagisLiveSource.MARKER_SCHEME + code,
    ): LiveChannel = LiveChannel(
        key = key,
        sourceId = MagisLiveSource.SOURCE_ID,
        sourceName = MagisLiveSource.SOURCE_NAME,
        name = "RCN",
        tvgId = null,
        tvgName = "RCN",
        logo = null,
        groupId = MagisLiveSource.groupId(1),
        group = "Abertos",
        number = 101,
        url = url,
        headers = emptyMap(),
    )

    // --- channel mapping -------------------------------------------------------

    @Test(timeout = 10_000)
    fun `a portal channel becomes a base LiveChannel with a stable key and a marker URL`() {
        val c = MagisLiveSource.channelFrom(
            MagisLiveChannel(code = "cyx-RCNHD", name = "RCN", number = 101, logo = "https://art/rcn.png"),
            categoryId = 3,
            categoryName = "Colombia",
        )

        assertEquals("magis:cyx-RCNHD", c.key)
        assertEquals(MagisLiveSource.SOURCE_ID, c.sourceId)
        // Categories are ordinary Live TV groups: the UI groups, searches and favourites them as-is.
        assertEquals("magis:cat:3", c.groupId)
        assertEquals("Colombia", c.group)
        assertEquals("https://art/rcn.png", c.logo)
        // The URL is a placeholder: no signature exists yet, and none may be minted at catalog time.
        assertEquals("magis-live://cyx-RCNHD", c.url)
        assertTrue(c.headers.isEmpty())
        // No tvg-id: the guide matcher would otherwise pair the channel with a wrong listing.
        assertNull(c.tvgId)
    }

    @Test(timeout = 10_000)
    fun `codeOf reads channel keys and marker URLs and nothing else`() {
        assertEquals("cyx-RCNHD", MagisLiveSource.codeOf("magis:cyx-RCNHD"))
        assertEquals("cyx-RCNHD", MagisLiveSource.codeOf("magis-live://cyx-RCNHD"))
        assertNull(MagisLiveSource.codeOf("playlist-id||Vox"))
        assertFalse(MagisLiveSource.isMarkerUrl("https://provider/live/x.m3u8"))
        assertTrue(MagisLiveSource.isMarkerUrl("magis-live://x"))
    }

    // --- catalog walk -----------------------------------------------------------

    @Test(timeout = 10_000)
    fun `an unconfigured source is absent — no load, no crash`() = runTest {
        val loader = MagisChannelLoader(null)
        assertFalse(loader.isConfigured)
        assertEquals(emptyList<LiveChannel>(), loader.loadChannels())
    }

    @Test(timeout = 10_000)
    fun `the walk pages until a short page and keeps one row per channel`() = runTest {
        val full = (1..MagisLiveClient.PAGE_SIZE).map { MagisLiveChannel("ch$it", "Canal $it", it, "") }
        val api = FakeCatalogApi(
            categories = listOf(MagisLiveCategory(1, "Abertos")),
            pages = mapOf(
                (1 to 1) to full,
                (1 to 2) to listOf(MagisLiveChannel("extra", "Extra", 999, "")),
            ),
        )
        val channels = MagisChannelLoader(api).loadChannels()

        assertEquals(MagisLiveClient.PAGE_SIZE + 1, channels.size)
        // Page 3 is never asked: page 2 came back short, so the category is exhausted.
        assertEquals(setOf(1 to 1, 1 to 2), api.seen.toSet())
        assertEquals(2, api.seen.size)
    }

    @Test(timeout = 10_000)
    fun `a channel in several categories appears once, in the first one`() = runTest {
        val shared = MagisLiveChannel("cyx-SHARED", "Compartido", 7, "")
        val api = FakeCatalogApi(
            categories = listOf(MagisLiveCategory(1, "Abertos"), MagisLiveCategory(2, "Deportes")),
            pages = mapOf(
                (1 to 1) to listOf(shared),
                (2 to 1) to listOf(shared),
            ),
        )
        val channels = MagisChannelLoader(api).loadChannels()

        assertEquals(1, channels.size)
        assertEquals("magis:cat:1", channels.single().groupId)
        assertEquals("Abertos", channels.single().group)
    }

    @Test(timeout = 10_000)
    fun `a category that fails leaves the others standing`() = runTest {
        val api = FakeCatalogApi(
            categories = listOf(MagisLiveCategory(1, "Abertos"), MagisLiveCategory(2, "Muertas")),
            pages = mapOf(
                (1 to 1) to listOf(MagisLiveChannel("ok", "Ok", 1, "")),
            ),
            failingCategories = setOf(2),
        )
        val channels = MagisChannelLoader(api).loadChannels()

        assertEquals(listOf("magis:ok"), channels.map { it.key })
    }

    // --- resolution -------------------------------------------------------------

    @Test(timeout = 10_000)
    fun `resolution replaces the marker with the first CDN and its headers`() = runTest {
        val api = FakePlaybackApi()
        val resolved = MagisStreamResolver(api).resolve(magisChannel())!!

        assertEquals(listOf("cyx-RCNHD"), api.resolved)
        assertEquals("http://cdn1.example/live/pc-1.m3u8", resolved.url)
        assertEquals("auth=1&token=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", resolved.headers["Content-Auth"])
        // Identity survives resolution: favorites, recents and the guide key off the same channel.
        assertEquals("magis:cyx-RCNHD", resolved.key)
        assertEquals("Abertos", resolved.group)
        // A resolved channel never needs resolution again — that is what stops an infinite re-sign.
        assertFalse(MagisStreamResolver(api).needsResolution(resolved))
    }

    @Test(timeout = 10_000)
    fun `resolveCdn aims the SAME signal at a fallback CDN`() = runTest {
        val api = FakePlaybackApi()
        val second = MagisStreamResolver(api).resolveCdn(magisChannel(), 1)!!

        // Same playCode and license (one signal's pair), only the host and authBase move.
        assertEquals("http://cdn2.example/live/pc-1.m3u8", second.url)
        assertEquals("auth=2&token=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", second.headers["Content-Auth"])
        assertNull(MagisStreamResolver(api).resolveCdn(magisChannel(), 2))
    }

    @Test(timeout = 10_000)
    fun `a failed or unconfigured resolution serves nothing`() = runTest {
        val dead = FakePlaybackApi(MagisResult.PortalError("live_no_cfl_cdn", "sin CDN"))
        assertNull(MagisStreamResolver(dead).resolve(magisChannel()))
        assertNull(MagisStreamResolver(null).resolve(magisChannel()))
        assertEquals(0, MagisStreamResolver(null).cdnCount(magisChannel()))
    }

    @Test(timeout = 10_000)
    fun `a playlist channel is not the source's business`() = runTest {
        val resolver = MagisStreamResolver(FakePlaybackApi())
        val playlistChannel = magisChannel().copy(sourceId = "pl-1", url = "https://provider/x.m3u8")

        assertFalse(resolver.owns(playlistChannel))
        assertFalse(resolver.needsResolution(playlistChannel))
    }
}
