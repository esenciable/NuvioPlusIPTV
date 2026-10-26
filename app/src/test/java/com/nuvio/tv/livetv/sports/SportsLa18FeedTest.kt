package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The la18hd ("Partidos de Hoy") feed: the grouping of one row per channel into one event with
 * one source per channel, the Lima -> UTC kick-off arithmetic, the live window the site's own
 * script uses, and the disk cache it shares with the other sports feeds.
 *
 * The fixture is the real agenda as served on 2026-10-26 (`la18/agenda-v1.json`); the golden
 * expectations below were read off that day's rows.
 */
class SportsLa18FeedTest {

    private val fixture: String
        get() = File("src/test/resources/la18/agenda-v1.json").readText()

    /** 2026-10-26 13:15 America/Lima = 2026-10-26 18:15 UTC. */
    private val afcKickoffMs = 1_793_038_500_000L

    /** 2026-10-26 13:45 Lima — the Messi Cup row with the "vs." spelling. */
    private val messiKickoffMs = 1_793_040_300_000L

    @Test
    fun `groups one row per channel into one event with one source per channel`() {
        val events = SportsLa18Feed.parseEvents(fixture, now = afcKickoffMs + 1)
        // 36 rows over 14 distinct (title, date, time) triples in this fixture.
        assertEquals(14, events.size)
        val afc = events.first { it.title.startsWith("AFC Champions") }
        assertEquals("Al-Qadisiyah FC", afc.teams[0].name)
        assertEquals("Al Sadd", afc.teams[1].name)
        assertEquals(afcKickoffMs, afc.kickoffMs)
        // Four links; the tarjetarojita embed (measured unplayable) is dropped.
        assertEquals(listOf("ESPN 4", "Disney 2", "ESPN 3 MX"), afc.sources.map { it.label })
        assertTrue(afc.sources.all { it.url.startsWith("la18:") })
    }

    @Test
    fun `parses the site's alternate spellings of the same title`() {
        val events = SportsLa18Feed.parseEvents(fixture, now = messiKickoffMs + 1)
        val messi = events.first { it.title.startsWith("Messi Cup – Boca") }
        assertEquals("Boca Juniors", messi.teams[0].name)
        assertEquals("UE Cornellà", messi.teams[1].name)
        assertEquals("Messi Cup", messi.leagueName)
        // One la18hd link; the tarjetarojita embed is dropped.
        assertEquals(listOf("ESPN 2"), messi.sources.map { it.label })
    }

    @Test
    fun `kick-off is the site's wall clock shifted out of Lima`() {
        assertEquals(afcKickoffMs, SportsLa18Feed.kickoffMs("2026-10-26", "13:15"))
        // 20:45 Lima rolls into the next UTC day.
        assertEquals(1_793_065_500_000L, SportsLa18Feed.kickoffMs("2026-10-26", "20:45"))
        assertNull(SportsLa18Feed.kickoffMs("2026-10-26", "25:15"))
        assertNull(SportsLa18Feed.kickoffMs("bad", "13:15"))
    }

    @Test
    fun `live is the site's own window, not the stale status column`() {
        val kickoff = afcKickoffMs
        // Scheduled before, live inside the 150-minute tail, finished after.
        assertEquals("scheduled", SportsLa18Feed.parseEvents(fixture, now = kickoff - 1).first { it.title.startsWith("AFC") }.status)
        assertEquals("live", SportsLa18Feed.parseEvents(fixture, now = kickoff).first { it.title.startsWith("AFC") }.status)
        assertEquals("live", SportsLa18Feed.parseEvents(fixture, now = kickoff + SportsLa18Feed.LIVE_TAIL_MS - 1).first { it.title.startsWith("AFC") }.status)
        val after = SportsLa18Feed.parseEvents(fixture, now = kickoff + SportsLa18Feed.LIVE_TAIL_MS).first { it.title.startsWith("AFC") }
        assertEquals("finished", after.status)
        assertFalse(after.live)
    }

