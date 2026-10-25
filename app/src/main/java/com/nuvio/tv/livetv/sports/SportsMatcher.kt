package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.CatchupUrlBuilder
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.SportsCatalog.hasWord
import com.nuvio.tv.livetv.sports.SportsCatalog.norm

/**
 * Finds which of your channels show each game, in four ways (best first):
 *  1. your TV guide: a show around kick-off naming both teams (or the event),
 *  2. the channel's own name ("NFL 03: Bears @ Lions", common for event channels),
 *  3. the network ESPN lists (FOX, ESPN, TNT…) with a sports show on at the time,
 *  4. channels you picked yourself with Fix Match (and never the ones you said were wrong).
 * Works for any country: UK users get Sky Sports / TNT Sports from their own guide.
 */
internal class SportsMatcher(private val db: EpgDatabase) {

    class Input(
        val events: List<SportsEvent>,
        /** Channels you can see (not hidden, not locked). */
        val channels: List<LiveChannel>,
        /** Channel key -> picture quality seen ("4K", "FHD", "HD", "SD"). */
        val quality: Map<String, String>,
        /** Your guide time shift (Live TV settings), in ms: the database keeps the guide's own times. */
        val shiftMs: Long,
        val settings: SportsSettings,
        val now: Long
    )

    class Result(val matches: Map<String, List<ChannelMatch>>, val summary: String)

    private class Found(val key: String, var score: Int, var how: String, var program: EpgProgram?)

    /** Disc golf is its own sport: its events keep their matches, and no other event takes its shows. */
    private fun isDiscGolf(ev: SportsEvent): Boolean =
        hasWord(norm(ev.title), "disc golf") || ev.leagueId.contains("disc", ignoreCase = true)

