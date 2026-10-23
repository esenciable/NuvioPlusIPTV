package com.nuvio.tv.ext.livetv.data

import com.nuvio.tv.ext.livetv.domain.NativeLiveCatalogLoader
import com.nuvio.tv.ext.livetv.magis.MagisLiveCatalogApi
import com.nuvio.tv.ext.livetv.magis.MagisLiveCategory
import com.nuvio.tv.ext.livetv.magis.MagisLiveChannel
import com.nuvio.tv.ext.livetv.magis.MagisLiveClient
import com.nuvio.tv.ext.livetv.magis.MagisLiveSource
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The catalog side of the native Magis source: it walks the portal's categories and pages and
 * produces [LiveChannel]s in the base's own shape, so the Live TV tab shows them beside the
 * playlist channels without a single special case downstream.
 *
 * The port it consumes — [MagisLiveCatalogApi] — is the smallest slice of [MagisLiveClient] the
 * walk needs, so tests can fake the portal without a session, a crypto stack or a network.
 *
 * A category that could not be read is skipped, not fatal: the categories that did answer still
 * arrive (cancellation is rethrown — an abandoned screen must not be read as a portal failure). A
 * channel appearing in several categories keeps the FIRST one (the base's model carries one group
 * per channel), and the channel key is stable across reloads so favorites survive.
 */
class MagisChannelLoader(
    /** The portal API, or null when the deployment carries no Magis configuration — a build
     *  without MAGIS_* values must behave as if the source does not exist, not crash or load. */
    private val client: MagisLiveCatalogApi?,
    /** The page size [MagisLiveClient] requests; a short page means the category is exhausted. */
    private val pageSize: Int = MagisLiveClient.PAGE_SIZE,
    /** Hard cap of portal pages per category, so a category that always answers full stops. */
    private val maxPagesPerCategory: Int = DEFAULT_MAX_PAGES,
    /** Bounded like the addon loader; the portal's own pace between calls is the real limiter. */
    private val concurrency: Semaphore = Semaphore(DEFAULT_CONCURRENCY),
) : NativeLiveCatalogLoader {

    override val sourceId: String get() = MagisLiveSource.SOURCE_ID

    /** Whether this build carries a usable Magis configuration at all. */
    override val isConfigured: Boolean get() = client != null

    override suspend fun loadChannels(): List<LiveChannel> {
        val api = client ?: return emptyList()
        val categories = runCatching { api.categories() }.getOrElse {
            // The categories are the whole walk: without them there is nothing to page, and the
            // reason (portal down, dead session, no config) is the only thing a report can say.
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Native live (Magis): no categories — ${it.message}")
            return emptyList()
        }
        if (categories.isEmpty()) {
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Native live (Magis): the portal answered 0 categories")
            return emptyList()
        }

        // Categories are independent reads; the bounded concurrency only removes dead time
        // between them. One failed category leaves the rest exactly as they were — but a
        // cancelled screen is not a failed category: cancellation travels up, it is never
        // swallowed as an empty answer.
        val firstFailure = java.util.concurrent.atomic.AtomicInteger()
        val batches: List<List<MagisLiveChannel>> = coroutineScope {
            categories
                .map { category ->
                    async {
                        concurrency.withPermit {
                            try {
                                loadCategory(api, category)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // A dead category contributes nothing; the walk continues. The
                                // first failure is reported once — the portal answers the same
                                // way to every page, and 38 lines would bury the rest of the log.
                                if (firstFailure.getAndIncrement() == 0) {
                                    com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                                        "Native live (Magis): ${category.name} failed — ${e.message}",
                                    )
                                }
                                emptyList()
                            }
                        }
                    }
                }
                .awaitAll()
        }

        val out = LinkedHashMap<String, LiveChannel>()
        categories.forEachIndexed { index, category ->
            for (magisChannel in batches[index]) {
                if (magisChannel.code.isBlank()) continue
                val channel = MagisLiveSource.channelFrom(magisChannel, category.id, category.name)
                out.putIfAbsent(channel.key, channel)
            }
        }
        return out.values.toList()
    }

    /** One category, every page: full pages keep coming until one arrives short. */
    private suspend fun loadCategory(api: MagisLiveCatalogApi, category: MagisLiveCategory): List<MagisLiveChannel> {
        val loaded = mutableListOf<MagisLiveChannel>()
        for (page in 1..maxPagesPerCategory) {
            val fetched = api.channels(category.id, page)
            loaded += fetched
            if (fetched.size < pageSize) break
        }
        return loaded
    }

    companion object {
        const val DEFAULT_MAX_PAGES = 5

        /** Bounded like the addon loader; the portal's own pace dominates anyway. */
        const val DEFAULT_CONCURRENCY = 4
    }
}