    @Test
    fun `event ids are stable across parses`() {
        val a = SportsLa18Feed.parseEvents(fixture, now = afcKickoffMs)
        val b = SportsLa18Feed.parseEvents(fixture, now = afcKickoffMs + 60_000)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertTrue(a.first { it.title.startsWith("AFC") }.id.startsWith("la18-"))
    }

    @Test
    fun `duplicate links collapse, foreign links drop out`() {
        val body = """
            [
              { "category": "futbol", "title": "Liga: A vs B", "time": "13:15", "status": "En vivo",
                "language": "Español", "date": "2026-10-26", "link": "https://la18hd.su/vivo/canales.php?stream=espn" },
              { "category": "futbol", "title": "Liga: A vs B", "time": "13:15", "status": "En vivo",
                "language": "Español", "date": "2026-10-26", "link": "https://la18hd.su/vivo/canales.php?stream=espn" },
              { "category": "futbol", "title": "Liga: A vs B", "time": "13:15", "status": "En vivo",
                "language": "Español", "date": "2026-10-26", "link": "https://www.tarjetarojita.xyz/sw3.html?get=x" }
            ]
        """.trimIndent()
        val events = SportsLa18Feed.parseEvents(body, now = 1_793_038_500_000L)
        assertEquals(1, events.size)
        assertEquals(1, events[0].sources.size)
        assertEquals("ESPN", events[0].sources[0].label)
        assertEquals("la18:espn", events[0].streamUrl)
    }

    @Test
    fun `an entry without a parsable title or channel is left out`() {
        val body = """
            [
              { "category": "futbol", "title": "Resumen del día", "time": "13:15",
                "date": "2026-10-26", "link": "https://la18hd.su/vivo/canales.php?stream=espn" },
              { "category": "futbol", "title": "Liga: A vs B", "time": "13:15",
                "date": "2026-10-26", "link": "https://example.com/not-a-channel" },
              { "category": "futbol", "title": "Copa: C vs D", "time": "14:00",
                "date": "2026-10-26", "link": "https://la18hd.su/vivo/canales.php?stream=espn2" }
            ]
        """.trimIndent()
        val events = SportsLa18Feed.parseEvents(body, now = 1_793_038_500_000L)
        assertEquals(listOf("Copa: C vs D"), events.map { it.title })
    }

    @Test
    fun `a non-agenda body is empty, never a throw`() {
        assertTrue(SportsLa18Feed.parseEvents("<html>403</html>", now = 0).isEmpty())
        assertTrue(SportsLa18Feed.parseEvents("", now = 0).isEmpty())
        assertTrue(SportsLa18Feed.parseEvents("{}", now = 0).isEmpty())
    }

    @Test
    fun `the cache serves the saved body when the download fails`() {
        val dir = Files.createTempDirectory("la18feed").toFile()
        val file = File(dir, SportsLa18Feed.CACHE_NAME)
        file.writeText("1000\n[{}]")
        var fail = false
        val feed = SportsLa18Feed(dir, ttlMs = 60_000, now = { 2_000 }, httpGet = {
            if (fail) throw java.io.IOException("down") else "[{}]"
        })
        assertEquals("[{}]", feed.raw())
        fail = true
        // Fresh TTL has passed and the download fails: the saved body still answers.
        assertEquals("[{}]", feed.raw(force = true))
    }

    @Test
    fun `channel ids and names`() {
        assertEquals("espn4", SportsLa18Feed.channelId("https://la18hd.su/vivo/canales.php?stream=espn4"))
        assertNull(SportsLa18Feed.channelId("https://www.tarjetarojita.xyz/sw3.html?get=x"))
        assertEquals("ESPN 4", SportsLa18Feed.prettify("espn4"))
        assertEquals("DSports AR", SportsLa18Feed.prettify("dsportsar"))
        assertEquals("DSports (op. 2)", SportsLa18Feed.prettify("dsports_op2"))
        assertEquals("SOMETHINGNEW", SportsLa18Feed.prettify("somethingnew"))
        assertEquals("la18:espn4", SportsLa18Feed.streamPlaceholder("espn4"))
    }
}
