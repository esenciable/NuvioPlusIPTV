package com.nuvio.tv.ext.livetv.magis

/**
 * Title/year lookup port for the Magis VOD flow. The plugin resolves the title with TMDB
 * (`flatTmdbInfo`); the fork ALREADY has the whole TMDB stack, so the Android implementation in
 * `ext/livetv/platform/TmdbTitleLookup.kt` reuses it instead of porting a new TMDB client — and
 * the mobile client brings its own.
 *
 * The port lives here, in the portable package, because the VOD selection logic depends on this
 * shape and not on how a client talks to TMDB.
 */
interface MagisTitleLookup {

    /**
     * The localized + original titles for [id] (a numeric TMDB id, `tmdb:`-prefixed, or an
     * IMDb `tt…` id). `null` when TMDB has no entry — the plugin answers the same way and the
     * VOD flow surfaces it as a typed error, never a silent empty result.
     */
    suspend fun lookup(id: String, isSeries: Boolean): MagisTitleInfo?
}

/** The title pair the search fallback walks: localized first, then original. */
data class MagisTitleInfo(
    val title: String,
    val originalTitle: String,
    /** Release year (movie release date / TV first air date) when TMDB provides one. */
    val year: Int? = null,
)
