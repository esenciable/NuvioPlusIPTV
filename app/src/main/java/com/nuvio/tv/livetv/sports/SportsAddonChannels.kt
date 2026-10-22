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
     * A channel that exists only to be played. It is deliberately NOT added to the channel store:
     * it has no place in the guide or the channel list, and nothing that reads a `ChannelMatch`
     * re-resolves the channel by key — they use the object we hand them.
     */
    fun channelFor(addon: AddonEvent): LiveChannel = LiveChannel(
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
        url = addon.streamUrl,
    )

    /**
     * Puts the feed's link in front of every game it can serve.
     *
     * Returns [matches] itself when there is nothing to do, so a feed that never loaded (or a build
     * with the addon off) leaves the page exactly as it was.
     *
     * @param blocked the user's `"eventId|channelKey"` refusals, same shape as
     *   [SportsSettings.blocked] — a channel the user said isn't showing a game stays out, whether
     *   the guide suggested it or we did.
     */
    fun decorate(
        matches: Map<String, List<ChannelMatch>>,
        events: List<SportsEvent>,
        candidates: List<AddonEvent>,
        blocked: Set<String> = emptySet(),
    ): Map<String, List<ChannelMatch>> {
        if (candidates.isEmpty()) return matches
        var out: HashMap<String, List<ChannelMatch>>? = null
        for (ev in events) {
            val addon = SportsAddonMatcher.find(ev, SportsCatalog.league(ev.leagueId), candidates) ?: continue
            val channel = channelFor(addon)
            if ("${ev.id}|${channel.key}" in blocked) continue
            val existing = matches[ev.id].orEmpty()
            if (existing.any { it.channel.key == channel.key }) continue
            val ours = ChannelMatch(
                channel = channel,
                score = SCORE,
                how = HOW,
                program = null,
                quality = null,
                catchup = false,
            )
            if (out === null) out = HashMap(matches)
            out[ev.id] = listOf(ours) + existing
        }
        return out ?: matches
    }
}
