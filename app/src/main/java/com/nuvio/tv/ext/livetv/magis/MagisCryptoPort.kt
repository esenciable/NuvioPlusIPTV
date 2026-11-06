package com.nuvio.tv.ext.livetv.magis

/**
 * The platform primitives the Magis protocol needs, and nothing else.
 *
 * The package has to compile in the KMP mobile client (`composeApp/commonMain`), where
 * `javax.crypto`, `java.security` and `java.util.Base64` do not exist. Android implements this
 * with those three; iOS with CommonCrypto. Everything above — the wire format
 * `hex(base64(3DES-EDE/ECB/PKCS5(json)))`, the hex encoding, the caching — stays here.
 *
 * Deliberately PRIMITIVES, not conveniences: [MagisCrypto] builds the encoding on top so a
 * platform only has to get three DES blocks and a digest right.
 */
internal interface MagisCryptoPort {

    /** `DESede/ECB/PKCS5Padding` over [data] with the 24-byte [key]. */
    fun tripleDesEcbPkcs5(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray

    /** MD5 of [data]. Used for the device `sn` seed; not a security boundary. */
    fun md5(data: ByteArray): ByteArray

    /** [size] cryptographically random bytes (one device fingerprint per mint). */
    fun randomBytes(size: Int): ByteArray

}
