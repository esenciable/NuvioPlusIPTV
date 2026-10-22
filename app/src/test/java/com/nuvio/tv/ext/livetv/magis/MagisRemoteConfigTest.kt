package com.nuvio.tv.ext.livetv.magis

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote config contract (`odd/tasks/magis-remote-config.md`): all-or-nothing validation,
 * first-valid-wins resolution (disk -> remote -> BuildConfig), and "a failed fetch keeps the
 * good config and arms no TTL".
 */
class MagisRemoteConfigTest {

    private class FakeStore(initial: MagisRuntimeConfig? = null) : MagisConfigStore {
        var current: MagisRuntimeConfig? = initial
        var saveCount = 0
        override fun save(config: MagisRuntimeConfig) {
            current = config
            saveCount++
        }
        override fun read(): MagisRuntimeConfig? = current
    }

    private val diskKey = "e7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfebf"
    private val remoteKey = "a7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfebf"

    private val diskConfig = MagisRuntimeConfig(
        hosts = listOf("disk.host"),
        appId = "com.android.msandroid",
        apkVersion = "49902",
        apkVerHeader = "43404",
        spkgVer = "2025-08-07 05:40:11_36_16_",
        threeDesKeyHex = diskKey,
    )

    private fun validDoc(
        host: String = "remote.host",
        apkVerHeader: String = "43405",
        spkgVer: String = "2026-01-01 00:00:00_36_16_",
        key: String = remoteKey,
        appId: String = "com.android.msandroid",
        apkVersion: String = "49902",
    ) = """
        {
          "schema": 1,
          "updatedAt": "2026-10-07",
          "hosts": ["$host"],
          "appId": "$appId",
          "apkVersion": "$apkVersion",
          "apkVerHeader": "$apkVerHeader",
          "spkgVer": "$spkgVer",
          "threeDesKeyHex": "$key"
        }
    """.trimIndent()

    // --- validation matrix: ALL-OR-NOTHING -----------------------------------

    @Test(timeout = 5_000)
    fun `valid document parses into a config`() {
        val config = parseMagisConfig(validDoc())
        assertNotNull(config)
        config!!
        assertEquals(listOf("remote.host"), config.hosts)
        assertEquals("com.android.msandroid", config.appId)
        assertEquals("49902", config.apkVersion)
        assertEquals("43405", config.apkVerHeader)
        assertEquals("2026-01-01 00:00:00_36_16_", config.spkgVer)
        assertEquals(remoteKey, config.threeDesKeyHex)
    }

    @Test(timeout = 5_000)
    fun `each invalid field rejects the WHOLE document`() {
        val mutations: Map<String, (String) -> String> = mapOf(
            "schema" to { it.replace("\"schema\": 1", "\"schema\": 2") },
            "schema ausente" to { it.replace("\"schema\": 1,", "") },
            "hosts vacío" to { it.replace("[\"remote.host\"]", "[]") },
            "host con esquema" to { it.replace("remote.host", "https://remote.host") },
            "host con barra" to { it.replace("remote.host", "remote.host/api") },
            "appId vacío" to { it.replace("\"appId\": \"com.android.msandroid\"", "\"appId\": \"\"") },
            "apkVersion con letras" to {
                it.replace("\"apkVersion\": \"49902\"", "\"apkVersion\": \"49902a\"")
            },
            "apkVerHeader con letras" to {
                it.replace("\"apkVerHeader\": \"43405\"", "\"apkVerHeader\": \"43.4\"")
            },
            "spkgVer vacío" to { it.replace("\"spkgVer\": \"2026-01-01 00:00:00_36_16_\"", "\"spkgVer\": \"\"") },
            "clave corta" to {
                it.replace("\"threeDesKeyHex\": \"$remoteKey\"", "\"threeDesKeyHex\": \"aabb\"")
            },
            "clave mayúscula" to {
                it.replace(remoteKey, remoteKey.uppercase())
            },
            "clave no hex" to {
                it.replace(remoteKey, "z" + remoteKey.substring(1))
            },
        )
        mutations.forEach { (name, mutate) ->
            assertNull("debe rechazar: $name", parseMagisConfig(mutate(validDoc())))
        }
    }

    @Test(timeout = 5_000)
    fun `malformed JSON and null hosts are rejected`() {
        assertNull(parseMagisConfig("no es json"))
        assertNull(parseMagisConfig("""{"schema": 1, "hosts": null}"""))
    }

    // --- startup resolution: disk beats BuildConfig --------------------------

    @Test(timeout = 5_000)
    fun `readOr prefers the disk cache over the BuildConfig fallback`() {
        val buildConfigFallback = diskConfig.copy(hosts = listOf("baked.host"))
        assertEquals(diskConfig, FakeStore(diskConfig).readOr(buildConfigFallback))
    }

    @Test(timeout = 5_000)
    fun `readOr falls back to BuildConfig when the disk cache is empty`() {
        val buildConfigFallback = diskConfig.copy(hosts = listOf("baked.host"))
        assertEquals(buildConfigFallback, FakeStore(null).readOr(buildConfigFallback))
    }

