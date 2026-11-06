package com.nuvio.tv.ext.livetv.magis

/**
 * The single source of truth for the Magis portal's rotating values (`magis-config.json` at the
 * root of `esenciable/esencial-providers`, served by raw). The plugin reads the SAME file, so
 * plugin and fork cannot drift apart; a rotation is fixed with one push, no new APK.
 *
 * Port of the doc contract (`odd/tasks/magis-remote-config.md`):
 *
 *  - **Validation is ALL-OR-NOTHING**: every field must be valid or the whole document is
 *    rejected and the previous config is kept — never a half-valid config.
 *  - **Resolution order, first valid wins**: last-known-good on disk -> remote fetch in the
 *    background (NON-blocking) -> `BuildConfig` as the final fallback. Startup never waits for
 *    the network.
 *  - A failed fetch clears NOTHING and arms NO "attempted" TTL: only a valid result counts, and
 *    a *successful* refresh runs at most once every few hours per process.
 *
 * The transport (OkHttp) and the storage (DataStore) live in `ext/livetv/platform/`: what is
 * here is the contract, which both clients share.
 */
internal const val MAGIS_CONFIG_URL =
    "https://raw.githubusercontent.com/esenciable/esencial-providers/main/magis-config.json"

/** A *successful* remote refresh happens at most once per process every few hours. */
internal const val MAGIS_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L

/** How often the background loop wakes up to consider a refresh (failed fetches retry here). */
internal const val MAGIS_REFRESH_TICK_MS = 15 * 60 * 1000L

// --- parsing / validation ----------------------------------------------------

/**
 * Parses and validates `magis-config.json`. ALL-OR-NOTHING: returns `null` (rejecting the whole
 * document) if ANY field is invalid —
 *   `schema == 1`; `hosts` non-empty with no scheme and no `/` in any entry; `appId` non-blank;
 *   `apkVersion` and `apkVerHeader` digits only; `spkgVer` non-blank;
 *   `threeDesKeyHex` matching `^[0-9a-f]{48}$`.
 * The caller keeps the previous config on `null`.
 */
internal fun parseMagisConfig(json: String): MagisRuntimeConfig? {
    val doc = runCatching { MagisJsonObject(json) }.getOrNull() ?: return null
    if (doc.optInt("schema", -1) != 1) return null

    val hosts = mutableListOf<String>()
    val hostArray = doc.optJSONArray("hosts") ?: return null
    if (hostArray.length() == 0) return null
    for (i in 0 until hostArray.length()) {
        val host = hostArray.optString(i).trim()
        if (host.isEmpty() || host.contains("://") || host.contains('/')) return null
        hosts.add(host)
    }

    val appId = doc.optString("appId").trim()
    if (appId.isEmpty()) return null

    val apkVersion = doc.optString("apkVersion").trim()
    if (!apkVersion.matches(DIGITS)) return null

    val apkVerHeader = doc.optString("apkVerHeader").trim()
    if (!apkVerHeader.matches(DIGITS)) return null

    val spkgVer = doc.optString("spkgVer").trim()
    if (spkgVer.isEmpty()) return null

    val key = doc.optString("threeDesKeyHex").trim()
    if (!key.matches(KEY_48_HEX)) return null

    return MagisRuntimeConfig(
        hosts = hosts,
        appId = appId,
        apkVersion = apkVersion,
        apkVerHeader = apkVerHeader,
        spkgVer = spkgVer,
        threeDesKeyHex = key,
    )
}

private val DIGITS = Regex("""\d+""")
private val KEY_48_HEX = Regex("""^[0-9a-f]{48}$""")

internal fun MagisRuntimeConfig.toJson(): String = MagisJsonObject().apply {
    put("schema", 1)
    put("hosts", MagisJsonArray(hosts))
    put("appId", appId)
    put("apkVersion", apkVersion)
    put("apkVerHeader", apkVerHeader)
    put("spkgVer", spkgVer)
    put("threeDesKeyHex", threeDesKeyHex)
}.toString()

// --- holder ------------------------------------------------------------------

/**
 * The best-known Magis config at any moment, held in a `@Volatile` field so the portal, the
 * crypto and the live client read it PER USE and a rotation lands without an app restart.
 * Initialized (before any network wait) with the disk cache or, failing that, `BuildConfig`.
 */
internal class MagisConfigProvider(initial: MagisRuntimeConfig) {

    @Volatile
    var current: MagisRuntimeConfig = initial
        private set

    fun update(config: MagisRuntimeConfig) {
        current = config
    }
}

/**
 * Startup resolution, first-valid-wins and NEVER waiting for the network: the disk cache (last
 * known good) beats the `BuildConfig` fallback. The remote fetch only happens later, in the
 * background (see the platform's `MagisRemoteConfig`).
 */
internal fun MagisConfigStore.readOr(fallback: MagisRuntimeConfig): MagisRuntimeConfig = read() ?: fallback

// --- persistence contract ----------------------------------------------------

/** Persistence for the last-known-good remote config. An interface so each client persists its own way. */
internal interface MagisConfigStore {
    fun save(config: MagisRuntimeConfig)
    fun read(): MagisRuntimeConfig?
}