    fun match(input: Input): Result {
        val byKey = input.channels.associateBy { it.key }
        val rank = preferenceRank(input.settings)
        val names = input.channels.mapNotNull { ch -> if (ch.name.length < 6) null else ch to norm(ch.name.replace("+", " plus ").replace("@", " at ")) }
        val found = HashMap<String, HashMap<String, Found>>()
        fun add(eventId: String, key: String, score: Int, how: String, program: EpgProgram?) {
            if (key !in byKey || score <= 0) return
            val m = found.getOrPut(eventId) { HashMap() }
            val cur = m[key]
            if (cur == null || score > cur.score) m[key] = Found(key, score, how, program ?: cur?.program)
            else if (cur.program == null && program != null) cur.program = program
        }

        // Events that run for days (a golf tournament, an F1 weekend from practice to the race) have
        // no single start time: while they're on, treat them as starting now.
        fun multiDay(ev: SportsEvent) = !ev.hasTime && ev.endMs != null
        // Their ids: with no start time of their own, only a show that names the competition ("Formula 1",
        // "Grand Prix"…) counts, not one that merely shares a word or two with the title ("United States").
        val noStart = HashSet<String>()
        val events = input.events.mapNotNull { ev ->
            when {
                ev.hasTime -> ev
                !multiDay(ev) -> null
                // The feed's end date is the start of the last day (Sunday 00:00): the event runs through that day.
                ev.endMs!! + 24 * HOUR < input.now -> null
                ev.startMs >= input.now -> ev
                else -> ev.copy(startMs = input.now)
            }?.also { if (!ev.hasTime) noStart.add(ev.id) }
        }
        val wanted = events.filter { ev ->
            ev.state != GameState.OFF &&
                ev.startMs in (input.now - 8 * HOUR)..(input.now + AHEAD_MS)
        }
        // A channel you picked yourself shows straight away, however far off the game is (the
        // search above only looks a couple of days ahead).
        val picked = events.filter { ev ->
            ev.state != GameState.OFF && ev.startMs >= input.now - 8 * HOUR &&
                (ev.id in input.settings.eventChannels || ev.leagueId in input.settings.leagueChannels ||
                    (input.settings.teamChannels.isNotEmpty() && ev.sides.any { teamChannelKey(ev.leagueId, it.team.id) in input.settings.teamChannels }))
        }
        val byId = (wanted + picked).associateBy { it.id }
        var byGuide = 0; var byName = 0; var byNetwork = 0

        // Shows found in the guide already know their channels.
        wanted.filter { it.source == EventSource.GUIDE }.forEach { ev ->
            ev.guideHits.forEach { (key, p) -> add(ev.id, key, 100, "Guide: ${p.title}", p) }
        }

        val teamEvents = wanted.filter { it.source != EventSource.GUIDE }
        val terms = teamEvents.associate { ev -> ev.id to termsFor(ev) }

        // ---- 1. The guide, a half-hour slot at a time.
        teamEvents.groupBy { it.startMs / (30 * MINUTE) }.forEach { (_, slot) ->
            val slotStart = slot.minOf { it.startMs }
            val slotEnd = slot.maxOf { it.startMs }
            val words = slot.flatMap { ev -> terms[ev.id]?.sqlWords.orEmpty() }.distinct()
            if (words.isEmpty()) return@forEach
            val hits = runCatching {
                db.findByWords(
                    fromMs = slotStart - 100 * MINUTE - input.shiftMs,
                    toMs = slotEnd + 70 * MINUTE - input.shiftMs,
                    minStopMs = slotStart + 10 * MINUTE - input.shiftMs,
                    words = words
                )
            }.getOrDefault(emptyList())
            if (hits.isEmpty()) return@forEach
            val prepared = hits.mapNotNull { (key, p0) ->
                val ch = byKey[key] ?: return@mapNotNull null
                val p = if (input.shiftMs == 0L) p0 else p0.copy(startMs = p0.startMs + input.shiftMs, stopMs = p0.stopMs + input.shiftMs)
                if (fillerOrLoop(ch, p)) return@mapNotNull null
                val nt = norm(p.title)
                if (SportsCatalog.NOT_LIVE_WORDS.any { hasWord(nt, it) }) return@mapNotNull null
                Triple(key, p, nt to norm(p.description.orEmpty()))
            }
            for (ev in slot) {
                val t = terms[ev.id] ?: continue
                for ((key, p, texts) in prepared) {
                    val (nt, nd) = texts
                    // Disc golf shows never count for regular golf.
                    if (!isDiscGolf(ev) && (hasWord(nt, "disc golf") || hasWord(nd, "disc golf"))) continue
                    if (p.startMs > ev.startMs + 60 * MINUTE || p.stopMs < ev.startMs + 15 * MINUTE) continue
                    var score = t.scoreText(nt, nd)
                    // No start time (a race weekend, a tournament): a show on now that names the competition is enough.
                    if (ev.id in noStart) {
                        if (!t.namesLeague(nt, nd)) continue
                        if (score <= 0) score = 70
                    }
                    if (score <= 0) continue
                    // Pre-game shows often start an hour early on the same channel; much earlier or later is less likely the game.
                    if (p.startMs < ev.startMs - 80 * MINUTE || p.startMs > ev.startMs + 25 * MINUTE) score -= 10
                    add(ev.id, key, score, "Guide: ${p.title}", p)
                }
            }
        }
        byGuide = found.size

        // ---- 2. The channel's own name.
        for (ev in teamEvents) {
            if (ev.state == GameState.POST && ev.startMs < input.now - 5 * HOUR) continue
            val t = terms[ev.id] ?: continue
            for ((ch, nn) in names) {
                if (!isDiscGolf(ev) && hasWord(nn, "disc golf")) continue
                if (t.inName(nn)) { add(ev.id, ch.key, 95, "Channel name", null); byName++ }
                // A channel named for the competition ("Sky Sports F1") carries it while it's on.
                else if (ev.id in noStart && t.leagueInName(nn)) { add(ev.id, ch.key, 78, "Channel name", null); byName++ }
            }
        }

        // ---- 3. The network ESPN lists, with a sports show on at the time.
        val networkEvents = teamEvents.filter { it.broadcasts.isNotEmpty() && it.startMs > input.now - 5 * HOUR }
        val candidates = HashMap<String, List<LiveChannel>>()
        networkEvents.flatMap { it.broadcasts }.map { networkKey(it) }.distinct().forEach { n ->
            val pats = NETWORKS[n] ?: listOf(n).filter { it.length >= 3 }
            if (pats.isEmpty()) return@forEach
            val not = NETWORK_NOT[n].orEmpty()
            candidates[n] = names.filter { (_, nn) -> pats.any { hasWord(nn, it) } && not.none { hasWord(nn, it) } }.map { it.first }
        }
        networkEvents.groupBy { it.startMs / (30 * MINUTE) }.forEach { (_, slot) ->
            val at = slot.minOf { it.startMs } + 20 * MINUTE
            // Your own channel for a network counts even when no channel is named like it (Sky
            // Sports F1 for "Apple TV"): nothing to look through, but the pick still applies.
            for (ev in slot) for (b in ev.broadcasts) {
                input.settings.networkChannels[networkKey(b)]?.let { key -> add(ev.id, key, 75, "Your channel for $b", null) }
            }
            val keys = slot.flatMap { ev -> ev.broadcasts.flatMap { candidates[networkKey(it)].orEmpty() } }.map { it.key }.distinct()
            if (keys.isEmpty()) return@forEach
            val onNow: Map<String, EpgProgram?> = runCatching {
                db.range(at - input.shiftMs, at - input.shiftMs + 1, light = true, channels = keys)
                    .mapValues { (_, l) -> l.firstOrNull()?.let { p -> p.copy(startMs = p.startMs + input.shiftMs, stopMs = p.stopMs + input.shiftMs) } }
            }.getOrDefault(emptyMap())
            for (ev in slot) {
                val league = SportsCatalog.league(ev.leagueId) ?: continue
                val sporty = (league.guideWords + SPORT_WORDS).map { norm(it).trim() }
                for (b in ev.broadcasts) {
                    val n = networkKey(b)
                    input.settings.networkChannels[n]?.let { key -> add(ev.id, key, 75, "Your channel for $b", onNow[key]) }
                    // No start time (a race weekend, a tournament): the network's listing says nothing about what's on now.
                    if (ev.id in noStart) continue
                    val national = n in NATIONAL
                    candidates[n].orEmpty()
                        .map { ch ->
                            val p = onNow[ch.key]
                            val showText = p?.let { norm(it.title + " " + (it.episode ?: "")) }
                            val score = when {
                                !isDiscGolf(ev) && showText != null && hasWord(showText, "disc golf") -> 0
                                p == null -> if (keys.size < 400 && national) 45 else 0
                                // The guide says it's a different game: not this one.
                                terms[ev.id]?.otherGame(showText!!) == true -> 0
                                sporty.any { hasWord(norm(p.title), it) } && SportsCatalog.NOT_LIVE_WORDS.none { hasWord(norm(p.title), it) } ->
                                    if (national) 62 else 52
                                else -> 0
                            }
                            Triple(ch, score, p)
                        }
                        .filter { it.second > 0 }
                        .sortedWith(
                            compareByDescending<Triple<LiveChannel, Int, EpgProgram?>> { rank(it.first) }
                                .thenByDescending { qualityRank(input.quality[it.first.key] ?: nameQuality(it.first.name)) }
                        )
                        .take(if (national) 4 else 6)
                        .forEach { (ch, score, p) -> add(ev.id, ch.key, score, "$b listed by ESPN", p); byNetwork++ }
                }
            }
        }

        // ---- 4. Your own fixes: this game, then every game of a team.
        for (ev in byId.values) {
            input.settings.eventChannels[ev.id]?.substringBeforeLast('|')?.let { add(ev.id, it, 200, "Your pick", null) }
            // Your channel for the whole competition (every Formula 1 round, every PGA Tour event).
            input.settings.leagueChannels[ev.leagueId]?.let { add(ev.id, it, 140, "Your channel for ${SportsCatalog.league(ev.leagueId)?.name ?: "this competition"}", null) }
            if (input.settings.teamChannels.isNotEmpty()) for (side in ev.sides) {
                input.settings.teamChannels[teamChannelKey(ev.leagueId, side.team.id)]?.let { add(ev.id, it, 150, "Your channel for ${side.team.name}", null) }
            }
        }

        val out = HashMap<String, List<ChannelMatch>>()
        for ((eventId, m) in found) {
            val ev = byId[eventId] ?: continue
            val list = m.values
                .filter { "$eventId|${it.key}" !in input.settings.blocked }
                .mapNotNull { f ->
                    val ch = byKey[f.key] ?: return@mapNotNull null
                    ChannelMatch(
                        channel = ch,
                        score = f.score,
                        how = f.how,
                        program = f.program,
                        quality = input.quality[ch.key] ?: nameQuality(ch.name),
                        catchup = ch.catchup != null && CatchupUrlBuilder.isAvailable(ch.catchup, f.program?.startMs ?: ev.startMs, input.now)
                    )
                }
            if (list.isEmpty()) continue
            out[eventId] = sortMatches(list, input.settings.bestChannelFirst, rank).take(8)
        }
        // Events that run for days (golf, race weekends): what was looked for and found, for the report.
        val multi = teamEvents.filter { it.id in noStart }.joinToString(" | ") { ev ->
            val t = terms[ev.id]
            "${ev.title.take(32)}: looked for ${t?.leagueWords?.joinToString("/")}, " +
                "channels named like that ${names.count { (_, nn) -> t?.leagueInName(nn) == true }}, matched ${out[ev.id]?.size ?: 0}"
        }
        val summary = "Sports: ${wanted.size} games and events in the next day or so · channels found for ${out.size} " +
            "(guide $byGuide, channel names $byName, networks $byNetwork)" +
            (if (multi.isNotEmpty()) " · multi-day: $multi" else "")
        return Result(out, summary)
    }

