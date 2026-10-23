package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.ext.livetv.domain.NativeLiveResolver
import com.nuvio.tv.ext.livetv.magis.MagisChannelSession
import com.nuvio.tv.ext.livetv.magis.MagisLiveClient
import com.nuvio.tv.ext.livetv.magis.MagisLivePlaybackApi
import com.nuvio.tv.ext.livetv.magis.MagisLiveSource
import com.nuvio.tv.ext.livetv.magis.MagisResult
import com.nuvio.tv.livetv.model.LiveChannel

/**
 * The playback side of the native Magis source: it turns a channel's `magis-live://<code>` marker
 * into the playlist URL and the signed `Content-Auth`/`Content-License` headers the CDN demands,
 * and hands back a copy of the channel the base's player can open unchanged.
 *
 * ### Why resolution happens here and not in the catalog
 *
 * A live signal is only servable for a short window: the portal rotates `main_addr` on every
 * answer (an old host 403s) and the signature carries the moment it was minted. Any URL stored in
 * the channel list would be stale before the screen finished drawing, so the list keeps the marker
 * and the headers are minted seconds before playback — the fork's live path makes the same cut.
 *
 * ### Stale signatures are a retry, not a silent failure
 *
 * The headers are signed at RESOLVE time and the base's player freezes them into the DataSource
 * when the MediaSource is built. When one expires mid-session, the CDN answers 401/403; the
 * controller's reconnect path calls [resolve] again instead of replaying the old pair, and that
 * lands a fresh session, a fresh host and a fresh signature. A resolve that fails surfaces as an
 * error the retry can clear — never as a guess at a URL.
 *
 * ### One CDN, the first in portal order
 *
 * [MagisLiveClient] returns every servable cfl CDN; the base's [LiveChannel] carries ONE url and
 * one header set, so the first is used (portal order is the provider's own preference order). The
 * rest stay available through [resolveCdn] for a source picker built on top of this.
 */
class MagisStreamResolver(
    /** The portal API, or null on a build without Magis configuration: resolves nothing then. */
    private val client: MagisLivePlaybackApi?,
) : NativeLiveResolver {

    override val sourceId: String get() = MagisLiveSource.SOURCE_ID
    override val markerScheme: String get() = MagisLiveSource.MARKER_SCHEME

    /**
     * Back to the placeholder: the playCode travels in the channel key, so a resolved channel can
     * always be re-marked, and re-marking is what makes the next play resolve a fresh signature.
     */
    override fun reMarker(channel: LiveChannel): LiveChannel {
        val code = MagisLiveSource.codeOf(channel.key) ?: return channel
        return channel.copy(url = MagisLiveSource.MARKER_SCHEME + code, headers = emptyMap())
    }

    override suspend fun resolve(channel: LiveChannel): LiveChannel? {
        val api = client ?: return null
        val code = MagisLiveSource.codeOf(channel.key) ?: return null
        val session = resolveSession(code) ?: return null
        val cdn = session.cdns.firstOrNull() ?: return null
        val forCdn = session.forCdn(cdn)
        return channel.copy(url = api.playlistUrl(forCdn), headers = api.signedHeaders(forCdn))
    }

    /**
     * The resolved session's [index]-th CDN as a playable channel, for a source picker that wants
     * the provider's fallbacks instead of only its first choice. Null when the index is not there
     * or the portal failed.
     */
    suspend fun resolveCdn(channel: LiveChannel, index: Int): LiveChannel? {
        val api = client ?: return null
        val code = MagisLiveSource.codeOf(channel.key) ?: return null
        val session = resolveSession(code) ?: return null
        val cdn = session.cdns.getOrNull(index) ?: return null
        val forCdn = session.forCdn(cdn)
        return channel.copy(url = api.playlistUrl(forCdn), headers = api.signedHeaders(forCdn))
    }

    /** How many CDNs the portal served for [channel] right now (0 when it served nothing). */
    suspend fun cdnCount(channel: LiveChannel): Int =
        MagisLiveSource.codeOf(channel.key)?.let { resolveSession(it) }?.cdns?.size ?: 0

    private suspend fun resolveSession(code: String): MagisChannelSession? {
        val api = client ?: return null
        val result = runCatching { api.resolveDetailed(code) }.getOrNull()
        return (result as? MagisResult.Ok)?.data
    }
}

/**
 * The same session aimed at one CDN: the playCode and the license never change (they are one
 * signal's pair), only the host and the `authBase` its `Content-Auth` is built from.
 */
private fun MagisChannelSession.forCdn(cdn: com.nuvio.tv.ext.livetv.magis.MagisChannelCdn): MagisChannelSession =
    copy(host = cdn.host, authBase = cdn.authBase)
