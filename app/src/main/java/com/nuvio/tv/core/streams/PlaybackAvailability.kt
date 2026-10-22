package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.Video

internal fun Addon.supportsStreamResource(type: String, videoId: String): Boolean =
    supportsResource("stream", type, videoId)

internal fun Addon.supportsResource(name: String, type: String, id: String): Boolean =
    resources.any { resource ->
        resource.name == name &&
            (resource.types.isEmpty() || resource.types.contains(type)) &&
            run {
                val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                    ?: idPrefixes.takeIf { it.isNotEmpty() }
                prefixes == null || prefixes.any { id.startsWith(it) }
            }
    }

internal data class PlaybackAvailability(
    val addons: List<Addon> = emptyList(),
    val scrapers: List<ScraperInfo> = emptyList(),
    val isLoaded: Boolean = false,
    /**
     * True when at least one native VOD source (our in-APK Magis engine) is configured. Native
     * sources are not addons and are not scrapers, so nothing else in this predicate can see
     * them; without it the Play button stays greyed and the stream pipeline is never called.
     */
    private val nativeVodAvailable: Boolean = false,
    private val cachedMeta: (String, String) -> Meta? = { _, _ -> null },
    private val serverStreams: (String, String) -> Boolean = { _, _ -> false }
) {
    fun canStream(
        type: String,
        videoId: String,
        contentId: String = videoId,
        video: Video? = null
    ): Boolean = video?.takeIf { it.id == videoId }?.streams?.isNotEmpty() == true ||
        cachedMeta(type, contentId)?.videos?.any { it.id == videoId && it.streams.isNotEmpty() } == true ||
        addons.any { it.enabled && it.supportsStreamResource(type, videoId) } ||
        scrapers.any { it.enabled && it.supportsType(type) } ||
        // Movies and series only: the native engine is a VOD source, live stays the IPTV path.
        (nativeVodAvailable && (type == "movie" || type == "series")) ||
        serverStreams(type, videoId) ||
        // Live TV fork: your IPTV provider's On Demand may have it (offered as "📡 On Demand").
        ((type == "movie" || type == "series") && com.nuvio.tv.livetv.ondemand.OnDemandStreams.offeredInStreams)
}
