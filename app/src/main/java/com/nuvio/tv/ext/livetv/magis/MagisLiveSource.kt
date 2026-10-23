package com.nuvio.tv.ext.livetv.magis

import com.nuvio.tv.livetv.model.LiveChannel

/**
 * The identity Magis live wears inside the base's Live TV: not a playlist, not an addon — a list of
 * [LiveChannel]s built from the portal's own categories, rendered by the exact same models the
 * playlist channels use so groups, search, favorites and recents treat them as ordinary channels.
 *
 * [MARKER] is a sentinel URL scheme, never a network address: a native channel cannot carry a
 * playable URL at catalog time (a Magis playlist needs a session, a playCode and a signature that
 * expires in seconds), so it carries `magis-live://<channelCode>` and
 * [com.nuvio.tv.ext.livetv.data.MagisStreamResolver] turns that into a real URL + fresh signed
 * headers at playback time. No real playlist line can ever carry that scheme, so the two key
 * spaces cannot collide.
 */
object MagisLiveSource {

    /** The sentinel scheme a Magis channel's `url` carries until it is resolved. */
    const val MARKER_SCHEME = "magis-live://"

    /** The `sourceId` every Magis channel wears (no generated playlist id looks like this). */
    const val SOURCE_ID = "magis"

    /** The display name the source carries in channel rows and group titles. */
    const val SOURCE_NAME = "Magis"

    /** The channel key for a portal code: stable across reloads, so favorites and recents survive. */
    fun channelKey(code: String): String = "$SOURCE_ID:$code"

    /** The group id for one portal category — Magis categories are ordinary Live TV groups. */
    fun groupId(categoryId: Int): String = "$SOURCE_ID:cat:$categoryId"

    /** The channel-code half of a key or marker URL. */
    fun codeOf(keyOrUrl: String): String? {
        val code = when {
            keyOrUrl.startsWith("$SOURCE_ID:") -> keyOrUrl.removePrefix("$SOURCE_ID:")
            keyOrUrl.startsWith(MARKER_SCHEME) -> keyOrUrl.removePrefix(MARKER_SCHEME)
            else -> return null
        }
        // A group id shares the "magis:" prefix ("magis:cat:3"); it names a category, not a
        // channel, and must never be resolved as one.
        return code.takeIf { it.isNotBlank() && !it.startsWith("cat:") }
    }

    /** Whether [channel] is a Magis channel whose URL has never been resolved. */
    fun isMarkerUrl(url: String): Boolean = url.startsWith(MARKER_SCHEME)

    /**
     * One portal channel as a base [LiveChannel]. [categoryId]/[categoryName] are the category it
     * was read from; a channel in several categories keeps the FIRST one (the base's model carries
     * one group per channel — the same channel is not duplicated, so the group list stays stable).
     */
    fun channelFrom(
        channel: MagisLiveChannel,
        categoryId: Int,
        categoryName: String,
    ): LiveChannel = LiveChannel(
        key = channelKey(channel.code),
        sourceId = SOURCE_ID,
        sourceName = SOURCE_NAME,
        name = channel.name,
        // No tvg-id: the base's guide matches by tvg-id and Magis channels have none, so they
        // simply show no listings (same as the fork's first pass) instead of matching wrongly.
        tvgId = null,
        tvgName = channel.name.takeIf { it.isNotBlank() },
        // The portal delivers live art as `posterUrl`; a channel's visual identity is its logo.
        logo = channel.logo.takeIf(String::isNotBlank),
        groupId = groupId(categoryId),
        group = categoryName,
        number = channel.number,
        url = MARKER_SCHEME + channel.code,
        headers = emptyMap(),
        catchup = null,
        drm = null,
    )
}
