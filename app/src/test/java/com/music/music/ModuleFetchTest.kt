package com.music.music

import com.music.music.data.sources.module.ModuleFetchPolicy
import com.music.music.data.sources.module.QuickJsExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fetch policy, driven through the real binding a module actually gets
 * (issue #16).
 *
 * Each case asks module JavaScript to `fetch(...)` and reads back what the
 * call wrapper wrote — the same `{"error": ...}` payload a hostile module
 * would see, which is where the policy's refusal messages land. The server
 * is a real MockWebServer on loopback, which is exactly the kind of target
 * the policy must keep *working*: the point is not to break self-hosted
 * addons, it is to refuse everything past them.
 */
class ModuleFetchTest {

    @After
    fun tearDown() {
        QuickJsExecutor.unloadAll()
        QuickJsExecutor.maxResponseBytes = ModuleFetchPolicy.MAX_RESPONSE_BYTES
    }

    @Test
    fun `a fetch to the local server works`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("hello world"))

            val payload = callFetch(server.url("/hello").toString())
            assertTrue("payload should carry the body: $payload", "hello world" in payload)
            assertTrue("payload should carry the status: $payload", "\"status\":200" in payload)
        }
    }

    @Test
    fun `http to a public address is refused`() = runBlocking {
        // An IP literal needs no DNS: the refusal happens before a socket
        // is opened, which is the whole point.
        val payload = callFetch("http://93.184.216.34/x")
        assertTrue("payload should refuse it: $payload", "refused" in payload)
    }

    @Test
    fun `the link-local metadata block is refused even over https`() = runBlocking {
        val payload = callFetch("https://169.254.169.254/latest/meta-data/")
        assertTrue("payload should name the rule: $payload", "link-local" in payload)
    }

    @Test
    fun `a redirect into the metadata block dies at the second hop`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://169.254.169.254/latest/meta-data/"),
            )

            val payload = callFetch(server.url("/trap").toString())
            assertTrue("payload should name the rule: $payload", "link-local" in payload)
            // The first hop was local and fine; the refused second hop never
            // reached the network.
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `a relative redirect to another local path is followed`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/next"))
            server.enqueue(MockResponse().setBody("final answer"))

            val payload = callFetch(server.url("/start").toString())
            assertTrue("payload should carry the second hop: $payload", "final answer" in payload)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `a redirect loop gives up at the bound instead of running forever`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(8) {
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/loop"))
            }

            val payload = callFetch(server.url("/loop-start").toString())
            assertTrue("payload should give up: $payload", "redirected more than" in payload)
            assertEquals(ModuleFetchPolicy.MAX_REDIRECTS + 1, server.requestCount)
        }
    }

    @Test
    fun `a declared oversize response is refused before a byte is read`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            QuickJsExecutor.maxResponseBytes = 1024L
            server.enqueue(MockResponse().setBody("x".repeat(4096)))

            val payload = callFetch(server.url("/big").toString())
            assertTrue("payload should refuse it: $payload", "cap" in payload)
        }
    }

    @Test
    fun `an undeclared chunked response is cut off at the cap`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            QuickJsExecutor.maxResponseBytes = 1024L
            // Chunked means no Content-Length: the declared-size check has
            // nothing to catch, so this exercises the byte-by-byte count.
            server.enqueue(MockResponse().setChunkedBody("y".repeat(4096), 512))

            val payload = callFetch(server.url("/huge").toString())
            assertTrue("payload should cut it off: $payload", "cap" in payload)
        }
    }

    /** Loads the fixture module and runs one fetch through the binding. */
    private suspend fun callFetch(url: String): String {
        QuickJsExecutor.loadModule(MODULE_ID, FETCHING_MODULE).getOrThrow()
        return QuickJsExecutor.callExport(
            moduleId = MODULE_ID,
            functionName = "searchTracks",
            args = listOf(JsonPrimitive(url)),
        ).getOrThrow()
    }

    /** Plain on the outside — what it fetches is the argument, which is the point. */
    private val FETCHING_MODULE = """
        module.exports = {
            searchTracks: async (url) => {
                var r = await fetch(url);
                return JSON.stringify({ status: r.status, body: r.text() });
            },
        };
    """.trimIndent()

    private companion object {
        const val MODULE_ID = "test-fetch-policy-module"
    }
}
