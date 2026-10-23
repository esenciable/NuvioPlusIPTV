package com.nuvio.tv.ext.livetv.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LIVE flow against a scripted portal (no network): the exact call sequence, the catalog
 * cache, the per-entry license/playCode pairing, the typed errors and the signed-header shape —
 * the contract the TS reference (`live.ts`) and the fork verified against the real portal.
 */
class MagisLiveClientTest {

    /** In-memory session store (the real one is DataStore-backed and needs an Android context). */
    private class FakeStore : MagisSessionStore {
        var current: StoredSession? = null
        override fun save(s: StoredSession) { current = s }
        override fun read(): StoredSession? = current
    }

    /** Scripted portal: per-path answers, recording every (path, bean) pair. */
    private class FakePortal(
        private val script: MutableMap<String, MagisResult<JSONObject>.() -> MagisResult<JSONObject>> = mutableMapOf(),
    ) : MagisPortalLike {
        data class Call(val path: String, val bean: Map<String, Any?>)

        val calls = mutableListOf<Call>()

        override suspend fun call(
            path: String,
            bean: Map<String, Any?>,
            baseFields: Boolean,
            userId: String,
            userToken: String,
            sn: String?,
        ): MagisResult<JSONObject> {
            calls += Call(path, bean)
            val default: MagisResult<JSONObject>.() -> MagisResult<JSONObject> = {
                MagisResult.Ok(JSONObject(mapOf("userId" to "u1", "userToken" to "t1", "snToken" to "ST-fake")))
            }
            return (script[path] ?: default).invoke(MagisResult.Ok(JSONObject()))
        }
    }

    private val config = MagisRuntimeConfig(
        hosts = listOf("host1.test"),
        appId = "com.android.msandroid",
        apkVersion = "49902",
        apkVerHeader = "43404",
        spkgVer = "2025-08-07 05:40:11_36_16_",
        threeDesKeyHex = "e7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfeb8",
    )

    /** The fixed clock the signing tests assert against (a fresh moment is otherwise untestable). */
    private fun client(
        portal: FakePortal,
        nowMs: () -> Long = { 1_700_000_000_000L },
    ): MagisLiveClient =
        MagisLiveClient(portal, MagisSession(portal, FakeStore()), { config }, nowMs)

    // --- canned answers ------------------------------------------------------

    private fun categoriesAnswer(vararg pairs: Pair<Int, String>) = MagisResult.Ok(
        JSONObject().put(
            "recommendList",
            JSONArray().apply {
                pairs.forEach { (id, name) -> put(JSONObject().put("columnId", id).put("name", name)) }
            },
        ),
    )

    private fun liveDataAnswer(vararg channels: Triple<String, String, Int>) = MagisResult.Ok(
        JSONObject().put(
            "channelList",
            JSONArray().apply {
                channels.forEach { (code, name, num) ->
                    put(JSONObject().put("channelCode", code).put("name", name).put("channelNumber", num))
                }
            },
        ),
    )

    private fun startPlayLiveAnswer(license: String = "lic-1", playCode: String = "pc-1") = MagisResult.Ok(
        JSONObject().put(
            "liveAddressList",
            JSONArray().put(
                JSONObject().put("playCode", playCode).put("license", license),
            ),
        ),
    )

    private fun slbLiveAnswer(
        vararg cdns: Pair<String, String>, // main_addr -> cfl url
    ) = MagisResult.Ok(
        JSONObject()
            .put("invalidTime", 14400)
            .put(
                "cdn_list",
                JSONArray().apply {
                    cdns.forEach { (mainAddr, url) ->
                        put(
                            JSONObject()
                                .put("tag", "live")
                                .put("main_addr", mainAddr)
                                .put("url_list", JSONArray().put(JSONObject().put("url", url))),
                        )
                    }
                },
            ),
    )

    private val cflUrl1 = "auth=1&token=0123456789ABCDEF0123456789ABCDEF&sign_type=cfl"
    private val cflUrl2 = "auth=2&token=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sign_type=cfl"

    // --- catalogs -------------------------------------------------------------

    @Test(timeout = 10_000)
    fun `categories calls getNextColumns over the live root and parses the list`() = runTest {
        val portal = FakePortal(
            script = mutableMapOf("getNextColumns" to { categoriesAnswer(9 to "Esporte", 3 to "Abertos") }),
        )
        val cats = client(portal).categories()

        assertEquals(listOf(MagisLiveCategory(9, "Esporte"), MagisLiveCategory(3, "Abertos")), cats)
        val call = portal.calls.first { it.path == "getNextColumns" }
        // The unversioned path and the masnew_live root are what the real client sends.
        assertEquals(MAGIS_LIVE_ROOT, call.bean["columnCode"])
        // 200 and not 30: the portal truncates at the requested size without a "more" flag.
        assertEquals(200, call.bean["pageSize"])
    }

