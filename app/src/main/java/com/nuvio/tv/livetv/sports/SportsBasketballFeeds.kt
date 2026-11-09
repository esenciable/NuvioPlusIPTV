package com.nuvio.tv.livetv.sports

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The two basketball competitions ESPN doesn't carry: Spain's ACB (Liga Endesa) and the EuroLeague.
 *
 * Neither can be read from ESPN — the ACB isn't in its catalogue at all, and its EuroLeague entry
 * (league 52) is an empty shell with no events and no standings — so each is read from the
 * competition's own API and rewritten into the shape of an ESPN scoreboard. Everything downstream
 * (the parser, logos, colours, clocks, the channel matcher) then works without knowing the source.
 *
 *  - EuroLeague: its own v2 API, no key, one JSON document holding the whole season.
 *  - ACB: its own JSON API, one answer per round. It asks for an `X-APIKEY` header, and the key is
 *    the one ACB's own site ships inside its public JavaScript bundle: public, but ACB can rotate
 *    it, so it lives in one constant and a rejected key fails loudly instead of looking like an
 *    empty round.
 *
 * Neither source publishes a clock, so a game in progress shows its quarter instead of a countdown.
 */
internal class BasketballFeeds(
    private val get: (String) -> String,
    /** The same fetch with request headers: the ACB API wants a key and [get] sends none. */
    private val getWithHeaders: (String, Map<String, String>) -> String = { url, _ -> get(url) },
) {

    /** A scoreboard document for [league], in the shape ESPN would have answered with. */
    fun scoreboard(league: League): String = when (league.provider) {
        SportProvider.EUROLEAGUE -> euroleague()
        SportProvider.ACB -> acb()
        SportProvider.ESPN -> throw IllegalArgumentException("${league.id} comes from ESPN")
    }

    // ------------------------------------------------------------------ EuroLeague

    /**
     * The whole season in one answer: the fixtures still to play and the results so far. The season
     * is a letter plus the year it starts in, so in July the new one begins; if that year's document
     * is empty the previous season is the one still being played out.
     */
    private fun euroleague(): String {
        val now = System.currentTimeMillis()
        val year = seasonStartYear(now)
        var games = runCatching { euroleagueGames("E$year") }.getOrNull().orEmpty()
        if (games.isEmpty()) games = runCatching { euroleagueGames("E${year - 1}") }.getOrNull().orEmpty()
        val events = games.mapNotNull { euroleagueEvent(it, now) }
        return scoreboardJson(events)
    }

    private fun seasonStartYear(now: Long): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = now }
        val year = cal.get(Calendar.YEAR)
        return if (cal.get(Calendar.MONTH) >= Calendar.JULY) year else year - 1
    }

    private fun euroleagueGames(seasonCode: String): List<JSONObject> {
        val raw = get("$EUROLEAGUE_V2/competitions/E/seasons/$seasonCode/games?limit=500")
        // The same address has answered with XML for some seasons. Don't parse that as JSON.
        if (!raw.trimStart().startsWith("{")) throw IOException("EuroLeague answered ${raw.take(40)}")
        val data = JSONObject(raw).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }
    }

    private fun euroleagueEvent(g: JSONObject, now: Long): JSONObject? {
        val id = text(g, "identifier") ?: text(g, "gameCode") ?: return null
        val start = parseUtc(text(g, "utcDate") ?: text(g, "date")) ?: return null
        val homeSide = g.optJSONObject("local") ?: return null
        val awaySide = g.optJSONObject("road") ?: return null
        val homeClub = homeSide.optJSONObject("club") ?: return null
        val awayClub = awaySide.optJSONObject("club") ?: return null

        val homeScore = number(homeSide, "score")
        val awayScore = number(awaySide, "score")
        val homePeriods = partials(homeSide)
        val awayPeriods = partials(awaySide)
        val played = g.optBoolean("played", false)
        val period = maxOf(homePeriods.size, awayPeriods.size)

        // "played" is the only reliable signal: gameStatus says "Confirmed" even once it's over.
        val state = when {
            played -> "post"
            start > now -> "pre"
            now - start <= LIVE_WINDOW_MS && (homeScore != null || awayScore != null) -> "in"
            now - start > LIVE_WINDOW_MS && period > 0 -> "post"
            else -> "pre"
        }
        // A game that has not started yet carries no score, whatever the document says.
        val score = state != "pre"

        val winner = text(g.optJSONObject("winner"), "code")
        val homeWon = state == "post" && (winner == text(homeClub, "code") || (winner == null && homeScore != null && awayScore != null && homeScore > awayScore))
        val awayWon = state == "post" && (winner == text(awayClub, "code") || (winner == null && homeScore != null && awayScore != null && awayScore > homeScore))

        val homeTeam = teamJson(
            id = text(homeClub, "code"),
            name = text(homeClub, "abbreviatedName") ?: text(homeClub, "name"),
            full = text(homeClub, "name"),
            abbr = text(homeClub, "code"),
            colorHex = null,
            logo = text(homeClub.optJSONObject("images"), "crest")
        )
        val awayTeam = teamJson(
            id = text(awayClub, "code"),
            name = text(awayClub, "abbreviatedName") ?: text(awayClub, "name"),
            full = text(awayClub, "name"),
            abbr = text(awayClub, "code"),
            colorHex = null,
            logo = text(awayClub.optJSONObject("images"), "crest")
        )

        return eventJson(
            id = id,
            startMs = start,
            state = state,
            period = if (state == "in") period else if (state == "post") period else 0,
            shortDetail = if (state == "in") quarterLabel(period) else null,
            headline = euroleagueRound(g),
            venue = text(g.optJSONObject("venue"), "name"),
            broadcasts = emptyList(),
            home = homeTeam,
            away = awayTeam,
            homeScore = if (score) homeScore else null,
            awayScore = if (score) awayScore else null,
            homePeriods = if (score) homePeriods else emptyList(),
            awayPeriods = if (score) awayPeriods else emptyList(),
            homeWon = homeWon,
            awayWon = awayWon
        )
    }

    /** "Round 10" through the regular season, the phase's own name from the play-offs on. */
    private fun euroleagueRound(g: JSONObject): String? {
        val phase = text(g.optJSONObject("phaseType"), "code")
        if (phase != null && phase != "RS") return text(g.optJSONObject("phaseType"), "name")
        return text(g, "roundAlias") ?: text(g, "roundName") ?: text(g, "round")?.let { "Round $it" }
    }

    private fun partials(side: JSONObject): List<Int?> {
        val p = side.optJSONObject("partials") ?: return emptyList()
        val out = ArrayList<Int?>()
        for (n in 1..4) out += number(p, "partials$n")
        while (out.isNotEmpty() && out.last() == null) out.removeAt(out.size - 1)
        return out
    }

    // ------------------------------------------------------------------ ACB

    /**
     * The competition's own API, one request per round: the round it currently points at plus the
     * rounds either side of it, which is enough to cover the weeks the day chips reach. A round that
     * fails is skipped, so the rest of the list still shows.
     */
    private fun acb(): String {
        val base = acbRound(roundId = null)
        val wanted = LinkedHashSet<Int?>()
        wanted += base.round
        neighbour(base, base.round, -1)?.let { wanted += it }
        neighbour(base, base.round, +1)?.let { wanted += it }

        val seen = HashSet<String>()
        val events = ArrayList<JSONObject>()
        for (roundId in wanted) {
            val round = if (roundId == base.round) base
            else runCatching { acbRound(roundId) }.getOrNull() ?: continue
            for (match in round.matches) {
                val id = number(match, "id")?.toString() ?: continue
                if (!seen.add(id)) continue
                acbEvent(round, match)?.let { events += it }
            }
        }
        // An empty answer means the API changed under us: fail so the page keeps the rounds it had.
        if (events.isEmpty()) throw IOException("ACB answered no matches")
        return scoreboardJson(events)
    }

    /**
     * One round of the competition. Without `roundId` the answer is the round the competition
     * currently points at, which is what the site opens with.
     *
     * Only `roundId` is ever sent: the API also accepts `round`, answers 200 to it and then ignores
     * the value, so a wrong-looking request would look like a success.
     */
    private fun acbRound(roundId: Int?): AcbRound {
        val url = buildString {
            append(ACB_API).append("?competitionId=").append(ACB_COMPETITION)
            append("&isRoundSelected=true")
            if (roundId != null) append("&roundId=").append(roundId)
        }
        val raw = getWithHeaders(url, ACB_HEADERS)
        if (!raw.trimStart().startsWith("{")) throw IOException("ACB answered ${raw.take(40)}")
        val doc = JSONObject(raw)
        val selected = doc.optJSONObject("selectedFilters")
        val selectedRound = number(selected, "round")
        val teams = HashMap<Int, JSONObject>()
        for (team in doc.optJSONArray("teams").objects()) number(team, "id")?.let { teams[it] = team }
        val rounds = doc.optJSONObject("availableFilters")?.optJSONArray("rounds").objects()
        return AcbRound(
            round = selectedRound,
            rounds = rounds,
            roundLabel = acbRoundLabel(rounds, roundId ?: selectedRound),
            teams = teams,
            matches = doc.optJSONArray("matches").objects()
        )
    }

    /** "Jornada 7": the competition's round list carries the number, a round object does not. */
    private fun acbRoundLabel(rounds: List<JSONObject>, roundId: Int?): String? {
        if (roundId == null) return null
        val round = rounds.firstOrNull { number(it, "id") == roundId } ?: return null
        return number(round, "roundNumber")?.takeIf { it > 0 }?.let { "Jornada $it" }
    }

    /** The round [step] places away from [roundId] in the competition's own list, if there is one. */
    private fun neighbour(round: AcbRound, roundId: Int?, step: Int): Int? {
        if (roundId == null || round.rounds.isEmpty()) return null
        val index = round.rounds.indexOfFirst { number(it, "id") == roundId }
        if (index < 0) return null
        return round.rounds.getOrNull(index + step)?.let { number(it, "id") }
    }

    private fun acbEvent(round: AcbRound, m: JSONObject): JSONObject? {
        val id = number(m, "id") ?: return null
        val start = parseUtc(text(m, "startDateTime")) ?: return null
        // Teams are named by id and listed once for the whole round, so they resolve by lookup.
        val home = round.teams[number(m, "homeTeamId")] ?: return null
        val away = round.teams[number(m, "awayTeamId")] ?: return null

        val status = text(m, "matchStatus").orEmpty()
        val quarter = number(m, "currentQuarter") ?: 0
        val homeScore = number(m, "homeScore")
        val awayScore = number(m, "awayScore")
        // NOT_STARTED, NOT_SCHEDULED and FINALIZED are the only values ever measured, and no
        // in-progress one was seen. So an unknown status is read as live only when the rest of the
        // answer agrees: a score already on the board and a start inside the game's own window.
        // Anything else stays scheduled — a game is never labelled live on a guess.
        val inPlay = homeScore != null && awayScore != null &&
            System.currentTimeMillis() - start in 0L..LIVE_WINDOW_MS
        val state = when {
            status == "FINALIZED" -> "post"
            status == "NOT_STARTED" || status == "NOT_SCHEDULED" -> "pre"
            inPlay -> "in"
            else -> "pre"
        }
        val score = state != "pre"

        val homeTeam = teamJson(
            id = text(home, "id"),
            name = text(home, "shortName") ?: text(home, "fullName"),
            full = text(home, "fullName"),
            abbr = text(home, "abbreviatedName"),
            colorHex = text(home, "primaryColorHex"),
            logo = text(home, "logo")
        )
        val awayTeam = teamJson(
            id = text(away, "id"),
            name = text(away, "shortName") ?: text(away, "fullName"),
            full = text(away, "fullName"),
            abbr = text(away, "abbreviatedName"),
            colorHex = text(away, "primaryColorHex"),
            logo = text(away, "logo")
        )

        return eventJson(
            id = id.toString(),
            startMs = start,
            state = state,
            period = if (state == "pre") 0 else quarter,
            shortDetail = if (state == "in") quarterLabel(quarter) else null,
            headline = round.roundLabel,
            venue = null,
            broadcasts = acbChannels(m),
            home = homeTeam,
            away = awayTeam,
            homeScore = if (score) homeScore else null,
            awayScore = if (score) awayScore else null,
            homePeriods = emptyList(),
            awayPeriods = emptyList(),
            homeWon = state == "post" && homeScore != null && awayScore != null && homeScore > awayScore,
            awayWon = state == "post" && homeScore != null && awayScore != null && awayScore > homeScore
        )
    }

    /**
     * The ways to watch a game. The API sends a flat list of `{broadcaster, primary, order}` —
     * not the `{primary[], secondary[]}` object the site's page payload used to carry — so the
     * primary channel is a flag per entry, and a non-primary one is still a way to watch.
     */
    private fun acbChannels(m: JSONObject): List<String> {
        val entries = m.optJSONArray("broadcastChannels").objects()
        if (entries.isEmpty()) return emptyList()
        val primary = entries.filter { it.optBoolean("primary", false) }
        val chosen = if (primary.isEmpty()) entries else primary
        return chosen.sortedBy { number(it, "order") ?: Int.MAX_VALUE }
            .mapNotNull { text(it, "broadcaster") }
            .distinct()
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    // ------------------------------------------------------------------ ESPN's shape

    private fun scoreboardJson(events: List<JSONObject>): String {
        val array = JSONArray()
        events.forEach { array.put(it) }
        return JSONObject().put("events", array).toString()
    }

    @Suppress("LongParameterList")
    private fun eventJson(
        id: String,
        startMs: Long,
        state: String,
        period: Int,
        shortDetail: String?,
        headline: String?,
        venue: String?,
        broadcasts: List<String>,
        home: JSONObject,
        away: JSONObject,
        homeScore: Int?,
        awayScore: Int?,
        homePeriods: List<Int?>,
        awayPeriods: List<Int?>,
        homeWon: Boolean,
        awayWon: Boolean
    ): JSONObject {
        val competition = JSONObject()
        competition.put("status", statusJson(state, period, shortDetail))
        if (!venue.isNullOrBlank()) competition.put("venue", JSONObject().put("fullName", venue))
        if (!headline.isNullOrBlank()) {
            competition.put("notes", JSONArray().put(JSONObject().put("headline", headline)))
        }
        if (broadcasts.isNotEmpty()) {
            val list = JSONArray()
            broadcasts.forEach { list.put(JSONObject().put("names", JSONArray().put(it))) }
            competition.put("broadcasts", list)
        }
        competition.put(
            "competitors",
            JSONArray()
                .put(competitor(team = home, home = true, score = homeScore, periods = homePeriods, won = homeWon))
                .put(competitor(team = away, home = false, score = awayScore, periods = awayPeriods, won = awayWon))
        )
        return JSONObject()
            .put("id", id)
            .put("date", isoUtc(startMs))
            .put("competitions", JSONArray().put(competition))
    }

    private fun competitor(team: JSONObject, home: Boolean, score: Int?, periods: List<Int?>, won: Boolean): JSONObject {
        val out = JSONObject()
            .put("homeAway", if (home) "home" else "away")
            .put("team", team)
            .put("winner", won)
        if (score != null) out.put("score", score.toString())
        if (periods.isNotEmpty()) {
            val lines = JSONArray()
            periods.forEach { p ->
                val line = JSONObject()
                if (p != null) line.put("displayValue", p.toString())
                lines.put(line)
            }
            out.put("linescores", lines)
        }
        return out
    }

    private fun statusJson(state: String, period: Int, shortDetail: String?): JSONObject {
        val type = JSONObject()
            .put("state", state)
            .put(
                "name",
                when (state) {
                    "post" -> "STATUS_FINAL"
                    "in" -> "STATUS_IN_PROGRESS"
                    else -> "STATUS_SCHEDULED"
                }
            )
        if (!shortDetail.isNullOrBlank()) {
            type.put("shortDetail", shortDetail)
            type.put("detail", shortDetail)
        }
        val status = JSONObject().put("type", type)
        if (period > 0) status.put("period", period)
        return status
    }

    private fun teamJson(
        id: String?,
        name: String?,
        full: String?,
        abbr: String?,
        colorHex: String?,
        logo: String?
    ): JSONObject {
        val long = full ?: name ?: abbr ?: "?"
        val short = name ?: abbr ?: long
        val team = JSONObject()
            .put("id", id ?: abbr ?: long)
            .put("displayName", long)
            .put("name", long)
            .put("shortDisplayName", short)
            .put("abbreviation", abbr ?: "")
            .put("location", long)
        // ESPN writes colors as bare hex; the parser puts the alpha back.
        val bare = colorHex?.trim()?.removePrefix("#")
        if (bare != null && bare.length == 6) {
            team.put("color", bare)
            team.put("alternateColor", bare)
        }
        if (!logo.isNullOrBlank()) team.put("logo", logo)
        return team
    }

    /** "Q3" for a game in progress; a fifth quarter or more is overtime. */
    private fun quarterLabel(quarter: Int): String = when {
        quarter <= 0 -> ""
        quarter == 1 -> "Q1"
        quarter == 2 -> "Q2"
        quarter == 3 -> "Q3"
        quarter == 4 -> "Q4"
        else -> "OT${quarter - 4}"
    }.takeIf { it.isNotBlank() } ?: ""

    private fun isoUtc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(ms)

    private fun parseUtc(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        for (pattern in ISO_PATTERNS) {
            val f = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            runCatching { return f.parse(raw)?.time }
        }
        return null
    }

    private fun text(o: JSONObject?, name: String): String? =
        o?.optString(name, "")?.takeIf { it.isNotBlank() && it != "null" }

    private fun number(o: JSONObject?, name: String): Int? =
        o?.optInt(name, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }

    /** One round of the ACB competition: who plays, against whom, and the rounds either side. */
    private data class AcbRound(
        val round: Int?,
        val rounds: List<JSONObject>,
        val roundLabel: String?,
        val teams: Map<Int, JSONObject>,
        val matches: List<JSONObject>
    )

    private companion object {
        const val EUROLEAGUE_V2 = "https://api-live.euroleague.net/v2"

        /** The competition's own API; one answer per round. */
        const val ACB_API = "https://api2.acb.com/api/seasondata/Competition/matches"

        /** Liga Endesa. The same endpoint serves the Copa, the Supercopa and the Minicopa. */
        const val ACB_COMPETITION = 1

        /**
         * ACB's own front-end key. It is not a secret — it ships inside the public JavaScript
         * bundle of acb.com, which is how it was found — but ACB can rotate it at any time, so a
         * 401 from here has to surface as a failure, never as "no matches this round".
         */
        const val ACB_API_KEY = "0dd94928-6f57-4c08-a3bd-b1b2f092976e"

        val ACB_HEADERS = mapOf(
            "X-APIKEY" to ACB_API_KEY,
            "Accept" to "application/json",
            "Origin" to "https://acb.com",
            "Referer" to "https://acb.com/"
        )

        /** A basketball game plus its breaks: past this, a game still marked unplayed is taken as over. */
        const val LIVE_WINDOW_MS = 150L * 60 * 1000

        val ISO_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm"
        )
    }
}
