package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The addon feed client: its tolerant parsing, and the disk cache it shares with the other sports
 * feeds (a timestamp line, the raw body after it, and the previous body served when a download
 * fails).
 */
class SportsAddonFeedTest {

    private val body = """
        {
          "generatedAt": 1761148800000,
          "count": 2,
          "events": [
            {
              "id": "rb_4518459_1",
              "matchId": 4518459,
              "sportType": 1,
              "title": "Al-Ahli Dubai vs Al-Ain",
              "leagueId": 4916,
              "leagueName": "Arabian Gulf Cup",
              "country": "Emiratos Árabes Unidos",
              "countryId": 214,
              "kickoffMs": 1761148800000,
              "live": true,
              "status": "live",
              "teams": [
                { "side": 1, "name": "Al-Ahli Dubai", "logo": "https://logos.example/a.png" },
                { "side": 2, "name": "Al-Ain", "logo": "https://logos.example/b.png" }
              ],
              "streamUrl": "https://addon.example/kt1gh2mfcxry/rblive/match/4518459_1.m3u8?token=a%2Fb&e=1"
            },
            {
              "id": "rb_4518460_1",
              "matchId": 4518460,
              "sportType": 2,
              "title": "Lakers at Celtics",
              "leagueId": 4930,
              "leagueName": "NBA",
              "country": "Estados Unidos",
              "countryId": 5,
              "kickoffMs": 1761152400000,
              "live": false,
              "status": "scheduled",
              "teams": [
                { "side": 1, "name": "Lakers", "logo": "" },
                { "side": 2, "name": "Celtics", "logo": "" }
              ],
              "streamUrl": "https://addon.example/nba/match/4518460_1.m3u8"
            }
          ]
        }
    """.trimIndent()

    // ------------------------------------------------------------------ parsing

    @Test
    fun `reads every field of the feed`() {
        val events = SportsAddonFeed.parseEvents(body)
        assertEquals(2, events.size)

        val first = events[0]
        assertEquals("rb_4518459_1", first.id)
        assertEquals(4518459L, first.matchId)
        assertEquals(1, first.sportType)
        assertEquals("Al-Ahli Dubai vs Al-Ain", first.title)
        assertEquals(4916L, first.leagueId)
        assertEquals("Arabian Gulf Cup", first.leagueName)
        assertEquals("Emiratos Árabes Unidos", first.country)
        assertEquals(214, first.countryId)
        assertEquals(1761148800000L, first.kickoffMs)
        assertTrue(first.live)
        assertEquals("live", first.status)
        assertEquals(2, first.teams.size)
        assertEquals(AddonTeam(side = 1, name = "Al-Ahli Dubai", logo = "https://logos.example/a.png"), first.teams[0])
        assertEquals(AddonTeam(side = 2, name = "Al-Ain", logo = "https://logos.example/b.png"), first.teams[1])

        // The playable address is carried through untouched: no rebuilding from the id.
        assertEquals(
            "https://addon.example/kt1gh2mfcxry/rblive/match/4518459_1.m3u8?token=a%2Fb&e=1",
            first.streamUrl
        )

        assertEquals("rb_4518460_1", events[1].id)
        assertEquals("scheduled", events[1].status)
        assertNull(events[1].teams[0].logo.ifBlank { null })
    }

    @Test
    fun `skips an entry that does not carry two teams and keeps the rest`() {
        val json = """
            { "events": [
              { "id": "one_team", "kickoffMs": 1761148800000, "streamUrl": "https://a/1.m3u8",
                "teams": [ { "side": 1, "name": "Al-Ain", "logo": "" } ] },
              { "id": "no_teams", "kickoffMs": 1761148800000, "streamUrl": "https://a/2.m3u8", "teams": [] },
              { "id": "blank_team", "kickoffMs": 1761148800000, "streamUrl": "https://a/3.m3u8",
                "teams": [ { "side": 1, "name": "", "logo": "" }, { "side": 2, "name": "Al-Ain", "logo": "" } ] },
              { "id": "good", "kickoffMs": 1761148800000, "streamUrl": "https://a/4.m3u8",
                "teams": [ { "side": 1, "name": "Al-Ahli Dubai", "logo": "" }, { "side": 2, "name": "Al-Ain", "logo": "" } ] }
            ] }
        """.trimIndent()

        val events = SportsAddonFeed.parseEvents(json)
        assertEquals(listOf("good"), events.map { it.id })
        assertEquals("https://a/4.m3u8", events.single().streamUrl)
    }

