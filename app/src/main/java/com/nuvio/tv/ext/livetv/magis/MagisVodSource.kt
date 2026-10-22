package com.nuvio.tv.ext.livetv.magis

import com.nuvio.tv.ext.livetv.domain.NativeVodSource
import com.nuvio.tv.ext.livetv.domain.NativeVodStream

/**
 * The Magis VOD client as ONE entry of the [com.nuvio.tv.ext.livetv.data.NativeVodSources] list
 * injected into the stream pipeline — the VOD mirror of [MagisLiveSource].
 *
 * A build without a usable Magis configuration gets `client = null`: [isConfigured] is then
 * false and the source is skipped before any portal call — the same disabled-config contract
 * the live gateway keeps ("source absent, never a crash"). The client reads the runtime config
 * PER USE, so host/key rotations land without a restart while the process lives.
 */
internal class MagisVodSource(
    override val name: String = DEFAULT_SOURCE_NAME,
    private val client: MagisVodClient?,
    /** Failure sink, one line per failed resolution attempt (tests capture it; prod logs). */
    private val logFailure: (String) -> Unit = { android.util.Log.w(LOG_TAG, it) },
) : NativeVodSource {

    override val isConfigured: Boolean
        get() = client != null

    override suspend fun resolve(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<NativeVodStream> {
        val vodClient = client ?: return emptyList()
        // resolveDetailed() hands back the TYPED failure; the source logs it once per attempt
        // (kind + portal code when present) and answers empty — a failed Magis resolve never
        // throws and never vanishes into a silent empty result.
        return when (val result = vodClient.resolveDetailed(
            id = videoId,
            mediaType = type,
            season = season ?: 0,
            episode = episode ?: 0,
        )) {
            is MagisResult.Ok -> listOf(NativeVodStream(url = result.data.url, headers = result.data.headers))
            is MagisResult.PortalError -> {
                logFailure("portal ${result.code}${result.msg?.let { ": $it" } ?: ""} [videoId=$videoId]")
                emptyList()
            }
            is MagisResult.RedError -> {
                logFailure("connection error: ${result.cause.message ?: result.cause.javaClass.simpleName} [videoId=$videoId]")
                emptyList()
            }
        }
    }

    internal companion object {
        /** The group name the native Magis VOD streams wear on the stream screen. */
        const val DEFAULT_SOURCE_NAME = "Magis VOD"

        /** The log tag for failed resolution attempts (android.util.Log.w). */
        const val LOG_TAG = "MagisVod"
    }
}
