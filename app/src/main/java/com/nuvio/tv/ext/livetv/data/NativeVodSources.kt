package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.ext.livetv.domain.NativeVodSource
import com.nuvio.tv.ext.livetv.domain.NativeVodStream

/**
 * What one native VOD source resolved: its group name (the `AddonStreams.addonName` it will wear)
 * plus the streams in the source's own model.
 */
data class NativeVodStreams(
    val sourceName: String,
    val streams: List<NativeVodStream>,
)

/**
 * The list of native VOD sources, injected as ONE dependency the stream pipeline iterates without
 * naming any of them — the stream-path mirror of [NativeLiveSources].
 *
 * The disabled-config contract is carried here: [configuredSources] drops every unconfigured
 * source entirely (absent, never a failed resolve), and [resolveConfigured] isolates each
 * source's failure to that source — a dead source contributes nothing rather than sinking the
 * others or the scrapers running beside them.
 */
class NativeVodSources(private val entries: List<NativeVodSource>) {

    /** Every source's display name, in injection order (the order their groups are emitted in). */
    val names: List<String> get() = entries.map { it.name }

    /** The sources with a usable configuration; an unconfigured source is ABSENT, never failed. */
    fun configuredSources(): List<NativeVodSource> = entries.filter { it.isConfigured }

    /**
     * Resolves every CONFIGURED source. Each source runs behind its own catch: a failure is
     * logged and skipped, the remaining sources still resolve, and the caller's stream screen
     * never learns that anything died.
     */
    suspend fun resolveConfigured(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        onError: (NativeVodSource, Throwable) -> Unit = { _, _ -> },
    ): List<NativeVodStreams> {
        val resolved = mutableListOf<NativeVodStreams>()
        for (source in configuredSources()) {
            val streams = try {
                source.resolve(type, videoId, season, episode)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(source, e)
                continue
            }
            if (streams.isNotEmpty()) {
                resolved += NativeVodStreams(sourceName = source.name, streams = streams)
            }
        }
        return resolved
    }
}
