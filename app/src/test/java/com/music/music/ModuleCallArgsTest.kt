package com.music.music

import com.music.music.data.sources.module.QuickJsExecutor
import com.music.music.data.sources.module.contextArg
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The contract between the host and module JavaScript: arguments are data.
 *
 * The host used to build the call by hand — pre-quoting each argument and
 * pasting the result into the script — so the query text itself was one `"`
 * away from ending the string literal (issue #14). These tests run the real
 * QuickJS VM (the JVM artifact of the same engine the app ships) against a
 * module that echoes back what it received, which is the only way to prove
 * both halves of the fix: the value arrives byte-for-byte intact, and nothing
 * in it was ever executed.
 */
class ModuleCallArgsTest {

    private val json = Json

    @After
    fun tearDown() {
        // Every test gets its own engines; none may survive into the next one,
        // least of all if a hostile query ever did manage to set a global.
        QuickJsExecutor.unloadAll()
    }

    // ── The regression the issue names ─────────────────────────────────────

    @Test
    fun `a query with a quote backslash and newline arrives intact`() = runBlocking {
        loadEcho()
        // The exact shape that failed: a title containing a double quote, plus
        // the characters a string literal must carry through — backslash, real
        // newline, tab, and a // that would comment out the rest of the line
        // if it ever arrived unquoted.
        val query = "Don't \"Stop\" Me\\Now\n(2011 remaster)\t//live"
        val echo = search(query)
        assertEquals(query, echo.query)
        assertEquals("string", echo.queryType)
    }

    // ── The security half ──────────────────────────────────────────────────

    @Test
    fun `a query cannot inject statements into the module's script`() = runBlocking {
        loadEcho()
        // Closes the argument literal, calls its own code in the module's
        // context, then reopens a literal so the call still parses. Under the
        // old splicing this ran; now it can only be a string.
        val query = """x"), (function(){ globalThis.__pwned = 1; return "y"; })(), ("z"""
        val echo = search(query)
        assertEquals(query, echo.query)
        assertEquals("undefined", echo.injected)
    }

    @Test
    fun `a setting value with a quote and a newline survives the round trip`() = runBlocking {
        loadEcho()
        // The context object was built by string concatenation too, so a
        // module setting holding a quote broke the same way a query did.
        val artist = "Queen \"Live\"\nA Night at the Opera\\"
        val echo = search("queen", mapOf("quality" to "320 kbps", "artist" to artist))
        assertEquals(artist, echo.artist)
        assertEquals("320 kbps", echo.quality)
    }

    @Test
    fun `a track id carrying quotes arrives intact`() = runBlocking {
        loadEcho()
        // Track ids are whatever a module's backend produced — server-side
        // text, not something the host ever validated.
        val trackId = """colombia:"6x_9"-take#2\live"""
        val result = QuickJsExecutor.callExport(
            moduleId = ECHO_MODULE_ID,
            functionName = "getTrackStreamUrl",
            args = listOf(JsonPrimitive(trackId), JsonPrimitive("HIGH"), contextArg(mapOf("quality" to "HIGH"))),
        ).getOrThrow()
        val echo = json.decodeFromString<StreamEcho>(result)
        assertEquals(trackId, echo.trackId)
        assertEquals("HIGH", echo.quality)
    }

    // ── The contract a naive fix would break ───────────────────────────────

    @Test
    fun `argument types the module contract promises are unchanged`() = runBlocking {
        loadEcho()
        val echo = search("queen")
        assertEquals(20, echo.limit)
        assertEquals("number", echo.limitType)
        assertEquals("object", echo.contextType)
    }

    // ── The context object, without needing the VM ─────────────────────────

    @Test
    fun `the context argument is built by an encoder, not by concatenation`() {
        val hostile = "a\"b\\c\nd e"
        val encoded = contextArg(mapOf("k" to hostile)).toString()
        // Strict JSON: the hand-built `{settings:{…}}` could not parse at all.
        val shape = json.decodeFromString<ContextShape>(encoded)
        assertEquals(hostile, shape.settings.getValue("k").value)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private suspend fun loadEcho() {
        QuickJsExecutor.loadModule(ECHO_MODULE_ID, ECHO_MODULE).getOrThrow()
    }

    private suspend fun search(
        query: String,
        settings: Map<String, String> = mapOf("quality" to "320 kbps", "artist" to "Queen"),
    ): SearchEcho {
        val result = QuickJsExecutor.callExport(
            moduleId = ECHO_MODULE_ID,
            functionName = "searchTracks",
            args = listOf(JsonPrimitive(query), JsonPrimitive(20), contextArg(settings)),
        ).getOrThrow()
        return json.decodeFromString<SearchEcho>(result)
    }

    /**
     * A module in the shape the real ones are in, whose only job is to report
     * what the host handed it — including the JS type of each argument, and
     * whether any code from the query ever got to run.
     */
    private val ECHO_MODULE = """
        module.exports = {
            searchTracks: async (query, limit, context) => JSON.stringify({
                query: query,
                queryType: typeof query,
                limit: limit,
                limitType: typeof limit,
                quality: context.settings.quality.value,
                artist: context.settings.artist.value,
                contextType: typeof context,
                injected: typeof globalThis.__pwned,
            }),
            getTrackStreamUrl: async (trackId, quality, context) => JSON.stringify({
                trackId: trackId,
                quality: quality,
                contextQuality: context.settings.quality.value,
            }),
        };
    """.trimIndent()

    @Serializable
    private data class SearchEcho(
        val query: String,
        val queryType: String,
        val limit: Int,
        val limitType: String,
        val quality: String,
        val artist: String,
        val contextType: String,
        val injected: String,
    )

    @Serializable
    private data class StreamEcho(
        val trackId: String,
        val quality: String,
        val contextQuality: String,
    )

    @Serializable
    private data class ContextShape(val settings: Map<String, SettingShape>)

    @Serializable
    private data class SettingShape(val value: String)

    private companion object {
        const val ECHO_MODULE_ID = "test-echo-module"
    }
}
