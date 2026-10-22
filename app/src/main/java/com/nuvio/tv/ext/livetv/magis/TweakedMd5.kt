package com.nuvio.tv.ext.livetv.magis

/**
 * The modified MD5 Magis uses to sign every live TV segment.
 *
 * Copied verbatim from the verified Kotlin original (`kino-light-main`
 * `com.arkiv.player.data.magis.TweakedMd5`), which is itself the 1:1 source of the TS port
 * (`kino-light-addon/src/magis/live-signature.ts`). Compared to textbook MD5, **only two
 * things** change (derived by emulating the proprietary binary; the reference was verified
 * against the `.so` over 200 random blocks):
 *
 * 1. Round 1's message schedule is [ROUND1], not `0..15`. Rounds 2–4 are standard.
 * 2. Four different K constants — rounds 42, 45, 54 and 62 — that look like transcription
 *    typos of the original MD5.
 *
 * IV, the F/G/H/I functions, shifts, little-endian padding and feed-forward are MD5's own.
 * The five vectors captured from the app are the acceptance test (see `TweakedMd5Test`).
 */
internal object TweakedMd5 {
    private val SALT = "salt3333=4".toByteArray() +
        byteArrayOf(0x98.toByte(), 0x0d, 0x0a, 0x15, 0x32, 0xc9.toByte(),
                    0xc3.toByte(), 0x82.toByte(), 0x17, 0x08, 0xc0.toByte())

    private val K = intArrayOf(
        -0x28955b88, -0x173848aa, 0x242070db, -0x3e423112,
        -0x0a83f051, 0x4787c62a, -0x57cfb9ed, -0x02b96aff,
        0x698098d8, -0x74bb0851, -0x0000a44f, -0x76a32842,
        0x6b901122, -0x02678e6d, -0x5986bc72, 0x49b40821,
        -0x09e1da9e, -0x3fbf4cc0, 0x265e5a51, -0x16493856,
        -0x29d0efa3, 0x02441453, -0x275e197f, -0x182c0438,
        0x21e1cde6, -0x3cc8f82a, -0x0b2af279, 0x455a14ed,
        -0x561c16fb, -0x03105c08, 0x676f02d9, -0x72d5b376,
        -0x0005c6be, -0x788e097f, 0x6d9d6122, -0x021ac7f4,
        -0x5b4115bc, 0x4bdecfa9, -0x0944b4a0, -0x41404390,
        0x289b7ec6, -0x155ed806, -0x2b10cf7b, 0x04881d05,
        -0x262b2fc7, -0x1924661b, 0x1fa27cf8, -0x3b53a99b,
        -0x0bd6ddbc, 0x432aff97, -0x546bdc59, -0x036c5fc7,
        0x655b59c3, -0x70f3336e, -0x00100b83, -0x7a7ba22f,
        0x6fa87e4f, -0x01d31920, -0x5cfebcec, 0x4e0811a1,
        -0x08ac817e, -0x42c50dcb, 0x2ad7d2bb, -0x14792c6f,
    )

    // The tweak: four changed constants. Written as literal hex so they can be checked at a
    // glance against the reference's docstring table.
    private val KT = K.copyOf().also {
        it[42] = 0xd46f3085.toInt()   // standard d4ef3085
        it[45] = 0xe6bd99e5.toInt()   // standard e6db99e5
        it[54] = 0xffecc47d.toInt()   // standard ffeff47d
        it[62] = 0x2da7d2bb.toInt()   // standard 2ad7d2bb
    }

    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** The tweak: round 1's schedule. The other three are the standard formulas. */
    private val ROUND1 = intArrayOf(10, 11, 12, 13, 14, 15, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5)

    private val G = IntArray(64) { i ->
        when {
            i < 16 -> ROUND1[i]
            i < 32 -> (5 * i + 1) % 16
            i < 48 -> (3 * i + 5) % 16
            else -> (7 * i) % 16
        }
    }

    private fun rotl(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))

    private fun compress(state: IntArray, block: ByteArray, off: Int) {
        val m = IntArray(16) { j ->
            val p = off + j * 4
            (block[p].toInt() and 0xff) or
                ((block[p + 1].toInt() and 0xff) shl 8) or
                ((block[p + 2].toInt() and 0xff) shl 16) or
                ((block[p + 3].toInt() and 0xff) shl 24)
        }
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        for (i in 0 until 64) {
            val f = when {
                i < 16 -> (b and c) or (b.inv() and d)
                i < 32 -> (d and b) or (d.inv() and c)
                i < 48 -> b xor c xor d
                else -> c xor (b or d.inv())
            }
            val sum = f + a + KT[i] + m[G[i]]
            a = d; d = c; c = b
            b += rotl(sum, S[i])
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
    }

    fun digestHex(msg: ByteArray): String {
        // MD5's IV: 67452301 efcdab89 98badcfe 10325476, little-endian.
        val state = intArrayOf(0x67452301, -0x10325477, -0x67452302, 0x10325476)
        val remainder = msg.size % 64
        var i = 0
        while (i + 64 <= msg.size - remainder) { compress(state, msg, i); i += 64 }

        val tail = msg.copyOfRange(msg.size - remainder, msg.size)
        val padding = ByteArray(((56 - (tail.size + 1)) % 64 + 64) % 64)
        val bits = msg.size.toLong() * 8
        val length = ByteArray(8) { ((bits ushr (it * 8)) and 0xff).toByte() }
        val final = tail + byteArrayOf(0x80.toByte()) + padding + length
        var j = 0
        while (j < final.size) { compress(state, final, j); j += 64 }

        val sb = StringBuilder(32)
        state.forEach { word ->
            for (b in 0 until 4) sb.append("%02x".format((word ushr (b * 8)) and 0xff))
        }
        return sb.toString()
    }

    /** `sign2` for a session token and a moment in milliseconds. */
    fun signO3(token: String, startMoment: Long): String = digestHex(
        "token=$token&sign2_method=sign_o3&instance=0&start_moment=$startMoment"
            .toByteArray() + SALT
    )
}
