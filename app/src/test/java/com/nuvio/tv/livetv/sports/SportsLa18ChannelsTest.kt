package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The la18hd feed's place on the Sports page: SOURCE-ONLY. Its channels become rows
 * ("Partidos de Hoy · ESPN 4") keyed by the channel id and joined onto the matches the page
 * already lists — la18 never generates match rows of its own, and where both feeds serve the
 * same fixture the addon's named streams stay the automatic pick.
 */
class SportsLa18ChannelsTest {

    private val kickoff = 1_761_148_800_000L

    private val epl = League(
        id = "soccer/eng.1", sport = Sport.FOOTBALL, name = "Premier League", short = "EPL",
        espnPath = "soccer/eng.1", guideWords = listOf("Premier League", "EPL"), minutes = 115
    )

    private fun team(full: String) = TeamInfo(
        leagueId = epl.id, id = full.lowercase(), name = full, fullName = full,
        abbr = full.take(3).uppercase(), location = null, color = null, altColor = null, logo = null
    )

    private fun event(home: String, away: String, id: String = "espn:x:1") = SportsEvent(
        id = id,
        leagueId = epl.id,
        startMs = kickoff,
        hasTime = true,
        state = GameState.LIVE,
        title = "$home v $away",
        subtitle = null,
        status = null,
        away = Side(team = team(away), score = null, record = null, winner = false, home = false),
        home = Side(team = team(home), score = null, record = null, winner = true, home = true),
    )

    /** The addon-shaped candidate the other feed's tests build. */
    private fun addon(first: String, second: String, source: String) = AddonEvent(
        id = "rb_445_1",
        kickoffMs = kickoff,
        teams = listOf(AddonTeam(1, first, ""), AddonTeam(2, second, "")),
        streamUrl = source,
        sportType = 1,
        title = "$first vs $second",
        leagueName = "Premier League",
        live = true,
        status = "live",
    )

    /** An la18 candidate with two channels, shaped like the parser emits them. */
    private fun la18(home: String, away: String, channels: List<String> = listOf("espn4", "dsportsar")) = AddonEvent(
        id = "la18-$home-$away-20261026",
        kickoffMs = kickoff,
        teams = listOf(AddonTeam(1, home, ""), AddonTeam(2, away, "")),
        streamUrl = SportsLa18Feed.streamPlaceholder(channels.first()),
        sportType = 1,
        title = "Premier League: $home vs $away",
        leagueName = "Premier League",
        live = true,
        status = "live",
        sources = channels.map { ch ->
            AddonSource(streamId = 0, label = SportsLa18Feed.prettify(ch), verified = false, url = "la18:$ch")
        },
    )

    @Test
    fun `la18 channels are named after the channel and keyed by it`() {
        val channels = SportsAddonChannels.channelsFor(SportsAddonChannels.LA18, "", la18("Arsenal", "Chelsea"))
        assertEquals(listOf("Partidos de Hoy · ESPN 4", "Partidos de Hoy · DSports AR"), channels.map { it.name })
        assertEquals(
            listOf("ext:la18-Arsenal-Chelsea-20261026#espn4", "ext:la18-Arsenal-Chelsea-20261026#dsportsar"),
            channels.map { it.key },
        )
        // The row is the resolver's: the player must route it to the la18 mint.
        assertTrue(channels.all { it.sourceId == "la18" })
        assertTrue(channels.all { it.url.startsWith("la18:") })
        // No guide identity: nothing may promise a replay from a token that expires.
        assertNull(channels[0].tvgId)
    }

    @Test
    fun `the la18 marker passes through url joining untouched`() {
        val channels = SportsAddonChannels.channelsFor(SportsAddonChannels.LA18, "https://base.test", la18("Arsenal", "Chelsea"))
        // A feed base must NOT be joined onto the placeholder: it would forge a relative path.
        assertTrue(channels.all { it.url.startsWith("la18:") && !it.url.contains("base.test") })
    }

    @Test
    fun `decorate puts la18's channels under the matching event`() {
        val ev = event("Arsenal", "Chelsea")
        val out = SportsAddonChannels.decorate(
            SportsAddonChannels.LA18, "", emptyMap(), listOf(ev), listOf(la18("Arsenal", "Chelsea")),
        )
        assertEquals(1, out.size)
        val rows = out[ev.id].orEmpty()
        assertEquals(2, rows.size)
        assertEquals(190, rows[0].score)
        assertEquals("la18", rows[0].how)
    }

    @Test
    fun `the addon's streams outrank la18's channels on a match both carry`() {
        val ev = event("Arsenal", "Chelsea")
        val addonFirst = SportsAddonChannels.decorate(
            "https://addon.test/kt", emptyMap(), listOf(ev),
            listOf(addon("Arsenal", "Chelsea", "https://addon.test/kt/rblive/match/445_1.m3u8")),
        )
        val both = SportsAddonChannels.decorate(
            SportsAddonChannels.LA18, "", addonFirst, listOf(ev), listOf(la18("Arsenal", "Chelsea")),
        )
        val rows = both[ev.id].orEmpty()
        // The addon's row is the automatic pick; la18's channels are still in the picker.
        assertTrue(rows.first().channel.url.startsWith("https://addon.test/kt"))
        assertTrue(rows.any { it.channel.sourceId == "la18" })
    }

    @Test
    fun `a match la18 alone carries gets no row of its own`() {
        // Source-only by design: the page's list is the one ESPN, the guide and the fixtures arm.
        // A fixture outside that list keeps la18's links unused rather than inventing a row that
        // can arrive without its channels attached.
        val out = SportsAddonChannels.decorate(
            SportsAddonChannels.LA18, "", emptyMap(),
            listOf(event("Arsenal", "Chelsea")), listOf(la18("Santa Clara", "Benfica")),
        )
        assertTrue(out.isEmpty())
    }
}