    @Test
    fun `skips an entry without a kick-off or an id`() {
        val json = """
            { "events": [
              { "id": "no_kickoff", "streamUrl": "https://a/1.m3u8",
                "teams": [ { "side": 1, "name": "Al-Ahli Dubai", "logo": "" }, { "side": 2, "name": "Al-Ain", "logo": "" } ] },
              { "kickoffMs": 1761148800000, "streamUrl": "https://a/2.m3u8",
                "teams": [ { "side": 1, "name": "Al-Ahli Dubai", "logo": "" }, { "side": 2, "name": "Al-Ain", "logo": "" } ] },
              { "id": "good", "kickoffMs": 1761148800000, "streamUrl": "https://a/3.m3u8",
                "teams": [ { "side": 1, "name": "Al-Ahli Dubai", "logo": "" }, { "side": 2, "name": "Al-Ain", "logo": "" } ] }
            ] }
        """.trimIndent()

        assertEquals(listOf("good"), SportsAddonFeed.parseEvents(json).map { it.id })
    }

    @Test
    fun `a malformed document yields an empty list`() {
        assertEquals(emptyList<AddonEvent>(), SportsAddonFeed.parseEvents("{ not json at all"))
        assertEquals(emptyList<AddonEvent>(), SportsAddonFeed.parseEvents(""))
        assertEquals(emptyList<AddonEvent>(), SportsAddonFeed.parseEvents("   "))
        assertEquals(emptyList<AddonEvent>(), SportsAddonFeed.parseEvents("null"))
        assertEquals(emptyList<AddonEvent>(), SportsAddonFeed.parseEvents("[]"))
        assertEquals(emptyList<AddonEvent>(), SportsAddonFeed.parseEvents("""{"count": 0}"""))
        assertEquals(emptyList<AddonEvent>(), SportsAddonFeed.parseEvents("""{"events": "no"}"""))
    }

    // ------------------------------------------------------------------ fetching and caching

    private fun tempDir(): File = Files.createTempDirectory("sports-addon-feed-").toFile()

