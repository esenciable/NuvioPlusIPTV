package com.nuvio.tv.ext.livetv.magis

/**
 * Encryption for the Magis portal's bodies: `hex(base64(3DES-EDE/ECB/PKCS5(json)))`.
 *
 * Port of the TS reference (`kino-light-addon/src/magis/crypto.ts`) 1:1, and of the verified
 * Kotlin original (`kino-light-main/com.arkiv.player.data.magis.MagisCrypto`).
 *
 * The cipher primitives arrive through [MagisCryptoPort]; the wire format and the hex encoding
 * live here so every client produces byte-identical bodies.
 *
 * [keyHex] is the 24-byte master key in hex (48 characters). It is runtime configuration —
 * it must never be hardcoded here; see [MagisRuntimeConfig] for how it arrives.
 */
internal class MagisCrypto(
    private val port: MagisCryptoPort,
    keyHex: String,
) {

    init {
        require(keyHex.length == 48 && keyHex.all { it in "0123456789abcdefABCDEF" }) {
            "MAGIS_3DES_KEY must be 48 hexadecimal characters"
        }
    }

    private val key = hexToBytes(keyHex)

    fun encryptBody(plain: String): String {
        val ciphertext = port.tripleDesEcbPkcs5(key, plain.toByteArray(Charsets.UTF_8), encrypt = true)
        val b64 = port.base64Encode(ciphertext)
        return bytesToHex(b64.toByteArray(Charsets.US_ASCII))
    }

    fun decryptBlob(wire: String): String {
        val inner = String(hexToBytes(wire), Charsets.US_ASCII)
        val plain = port.tripleDesEcbPkcs5(key, port.base64Decode(inner), encrypt = false)
        return String(plain, Charsets.UTF_8)
    }
}

internal fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

internal fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

/**
 * Lazily builds [MagisCrypto] from the live config and rebuilds it ONLY when the 3DES key
 * actually changes: a key rotation from the remote config lands on the very next call without
 * restarting the app, and a stable config pays no cipher-init churn per call.
 * Returns `null` only while the current config carries an unusable key (the remote config's
 * all-or-nothing validation makes this rare; the DI layer treats it as "source absent").
 */
internal class MagisCryptoHolder(
    private val port: MagisCryptoPort,
    private val keyProvider: () -> String,
) {

    private val lock = Any()
    private var builtForKey: String? = null
    private var built: MagisCrypto? = null

    fun get(): MagisCrypto? {
        val key = keyProvider()
        synchronized(lock) {
            if (key != builtForKey) {
                built = runCatching { MagisCrypto(port, key) }.getOrNull()
                builtForKey = key
            }
            return built
        }
    }
}
