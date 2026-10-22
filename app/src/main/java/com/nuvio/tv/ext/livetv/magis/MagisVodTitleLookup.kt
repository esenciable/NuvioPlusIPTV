package com.nuvio.tv.ext.livetv.magis

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi

/**
 * Title/year lookup port for the Magis VOD flow. The plugin resolves the title with TMDB
 * (`flatTmdbInfo`); the fork ALREADY has the whole TMDB stack ([TmdbService] for the
 * id normalization + imdb↔tmdb conversion with caches, [TmdbApi] for the details), so this
 * port reuses it instead of porting a new TMDB client.
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

/**
 * The fork's TMDB resolution behind [MagisTitleLookup]. `ensureTmdbId` handles every id shape
 * the app produces (`123`, `tmdb:123`, `tt1234567`, with its imdb→tmdb cache) and the details
 * call brings `title`/`original_title` (movie) or `name`/`original_name` (tv), with the same
 * mutual fallback the plugin's `flatDetail` applies.
 *
 * LANGUAGE: both details calls pass `es-MX`, the plugin's TMDB language — the Magis portal is a
 * Spanish catalog, and asking TMDB in English made the lookup search "Dune" while the portal
 * lists "Duna", so the only candidate sharing the word was a mockbuster. The Spanish answer
 * still carries `original_title`/`original_name` ("Dune"), so the original-title fallback keeps
 * working, exactly like the plugin's `flatDetail` pair ("Duna", "Dune").
 */
internal class TmdbTitleLookup(
    private val tmdbService: TmdbService,
    private val tmdbApi: TmdbApi,
) : MagisTitleLookup {

    override suspend fun lookup(id: String, isSeries: Boolean): MagisTitleInfo? {
        val mediaType = if (isSeries) "tv" else "movie"
        val tmdbId = runCatching { tmdbService.ensureTmdbId(id, mediaType) }.getOrNull() ?: return null
        val numericId = tmdbId?.toIntOrNull() ?: return null
        return runCatching {
            val apiKey = tmdbService.apiKey()
            val details = if (isSeries) {
                tmdbApi.getTvDetails(numericId, apiKey, language = TMDB_LANGUAGE).body()
            } else {
                tmdbApi.getMovieDetails(numericId, apiKey, language = TMDB_LANGUAGE).body()
            } ?: return@runCatching null
            // flatDetail's mutual fallback: title takes the original when missing and
            // vice versa, so both fallback candidates are always populated together.
            val title = (details.title ?: details.name ?: details.originalTitle ?: details.originalName)
                ?.trim().orEmpty()
            val original = (details.originalTitle ?: details.originalName ?: title).trim()
            if (title.isEmpty() && original.isEmpty()) return@runCatching null
            // Same details call, same cost: the year rides along for the ±1-year candidate gate.
            val year = (details.releaseDate ?: details.firstAirDate)
                ?.takeIf { it.length >= 4 }
                ?.substring(0, 4)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
            MagisTitleInfo(title = title.ifEmpty { original }, originalTitle = original.ifEmpty { title }, year = year)
        }.getOrNull()
    }

    internal companion object {
        /** The plugin's TMDB language (es-MX): the Magis portal is a Spanish catalog. */
        const val TMDB_LANGUAGE = "es-MX"
    }
}
