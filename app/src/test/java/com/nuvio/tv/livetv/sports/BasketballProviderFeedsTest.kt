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
 * API, the current ACB round out of the Next.js payload, and an NBA scoreboard straight from ESPN
 * so the leagues that were already there are pinned too.
 */
class BasketballProviderFeedsTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("sports/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private fun league(id: String): League = checkNotNull(SportsCatalog.league(id)) { "no league $id" }

    @Test
    fun `euroleague season document becomes a scoreboard`() {
        val raw = fixture("euroleague-games.json")
        val feeds = BasketballFeeds { url ->
            if (url.contains("/competitions/E/seasons/")) raw else throw IOException("unexpected $url")
        }
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
    fun `acb round payload becomes a scoreboard with its channels`() {
        val html = fixture("acb-round-current.html")
        val feeds = BasketballFeeds { url ->
            // Only the round the page opens with: the rounds either side are fetched too, and a
            // failure there is meant to be harmless.
            if (url == "https://acb.com/es/liga/partidos") html else throw IOException("no round for $url")
        }
        val league = league("acb")

        val events = SportsFeeds.parseScoreboard(league, feeds.scoreboard(league))

        assertEquals("nine games a round", 9, events.size)
        val first = events.first { it.home?.team?.abbr == "K&U" }
        assertNotNull("the home side resolves through its teams:N reference", first.home)
        assertEquals("MOB", first.away?.team?.abbr)
        assertEquals("100", first.home?.score)
        assertEquals("99", first.away?.score)
        assertEquals(GameState.POST, first.state)
        assertNotNull("the home club's colour", first.home?.team?.color)
        assertNotNull("the home club's crest", first.home?.team?.logo)
        assertTrue("the round is named", first.subtitle?.contains("Jornada 7") == true)
        assertTrue("the TV channels come across", first.broadcasts.contains("DAZN"))
        assertTrue("all of them", first.broadcasts.containsAll(listOf("Movistar Plus +", "Orange", "Vodafone TV")))
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
