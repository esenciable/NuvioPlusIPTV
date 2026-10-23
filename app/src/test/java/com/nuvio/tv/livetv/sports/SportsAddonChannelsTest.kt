package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the addon link looks like to the rest of the feature: a channel the player can open, placed
 * in front of the guide's guesses without deleting them.
 *
 * The keeping-part matters as much as the front-part. Catch-up and recording are only available on
 * a channel that has a real EPG programme, so the guide's matches stay in the list underneath ours
 * — the user can still "watch from start" on them, and they are what the picker offers when our
 * signal is down.
 */
class SportsAddonChannelsTest {

    private val kickoff = 1_761_148_800_000L

    private val epl = League(
        id = "soccer/eng.1", sport = Sport.FOOTBALL, name = "Premier League", short = "EPL",
        espnPath = "soccer/eng.1", guideWords = listOf("Premier League", "EPL"), minutes = 115
    )

    private fun team(full: String, league: League = epl) = TeamInfo(
        leagueId = league.id, id = full.lowercase(), name = full, fullName = full,
        abbr = full.take(3).uppercase(), location = null, color = null, altColor = null, logo = null
    )

    private fun event(home: TeamInfo, away: TeamInfo, id: String = "espn:x:1") = SportsEvent(
        id = id,
        leagueId = epl.id,
        startMs = kickoff,
        hasTime = true,
        state = GameState.LIVE,
        title = "${home.name} v ${away.name}",
        subtitle = null,
        status = null,
        away = Side(team = away, score = null, record = null, winner = false, home = false),
        home = Side(team = home, score = null, record = null, winner = true, home = true),
        espnId = "1"
    )

    private fun addon(first: String, second: String, matchId: Long = 445) = AddonEvent(
        id = "rb_${matchId}_1",
        matchId = matchId,
        sportType = 1,
        title = "$first vs $second",
        leagueId = 4551,
        leagueName = "Premier League",
        country = "Ucrania",
        countryId = 212,
        kickoffMs = kickoff,
        live = true,
        status = "live",
        teams = listOf(
            AddonTeam(1, first, "https://logos.test/a.png"),
            AddonTeam(2, second, "https://logos.test/b.png"),
        ),
        streamUrl = "https://addon.test/kt/rblive/match/${matchId}_1.m3u8"
    )

    private fun guideChannel(key: String) = LiveChannel(
        key = key, sourceId = "iptv", sourceName = "esencial.m3u", name = "Sky Sports LaLiga",
        tvgId = "SkySports.es", tvgName = null, logo = null, groupId = "g1", group = "Deportes",
        number = 101, url = "http://iptv.test/1.m3u8"
    )

    private fun guideMatch(key: String, score: Int = 90) =
        ChannelMatch(guideChannel(key), score, "guide", null, "HD", catchup = true)

    @Test
    fun `the addon channel carries the feed url and a stable key`() {
        val ch = SportsAddonChannels.channelFor(addon("Arsenal", "Chelsea"))

        assertEquals("ext:rb_445_1", ch.key)
        assertEquals("https://addon.test/kt/rblive/match/445_1.m3u8", ch.url)
        assertEquals("Esencial Sport", ch.sourceName)
        // It is not in the playlist, so it has no guide: nothing may promise a replay from it.
        assertNull(ch.tvgId)
        assertNull(ch.catchup)
        // Rebuilding it must produce the same key: favourites and blocked channels persist by key.
        assertEquals(ch.key, SportsAddonChannels.channelFor(addon("Arsenal", "Chelsea")).key)
        assertEquals(ch.key, SportsAddonChannels.channelFor(addon("Different", "Names", matchId = 445)).key)
    }

    @Test
    fun `our link is the automatic pick and keeps the guide's guesses`() {
        val ev = event(team("Arsenal"), team("Chelsea"))

        val out = SportsAddonChannels.decorate(
            mapOf(ev.id to listOf(guideMatch("ch1"))),
            listOf(ev),
            listOf(addon("Arsenal", "Chelsea")),
        )

        val list = out.getValue(ev.id)
        assertEquals(2, list.size)
        assertEquals("ext:rb_445_1", list.first().channel.key)
        assertEquals(SportsAddonChannels.SCORE, list.first().score)
        assertEquals("addon", list.first().how)
        assertNull(list.first().program)
        assertFalse(list.first().catchup)
        assertTrue("the picker treats it as a sure match", list.first().sure)
        assertEquals("the guide's channel is still offered", "ch1", list.last().channel.key)
    }

    @Test
    fun `an event the guide never placed becomes watchable`() {
        val ev = event(team("Arsenal"), team("Chelsea"))

        val out = SportsAddonChannels.decorate(emptyMap(), listOf(ev), listOf(addon("Arsenal", "Chelsea")))

        assertEquals(1, out.getValue(ev.id).size)
        assertEquals("ext:rb_445_1", out.getValue(ev.id).first().channel.key)
    }

