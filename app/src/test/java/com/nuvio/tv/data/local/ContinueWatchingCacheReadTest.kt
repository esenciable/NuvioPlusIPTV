package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The continue-watching cache file as the rest of the app receives it.
 *
 * The file on a real box came back with `"contentId": null` in it, Gson built the Kotlin data class
 * anyway (Gson does not honor Kotlin nullability), and the first caller with a non-null parameter
 * — the home screen's `isDroppedShow(contentId: String)`, which runs the moment a profile is
 * chosen — threw a NullPointerException the crash catcher reported as "the app closed".
 *
 * Measured on the Mi Box, build 1.1.0-beta.8.1:
 * `ROOT CAUSE: java.lang.NullPointerException: Parameter specified as non-null is null: method
 * ...isDroppedShow, parameter contentId` inside `HomeViewModel.loadContinueWatching`, on
 * Dispatchers.IO, right after the profile picker.
 */
class ContinueWatchingCacheReadTest {

    /** The model's type as the cache reads it. */
    private val inProgressType = object : TypeToken<List<CachedInProgressItem>>() {}.type
    private val nextUpType = object : TypeToken<List<CachedNextUpItem>>() {}.type

    /** What a cache file carrying a null looks like on disk (the shape the box had). */
    private val dirtyInProgress = """
        [
          {"contentId": null, "contentType": "show", "name": "Sin id", "videoId": "v1",
           "poster": null, "backdrop": null, "logo": null, "season": 1, "episode": 2,
           "episodeTitle": null, "position": 10, "duration": 100, "lastWatched": 5,
           "progressPercent": 10.0},
          {"contentId": "tt0903747", "contentType": "show", "name": "Breaking Bad", "videoId": "v2",
           "poster": "p", "backdrop": "b", "logo": null, "season": 2, "episode": 5,
           "episodeTitle": "Breakage", "position": 30, "duration": 100, "lastWatched": 9,
           "progressPercent": 30.0}
        ]
    """.trimIndent()

    /** The same file with the field simply missing, which Gson reads the same way. */
    private val missingInProgress = """
        [{"contentType":"show","name":"Sin id","videoId":"v1","poster":null,"backdrop":null,
          "logo":null,"season":null,"episode":null,"episodeTitle":null,"position":1,
          "duration":2,"lastWatched":3,"progressPercent":1.0}]
    """.trimIndent()

    @Test
    fun `an item whose contentId is null never reaches the home screen`() {
        val items: List<CachedInProgressItem> =
            ContinueWatchingEnrichmentCache.parseSnapshot(dirtyInProgress, inProgressType)
        // Gson hands back the null as-is today; the reader must not.
        val ids = items.map { it.contentId }
        assertTrue("null/blank contentId passed the read: $ids", ids.all { !it.isNullOrBlank() })
        assertEquals(listOf("tt0903747"), ids)
    }

    @Test
    fun `an item without the field is dropped too`() {
        val items: List<CachedInProgressItem> =
            ContinueWatchingEnrichmentCache.parseSnapshot(missingInProgress, inProgressType)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `valid items keep everything the home screen reads`() {
        val items: List<CachedInProgressItem> =
            ContinueWatchingEnrichmentCache.parseSnapshot(dirtyInProgress, inProgressType)
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("Breaking Bad", item.name)
        assertEquals(30, item.position)
        assertEquals(2, item.season)
    }

    @Test
    fun `the next-up snapshot is guarded the same way`() {
        val dirtyNextUp = """
            [{"contentId": null, "contentType": "show", "name": "Sin id", "videoId": "v",
              "poster": null, "backdrop": null, "logo": null, "season": 1, "episode": 1,
              "episodeTitle": null, "thumbnail": null, "lastWatched": 1, "sortTimestamp": 2},
             {"contentId": "tt0000001", "contentType": "show", "name": "Ok", "videoId": "v2",
              "poster": null, "backdrop": null, "logo": null, "season": 1, "episode": 2,
              "episodeTitle": null, "thumbnail": null, "lastWatched": 1, "sortTimestamp": 3}]
        """.trimIndent()
        val items: List<CachedNextUpItem> =
            ContinueWatchingEnrichmentCache.parseSnapshot(dirtyNextUp, nextUpType)
        assertEquals(listOf("tt0000001"), items.map { it.contentId })
    }

    @Test
    fun `a body that is not a snapshot at all is empty, never a throw`() {
        listOf("<html>not json</html>", "{}", "").forEach { body ->
            val items: List<CachedInProgressItem> =
                ContinueWatchingEnrichmentCache.parseSnapshot(body, inProgressType)
            assertTrue("expected an empty answer for $body", items.isEmpty())
        }
    }

}
