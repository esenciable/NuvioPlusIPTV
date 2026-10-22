package com.nuvio.tv.ext.livetv.magis

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2 of the remote-config contract: the portal reads the config PER USE — a provider swap must
 * make the very next call send the NEW `apkVer`/`spkgVer`/`appId` headers and encrypt the body
 * with the NEW 3DES key, without any restart or re-construction.
 */
class MagisPortalConfigTest {

    private val keyA = "a7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfebf"
    private val keyB = "b7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfebf"

    private fun config(
        host: String,
        key: String,
        apkVerHeader: String,
        spkgVer: String,
    ) = MagisRuntimeConfig(
        hosts = listOf(host),
        appId = "com.android.msandroid",
        apkVersion = "49902",
        apkVerHeader = apkVerHeader,
        spkgVer = spkgVer,
        threeDesKeyHex = key,
    )

    private fun portalResponse() = MockResponse().setBody("""{"returnCode":"0"}""")

    @Test(timeout = 10_000)
    fun `a provider swap makes the next call send the NEW headers and key`() = runTest {
        val server = MockWebServer()
        server.enqueue(portalResponse())
        server.enqueue(portalResponse())
        server.start()
        try {
            val provider = MagisConfigProvider(
                config(
                    host = "${server.hostName}:${server.port}",
                    key = keyA,
                    apkVerHeader = "43404",
                    spkgVer = "2025-08-07 05:40:11_36_16_",
                ),
            )
            val holder = MagisCryptoHolder { provider.current.threeDesKeyHex }
            val portal = MagisPortalClient(
                configProvider = { provider.current },
                cryptoProvider = { holder.get() },
                scheme = "http",
            )

            // --- call 1: config A ---
            assertTrue(portal.call("v8/active", baseFields = false) is MagisResult.Ok)
            val r1 = server.takeRequest()
            assertEquals("com.android.msandroid", r1.getHeader("apk"))
            assertEquals("43404", r1.getHeader("apkVer"))
            assertEquals("2025-08-07 05:40:11_36_16_", r1.getHeader("spkgVer"))
            val dict1 = JSONObject(MagisCrypto(keyA).decryptBlob(r1.body.readUtf8()))
            assertEquals("49902", dict1.getString("apkVersion"))
            assertEquals("2025-08-07 05:40:11_36_16_", dict1.getString("sysVersion"))

            // --- swap: same portal instance, same holder, NEW config ---
            provider.update(
                config(
                    host = "${server.hostName}:${server.port}",
                    key = keyB,
                    apkVerHeader = "43405",
                    spkgVer = "2026-01-01 00:00:00_36_16_",
                ),
            )

            // --- call 2: config B, no restart ---
            assertTrue(portal.call("v8/active", baseFields = false) is MagisResult.Ok)
            val r2 = server.takeRequest()
            assertEquals("43405", r2.getHeader("apkVer"))
            assertEquals("2026-01-01 00:00:00_36_16_", r2.getHeader("spkgVer"))
            // The body must now decrypt with the NEW key: a rotation lands on the next call.
            val dict2 = JSONObject(MagisCrypto(keyB).decryptBlob(r2.body.readUtf8()))
            assertEquals("49902", dict2.getString("apkVersion"))
            assertEquals("2026-01-01 00:00:00_36_16_", dict2.getString("sysVersion"))
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 10_000)
    fun `a config without a usable key surfaces as RedError instead of crashing`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val provider = MagisConfigProvider(
                config("${server.hostName}:${server.port}", key = "no-es-hex", "43404", "2025-08-07"),
            )
            val holder = MagisCryptoHolder { provider.current.threeDesKeyHex }
            val portal = MagisPortalClient(
                configProvider = { provider.current },
                cryptoProvider = { holder.get() },
                scheme = "http",
            )

            val result = portal.call("v8/active", baseFields = false)

            assertTrue(result is MagisResult.RedError)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 10_000)
    fun `deviceDict carries the rotating fields from the live config`() = runTest {
        val server = MockWebServer()
        server.enqueue(portalResponse())
        server.start()
        try {
            val provider = MagisConfigProvider(
                config(
                    host = "${server.hostName}:${server.port}",
                    key = keyA,
                    apkVerHeader = "43404",
                    spkgVer = "2025-08-07 05:40:11_36_16_",
                ).copy(apkVersion = "49903", appId = "otro.appId"),
            )
            val portal = MagisPortalClient(
                configProvider = { provider.current },
                cryptoProvider = { MagisCrypto(provider.current.threeDesKeyHex) },
                scheme = "http",
            )

            portal.call("v8/active", baseFields = false)

            val dict = JSONObject(MagisCrypto(keyA).decryptBlob(server.takeRequest().body.readUtf8()))
            assertEquals("49903", dict.getString("apkVersion"))
            assertEquals("otro.appId", dict.getString("appId"))
        } finally {
            server.shutdown()
        }
    }
}
