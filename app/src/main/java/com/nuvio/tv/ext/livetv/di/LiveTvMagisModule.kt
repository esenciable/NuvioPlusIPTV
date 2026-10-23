package com.nuvio.tv.ext.livetv.di

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.ext.livetv.data.MagisChannelLoader
import com.nuvio.tv.ext.livetv.data.MagisStreamResolver
import com.nuvio.tv.ext.livetv.data.NativeLiveSources
import com.nuvio.tv.ext.livetv.data.NativeVodSources
import com.nuvio.tv.ext.livetv.magis.DataStoreMagisConfigStore
import com.nuvio.tv.ext.livetv.magis.DataStoreMagisSessionStore
import com.nuvio.tv.ext.livetv.magis.MagisConfigProvider
import com.nuvio.tv.ext.livetv.magis.MagisConfigStore
import com.nuvio.tv.ext.livetv.magis.MagisCryptoHolder
import com.nuvio.tv.ext.livetv.magis.MagisLiveCatalogApi
import com.nuvio.tv.ext.livetv.magis.MagisLiveClient
import com.nuvio.tv.ext.livetv.magis.MagisLivePlaybackApi
import com.nuvio.tv.ext.livetv.magis.MagisPortalClient
import com.nuvio.tv.ext.livetv.magis.MagisVodClient
import com.nuvio.tv.ext.livetv.magis.MagisVodSource
import com.nuvio.tv.ext.livetv.magis.MagisRemoteConfig
import com.nuvio.tv.ext.livetv.magis.TmdbTitleLookup
import com.nuvio.tv.ext.livetv.magis.MagisRuntimeConfig
import com.nuvio.tv.ext.livetv.magis.MagisSession
import com.nuvio.tv.ext.livetv.magis.readOr
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * DI for the native Magis sources: VOD in the stream pipeline, LIVE in the Live TV tab, over ONE
 * portal stack (one rate limiter, one anonymous device session, one remote config).
 *
 * The whole portal stack is built HERE — config, crypto, session store, portal, session — ONCE,
 * shared by every native source: a build without usable MAGIS_* values must produce a DISABLED
 * stack, not a crash: [MagisCrypto] rejects an incomplete key at construction, so an invalid
 * configuration falls back to an EMPTY native-source list, whose consumers simply report the
 * sources as unconfigured.
 *
 * The runtime config is REMOTE-first (`magis-config.json`, see [MagisRemoteConfig]): startup
 * uses the disk cache or the `BuildConfig` fallback immediately and adopts the remote document
 * when the background fetch lands, so a portal rotation is fixed with one push to the plugins
 * repo instead of a new APK.
 */
@Module
@InstallIn(SingletonComponent::class)
object LiveTvMagisModule {

    @Provides
    @Singleton
    internal fun provideMagisConfigStore(@ApplicationContext context: Context): MagisConfigStore =
        DataStoreMagisConfigStore(context)

    /** `BuildConfig` is only the FINAL fallback: disk cache first, remote later, never network. */
    private fun buildConfigFallback() = MagisRuntimeConfig(
        hosts = BuildConfig.MAGIS_HOSTS.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        appId = BuildConfig.MAGIS_APP_ID.trim(),
        apkVersion = BuildConfig.MAGIS_APK_VERSION.trim(),
        apkVerHeader = BuildConfig.MAGIS_APK_VER_HEADER.trim(),
        spkgVer = BuildConfig.MAGIS_SPKG_VER.trim(),
        threeDesKeyHex = BuildConfig.MAGIS_3DES_KEY.trim(),
    )

    @Provides
    @Singleton
    internal fun provideMagisConfigProvider(store: MagisConfigStore): MagisConfigProvider =
        MagisConfigProvider(store.readOr(buildConfigFallback()))

    @Provides
    @Singleton
    internal fun provideMagisRemoteConfig(
        provider: MagisConfigProvider,
        store: MagisConfigStore,
    ): MagisRemoteConfig = MagisRemoteConfig(provider, store)

