package com.nuvio.tv.livetv

import com.nuvio.tv.livetv.data.NuvioGuideSources
import com.nuvio.tv.livetv.model.EpgSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared sports guide has to reach every install without the user doing anything,
 * and it must not do it by force: seeding runs once per version, so a guide the owner
 * removes stays removed until the product deliberately ships a new one.
 *
 * Order matters as much as presence — the app's guide matching prefers the earlier
 * source in the list, so a built-in guide must never push a guide the user chose.
 */
class NuvioGuideSourcesTest {

    private val url = "https://guide.example.test/epg.xml"

    private fun user(id: String, url: String) = EpgSource(id = id, name = id, url = url)

    @Test
    fun `a fresh install gets the built-in guide`() {
        val seed = NuvioGuideSources.seed(emptyList(), seededVersion = 0, url = url)!!
        assertEquals(1, seed.sources.size)
        val added = seed.sources.single()
        assertEquals(NuvioGuideSources.SPORTS_ID, added.id)
        assertEquals(url, added.url)
        assertTrue("a seeded guide must be on from the start", added.enabled)
        assertEquals(NuvioGuideSources.SEED_VERSION, seed.version)
    }

    @Test
    fun `the guide the user chose keeps the front of the list`() {
        val mine = user("my-guide", "https://mine.test/tv.xml")
        val seed = NuvioGuideSources.seed(listOf(mine), seededVersion = 0, url = url)!!
        assertEquals(listOf("my-guide", NuvioGuideSources.SPORTS_ID), seed.sources.map { it.id })
    }

    @Test
    fun `an install already seeded writes nothing, even with the guide deleted`() {
        assertNull(NuvioGuideSources.seed(emptyList(), seededVersion = NuvioGuideSources.SEED_VERSION, url = url))
        assertNull(NuvioGuideSources.seed(listOf(user("a", "u")), seededVersion = NuvioGuideSources.SEED_VERSION, url = url))
    }

    @Test
    fun `seeding twice does not list the same guide twice`() {
        val once = NuvioGuideSources.seed(emptyList(), seededVersion = 0, url = url)!!.sources
        val twice = NuvioGuideSources.seed(once, seededVersion = 0, url = url)!!.sources
        assertEquals(1, twice.count { it.id == NuvioGuideSources.SPORTS_ID })
    }

    @Test
    fun `a guide the user added by hand at the same address is not duplicated`() {
        val addedByHand = user("paste-1", url)
        val seed = NuvioGuideSources.seed(listOf(addedByHand), seededVersion = 0, url = url)!!
        assertEquals(1, seed.sources.size)
        assertEquals("paste-1", seed.sources.single().id)
    }

    @Test
    fun `a build with no guide address ships nothing but is still marked seeded`() {
        val seed = NuvioGuideSources.seed(listOf(user("a", "u")), seededVersion = 0, url = "   ")!!
        assertEquals(listOf("a"), seed.sources.map { it.id })
        assertEquals(NuvioGuideSources.SEED_VERSION, seed.version)
    }

    @Test
    fun `the built-in list is empty when the build carries no address`() {
        assertTrue(NuvioGuideSources.builtIn(url = "").isEmpty())
        assertEquals(1, NuvioGuideSources.builtIn(url = url).size)
    }
}
