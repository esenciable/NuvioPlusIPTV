package com.nuvio.tv.livetv.sports

import android.content.Context
import android.util.Log
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything behind the Sports page: today's games from ESPN (refreshed every 30 seconds while
 * a game is on and you're looking, much less otherwise), sports found in your guide, and which
 * of your channels show each one. Kept on the device so the page opens straight away.
 */
@Singleton
class SportsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LiveTvPreferences,
    private val liveRepo: LiveTvRepository,
    private val channelStore: LiveTvChannelStore,
    private val guideDb: EpgDatabase,
    private val playback: LiveTvPlaybackController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val feeds = SportsFeeds()
    private val matcher = SportsMatcher(guideDb)
    private val dir = File(context.filesDir, "sports").apply { mkdirs() }

    /** Esencial Sport's events: the fixtures whose stream we serve ourselves. Empty until loaded. */
    private val addonFeed = SportsAddonFeed(dir)

    @Volatile
    private var addonCandidates: List<AddonEvent> = emptyList()

    val settings: StateFlow<SportsSettings> = prefs.sportsSettingsJson
        .map { SportsSettings.fromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, SportsSettings())

    val follows: StateFlow<SportsFollows> = prefs.sportsMineJson
        .map { SportsFollows.fromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, SportsFollows())

    private class LeagueData(val events: List<SportsEvent>, val fetchedAt: Long, val error: String?)

    private val leagueData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** ESPN games from the day after tomorrow to a week ahead (updated a few times a day). */
    private val weekData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    private val fixtureData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /**
     * Leagues you follow or show on My Teams: their games from a week ahead to five weeks ahead
     * (the week before that is in [weekData]), so a league's row runs well past this week.
     */
    private val aheadData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** Followed teams' whole seasons from ESPN, by followed team key. */
    private val scheduleData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** Non-league football (nuvio-data files): whole seasons, by league id. */
    private val tsdbData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    private val tsdbTeams = java.util.concurrent.ConcurrentHashMap<String, List<TeamInfo>>()
    /** Shows found in the guide, by day (the day's start). */
    private val guideData = MutableStateFlow<Map<Long, List<SportsEvent>>>(emptyMap())

    /** Every game and event on the page's days (yesterday to a week ahead). */
    val events: StateFlow<List<SportsEvent>> = combine(leagueData, weekData, fixtureData, guideData, tsdbData) { l, w, f, g, t ->
        val guide = mergeWrestlingAirings(g.values.flatten())
        val from = dayStart(-DAYS_BACK - 1)
        val to = dayStart(DAYS_AHEAD + 2)
        val tsdb = t.values.flatMap { it.events }.filter { it.startMs in from until to }
        // A scheduled event (sporting-events.org, the wrestling schedule) gives way to the same
        // event found in the guide: one of its league within 3 hours of its start (or, for
        // events with a date only, that day). Anything else in the guide doesn't hide it.
        val guideByLeague = guide.groupBy { it.leagueId }
        val fixtures = f.values.flatMap { it.events }.filter { fx ->
            val same = guideByLeague[fx.leagueId].orEmpty()
            if (fx.hasTime) same.none { kotlin.math.abs(it.startMs - fx.startMs) <= 3 * HOUR || sameShowShifted(it, fx) }
            else same.none { dayOf(it.startMs) == dayOf(fx.startMs) }
        }
        val espn = l.values.flatMap { it.events } + w.values.flatMap { it.events }
        // A match ESPN lists, found again in the guide (or a schedule file) under a guide-only
        // league of the same sport, shows once: the ESPN one, with its score. "The same match":
        // within 3 hours, with one of ESPN's team names in the title. Any sport, not just rugby.
        // (TheSportsDB's leagues count too: rugby league and cricket are also in the guide.)
        val espnBySport = (espn + tsdb).filter { it.away != null && it.home != null }
            .groupBy { SportsCatalog.league(it.leagueId)?.sport }
        fun coveredByEspn(ev: SportsEvent): Boolean {
            val league = SportsCatalog.league(ev.leagueId) ?: return false
            if (league.espnPath != null) return false
            val same = espnBySport[league.sport] ?: return false
            val t = SportsCatalog.norm(ev.title + " " + (ev.subtitle ?: ""))
            return same.any { r ->
                kotlin.math.abs(r.startMs - ev.startMs) <= 3 * HOUR &&
                    listOfNotNull(r.away, r.home).any { sd -> SportsCatalog.norm(sd.team.name).trim().let { it.length >= 3 && t.contains(it) } }
            }
        }
        (espn + (fixtures + guide).filterNot(::coveredByEspn) + tsdb).distinctBy { it.id }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Everything we know, for My Teams: the page's days plus your teams' whole seasons and the
     * non-league seasons. A game in both counts once (the fresher copy, from the page's days).
     */
    val known: StateFlow<List<SportsEvent>> = combine(events, scheduleData, tsdbData, aheadData) { ev, s, t, a ->
        val seen = HashSet<String>(ev.size * 2)
        ev.forEach { seen += dedupeKey(it) }
        val extra = (a.values.flatMap { it.events } + s.values.flatMap { it.events } + t.values.flatMap { it.events }).filter { seen.add(dedupeKey(it)) }
        ev + extra
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The same weekly wrestling show in the schedule file and in your guide, up to a day and a
     * bit apart: TheSportsDB sometimes files a US evening show under the wrong day (SmackDown,
     * Friday 8 pm Eastern, listed as Thursday 8 pm: midnight UTC put on the Friday date).
     * Your guide's airing is the real one, so the schedule's copy gives way to it.
     */
    private fun sameShowShifted(guide: SportsEvent, fx: SportsEvent): Boolean {
        if (SportsCatalog.league(fx.leagueId)?.sport != Sport.WRESTLING) return false
        if (kotlin.math.abs(guide.startMs - fx.startMs) > 30 * HOUR) return false
        val a = SportsCatalog.norm(wrestlingHeadline(guide.title)).trim()
        val b = SportsCatalog.norm(wrestlingHeadline(fx.title)).trim()
        return a.length >= 3 && b.length >= 3 && (a == b || a.contains(b) || b.contains(a))
    }

    /**
     * One wrestling show aired at different times (USA at 8 pm, a later or overseas feed at 1 am):
     * one entry, at the earliest airing, with every channel and its time. Airings of the same show
     * within 12 hours of the first count as one; next week's episode never does.
     */
    private fun mergeWrestlingAirings(guide: List<SportsEvent>): List<SportsEvent> {
        val (wrestling, rest) = guide.partition { SportsCatalog.league(it.leagueId)?.sport == Sport.WRESTLING }
        if (wrestling.size < 2) return guide
        val out = ArrayList<SportsEvent>(wrestling.size)
        val byShow = wrestling.groupBy { it.leagueId + "|" + SportsCatalog.norm(wrestlingHeadline(it.title)).trim() }
        for ((_, airings) in byShow) {
            var run = ArrayList<SportsEvent>()
            fun flush() {
                if (run.isEmpty()) return
                val first = run.first()
                out += if (run.size == 1) first else first.copy(
                    // On now if any airing is; otherwise the first airing's state.
                    state = if (run.any { it.state == GameState.LIVE }) GameState.LIVE else first.state,
                    guideHits = run.flatMap { it.guideHits }.distinctBy { (key, p) -> key + "|" + p.startMs },
                    endMs = run.mapNotNull { it.endMs }.maxOrNull() ?: first.endMs,
                    subtitle = first.subtitle ?: run.firstNotNullOfOrNull { it.subtitle },
                    word = first.word ?: run.firstNotNullOfOrNull { it.word }
                )
                run = ArrayList()
            }
            for (ev in airings.sortedBy { it.startMs }) {
                if (run.isNotEmpty() && ev.startMs - run.first().startMs > 12 * HOUR) flush()
                run += ev
            }
            flush()
        }
        return rest + out
    }

    private fun dedupeKey(ev: SportsEvent): String = ev.espnId?.let { "e:$it" } ?: ev.id

    private val _matches = MutableStateFlow<Map<String, List<ChannelMatch>>>(emptyMap())
    /** Event id -> the channels showing it, best first. */
    val matches: StateFlow<Map<String, List<ChannelMatch>>> = _matches.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _matching = MutableStateFlow(false)
    /** Looking through your channels and guide (the first time takes a few seconds). */
    val matching: StateFlow<Boolean> = _matching.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    /** Something went wrong getting scores (shown quietly on the page). */
    val problem: StateFlow<String?> = _problem.asStateFlow()
    private val _updatedAt = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()

    private val viewers = MutableStateFlow(0)
    private val foreground = MutableStateFlow(true)
    private val kick = MutableStateFlow(0)
    @Volatile private var started = false

    /** Starts the background updates (once). Called by the app as it starts. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            loadCache()
            runCatching { updateLoop() }.onFailure { Log.w(TAG, "Sports updates stopped", it) }
        }
        scope.launch { matchLoop() }
    }

    /** The Sports page (or a game) is on screen: keep scores fresh. */
    fun setViewing(on: Boolean) {
        viewers.update { (it + if (on) 1 else -1).coerceAtLeast(0) }
        if (on) {
            liveRepo.ensureLoaded(updateCheckDelayMs = 20_000)
            kick.value++
        }
    }

    fun setForeground(on: Boolean) {
        foreground.value = on
        if (on) kick.value++
    }

    /** Update now (the page's refresh, a day picked). */
    fun refreshNow() {
        forceAll = true
        forceExtras = true
        kick.value++
    }

    @Volatile private var forceAll = false
    @Volatile private var forceExtras = false

    // ------------------------------------------------------------------ which leagues

    /** Leagues on the Sports page, plus any league you follow a team or the whole league in. */
    fun activeLeagues(s: SportsSettings = settings.value, f: SportsFollows = follows.value): List<League> {
        val mine = followedLeagueIds(f)
        return SportsCatalog.ordered(s).filter { SportsCatalog.shown(it, s) || it.id in mine }
    }

    /**
     * Leagues you follow something in: your teams' leagues and the other competitions they play
     * in (cups, European games, a country's qualifiers), whole leagues, and top-bar leagues.
     */
    fun followedLeagueIds(f: SportsFollows = follows.value): Set<String> {
        val out = LinkedHashSet<String>()
        f.teams.forEach { t -> out += t.leagueId; out += SportsCatalog.relatedLeagues(t.leagueId) }
        out += f.leagues
        out += f.pins
        return out
    }

    private fun isMine(l: League, f: SportsFollows) = l.id in followedLeagueIds(f)

    // ------------------------------------------------------------------ updates

    private suspend fun updateLoop() {
        var lastKick = -1
        var lastFixtures = 0L
        while (true) {
            val active = foreground.value && (settings.value.showInSidebar || viewers.value > 0)
            if (active) runCatching { updateOnce(System.currentTimeMillis()) }.onFailure { Log.w(TAG, "Sports update failed", it) }
            if (active) {
                val force = forceExtras.also { forceExtras = false }
                runCatching { updateWeek(System.currentTimeMillis(), force) }.onFailure { Log.w(TAG, "Sports week update failed", it) }
                runCatching { updateAhead(System.currentTimeMillis(), force) }.onFailure { Log.w(TAG, "Sports weeks-ahead update failed", it) }
                runCatching { updatePast(System.currentTimeMillis()) }.onFailure { Log.w(TAG, "Sports past days update failed", it) }
                runCatching { updateTsdb(System.currentTimeMillis(), force) }.onFailure { Log.w(TAG, "Non-league update failed", it) }
                runCatching { updateSchedules(System.currentTimeMillis(), force) }.onFailure { Log.w(TAG, "Team schedules update failed", it) }
                runCatching { refreshAddonFeed(force) }.onFailure { Log.w(TAG, "Addon feed update failed", it) }
            }
            if (active && System.currentTimeMillis() - lastFixtures > 12 * HOUR) {
                lastFixtures = System.currentTimeMillis()
                runCatching { updateFixtures() }
            }
            val wait = if (viewers.value > 0) 10_000L else 30_000L
            lastKick = kick.value
            withTimeoutOrNull(wait) { kick.first { it != lastKick } }
        }
    }

    /**
     * Loads the addon's event feed beside the other sources. An empty answer keeps what we already
     * had: a feed that is down or not deployed yet must not take away links the page was using, and
     * `SportsAddonChannels.decorate` with an empty list changes nothing anyway.
     */
    private fun refreshAddonFeed(force: Boolean) {
        val base = addonBaseUrl
        if (base.isBlank()) return
        addonFeed.events(base, force).let { if (it.isNotEmpty()) addonCandidates = it }
    }

    private suspend fun updateOnce(now: Long) {
        val s = settings.value
        val f = follows.value
        val viewing = viewers.value > 0
        val force = forceAll.also { forceAll = false }
        val data = leagueData.value
        val playingKey = playback.state.value.channelKey
        val playingLeagues = if (playingKey == null) emptySet() else _matches.value
            .filter { (_, list) -> list.any { it.channel.key == playingKey && it.sure } }
            .keys.mapNotNull { id -> events.value.firstOrNull { it.id == id && it.state == GameState.LIVE }?.leagueId }.toSet()
        val due = activeLeagues(s, f).filter { l ->
            if (l.espnPath == null) return@filter false
            val d = data[l.id]
            val age = now - (d?.fetchedAt ?: 0L)
            val live = d?.events?.any { it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMs in (now - 15 * MINUTE)..(now + 10 * MINUTE)) } == true
            val mine = isMine(l, f)
            when {
                force -> age > 15_000
                viewing -> age > (if (live) 30_000 else 5 * MINUTE)
                l.id in playingLeagues && s.scoreOnInfoBar -> age > 60_000
                mine && live -> age > (if (s.scoringAlerts) 45_000 else 90_000)
                mine -> age > 30 * MINUTE
                // Not looking and not yours: only so the page isn't empty when it opens.
                d == null -> SportsCatalog.shown(l, s) && age > 6 * HOUR
                else -> false
            }
        }
        if (due.isEmpty()) return
        _loading.value = true
        try {
            val (from, to) = espnRange(now)
            val days = espnDays(now)
            val gate = Semaphore(4)
            val failed = ArrayList<String>()
            val reasons = LinkedHashSet<String>()
            coroutineScopeFetch(due) { l ->
                gate.withPermit {
                    val r = runCatching { fetchScoreboard(l, from, to, days) }
                    r.onSuccess { raws ->
                        val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrElse { emptyList() } }
                            .distinctBy { it.id }
                        val before = leagueData.value[l.id]?.events
                        leagueData.update { it + (l.id to LeagueData(parsed, System.currentTimeMillis(), null)) }
                        runCatching { File(dir, "sb_${safe(l.id)}.json").writeText("${System.currentTimeMillis()}\n" + raws.joinToString(RAW_SEP)) }
                        if (before != null) noticeScoring(l, before, parsed)
                    }.onFailure { e ->
                        synchronized(failed) { failed += l.name; reasons += describe(e) }
                        leagueData.update { m ->
                            val old = m[l.id]
                            m + (l.id to LeagueData(old?.events.orEmpty(), old?.fetchedAt ?: (System.currentTimeMillis() - 4 * MINUTE), e.message))
                        }
                    }
                }
            }
            val why = reasons.take(2).joinToString(", ")
            _problem.value = if (failed.size >= due.size && due.isNotEmpty()) "Couldn't reach the scores service ($why). Showing what was saved." else null
            if (failed.isNotEmpty()) {
                val line = "Sports: couldn't get scores for ${failed.take(6).joinToString(", ")}${if (failed.size > 6) " and ${failed.size - 6} more" else ""} ($why)"
                if (line != lastFailLine) { lastFailLine = line; LiveTvLoadReport.add(line) }
            } else lastFailLine = null
            _updatedAt.value = System.currentTimeMillis()
            rebuildLiveScores()
        } finally {
            _loading.value = false
        }
    }

    private var lastFailLine: String? = null

    // ------------------------------------------------------------------ the week ahead, seasons

    /** ESPN games from the day after tomorrow to a week ahead: a few times a day. */
    private suspend fun updateWeek(now: Long, force: Boolean) {
        val s = settings.value
        val f = follows.value
        val viewing = viewers.value > 0
        val due = activeLeagues(s, f).filter { l ->
            if (l.espnPath == null) return@filter false
            val age = now - (weekData.value[l.id]?.fetchedAt ?: 0L)
            when {
                force -> age > 30 * MINUTE
                viewing -> age > 3 * HOUR
                isMine(l, f) -> age > 6 * HOUR
                else -> false
            }
        }
        if (due.isEmpty()) return
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val from = fmt.format(java.util.Date(now + 2 * DAY))
        val to = fmt.format(java.util.Date(now + DAYS_AHEAD * DAY))
        val singleDays = (2..DAYS_AHEAD).map { d -> fmt.format(java.util.Date(now + d * DAY)) }
        val gate = Semaphore(3)
        coroutineScopeFetch(due) { l ->
            gate.withPermit {
                // One request for the whole week; a feed that won't take a range of days: a day at a time.
                // A day at a time (ESPN turns down a range of days).
                val raws: List<String> = singleDays.mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() }
                val at = System.currentTimeMillis()
                if (raws.isEmpty()) {
                    // Try again in an hour, not straight away.
                    weekData.update { m -> m + (l.id to LeagueData(m[l.id]?.events.orEmpty(), at - 2 * HOUR, "failed")) }
                    LiveTvLoadReport.add("Sports: couldn't get the week ahead for ${l.name}")
                    return@withPermit
                }
                val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }.distinctBy { it.id }
                weekData.update { it + (l.id to LeagueData(parsed, at, null)) }
                runCatching { File(dir, "wk_${safe(l.id)}.json").writeText("$at\n" + raws.joinToString(RAW_SEP)) }
            }
        }
    }

    /**
     * Leagues you follow or show on My Teams: up to five weeks ahead (one request each, a few
     * times a day), so their rows aren't only this week. Not for feeds that need asking a day at a time.
     */
    private suspend fun updateAhead(now: Long, force: Boolean) {
        val f = follows.value
        // Plus your teams' competitions where ESPN gives no team schedule (rugby): their games
        // further ahead come from the league's months instead.
        val noSchedule = f.teams.flatMap { listOf(it.leagueId) + SportsCatalog.relatedLeagues(it.leagueId) }
            .filter { it.startsWith("rugby/") || it.startsWith("australian-football/") }
        val ids = (f.pins + f.leagues + noSchedule).toSet()
        if (aheadData.value.keys.any { it !in ids }) aheadData.update { m -> m.filterKeys { it in ids } }
        val due = ids.mapNotNull { SportsCatalog.league(it) }.filter { l ->
            l.espnPath != null && now - (aheadData.value[l.id]?.fetchedAt ?: 0L) > (if (force) HOUR else 6 * HOUR)
        }
        if (due.isEmpty()) return
        // ESPN takes a whole month ("202611") though not a range of days: the months the next
        // five weeks touch, kept from a week ahead on (the week before is in [weekData]).
        val months = SimpleDateFormat("yyyyMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val wanted = listOf(now + (DAYS_AHEAD + 1) * DAY, now + 35 * DAY).map { months.format(java.util.Date(it)) }.distinct()
        val keepFrom = dayStart(DAYS_AHEAD + 1)
        val keepTo = now + 36 * DAY
        for (l in due) {
            val raws = wanted.mapNotNull { m -> runCatching { feeds.scoreboardDayRaw(l, m) }.getOrNull() }
            val at = System.currentTimeMillis()
            if (raws.isEmpty()) {
                aheadData.update { m -> m + (l.id to LeagueData(m[l.id]?.events.orEmpty(), at - 5 * HOUR, "failed")) }
                continue
            }
            val parsed = withContext(com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
                raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }
                    .filter { it.startMs in keepFrom until keepTo }.distinctBy { it.id }
            }
            aheadData.update { it + (l.id to LeagueData(parsed, at, null)) }
        }
    }

    @Volatile private var pastWanted = 0L

    /** A day before yesterday is on screen: get the week back (once, then every couple of hours while looked at). */
    fun wantPast() {
        val now = System.currentTimeMillis()
        if (now - pastWanted > 60_000L) {
            pastWanted = now
            kick.value++
        }
    }

    /** The week before yesterday, for the page's past days: only when someone has looked at one. */
    private suspend fun updatePast(now: Long) {
        if (now - pastWanted > 30 * MINUTE) return
        val due = activeLeagues().filter { l ->
            l.espnPath != null && now - (weekData.value["p|" + l.id]?.fetchedAt ?: 0L) > 2 * HOUR
        }
        if (due.isEmpty()) return
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val from = fmt.format(java.util.Date(now - DAYS_BACK * DAY))
        val to = fmt.format(java.util.Date(now - 2 * DAY))
        val singleDays = (2..DAYS_BACK).map { d -> fmt.format(java.util.Date(now - d * DAY)) }
        val gate = Semaphore(3)
        coroutineScopeFetch(due) { l ->
            gate.withPermit {
                val raws: List<String> = singleDays.mapNotNull { d -> runCatching { feeds.scoreboardDayRaw(l, d) }.getOrNull() }
                val at = System.currentTimeMillis()
                val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrDefault(emptyList()) }.distinctBy { it.id }
                weekData.update { it + ("p|" + l.id to LeagueData(parsed, if (raws.isEmpty()) at - HOUR else at, null)) }
            }
        }
    }

    /** Your teams' whole seasons (every competition), from ESPN: every 6 hours. */
    private suspend fun updateSchedules(now: Long, force: Boolean) {
        val f = follows.value
        val keys = f.teams.map { it.key }.toSet()
        if (scheduleData.value.keys.any { it !in keys }) scheduleData.update { m -> m.filterKeys { it in keys } }
        for (t in f.teams) {
            val l = SportsCatalog.league(t.leagueId) ?: continue
            val path = l.espnPath ?: continue
            val cur = scheduleData.value[t.key]
            if (cur != null && now - cur.fetchedAt < (if (force) HOUR else 6 * HOUR)) continue
            val raws = ArrayList<Pair<League, String>>()
            runCatching { feeds.teamScheduleRaw(path, t.teamId, fixtures = false) }.getOrNull()?.let { raws += l to it }
            if (path.startsWith("soccer/") || l.national) {
                runCatching { feeds.teamScheduleRaw(path, t.teamId, fixtures = true) }.getOrNull()?.let { raws += l to it }
                for (rid in SportsCatalog.relatedLeagues(l.id)) {
                    val rl = SportsCatalog.league(rid) ?: continue
                    val rp = rl.espnPath ?: continue
                    runCatching { feeds.teamScheduleRaw(rp, t.teamId, fixtures = true) }.getOrNull()?.let { raws += rl to it }
                }
            }
            val at = System.currentTimeMillis()
            // The league's team list (saved for a week): where the colours come from.
            if (raws.isNotEmpty()) runCatching { leagueTeams(l) }
            if (raws.isEmpty()) {
                scheduleData.update { m -> m + (t.key to LeagueData(m[t.key]?.events.orEmpty(), at - 5 * HOUR, "failed")) }
                continue
            }
            val list = withContext(com.nuvio.tv.livetv.data.BackgroundWork.quiet) { mergeSchedule(raws) }
            scheduleData.update { it + (t.key to LeagueData(list, at, null)) }
            runCatching {
                File(dir, "ts_${safe(t.key)}.json").writeText("$at\n" + raws.joinToString(RAW_SEP) { (rl, raw) -> rl.id + "\t" + raw })
            }
        }
    }

    /** One list from a team's schedule answers: each game once, under the first competition it came with. */
    private fun mergeSchedule(raws: List<Pair<League, String>>): List<SportsEvent> {
        val byId = LinkedHashMap<String, SportsEvent>()
        for ((rl, raw) in raws) {
            // First answer wins: the team's main league is asked first, and only has its own
            // games (or, when the feed says which competition a game is in, it's under that one).
            runCatching { SportsFeeds.parseSchedule(rl, raw) }.getOrDefault(emptyList()).forEach { ev ->
                byId.putIfAbsent(ev.espnId ?: ev.id, ev)
            }
        }
        // A team's schedule from ESPN leaves out the teams' colours (the scoreboard has them):
        // filled in from the league's team list and today's games, so every card has its colours.
        val known = HashMap<String, TeamInfo>()
        fun learn(t: TeamInfo) {
            if (t.color == null && t.altColor == null) return
            known.putIfAbsent(SportsCatalog.teamFamily(t.leagueId) + "|" + t.id, t)
        }
        raws.map { it.first }.distinctBy { it.id }.forEach { l -> cachedTeams(l).forEach(::learn) }
        (leagueData.value.values + weekData.value.values).forEach { d -> d.events.forEach { ev -> ev.sides.forEach { learn(it.team) } } }
        synchronized(teamCache) { teamCache.values.toList() }.forEach { list -> list.forEach(::learn) }
        fun fill(side: Side?): Side? {
            if (side == null || side.team.color != null) return side
            val k = known[SportsCatalog.teamFamily(side.team.leagueId) + "|" + side.team.id] ?: return side
            return side.copy(team = side.team.copy(color = k.color, altColor = k.altColor, logo = side.team.logo ?: k.logo))
        }
        return byId.values.map { ev -> if (ev.sides.all { it.team.color != null }) ev else ev.copy(away = fill(ev.away), home = fill(ev.home)) }
            .sortedBy { it.startMs }
    }

    /** A league's teams as already saved (no download): for colours. */
    private fun cachedTeams(league: League): List<TeamInfo> {
        synchronized(teamCache) { teamCache[league.id] }?.let { return it }
        val file = File(dir, "teams_${safe(league.id)}.json")
        if (!file.exists()) return emptyList()
        return runCatching { SportsFeeds.parseTeams(league, file.readText()) }.getOrDefault(emptyList())
    }

    @Volatile private var tsdbIndexAt = 0L

    /**
     * Non-league football: the list of leagues and teams (twice a day, for Pick Your Teams),
     * and the seasons of leagues you follow a team in or have on the top bar (every few minutes
     * while one of their games is on).
     */
    private suspend fun updateTsdb(now: Long, force: Boolean) {
        val viewing = viewers.value > 0
        val f = follows.value
        val indexAge = now - tsdbIndexAt
        if ((viewing && indexAge > 12 * HOUR) || (force && indexAge > 30 * MINUTE)) {
            runCatching { feeds.footballIndexRaw() }.getOrNull()?.let { raw ->
                if (applyTsdbIndex(raw)) {
                    tsdbIndexAt = System.currentTimeMillis()
                    runCatching { File(dir, "tsdb_index.json").writeText(raw) }
                }
            } ?: run { tsdbIndexAt = now - 11 * HOUR }
        }
        // What you follow, plus the ones shown in your region (rugby league and cricket in the UK…).
        // These are nuvio-data's own files, not TheSportsDB itself.
        val wanted = (followedLeagueIds(f) + activeLeagues(settings.value, f).map { it.id }).distinct().filter { it.startsWith("tsdb/") }
        for (id in wanted) {
            val l = SportsCatalog.league(id) ?: continue
            val cur = tsdbData.value[id]
            val busy = cur?.events?.any { it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMs in (now - 20 * MINUTE)..(now + 15 * MINUTE)) } == true
            val maxAge = when {
                busy && (viewing || force) -> 4 * MINUTE
                busy -> 10 * MINUTE
                force -> 30 * MINUTE
                else -> 3 * HOUR
            }
            if (cur != null && now - cur.fetchedAt < maxAge) continue
            val tid = id.removePrefix("tsdb/")
            val raw = runCatching { feeds.footballLeagueRaw(tid) }.getOrNull()
            val at = System.currentTimeMillis()
            if (raw == null) {
                tsdbData.update { m -> m + (id to LeagueData(m[id]?.events.orEmpty(), at - maxAge + 5 * MINUTE, "failed")) }
                continue
            }
            val teams = tsdbTeams[id].orEmpty().associateBy { it.id }
            val list = runCatching { SportsFeeds.parseFootballLeague(l, raw, teams) }.getOrDefault(emptyList())
            tsdbData.update { it + (id to LeagueData(list, at, null)) }
            runCatching { File(dir, "tsdb_$tid.json").writeText(raw) }
        }
        if (tsdbData.value.keys.any { it !in wanted }) tsdbData.update { m -> m.filterKeys { it in wanted } }
    }

    /** The non-league leagues and their teams, from the list file. False if it couldn't be read. */
    private fun applyTsdbIndex(raw: String): Boolean {
        val list = runCatching { SportsFeeds.parseFootballIndex(raw) }.getOrNull() ?: return false
        if (list.isEmpty()) return false
        list.forEach { tsdbTeams[it.league.id] = it.teams }
        SportsCatalog.setExtra(list.map { it.league })
        return true
    }

    /** Leagues whose feed wouldn't take a range of days (asked one day at a time from then on). */
    private val dayByDay: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * A league's games for the three days around today: one request for the whole range; if the
     * feed won't take that, one request per day; failing that, whatever day the feed picks.
     */
    private fun fetchScoreboard(l: League, @Suppress("UNUSED_PARAMETER") from: String, @Suppress("UNUSED_PARAMETER") to: String, days: List<String>): List<String> {
        // ESPN turns down a range of days ("20261019-20261021": HTTP 400, in every league), so
        // a day at a time (the ESPN check in nuvio-data showed it).
        var firstError: Throwable? = null
        val singles = days.mapNotNull { d ->
            runCatching { feeds.scoreboardDayRaw(l, d) }.onFailure { if (firstError == null) firstError = it }.getOrNull()
        }
        if (singles.isNotEmpty()) return singles
        runCatching { feeds.scoreboardDefaultRaw(l) }.getOrNull()?.let { return listOf(it) }
        throw firstError ?: java.io.IOException("no answer")
    }

    private fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "no internet or the address can't be found"
        is java.net.SocketTimeoutException -> "timed out"
        is javax.net.ssl.SSLException -> "secure connection failed: ${e.message?.take(60)}"
        else -> e.message?.take(80)?.ifBlank { null } ?: e.javaClass.simpleName
    }

    private suspend fun coroutineScopeFetch(list: List<League>, block: suspend (League) -> Unit) {
        kotlinx.coroutines.coroutineScope { list.map { l -> async { block(l) } }.awaitAll() }
    }

    private suspend fun updateFixtures() {
        val s = settings.value
        val leagues = activeLeagues(s).filter { it.fixtureSlug != null }
        for (l in leagues) {
            val slug = l.fixtureSlug ?: continue
            val file = File(dir, "fx_$slug.json")
            val raw = runCatching { feeds.fixturesRaw(slug) }.onSuccess { runCatching { file.writeText(it) } }.getOrNull()
                ?: runCatching { file.readText() }.getOrNull() ?: continue
            val list = runCatching { SportsFeeds.parseFixtures(l, raw) }.getOrDefault(emptyList())
            fixtureData.update { it + (l.id to LeagueData(list, System.currentTimeMillis(), null)) }
        }
        // Wrestling's schedule (from TheSportsDB, through a file a GitHub job keeps up to date).
        // (Boxing's fight cards come in the same file.)
        val wrestling = activeLeagues(s).filter { it.sport == Sport.WRESTLING || it.id == "guide/boxing" }.map { it.id }.toSet()
        if (wrestling.isNotEmpty()) {
            val file = File(dir, "fx_wrestling.json")
            val raw = runCatching { feeds.wrestlingRaw() }.onSuccess { runCatching { file.writeText(it) } }.getOrNull()
                ?: runCatching { file.readText() }.getOrNull()
            if (raw != null) {
                val byLeague = runCatching { SportsFeeds.parseWrestling(raw) }.getOrDefault(emptyMap())
                val at = System.currentTimeMillis()
                fixtureData.update { cur ->
                    cur + byLeague.filterKeys { it in wrestling }.mapValues { (_, list) -> LeagueData(list, at, null) }
                }
            }
        }
    }

    private fun loadCache() {
        val now = System.currentTimeMillis()
        val m = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            if (l.espnPath == null) continue
            val file = File(dir, "sb_${safe(l.id)}.json")
            if (!file.exists()) continue
            runCatching {
                val text = file.readText()
                val nl = text.indexOf('\n')
                val at = text.substring(0, nl).toLong()
                if (now - at > 2 * DAY) return@runCatching
                // Saved a while ago: counts as old, so it's checked again when wanted.
                m[l.id] = LeagueData(
                    text.substring(nl + 1).split(RAW_SEP).flatMap { SportsFeeds.parseScoreboard(l, it) }.distinctBy { it.id }, at, null
                )
            }
        }
        val fx = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            val slug = l.fixtureSlug ?: continue
            val file = File(dir, "fx_$slug.json")
            if (!file.exists()) continue
            runCatching { fx[l.id] = LeagueData(SportsFeeds.parseFixtures(l, file.readText()), 0L, null) }
        }
        runCatching {
            val file = File(dir, "fx_wrestling.json")
            if (file.exists()) SportsFeeds.parseWrestling(file.readText()).forEach { (id, list) -> fx[id] = LeagueData(list, 0L, null) }
        }
        leagueData.value = m
        fixtureData.value = fx
        // The week ahead, team seasons and non-league football, as last saved.
        val wk = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            if (l.espnPath == null) continue
            val file = File(dir, "wk_${safe(l.id)}.json")
            if (!file.exists()) continue
            runCatching {
                val text = file.readText()
                val nl = text.indexOf('\n')
                val at = text.substring(0, nl).toLong()
                if (now - at > 3 * DAY) return@runCatching
                wk[l.id] = LeagueData(text.substring(nl + 1).split(RAW_SEP).flatMap { SportsFeeds.parseScoreboard(l, it) }.distinctBy { it.id }, at, null)
            }
        }
        weekData.value = wk
        runCatching { File(dir, "tsdb_index.json").takeIf { it.exists() }?.readText()?.let { applyTsdbIndex(it) } }
        val f = follows.value
        val sched = HashMap<String, LeagueData>()
        for (t in f.teams) {
            val file = File(dir, "ts_${safe(t.key)}.json")
            if (!file.exists()) continue
            runCatching {
                val text = file.readText()
                val nl = text.indexOf('\n')
                val at = text.substring(0, nl).toLong()
                val raws = text.substring(nl + 1).split(RAW_SEP).mapNotNull { part ->
                    val tab = part.indexOf('\t')
                    if (tab <= 0) null else SportsCatalog.league(part.substring(0, tab))?.let { it to part.substring(tab + 1) }
                }
                if (raws.isNotEmpty()) sched[t.key] = LeagueData(mergeSchedule(raws), at, null)
            }
        }
        scheduleData.value = sched
        val ts = HashMap<String, LeagueData>()
        for (id in followedLeagueIds(f).filter { it.startsWith("tsdb/") }) {
            val l = SportsCatalog.league(id) ?: continue
            val file = File(dir, "tsdb_${id.removePrefix("tsdb/")}.json")
            if (!file.exists()) continue
            runCatching {
                ts[id] = LeagueData(SportsFeeds.parseFootballLeague(l, file.readText(), tsdbTeams[id].orEmpty().associateBy { it.id }), 0L, null)
            }
        }
        tsdbData.value = ts
    }

    // ------------------------------------------------------------------ matching channels

    @OptIn(FlowPreview::class)
    private suspend fun matchLoop() {
        // Run again when the games, your channels, Sports settings or Live TV's state change…
        val trigger = combine(
            events.map { list -> list.filter { it.source != EventSource.GUIDE }.map { "${it.id}@${it.startMs}:${it.broadcasts.size}" } }.distinctUntilChanged(),
            channelStore.displayChannels.map { it.size to it.firstOrNull()?.key }.distinctUntilChanged(),
            settings.map { listOf(it.networkChannels, it.eventChannels, it.blocked, it.bestChannelFirst, it.hiddenLeagues, it.region) }.distinctUntilChanged(),
            follows.map { it.words to it.leagues }.distinctUntilChanged(),
            liveRepo.status.map { it.loading }.distinctUntilChanged()
        ) { a, b, c, d, e -> listOf(a, b, c, d, e) }
        var lastRun = 0L
        scope.launch {
            // …and every 15 minutes (shows move on; new guide listings).
            while (true) {
                delay(15 * MINUTE)
                if (foreground.value && System.currentTimeMillis() - lastRun > 10 * MINUTE) matchKick.value++
            }
        }
        combine(trigger, matchKick) { t, k -> t to k }.debounce(1_500).collect {
            if (!foreground.value && viewers.value == 0) return@collect
            if (!settings.value.showInSidebar && viewers.value == 0) return@collect
            if (liveRepo.status.value.loading && !liveRepo.status.value.quiet && _matches.value.isNotEmpty()) return@collect
            lastRun = System.currentTimeMillis()
            runCatching { runMatch() }.onFailure { Log.w(TAG, "Sports matching failed", it) }
        }
    }

    private val matchKick = MutableStateFlow(0)
    private var lastSummary: String? = null

    // Nobody on the Sports page: low priority, out of the way of the guide and the video.
    private suspend fun runMatch() = withContext(if (viewers.value > 0) Dispatchers.Default else com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
        val live = prefs.settings.first()
        val user = prefs.userState.first()
        val channels: List<LiveChannel> = channelStore.displayChannels.value.filter { ch ->
            ch.key !in user.hiddenChannels && ch.groupId !in user.hiddenGroups &&
                !ParentalControls.isLocked(ch.groupId, ch.group, live, user.lockedGroups) &&
                !(ParentalControls.enabled(live) && live.lockAdultContent && ParentalControls.isAdult(ch.name))
        }
        if (channels.isEmpty()) return@withContext
        _matching.value = true
        try {
            val now = System.currentTimeMillis()
            val shift = live.epgOffsetMinutes * MINUTE
            val s = settings.value
            val f = follows.value
            // Sports ESPN doesn't cover, and names you follow, from the guide: yesterday to tomorrow.
            val guideLeagues = activeLeagues(s, f).filter { it.guideOnly }
            val keys = channels.associateBy { it.key }
            val days = HashMap<Long, List<SportsEvent>>()
            for (offset in -1..1) {
                val start = dayStart(offset)
                days[start] = runCatching {
                    matcher.guideEvents(start, dayStart(offset + 1), guideLeagues, f.words, keys, shift, now)
                }.getOrDefault(emptyList())
            }
            guideData.value = days
            val result = matcher.match(
                SportsMatcher.Input(
                    events = events.value,
                    channels = channels,
                    quality = user.channelQuality,
                    shiftMs = shift,
                    settings = s,
                    now = now
                )
            )
            _matches.value = SportsAddonChannels.decorate(
                result.matches, events.value, addonCandidates, s.blocked
            )
            if (result.summary != lastSummary) {
                lastSummary = result.summary
                LiveTvLoadReport.add(result.summary)
            }
            rebuildLiveScores()
        } finally {
            _matching.value = false
        }
    }

    // ------------------------------------------------------------------ scores elsewhere in the app

    /** Channel key -> the live game it's showing (sure matches only), for the Live TV info bar. */
    private fun rebuildLiveScores() {
        val evs = events.value.associateBy { it.id }
        val out = HashMap<String, SportsEvent>()
        val cards = HashMap<String, SportsEvent>()
        for ((id, list) in _matches.value) {
            val ev = evs[id] ?: continue
            if (ev.state != GameState.LIVE) continue
            list.filter { it.sure }.forEach { m ->
                cards.putIfAbsent(m.channel.key, ev)
                if (ev.hasScore) out.putIfAbsent(m.channel.key, ev)
            }
        }
        SportsLive.byChannel.value = out
        SportsLive.cardByChannel.value = cards
    }

    /** A team you follow scored: tell the app (Scoring Alerts). */
    private fun noticeScoring(league: League, before: List<SportsEvent>, after: List<SportsEvent>) {
        if (!settings.value.scoringAlerts) return
        val f = follows.value
        val old = before.associateBy { it.id }
        for (ev in after) {
            if (ev.state != GameState.LIVE || !ev.hasScore) continue
            val prev = old[ev.id] ?: continue
            if (!prev.hasScore) continue
            for (side in ev.sides) {
                if (!f.followsTeam(league.id, side.team.id) && league.id !in f.leagues) continue
                val was = prev.sides.firstOrNull { it.team.id == side.team.id }?.score?.toDoubleOrNull() ?: continue
                val nowScore = side.score?.toDoubleOrNull() ?: continue
                if (nowScore > was) SportsLive.alert(SportsAlert(ev, side.team, league))
            }
        }
    }

    // ------------------------------------------------------------------ teams, details

    private val teamCache = HashMap<String, List<TeamInfo>>()

    /** A league's teams (for Pick Your Teams): saved for a week, then fetched again. */
    suspend fun teams(league: League): List<TeamInfo> = withContext(Dispatchers.IO) {
        if (league.id.startsWith("tsdb/")) {
            if (tsdbTeams[league.id] == null) runCatching { feeds.footballIndexRaw() }.getOrNull()?.let { raw ->
                if (applyTsdbIndex(raw)) {
                    tsdbIndexAt = System.currentTimeMillis()
                    runCatching { File(dir, "tsdb_index.json").writeText(raw) }
                }
            }
            return@withContext tsdbTeams[league.id].orEmpty()
        }
        // Countries: the same list whichever international competition you open (every country
        // ESPN lists in any of them), so England is there even between tournaments.
        if (league.national) {
            val all = LinkedHashMap<String, TeamInfo>()
            for (l in SportsCatalog.leagues.filter { it.national && it.nationalGroup == league.nationalGroup }) {
                leagueTeams(l).forEach { t -> all.putIfAbsent(t.id, if (l.id == league.id) t else t.copy(leagueId = league.id)) }
            }
            return@withContext all.values.sortedBy { it.fullName.lowercase(Locale.ROOT) }
        }
        leagueTeams(league)
    }

    private fun leagueTeams(league: League): List<TeamInfo> {
        synchronized(teamCache) { teamCache[league.id] }?.let { return it }
        if (league.espnPath == null) return emptyList()
        val file = File(dir, "teams_${safe(league.id)}.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 7 * DAY
        val raw = (if (fresh) runCatching { file.readText() }.getOrNull() else null)
            ?: runCatching { feeds.teamsRaw(league).also { runCatching { file.writeText(it) } } }.getOrNull()
            ?: runCatching { file.readText() }.getOrNull()
            ?: return emptyList()
        val list = runCatching { SportsFeeds.parseTeams(league, raw) }.getOrDefault(emptyList())
            .sortedBy { (it.location ?: it.fullName).lowercase(Locale.ROOT) }
        if (list.isNotEmpty()) synchronized(teamCache) { teamCache[league.id] = list }
        return list
    }

    /** Event id -> when asked, the score then, the plays. A new score asks again straight away. */
    private val playsCache = HashMap<String, Triple<Long, String, List<ScoringPlay>>>()

    /** The game's scoring plays (touchdowns, goals…), when ESPN has them. */
    suspend fun scoringPlays(ev: SportsEvent): List<ScoringPlay> = withContext(Dispatchers.IO) {
        val league = SportsCatalog.league(ev.leagueId) ?: return@withContext emptyList()
        val id = ev.espnId ?: return@withContext emptyList()
        if (ev.state == GameState.PRE || league.kind != LeagueKind.TEAMS) return@withContext emptyList()
        val sig = "${ev.away?.score}-${ev.home?.score}-${ev.state}"
        synchronized(playsCache) { playsCache[ev.id] }?.let { (at, was, list) ->
            if (was == sig && System.currentTimeMillis() - at < (if (ev.state == GameState.LIVE) 25_000 else 30 * MINUTE)) return@withContext list
        }
        val list = runCatching { feeds.summary(league, id) }.getOrNull() ?: return@withContext synchronized(playsCache) { playsCache[ev.id]?.third }.orEmpty()
        synchronized(playsCache) { playsCache[ev.id] = Triple(System.currentTimeMillis(), sig, list) }
        list
    }

    // ------------------------------------------------------------------ your choices

    fun updateSettings(transform: (SportsSettings) -> SportsSettings) {
        scope.launch { prefs.editSportsSettings { raw -> transform(SportsSettings.fromJson(raw)).toJson() } }
    }

    fun updateFollows(transform: (SportsFollows) -> SportsFollows) {
        scope.launch {
            prefs.editSportsMine { raw -> transform(SportsFollows.fromJson(raw)).toJson() }
            kick.value++
        }
    }

    fun toggleTeam(team: TeamInfo) = updateFollows { f ->
        val had = f.followedTeam(team)
        if (had != null) f.copy(teams = f.teams - had)
        // Just the team (its league isn't followed with it: following the NFL is a separate choice).
        else f.copy(teams = f.teams + FollowedTeam(team.leagueId, team.id, team.name, team.abbr, team.color, team.logo), setUp = true)
    }

    /** Moves a row of My Sports up ([by] -1) or down (+1). */
    fun moveRow(key: String, by: Int) = updateFollows { f ->
        val keys = SportsRows.rowKeys(f).toMutableList()
        val i = keys.indexOf(key)
        val j = i + by
        if (i < 0 || j !in keys.indices) f
        else {
            keys.add(j, keys.removeAt(i))
            f.copy(order = keys)
        }
    }

    /** A league's own button on the Sports top bar (and its row under My Teams): on or off. */
    fun togglePin(leagueId: String) = updateFollows { f ->
        if (leagueId in f.pins) f.copy(pins = f.pins - leagueId, unpinned = f.unpinned + leagueId)
        else f.copy(pins = f.pins + leagueId, unpinned = f.unpinned - leagueId, setUp = true)
    }

    fun toggleLeague(leagueId: String) = updateFollows { f ->
        f.copy(leagues = if (leagueId in f.leagues) f.leagues - leagueId else f.leagues + leagueId, setUp = true)
    }

    fun addWord(word: String) {
        val w = word.trim()
        if (w.length < 3) return
        updateFollows { f -> if (f.words.any { it.equals(w, true) }) f else f.copy(words = f.words + w, setUp = true) }
    }

    fun removeWord(word: String) = updateFollows { f -> f.copy(words = f.words.filterNot { it.equals(word, true) }) }

    fun markSetUp() = updateFollows { it.copy(setUp = true) }

    fun isReminded(ev: SportsEvent): Boolean = follows.value.reminders.any { it.eventId == ev.id }

    fun toggleReminder(ev: SportsEvent) = updateFollows { f ->
        if (f.reminders.any { it.eventId == ev.id }) f.copy(reminders = f.reminders.filterNot { it.eventId == ev.id })
        else f.copy(reminders = (f.reminders.filter { it.startMs > System.currentTimeMillis() - 3 * HOUR } + SportsReminder(ev.id, ev.title, ev.startMs)))
    }

    /** This game's reminder: switch to it by itself, or ask (sets a reminder if there wasn't one). */
    fun setAutoSwitch(ev: SportsEvent, on: Boolean) = updateFollows { f ->
        val has = f.reminders.any { it.eventId == ev.id }
        f.copy(reminders = if (has) f.reminders.map { if (it.eventId == ev.id) it.copy(autoSwitch = on) else it }
            else f.reminders + SportsReminder(ev.id, ev.title, ev.startMs, on))
    }

    /** Whether [ev]'s reminder switches by itself: its own choice, else the setting. */
    fun switchesFor(ev: SportsEvent): Boolean =
        follows.value.reminders.firstOrNull { it.eventId == ev.id }?.autoSwitch ?: settings.value.autoSwitch

    fun dropReminder(eventId: String) = updateFollows { f -> f.copy(reminders = f.reminders.filterNot { it.eventId == eventId }) }

    /**
     * "This is the right channel" for [ev]. With [forNetworks], also for every game the same
     * network has from now on (FOX on your FOX 4K channel, say).
     */
    fun pickChannel(ev: SportsEvent, channelKey: String, forNetworks: Boolean, forTeam: TeamInfo? = null) = updateSettings { s ->
        val keep = s.eventChannels.filterValues { v -> (v.substringAfter('|').toLongOrNull() ?: 0L) > System.currentTimeMillis() - 3 * DAY }
        s.copy(
            eventChannels = keep + (ev.id to "$channelKey|${ev.startMs}"),
            networkChannels = if (forNetworks) s.networkChannels + ev.broadcasts.associate { SportsMatcher.networkKey(it) to channelKey } else s.networkChannels,
            teamChannels = if (forTeam != null) s.teamChannels + (teamChannelKey(ev.leagueId, forTeam.id) to channelKey) else s.teamChannels,
            blocked = s.blocked - "${ev.id}|$channelKey"
        )
    }

    /** "Not this channel" for [ev] (and it stops being used for its network). */
    fun blockChannel(ev: SportsEvent, channelKey: String) = updateSettings { s ->
        s.copy(
            blocked = (s.blocked + "${ev.id}|$channelKey").toList().takeLast(400).toSet(),
            eventChannels = s.eventChannels.filterNot { (id, v) -> id == ev.id && v.substringBefore('|') == channelKey },
            networkChannels = s.networkChannels.filterNot { (n, key) -> key == channelKey && ev.broadcasts.any { SportsMatcher.networkKey(it) == n } },
            teamChannels = s.teamChannels.filterNot { (t, key) -> key == channelKey && ev.sides.any { teamChannelKey(ev.leagueId, it.team.id) == t } }
        )
    }

    fun clearFixes() = updateSettings { it.copy(networkChannels = emptyMap(), eventChannels = emptyMap(), blocked = emptySet(), teamChannels = emptyMap()) }

    /** The channel to tune for a reminder or "watch now": the best match, if any. */
    fun bestChannel(eventId: String): ChannelMatch? = _matches.value[eventId]?.firstOrNull()

    fun channelsForPicker(): List<LiveChannel> = channelStore.displayChannels.value

    // ------------------------------------------------------------------ days

    fun dayStart(offset: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, offset)
    }.timeInMillis

    private fun dayOf(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** ESPN groups games by US Eastern days: yesterday to tomorrow there covers the page's three days. */
    private fun espnRange(now: Long): Pair<String, String> {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        return f.format(java.util.Date(now - DAY)) to f.format(java.util.Date(now + DAY))
    }

    /** The ESPN (US Eastern) dates for yesterday, today and tomorrow. */
    private fun espnDays(now: Long): List<String> {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        return listOf(now - DAY, now, now + DAY).map { f.format(java.util.Date(it)) }
    }

    private fun safe(id: String) = id.replace(Regex("[^a-zA-Z0-9]+"), "_")

    companion object {
        private const val TAG = "Sports"

        /**
         * Where Esencial Sport's events come from. Blank disables the whole path, so a build
         * without the addon behaves exactly as before this existed.
         *
         * The sports addon's own public host, with no path: `GET {base}/events.json` answers for
         * everyone. A path would be an installation token, and baking one into the app would hand
         * every device that installation's configured sports.
         */
        const val addonBaseUrl = "https://esencialsport.attendanceos.app"
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR
        /** The page's days run from this many days back to [DAYS_AHEAD] ahead. */
        const val DAYS_AHEAD = 7
        const val DAYS_BACK = 7
        /** Between saved answers when a league was asked one day at a time. */
        private const val RAW_SEP = "\n\u0001\n"
    }
}

/** A team you follow just scored (shown for a few seconds wherever you are). */
data class SportsAlert(val event: SportsEvent, val team: TeamInfo, val league: League, val id: Long = System.nanoTime())

/** Sports data the rest of the app shows: scores on Live TV's info bar, scoring alerts. */
object SportsLive {
    val byChannel = MutableStateFlow<Map<String, SportsEvent>>(emptyMap())
    /** Channel key -> the live game on it (with or without a score), for the card on the info panel. */
    val cardByChannel = MutableStateFlow<Map<String, SportsEvent>>(emptyMap())
    val alerts = MutableStateFlow<SportsAlert?>(null)
    fun alert(a: SportsAlert) { alerts.value = a }
}
