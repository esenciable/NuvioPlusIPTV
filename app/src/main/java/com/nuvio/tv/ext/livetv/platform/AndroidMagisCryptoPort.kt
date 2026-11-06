package com.nuvio.tv.ext.livetv.platform

import com.nuvio.tv.ext.livetv.magis.MagisCryptoPort
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The Android implementation of the Magis crypto port.
 *
 * Lives OUTSIDE `ext/livetv/magis/` on purpose: that package has to compile in the KMP mobile
 * client, and this file cannot (`javax.crypto`, `java.security`, `java.util.Base64` are JVM-only).
 * `MagisPortabilityTest` enforces the boundary; this is where the boundary is allowed to end.
 */
internal object AndroidMagisCryptoPort : MagisCryptoPort {

    private val random = SecureRandom()

    override fun tripleDesEcbPkcs5(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "DESede"))
        return cipher.doFinal(data)
    }

    override fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    override fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { random.nextBytes(it) }
}
