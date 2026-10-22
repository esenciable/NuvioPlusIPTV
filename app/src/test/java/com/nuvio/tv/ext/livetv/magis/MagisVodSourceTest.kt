package com.nuvio.tv.ext.livetv.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Magis VOD source as ONE entry of the native-VOD list: the disabled-config contract
 * (`client = null` → absent: `isConfigured` false, resolve touches NOTHING and answers empty),
 * the mapping of the client's own stream model onto `NativeVodStream` with its CDN headers, and
 * the failure path answering empty instead of throwing.
 */
class MagisVodSourceTest {

    private class FakeStore : MagisSessionStore {
        var current: StoredSession? = null
        override fun save(s: StoredSession) { current = s }
        override fun read(): StoredSession? = current
    }

    private class FakeTitleLookup : MagisTitleLookup {
        override suspend fun lookup(id: String, isSeries: Boolean): MagisTitleInfo? =
            MagisTitleInfo(title = "Coco", originalTitle = "Coco")
    }

    /** Portal recording every path; per-path answers overridable, default an empty Ok. */
    private class FakePortal(
        private val answers: Map<String, MagisResult<JSONObject>.() -> MagisResult<JSONObject>> = emptyMap(),
    ) : MagisPortalLike {
        val paths = mutableListOf<String>()
        override suspend fun call(
            path: String,
            bean: Map<String, Any?>,
            baseFields: Boolean,
            userId: String,
            userToken: String,
            sn: String?,
        ): MagisResult<JSONObject> {
            paths += path
            // Default covers the session mint (v3/snToken needs snToken; the session fields are
            // what MagisVodClientTest's default carries).
            val ok = MagisResult.Ok(
                JSONObject(mapOf("snToken" to "ST-fake", "userId" to "u1", "userToken" to "t1")),
            )
            return (answers[path] ?: { ok }).invoke(ok)
        }
    }

    private fun config() = MagisRuntimeConfig(
        hosts = listOf("host1.test"),
        appId = "app",
        apkVersion = "49902",
        apkVerHeader = "43404",
        spkgVer = "spkg",
        threeDesKeyHex = "e7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfeb8",
    )

    private fun client(portal: MagisPortalLike) = MagisVodClient(
        portal = portal,
        session = MagisSession(portal, FakeStore()),
        configProvider = { config() },
        titleLookup = FakeTitleLookup(),
    )

    private fun searchAnswer() = MagisResult.Ok(
        JSONObject().put(
            "searchItem",
            JSONArray().put(JSONObject().put("contentId", "C1").put("name", "Coco")),
        ),
    )

    private fun playAnswer() = JSONObject().put(
        "episodeList",
        JSONArray().put(
            JSONObject().put(
                "totalMovieList",
                JSONArray().put(
                    JSONObject().put(
                        "movieList",
                        JSONArray().put(
                            JSONObject()
                                .put("contentId", "MEDIA1")
                                .put("encodeFormat", "h264")
                                .put("videoFormat", "ts")
                                .put("height", 1080)
                                .put("licenseList", JSONArray().put(JSONObject().put("license", "lic"))),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun slbAnswer() = JSONObject()
        .put("invalidTime", 14400)
        .put(
            "cdn_list",
            JSONArray().put(
                JSONObject()
                    .put("tag", "vod")
                    .put("main_addr", "cdn.example.com")
                    .put(
                        "url_list",
                        JSONArray().put(
                            JSONObject().put("tag", "free").put("url", "auth=abc&sign_type=cfl"),
                        ),
                    ),
            ),
        )

    @Test
    fun `unconfigured source is absent - not configured and never resolves`() = runTest {
        val source = MagisVodSource(client = null)
        assertFalse(source.isConfigured)
        // The contract: skip, never crash, never touch the portal.
        assertTrue(source.resolve("movie", "tt354912", null, null).isEmpty())
    }

    @Test
    fun `default source name is the group name the screen sorts first`() {
        assertEquals("Magis VOD", MagisVodSource(client = null).name)
        assertTrue(MagisVodSource(client = null).isConfigured.not())
    }

    @Test
    fun `configured source maps the client stream with its CDN headers`() = runTest {
        val portal = FakePortal(
            answers = mapOf(
                "v3/searchByName" to { searchAnswer() },
                "v10/startPlayVOD" to { MagisResult.Ok(playAnswer()) },
                "v14/getSlbInfo" to { MagisResult.Ok(slbAnswer()) },
            ),
        )
        val source = MagisVodSource(client = client(portal))

        assertTrue(source.isConfigured)
        val resolved = source.resolve("movie", "tt354912", null, null)
        assertEquals(1, resolved.size)
        assertEquals("https://cdn.example.com/vod/MEDIA1_media.ts", resolved.single().url)
        // `Content-Auth` carries the slb entry's whole querystring — that IS the CDN auth.
        assertEquals("auth=abc&sign_type=cfl", resolved.single().headers["Content-Auth"])
        assertEquals("lic", resolved.single().headers["Content-License"])
    }

    @Test
    fun `a failed resolve answers empty instead of throwing`() = runTest {
        // The portal answers everything with Ok(empty): selection never finds a candidate, the
        // client surfaces its typed error, and the source collapses it to an empty list.
        val portal = FakePortal()
        val source = MagisVodSource(client = client(portal))
        assertTrue(source.resolve("movie", "tt354912", null, null).isEmpty())
    }

    @Test
    fun `a failed resolve logs the typed failure ONCE with kind and portal code`() = runTest {
        // The silence defect: the client's typed failure used to collapse to null → emptyList
        // with no log line at all. Now the source logs ONE line per resolution attempt, carrying
        // the error kind and the portal code when present.
        val portal = FakePortal(
            answers = mapOf(
                "v3/searchByName" to { MagisResult.PortalError("aaa100028", "not logged in") },
            ),
        )
        val logs = mutableListOf<String>()
        val source = MagisVodSource(client = client(portal), logFailure = { logs += it })

        assertTrue(source.resolve("movie", "tt354912", null, null).isEmpty())
        assertEquals(1, logs.size)
        assertTrue("log names the kind", logs.single().contains("portal"))
        assertTrue("log carries the portal code", logs.single().contains("aaa100028"))
        assertTrue("log names the video", logs.single().contains("tt354912"))
    }

    @Test
    fun `a failed resolve logs connection errors with their cause`() = runTest {
        val portal = FakePortal(
            answers = mapOf(
                "v3/searchByName" to { MagisResult.RedError(java.io.IOException("timeout")) },
            ),
        )
        val logs = mutableListOf<String>()
        val source = MagisVodSource(client = client(portal), logFailure = { logs += it })

        assertTrue(source.resolve("movie", "tt354912", null, null).isEmpty())
        assertEquals(1, logs.size)
        assertTrue("log names the kind", logs.single().contains("connection"))
        assertTrue("log carries the cause", logs.single().contains("timeout"))
    }

    @Test
    fun `a successful resolve logs nothing`() = runTest {
        val portal = FakePortal(
            answers = mapOf(
                "v3/searchByName" to { searchAnswer() },
                "v10/startPlayVOD" to { MagisResult.Ok(playAnswer()) },
                "v14/getSlbInfo" to { MagisResult.Ok(slbAnswer()) },
            ),
        )
        val logs = mutableListOf<String>()
        val source = MagisVodSource(client = client(portal), logFailure = { logs += it })

        assertEquals(1, source.resolve("movie", "tt354912", null, null).size)
        assertTrue("no log on success", logs.isEmpty())
    }
}