    @Test
    fun `asks the feed once and then serves the saved body`() {
        val dir = tempDir()
        try {
            val asked = ArrayList<String>()
            var clock = 1_000_000L
            val feed = SportsAddonFeed(
                dir = dir,
                now = { clock },
                httpGet = { url -> asked += url; body }
            )

            assertEquals(2, feed.events("https://addon.example").size)
            assertEquals(2, feed.events("https://addon.example").size)
            assertEquals(2, feed.events("https://addon.example").size)

            assertEquals(listOf("https://addon.example/events.json"), asked)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a trailing slash on the base address still asks events json`() {
        val dir = tempDir()
        try {
            val asked = ArrayList<String>()
            val feed = SportsAddonFeed(dir = dir, now = { 0L }, httpGet = { url -> asked += url; body })

            assertEquals(2, feed.events("https://addon.example/").size)
            assertEquals(listOf("https://addon.example/events.json"), asked)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `asks again once the saved body is older than the feed's TTL`() {
        val dir = tempDir()
        try {
            var calls = 0
            var clock = 1_000_000L
            val feed = SportsAddonFeed(dir = dir, now = { clock }, httpGet = { calls++; body })

            assertEquals(2, feed.events("https://addon.example").size)
            assertEquals(1, calls)

            clock += SportsAddonFeed.TTL_MS - 1
            assertEquals(2, feed.events("https://addon.example").size)
            assertEquals(1, calls)

            clock += 2
            assertEquals(2, feed.events("https://addon.example").size)
            assertEquals(2, calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a failed download serves the body saved before it`() {
        val dir = tempDir()
        try {
            var calls = 0
            var clock = 1_000_000L
            var failing = false
            val feed = SportsAddonFeed(
                dir = dir,
                now = { clock },
                httpGet = {
                    calls++
                    if (failing) throw java.io.IOException("HTTP 503") else body
                }
            )

            assertEquals(2, feed.events("https://addon.example").size)

            failing = true
            clock += SportsAddonFeed.TTL_MS + 1
            assertEquals(2, feed.events("https://addon.example").size)
            assertEquals(2, calls)

            // The failed attempt didn't count as a fresh answer: the next call tries again.
            assertEquals(2, feed.events("https://addon.example").size)
            assertEquals(3, calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `keeps one saved body per base address beside the other feeds`() {
        val dir = tempDir()
        try {
            val feed = SportsAddonFeed(dir = dir, now = { 0L }, httpGet = { body })

            assertEquals(2, feed.events("https://addon.example").size)
            assertEquals(2, feed.events("https://other.example/sub").size)

            val saved = dir.listFiles().orEmpty().filter { it.isFile }
            assertEquals(2, saved.size)
            assertTrue(saved.all { it.name.startsWith("addon_") && it.name.endsWith(".json") })
            assertTrue(saved.all { it.length() > 0 })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `no saved body and no answer means no events`() {
        val dir = tempDir()
        try {
            val feed = SportsAddonFeed(
                dir = dir,
                now = { 0L },
                httpGet = { throw java.io.IOException("no internet") }
            )

            assertEquals(emptyList<AddonEvent>(), feed.events("https://addon.example"))
            assertNull(feed.raw("https://addon.example"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a failed download never overwrites a good saved body with junk`() {
        val dir = tempDir()
        try {
            var clock = 1_000_000L
            var answer: () -> String = { body }
            val feed = SportsAddonFeed(dir = dir, now = { clock }, httpGet = { answer() })

            assertEquals(2, feed.events("https://addon.example").size)

            answer = { "{ not a feed" }
            clock += SportsAddonFeed.TTL_MS + 1
            assertEquals(2, feed.events("https://addon.example").size)
            assertTrue(dir.listFiles().orEmpty().single().readText().contains("rb_4518459_1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a live event carries every source the addon lists`() {
        val json = """
            {"events":[{"id":"rb_445_1","kickoffMs":1761148800000,
              "streamUrl":"https://addon.test/rblive/match/445_1.m3u8",
              "teams":[{"side":1,"name":"Flamengo"},{"side":2,"name":"Estudiantes"}],
              "sources":[
                {"streamId":11,"label":"HD","verified":true,"url":"https://addon.test/rblive/match/445_1.m3u8?s=11"},
                {"streamId":12,"label":null,"verified":false,"url":"https://addon.test/rblive/match/445_1.m3u8?s=12"}
              ]}]}
        """.trimIndent()

        val ev = SportsAddonFeed.parseEvents(json).single()

        assertEquals(2, ev.sources.size)
        assertEquals(11L, ev.sources[0].streamId)
        assertEquals("HD", ev.sources[0].label)
        assertTrue(ev.sources[0].verified)
        assertEquals("https://addon.test/rblive/match/445_1.m3u8?s=11", ev.sources[0].url)
        // A source the upstream does not name is still a source; only our knowledge of it is missing.
        assertNull(ev.sources[1].label)
        assertFalse(ev.sources[1].verified)
    }

    @Test
    fun `a missing or null sources field leaves the single stable link alone`() {
        val nullSources = """{"events":[{"id":"rb_1_1","kickoffMs":1,"streamUrl":"https://addon.test/m",
            "teams":[{"side":1,"name":"A"},{"side":2,"name":"B"}],"sources":null}]}""".trimIndent()
        val noField = """{"events":[{"id":"rb_1_1","kickoffMs":1,"streamUrl":"https://addon.test/m",
            "teams":[{"side":1,"name":"A"},{"side":2,"name":"B"}]}]}""".trimIndent()

        // Both mean "one link for this match", which is what an older addon answer says too.
        assertEquals(emptyList<AddonSource>(), SportsAddonFeed.parseEvents(nullSources).single().sources)
        assertEquals(emptyList<AddonSource>(), SportsAddonFeed.parseEvents(noField).single().sources)
        assertEquals("https://addon.test/m", SportsAddonFeed.parseEvents(nullSources).single().streamUrl)
    }

    @Test
    fun `a source with no address is dropped instead of becoming an empty channel`() {
        val json = """{"events":[{"id":"rb_1_1","kickoffMs":1,"streamUrl":"https://addon.test/m",
          "teams":[{"side":1,"name":"A"},{"side":2,"name":"B"}],
          "sources":[{"streamId":7,"label":"HD","verified":true,"url":""},
                    {"label":"sin streamId","url":"https://addon.test/s"}]}]}""".trimIndent()

        val ev = SportsAddonFeed.parseEvents(json).single()

        // The first has no address, so it cannot be a channel at all. The second has no upstream id
        // to key on: it still travels, keyed by position, because refusing to show a working feed
        // would hide a signal the viewer can actually watch.
        assertEquals(emptyList<AddonSource>(), ev.sources.filter { it.url.isBlank() })
        assertEquals(1, ev.sources.size)
        assertEquals(0L, ev.sources[0].streamId)
    }
    /** What the addon's stream route answers for one live match (measured: ten feeds for Serie A). */
    private val streamsBody = """
        {"streams":[
          {"name":"Esencial Sport","title":"Napoli vs AS Roma \u00b7 8X VN",
           "url":"https://addon.example/rblive/playlist.m3u8?u=abc","resolution":"8X VN",
           "streamId":888011,"verified":true},
          {"name":"Esencial Sport","title":"Napoli vs AS Roma \u00b7 ESPN MX",
           "url":"https://addon.example/rblive/playlist.m3u8?s=def","resolution":"ESPN MX",
           "streamId":888012,"verified":false},
          {"name":"Esencial Sport","title":"no url, no channel","resolution":"Rota"},
          {"name":"Esencial Sport","title":"unnamed feed",
           "url":"https://addon.example/rblive/playlist.m3u8?s=ghi"}
        ]}""".trimIndent()

    @Test
    fun `asks one match's feeds of the stream route`() {
        val asked = mutableListOf<String>()
        val dir = Files.createTempDirectory("addon").toFile()
        val feed = SportsAddonFeed(dir = dir, now = { 0L })

        val sources = feed.sources(
            "https://addon.example/kt1gh2mfcxry/", "rb_4441324_1",
            httpGet = { url -> asked += url; streamsBody },
        )

        // The base arrives with a trailing slash from the build config sometimes; a double slash
        // is a different resource to the upstream and answers 404.
        assertEquals(listOf("https://addon.example/kt1gh2mfcxry/stream/tv/rb_4441324_1.json"), asked)
        // A readable answer with rows in it is a list, never `null`: `null` is reserved for "the
        // addon could not ask", which the tab must not cache as a fact about the match.
        val got = sources ?: return fail("a stream list must be an answer, got null")
        assertEquals(
            listOf("8X VN", "ESPN MX", null),
            got.map { it.label },
        )
        // The route says `resolution` where the feed says `label`, and `verified` only when it
        // probed: an absent flag is "not verified", never an unknown drawn as a working feed.
        assertEquals(listOf(888011L, 888012L, 0L), got.map { it.streamId })
        assertEquals(listOf(true, false, false), got.map { it.verified })
    }

    @Test
    fun `an empty list is the addon answering that this match has no feeds`() {
        val dir = Files.createTempDirectory("addon").toFile()
        val feed = SportsAddonFeed(dir = dir, now = { 0L })

        assertEquals(emptyList<AddonSource>(),
            feed.sources("https://addon.example", "rb_1_1", httpGet = { """{"streams":[]}""" }))
    }

    @Test
    fun `a body we cannot read is no answer at all`() {
        for (body in listOf("<html>502 Bad Gateway</html>", "", "{}")) {
            val dir = Files.createTempDirectory("addon").toFile()
            val feed = SportsAddonFeed(dir = dir, now = { 0L })
            // `null`, not the empty list: an empty list claims the fixture was asked about and has
            // nothing, which is a fact about the match this reply does not establish.
            assertNull("body: $body", feed.sources("https://addon.example", "rb_1_1", httpGet = { body }))
        }
    }

    @Test
    fun `the addon saying it could not ask is not an answer`() {
        val dir = Files.createTempDirectory("addon").toFile()
        val feed = SportsAddonFeed(dir = dir, now = { 0L })

        // `failed` is the server's rate limit, not the fixture's. Reading it as "no feeds" is what
        // made a viewer keep seeing one row for minutes after the upstream recovered: the tab
        // stored the silence as an answer about that match.
        assertNull(feed.sources("https://addon.example", "rb_1_1", httpGet = { """{"failed":true,"streams":[]}""" }))
        // A real list with the flag set is still the odd case worth surviving: the rows it names
        // came from somewhere, so they are the answer.
        assertEquals(3, feed.sources("https://addon.example", "rb_1_1", httpGet = { """{"failed":true,"streams":[{"url":"https://a/m","resolution":"HD"},{"url":"https://b/m","resolution":"SD"},{"url":"https://c/m"}]}""" })?.size)
    }

    @Test
    fun `a route we cannot reach is no answer, and leaves the match on its own link`() {
        val dir = Files.createTempDirectory("addon").toFile()
        val feed = SportsAddonFeed(dir = dir, now = { 0L })

        // The caller keeps `sources` empty, which `decorate` answers with the single stable
        // streamUrl: a rate limit must cost the viewer the extra feeds, never the match.
        assertNull(feed.sources("https://addon.example", "rb_1_1", httpGet = { throw java.io.IOException("HTTP 429") }))
    }
}
