package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeping the feeds a viewer paid for across the ten-second poll.
 *
 * `/events.json` carries `sources: []` for live matches (listing does not probe), so replacing the
 * candidate list with every poll erased what the open screen had just asked for — and the ask gate
 * then refused to ask again for five minutes: the same match showed its full shelf once and a
 * single row the next time.
 */
class SportsSourceMergeTest {

    private fun event(id: String, sources: List<AddonSource> = emptyList()) = AddonEvent(
        id = id,
        kickoffMs = 1_761_148_800_000L,
        teams = listOf(AddonTeam(1, "A", ""), AddonTeam(2, "B", "")),
        streamUrl = "https://addon.test/e/$id.m3u8",
        title = "A vs B",
        live = true,
        status = "live",
        sources = sources,
    )

    private fun src(label: String) = AddonSource(streamId = label.hashCode().toLong(), label = label, verified = true, url = "https://addon.test/s/$label.m3u8")

    @Test
    fun `a poll without sources keeps the ones we asked for`() {
        val learned = mapOf("rb_1_1" to listOf(src("1X EN"), src("TyR")))
        val at = mapOf("rb_1_1" to 1_000L)
        val out = SportsAddonChannels.mergeLearned(
            listOf(event("rb_1_1"), event("rb_2_1")), learned, at, now = 2_000L,
        )
        assertEquals(listOf("1X EN", "TyR"), out[0].sources.map { it.label })
        // A match we never asked about stays as the poll said: empty, not invented.
        assertTrue(out[1].sources.isEmpty())
    }

    @Test
    fun `a real answer from the feed replaces what we learned`() {
        val learned = mapOf("rb_1_1" to listOf(src("1X EN")))
        val at = mapOf("rb_1_1" to 1_000L)
        val fresh = listOf(event("rb_1_1", listOf(src("Fubo US"))))
        val out = SportsAddonChannels.mergeLearned(fresh, learned, at, now = 2_000L)
        assertEquals(listOf("Fubo US"), out[0].sources.map { it.label })
    }

    @Test
    fun `a learned shelf older than the window is asked for again`() {
        val learned = mapOf("rb_1_1" to listOf(src("1X EN")))
        val at = mapOf("rb_1_1" to 1_000L)
        val out = SportsAddonChannels.mergeLearned(
            listOf(event("rb_1_1")), learned, at,
            now = 1_000L + SportsAddonChannels.LEARNED_TTL_MS + 1,
        )
        assertTrue(out[0].sources.isEmpty())
    }

    @Test
    fun `outside the window the next open is allowed to ask`() {
        val learned = mapOf("rb_1_1" to listOf(src("1X EN")))
        val at = mapOf("rb_1_1" to 1_000L)
        val out = SportsAddonChannels.mergeLearned(
            listOf(event("rb_1_1")), learned, at, now = 1_000L + 60_000L,
        )
        assertEquals(1, out[0].sources.size)
    }
}
