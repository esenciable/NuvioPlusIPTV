package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join between the addon feed and the app's own games: both teams must match (in either order),
 * kick-off within twenty minutes, and the feed's league only breaks ties.
 */
class SportsAddonMatcherTest {

    private val kickoff = 1_761_148_800_000L
    private val minute = 60_000L

    private val epl = League(
        id = "soccer/eng.1", sport = Sport.FOOTBALL, name = "Premier League", short = "EPL",
        espnPath = "soccer/eng.1", guideWords = listOf("Premier League", "EPL"), minutes = 115
    )

    private fun team(full: String, nick: String = full, league: League = epl, location: String? = null) = TeamInfo(
        leagueId = league.id, id = full.lowercase(), name = nick, fullName = full, abbr = nick.take(3).uppercase(),
        location = location, color = null, altColor = null, logo = null
    )

    private fun event(home: TeamInfo, away: TeamInfo, league: League = epl, startMs: Long = kickoff) = SportsEvent(
        id = "espn:${league.id}:1",
        leagueId = league.id,
        startMs = startMs,
        hasTime = true,
        state = GameState.PRE,
        title = "${home.name} v ${away.name}",
        subtitle = null,
        status = null,
        away = Side(team = away, score = null, record = null, winner = false, home = false),
        home = Side(team = home, score = null, record = null, winner = false, home = true),
        espnId = "1"
    )

    private fun addon(
        first: String,
        second: String,
        id: String = "addon_$first",
        leagueId: Long = 0L,
        leagueName: String = "",
        country: String = "",
        kickoffMs: Long = kickoff,
        live: Boolean = false,
        status: String = if (live) "live" else "scheduled"
    ) = AddonEvent(
        id = id,
        matchId = 0L,
        sportType = 1,
        title = "$first vs $second",
        leagueId = leagueId,
        leagueName = leagueName,
        country = country,
        countryId = 0,
        kickoffMs = kickoffMs,
        live = live,
        status = status,
        teams = listOf(AddonTeam(1, first, ""), AddonTeam(2, second, "")),
        streamUrl = "https://addon.example/$id.m3u8"
    )

    private val brighton = team("Brighton & Hove Albion", nick = "Brighton", location = "Brighton")
    private val arsenal = team("Arsenal", nick = "Arsenal", location = "London")

    // ------------------------------------------------------------------ names

    @Test
    fun `a name matches across accents, club suffixes and short forms`() {
        assertTrue(SportsAddonMatcher.nameMatches("Atlético Madrid", listOf("atletico madrid")))
        assertTrue(SportsAddonMatcher.nameMatches("ATLETICO MADRID", listOf("atletico madrid")))
        assertTrue(SportsAddonMatcher.nameMatches("Arsenal FC", listOf("arsenal")))
        assertTrue(SportsAddonMatcher.nameMatches("Arsenal", listOf("arsenal fc")))
        assertTrue(SportsAddonMatcher.nameMatches("Brighton", listOf("brighton and hove albion")))
        assertTrue(SportsAddonMatcher.nameMatches("Brighton & Hove Albion", listOf("brighton")))
        assertTrue(SportsAddonMatcher.nameMatches("  Bayern  München ", listOf("bayern munchen")))
    }

    @Test
    fun `a connector word between the two namings does not hide the match`() {
        // Measured on the operator's box: ESPN "Recreativo Huelva", the feed "Recreativo de Huelva"
        // — the whole-string rule missed, so a live match the upstream carries showed no row at all.
        assertTrue(SportsAddonMatcher.nameMatches("Recreativo Huelva", listOf("recreativo de huelva")))
        assertTrue(SportsAddonMatcher.nameMatches("Recreativo de Huelva", listOf("recreativo huelva")))
        assertTrue(SportsAddonMatcher.nameMatches("Rayo", listOf("rayo vallecano")))
        assertTrue(SportsAddonMatcher.nameMatches("Vitoria de Guimaraes", listOf("vitoria guimaraes")))
        assertTrue(SportsAddonMatcher.nameMatches("Deportivo La Coruna", listOf("deportivo la coruna")))
    }

    @Test
    fun `dropping connectors must not merge different clubs`() {
        assertFalse(SportsAddonMatcher.nameMatches("Real Madrid", listOf("real sociedad")))
        assertFalse(SportsAddonMatcher.nameMatches("Real Sociedad", listOf("real madrid")))
        assertFalse(SportsAddonMatcher.nameMatches("Sporting Gijon", listOf("sporting lisbon")))
        assertFalse(SportsAddonMatcher.nameMatches("Manchester United", listOf("manchester city")))
        // A one-word name still has to be that word, not a fragment of it.
        assertFalse(SportsAddonMatcher.nameMatches("Inter", listOf("internazionale")))
    }

