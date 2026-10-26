package com.nuvio.tv.ext.livetv

import com.nuvio.tv.ext.livetv.data.NativeLiveSources
import com.nuvio.tv.ext.livetv.domain.NativeLiveCatalogLoader
import com.nuvio.tv.ext.livetv.domain.NativeLiveResolver
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The aggregation the Live TV tab and the player consult: which source owns which channel, and what
 * a source that is unconfigured or dying does to the rest. The disabled-config contract is the whole
 * point of this file — a build without Magis, or a portal that fails mid-walk, must look like
 * "nothing extra", never like a crash or an empty channel list.
 */
class NativeLiveSourcesTest {

    private class Loader(
        override val sourceId: String,
        override val isConfigured: Boolean = true,
        private val channels: List<LiveChannel> = emptyList(),
        private val boom: Boolean = false,
    ) : NativeLiveCatalogLoader {
        var loads = 0
        override suspend fun loadChannels(): List<LiveChannel> {
            loads++
            if (boom) throw IllegalStateException("portal down")
            return channels
        }
    }

    private class Resolver(
        override val sourceId: String,
        override val markerScheme: String = "$sourceId://",
    ) : NativeLiveResolver {
        var resolves = 0
        override suspend fun resolve(channel: LiveChannel): LiveChannel? {
            resolves++
            return channel.copy(url = "https://cdn.example/live.m3u8")
        }

        /** Back to the placeholder, the way the player's "live again" path expects. */
        override fun reMarker(channel: LiveChannel): LiveChannel =
            channel.copy(url = "$markerScheme${channel.key.removePrefix("$sourceId:")}", headers = emptyMap())
    }

    private fun channel(source: String, url: String = "$source://x") = LiveChannel(
        key = "$source:1",
        sourceId = source,
        sourceName = source,
        name = "Canal",
        tvgId = null,
        tvgName = null,
        logo = null,
        groupId = "$source:g",
        group = "Group",
        number = 1,
        url = url,
    )

    @Test(timeout = 10_000)
    fun `no configured source means no channels and no owner`() = runTest {
        val sources = NativeLiveSources(
            listOf(NativeLiveSources.NativeLiveEntry(Loader("a", isConfigured = false), Resolver("a"))),
        )
        assertTrue(sources.isEmpty)
        assertEquals(emptyList<LiveChannel>(), sources.loadAll())
        assertNull(sources.resolverFor(channel("a")))
    }

    @Test(timeout = 10_000)
    fun `an entry without catalog does not claim a source but still resolves`() = runTest {
        val r = Resolver("la18")
        val sources = NativeLiveSources(
            listOf(
                NativeLiveSources.NativeLiveEntry(Loader("la18"), r, contributesCatalog = false),
            ),
        )
        // The UI's "this build has native sources" stays about catalog sources; the resolver
        // still owns the rows the Sports tab builds for it.
        assertTrue(sources.isEmpty)
        assertEquals(emptyList<LiveChannel>(), sources.loadAll())
        assertTrue(sources.resolverFor(channel("la18")) === r)
    }

    @Test(timeout = 10_000)
    fun `the resolver of the owning source answers, and a playlist channel has none`() {
        val a = Resolver("a")
        val sources = NativeLiveSources(listOf(NativeLiveSources.NativeLiveEntry(Loader("a"), a)))

        assertTrue(sources.resolverFor(channel("a")) === a)
        assertNull(sources.resolverFor(channel("playlist", "https://provider/x.m3u8")))
    }

    @Test(timeout = 10_000)
    fun `needsResolution only fires on the placeholder URL`() {
        val sources = NativeLiveSources(listOf(NativeLiveSources.NativeLiveEntry(Loader("a"), Resolver("a"))))
        val resolver = sources.resolverFor(channel("a"))!!

        assertTrue(resolver.needsResolution(channel("a")))
        // A resolved channel is never resolved again — that is what stops an infinite re-sign loop.
        assertFalse(resolver.needsResolution(channel("a", "https://cdn.example/live.m3u8")))
    }

    @Test(timeout = 10_000)
    fun `reMarker puts the placeholder back so live-again re-signs`() {
        val resolver = Resolver("a")
        val played = channel("a", "https://cdn.example/live.m3u8")

        val remarked = resolver.reMarker(played)
        assertTrue(resolver.needsResolution(remarked))
        // Identity survives: same key, same group, only the URL and the expired headers move.
        assertEquals(played.key, remarked.key)
        assertEquals(played.groupId, remarked.groupId)
        assertTrue(remarked.headers.isEmpty())
    }

    @Test(timeout = 10_000)
    fun `a source that dies in the walk leaves the others standing`() = runTest {
        val good = Loader("b", channels = listOf(channel("b")))
        val dead = Loader("a", boom = true)
        val sources = NativeLiveSources(
            listOf(
                NativeLiveSources.NativeLiveEntry(dead, Resolver("a")),
                NativeLiveSources.NativeLiveEntry(good, Resolver("b")),
            ),
        )

        assertEquals(listOf("b:1"), sources.loadAll().map { it.key })
        assertEquals(1, good.loads)
    }
}
