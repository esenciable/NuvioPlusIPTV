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
 * competition's own site and rewritten into the shape of an ESPN scoreboard. Everything downstream
 * (the parser, logos, colours, clocks, the channel matcher) then works without knowing the source.
 *
 *  - EuroLeague: its own v2 API, no key, one JSON document holding the whole season.
 *  - ACB: no API exists (api.acb.com doesn't resolve and the back office wants credentials), so the
 *    match list is read out of the Next.js payload embedded in the page. Brittle by nature: if ACB
 *    redeploys and the payload moves, this throws and the page keeps what it had cached.
 *
 * Neither source publishes a clock, so a game in progress shows its quarter instead of a countdown.
 */
internal class BasketballFeeds(private val get: (String) -> String) {

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
     * The page has no API behind it, so the round is read out of the page itself: the one it opens
     * with (a pointer to the current round), plus the round either side of it, which is enough to
     * cover the weeks the day chips reach.
     */
    private fun acb(): String {
        val base = acbPage(get(ACB_URL), round = null)
        val current = base.currentRoundId
        val pages = ArrayList<AcbPage>()
        pages += base
        if (current != null) {
            for (round in intArrayOf(current - 1, current + 1)) {
                runCatching { acbPage(get("$ACB_URL?jornada=$round"), round) }.getOrNull()?.let { pages += it }
            }
        }
        val seen = HashSet<String>()
        val events = ArrayList<JSONObject>()
        for (page in pages) {
            for (match in page.matches) {
                val id = number(match, "id")?.toString() ?: continue
                if (!seen.add(id)) continue
                acbEvent(page, match)?.let { events += it }
            }
        }
        // An empty answer means the payload moved: fail so the page keeps the rounds it had.
        if (events.isEmpty()) throw IOException("ACB answered no matches")
        return scoreboardJson(events)
    }

    /**
     * The page without a `jornada` shows the competition's current round, and `currentRoundId` names
     * that round whichever one was asked for — so the round a page belongs to comes from the request
     * and only falls back to the pointer when nothing was asked.
     */
    private fun acbPage(html: String, round: Int?): AcbPage {
        val payload = flightPayload(html)
        if (payload.isEmpty()) throw IOException("ACB page carried no payload")
        val roots = ArrayList<Any>()
        // One flight chunk carries a row per line; a single document is taken as it comes.
        val whole = runCatching { JSONObject(payload) }.getOrNull()
            ?: runCatching { JSONArray(payload) }.getOrNull()
        if (whole != null) {
            roots += whole
        } else {
            for (line in payload.split('\n')) {
                val id = line.substringBefore(':', "")
                if (id.isEmpty() || id.length > ROW_ID_MAX || !id.all { it in '0'..'9' || it in 'a'..'f' }) continue
                val body = line.substring(id.length + 1)
                val row = runCatching { JSONObject(body) }.getOrNull()
                    ?: runCatching { JSONArray(body) }.getOrNull()
                    ?: continue
                roots += row
            }
        }
        var node: JSONObject? = null
        var rounds: JSONArray? = null
        for (root in roots) {
            if (node == null) node = findRoundData(root)
            if (rounds == null) rounds = findRounds(root)
            if (node != null && rounds != null) break
        }
        val found = node ?: throw IOException("ACB page carried no round")
        val current = Regex("\"currentRoundId\":(\\d+)").find(payload)?.groupValues?.get(1)?.toIntOrNull()
        return AcbPage(
            currentRoundId = current,
            roundLabel = roundName(rounds, round ?: current),
            teams = found.optJSONArray("teams") ?: JSONArray(),
            matches = found.optJSONArray("matches").objects()
        )
    }

    private fun roundName(rounds: JSONArray?, id: Int?): String? {
        if (id == null) return null
        return rounds.objects().firstOrNull { number(it, "id") == id }?.let { text(it, "name") }
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    /**
     * Next.js streams its page as escaped string chunks pushed into `self.__next_f`. Unescaping them
     * (by way of a JSON array, which is what they already are) gives one document with the round in it.
     *
     * Scanned by hand rather than with a pattern: the alternation over a 20 KB literal makes the
     * regex engine backtrack itself into a StackOverflowError.
     */
    private fun flightPayload(html: String): String {
        val literals = StringBuilder("[")
        var found = 0
        var at = 0
        while (true) {
            val marker = html.indexOf(FLIGHT_MARKER, at)
            if (marker < 0) break
            val open = html.indexOf('"', marker + FLIGHT_MARKER.length)
            if (open < 0) break
            var i = open + 1
            while (i < html.length) {
                val c = html[i]
                if (c == '\\') i += 2 else if (c == '"') break else i++
            }
            if (i >= html.length) break
            if (found > 0) literals.append(',')
            literals.append(html, open, i + 1)
            found++
            at = i + 1
        }
        if (found == 0) return ""
        literals.append(']')
        val chunks = runCatching { JSONArray(literals.toString()) }.getOrNull() ?: return ""
        val out = StringBuilder()
        for (i in 0 until chunks.length()) out.append(chunks.optString(i))
        return out.toString()
    }

    /**
     * The round's own object, wherever its row buries it: the one carrying both the team list the
     * matches point into and the matches themselves.
     */
    private fun findRoundData(node: Any?, depth: Int = 0): JSONObject? {
        if (depth > DEPTH_LIMIT) return null
        when (node) {
            is JSONObject -> {
                val matches = node.optJSONArray("matches")
                if (node.optJSONArray("teams") != null && matches != null &&
                    matches.optJSONObject(0)?.has("homeTeam") == true
                ) return node
                for (key in node.keys()) findRoundData(node.opt(key), depth + 1)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findRoundData(node.opt(i), depth + 1)?.let { return it }
        }
        return null
    }

    /** The competition's round list (id and name), to say which round a page is. */
    private fun findRounds(node: Any?, depth: Int = 0): JSONArray? {
        if (depth > DEPTH_LIMIT) return null
        when (node) {
            is JSONArray -> {
                if (node.optJSONObject(0)?.has("roundNumber") == true) return node
                for (i in 0 until node.length()) findRounds(node.opt(i), depth + 1)?.let { return it }
            }
            is JSONObject -> for (key in node.keys()) findRounds(node.opt(key), depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * A match names its two teams by reference into the page's own team list
     * (`"$3d:props:children:…:data:teams:11"`): the number is the index. A team already written out
     * in full is taken as it is.
     */
    private fun acbTeam(match: JSONObject, key: String, teams: JSONArray): JSONObject? =
        when (val value = match.opt(key)) {
            is JSONObject -> value
            is String -> value.substringAfterLast(':').toIntOrNull()?.let { teams.optJSONObject(it) }
            else -> null
        }

    private fun acbEvent(page: AcbPage, m: JSONObject): JSONObject? {
        val id = number(m, "id") ?: return null
        val start = parseUtc(text(m, "startDateTime")) ?: return null
        val home = acbTeam(m, "homeTeam", page.teams) ?: return null
        val away = acbTeam(m, "awayTeam", page.teams) ?: return null

        val status = text(m, "matchStatus").orEmpty()
        val quarter = number(m, "currentQuarter") ?: 0
        val homeScore = number(m, "homeScore")
        val awayScore = number(m, "awayScore")
        // Only two values were ever seen: NOT_STARTED and FINALIZED. Anything else is taken as a
        // game in progress rather than dropped, and a quarter beyond the fourth is overtime.
        val state = when {
            status == "NOT_STARTED" -> "pre"
            status == "FINALIZED" -> "post"
            status.isNotEmpty() -> "in"
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

        val channels = LinkedHashSet<String>()
        m.optJSONObject("broadcastChannels")?.let { c ->
            for (group in arrayOf("primary", "secondary")) {
                val list = c.optJSONArray(group) ?: continue
                for (i in 0 until list.length()) text(list.optJSONObject(i), "name")?.let { channels += it }
            }
        }

        return eventJson(
            id = id.toString(),
            startMs = start,
            state = state,
            period = if (state == "pre") 0 else quarter,
            shortDetail = if (state == "in") quarterLabel(quarter) else null,
            headline = page.roundLabel,
            venue = null,
            broadcasts = channels.toList(),
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

    private data class AcbPage(
        val currentRoundId: Int?,
        val roundLabel: String?,
        val teams: JSONArray,
        val matches: List<JSONObject>
    )

    private companion object {
        const val EUROLEAGUE_V2 = "https://api-live.euroleague.net/v2"
        const val ACB_URL = "https://acb.com/es/liga/partidos"

        /** A basketball game plus its breaks: past this, a game still marked unplayed is taken as over. */
        const val LIVE_WINDOW_MS = 150L * 60 * 1000

        /** Serialised rows name themselves with a short hex id. */
        const val ROW_ID_MAX = 4

        /** How deep into a serialised row the round's own object can sit. */
        const val DEPTH_LIMIT = 40

        val FLIGHT_MARKER = "self.__next_f.push([1,"

        val ISO_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm"
        )
    }
}