    @Test
    fun `events with no feed counterpart are left exactly as they were`() {
        val ev = event(team("Arsenal"), team("Chelsea"))
        val before = mapOf(ev.id to listOf(guideMatch("ch1")))

        val out = SportsAddonChannels.decorate(before, listOf(ev), listOf(addon("Real Madrid", "Barcelona")))

        assertEquals(before, out)
    }

    @Test
    fun `an empty feed changes nothing`() {
        val ev = event(team("Arsenal"), team("Chelsea"))
        val before = mapOf(ev.id to listOf(guideMatch("ch1")))

        assertEquals(before, SportsAddonChannels.decorate(before, listOf(ev), emptyList()))
    }

    @Test
    fun `the guide's own order is preserved below ours`() {
        val ev = event(team("Arsenal"), team("Chelsea"))

        val out = SportsAddonChannels.decorate(
            mapOf(ev.id to listOf(guideMatch("chA", 150), guideMatch("chB", 80))),
            listOf(ev),
            listOf(addon("Arsenal", "Chelsea")),
        )

        assertEquals(listOf("ext:rb_445_1", "chA", "chB"), out.getValue(ev.id).map { it.channel.key })
    }

    @Test
    fun `a blocked addon channel is respected like any other key`() {
        val ev = event(team("Arsenal"), team("Chelsea"))
        // The same "eventId|channelKey" shape SportsMatcher.kt:167 and blockChannel() already use.
        val blocked = setOf("${ev.id}|${SportsAddonChannels.keyOf(addon("Arsenal", "Chelsea"))}")

        val out = SportsAddonChannels.decorate(
            mapOf(ev.id to listOf(guideMatch("ch1"))),
            listOf(ev),
            listOf(addon("Arsenal", "Chelsea")),
            blocked = blocked,
        )

        assertEquals(listOf("ch1"), out.getValue(ev.id).map { it.channel.key })
    }

    @Test
    fun `blocking the addon channel for one game does not hide it for another`() {
        val arsenal = event(team("Arsenal"), team("Chelsea"), id = "espn:x:1")
        val villa = event(team("Aston Villa"), team("Brighton"), id = "espn:x:2")
        val candidates = listOf(addon("Arsenal", "Chelsea", matchId = 445), addon("Aston Villa", "Brighton", matchId = 446))

        val out = SportsAddonChannels.decorate(
            emptyMap(),
            listOf(arsenal, villa),
            candidates,
            blocked = setOf("espn:x:1|ext:rb_445_1"),
        )

        assertFalse(out.containsKey(arsenal.id))
        assertEquals(listOf("ext:rb_446_1"), out.getValue(villa.id).map { it.channel.key })
    }

    private val twoSources = listOf(
        AddonSource(11, "HD", true, "https://addon.test/rblive/match/445_1.m3u8?s=11"),
        AddonSource(12, null, false, "https://addon.test/rblive/match/445_1.m3u8?s=12"),
    )

    @Test
    fun `every source of an event becomes its own channel, above the guide's guesses`() {
        val ev = event(team("Arsenal"), team("Chelsea"))

        val out = SportsAddonChannels.decorate(
            mapOf(ev.id to listOf(guideMatch("ch1"))),
            listOf(ev),
            listOf(addon("Arsenal", "Chelsea").copy(sources = twoSources)),
        )

        val matches = out.getValue(ev.id)
        assertEquals(listOf("ext:rb_445_1#11", "ext:rb_445_1#12", "ch1"), matches.map { it.channel.key })
        // Each one plays its own feed, not the match's generic address.
        assertEquals("https://addon.test/rblive/match/445_1.m3u8?s=12", matches[1].channel.url)
        assertEquals("Esencial Sport · HD", matches[0].channel.name)
        // An unnamed source is still pickable: the position says which of the two it is.
        assertEquals("Esencial Sport · 2", matches[1].channel.name)
        assertEquals(listOf(200, 200, 90), matches.map { it.score })
    }

    @Test
    fun `refusing one source does not refuse the match's other feeds`() {
        val ev = event(team("Arsenal"), team("Chelsea"))
        val candidates = listOf(addon("Arsenal", "Chelsea").copy(sources = twoSources))

        val out = SportsAddonChannels.decorate(
            mapOf(ev.id to listOf(guideMatch("ch1"))),
            listOf(ev),
            candidates,
            blocked = setOf("espn:x:1|ext:rb_445_1#11"),
        )

        // The user said the HD feed isn't showing this game. That is not a verdict on the other one.
        assertEquals(listOf("ext:rb_445_1#12", "ch1"), out.getValue(ev.id).map { it.channel.key })
    }

    @Test
    fun `an event with no sources keeps the key the feature shipped with`() {
        val ev = event(team("Arsenal"), team("Chelsea"))

        val out = SportsAddonChannels.decorate(
            mapOf(ev.id to listOf(guideMatch("ch1"))),
            listOf(ev),
            listOf(addon("Arsenal", "Chelsea")),
        )

        // Older addon answers, and a live match we could not resolve, must not silently move the
        // key: favourites and refusals persist by it.
        assertEquals(listOf("ext:rb_445_1", "ch1"), out.getValue(ev.id).map { it.channel.key })
    }
}
