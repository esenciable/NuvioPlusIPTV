package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel

/**
 * Turns an event of the addon feed into the thing the Sports page already knows how to use: a
 * channel.
 *
 * The tab resolves *where to watch* by scoring guide text against team names, and that is the layer
 * that lies: a source grid can carry two overlapping rows naming two different matches for the same
 * hour. The feed knows the answer outright — the fixture, the kick-off and a working URL — so here
 * it becomes one more channel, ranked above the guesses.
 *
 * It is added in front, never instead. Catch-up and recording are only available on a channel with
 * a real [EpgProgram], and ours has none, so the guide's matches stay in the list underneath: the
 * picker still offers them, and "watch from start" still works on them.
 */
internal object SportsAddonChannels {

    /** Above the matcher's top band (`>= 150`), so `bestChannel()` takes it without being told. */
    const val SCORE = 200

    /** What [ChannelMatch.how] says about this one, for reports and the "why this channel" text. */
    const val HOW = "addon"

    const val SOURCE_ID = "addon"
    const val SOURCE_NAME = "Esencial Sport"

    /**
     * One channel-source feed's identity on the page: how its rows are named, how highly they
     * rank, and how one source of an event is told apart inside a channel key.
     *
     * The key suffix must be stable across refreshes — favourites, the channel you picked for a
     * game and the channels you blocked for it all persist by key — and, for feeds whose URLs are
     * resolved at play time, must carry the id the resolver re-marks from.
     */
    data class Feed(
        val id: String,
        val name: String,
        val score: Int,
        val how: String,
        val keySuffix: (source: AddonSource, index: Int) -> String,
    )

    /** Esencial Sport: the upstream's own stream id when it named one, its place when it did not. */
    val ADDON = Feed(
        id = SOURCE_ID,
        name = SOURCE_NAME,
        score = SCORE,
        how = HOW,
    ) { s, i -> if (s.streamId != 0L) s.streamId.toString() else "i$i" }

    /** la18hd: the channel id is the identity, and the resolver re-marks from it. */
    val LA18 = Feed(
        id = "la18",
        name = "Partidos de Hoy",
        // One band under the addon's: where both feeds carry the same match, the addon's named
        // streams (a commentary language each) stay the automatic pick and bestChannel() opens
        // one of them; la18's channels still rank above every guide guess (`>= 150`).
        score = SCORE - 10,
        how = "la18",
    ) { s, _ -> s.url.removePrefix("la18:") }

    /**
     * Stable across refreshes: favourites, the channel you picked for a game and the channels you
     * blocked for it all persist by key, so a key that moved would silently forget what the user
     * decided.
     */
    fun keyOf(addon: AddonEvent) = "ext:${addon.id}"

    /** The addon feed's key for one source. See [ADDON]'s suffix rule. */
    fun keyOf(addon: AddonEvent, source: AddonSource, index: Int) =
        keyOf(ADDON, addon, source, index)

    /**
     * The key of one source of an event, under [feed]'s own suffix rule.
     */
    fun keyOf(feed: Feed, addon: AddonEvent, source: AddonSource, index: Int) =
        "${keyOf(addon)}#${feed.keySuffix(source, index)}"

    /**
     * Every channel an event offers: one per source when the feed named them, and the match's
     * single stable link when it did not.
     *
     * The ones we watched answer with a real playlist come first. These channels all carry the
     * same score, so the order here is what `bestChannel()` ends up playing automatically — it
     * should be a signal we saw working, not one the upstream merely claims to have.
     */
    fun channelsFor(feed: Feed, baseUrl: String, addon: AddonEvent): List<LiveChannel> {
        if (addon.sources.isEmpty()) return listOf(channelFor(feed, baseUrl, addon))
        return addon.sources.mapIndexed { i, s -> i to s }
            .sortedWith(compareBy({ !it.second.verified }, { it.first }))
            .map { (index, source) -> channelFor(feed, baseUrl, addon, source, index) }
    }

    /** The addon feed's channels for one event. See [ADDON]. */
    fun channelsFor(baseUrl: String, addon: AddonEvent): List<LiveChannel> = channelsFor(ADDON, baseUrl, addon)

    /** One source as a channel, named so the two are tellable apart in the picker. */
    fun channelFor(feed: Feed, baseUrl: String, addon: AddonEvent, source: AddonSource, index: Int): LiveChannel =
        channelFor(feed, baseUrl, addon).copy(
            key = keyOf(feed, addon, source, index),
            // The upstream's own name for the feed when it gave one; its place in the list when
            // it did not. "Esencial Sport" alone would render ten identical rows.
            name = feed.name + " · " + (source.label?.takeIf { it.isNotBlank() } ?: (index + 1).toString()),
            url = resolveUrl(baseUrl, source.url),
        )

    /** The addon feed's channel for one source. See [ADDON]. */
    fun channelFor(baseUrl: String, addon: AddonEvent, source: AddonSource, index: Int): LiveChannel =
        channelFor(ADDON, baseUrl, addon, source, index)

