package com.nuvio.tv.ext.livetv.magis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The SLB cache's addon semantics (`src/magis/resolve.ts` `sessionSlb`): one slot keyed by the
 * session's token owner, reusable while `now < expiresAt` with
 * `expiresAt = now + invalidTime*1000 - 300_000`.
 */
class MagisVodSlbCacheTest {

    private fun slb(invalidTime: Long = 14400): JSONObject =
        if (invalidTime > 0) JSONObject().put("invalidTime", invalidTime) else JSONObject()

    /** 1h of fake clock starting at t=1_000_000; the cache reads it per call. */
    private class Clock(var now: Long = 1_000_000L)

    @Test
    fun `hits within the TTL`() {
        val clock = Clock()
        val cache = MagisVodSlbCache(nowMs = { clock.now })
        cache.put("tok", slb(14400))
        // expiresAt = 1_000_000 + 14_400_000 - 300_000 = 15_100_000.
        clock.now = 15_099_999L
        assertNotNull("viva dentro del TTL", cache.get("tok"))
    }

    @Test
    fun `misses exactly at expiresAt (now is not strictly before it)`() {
        val clock = Clock()
        val cache = MagisVodSlbCache(nowMs = { clock.now })
        cache.put("tok", slb(14400))
        clock.now = 1_000_000L + 14400 * 1000 - 300_000
        assertNull("caducada en la frontera", cache.get("tok"))
    }

    @Test
    fun `misses for a different token owner`() {
        val clock = Clock()
        val cache = MagisVodSlbCache(nowMs = { clock.now })
        cache.put("tok", slb(14400))
        clock.now += 1000
        assertNull("otro dueño no reusa", cache.get("other"))
        assertNotNull("el dueño original sí", cache.get("tok"))
    }

    @Test
    fun `falls back to 300s when the answer has no invalidTime`() {
        val clock = Clock()
        val cache = MagisVodSlbCache(nowMs = { clock.now })
        cache.put("tok", slb(invalidTime = 0))
        // expiresAt = now + 300*1000 - 300_000 = now → usable only strictly before `now`.
        clock.now -= 1
        assertNotNull(cache.get("tok"))
        clock.now += 1
        assertNull(cache.get("tok"))
    }

    @Test
    fun `a new put replaces the previous owner and answer`() {
        val clock = Clock()
        val cache = MagisVodSlbCache(nowMs = { clock.now })
        cache.put("tok1", slb(14400).put("marker", "old"))
        cache.put("tok2", slb(14400).put("marker", "new"))
        clock.now += 1000
        assertNull(cache.get("tok1"))
        assertEquals("new", cache.get("tok2")?.optString("marker"))
    }
}
