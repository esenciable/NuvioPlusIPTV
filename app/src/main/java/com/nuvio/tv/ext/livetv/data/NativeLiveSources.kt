package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.ext.livetv.domain.NativeLiveCatalogLoader
import com.nuvio.tv.ext.livetv.domain.NativeLiveResolver
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The live TV mirror of [NativeVodSources]: the native sources are injected as ONE dependency the
 * pipeline iterates without naming any of them. Two consumers come through it, and neither asks
 * what protocol stands behind the other:
 *
 *  - [loaders] feed [com.nuvio.tv.livetv.data.LiveTvRepository]'s channel merge (catalog side);
 *  - [resolvers] answer playback's "this URL is a placeholder, give me a real one"
 *    ([com.nuvio.tv.livetv.player.LiveTvPlaybackController]).
 *
 * A build without a usable MAGIS_* configuration carries no configured source: the merge adds
 * nothing, no resolver ever owns a channel, and Live TV behaves exactly like a build without the
 * feature — never a crash, never a failed load dressed up as one.
 */
class NativeLiveSources(
    private val entries: List<NativeLiveEntry>,
) {

    /** One native source's two halves: the catalog walk and the playback resolver. */
    data class NativeLiveEntry(
        val loader: NativeLiveCatalogLoader,
        val resolver: NativeLiveResolver,
        /**
         * Whether the loader's catalog counts toward "this build has native sources". A source
         * that exists only to resolve playback for rows built elsewhere (la18hd's Sports-tab
         * channels) contributes no catalog and must not make the UI claim a source is there.
         */
        val contributesCatalog: Boolean = true,
    )

    /** The sources with a usable configuration; an unconfigured source is ABSENT, never failed. */
    private val configured: List<NativeLiveEntry> get() = entries.filter { it.loader.isConfigured }

    /** Whether this build has any native live source AT THE CATALOG: one that lists channels. */
    val isEmpty: Boolean get() = configured.none { it.contributesCatalog }

    /** Whether a native source is configured, before its first catalog walk has landed. */
    val hasSources: Boolean get() = configured.isNotEmpty()

    /** The resolver that owns [channel], or null for a playlist channel or an unknown source. */
    fun resolverFor(channel: LiveChannel): NativeLiveResolver? =
        configured.firstOrNull { it.resolver.owns(channel) }?.resolver

    /** Every configured source's channels, each source isolated: a dead one adds nothing. */
    suspend fun loadAll(): List<LiveChannel> {
        val out = mutableListOf<LiveChannel>()
        for (entry in configured) {
            val channels = try {
                entry.loader.loadChannels()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                continue
            }
            out += channels
        }
        return out
    }
}

/**
 * The catalog side of the native live sources, held as app state so the Live TV repository can
 * merge it into the channel list without loading the portal on every rebuild.
 *
 * The walk is several portal calls (one per category page), so the result is cached and refreshed
 * on the portal's own catalog pace rather than on every channel-list rebuild. [refresh] is
 * serialized: two screens opening at once produce ONE portal walk, not two.
 */
@Singleton
class NativeLiveCatalog @Inject constructor(
    private val sources: NativeLiveSources,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var loadedAtMs = 0L

    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val channels: StateFlow<List<LiveChannel>> = _channels.asStateFlow()

    /** Whether this build has a native live source to walk at all. */
    val isEmpty: Boolean get() = sources.isEmpty

    /** The native resolver that owns [channel], or null for a playlist channel. */
    fun resolverFor(channel: LiveChannel): NativeLiveResolver? = sources.resolverFor(channel)

    /** A cached answer is worth the wait for at most this long; the portal's TTL is shorter. */
    private val ttlMs: Long get() = REFRESH_INTERVAL_MS

    /** Loads now when there is nothing loaded; a load already running is not started twice. */
    fun ensureLoaded(): Job = scope.launch { refresh(force = false) }

    suspend fun refresh(force: Boolean) {
        if (sources.isEmpty) {
            // Worth one line in the Start-Up Report: "Live TV shows no Magis channels" has two
            // very different causes (no configuration vs. a portal that answered nothing), and
            // without this the only way to tell them apart is reading the build's secrets.
            android.util.Log.i(TAG_NATIVE, "no configured native source in this build")
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Native live: no configured source (no MAGIS_* in this build)")
            return
        }
        if (!force && _channels.value.isNotEmpty() &&
            System.currentTimeMillis() - loadedAtMs < ttlMs
        ) return
        lock.withLock {
            if (!force && _channels.value.isNotEmpty() &&
                System.currentTimeMillis() - loadedAtMs < ttlMs
            ) return
            val t0 = System.currentTimeMillis()
            val loaded = sources.loadAll()
            // A failed walk keeps yesterday's channels rather than emptying the tab: the portal
            // being briefly down is not a reason to show "no channels".
            if (loaded.isNotEmpty()) {
                _channels.value = loaded
                loadedAtMs = System.currentTimeMillis()
            }
            android.util.Log.i(TAG_NATIVE, "loaded=${loaded.size} in ${System.currentTimeMillis() - t0} ms; kept=${_channels.value.size}")
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "Native live: ${loaded.size} channels in ${System.currentTimeMillis() - t0} ms" +
                    if (loaded.isEmpty()) " (kept ${_channels.value.size} from the last good walk)" else ""
            )
        }
    }

    companion object {
        /** The portal's own catalog pace; refreshing sooner is a call the portal will pace anyway. */
        const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L

        /** Logcat tag for the native catalog walk: the one thing a field report cannot carry. */
        private const val TAG_NATIVE = "NuvioNativeLive"
    }
}
