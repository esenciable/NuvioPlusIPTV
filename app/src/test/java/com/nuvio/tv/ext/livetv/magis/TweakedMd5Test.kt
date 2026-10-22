package com.nuvio.tv.ext.livetv.magis

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The five acceptance vectors captured from the real app (see the TS reference
 * `kino-light-addon/src/magis/live-signature.test.ts` and the verified Kotlin original
 * `kino-light-main/.../TweakedMd5Test.kt`), plus the padding-edge vectors captured from
 * the reference implementation.
 */
class TweakedMd5Test {

    private val vectors = listOf(
        Triple("941d98961990d67e249dcd1ac57378c8", 1786228951248L, "42eda1217c11706f8034f00831f11645"),
        Triple("941d98961990d67e249dcd1ac57378c8", 1786229028826L, "7b7a1751bd8dc9fa4cb38bcc8dd8acb3"),
        Triple("941d98961990d67e249dcd1ac57378c8", 1786229709567L, "0cccdfc85f900a6ee407eedd13003494"),
        Triple("c3ec544b53a526c59ab677ffbdffa1e0", 1786223278615L, "2e055d6f2c0407c82017286e8f4a31ad"),
        Triple("c3ec544b53a526c59ab677ffbdffa1e0", 1786225491689L, "095a0c6ebc25e6570705fd9d16c6b67b"),
    )

    @Test
    fun `the five real vectors`() {
        vectors.forEach { (token, moment, expected) ->
            assertEquals("moment $moment", expected, TweakedMd5.signO3(token, moment))
        }
    }

    @Test
    fun `the padding boundaries don't shift`() {
        // 55 and 56 bytes are the edge where padding starts needing an extra block (63/64/65
        // cover the analogous edge one block further on). Real output of
        // `digest_hex(bytes(n))` in the reference implementation verified against the
        // proprietary binary (see kino-light-main's TweakedMd5Test for how they were captured).
        val paddingVectors = mapOf(
            0 to "788eb771bc499f0bc7f00fdb08c397aa",
            55 to "3df0dbf8fb79a50d50d4d1d95a40942c",
            56 to "0e0b477553c03363f907a303756fb565",
            63 to "16b54eb04d82dee39edc72de0532523d",
            64 to "acd46d59775f5cd639b96b2d1a4dc020",
            65 to "233f868f6402130ab977de8bd5d2b943",
        )
        paddingVectors.forEach { (n, expected) ->
            assertEquals("length $n", expected, TweakedMd5.digestHex(ByteArray(n)))
        }
    }

    @Test
    fun `it's not standard MD5`() {
        // If someone "fixes" the tweaked constants thinking they're typos, this catches it.
        val md5 = java.security.MessageDigest.getInstance("MD5")
            .digest(ByteArray(64)).joinToString("") { "%02x".format(it) }
        org.junit.Assert.assertNotEquals(md5, TweakedMd5.digestHex(ByteArray(64)))
    }
}