    /**
     * Shows in the guide for sports ESPN doesn't cover (darts, snooker, rugby…) and names you
     * follow: live shows on [dayStart, dayEnd) whose title says so.
     */
    fun guideEvents(
        dayStart: Long,
        dayEnd: Long,
        leagues: List<League>,
        followedWords: List<String>,
        channels: Map<String, LiveChannel>,
        shiftMs: Long,
        now: Long
    ): List<SportsEvent> {
        val leagueWords = leagues.flatMap { l -> l.guideWords.map { it to l } }
        val hits = ArrayList<Pair<String, EpgProgram>>()
        runCatching {
            if (leagueWords.isNotEmpty()) hits += db.findByWords(dayStart - shiftMs, dayEnd - shiftMs, dayStart - shiftMs, leagueWords.map { it.first }, inDescription = false)
            if (followedWords.isNotEmpty()) hits += db.findByWords(dayStart - shiftMs, dayEnd - shiftMs, dayStart - shiftMs, followedWords, inDescription = true)
        }
        val groups = LinkedHashMap<String, MutableList<Pair<String, EpgProgram>>>()
        val meta = HashMap<String, Pair<League?, String?>>()
        val seen = HashSet<String>()
        for ((key, p0) in hits) {
            val ch = channels[key] ?: continue
            if (!seen.add("$key|${p0.startMs}")) continue
            val p = if (shiftMs == 0L) p0 else p0.copy(startMs = p0.startMs + shiftMs, stopMs = p0.stopMs + shiftMs)
            val nt = norm(p.title)
            val nd = norm(p.description.orEmpty())
            if (SportsCatalog.NOT_LIVE_WORDS.any { hasWord(nt, it) }) continue
            val league = leagueWords.firstOrNull { (w, _) -> hasWord(nt, norm(w).trim()) }?.second
            // 24/7 and loop channels ("NETFLIX WWE", a movie channel called "… NXT"): not live sport.
            if (fillerOrLoop(ch, p)) continue
            // Wrestling words turn up in other shows' titles ("RuPaul's Drag Race - Lip Sync
            // Smackdown", "The Goldbergs - WrestleMania"): the title must start with the show's
            // name, or name the promotion (WWE, AEW, TNA).
            if (league?.sport == Sport.WRESTLING && !wrestlingTitle(cleanTitle(p.title), league)) continue
            val saysLive = isLive(nt, nd)
            // Wrestling's weekly shows are live without the guide saying so, but not on a channel
            // named after the show or league (those run old shows all day).
            if (!saysLive && (league?.liveByDefault != true || league.guideWords.any { hasWord(nameNorm(ch.name), norm(it).trim()) })) continue
            val word = followedWords.firstOrNull { w -> hasWord(nt, norm(w).trim()) || hasWord(nd, norm(w).trim()) }
            if (league == null && word == null) continue
            val clean = cleanTitle(p.title)
            val g = norm(clean).trim() + "|" + (p.startMs / (15 * MINUTE))
            groups.getOrPut(g) { ArrayList() } += key to p
            val prev = meta[g]
            meta[g] = (prev?.first ?: league) to (prev?.second ?: word)
        }
        return groups.map { (g, list) ->
            val p = list.first().second
            val (league, word) = meta[g] ?: (null to null)
            SportsEvent(
                id = "guide:" + g.hashCode().toUInt().toString(16) + ":" + (p.startMs / 60_000),
                leagueId = league?.id ?: OTHER_ID,
                startMs = p.startMs,
                hasTime = true,
                state = when {
                    now >= p.stopMs -> GameState.POST
                    now >= p.startMs -> GameState.LIVE
                    else -> GameState.PRE
                },
                title = cleanTitle(p.title),
                subtitle = p.description?.takeIf { it.isNotBlank() }?.let { d -> d.take(140) + if (d.length > 140) "…" else "" },
                status = null,
                away = null, home = null,
                source = EventSource.GUIDE,
                guideHits = list,
                endMs = p.stopMs,
                word = word
            )
        }
    }

