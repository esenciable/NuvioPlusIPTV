package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The two basketball leagues ESPN doesn't carry: their own answers, rewritten into a scoreboard and
 * read back by the parser the page already uses.
 *
 * The fixtures are real, trimmed captures — an EuroLeague season document from the competition's v2
 * API, the current ACB round out of the competition's own JSON API, and an NBA scoreboard straight
 * from ESPN so the leagues that were already there are pinned too.
 */
class BasketballProviderFeedsTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("sports/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private fun league(id: String): League = checkNotNull(SportsCatalog.league(id)) { "no league $id" }

    @Test
    fun `euroleague season document becomes a scoreboard`() {
        val raw = fixture("euroleague-games.json")
        val feeds = BasketballFeeds(get = { url ->
            if (url.contains("/competitions/E/seasons/")) raw else throw IOException("unexpected $url")
        })
        val league = league("euroleague")

        val events = SportsFeeds.parseScoreboard(league, feeds.scoreboard(league))

        assertEquals("one played game and one still to play", 2, events.size)
        val played = events.first { it.state == GameState.POST }
        assertEquals("Partizan", played.home?.team?.name)
        assertEquals("Maccabi", played.away?.team?.name)
        assertEquals("88", played.home?.score)
        assertEquals("67", played.away?.score)
        assertTrue("the winner is flagged", played.home?.winner == true)
        assertEquals("quarters are kept", 4, played.home?.periods?.size)
        assertNotNull("a crest", played.home?.team?.logo)
        assertTrue("a real date", played.startMs > 0L)
        assertTrue("never started games stay ahead", events.any { it.state == GameState.PRE })
    }

    @Test
    fun `acb round from its api becomes a scoreboard with its channels`() {
        val json = fixture("acb-round.json")
        val feeds = BasketballFeeds(
            get = { url -> throw IOException("the ACB source needs its key, not a plain fetch: $url") },
            getWithHeaders = { url, headers ->
                // The key rides on the request: without it the API answers 401.
                check(headers["X-APIKEY"]?.isNotBlank() == true) { "the ACB key must be sent" }
                check(url.startsWith("https://api2.acb.com/api/seasondata/Competition/matches?competitionId=1")) {
                    "unexpected $url"
                }
                // Only the round the competition points at answers here. The rounds either side are
                // asked for too, and one that fails must be skipped, never empty the list.
                if (url.contains("roundId=")) throw IOException("no round for $url")
                json
            }
        )
        val league = league("acb")

        val events = SportsFeeds.parseScoreboard(league, feeds.scoreboard(league))

        assertEquals("nine games a round, from the one round that answered", 9, events.size)
        val first = events.first { it.home?.team?.abbr == "K&U" }
        assertEquals("teams resolve by id, not by an index into the page", "MOB", first.away?.team?.abbr)
        assertEquals("100", first.home?.score)
        assertEquals("99", first.away?.score)
        assertEquals(GameState.POST, first.state)
        assertNotNull("the home club's colour", first.home?.team?.color)
        assertNotNull("the home club's crest", first.home?.team?.logo)
        assertTrue("the round is named", first.subtitle?.contains("Jornada 7") == true)
        // The API's broadcast list is flat with a primary flag, not the page's {primary, secondary}.
        assertEquals(
            "the primary channels, in ACB's own order",
            listOf("DAZN", "Movistar Plus +", "Orange", "Vodafone TV"),
            first.broadcasts
        )
    }

    @Test
    fun `an acb answer with no matches fails instead of hiding the round`() {
        val feeds = BasketballFeeds(
            get = { url -> throw IOException("unexpected $url") },
            getWithHeaders = { _, _ -> "{\"teams\":[],\"matches\":[]}" }
        )

        val failure = runCatching { feeds.scoreboard(league("acb")) }

        assertTrue("an empty answer is a failure, not an empty league", failure.isFailure)
    }

    @Test
    fun `espn leagues keep the feed they had`() {
        val nba = league("basketball/nba")
        assertEquals(SportProvider.ESPN, nba.provider)
        assertEquals("basketball/nba", nba.espnPath)
        assertTrue(nba.hasFeed)

        val events = SportsFeeds.parseScoreboard(nba, fixture("espn-nba-scoreboard.json"))
        assertEquals(1, events.size)
        val game = events.first()
        assertNotNull(game.home)
        assertNotNull(game.away)
        assertTrue(game.home?.team?.name?.isNotBlank() == true)
        assertTrue(game.startMs > 0L)
    }

    @Test
    fun `nothing else in the catalogue was rerouted`() {
        val own = SportsCatalog.leagues.filter { it.provider != SportProvider.ESPN }.map { it.id }.toSet()
        assertEquals(setOf("acb", "euroleague"), own)
        // Visible wherever the device is, so neither is hidden by the region it is read from.
        assertEquals(League.ALL_REGIONS, league("acb").regions)
        assertEquals(League.ALL_REGIONS, league("euroleague").regions)
        assertEquals(Sport.BASKETBALL, league("acb").sport)
        assertEquals(Sport.BASKETBALL, league("euroleague").sport)
    }
}
