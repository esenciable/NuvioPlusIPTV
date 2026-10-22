package com.nuvio.tv.ext.livetv.magis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Vectors from the TS reference (`kino-light-addon/src/magis/crypto.test.ts` semantics):
 * the wire format is hex(base64(3DES-EDE/ECB/PKCS5(json))). The known wire vector was
 * computed with pycryptodome outside this repo so the test doesn't circularly depend on
 * the code under test.
 */
class MagisCryptoTest {

    // Real Magis 3DES master key shape: 24 bytes in hex (48 chars).
    private val key = "e7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfeb8"

    @Test
    fun `encryptBody produces the exact wire for a known vector`() {
        val plain = """{"hola":"mundo"}"""
        val expected = "336b6e7968596c346c48552f313276566c5134474951646642336151306b4f32"
        assertEquals(expected, MagisCrypto(key).encryptBody(plain))
    }

    @Test
    fun `decryptBlob reverses encryptBody`() {
        val plain = """{"hola":"mundo","nested":{"list":[1,2,3]},"unicode":"español"}"""
        val crypto = MagisCrypto(key)
        assertEquals(plain, crypto.decryptBlob(crypto.encryptBody(plain)))
    }

    @Test
    fun `decryptBlob reads the known wire`() {
        val wire = "336b6e7968596c346c48552f313276566c5134474951646642336151306b4f32"
        assertEquals("""{"hola":"mundo"}""", MagisCrypto(key).decryptBlob(wire))
    }

    @Test
    fun `the key must be 48 hex characters`() {
        assertThrows(IllegalArgumentException::class.java) { MagisCrypto("short") }
        assertThrows(IllegalArgumentException::class.java) {
            MagisCrypto("e7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfebZ") // 48 chars, not hex
        }
        assertThrows(IllegalArgumentException::class.java) {
            MagisCrypto(key + "aa") // 50 chars
        }
    }

    @Test
    fun `hex helpers round-trip`() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x7f, 0x80.toByte(), 0xfe.toByte(), 0xff.toByte())
        assertEquals("000f7f80feff", bytesToHex(bytes))
        assertArrayEquals(bytes, hexToBytes("000f7f80feff"))
        assertArrayEquals(ByteArray(0), hexToBytes(""))
    }
}
