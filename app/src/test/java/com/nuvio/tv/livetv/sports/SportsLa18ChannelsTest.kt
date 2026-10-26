package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The la18hd feed's place on the Sports page: the channels a candidate carries become rows
 * ("Partidos de Hoy · ESPN 4") keyed by the channel id, the `la18:` placeholder passes through
 * URL joining untouched, and a match nothing else carries becomes an event of its own while a
 * match something else carries never duplicates.
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
        assertEquals(200, rows[0].score)
        assertEquals("la18", rows[0].how)
    }

    @Test
    fun `a match only la18 carries becomes a row of its own`() {
        val rows = SportsLa18Rows.rows(listOf(la18("Santa Clara", "Benfica")), emptyList())
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals("Santa Clara vs Benfica", row.title)
        assertEquals("Premier League", row.subtitle)
        assertEquals(GameState.LIVE, row.state)
        assertEquals("Santa Clara", row.home?.team?.name)
        assertEquals("Benfica", row.away?.team?.name)
    }

    @Test
    fun `a match something else carries is not duplicated`() {
        val covered = SportsLa18Rows.rows(listOf(la18("Arsenal", "Chelsea")), listOf(event("Arsenal", "Chelsea")))
        assertTrue(covered.isEmpty())
        // Either order of the sides is still the same game.
        val flipped = SportsLa18Rows.rows(listOf(la18("Chelsea", "Arsenal")), listOf(event("Arsenal", "Chelsea")))
        assertTrue(flipped.isEmpty())
        // A different fixture at the same hour is not.
        val other = SportsLa18Rows.rows(listOf(la18("Porto", "Braga")), listOf(event("Arsenal", "Chelsea")))
        assertEquals(1, other.size)
    }

    @Test
    fun `the feed's league name joins the catalog's`() {
        assertEquals("soccer/eng.1", SportsLa18Rows.leagueOf("Premier League")?.id)
        // A league the tab doesn't know: the row still stands, unplaceable.
        assertNull(SportsLa18Rows.leagueOf("Liga 1 Peruana"))
        val unknown = SportsLa18Rows.event(la18("Universitario", "Alianza").copy(leagueName = "Liga 1 Peruana"))
        assertTrue(unknown.leagueId.startsWith("guide/"))
    }
}