    // ------------------------------------------------------------------ how a game is named

    private class Terms(
        val a: List<String>, val aWeak: List<String>,
        val b: List<String>, val bWeak: List<String>,
        val abbrA: String?, val abbrB: String?,
        val leagueWords: List<String>,
        val eventWords: List<String>,
        val sqlWords: List<String>
    ) {
        private fun any(text: String, list: List<String>) = list.any { hasWord(text, it) }

        fun scoreText(nt: String, nd: String): Int {
            if (a.isNotEmpty() && b.isNotEmpty()) {
                val aT = any(nt, a); val bT = any(nt, b)
                val aW = any(nt, aWeak); val bW = any(nt, bWeak)
                val aD = any(nd, a); val bD = any(nd, b)
                return when {
                    aT && bT -> 100
                    (aT && bW) || (bT && aW) -> 90
                    (aT || aD) && (bT || bD) -> 82
                    (aT || bT) && any(nt, leagueWords) -> 58
                    else -> 0
                }
            }
            val ew = eventWords.count { hasWord(nt, it) || hasWord(nd, it) }
            val lw = any(nt, leagueWords)
            return when {
                lw && ew > 0 -> 92
                ew >= 2 -> 75
                ew > 0 && any(nd, leagueWords) -> 70
                else -> 0
            }
        }

        /** The show names a game ("Bills at Raiders", "Juventus vs. Lazio") that isn't this one. */
        fun otherGame(text: String): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            val us = any(text, a) || any(text, aWeak) || any(text, b) || any(text, bWeak) ||
                listOfNotNull(abbrA, abbrB).any { it.length >= 2 && hasWord(text, it) }
            if (us) return false
            return MATCHUP.containsMatchIn(text)
        }

