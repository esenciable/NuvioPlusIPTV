package com.nuvio.tv.ext.livetv.platform

import com.nuvio.tv.ext.livetv.magis.MagisCrypto
import com.nuvio.tv.ext.livetv.magis.MagisJsonObject
import com.nuvio.tv.ext.livetv.magis.MagisPortalLike
import com.nuvio.tv.ext.livetv.magis.MagisResult
import com.nuvio.tv.ext.livetv.magis.MagisRuntimeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks directly to the Magis portal over OkHttp. Three things aren't negotiable (if missing,
 * the portal answers "版本已停止使用" or 未登录):
 *  - the body goes encrypted with [MagisCrypto] (hex(base64(3DES))), never bare JSON;
 *  - EVERY body gets [deviceDict]'s ~15 fields glued on (the app's interceptor);
 *  - the `apk` / `apkVer` / `spkgVer` headers always go.
 *
 * Lives outside the portable `magis` package because OkHttp is a client choice, not protocol:
 * the mobile client brings its own HTTP stack and implements [MagisPortalLike] with it.
 *
 * [hosts] arrives through [MagisRuntimeConfig] so tests can point the client at a local server;
 * the real values are injected by whoever wires the config (see [MagisRuntimeConfig]'s M4 note).
 */
internal class MagisPortalClient(
    /** Runtime config, read PER USE: hosts, `appId`, `apkVer` and `spkgVer` rotate with the
     *  portal, and the remote config lands while the process is alive (see `MagisConfigProvider`).
     *  Capturing a snapshot here would freeze the values from app start until restart. */
    private val configProvider: () -> MagisRuntimeConfig,
    /** Crypto, read PER USE: rebuilt only when the config's 3DES key actually changes. */
    private val cryptoProvider: () -> MagisCrypto?,
    /** The minted device's `sn`, read on every call: the session mints it and changes it live. */
    private val snProvider: () -> String = { "" },
    /** `https` in production; `http` exists so tests can point this at a MockWebServer. */
    private val scheme: String = "https",
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : MagisPortalLike {

    private val jsonType = "application/json;charset=utf-8".toMediaType()

    /** Minimum pace between calls: a small courtesy gap between portal calls (measured live —
     *  see the RATE_LIMIT_MS note below; the TS reference paced 400 ms, the JS plugin none). */
    private val rateLimit = Mutex()
    private var lastCallMs = 0L

    /** Host that worked last time: tried first so as not to pay a dead host's timeout every call. */
    @Volatile
    private var preferredHost: String? = null

    override suspend fun call(
        path: String,
        bean: Map<String, Any?>,
        baseFields: Boolean,
        userId: String,
        userToken: String,
        sn: String?,
    ): MagisResult<MagisJsonObject> {
        val crypto = cryptoProvider()
            ?: return MagisResult.RedError(IllegalStateException("config de Magis sin clave 3DES usable"))
        val config = configProvider()
        val body = buildMap<String, Any?> {
            if (baseFields) {
                put("portalCode", PORTAL_CODE)
                put("userId", userId)
                put("userToken", userToken)
            }
            putAll(bean)
            putAll(deviceDict(config, sn ?: snProvider()))
        }
        val wire = crypto.encryptBody(MagisJsonObject(body).toString())

        waitTurn()
        var lastError: Throwable? = null
        for (host in hostOrder(config)) {
            val request = Request.Builder()
                .url("$scheme://$host/api/portalCore/$path")
                .post(wire.toRequestBody(jsonType))
                .header("apk", config.appId)
                .header("apkVer", config.apkVerHeader)
                .header("spkgVer", config.spkgVer)
                .header("User-Agent", "okhttp/3.12.12")
                .build()
            try {
                val raw = withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { it.body?.string().orEmpty() }
                }
                val j = MagisJsonObject(raw)
                preferredHost = host
                val rc = j.optString("returnCode").takeIf { it.isNotEmpty() }
                if (rc != null && rc != "0") {
                    return MagisResult.PortalError(rc, j.optString("errorMessage").ifBlank { null })
                }
                val data = j.optString("data")
                return if (data.isNotEmpty()) {
                    MagisResult.Ok(MagisJsonObject(crypto.decryptBlob(data)))
                } else {
                    MagisResult.Ok(j)
                }
            } catch (e: Throwable) {
                // Network down, TLS, or a response that isn't JSON: this host is no good, try
                // the next one. A MagisResult.PortalError above already returned.
                lastError = e
            }
        }
        return MagisResult.RedError(lastError ?: IllegalStateException("sin hosts configurados"))
    }

    private fun hostOrder(config: MagisRuntimeConfig): List<String> {
        val preferred = preferredHost ?: return config.hosts
        return listOf(preferred) + config.hosts.filter { it != preferred }
    }

    private suspend fun waitTurn() = rateLimit.withLock {
        val elapsed = System.currentTimeMillis() - lastCallMs
        if (lastCallMs != 0L && elapsed < RATE_LIMIT_MS) delay(RATE_LIMIT_MS - elapsed)
        lastCallMs = System.currentTimeMillis()
    }

    /**
     * The ~15 fields the original app glues to every body. The fixed values are the emulator's,
     * the one the protocol was captured with: changing them is untested and the portal validates
     * some of them against the minted device. `reserve1`/`deviceToken`/`drmId` go EMPTY on
     * purpose — that's how production sends them.
     */
    private fun deviceDict(config: MagisRuntimeConfig, sn: String): Map<String, Any?> = mapOf(
        "loginType" to "2",
        "appLanguage" to "en",
        "apkVersion" to config.apkVersion,
        "sysVersion" to config.spkgVer,
        "appId" to config.appId,
        "hardwareInfo" to "ranchu",
        "model" to "sdk_gphone64_arm64",
        "product" to "sdk_gphone64_arm64",
        "cpu" to "arm64-v8a",
        "B29" to "",
        "reserve1" to "",
        "deviceToken" to "",
        "sn" to sn,
        "drmId" to "",
        "sdkVer" to 36,
    )

    private companion object {
        const val PORTAL_CODE = "masnew"

        // `apkVer`/`spkgVer` are NOT constants anymore: they rotate with the portal and now come
        // from the runtime config (magis-config.json -> MagisConfigProvider) on every request.
        //
        // Minimum pace between calls. The TS reference awaits a 400 ms queue turn before every
        // request, but MEASURED (live portal, consecutive resolves, no rejection at any pass —
        // see odd/tasks/magis-vod-native.md V4a evidence): the JS plugin sends UNGACED and is
        // accepted fine, and 400 ms cost ~900 ms cold / ~370-540 ms warm. 150 ms keeps a
        // courtesy gap while returning most of that: cold ~4 calls, warm 3 calls.
        const val RATE_LIMIT_MS = 150L
    }
}
