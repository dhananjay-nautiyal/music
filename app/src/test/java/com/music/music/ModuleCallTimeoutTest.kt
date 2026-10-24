package com.music.music

import com.music.music.data.sources.module.QuickJsExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module host's deadline, against the real VM (issue #15).
 *
 * A module stuck in a loop used to occupy one of its pool's three engines
 * forever: the call is a blocking native call, so neither the coroutine nor
 * the caller ever came back, and three such calls wedged the module for the
 * rest of the session. These drive exactly that module through the actual
 * executor, with the deadline set low enough to trip inside a test.
 */
class ModuleCallTimeoutTest {

    @After
    fun tearDown() {
        QuickJsExecutor.unloadAll()
        QuickJsExecutor.callTimeoutMillis = 30_000L
    }

    @Test
    fun `a hung call fails on the interpreter's deadline instead of never`() = runBlocking {
        loadHanging()
        val at = System.nanoTime()
        val result = call("hang")
        val tookMs = (System.nanoTime() - at) / 1_000_000
        assertTrue("expected a failure, got $result", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("the abort should name the interpreter's reason, got '$message'", "interrupted" in message)
        assertTrue("the deadline should trip in well under 10s, took ${tookMs}ms", tookMs < 10_000)
    }

    @Test
    fun `the aborted engine is replaced and the next call still runs`() = runBlocking {
        loadHanging()
        call("hang")
        val next = call("ok")
        assertTrue("the pool should recover with a fresh engine, got $next", next.isSuccess)
    }

    @Test
    fun `three hung calls do not wedge the module`() = runBlocking {
        loadHanging()
        // ENGINES_PER_MODULE is three, so under the old behaviour these three
        // held every engine forever and a fourth call blocked behind them.
        val hung = List(3) { async { call("hang") } }.awaitAll()
        assertTrue("all three hung calls should fail, got $hung", hung.all { it.isFailure })
        val next = call("ok")
        assertTrue("the module should still answer after three aborts, got $next", next.isSuccess)
    }

    @Test
    fun `a module that loops at load time fails its load instead of hanging`() = runBlocking {
        QuickJsExecutor.callTimeoutMillis = 500L
        val at = System.nanoTime()
        val result = QuickJsExecutor.loadModule("test-hanging-at-load", HANGING_AT_LOAD)
        val tookMs = (System.nanoTime() - at) / 1_000_000
        assertTrue("the load should fail, got $result", result.isFailure)
        assertTrue("the load abort should trip in well under 10s, took ${tookMs}ms", tookMs < 10_000)
    }

    /** Sets the deadline low enough for a test and loads the fixture module. */
    private suspend fun loadHanging() {
        QuickJsExecutor.callTimeoutMillis = 500L
        QuickJsExecutor.loadModule(MODULE_ID, HANGING_MODULE).getOrThrow()
    }

    private suspend fun call(query: String) = QuickJsExecutor.callExport(
        moduleId = MODULE_ID,
        functionName = "searchTracks",
        args = listOf(JsonPrimitive(query)),
    )

    /** Behaves unless asked to spin — one fixture for both sides of the line. */
    private val HANGING_MODULE = """
        module.exports = {
            searchTracks: async (query) => {
                if (query === "hang") { while (true) {} }
                return JSON.stringify({ ok: query });
            },
        };
    """.trimIndent()

    /** The load script itself never returns; the deadline has to cover it too. */
    private val HANGING_AT_LOAD = """
        while (true) {}
        module.exports = { searchTracks: async () => JSON.stringify({}) };
    """.trimIndent()

    private companion object {
        const val MODULE_ID = "test-hanging-module"
    }
}
