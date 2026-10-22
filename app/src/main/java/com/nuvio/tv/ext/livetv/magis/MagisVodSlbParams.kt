package com.nuvio.tv.ext.livetv.magis

import org.json.JSONArray
import org.json.JSONObject

/**
 * VOD-side copy of the `v14/getSlbInfo` request body.
 *
 * PORT ADAPTATION: [slbRequestParams] and the live column code it carries are defined in
 * `MagisLiveClient.kt` in the fork, and the native LIVE engine is a non-goal of this port (P3):
 * the VOD flow is the only caller in the base, so both travel here, VERBATIM from that file.
 * Keep them identical to the live client's copy — the portal validates the body, not the caller.
 */
internal const val MAGIS_LIVE_ROOT = "masnew_live"

/**
 * `v14/getSlbInfo` is the same call for VOD and for live; for live, the channel being resolved
 * goes into `liveCodeList`. Explicit `JSONArray`: Android's `JSONObject(Map)` does NOT convert a
 * Kotlin `List`, it serializes it as the text `[cyx-XXX]` and the portal gets garbage.
 */
internal fun slbRequestParams(apkVersion: String, liveCodes: List<String>): Map<String, Any?> = mapOf(
    "hasPay" to "0",
    "userIdentity" to "1",
    "type" to "merge",
    "appVer" to apkVersion,
    "lang" to "es",
    "encMediaSupported" to 1,
    "liveCodeList" to JSONArray(liveCodes),
    "appParams" to "",
    "reserve1" to "02:00:00:00:00:00",
    "pipFlag" to "0",
)

/**
 * The `v14/getSlbInfo` answer's TTL in seconds (`invalidTime`), 300 s when the portal omits it —
 * the same default [MagisVodSlbCache] relies on. Verbatim from `MagisLiveClient.kt` in the fork
 * (see the port note above): the VOD SLB cache is its only caller in the base.
 */
internal fun ttlOf(slb: JSONObject): Long =
    slb.optString("invalidTime").toLongOrNull()?.takeIf { it > 0 } ?: 300L