    @Test
    fun `the operators match joins, ESPN names against the feeds`() {
        // The live event the box showed with no rows at all: ESPN's own naming on one side
        // (shortDisplayName "R Huelva", name "Recreativo Huelva") and the feed's on the other
        // ("Recreativo de Huelva", "Rayo Vallecano"), same kick-off.
        val kickoff = 1_793_131_200_000L // 2026-10-27T20:00:00Z
        val league = League(
            id = "soccer/esp.copa_del_rey", sport = Sport.FOOTBALL, name = "Copa del Rey",
            short = "CDR", espnPath = "soccer/esp.copa_del_rey", guideWords = listOf("Copa del Rey"),
            minutes = 115,
        )
        fun side(name: String, full: String, home: Boolean) = Side(
            team = TeamInfo(
                leagueId = league.id, id = full.lowercase(), name = name, fullName = full,
                abbr = full.take(3).uppercase(), location = null, color = null, altColor = null, logo = null,
            ),
            score = null, record = null, winner = false, home = home,
        )
        val espn = SportsEvent(
            id = "espn:x:9", leagueId = league.id, startMs = kickoff, hasTime = true,
            state = GameState.LIVE, title = "Recreativo Huelva v Rayo Vallecano", subtitle = null,
            status = null,
            home = side("R Huelva", "Recreativo Huelva", home = true),
            away = side("Rayo", "Rayo Vallecano", home = false),
        )
        val feed = AddonEvent(
            id = "rb_4520476_1", kickoffMs = kickoff,
            teams = listOf(AddonTeam(1, "Recreativo de Huelva", ""), AddonTeam(2, "Rayo Vallecano", "")),
            streamUrl = "https://addon.test/m.m3u8", title = "Recreativo Huelva vs Rayo Vallecano",
            leagueName = "Copa del Rey", live = true, status = "live",
        )
        assertTrue(SportsAddonMatcher.matches(espn, league, feed))
        assertEquals("rb_4520476_1", SportsAddonMatcher.find(espn, league, listOf(feed))?.id)
    }

    @Test
    fun `a name only matches on whole words`() {
        assertFalse(SportsAddonMatcher.nameMatches("Man", listOf("manchester united")))
        assertFalse(SportsAddonMatcher.nameMatches("Inter", listOf("internazionale")))
        assertFalse(SportsAddonMatcher.nameMatches("", listOf("arsenal")))
        assertFalse(SportsAddonMatcher.nameMatches("Arsenal", emptyList()))
        assertFalse(SportsAddonMatcher.nameMatches("Arsenal", listOf("")))
    }

    // ------------------------------------------------------------------ the join

    @Test
    fun `finds the addon event whichever order the feed puts the two teams in`() {
        val game = event(home = brighton, away = arsenal)

        val asSides = addon(first = "Brighton", second = "Arsenal")
        val reversed = addon(first = "Arsenal", second = "Brighton and Hove Albion", id = "addon_rev")

        assertEquals(asSides, SportsAddonMatcher.find(game, epl, listOf(asSides)))
        assertEquals(reversed, SportsAddonMatcher.find(game, epl, listOf(reversed)))
    }

    @Test
    fun `matches names the guide and the feed spell differently`() {
        val atletico = team("Atletico Madrid", nick = "Atletico Madrid", location = "Madrid")
        val barca = team("Barcelona", nick = "Barcelona", location = "Barcelona")
        val game = event(home = atletico, away = barca)

        val candidate = addon(first = "Atlético de Madrid", second = "FC Barcelona")
        assertEquals(candidate, SportsAddonMatcher.find(game, epl, listOf(candidate)))
    }

    @Test
    fun `a name carried by the catalog's aliases still joins`() {
        val atletico = team("Atletico Madrid", nick = "Atletico Madrid", location = "Madrid")
        val barca = team("Barcelona", nick = "Barcelona", location = "Barcelona")
        val game = event(home = atletico, away = barca)

        // "atletico" comes from SportsCatalog's own alias table, so a filler word in the feed's
        // name ("de") doesn't lose the join.
        assertTrue(SportsAddonMatcher.names(atletico, epl).contains("atletico"))
        val candidate = addon(first = "Atlético de Madrid", second = "Barcelona")
        assertEquals(candidate, SportsAddonMatcher.find(game, epl, listOf(candidate)))
    }

