package com.nuvio.tv.livetv.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** One side of an addon event, as the addon names it ([side] is the feed's own 1 or 2, not home/away). */
data class AddonTeam(val side: Int, val name: String, val logo: String)

/**
 * One of the feeds a match is offered on.
 *
 * These are different signals, not qualities of one another: a second language's commentary, a
 * different CDN, a broadcaster's own feed. A match's first source is routinely the one that is
 * down, so the list is the point — [streamId] is the upstream's own id and stays put across
 * refreshes, unlike a position. 0 means the feed did not name one and the position keys it instead.
 */
data class AddonSource(val streamId: Long, val label: String?, val verified: Boolean, val url: String)

/**
 * One event of the addon feed (`GET {baseUrl}/events.json`), with the feed's own field names.
 * [streamUrl] is the address it sent: it is never rebuilt from [id] or [matchId].
 */
data class AddonEvent(
    val id: String,
    val kickoffMs: Long,
    /** The two sides taking part (a feed that sends more than two, or fewer, is not a game). */
    val teams: List<AddonTeam>,
    val streamUrl: String,
    val matchId: Long = 0L,
    /** 1 = football, 2 = basketball, 3 = tennis, 4 = baseball, 7 = motorsport, 11 = hockey. */
    val sportType: Int = 0,
    val title: String = "",
    val leagueId: Long = 0L,
    val leagueName: String = "",
    val country: String = "",
    val countryId: Int = 0,
    val live: Boolean = false,
    /** "live", "scheduled" or "finished". */
    val status: String = "",
    /**
     * Every feed this match is offered on, as the addon listed them. Empty when the addon sent no
     * `sources` — an older answer, or a match that is not live yet — which is not the same claim as
     * an empty list from a feed that looked and found none, but plays the same way: [streamUrl]
     * still stands on its own.
     */
    val sources: List<AddonSource> = emptyList()
) {
    /** On now, by either the flag or the status line. */
    val isLive: Boolean get() = live || status.equals("live", ignoreCase = true)
}

/**
 * The external addon feed: a flat list of events it will play, each with the two teams, the kick-off
 * and the stream address. Kept on disk beside the other sports feeds ([SportsRepository]'s
 * `filesDir/sports`): a timestamp line, then the body, and the body saved before is served when a
 * download fails, so a screen never loses its rows to a bad minute of network.
 */
internal class SportsAddonFeed(
    private val dir: File,
    private val ttlMs: Long = TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val httpGet: (url: String) -> String = ::download
) {

    /** The feed's body from the base address: saved and still fresh, or downloaded; the saved one if that fails. */
    fun raw(baseUrl: String, force: Boolean = false): String? {
        val file = File(dir, cacheName(baseUrl))
        val saved = read(file)
        val at = now()
        if (!force && saved != null && at - saved.first < ttlMs) return saved.second
        val fresh = runCatching { httpGet(url(baseUrl)) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (fresh != null && isFeedDocument(fresh)) {
            runCatching { dir.mkdirs(); file.writeText("$at\n$fresh") }
            return fresh
        }
        // Nothing usable came back: whatever was saved is still the best answer, unchanged (so the
        // next call asks again rather than trusting the failed attempt for a whole TTL).
        return saved?.second
    }

    /** The addon's events, ready to be joined to the app's own games. */
    fun events(baseUrl: String, force: Boolean = false): List<AddonEvent> = parseEvents(raw(baseUrl, force).orEmpty())

    private fun read(file: File): Pair<Long, String>? {
        val text = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull() ?: return null
        val nl = text.indexOf('\n')
        if (nl <= 0) return null
        val at = text.substring(0, nl).toLongOrNull() ?: return null
        return at to text.substring(nl + 1)
    }

    companion object {
        /** A list of events to play is worth a minute of the TV's time, not more. */
        const val TTL_MS = 60_000L

        /** The feed's address for a base URL (a trailing slash makes no difference). */
        fun url(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/events.json"

        /** One file per base address, named like the other feeds' ("addon_https_addon_example.json"). */
        fun cacheName(baseUrl: String): String = "addon_" + baseUrl.trim().replace(Regex("[^a-zA-Z0-9]+"), "_") + ".json"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun download(url: String): String {
            val req = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                return r.body?.string() ?: throw java.io.IOException("empty answer")
            }
        }

        /** A body that is a feed document (an `events` list), as opposed to an error page or half a file. */
        private fun isFeedDocument(body: String): Boolean =
            (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.get("events") is JsonArray

        /**
         * The feed's events. Tolerant by design: an entry without an id, a kick-off or two named
         * teams is left out, and a document that isn't a feed at all is simply empty.
         */
        fun parseEvents(json: String): List<AddonEvent> {
            val root = runCatching { SportsAddonFeed.json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return emptyList()
            val events = root["events"] as? JsonArray ?: return emptyList()
            val out = ArrayList<AddonEvent>(events.size)
            for (e in events) out += event(e as? JsonObject ?: continue) ?: continue
            return out
        }

        private fun event(o: JsonObject): AddonEvent? {
            val id = o.str("id") ?: return null
            val kickoff = o.long("kickoffMs") ?: return null
            val teams = (o["teams"] as? JsonArray)?.mapNotNull { t -> team(t as? JsonObject ?: return@mapNotNull null) }.orEmpty()
            if (teams.size < 2) return null
            return AddonEvent(
                id = id,
                kickoffMs = kickoff,
                teams = teams,
                // Verbatim: the addon's own playable address, query string and all.
                streamUrl = o.str("streamUrl") ?: "",
                matchId = o.long("matchId") ?: 0L,
                sportType = o.int("sportType") ?: 0,
                title = o.str("title") ?: "",
                leagueId = o.long("leagueId") ?: 0L,
                leagueName = o.str("leagueName") ?: "",
                country = o.str("country") ?: "",
                countryId = o.int("countryId") ?: 0,
                live = (o["live"] as? JsonPrimitive)?.booleanOrNull ?: false,
                status = o.str("status") ?: "",
                sources = (o["sources"] as? JsonArray)?.mapNotNull { s -> source(s as? JsonObject ?: return@mapNotNull null) }.orEmpty(),
            )
        }

        /**
         * One source. An address is the one thing a source cannot be without: a channel that opens
         * nothing is worse than one that is not offered.
         */
        private fun source(o: JsonObject): AddonSource? {
            val url = o.str("url") ?: return null
            return AddonSource(
                streamId = o.long("streamId") ?: 0L,
                label = o.str("label"),
                verified = (o["verified"] as? JsonPrimitive)?.booleanOrNull ?: false,
                url = url,
            )
        }

        private fun team(o: JsonObject): AddonTeam? {
            val name = o.str("name") ?: return null
            return AddonTeam(side = o.int("side") ?: 0, name = name, logo = o.str("logo") ?: "")
        }

        /** Numbers the feed sometimes sends as strings ("1761148800000"). */
        private fun JsonObject.num(name: String): Double? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()

        private fun JsonObject.long(name: String): Long? = num(name)?.toLong()

        private fun JsonObject.int(name: String): Int? = num(name)?.takeIf { it in -Int.MAX_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }?.toInt()

        private fun JsonObject.str(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() && it != "null" }
    }
}