    @Test(timeout = 5_000)
    fun `a corrupt disk cache falls back to BuildConfig instead of crashing`() {
        val store = object : MagisConfigStore {
            var doc: String? = "{\"schema\": 1, \"hosts\": [\"h\"]" // truncated
            override fun save(config: MagisRuntimeConfig) {
                doc = config.toJson()
            }
            override fun read(): MagisRuntimeConfig? = doc?.let { parseMagisConfig(it) }
        }
        val fallback = diskConfig.copy(hosts = listOf("baked.host"))
        assertEquals(fallback, store.readOr(fallback))
    }

    // --- remote adoption ------------------------------------------------------

    @Test(timeout = 5_000)
    fun `a valid remote beats disk and BuildConfig and is persisted`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(validDoc()))
        server.start()
        try {
            val store = FakeStore(diskConfig)
            val provider = MagisConfigProvider(diskConfig)
            val remote = MagisRemoteConfig(provider, store, url = server.url("/magis-config.json").toString())

            remote.refreshIfNeeded()

            assertEquals(listOf("remote.host"), provider.current.hosts)
            assertEquals("43405", provider.current.apkVerHeader)
            assertEquals(remoteKey, provider.current.threeDesKeyHex)
            assertEquals(listOf("remote.host"), store.current!!.hosts)
            assertEquals(1, store.saveCount)
            assertEquals("/magis-config.json", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5_000)
    fun `an equal remote result is adopted without a disk rewrite`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                validDoc(
                    host = "disk.host",
                    apkVerHeader = diskConfig.apkVerHeader,
                    spkgVer = diskConfig.spkgVer,
                    key = diskKey,
                ),
            ),
        )
        server.start()
        try {
            val store = FakeStore(diskConfig)
            val provider = MagisConfigProvider(diskConfig)
            val remote = MagisRemoteConfig(provider, store, url = server.url("/c.json").toString())

            remote.refreshIfNeeded()

            assertEquals(diskConfig, provider.current)
            assertEquals(0, store.saveCount)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5_000)
    fun `an INVALID remote document keeps the previous config and arms no TTL`() = runTest {
        val server = MockWebServer()
        // Same document but with a broken key: the whole document must be rejected. Two
        // responses: the second proves a rejected attempt armed no TTL.
        server.enqueue(MockResponse().setBody(validDoc(key = "no-valida")))
        server.enqueue(MockResponse().setBody(validDoc(key = "no-valida")))
        server.start()
        try {
            val store = FakeStore(diskConfig)
            val provider = MagisConfigProvider(diskConfig)
            val remote = MagisRemoteConfig(provider, store, url = server.url("/c.json").toString())

            remote.refreshIfNeeded()

            assertSame(diskConfig, provider.current)
            assertEquals(0, store.saveCount)
            // No TTL armed: an immediate second attempt DOES hit the network again.
            remote.refreshIfNeeded()
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5_000)
    fun `a FAILED fetch keeps the good config and retries instead of waiting the interval`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(validDoc()))
        server.start()
        try {
            val store = FakeStore(diskConfig)
            val provider = MagisConfigProvider(diskConfig)
            val remote = MagisRemoteConfig(provider, store, url = server.url("/c.json").toString())

            remote.refreshIfNeeded()
            assertSame("el fetch fallido no borra la config buena", diskConfig, provider.current)
            assertEquals(0, store.saveCount)

            // The failure armed NOTHING: the retry right after succeeds and adopts the remote.
            remote.refreshIfNeeded()
            assertEquals(listOf("remote.host"), provider.current.hosts)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5_000)
    fun `a valid refresh is throttled to once per interval`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(validDoc()))
        server.start()
        try {
            val provider = MagisConfigProvider(diskConfig)
            val remote = MagisRemoteConfig(provider, FakeStore(), url = server.url("/c.json").toString())

            remote.refreshIfNeeded()
            remote.refreshIfNeeded() // inside the interval: must NOT fetch again

            assertEquals(listOf("remote.host"), provider.current.hosts)
            assertEquals(1, server.requestCount)
            assertTrue(!server.takeRequest().path.isNullOrBlank())
        } finally {
            server.shutdown()
        }
    }

    // --- crypto holder: rebuild ONLY on key change ----------------------------

    @Test(timeout = 5_000)
    fun `MagisCryptoHolder rebuilds only when the key changes`() {
        val provider = MagisConfigProvider(diskConfig)
        val holder = MagisCryptoHolder { provider.current.threeDesKeyHex }
        val first = holder.get()
        assertNotNull(first)
        assertSame("misma clave: sin rebuild", first, holder.get())

        provider.update(diskConfig.copy(threeDesKeyHex = "b7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfebf"))
        val second = holder.get()
        assertNotNull(second)
        assertFalse("clave rotada: nueva instancia", first === second)
        assertSame("clave nueva estable: sin rebuild por llamada", second, holder.get())
    }
}
