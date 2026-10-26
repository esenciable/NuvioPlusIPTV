package com.nuvio.tv.livetv.sports

/**
 * la18hd events the page has no row for yet, as rows.
 *
 * The addon feed only ever DECORATES rows something else found — ESPN, the guide. la18hd knows
 * today's football outright, including matches ESPN does not carry (a cup in a country the tab
 * has no league for), and a viewer who opened the tab for one of those must find it. So a
 * candidate nothing else covers becomes its own row — never INSTEAD of a covered one: a
 * candidate that matches an existing event is skipped here, and decorate() puts its channels
 * under that event's row.
 */
internal object SportsLa18Rows {

    /** [candidates] that no event of [existing] already covers, as the page's own events. */
    fun rows(candidates: List<AddonEvent>, existing: List<SportsEvent>): List<SportsEvent> =
        candidates.filter { c -> existing.none { SportsAddonMatcher.matches(it, SportsCatalog.league(it.leagueId), c) } }
            .map { event(it) }

    /** One candidate as a row: the page's own shape, the feed's facts. */
    fun event(addon: AddonEvent): SportsEvent {
        val league = leagueOf(addon.leagueName)
        val home = addon.teams.getOrNull(0)
        val away = addon.teams.getOrNull(1)
        return SportsEvent(
            id = addon.id,
            leagueId = league?.id ?: SportsMatcher.OTHER_ID,
            startMs = addon.kickoffMs,
            hasTime = true,
            state = when {
                addon.isLive -> GameState.LIVE
                addon.status.equals("finished", ignoreCase = true) -> GameState.POST
                else -> GameState.PRE
            },
            title = listOfNotNull(home?.name, away?.name).joinToString(" vs ")
                .ifBlank { addon.title },
            subtitle = addon.leagueName.takeIf { it.isNotBlank() },
            status = null,
            home = home?.let { side(it, league, home = true) },
            away = away?.let { side(it, league, home = false) },
            source = EventSource.FIXTURES,
        )
    }

    /** The catalog league whose name is the feed's, or null for a league the tab doesn't know. */
    fun leagueOf(name: String): League? {
        val n = SportsCatalog.norm(name).trim()
        if (n.isEmpty()) return null
        return SportsCatalog.all.firstOrNull { SportsCatalog.norm(it.name).trim() == n }
            ?: SportsCatalog.all.firstOrNull { l -> l.guideWords.any { SportsCatalog.norm(it).trim() == n } }
    }

    private fun side(team: AddonTeam, league: League?, home: Boolean) = Side(
        team = TeamInfo(
            leagueId = league?.id ?: SportsMatcher.OTHER_ID,
            id = SportsCatalog.norm(team.name).ifBlank { team.name.lowercase() },
            name = team.name,
            fullName = team.name,
            abbr = team.name.take(3).uppercase(),
            location = null,
            color = null,
            altColor = null,
            logo = team.logo?.takeIf { it.isNotBlank() },
        ),
        score = null,
        record = null,
        winner = false,
        home = home,
    )
}
