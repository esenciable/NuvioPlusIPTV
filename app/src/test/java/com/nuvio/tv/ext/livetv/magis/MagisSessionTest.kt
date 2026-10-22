package com.nuvio.tv.ext.livetv.magis

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session lifecycle: the deadlock class of bugs lives here. `reauthenticate` must not hold the
 * session mutex while calling `ensureAnonymous` (which takes it itself) — kotlinx Mutex is not
 * reentrant, and that exact shape hung `withValidSession` forever the first time the portal
 * rejected a call with the session alive (observed on device: Live TV stuck on "Looking for
 * channels..." with no error and no timeout).
 */
class MagisSessionTest {

    /** In-memory store: the real one is DataStore-backed and needs an Android context. */
    private class FakeStore : MagisSessionStore {
        var current: StoredSession? = null
        override fun save(s: StoredSession) { current = s }
        override fun read(): StoredSession? = current
    }

    /**
     * Scripted portal: answers per-path, recording every call. The default answer for
     * `v8/active` mints a session, so the happy path behaves like the real portal.
     */
    private class FakePortal(
        /** path -> answer; anything unlisted falls back to [fallback]. */
        private val script: Map<String, (MagisResult<JSONObject>) -> MagisResult<JSONObject>> = emptyMap(),
    ) : MagisPortalLike {
        val calls = mutableListOf<String>()
        var activeCallCount = 0
        private fun ok(vararg pairs: Pair<String, Any?>) =
            MagisResult.Ok(
                JSONObject(
                    mapOf(
                        "userId" to "u1",
                        "userToken" to "t1",
                        // mintDevice reads this from v3/snToken's answer.
                        "snToken" to "ST-fake",
                    ) + pairs
                )
            )
        override suspend fun call(
            path: String,
            bean: Map<String, Any?>,
            baseFields: Boolean,
            userId: String,
            userToken: String,
            sn: String?,
        ): MagisResult<JSONObject> {
            calls += path
            if (path == "v8/active") activeCallCount++
            val answer = script[path]?.invoke(ok()) ?: ok()
            return answer
        }
    }

    private fun session(portal: MagisPortalLike, store: FakeStore = FakeStore()) =
        MagisSession(portal, store)

    @Test(timeout = 5_000)
    fun `withValidSession recovers after a portal rejection instead of deadlocking`() = runTest {
        // First categories() call rejected (any PortalError triggers the reauth path), the
        // retry succeeds — the exact shape that hung on device.
        val portal = FakePortal(
            script = mapOf(
                "getSlbInfoCategory" to { MagisResult.PortalError("portal100001", "rejected") },
            ),
        )
        val s = session(portal)
        var attempts = 0
        val result = s.withValidSession {
            attempts++
            if (attempts == 1) MagisResult.PortalError("portal100001", "rejected")
            else MagisResult.Ok(JSONObject("""{"ok":true}"""))
        }

        // The block itself is not a portal call: the rejection path must reauth (mint/activate)
        // and run the block exactly twice.
        assertTrue("debe reintentar tras reautenticar", attempts == 2)
        assertTrue(result is MagisResult.Ok)
        // Reauth mints: one snToken + one activate.
        assertTrue(portal.calls.contains("v3/snToken"))
        assertTrue(portal.calls.contains("v8/active"))
    }

    @Test(timeout = 5_000)
    fun `withValidSession keeps the first error when reauthentication fails`() = runTest {
        val portal = FakePortal(
            script = mapOf(
                // Reauth's mint itself is rejected: the original error is what must surface.
                "v3/snToken" to { MagisResult.PortalError("portal200001", "版本已停止使用") },
            ),
        )
        val s = session(portal)
        var attempts = 0
        val result = s.withValidSession {
            attempts++
            MagisResult.PortalError("aaa100027", "token caducado")
        }

        assertTrue("no reintenta si la reautenticación falla", attempts == 1)
        assertTrue(result is MagisResult.PortalError)
        assertEquals("aaa100027", (result as MagisResult.PortalError).code)
    }

    @Test(timeout = 5_000)
    fun `ensureAnonymous is idempotent while the session is alive`() = runTest {
        val portal = FakePortal()
        val s = session(portal)

        assertTrue(s.ensureAnonymous() is MagisResult.Ok)
        val mintsAfterFirst = portal.activeCallCount
        assertTrue(s.ensureAnonymous() is MagisResult.Ok)
        assertEquals("una sesión viva no reminta", mintsAfterFirst, portal.activeCallCount)
    }
}