    @Test
    fun `the feed's Spanish spellings of a foreign club still join`() {
        // Measured in production `/events.json` (2026-10-24, 940 distinct team names): the feed
        // writes some foreign clubs by their Spanish name. Stripping accents does not undo that —
        // "Fráncfort" becomes "francfort", which is not the word "Frankfurt" is — so the join fails
        // and the viewer who opened that match sees no Esencial row at all, not one source fewer.
        // These four are the ones the feed actually uses today.
        val frankfurt = team("Eintracht Frankfurt", nick = "Frankfurt", location = "Frankfurt")
        val dortmund = team("Borussia Dortmund", nick = "Dortmund", location = "Dortmund")
        val bundesliga = addon(first = "Borussia Dortmund", second = "Eintracht Fráncfort", id = "bvb")
        assertEquals(bundesliga, SportsAddonMatcher.find(event(home = dortmund, away = frankfurt), epl, listOf(bundesliga)))

        val nuremberg = team("1. FC Nuremberg", nick = "Nuremberg", location = "Nuremberg")
        val ingolstadt = team("FC Ingolstadt", nick = "Ingolstadt", location = "Ingolstadt")
        val secondBundesliga = addon(first = "Núremberg", second = "Ingolstadt", id = "fcn")
        assertEquals(secondBundesliga, SportsAddonMatcher.find(event(home = nuremberg, away = ingolstadt), epl, listOf(secondBundesliga)))

        val brugge = team("Club Brugge", nick = "Club Brugge", location = "Bruges")
        val genk = team("KRC Genk", nick = "Genk", location = "Genk")
        val belgian = addon(first = "Club Brujas", second = "Genk", id = "brujas")
        assertEquals(belgian, SportsAddonMatcher.find(event(home = brugge, away = genk), epl, listOf(belgian)))

        val porto = team("FC Porto", nick = "Porto", location = "Porto")
        val braga = team("SC Braga", nick = "Braga", location = "Braga")
        val portuguese = addon(first = "Oporto", second = "Braga", id = "oporto")
        assertEquals(portuguese, SportsAddonMatcher.find(event(home = porto, away = braga), epl, listOf(portuguese)))
    }

    @Test
    fun `keeps the twenty minute kick-off window`() {
        val game = event(home = brighton, away = arsenal)

        assertEquals(
            "19 minutes late is the same game",
            1,
            count(game, addon(first = "Brighton", second = "Arsenal", kickoffMs = kickoff + 19 * minute))
        )
        assertEquals(
            "20 minutes is still inside the window",
            1,
            count(game, addon(first = "Brighton", second = "Arsenal", kickoffMs = kickoff - 20 * minute))
        )
        assertEquals(
            "25 minutes away is another kick-off",
            0,
            count(game, addon(first = "Brighton", second = "Arsenal", kickoffMs = kickoff + 25 * minute))
        )
    }

    private fun count(game: SportsEvent, candidate: AddonEvent): Int =
        if (SportsAddonMatcher.find(game, epl, listOf(candidate)) == candidate) 1 else 0

    @Test
    fun `a candidate whose feed league is 0 still matches`() {
        // An older build persisted the snapshot with leagueId 0: no league evidence, not a veto.
        val game = event(home = brighton, away = arsenal)
        val candidate = addon(first = "Brighton", second = "Arsenal", leagueId = 0L, leagueName = "Premier League")
        assertEquals(candidate, SportsAddonMatcher.find(game, epl, listOf(candidate)))
    }

    @Test
    fun `live wins over a nearer kick-off`() {
        val game = event(home = brighton, away = arsenal)
        val live = addon(first = "Brighton", second = "Arsenal", id = "live", kickoffMs = kickoff + 15 * minute, live = true)
        val soon = addon(first = "Brighton", second = "Arsenal", id = "soon", kickoffMs = kickoff + 1 * minute)

        assertEquals(live, SportsAddonMatcher.find(game, epl, listOf(soon, live)))
        assertEquals(live, SportsAddonMatcher.find(game, epl, listOf(live, soon)))
    }

    @Test
    fun `without a live feed the nearest kick-off wins`() {
        val game = event(home = brighton, away = arsenal)
        val far = addon(first = "Brighton", second = "Arsenal", id = "far", kickoffMs = kickoff + 12 * minute)
        val near = addon(first = "Brighton", second = "Arsenal", id = "near", kickoffMs = kickoff + 2 * minute)

        assertEquals(near, SportsAddonMatcher.find(game, epl, listOf(far, near)))
    }

    @Test
    fun `the more specific name wins when everything else ties`() {
        val ain = team("Al-Ain", nick = "Al-Ain", location = "Al Ain")
        val ahli = team("Al-Ahli Dubai", nick = "Al-Ahli Dubai", location = "Dubai")
        val game = event(home = ain, away = ahli)

        val short = addon(first = "Al-Ain", second = "Al-Ahli Dubai", id = "short", live = true)
        val long = addon(first = "Al-Ain Club", second = "Al-Ahli Dubai Club", id = "long", live = true)

        assertEquals(short, SportsAddonMatcher.find(game, epl, listOf(long, short)))
    }

