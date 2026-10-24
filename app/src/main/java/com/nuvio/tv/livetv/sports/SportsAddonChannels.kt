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
     * Stable across refreshes: favourites, the channel you picked for a game and the channels you
     * blocked for it all persist by key, so a key that moved would silently forget what the user
     * decided.
     */
    fun keyOf(addon: AddonEvent) = "ext:${addon.id}"

    /**
     * The key of one source of an event.
     *
     * The upstream's own id when it sent one. Only when it did not do we fall back to the position,
     * and only there — a position is not stable across refreshes, so a refusal remembered against
     * one could otherwise land on a different feed next time.
     */
    fun keyOf(addon: AddonEvent, source: AddonSource, index: Int) =
        "${keyOf(addon)}#${if (source.streamId != 0L) source.streamId.toString() else "i$index"}"

    /**
     * Every channel an event offers: one per source when the feed named them, and the match's single
     * stable link when it did not.
     *
     * The ones we watched answer with a real playlist come first. These channels all carry the same
     * score, so the order here is what `bestChannel()` ends up playing automatically — it should be
     * a signal we saw working, not one the upstream merely claims to have.
     */
    fun channelsFor(baseUrl: String, addon: AddonEvent): List<LiveChannel> {
        if (addon.sources.isEmpty()) return listOf(channelFor(baseUrl, addon))
        return addon.sources.mapIndexed { i, s -> i to s }
            .sortedWith(compareBy({ !it.second.verified }, { it.first }))
            .map { (index, source) -> channelFor(baseUrl, addon, source, index) }
    }

    /** One source as a channel, named so the two are tellable apart in the picker. */
    fun channelFor(baseUrl: String, addon: AddonEvent, source: AddonSource, index: Int): LiveChannel = channelFor(baseUrl, addon).copy(
        key = keyOf(addon, source, index),
        // The upstream's own name for the feed when it gave one; its place in the list when it
        // did not. "Esencial Sport" alone would render ten identical rows.
        name = "$SOURCE_NAME · " + (source.label?.takeIf { it.isNotBlank() } ?: (index + 1).toString()),
        url = resolveUrl(baseUrl, source.url),
    )

    /**
     * A channel that exists only to be played. It is deliberately NOT added to the channel store:
     * it has no place in the guide or the channel list, and nothing that reads a `ChannelMatch`
     * re-resolves the channel by key — they use the object we hand them.
     */
    fun channelFor(baseUrl: String, addon: AddonEvent): LiveChannel = LiveChannel(
        key = keyOf(addon),
        sourceId = SOURCE_ID,
        sourceName = SOURCE_NAME,
        name = SOURCE_NAME,
        // No tvg-id: this channel is not in the guide, and claiming an id would invite catch-up
        // and recording promises that cannot be kept.
        tvgId = null,
        tvgName = addon.title.takeIf { it.isNotBlank() },
        logo = addon.teams.firstOrNull()?.logo?.takeIf { it.isNotBlank() },
        groupId = SOURCE_ID,
        group = SOURCE_NAME,
        // The matcher sorts a tie by channel number; 0 keeps ours first, which is what we want.
        number = 0,
        url = resolveUrl(baseUrl, addon.streamUrl),
    )

    /**
     * Builds the full URL to the addon's playable address.
     *
     * The addon's `/events.json` answers paths relative to the addon's own base (`streamUrl` and
     * `sources[].url`), because the same build serves `/` and `/kt` equally. The player needs a
     * fully-qualified address, so the base travels with the feed and is joined here — keeping one
     * slash where the path already brought one, or a strict upstream reads `//rblive/...` as a
     * different resource and answers 404 to a signal that is there.
     */
    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        val trimmed = relativeUrl.trim()
        if (trimmed.isBlank()) return trimmed
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
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
    ): Map<String, List<ChannelMatch>> {
        if (candidates.isEmpty()) return matches
        var out: HashMap<String, List<ChannelMatch>>? = null
        for (ev in events) {
            val addon = SportsAddonMatcher.find(ev, SportsCatalog.league(ev.leagueId), candidates) ?: continue
            val existing = matches[ev.id].orEmpty()
            val channels = SportsAddonChannels.channelsFor(baseUrl, addon)
                .filterNot { "${ev.id}|${it.key}" in blocked }
                .filterNot { ch -> existing.any { it.channel.key == ch.key } }
            if (channels.isEmpty()) continue
            val ours = channels.map {
                ChannelMatch(
                    channel = it,
                    score = SCORE,
                    how = HOW,
                    program = null,
                    quality = null,
                    catchup = false,
                )
            }
            if (out === null) out = HashMap(matches)
            out[ev.id] = ours + existing
        }
        return out ?: matches
    }
}
