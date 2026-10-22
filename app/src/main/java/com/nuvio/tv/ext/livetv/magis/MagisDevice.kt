package com.nuvio.tv.ext.livetv.magis

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The device identity the Magis protocol speaks about. Port of the TS reference's device
 * handling (`kino-light-addon` device minting, materialized in
 * `kino-light-main`'s `MagisSession.hardwareFingerprint`): the identifying fields are
 * randomized so each mint creates ITS OWN device, while the fixed identity fields are the
 * emulator's, the one the protocol was captured with — changing them is untested and the
 * portal validates some of them against the minted device.
 *
 * Pure on purpose: no Android APIs, so minting and the `sn` seed derivation are unit-testable.
 */
internal object MagisDevice {

    private val random = SecureRandom()

    /** The MAC the original app sends, fixed and fake. */
    const val FIXED_MAC = "02:00:00:00:00:00"

    /** Salt for `sn = md5(snToken + salt)` when the portal doesn't hand an `sn` back. */
    const val SNTOKEN_SALT = "ntFT65w6itH!lHCPw7D=@qnsFC5adD28"

    /**
     * The fingerprint `v3/snToken` expects (`SnTokenBean` in the decompiled app). The original
     * app sends the phone's real data; here go the emulator's fixed fields with the
     * identifying ones randomized — two fingerprints would be two devices, and the portal
     * hands out one device per fingerprint.
     */
    fun hardwareFingerprint(): Map<String, Any?> = mapOf(
        "androidId" to randomHex(8),
        "board" to "goldfish_arm64",
        "brand" to "google",
        "cpuAbi" to "arm64-v8a",
        "cpuId" to randomHex(8),
        "device" to "emu64a",
        "diskInfo" to "8GB",
        "display" to "sdk_gphone64_arm64",
        "etheMac" to randomMac(),
        "fingerprint" to
            "google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11228894:user/release-keys",
        "gatewayMac" to randomMac(),
        "hardware" to "ranchu",
        "host" to "abfarm",
        "manufacturer" to "Google",
        "ramSize" to "4GB",
        "romSize" to "8GB",
        "serialNumber" to randomHex(8),
        "tags" to "release-keys",
        "verId" to "",
        "wifiMac" to randomMac(),
    )

    /**
     * This device's `sn`: the portal's own when it returned one, otherwise derived from the
     * `snToken` (`md5(snToken + SNTOKEN_SALT)`, lowercase — the TS reference does the same in
     * `session.ts`). What can never be done is making up an `sn`: the portal answers
     * `snToken已经失效`.
     */
    fun snFromSnToken(snToken: String, portalSn: String = ""): String =
        portalSn.trim().takeIf { it.isNotBlank() }
            ?: md5Hex(snToken + SNTOKEN_SALT).lowercase()

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun randomMac(): String =
        ByteArray(6).also { random.nextBytes(it) }.joinToString(":") { "%02x".format(it) }

    private fun md5Hex(s: String): String = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
