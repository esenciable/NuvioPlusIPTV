package com.nuvio.tv.livetv.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The "Partidos de Hoy" feed (la18hd.su): a public static JSON listing today's football matches,
 * each repeated once per channel that carries it.
 *
 * The shape is the opposite of the addon feed's in one useful way: there an event owns one stream
 * the upstream resolved; here an event owns CHANNELS — ESPN, DSports, Premiere — so a match is
 * "watch it on ESPN 4". The rows that follow it are 24/7 sports channels, and the signed HLS
 * behind a channel is minted at play time by the la18 resolver (the CDN blocks datacenter IPs
 * outright, so no server may stand between the device and the stream).
 *
 * Grouping: the JSON repeats `(title, date, time)` once per link, so entries are grouped on that
 * triple into one [AddonEvent] with one [AddonSource] per la18hd channel link. Links that are not
 * la18hd channel pages (third-party embeds, measured unplayable) are dropped: a row that opens an
 * ad shell is worse than a row that is not offered.
 */
internal class SportsLa18Feed(
    private val dir: File,
    private val ttlMs: Long = TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val httpGet: (url: String) -> String = ::download,
) {

    /** The feed's body: saved and still fresh, or downloaded; the saved one if that fails. */
    fun raw(force: Boolean = false): String? {
        val file = File(dir, CACHE_NAME)
        val saved = read(file)
        val at = now()
        if (!force && saved != null && at - saved.first < ttlMs) return saved.second
        val fresh = runCatching { httpGet(FEED_URL) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (fresh != null && isFeedDocument(fresh)) {
            runCatching { dir.mkdirs(); file.writeText("$at\n$fresh") }
            return fresh
        }
        // Nothing usable came back: whatever was saved is still the best answer, unchanged (so the
        // next call asks again rather than trusting the failed attempt for a whole TTL).
        return saved?.second
    }

    /** The feed's events, ready to be joined to the app's own games. */
    fun events(force: Boolean = false): List<AddonEvent> = parseEvents(raw(force).orEmpty(), now())

    private fun read(file: File): Pair<Long, String>? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val i = text.indexOf('\n')
        if (i <= 0) return null
        val at = text.substring(0, i).toLongOrNull() ?: return null
        return at to text.substring(i + 1)
    }

    companion object {
        /** The site's own agenda document; its name is part of the site's assets, not an API. */
        const val FEED_URL = "https://la18hd.su/eventos/json/agenda123.json"

        /** A list of events to play is worth a minute of the TV's time, not more. */
        const val TTL_MS = 60_000L

        /** One file: one upstream, one cache. */
        const val CACHE_NAME = "la18_agenda.json"

        /**
         * How long after kick-off a match still counts as live, straight from the site's own
         * script (an event stays "en vivo" for 150 minutes past its start): half-time breaks and
         * stoppage make the clock a bad judge of "over".
         */
        const val LIVE_TAIL_MS = 150 * 60_000L

        /**
         * The site publishes in America/Lima, which has stayed UTC-5 year-round since 1994, so the
         * conversion is an arithmetic offset — no tz database on the device, no DST surprises.
         */
        const val SITE_UTC_OFFSET_HOURS = 5

        /**
         * A channel's stream address as the rest of the pipeline sees it: a placeholder the
         * resolver mints into a signed HLS URL at play time (the token lives five hours and there
         * is nothing worth pre-minting).
         */
        fun streamPlaceholder(channelId: String) = "la18:$channelId"

        /** The la18hd channel id inside an agenda link, or null for anything else. */
        fun channelId(link: String): String? =
            Regex("""canales\.php\?stream=([A-Za-z0-9_.-]+)""").find(link.trim())?.groupValues?.get(1)

        private val parser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** A body that is the agenda (a bare JSON array), as opposed to an error page. */
        private fun isFeedDocument(body: String): Boolean =
            runCatching { parser.parseToJsonElement(body) }.getOrNull() is JsonArray

        /**
         * The feed's events. Tolerant by design: an entry without a date, a time, a kick-off or a
         * "Team vs Team" title is left out, and a document that isn't the agenda is simply empty.
         */
        fun parseEvents(json: String, now: Long): List<AddonEvent> {
            val root = runCatching { parser.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return emptyList()
            val grouped = LinkedHashMap<String, MutableList<JsonObject>>()
            for (e in root) {
                val o = e as? JsonObject ?: continue
                val title = o.str("title") ?: continue
                val date = o.str("date") ?: continue
                val time = o.str("time") ?: continue
                grouped.getOrPut("$title\n$date\n$time") { ArrayList() }.add(o)
            }
            val out = ArrayList<AddonEvent>(grouped.size)
            for ((key, rows) in grouped) out += eventOf(key, rows, now) ?: continue
            return out
        }

        /** One event from its grouped entries: teams from the title, one source per channel link. */
        private fun eventOf(key: String, rows: List<JsonObject>, now: Long): AddonEvent? {
            val title = key.substringBefore('\n')
            val date = key.substringAfter('\n').substringBefore('\n')
            val time = key.substringAfterLast('\n')
            val kickoff = kickoffMs(date, time) ?: return null
            val sides = La18Title.of(title) ?: return null
            val category = rows.firstNotNullOfOrNull { it.str("category") }.orEmpty()

            // The site's own status column is written when the row is made and goes stale; its
            // script recomputes from the clock, and so do we — same window, same result.
            val elapsed = now - kickoff
            val (live, status) = when {
                elapsed >= LIVE_TAIL_MS -> false to "finished"
                elapsed >= 0 -> true to "live"
                else -> false to "scheduled"
            }

            val seen = HashSet<String>()
            val sources = ArrayList<AddonSource>(rows.size)
            for (row in rows) {
                val channel = channelId(row.str("link").orEmpty()) ?: continue
                val url = streamPlaceholder(channel)
                if (!seen.add(url)) continue
                sources += AddonSource(streamId = 0L, label = prettify(channel), verified = false, url = url)
            }
            if (sources.isEmpty()) return null

            return AddonEvent(
                // The upstream names nothing; the grouping triple is what stays put.
                id = "la18-" + slug(title, date),
                kickoffMs = kickoff,
                teams = sides.toTeams(),
                streamUrl = sources.first().url,
                sportType = if (category.equals("futbol", ignoreCase = true)) 1 else 0,
                title = title,
                leagueId = 0L,
                leagueName = sides.league.orEmpty(),
                live = live,
                status = status,
                sources = sources,
            )
        }

        /** A stable, readable id from the grouping triple's stable parts. */
        private fun slug(title: String, date: String): String =
            title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                .take(60) + "-" + date.replace("-", "")

        /**
         * The row name people read ("Partidos de Hoy · ESPN 4"): the upstream's ids are terse and
         * some carry a variant suffix (`dsports_op2`, `espn3mx`), so the known ones get a real
         * name and an unknown one gets its id upper-cased — a label, not a guess.
         */
        fun prettify(channelId: String): String = CHANNEL_NAMES[channelId.lowercase(Locale.ROOT)]
            ?: channelId.uppercase(Locale.ROOT)

        /**
         * Kick-off as epoch millis: `date` (ISO) + `time` (site-local HH:mm) shifted to UTC. A
         * time past midnight after the shift rolls the day forward — the site publishes Lima
         * wall-clock times, so 20:45 Lima is 01:45 UTC the next day and must land there.
         */
        fun kickoffMs(date: String, time: String): Long? {
            val (y, mo, d) = parseDate(date) ?: return null
            val (h, mi) = parseTime(time) ?: return null
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(y, mo - 1, d, h + SITE_UTC_OFFSET_HOURS, mi, 0)
            }
            return cal.timeInMillis
        }

        private fun parseDate(date: String): Triple<Int, Int, Int>? {
            val p = date.split('-')
            if (p.size != 3) return null
            val (y, mo, d) = listOf(p[0].toIntOrNull(), p[1].toIntOrNull(), p[2].toIntOrNull())
            if (y == null || mo == null || d == null) return null
            return Triple(y, mo, d)
        }

        private fun parseTime(time: String): Pair<Int, Int>? {
            val p = time.split(':')
            if (p.size < 2) return null
            val h = p[0].toIntOrNull() ?: return null
            val mi = p[1].toIntOrNull() ?: return null
            // A wall clock cannot carry 25:15, and Calendar would silently roll it over.
            if (h !in 0..23 || mi !in 0..59) return null
            return h to mi
        }

        private fun download(url: String): String {
            val req = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
                .build()
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .build()
                .newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    return r.body?.string() ?: throw java.io.IOException("empty answer")
                }
        }

        /** The channel ids the feed carried when this was written; a new one falls back to upper-case. */
        private val CHANNEL_NAMES = mapOf(
            "beinsportes" to "BeIN Sports",
            "disney2" to "Disney 2",
            "disney5" to "Disney 5",
            "disney6" to "Disney 6",
            "disney7" to "Disney 7",
            "disney8" to "Disney 8",
            "disney9" to "Disney 9",
            "dsports" to "DSports",
            "dsports_op2" to "DSports (op. 2)",
            "dsportsar" to "DSports AR",
            "espn" to "ESPN",
            "espn2" to "ESPN 2",
            "espn2mx" to "ESPN 2 MX",
            "espn3mx" to "ESPN 3 MX",
            "espn4" to "ESPN 4",
            "espn5" to "ESPN 5",
            "espn7" to "ESPN 7",
            "espndeportes" to "ESPN Deportes",
            "espnpremium" to "ESPN Premium",
            "hypermotion1" to "Hypermotion 1",
            "premiere1" to "Premiere 1",
            "premiere2" to "Premiere 2",
            "premiere3" to "Premiere 3",
            "tntsportschile" to "TNT Sports Chile",
            "winplus" to "Win+",
        )
    }
}

