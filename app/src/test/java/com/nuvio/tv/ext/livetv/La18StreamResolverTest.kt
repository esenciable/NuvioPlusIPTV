package com.nuvio.tv.ext.livetv

import com.nuvio.tv.ext.livetv.data.La18StreamResolver
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The la18hd playback resolver: the signed playlist out of the player page, the five-hour token
 * served from a one-hour cache, concurrent mints sharing one fetch, failures never cached, and
 * the channel id travelling in the key so a resolved channel can always be re-marked.
 *
 * The fixture is the real `canales.php` body as served on 2026-10-26
 * (`la18/canales-v1.html`), ESPN 4, token and all.
 */
class La18StreamResolverTest {

    private val page: String
        get() = File("src/test/resources/la18/canales-v1.html").readText()

    private fun channel(
        url: String = "la18:espn4",
        key: String = "ext:la18-afc-20261026#espn4",
        id: String = "la18",
    ) = LiveChannel(
        key = key, sourceId = id, sourceName = "Partidos de Hoy", name = "Partidos de Hoy · ESPN 4",
        tvgId = null, tvgName = null, logo = null, groupId = "la18", group = "Partidos de Hoy",
        number = 0, url = url,
    )

    @Test
    fun `extracts the signed playlist from the real page`() {
        val url = La18StreamResolver.playlistUrlOf(page)
        assertTrue(url!!.startsWith("https://") && ".m3u8?token=" in url)
        assertTrue("fubo18.com" in url)
        assertEquals("espn4", url.substringBefore("/mono.m3u8").substringAfterLast("/"))
    }

    @Test
    fun `an ad script's address is not a playlist`() {
        assertNull(La18StreamResolver.playlistUrlOf("""<script src="https://evil.example/aclib.js"></script>"""))
        assertNull(La18StreamResolver.playlistUrlOf("""{"src":"http://x.fubo18.com/a.m3u8?token=1"}"""))
        assertNull(La18StreamResolver.playlistUrlOf("""{"src":"https://x.fubo18.com/a.mp4?token=1"}"""))
        assertNull(La18StreamResolver.playlistUrlOf("""{"src":"https://x.fubo18.com/a.m3u8"}"""))
    }

    @Test
    fun `resolve mints once and serves the cache inside its window`() = runBlocking {
        val fetches = AtomicInteger(0)
        var at = 0L
        val r = La18StreamResolver(now = { at }, httpGet = { fetches.incrementAndGet(); page })
        val a = r.resolve(channel())!!
        val b = r.resolve(channel())!!
        assertEquals(1, fetches.get())
        assertEquals(a.url, b.url)
        at = La18StreamResolver.CACHE_TTL_MS - 1
        r.resolve(channel())!!
        assertEquals(1, fetches.get())
        // Past the cache window: a fresh mint (the token itself is still valid, but the cache
        // does not know the page's future — a fetch is what it can afford).
        at = La18StreamResolver.CACHE_TTL_MS
        r.resolve(channel())!!
        assertEquals(2, fetches.get())
    }

    @Test
    fun `a failed mint is not cached`() = runBlocking {
        val fetches = AtomicInteger(0)
        var up = false
        val r = La18StreamResolver(now = { 0 }, httpGet = {
            fetches.incrementAndGet(); if (up) page else throw java.io.IOException("403")
        })
        assertNull(r.resolve(channel()))
        assertNull(r.resolve(channel()))
        assertEquals(2, fetches.get())
        up = true
        val ok = r.resolve(channel())!!
        assertTrue(ok.url.startsWith("https://"))
    }

    @Test
    fun `with a relay configured, the resolved url is the worker's and stays stable`() = runBlocking {
        val r = La18StreamResolver(now = { 0 }, httpGet = { throw java.io.IOException("must not mint") },
            workerBase = "https://la18-relay.example.workers.dev", workerKey = "k1")
        val a = r.resolve(channel())!!
        assertEquals("https://la18-relay.example.workers.dev/la18/stream/espn4.m3u8?key=k1", a.url)
        // Re-resolving (reconnect, live-again) returns the same address: the worker owns the mint.
        assertEquals(a.url, r.resolve(channel())!!.url)
    }

    @Test
    fun `reMarker rebuilds the placeholder from the key`() {
        val r = La18StreamResolver()
        val resolved = channel(url = "https://dglvz29s.fubo18.com/espn4/mono.m3u8?token=abc")
        val back = r.reMarker(resolved)
        assertEquals("la18:espn4", back.url)
        assertTrue(r.needsResolution(back))
        // A key that carries no channel id comes back unchanged, never guessed.
        val bare = channel(key = "ext:la18-afc-20261026", url = "https://cdn.example/a.m3u8")
        assertEquals(bare, r.reMarker(bare))
    }

    @Test
    fun `ownership and ids`() {
        assertTrue(La18StreamResolver.idOf(channel()) == "espn4")
        assertNull(La18StreamResolver.idOf(channel(key = "ext:la18-afc", url = "https://cdn.example/a.m3u8")))
        // Another source's channel is never ours, whatever its key looks like.
        assertNull(La18StreamResolver.idOf(channel(id = "addon")))
        val r = La18StreamResolver()
        assertTrue(r.owns(channel()))
        assertTrue(r.needsResolution(channel()))
        assertFalse(r.needsResolution(channel(url = "https://dglvz29s.fubo18.com/espn4/mono.m3u8?token=abc")))
    }

    private fun assertFalse(v: Boolean) = org.junit.Assert.assertFalse(v)
}
