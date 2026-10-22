package com.nuvio.tv.ext.livetv.magis

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

/**
 * The TMDB title lookup behind the Magis VOD flow: it must ask TMDB in SPANISH (`es-MX`, the
 * plugin's language) so the localized title matches what the Magis portal actually lists — "Dune"
 * in English missed the portal's "Duna" and let a mockbuster through (2026-10-09, option 4). The
 * Spanish answer still carries `original_title`/`original_name`, so the original-title fallback
 * keeps working, and the year rides the same call for the ±1-year gate.
 */
class MagisVodTitleLookupTest {

    private fun details(
        title: String? = null,
        name: String? = null,
        originalTitle: String? = null,
        originalName: String? = null,
        releaseDate: String? = null,
        firstAirDate: String? = null,
    ) = TmdbDetailsResponse(
        id = 1,
        title = title,
        name = name,
        originalTitle = originalTitle,
        originalName = originalName,
        releaseDate = releaseDate,
        firstAirDate = firstAirDate,
    )

    private fun api(): TmdbApi = mockk()

    @Test
    fun `movie lookup asks TMDB in es-MX and returns the localized plus original pair`() = runTest {
        val tmdbApi = api()
        val languages = mutableListOf<String?>()
        coEvery { tmdbApi.getMovieDetails(any(), any(), any()) } answers {
            languages.add(thirdArg())
            Response.success(details(title = "Duna", originalTitle = "Dune", releaseDate = "2021-10-22"))
        }

        val info = TmdbTitleLookup(TmdbService(tmdbApi), tmdbApi).lookup("1160419", isSeries = false)

        assertEquals(listOf("es-MX"), languages)
        assertEquals(MagisTitleInfo(title = "Duna", originalTitle = "Dune", year = 2021), info)
    }

    @Test
    fun `tv lookup asks TMDB in es-MX and uses the name fields`() = runTest {
        val tmdbApi = api()
        val languages = mutableListOf<String?>()
        coEvery { tmdbApi.getTvDetails(any(), any(), any()) } answers {
            languages.add(thirdArg())
            Response.success(details(name = "Breaking Mal", originalName = "Breaking Bad", firstAirDate = "2008-01-20"))
        }

        val info = TmdbTitleLookup(TmdbService(tmdbApi), tmdbApi).lookup("1396", isSeries = true)

        assertEquals(listOf("es-MX"), languages)
        assertEquals(MagisTitleInfo(title = "Breaking Mal", originalTitle = "Breaking Bad", year = 2008), info)
    }

    @Test
    fun `a details answer without usable titles yields null`() = runTest {
        val tmdbApi = api()
        coEvery { tmdbApi.getMovieDetails(any(), any(), any()) } returns
            Response.success(details())

        assertNull(TmdbTitleLookup(TmdbService(tmdbApi), tmdbApi).lookup("1", isSeries = false))
    }
}