    @Test(timeout = 10_000)
    fun `categories cache within the TTL and refetch after it`() = runTest {
        var now = 0L
        val portal = FakePortal(
            script = mutableMapOf("getNextColumns" to { categoriesAnswer(1 to "A") }),
        )
        val c = client(portal) { now }

        c.categories()
        c.categories()
        assertEquals(1, portal.calls.count { it.path == "getNextColumns" })

        now = MagisLiveClient.CATALOG_TTL_MS + 1
        c.categories()
        assertEquals(2, portal.calls.count { it.path == "getNextColumns" })
    }

    @Test(timeout = 10_000)
    fun `channels pages through getLiveData and stops on a short page`() = runTest {
        val full = (1..MagisLiveClient.PAGE_SIZE).map { Triple("ch$it", "Canal $it", it) }.toTypedArray()
        var liveDataCalls = 0
        val portal = FakePortal(
            script = mutableMapOf(
                "v6/getLiveData" to {
                    liveDataCalls++
                    if (liveDataCalls == 1) liveDataAnswer(*full) else liveDataAnswer(Triple("last", "Last", 101))
                },
            ),
        )
        val channels = client(portal).channels(9, 1)

        assertEquals(MagisLiveClient.PAGE_SIZE, channels.size)
        assertEquals("ch1", channels.first().code)
        val call = portal.calls.first { it.path == "v6/getLiveData" }
        assertEquals(9, call.bean["columnId"])
        assertEquals(MagisLiveClient.PAGE_SIZE, call.bean["pageSize"])
    }

    // --- resolution -----------------------------------------------------------

    @Test(timeout = 10_000)
    fun `resolve runs the mint then startPlayLive then getSlbInfo with the CHANNEL code`() = runTest {
        val portal = FakePortal(
            script = mutableMapOf(
                "v4/startPlayLive" to { startPlayLiveAnswer() },
                "v14/getSlbInfo" to { slbLiveAnswer("http://cdn1.magis.example/v3/youshi/" to cflUrl1) },
            ),
        )
        val result = client(portal).resolveDetailed("cyx-RCNHD")

        assertTrue(result is MagisResult.Ok)
        val s = (result as MagisResult.Ok).data
        assertEquals("cdn1.magis.example", s.host)
        assertEquals("lic-1", s.license)
        assertEquals("pc-1", s.playCode)
        assertEquals(14400L, s.ttlSeconds)
        assertEquals(
            listOf("v3/snToken", "v8/active", "v4/startPlayLive", "v14/getSlbInfo"),
            portal.calls.map { it.path },
        )
        val play = portal.calls.first { it.path == "v4/startPlayLive" }
        assertEquals("cyx-RCNHD", play.bean["channelCode"])
        // getSlbInfo is asked with the CHANNEL's code, not the playCode.
        val slb = portal.calls.first { it.path == "v14/getSlbInfo" }
        val liveCodes = slb.bean["liveCodeList"] as JSONArray
        assertEquals(listOf("cyx-RCNHD"), (0 until liveCodes.length()).map { liveCodes.getString(it) })
    }

    @Test(timeout = 10_000)
    fun `playCode comes from the first entry with BOTH license and code`() = runTest {
        val portal = FakePortal(
            script = mutableMapOf(
                "v4/startPlayLive" to {
                    MagisResult.Ok(
                        JSONObject().put(
                            "liveAddressList",
                            JSONArray()
                                .put(JSONObject().put("playCode", "").put("license", "lic-A"))
                                .put(JSONObject().put("playCode", "pc-B").put("license", "lic-B")),
                        ),
                    )
                },
                "v14/getSlbInfo" to { slbLiveAnswer("cdn1.example" to cflUrl1) },
            ),
        )
        val s = (client(portal).resolveDetailed("c1") as MagisResult.Ok).data

        // The complete pair (pc-B/lic-B), never pc from one entry and license from another.
        assertEquals("pc-B", s.playCode)
        assertEquals("lic-B", s.license)
    }

