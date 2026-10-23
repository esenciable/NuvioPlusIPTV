package com.nuvio.tv.ext.livetv.magis

/**
 * The narrow ports the native-source adapters consume, declared next to the client that
 * implements them so the magis package never depends on the data package.
 *
 * - [MagisLiveCatalogApi]: the slice the catalog walk needs ([data.MagisChannelLoader]).
 * - [MagisLivePlaybackApi]: the slice playback needs ([data.MagisStreamResolver]).
 *
 * Both are deliberately minimal so tests can fake the portal without a session, a crypto stack or
 * a network. They are PUBLIC (while the implementing client stays internal) because the adapters
 * that consume them are public constructor surfaces — the Hilt graph cannot carry internal types.
 *
 * PORT NOTE: verbatim from the fork's `ext/livetv/magis/MagisLivePorts.kt`.
 */
interface MagisLiveCatalogApi {
    suspend fun categories(): List<MagisLiveCategory>
    suspend fun channels(categoryId: Int, page: Int): List<MagisLiveChannel>
}

interface MagisLivePlaybackApi {
    suspend fun resolveDetailed(channelCode: String): MagisResult<MagisChannelSession>

    /** The playlist URL for a resolved session, as [MagisLiveClient] defines it. */
    fun playlistUrl(cdn: MagisChannelSession): String

    /** Signed headers for one origin request against [cdn], as [MagisLiveClient] defines it. */
    fun signedHeaders(cdn: MagisChannelSession): Map<String, String>
}
