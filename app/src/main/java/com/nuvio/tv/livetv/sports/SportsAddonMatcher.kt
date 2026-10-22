package com.nuvio.tv.livetv.sports

import kotlin.math.abs

/**
 * Joins one of the app's games to one event of the addon feed. Both teams have to be the same two
 * teams in either order, and the kick-offs have to be within [WINDOW_MS]; the feed's league is only
 * ever a tie-breaker, because its own display name is ambiguous ("Premier League" is a different
 * competition in every country) and its league id can legitimately come through as 0 from a
 * snapshot an older build saved.
 */
internal object SportsAddonMatcher {

    /** How far the two kick-offs may differ and still be the same game. */
    const val WINDOW_MS = 20 * 60_000L

    /** The feed's league is this one. */
    const val LEAGUE_MATCH = 2
    /** Nothing in the feed contradicts the league (no id, or a name several competitions share). */
    const val LEAGUE_UNKNOWN = 1
    /** The feed names another competition: a last tie-breaker, never a veto. */
    const val LEAGUE_CONFLICT = 0

    /** The best event of [candidates] for [event], or null when the feed has no such game. */
    fun find(event: SportsEvent, league: League?, candidates: List<AddonEvent>): AddonEvent? =
        candidates.filter { matches(event, league, it) }.minWithOrNull(preference(event, league))

    /** True when [addon] is the same game as [event]: both teams, in either order, and the kick-off. */
    fun matches(event: SportsEvent, league: League?, addon: AddonEvent): Boolean {
        if (abs(event.startMs - addon.kickoffMs) > WINDOW_MS) return false
        val home = event.home ?: return false
        val away = event.away ?: return false
        val first = addon.teams.getOrNull(0) ?: return false
        val second = addon.teams.getOrNull(1) ?: return false
        val homeNames = names(home.team, league)
        val awayNames = names(away.team, league)
        fun isHome(t: AddonTeam) = nameMatches(t.name, homeNames)
        fun isAway(t: AddonTeam) = nameMatches(t.name, awayNames)
        // The feed's side 1 and 2 don't reliably line up with home and away.
        return (isHome(first) && isAway(second)) || (isAway(first) && isHome(second))
    }

    /**
     * How a guide or a feed might write [team], as [SportsCatalog.teamNames] has it: the one
     * normaliser both sides of the join go through.
     */
    fun names(team: TeamInfo, league: League?): List<String> {
        // No league at hand (an event from a feed we couldn't place): the same rules, without the
        // league's own habits (school names, football's short names).
        val known = league ?: League(id = team.leagueId, sport = Sport.OTHER, name = team.leagueId, short = team.leagueId.take(6))
        val (strong, weak) = SportsCatalog.teamNames(team, known)
        return strong + weak
    }

    /**
     * Whether the feed's [rawTeam] is one of [variants]: case and accents ignored, and a variant
     * that is a whole-word part of the other name tolerated both ways ("Brighton" is
     * "Brighton & Hove Albion", "Arsenal FC" is "Arsenal").
     */
    fun nameMatches(rawTeam: String, variants: List<String>): Boolean {
        val bare = SportsCatalog.norm(rawTeam).trim()
        if (bare.length < 3) return false
        val padded = " $bare "
        return variants.any { v ->
            val variant = v.trim()
            variant.length >= 3 && (padded.contains(" $variant ") || " $variant ".contains(padded))
        }
    }

    /**
     * Which event wins when they all fit: one on now, then the nearest kick-off, then the more
     * specific name, and only then the feed's league.
     */
    private fun preference(event: SportsEvent, league: League?): Comparator<AddonEvent> =
        compareBy<AddonEvent> { if (it.isLive) 0 else 1 }
            .thenBy { abs(event.startMs - it.kickoffMs) }
            .thenBy { addon -> addon.teams.sumOf { it.name.length } }
            .thenByDescending { leagueRank(league, it) }
            .thenBy { it.id }

    /** [LEAGUE_MATCH], [LEAGUE_UNKNOWN] or [LEAGUE_CONFLICT] for the feed's league against [league]. */
    fun leagueRank(league: League?, addon: AddonEvent): Int {
        if (league == null) return LEAGUE_UNKNOWN
        val id = numericLeagueId(league.id) ?: return LEAGUE_UNKNOWN
        // A snapshot an older build saved, or a feed that doesn't say: no evidence either way.
        if (addon.leagueId <= 0L) return LEAGUE_UNKNOWN
        if (id == addon.leagueId) return LEAGUE_MATCH
        // The same display name in another country is no evidence; a different one is a hint.
        return if (sameDisplayName(league, addon)) LEAGUE_UNKNOWN else LEAGUE_CONFLICT
    }

    /** The feed's own id for a league we know by "tsdb/4551": 4551 (null when ours has no such id). */
    fun numericLeagueId(leagueId: String): Long? =
        Regex("""(\d{2,})$""").find(leagueId.substringAfterLast('/'))?.groupValues?.get(1)?.toLongOrNull()

    private fun sameDisplayName(league: League, addon: AddonEvent): Boolean {
        val feed = SportsCatalog.norm(addon.leagueName).trim()
        if (feed.length < 3) return false
        return feed == SportsCatalog.norm(league.name).trim() || league.guideWords.any { SportsCatalog.norm(it).trim() == feed }
    }
}
