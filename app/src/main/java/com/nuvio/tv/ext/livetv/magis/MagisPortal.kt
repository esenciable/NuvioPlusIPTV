package com.nuvio.tv.ext.livetv.magis

/**
 * Runtime configuration for talking to the Magis portal.
 *
 * TODO(M4): the values must come from the app's settings/build config — hosts, the 3DES master
 *  key and the app identity are deployment secrets, NOT code constants. Nothing in this package
 *  hardcodes them; whoever wires this (M2/M3 DI, M4 settings UI) injects the values here, the
 *  same way the fork injects other `local.properties`-sourced secrets through BuildConfig.
 */
data class MagisRuntimeConfig(
    /** Bare portal hosts (no scheme), in preference order; e.g. `host1.example.com`. */
    val hosts: List<String>,
    /** The `appId` the portal expects in the `apk` header and the device dict. */
    val appId: String,
    /** The device dict's `apkVersion` (NOT the fixed `apkVer` header; different fields). */
    val apkVersion: String,
    /** The `apkVer` HTTP header value (`43404`-style): the one that rotates with the portal. */
    val apkVerHeader: String,
    /** The `spkgVer` HTTP header / `sysVersion` device-dict value, also rotating. */
    val spkgVer: String,
    /** 48-hex-character 3DES master key. */
    val threeDesKeyHex: String,
)

/**
 * Transport for the Magis portal. The layers above (session, catalog, resolution) depend on
 * this interface and not on the platform client, so a double can be injected into tests and the
 * mobile client can bring its own HTTP stack.
 *
 * Port of the TS reference (`kino-light-addon/src/magis/portal.ts`) and the verified Kotlin
 * original (`kino-light-main` `MagisPortalClient.kt`).
 */
internal interface MagisPortalLike {
    suspend fun call(
        path: String,
        bean: Map<String, Any?> = emptyMap(),
        baseFields: Boolean = true,
        userId: String = "",
        userToken: String = "",
        /** Overrides the device dict's `sn` for this one call (`null` keeps the stored session's). */
        sn: String? = null,
    ): MagisResult<MagisJsonObject>
}
