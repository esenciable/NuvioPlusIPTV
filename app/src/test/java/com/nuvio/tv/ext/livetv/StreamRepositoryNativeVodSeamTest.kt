package com.nuvio.tv.ext.livetv

import android.content.Context
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.LocalDebridAvailabilityService
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.mediaserver.ServerStreams
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.data.repository.StreamRepositoryImpl
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ext.livetv.data.NativeVodSources
import com.nuvio.tv.ext.livetv.domain.NativeVodSource
import com.nuvio.tv.ext.livetv.domain.NativeVodStream
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The native VOD seam in the stream repository: the base's own pipeline is the thing under test
 * here (it changed), so the cases are the two ways a wrong job count fails SILENTLY —
 *
 *  - under-counting: [StreamRepositoryImpl] closes the result channel before every native source
 *    reported, so a native group silently disappears;
 *  - over-counting: the channel never closes and the streams screen waits forever.
 *
 * Plus the presentation contract: each source's streams ride the addon group path with the
 * source's name as the group name and the CDN headers on `proxyHeaders.request`.
 */
class StreamRepositoryNativeVodSeamTest {

    private class FakeNativeSource(
        override val name: String,
        override val isConfigured: Boolean = true,
        private val fail: Boolean = false,
        private val delayMs: Long = 0,
    ) : NativeVodSource {
        var resolveCalls = 0
        override suspend fun resolve(
            type: String,
            videoId: String,
            season: Int?,
            episode: Int?,
        ): List<NativeVodStream> {
            resolveCalls++
            if (fail) throw IllegalStateException("portal exploded")
            if (delayMs > 0) delay(delayMs)
            return listOf(
                NativeVodStream(
                    url = "https://cdn.magis.test/vod/${name.replace(' ', '_')}_media.ts",
                    headers = mapOf("Content-Auth" to "auth-token"),
                ),
            )
        }
    }

    @Test
    fun `native streams arrive as their own group with the CDN headers`() = runBlocking {
        val harness = newHarness(listOf(FakeNativeSource("Magis VOD")))

        val success = withTimeout(2_000) {
            harness.getStreamsFromAllAddons("movie", "tt1341338", null, null)
                .first { it is NetworkResult.Success && it.data.any { group -> group.addonName == "Magis VOD" } }
        } as NetworkResult.Success

        val group = success.data.single { it.addonName == "Magis VOD" }
        val stream = group.streams.single()
        assertEquals("Magis VOD", stream.name)
        assertEquals("https://cdn.magis.test/vod/Magis_VOD_media.ts", stream.url)
        // The row reads like the plugin's own answer, so quality-aware ranking treats it the same.
        assertEquals("Auto", stream.quality)
        assertEquals("auth-token", stream.behaviorHints?.proxyHeaders?.request?.get("Content-Auth"))
    }

    @Test
    fun `every configured native source's group arrives`() = runBlocking {
        // Two sources, the second one slow: an under-counted job count closes the channel after
        // the first source reports and the slow group is silently lost.
        val harness = newHarness(
            listOf(
                FakeNativeSource("Magis VOD"),
                FakeNativeSource("Slow VOD", delayMs = 150),
            ),
        )

        val expected = setOf("Fast Addon", "Magis VOD", "Slow VOD")
        val success = withTimeout(2_000) {
            harness.getStreamsFromAllAddons("movie", "tt1341338", null, null)
                .first { result ->
                    result is NetworkResult.Success &&
                        result.data.map { group -> group.addonName }.toSet().containsAll(expected)
                }
        } as NetworkResult.Success

        assertEquals(expected, success.data.map { it.addonName }.toSet())
    }

    @Test
    fun `an unconfigured native source is never resolved and counts no job`() = runBlocking {
        val absent = FakeNativeSource("Absent VOD", isConfigured = false)
        val harness = newHarness(listOf(absent))

        // An over-counted job count (the absent source still counted, no job launched for it)
        // hangs here until the timeout: the channel would never close.
        val success = withTimeout(2_000) {
            harness.getStreamsFromAllAddons("movie", "tt1341338", null, null)
                .first { it is NetworkResult.Success }
        } as NetworkResult.Success

        assertEquals(listOf("Fast Addon"), success.data.map { it.addonName })
        assertEquals(0, absent.resolveCalls)
    }