    @Test(timeout = 10_000)
    fun `resolution errors are typed`() = runTest {
        // No addresses at all.
        val noAddr = FakePortal(
            script = mutableMapOf(
                "v4/startPlayLive" to { MagisResult.Ok(JSONObject()) },
                "v14/getSlbInfo" to { slbLiveAnswer("cdn1.example" to cflUrl1) },
            ),
        )
        assertEquals(MagisLiveClient.NO_ADDRESSES, (client(noAddr).resolveDetailed("c") as MagisResult.PortalError).code)

        // No cfl CDN (cflx does NOT count — exact key match, not a prefix).
        val noCdn = FakePortal(
            script = mutableMapOf(
                "v4/startPlayLive" to { startPlayLiveAnswer() },
                "v14/getSlbInfo" to { slbLiveAnswer("cdn1.example" to "auth=1&token=0123456789ABCDEF0123456789ABCDEF&sign_type=cflx") },
            ),
        )
        assertEquals(MagisLiveClient.NO_CDN, (client(noCdn).resolveDetailed("c") as MagisResult.PortalError).code)

        // cfl CDN without a 32-hex token.
        val noToken = FakePortal(
            script = mutableMapOf(
                "v4/startPlayLive" to { startPlayLiveAnswer() },
                "v14/getSlbInfo" to { slbLiveAnswer("cdn1.example" to "auth=1&sign_type=cfl") },
            ),
        )
        assertEquals(MagisLiveClient.NO_TOKEN, (client(noToken).resolveDetailed("c") as MagisResult.PortalError).code)
    }

    @Test(timeout = 10_000)
    fun `every servable cfl CDN travels in order for fallback`() = runTest {
        val portal = FakePortal(
            script = mutableMapOf(
                "v4/startPlayLive" to { startPlayLiveAnswer() },
                "v14/getSlbInfo" to {
                    slbLiveAnswer(
                        "https://cdn1.magis.example/v3/youshi/" to cflUrl1,
                        "cdn2.magis.example/other/" to cflUrl2,
                    )
                },
            ),
        )
        val s = (client(portal).resolveDetailed("c") as MagisResult.Ok).data

        assertEquals(listOf("cdn1.magis.example", "cdn2.magis.example"), s.cdns.map { it.host })
        assertEquals(cflUrl2, s.cdns[1].authBase)
        // The session's own host/authBase are the FIRST CDN's.
        assertEquals("cdn1.magis.example", s.host)
    }

    // --- URL and header shapes -------------------------------------------------

    @Test(timeout = 10_000)
    fun `playlist URL is the bare host with the playCode and no slb path`() = runTest {
        val portal = FakePortal(
            script = mutableMapOf(
                "v4/startPlayLive" to { startPlayLiveAnswer(playCode = "cyx-2EF7E") },
                "v14/getSlbInfo" to { slbLiveAnswer("https://cdn1.magis.example/v3/youshi/" to cflUrl1) },
            ),
        )
        val c = client(portal)
        val s = (c.resolveDetailed("cyx-RCNHD") as MagisResult.Ok).data

        // The path suffix main_addr carries must NOT travel here, and the playCode (not the
        // channel code) is what the CDN serves.
        assertEquals("http://cdn1.magis.example/live/cyx-2EF7E.m3u8", c.playlistUrl(s))
    }

    @Test(timeout = 10_000)
    fun `signed headers carry a fresh moment and the sign_o3 of the token`() = runTest {
        val portal = FakePortal(
            script = mutableMapOf(
                "v4/startPlayLive" to { startPlayLiveAnswer() },
                "v14/getSlbInfo" to { slbLiveAnswer("cdn1.example" to cflUrl1) },
            ),
        )
        val now = 1_700_000_000_000L
        val c = client(portal) { now }
        val s = (c.resolveDetailed("c") as MagisResult.Ok).data
        val h = c.signedHeaders(s)

        val expected = TweakedMd5.signO3("0123456789ABCDEF0123456789ABCDEF", now)
        assertEquals(
            "$cflUrl1&sign2_method=sign_o3&instance=0&start_moment=$now&sign2=$expected",
            h["Content-Auth"],
        )
        assertEquals("lic-1", h["Content-License"])
        assertEquals("Ranger/4.9.4-17294ac0", h["User-Agent"])
        assertEquals("com.android.msandroid", h["App"])
        assertEquals("49902", h["App-Version"])
        assertEquals("0", h["X-Buffer"])
    }

    // --- pure response translation --------------------------------------------

    @Test(timeout = 10_000)
    fun `bareHost strips scheme and path`() {
        assertEquals("a.b.c", bareHost("https://a.b.c/v3/youshi/"))
        assertEquals("a.b.c", bareHost("http://a.b.c"))
        assertEquals("a.b.c", bareHost("a.b.c/path"))
    }

    @Test(timeout = 10_000)
    fun `signalFrom returns null only when there is no license at all`() {
        val withLicenseNoCode = JSONObject().put(
            "liveAddressList",
            JSONArray().put(JSONObject().put("playCode", "").put("license", "lic")),
        )
        assertEquals("" to "lic", signalFrom(withLicenseNoCode))
        assertNull(signalFrom(JSONObject().put("liveAddressList", JSONArray())))
    }
}
