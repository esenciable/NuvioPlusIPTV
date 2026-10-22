package com.nuvio.tv.ext.livetv.domain

/**
 * The identity a NATIVE VOD source wears inside the fork's stream pipeline: a stream source that
 * is not an addon and not a scraper plugin, resolving on-device the same way the native Live TV
 * sources resolve ([NativeLiveSource]) — one seam per pipeline, same contract.
 *
 * The stream search emits one `AddonStreams` group per source, named after [name], so the group
 * rides the exact presentation path the addon groups use (order, filters, badges) without the
 * source ever entering the scraper registry.
 */
interface NativeVodSource {

    /** The display name the source carries as its `AddonStreams` group name. */
    val name: String

    /**
     * Whether this build carries a usable configuration for the source. `false` means the source
     * is ABSENT: it is skipped entirely — never a failed resolve, never a crash (the same
     * disabled-config contract the live gateway has).
     */
    val isConfigured: Boolean

    /**
     * Resolves [videoId] (a numeric TMDB id, `tmdb:`-prefixed, or an IMDb `tt…` id) into the
     * source's own streams — direct URLs plus the headers their CDN demands. [season]/[episode]
     * only matter for series. Empty (never an exception) when the source has nothing for the id;
     * a failure inside the source surfaces as an empty list plus the source's own logging, so
     * one dead source can never break the stream screen.
     */
    suspend fun resolve(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<NativeVodStream>
}

/** One native VOD stream: a direct CDN URL plus the request headers it needs on every request. */
data class NativeVodStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)