        /** The show's title or description names the competition itself ("Formula 1", "Grand Prix"). */
        fun namesLeague(nt: String, nd: String): Boolean = leagueWords.any { hasWord(nt, it) || hasWord(nd, it) }

        /** The channel's own name carries the competition's name ("Sky Sports F1", "Formula 1 HD"). */
        fun leagueInName(nn: String): Boolean = leagueWords.any { it.length >= 2 && it != "grand prix" && hasWord(nn, it) }

        fun inName(nn: String): Boolean {
            if (a.isNotEmpty() && b.isNotEmpty()) {
                if (any(nn, a) && any(nn, b)) return true
                val x = abbrA ?: return false
                val y = abbrB ?: return false
                return listOf("$x $y", "$x at $y", "$x vs $y", "$x v $y", "$y $x", "$y vs $x", "$y v $x", "$y at $x").any { hasWord(nn, it) }
            }
            return eventWords.isNotEmpty() && any(nn, leagueWords) && eventWords.count { hasWord(nn, it) } >= 1
        }
    }

    /** A wrestling show's own title: starts with one of the league's names, or names the promotion. */
    private fun wrestlingTitle(title: String, league: League): Boolean {
        val t = norm(title)
        if (listOf("wwe", "aew", "tna", "njpw", "all elite wrestling", "impact wrestling").any { hasWord(t, it) }) return true
        val start = t.trim()
        return league.guideWords.any { w ->
            val n = norm(w).trim()
            start == n || start.startsWith("$n ") || start.startsWith("friday night $n") || start.startsWith("monday night $n")
        }
    }

    /** A channel name without "US:" and quality words, for comparing with show titles. */
    private fun nameNorm(name: String): String =
        norm(name.replace(NAME_PREFIX, "")).replace(NAME_NOISE, " ").replace(Regex(" +"), " ")

    /**
     * Not a real broadcast of a game or show: a 24/7 channel, a block of 6 hours or more, or a
     * listing that's just the channel's own name ("CINE PRO NXT" on CINE PRO NXT).
     */
    private fun fillerOrLoop(ch: LiveChannel, p: EpgProgram): Boolean {
        if (com.nuvio.tv.livetv.data.LiveTvPosterResolver.is247(ch)) return true
        if (p.stopMs - p.startMs >= 6 * HOUR) return true
        val t = nameNorm(cleanTitle(p.title)).trim()
        val c = nameNorm(ch.name).trim()
        if (t.isEmpty() || c.isEmpty()) return t.isEmpty()
        if (t == c) return true
        // A team's own channel ("ATLANTA HAWKS") showing its game ("Atlanta Hawks @ Orlando
        // Magic"): a real game, not the channel's name repeated.
        if (MATCHUP.containsMatchIn(" " + norm(p.title.replace("@", " at ")).trim() + " ")) return false
        // The listing repeats the whole (multi-word) channel name: filler ("CINE PRO NXT").
        return c.contains(' ') && c.length >= 8 && " $t ".contains(" $c ")
    }

    private fun termsFor(ev: SportsEvent): Terms {
        val league = SportsCatalog.league(ev.leagueId)
        val leagueWords = league?.guideWords.orEmpty().map { norm(it).trim() }.filter { it.length >= 2 }
        val away = ev.away
        val home = ev.home
        if (league != null && away != null && home != null) {
            val (a, aw) = SportsCatalog.teamNames(away.team, league)
            val (b, bw) = SportsCatalog.teamNames(home.team, league)
            fun ab(t: TeamInfo) = norm(t.abbr).trim().takeIf { it.length in 2..4 }
            return Terms(a, aw, b, bw, ab(away.team), ab(home.team), leagueWords, emptyList(), (a + b).distinct())
        }
        // A wrestling show: its own name ("RAW #1743" -> "raw", "SmackDown", "NXT"), so a channel
        // called "LIVE EVENT 01 - WWE Monday Night RAW" is found.
        if (league?.sport == Sport.WRESTLING) {
            val show = norm(wrestlingHeadline(ev.title)).trim()
            val words = listOf(show).filter { it.length >= 3 }
            if (words.isNotEmpty()) return Terms(emptyList(), emptyList(), emptyList(), emptyList(), null, null, leagueWords, words, (words + leagueWords).distinct())
        }
        // One event (a race, a fight card, a tournament): its own words ("mexico city", "pantoja").
        val core = norm(ev.title).trim()
            .split(" vs ", " v ", " at ")
            .flatMap { part -> listOf(part.trim()) + part.trim().split(' ').filter { it.length >= 5 } }
            .map { it.trim() }
            .filter { it.length >= 4 && it !in GENERIC_EVENT_WORDS && leagueWords.none { lw -> lw == it } }
            .distinct()
            .take(8)
        return Terms(emptyList(), emptyList(), emptyList(), emptyList(), null, null, leagueWords, core, (core + leagueWords).filter { it.length >= 3 }.distinct())
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        /** How far ahead games are looked for in your channels (most guides cover 2–3 days). */
        const val AHEAD_MS = 56 * HOUR

        private val NAME_PREFIX = Regex("""^\s*[A-Za-z]{2,3}\s*[:|\-]\s*""")
        private val NAME_NOISE = Regex(""" (fhd|uhd|hd|sd|4k|hevc|raw|backup|vip) """)

        /** Shows found in the guide for a followed name that isn't one of the listed sports. */
        const val OTHER_ID = "guide/other"

        private val GENERIC_EVENT_WORDS = setOf(
            "grand prix", "grand", "prix", "championship", "championships", "open", "classic", "tour", "the", "final",
            "finals", "race", "series", "invitational", "masters", "trophy", "fight night", "night", "main card", "prelims"
        )

        /** Words that make a show on a sports network look like a game (not news or a talk show). */
        private val SPORT_WORDS = listOf(
            "football", "basketball", "hockey", "baseball", "soccer", "game", "live", "ncaa", "college", "sports",
            "nfl", "nba", "nhl", "mlb", "mls", "playoff", "playoffs", "bowl", "series"
        )

        private fun isLive(nt: String, nd: String): Boolean =
            nt.startsWith(" live ") || hasWord(nt, "live") || nd.startsWith(" live ") ||
                hasWord(nd, "live coverage") || hasWord(nd, "live action") || hasWord(nd, "coverage of")

        /** "x at y", "x vs y", "x v y" in a show title (text already through norm). */
        private val MATCHUP = Regex("""\s(?:at|vs|v|versus)\s""")

        private val LIVE_PREFIX = Regex("""^\s*(?:live\s*[:\-–|]?\s*)""", RegexOption.IGNORE_CASE)
        // Guides mark first showings: "New: AEW Collision", "AEW Collision (New)", "… - NEW". Only
        // with the colon, dash or brackets ("New England Patriots" and "New York" stay as they are).
        private val NEW_PREFIX = Regex("""^\s*\(?new\)?\s*[:\-–|]\s*""", RegexOption.IGNORE_CASE)
        private val NEW_SUFFIX = Regex("""\s*(?:\(new\)|\[new]|[\-–|]\s*new)\s*$""", RegexOption.IGNORE_CASE)
        fun cleanTitle(t: String): String {
            var s = SportsCatalog.withoutSmallMarks(t)
            repeat(2) { s = s.replace(LIVE_PREFIX, "").replace(NEW_PREFIX, "").replace(NEW_SUFFIX, "").trim() }
            return s.ifBlank { t }
        }

        /** A network as we key it ("ESPN+" -> "espn plus", "USA Net" -> "usa net"). */
        fun networkKey(name: String): String = norm(name.replace("+", " plus ")).trim()

        /** Networks that show the same thing everywhere (no local stations). */
        private val NATIONAL = setOf(
            "espn", "espn2", "espnu", "espnews", "espn plus", "espn deportes", "fs1", "fs2", "nfl net", "nfl network", "nfln",
            "nba tv", "nhl net", "nhl network", "mlb net", "mlb network", "tnt", "tbs", "trutv", "usa net", "usa network",
            "cbssn", "cbs sports network", "btn", "big ten network", "secn", "sec network", "accn", "acc network",
            "golf", "golf channel", "tennis channel", "fox deportes", "tudn", "univision", "telemundo", "universo", "peacock",
            "prime video", "paramount plus", "apple tv", "netflix", "bein sports", "nbcsn", "mlb tv", "max", "truetv"
        )

        /** How each network appears in channel names. */
        private val NETWORKS: Map<String, List<String>> = mapOf(
            "fox" to listOf("fox"),
            "cbs" to listOf("cbs"),
            "nbc" to listOf("nbc"),
            "abc" to listOf("abc"),
            "espn" to listOf("espn"),
            "espn2" to listOf("espn2", "espn 2"),
            "espnu" to listOf("espnu", "espn u"),
            "espnews" to listOf("espnews", "espn news"),
            "espn plus" to listOf("espn plus"),
            "espn deportes" to listOf("espn deportes"),
            "fs1" to listOf("fs1", "fox sports 1"),
            "fs2" to listOf("fs2", "fox sports 2"),
            "nfl net" to listOf("nfl network", "nfl net", "nfln"),
            "nfl network" to listOf("nfl network", "nfl net", "nfln"),
            "nfln" to listOf("nfl network", "nfl net", "nfln"),
            "nba tv" to listOf("nba tv", "nbatv"),
            "nhl net" to listOf("nhl network", "nhl net"),
            "nhl network" to listOf("nhl network", "nhl net"),
            "mlb net" to listOf("mlb network", "mlb net"),
            "mlb network" to listOf("mlb network", "mlb net"),
            "tnt" to listOf("tnt"),
            "tbs" to listOf("tbs"),
            "trutv" to listOf("trutv", "tru tv"),
            "usa net" to listOf("usa network", "usa net"),
            "usa network" to listOf("usa network", "usa net"),
            "cbssn" to listOf("cbs sports network", "cbssn", "cbs sports net"),
            "cbs sports network" to listOf("cbs sports network", "cbssn", "cbs sports net"),
            "btn" to listOf("big ten network", "btn", "big ten"),
            "big ten network" to listOf("big ten network", "btn", "big ten"),
            "secn" to listOf("sec network", "secn"),
            "sec network" to listOf("sec network", "secn"),
            "accn" to listOf("acc network", "accn"),
            "acc network" to listOf("acc network", "accn"),
            "golf" to listOf("golf channel", "golf"),
            "golf channel" to listOf("golf channel", "golf"),
            "tennis channel" to listOf("tennis channel"),
            "fox deportes" to listOf("fox deportes"),
            "tudn" to listOf("tudn"),
            "univision" to listOf("univision"),
            "telemundo" to listOf("telemundo"),
            "universo" to listOf("universo"),
            "peacock" to listOf("peacock"),
            "prime video" to listOf("prime video", "amazon prime"),
            "paramount plus" to listOf("paramount plus", "paramount"),
            "apple tv" to listOf("apple tv", "mls season pass"),
            "netflix" to listOf("netflix"),
            "bein sports" to listOf("bein sports", "bein"),
            "max" to listOf("max", "hbo max")
        )

        /** Channel-name words that rule a channel out for that network (FOX News isn't FOX). */
        private val NETWORK_NOT: Map<String, List<String>> = mapOf(
            "fox" to listOf("news", "business", "deportes", "sports 1", "sports 2", "fs1", "fs2", "soccer", "weather", "life", "kids", "movies", "classics", "comedy", "nation", "family", "crime", "reality"),
            "cbs" to listOf("news", "sports", "cbssn", "golazo", "reality", "drama", "action"),
            "nbc" to listOf("news", "nbcsn", "sports", "universo", "golf"),
            "abc" to listOf("news", "family", "kids", "me", "australia"),
            "espn" to listOf("2", "u", "news", "deportes", "classic", "plus", "espn2", "espnu", "espnews"),
            "tnt" to listOf("sports 1", "sports 2", "sports 3", "sports 4", "sports 5", "drama", "series", "film", "comedy"),
            "tbs" to listOf("news"),
            "max" to listOf("cinemax", "maximum", "maxx", "movies"),
            "golf" to listOf("tv"),
            "paramount plus" to listOf("network", "comedy")
        )

        fun nameQuality(name: String): String? {
            val n = norm(name)
            return when {
                listOf("4k", "uhd", "2160", "2160p").any { hasWord(n, it) } -> "4K"
                listOf("fhd", "1080", "1080p", "1080i").any { hasWord(n, it) } -> "FHD"
                listOf("hd", "720", "720p").any { hasWord(n, it) } -> "HD"
                listOf("sd", "480", "576").any { hasWord(n, it) } -> "SD"
                else -> null
            }
        }

        fun qualityRank(q: String?): Int = when (q) {
            "4K" -> 4; "FHD" -> 3; "HD" -> 2; "SD" -> 1; else -> 2
        }

        /** Your own region's words, as channel names and groups use them ("UK: TNT Sports 1", group "UK | Sports"). */
        private val REGION_WORDS = mapOf(
            "UK" to listOf("uk", "gb", "united kingdom", "britain", "england", "scotland"),
            "IE" to listOf("ie", "ireland", "irish", "irl", "eir"),
            "US" to listOf("us", "usa", "united states"),
            "CA" to listOf("ca", "canada", "can"),
            "AU" to listOf("au", "aus", "australia", "nz", "new zealand")
        )

        /**
         * How much a channel is preferred for any game: 2 in a group you chose (Sports settings),
         * 1 from your own region (its group or the start of its name says so), else 0.
         */
        fun preferenceRank(settings: SportsSettings): (LiveChannel) -> Int {
            val groups = settings.preferredGroups.map { it.lowercase().trim() }.toSet()
            val words = REGION_WORDS[SportsCatalog.regionOf(settings)].orEmpty()
            return { ch ->
                when {
                    groups.isNotEmpty() && ch.group.lowercase().trim() in groups -> 2
                    words.isNotEmpty() && (
                        norm(ch.group).let { g -> words.any { hasWord(g, it) } } ||
                            norm(ch.name.substringBefore(':').substringBefore('|').take(16)).let { n ->
                                (ch.name.contains(':') || ch.name.contains('|')) && words.any { hasWord(n, it) }
                            }
                        ) -> 1
                    else -> 0
                }
            }
        }

        fun sortMatches(list: List<ChannelMatch>, bestFirst: Boolean, rank: (LiveChannel) -> Int = { 0 }): List<ChannelMatch> {
            fun band(score: Int) = when {
                score >= 150 -> 3
                score >= 80 -> 2
                score >= 55 -> 1
                else -> 0
            }
            // Your own picks first; then your groups and region; then how sure and how good.
            return if (bestFirst) list.sortedWith(
                compareByDescending<ChannelMatch> { if (band(it.score) == 3) 1 else 0 }
                    .thenByDescending { rank(it.channel) }
                    .thenByDescending { band(it.score) }
                    .thenByDescending { qualityRank(it.quality) }
                    .thenByDescending { it.score }
                    .thenBy { it.channel.name.length }
            ) else list.sortedWith(
                compareByDescending<ChannelMatch> { if (band(it.score) == 3) 1 else 0 }
                    .thenByDescending { rank(it.channel) }
                    .thenByDescending { it.score }
                    .thenBy { it.channel.number }
            )
        }
    }
}

/** The key a team's own channel is saved under: the same club or country in every competition. */
internal fun teamChannelKey(leagueId: String, teamId: String): String = SportsCatalog.teamFamily(leagueId) + "|" + teamId