/**
 * The title's two sides: "League: Home vs Away", "Messi Cup – Boca Juniors vs. UE Cornellà",
 * "Home vs Away". The league part is display-only ([AddonEvent.leagueName]), so a title that
 * carries no separator still matches — the join never reads it.
 */
internal object La18Title {

    /** "Team vs Team" (the site also writes "vs."); the first one splits the sides. */
    private val VS = Regex("""\s+vs\.?\s+""", RegexOption.IGNORE_CASE)

    /** How the league is separated from the home side, when it is there at all. */
    private val SEPARATORS = listOf(": ", " – ", " - ")

    data class Sides(val league: String?, val home: String, val away: String)

    fun of(title: String): Sides? {
        val m = VS.find(title) ?: return null
        // The match's leading whitespace is part of it, so the head keeps the separator's own
        // space and a trailing ": " survives to be found below.
        val head = title.substring(0, m.range.first + 1).trimEnd()
        val away = title.substring(m.range.last + 1).trim()
        if (head.isEmpty() || away.isEmpty()) return null
        // The LAST separator in the head splits league from home: "Messi Cup – Boca Juniors"
        // is league "Messi Cup", home "Boca Juniors". No separator: the whole head is the home.
        val cut = SEPARATORS.map { head.lastIndexOf(it) to it }.filter { it.first >= 0 }
            .maxByOrNull { it.first }
        if (cut == null) return Sides(null, head.trim(), away)
        val league = head.substring(0, cut.first).trim().takeIf { it.isNotEmpty() }
        val home = head.substring(cut.first + cut.second.length).trim()
        if (home.isEmpty()) return null
        return Sides(league, home, away)
    }
}

/** The parsed sides as [AddonTeam]s, side 1 and 2 like the addon feed names them. */
internal fun La18Title.Sides.toTeams(): List<AddonTeam> = listOf(
    AddonTeam(side = 1, name = home, logo = ""),
    AddonTeam(side = 2, name = away, logo = ""),
)

private fun JsonObject.str(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
