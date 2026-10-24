package com.nuvio.tv.livetv.data

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.livetv.model.EpgSource

/**
 * The guide sources the product ships with, added to an install by itself.
 *
 * A sports listing published on the server is only useful if the device reads it, and
 * asking every user to type a URL into Settings means almost nobody does. So the guide
 * arrives with the app — once.
 *
 * "Once" is the important half. Seeding is stamped with a version in the saved settings:
 * an owner who removes the built-in guide stays removed, and a device that already saw
 * this version does not rewrite its guide list on every update check. The version only
 * goes up when the product wants every install to look again.
 *
 * Built-in guides are appended AFTER the user's own: guide matching prefers the earlier
 * source in the list, and a guide somebody chose on purpose must not be pushed aside by
 * one they never picked.
 */
object NuvioGuideSources {

    /** Raised when every install should be offered the built-in guides again. */
    const val SEED_VERSION = 1

    /** Stable id: it names the saved guide file, so it must never change meaning. */
    const val SPORTS_ID = "nuvio-sports"
    const val SPORTS_NAME = "Nuvio Sports"

    /** The published sports guide, or nothing on a build that carries no address. */
    fun builtIn(url: String = BuildConfig.SPORTS_GUIDE_URL): List<EpgSource> =
        url.trim().takeIf { it.isNotBlank() }
            ?.let { listOf(EpgSource(id = SPORTS_ID, name = SPORTS_NAME, url = it)) }
            ?: emptyList()

    /**
     * What to write, or null when this install has already been seeded at this version.
     *
     * A missing address still returns a stamp: the version has to be recorded even when
     * there was nothing to add, or every start would ask the same question again.
     */
    fun seed(current: List<EpgSource>, seededVersion: Int, url: String = BuildConfig.SPORTS_GUIDE_URL): Seed? {
        if (seededVersion >= SEED_VERSION) return null
        val missing = builtIn(url).filter { want -> current.none { it.id == want.id || it.url == want.url } }
        return Seed(current + missing)
    }

    data class Seed(val sources: List<EpgSource>, val version: Int = SEED_VERSION)

    /**
     * Which channels a guide may fill.
     *
     * The guides the product ships exist for the channels the app loads for itself: a portal's
     * "NBA TV 04" carries no tvg-id, so its name is the only thing a listing can attach to. Any
     * other guide — a user's XMLTV, a playlist's built-in one — keeps matching playlist channels
     * alone. A portal's thousand names matched by name against somebody's big guide would
     * duplicate listings their playlists already provide, and grow the guide database for signals
     * nobody published anything for.
     */
    fun <T> matchable(channels: List<T>, keyOf: (T) -> String, nativeKeys: Set<String>, builtIn: Boolean): List<T> =
        if (builtIn) channels else channels.filterNot { keyOf(it) in nativeKeys }
}