    @Test
    fun `the search finishes when no native source is configured`() = runBlocking {
        val absent = FakeNativeSource("Absent VOD", isConfigured = false)
        val harness = newHarness(listOf(absent))

        // Collect to COMPLETION: a job counted but never launched (an over-counted totalJobs)
        // leaves the result channel open and this call never returns — the streams screen waits
        // forever. The job count must equal the jobs actually launched, in both directions.
        val emissions = withTimeout(2_000) {
            harness.getStreamsFromAllAddons("movie", "tt1341338", null, null).toList()
        }

        assertTrue(
            emissions.any { result ->
                result is NetworkResult.Success && result.data.any { group -> group.addonName == "Fast Addon" }
            },
        )
        assertEquals(0, absent.resolveCalls)
    }

    @Test
    fun `a failing native source does not drop the addon or its sibling`() = runBlocking {
        val harness = newHarness(
            listOf(
                FakeNativeSource("Dying VOD", fail = true),
                FakeNativeSource("Magis VOD"),
            ),
        )

        val success = withTimeout(2_000) {
            harness.getStreamsFromAllAddons("movie", "tt1341338", null, null)
                .first { result ->
                    result is NetworkResult.Success &&
                        result.data.map { group -> group.addonName }
                            .containsAll(listOf("Fast Addon", "Magis VOD"))
                }
        } as NetworkResult.Success

        val names = success.data.map { it.addonName }.toSet()
        assertTrue("addon group expected, got $names", "Fast Addon" in names)
        assertTrue("healthy native group expected, got $names", "Magis VOD" in names)
        assertTrue("the dying source contributes nothing, got $names", "Dying VOD" !in names)
    }

    private fun newHarness(nativeSources: List<NativeVodSource>): StreamRepositoryImpl {
        val addon = Addon(
            id = "fast-addon",
            name = "Fast Addon",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://addon.example",
            catalogs = emptyList(),
            types = emptyList(),
            resources = listOf(
                AddonResource(name = "stream", types = listOf("movie"), idPrefixes = listOf("tt")),
            ),
        )
        val api = mockk<AddonApi>()
        coEvery { api.getStreams(any()) } returns Response.success(
            StreamResponseDto(
                streams = listOf(StreamDto(name = "Fast Stream", url = "https://stream.example/video.m3u8")),
            ),
        )

        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(addon))

        // Plugins off: the scraper path is upstream behaviour this test does not exercise.
        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.enabledScrapers } returns flowOf(emptyList())
        every { pluginManager.pluginsEnabled } returns flowOf(false)
        every { pluginManager.groupStreamsByRepository } returns flowOf(false)
        every { pluginManager.repositories } returns flowOf(emptyList())

        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)

        val debridSettingsDataStore = mockk<DebridSettingsDataStore>()
        every { debridSettingsDataStore.settings } returns flowOf(DebridSettings())

        val presentation = mockk<DebridStreamPresentation>()
        every { presentation.apply(any(), any<DebridSettings>(), any(), any()) } answers {
            firstArg<List<AddonStreams>>()
        }

        val availability = mockk<LocalDebridAvailabilityService>()
        coEvery { availability.markChecking(any()) } coAnswers { firstArg<List<AddonStreams>>() }
        coEvery { availability.annotateCachedAvailability(any()) } coAnswers { firstArg<List<AddonStreams>>() }

        return StreamRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            api = api,
            addonRepository = addonRepository,
            pluginManager = pluginManager,
            profileManager = profileManager,
            debridSettingsDataStore = debridSettingsDataStore,
            tmdbService = mockk<TmdbService>(relaxed = true),
            debridStreamPresentation = presentation,
            localDebridAvailabilityService = availability,
            // The mediaserver stream source; unused by these native-source tests.
            serverStreams = mockk<ServerStreams>(relaxed = true),
            nativeVodSources = NativeVodSources(nativeSources),
        )
    }
}