    @Provides
    @Singleton
    internal fun provideMagisPortalStack(
        provider: MagisConfigProvider,
        remoteConfig: MagisRemoteConfig,
        @ApplicationContext context: Context,
    ): MagisPortalStack? {
        // The crypto rebuilds ONLY when the config's 3DES key changes, so a key rotation lands
        // without a restart; the portal/live/vod clients read the config PER USE and never
        // capture a snapshot.
        val holder = MagisCryptoHolder { provider.current.threeDesKeyHex }
        // Background refresh: launched ASYNC on a process-lifetime scope the moment the stack is
        // provisioned — the first fetch fires without blocking injection, and the loop keeps
        // retrying on its tick until a VALID result arms the few-hours interval. It runs even
        // when this build starts disabled: the fetched config is persisted to disk, so the next
        // launch comes up enabled from the cache ("source absent, never a crash" is preserved).
        remoteConfig.start(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        if (holder.get() == null) return null
        val store = DataStoreMagisSessionStore(context)
        val portal = MagisPortalClient(
            configProvider = { provider.current },
            cryptoProvider = { holder.get() },
            // The portal signs every body with the minted device's sn; the session mints it and
            // changes it live, so it is READ per call, never captured.
            snProvider = { store.read()?.sn.orEmpty() },
        )
        return MagisPortalStack(portal = portal, session = MagisSession(portal, store))
    }

    /**
     * The native VOD sources for the stream pipeline, built on the shared portal stack above
     * (one rate limiter, one session store) plus the base's own TMDB stack for the title
     * lookup. An unusable Magis config yields an EMPTY source list: the stream pipeline runs
     * scrapers-only, exactly like a build without the feature — never a crash.
     */
    @Provides
    @Singleton
    internal fun provideNativeVodSources(
        provider: MagisConfigProvider,
        stack: MagisPortalStack?,
        tmdbService: TmdbService,
        tmdbApi: TmdbApi,
    ): NativeVodSources {
        val source = stack?.let {
            MagisVodSource(
                client = MagisVodClient(
                    portal = it.portal,
                    session = it.session,
                    configProvider = { provider.current },
                    titleLookup = TmdbTitleLookup(tmdbService, tmdbApi),
                ),
            )
        }
        return NativeVodSources(listOfNotNull(source))
    }

    // --- native LIVE source -----------------------------------------------------

    /**
     * The portal's live API over the SAME stack as VOD: one rate limiter, one anonymous session,
     * one config. Null when the build carries no usable Magis configuration — every live consumer
     * treats that as "the source does not exist".
     */
    @Provides
    @Singleton
    internal fun provideMagisLiveClient(
        provider: MagisConfigProvider,
        stack: MagisPortalStack?,
    ): MagisLiveClient? = stack?.let {
        MagisLiveClient(
            portal = it.portal,
            session = it.session,
            configProvider = { provider.current },
        )
    }

    @Provides
    @Singleton
    internal fun provideMagisLiveCatalogApi(client: MagisLiveClient?): MagisLiveCatalogApi? = client

    @Provides
    @Singleton
    internal fun provideMagisLivePlaybackApi(client: MagisLiveClient?): MagisLivePlaybackApi? = client

    /**
     * The native live sources the Live TV tab and the player consult. One entry per configured
     * source; the disabled-config contract (a null client adds nothing and crashes nothing) is
     * carried by [NativeLiveSources] itself.
     */
    @Provides
    @Singleton
    internal fun provideNativeLiveSources(
        catalogApi: MagisLiveCatalogApi?,
        playbackApi: MagisLivePlaybackApi?,
    ): NativeLiveSources = NativeLiveSources(
        listOfNotNull(
            if (catalogApi != null && playbackApi != null) {
                NativeLiveSources.NativeLiveEntry(
                    loader = MagisChannelLoader(catalogApi),
                    resolver = MagisStreamResolver(playbackApi),
                )
            } else null,
        ),
    )
}

/**
 * The shared Magis portal stack behind the native sources: one portal (and so one rate limiter
 * and host failover state) plus one session over the shared DataStore. `null` when the build
 * carries no usable Magis configuration.
 */
internal class MagisPortalStack internal constructor(
    internal val portal: MagisPortalClient,
    internal val session: MagisSession,
)
