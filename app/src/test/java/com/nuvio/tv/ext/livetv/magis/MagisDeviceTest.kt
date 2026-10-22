package com.nuvio.tv.ext.livetv.magis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device fingerprint is pure: no Android APIs, so it can be tested on the JVM. */
class MagisDeviceTest {

    @Test
    fun `the fingerprint carries the emulator's fixed identity fields`() {
        val fp = MagisDevice.hardwareFingerprint()
        assertEquals("ranchu", fp["hardware"])
        assertEquals("goldfish_arm64", fp["board"])
        assertEquals("google", fp["brand"])
        assertEquals("Google", fp["manufacturer"])
        assertEquals("arm64-v8a", fp["cpuAbi"])
        assertEquals("emu64a", fp["device"])
    }

    @Test
    fun `the identifying fields are randomized per fingerprint`() {
        val a = MagisDevice.hardwareFingerprint()
        val b = MagisDevice.hardwareFingerprint()
        // Two minted devices must never be the same device: the portal hands out one device
        // per fingerprint.
        assertNotEquals(a["androidId"], b["androidId"])
        assertNotEquals(a["serialNumber"], b["serialNumber"])
        assertNotEquals(a["wifiMac"], b["wifiMac"])
        assertNotEquals(a["etheMac"], b["etheMac"])
        assertNotEquals(a["gatewayMac"], b["gatewayMac"])
    }

    @Test
    fun `random fields are well-formed`() {
        val fp = MagisDevice.hardwareFingerprint()
        val hexFields = listOf("androidId", "cpuId", "serialNumber")
        hexFields.forEach { key ->
            val value = fp[key] as String
            assertEquals("field $key length", 16, value.length)
            assertTrue("field $key is hex", value.all { it in "0123456789abcdef" })
        }
        listOf("wifiMac", "etheMac", "gatewayMac").forEach { key ->
            val value = fp[key] as String
            val parts = value.split(":")
            assertEquals("field $key has 6 octets", 6, parts.size)
            parts.forEach { octet ->
                assertEquals("octet length in $key", 2, octet.length)
                assertTrue("octet is hex in $key", octet.all { it in "0123456789abcdef" })
            }
        }
    }

    @Test
    fun `the fixed MAC is the one the original app sends`() {
        assertEquals("02:00:00:00:00:00", MagisDevice.FIXED_MAC)
    }

    @Test
    fun `the sn seed is md5 of snToken plus the salt, lowercase`() {
        // Vector computed externally: md5("someTestToken" + SNTOKEN_SALT).
        assertEquals(
            "f0eaea3b1d90bb24d28e492feab5c93a",
            MagisDevice.snFromSnToken("someTestToken"),
        )
        // The portal's own sn always wins over the derived one.
        assertEquals(
            "fromportal",
            MagisDevice.snFromSnToken("someTestToken", portalSn = "  fromportal\n"),
        )
    }
}
