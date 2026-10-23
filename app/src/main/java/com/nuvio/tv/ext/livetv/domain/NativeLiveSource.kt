package com.nuvio.tv.ext.livetv.domain

import com.nuvio.tv.livetv.model.LiveChannel

/**
 * The catalog side of one native live source: the walk that turns the source's own categories and
 * channels into [LiveChannel]s the base's Live TV UI can group, search and favourite without
 * knowing where they came from. Implemented per source (e.g. [data.MagisChannelLoader]).
 *
 * The disabled-config contract is carried here: [isConfigured] false means the source is ABSENT —
 * never a crash, never a load, exactly like a build without the feature.
 */
interface NativeLiveCatalogLoader {
    /** The source's [LiveChannel.sourceId]; a playlist can never generate an id like it. */
    val sourceId: String

    val isConfigured: Boolean

    /**
     * Every channel of every category, in category order. Never throws: a failed category is
     * skipped, contributes no channels, and the categories that did answer still arrive.
     */
    suspend fun loadChannels(): List<LiveChannel>
}

/**
 * The playback side of one native live source. A native channel's catalog URL is a placeholder
 * carrying [markerScheme] — it cannot be opened, and it must not be: the real URL and the headers
 * the CDN demands are minted seconds before playback and expire minutes later.
 *
 * - [owns] says whether the channel belongs to this source (by [LiveChannel.sourceId]);
 * - [needsResolution] says whether it still carries its placeholder URL;
 * - [resolve] returns a copy of the channel with the real URL and fresh headers, or null when the
 *   source cannot serve it (portal down, session dead, no CDN). The caller surfaces the failure;
 *   it never guesses a URL.
 *
 * A RESOLVED channel is never resolved again by accident: its URL no longer starts with
 * [markerScheme], so [needsResolution] is false and the player treats it as an ordinary channel.
 * Re-signing after an expiry goes through the source on purpose (a fresh resolve, a fresh host, a
 * fresh signature), never through a cached copy of the old one.
 */
interface NativeLiveResolver {
    val sourceId: String

    /** The scheme a not-yet-resolved channel's URL carries (`magis-live://…`). */
    val markerScheme: String

    fun owns(channel: LiveChannel): Boolean = channel.sourceId == sourceId

    fun needsResolution(channel: LiveChannel): Boolean =
        owns(channel) && channel.url.startsWith(markerScheme)

    /**
     * [channel] back in its placeholder form, so the next playback resolves a FRESH signal (new
     * host, new signature) instead of replaying the pair that has been sitting in the player
     * since the channel started. Called when the viewer asks for live again after a pause or a
     * rewind — minutes can have gone by, and an expired signature is a 403 far from its cause.
     *
     * A channel this source cannot re-mark (no code in its key) comes back unchanged.
     */
    fun reMarker(channel: LiveChannel): LiveChannel

    /** A copy of [channel] with a real URL and fresh headers, or null when it cannot be served. */
    suspend fun resolve(channel: LiveChannel): LiveChannel?
}