    /** The addon feed's channel for an event with no named sources. See [ADDON]. */
    fun channelFor(baseUrl: String, addon: AddonEvent): LiveChannel = channelFor(ADDON, baseUrl, addon)

    /**
     * A channel that exists only to be played. It is deliberately NOT added to the channel store:
     * it has no place in the guide or the channel list, and nothing that reads a `ChannelMatch`
     * re-resolves the channel by key — they use the object we hand them.
     */
    fun channelFor(feed: Feed, baseUrl: String, addon: AddonEvent): LiveChannel = LiveChannel(
        key = keyOf(addon),
        sourceId = feed.id,
        sourceName = feed.name,
        name = feed.name,
        // No tvg-id: this channel is not in the guide, and claiming an id would invite catch-up
        // and recording promises that cannot be kept.
        tvgId = null,
        tvgName = addon.title.takeIf { it.isNotBlank() },
        logo = addon.teams.firstOrNull()?.logo?.takeIf { it.isNotBlank() },
        groupId = feed.id,
        group = feed.name,
        // The matcher sorts a tie by channel number; 0 keeps ours first, which is what we want.
        number = 0,
        url = resolveUrl(baseUrl, addon.streamUrl),
    )

    /**
     * Builds the full URL to the feed's playable address.
     *
     * A feed may answer paths relative to its own base (`streamUrl` and `sources[].url`), because
     * the same build serves `/` and `/kt` equally. The player needs a fully-qualified address, so
     * the base travels with the feed and is joined here — keeping one slash where the path already
     * brought one, or a strict upstream reads `//rblive/...` as a different resource and answers
     * 404 to a signal that is there.
     *
     * A placeholder scheme (`la18:espn4`) is neither relative nor absolute: it is left alone, for
     * the resolver to mint at play time.
     */
    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        val trimmed = relativeUrl.trim()
        if (trimmed.isBlank()) return trimmed
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        val colon = trimmed.indexOf(':')
        if (colon in 1..16 && trimmed.substring(0, colon).all { it.isLetterOrDigit() || it in "+.-" } &&
            !trimmed.substring(colon + 1).startsWith("//")
        ) return trimmed
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return trimmed
        return if (trimmed.startsWith("/")) base + trimmed else "$base/$trimmed"
    }

    /**
     * Puts the feed's links in front of every game it can serve.
     *
     * Returns [matches] itself when there is nothing to do, so a feed that never loaded (or a build
     * with the addon off) leaves the page exactly as it was.
     *
     * @param blocked the user's `"eventId|channelKey"` refusals, same shape as
     *   [SportsSettings.blocked] — a channel the user said isn't showing a game stays out, whether
     *   the guide suggested it or we did. Refusing one of our sources refuses that source only: the
     *   match's other feeds are different signals and stay pickable.
     */
    fun decorate(
        baseUrl: String,
        matches: Map<String, List<ChannelMatch>>,
        events: List<SportsEvent>,
        candidates: List<AddonEvent>,
        blocked: Set<String> = emptySet(),
    ): Map<String, List<ChannelMatch>> = decorate(ADDON, baseUrl, matches, events, candidates, blocked)

    /**
     * Puts [feed]'s links in front of every game it can serve.
     *
     * Returns [matches] itself when there is nothing to do, so a feed that never loaded (or a
     * build with the feed off) leaves the page exactly as it was.
     *
     * @param blocked the user's `"eventId|channelKey"` refusals, same shape as
     *   [SportsSettings.blocked] — a channel the user said isn't showing a game stays out, whether
     *   the guide suggested it or we did. Refusing one of our sources refuses that source only: the
     *   match's other feeds are different signals and stay pickable.
     */
    fun decorate(
        feed: Feed,
        baseUrl: String,
        matches: Map<String, List<ChannelMatch>>,
        events: List<SportsEvent>,
        candidates: List<AddonEvent>,
        blocked: Set<String> = emptySet(),
    ): Map<String, List<ChannelMatch>> {
        if (candidates.isEmpty()) return matches
        var out: HashMap<String, List<ChannelMatch>>? = null
        for (ev in events) {
            val addon = SportsAddonMatcher.find(ev, SportsCatalog.league(ev.leagueId), candidates) ?: continue
            val existing = matches[ev.id].orEmpty()
            val channels = SportsAddonChannels.channelsFor(feed, baseUrl, addon)
                .filterNot { "${ev.id}|${it.key}" in blocked }
                .filterNot { ch -> existing.any { it.channel.key == ch.key } }
            if (channels.isEmpty()) continue
            val ours = channels.map {
                ChannelMatch(
                    channel = it,
                    score = feed.score,
                    how = feed.how,
                    program = null,
                    quality = null,
                    catchup = false,
                )
            }
            if (out === null) out = HashMap(matches)
            // Ours are prepended (they outrank the guide's guesses), but the whole list is then
            // kept score-ordered and STABLE: a feed that runs a second pass must not jump ahead
            // of a higher-scored row that was already there — `bestChannel()` plays the first.
            out[ev.id] = (ours + existing).sortedByDescending { it.score }
        }
        return out ?: matches
    }
}
