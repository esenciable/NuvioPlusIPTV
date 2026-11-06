package com.nuvio.tv.ext.livetv.magis

/**
 * Base64, in Kotlin, because it is not a platform primitive.
 *
 * `java.util.Base64` is JVM-only (and API 26+ on Android); `android.util.Base64` is a stub in
 * unit tests, which silently turns a crypto test into a "method not mocked" failure. The wire
 * format needs it on every client, so it lives here and is tested as part of the protocol.
 *
 * Standard alphabet with `=` padding, which is what the portal's `hex(base64(3DES(json)))`
 * envelope uses.
 */
internal object MagisBase64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            out.append(if (b1 >= 0) ALPHABET[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
            out.append(if (b2 >= 0) ALPHABET[b2 and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.filterNot { it == '\n' || it == '\r' || it == ' ' }
        require(clean.length % 4 == 0) { "invalid base64 length: ${clean.length}" }
        val out = ArrayList<Byte>(clean.length / 4 * 3)
        var i = 0
        while (i < clean.length) {
            val chunk = clean.substring(i, i + 4)
            val v0 = index(chunk[0])
            val v1 = index(chunk[1])
            val v2 = if (chunk[2] == '=') -1 else index(chunk[2])
            val v3 = if (chunk[3] == '=') -1 else index(chunk[3])
            out.add(((v0 shl 2) or (v1 shr 4)).toByte())
            if (v2 >= 0) out.add((((v1 and 0x0F) shl 4) or (v2 shr 2)).toByte())
            if (v3 >= 0) out.add((((v2 and 0x03) shl 6) or v3).toByte())
            i += 4
        }
        return out.toByteArray()
    }

    private fun index(c: Char): Int {
        val i = ALPHABET.indexOf(c)
        require(i >= 0) { "invalid base64 character: $c" }
        return i
    }
}