    // ------------------------------------------------------------------ the same display name

    private val ukraine = League(id = "tsdb/4551", sport = Sport.FOOTBALL, name = "Premier League", short = "UPL", minutes = 115)
    private val armenia = League(id = "tsdb/4678", sport = Sport.FOOTBALL, name = "Premier League", short = "ARM", minutes = 115)

    @Test
    fun `another country's Premier League cannot cross-match`() {
        val shakhtar = team("Shakhtar Donetsk", league = ukraine, location = "Donetsk")
        val dynamo = team("Dynamo Kyiv", league = ukraine, location = "Kyiv")
        val ararat = team("Ararat-Armenia", league = armenia, location = "Yerevan")
        val pyunik = team("Pyunik", league = armenia, location = "Yerevan")

        val ukrainianGame = event(home = shakhtar, away = dynamo, league = ukraine)
        val armenianGame = event(home = ararat, away = pyunik, league = armenia)

        // The feed names both competitions "Premier League": only the teams decide.
        val ukrainianFeed = addon(
            first = "Shakhtar Donetsk", second = "Dynamo Kyiv",
            leagueId = 4551L, leagueName = "Premier League", country = "Ucrania"
        )
        val armenianFeed = addon(
            first = "Ararat-Armenia", second = "Pyunik",
            id = "arm", leagueId = 4678L, leagueName = "Premier League", country = "Armenia"
        )

        assertEquals(ukrainianFeed, SportsAddonMatcher.find(ukrainianGame, ukraine, listOf(ukrainianFeed, armenianFeed)))
        assertEquals(armenianFeed, SportsAddonMatcher.find(armenianGame, armenia, listOf(ukrainianFeed, armenianFeed)))
        assertNull(SportsAddonMatcher.find(armenianGame, armenia, listOf(ukrainianFeed)))
        assertNull(SportsAddonMatcher.find(ukrainianGame, ukraine, listOf(armenianFeed)))
    }

    @Test
    fun `the league breaks a tie between two otherwise identical candidates`() {
        val shakhtar = team("Shakhtar Donetsk", league = ukraine, location = "Donetsk")
        val dynamo = team("Dynamo Kyiv", league = ukraine, location = "Kyiv")
        val game = event(home = shakhtar, away = dynamo, league = ukraine)

        val right = addon(first = "Shakhtar Donetsk", second = "Dynamo Kyiv", id = "right", leagueId = 4551L, leagueName = "Premier League")
        val other = addon(first = "Shakhtar Donetsk", second = "Dynamo Kyiv", id = "other", leagueId = 4678L, leagueName = "Armenian Premier League")

        assertEquals(right, SportsAddonMatcher.find(game, ukraine, listOf(other, right)))
        assertEquals(right, SportsAddonMatcher.find(game, ukraine, listOf(right, other)))
    }

    @Test
    fun `the league is never a requirement`() {
        val shakhtar = team("Shakhtar Donetsk", league = ukraine, location = "Donetsk")
        val dynamo = team("Dynamo Kyiv", league = ukraine, location = "Kyiv")
        val game = event(home = shakhtar, away = dynamo, league = ukraine)

        val oddId = addon(first = "Shakhtar Donetsk", second = "Dynamo Kyiv", leagueId = 9999L, leagueName = "Friendly Cup")
        assertEquals(oddId, SportsAddonMatcher.find(game, ukraine, listOf(oddId)))
    }

    // ------------------------------------------------------------------ nothing to join

    @Test
    fun `a game without both teams, or with a share of a name, is not joined`() {
        val guideEvent = SportsEvent(
            id = "guide:wwe", leagueId = "guide/wwe", startMs = kickoff, hasTime = true, state = GameState.PRE,
            title = "WWE SmackDown", subtitle = null, status = null, away = null, home = null
        )
        assertNull(SportsAddonMatcher.find(guideEvent, SportsCatalog.league("guide/wwe"), listOf(addon(first = "Brighton", second = "Arsenal"))))

        val game = event(home = brighton, away = arsenal)
        assertNull(SportsAddonMatcher.find(game, epl, listOf(addon(first = "Brighton", second = "Crystal Palace"))))
        assertNull(SportsAddonMatcher.find(game, epl, emptyList()))
        assertNull(SportsAddonMatcher.find(game, epl, listOf(addon(first = "Brighton", second = "Arsenal", kickoffMs = kickoff + 3 * 60 * minute))))
    }
}
