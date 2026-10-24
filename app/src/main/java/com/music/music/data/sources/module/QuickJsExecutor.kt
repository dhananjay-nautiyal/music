package com.music.music.data.sources.module

import android.util.Log
import com.music.music.data.TrackLog
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.Locale

/**
 * Sandboxed QuickJS engine pool for executing module JS.
 *
 * Modules commonly stash session/auth state in top-level variables set up
 * once at load time (e.g. an eager token pre-fetch). The old one-shot
 * executor re-evaluated the whole script on every function call, so that
 * state never survived from `searchTracks()` to `getStreamUrl()` — each
 * ran in its own throwaway VM. Keeping one engine alive per loaded module,
 * reused across calls, is what a normal module host does and what these
 * modules assume. LRU-capped at [MAX_MODULES]; least-recently-used is
 * evicted to make room rather than refusing to load past the cap.
 *
 * ### Why a module gets several engines and not one
 *
 * A QuickJS VM is a single-threaded interpreter and [callExport] drives it
 * across two `evaluate` calls with the answer parked in a global in between.
 * Both of those make one engine strictly one call at a time: two callers
 * sharing it would interleave their `evaluate`s inside the interpreter, and
 * even if they didn't, the second would overwrite `__spine_resolved_json`
 * before the first had read it — so one track's download would quietly receive
 * another track's stream URL. That was survivable while downloads drained one
 * at a time and playback asked for one track at a time; it is not survivable
 * with several downloads in flight.
 *
 * Serialising on a single engine would be correct and would also give all the
 * concurrency back, since a module's own HTTP fetches happen *inside* the VM
 * and hold it for their whole duration — measured at 7.7s for one module on
 * one query. So each module gets a small pool instead: [ENGINES_PER_MODULE]
 * independent VMs, each handed to one caller at a time, growing lazily so a
 * module nobody is hammering still costs exactly one.
 *
 * Ported from Convx's `QuickJsExecutor`, with the pool added.
 */
internal object QuickJsExecutor {

    private const val TAG = "Music"

    /**
     * How many modules stay resident.
     *
     * Has to cover a whole index, not a working set. A search fans out to
     * *every* module at once, so a cap below the index size means each search
     * evicts engines the same search is still using — and the eviction is
     * invisible to [ModuleManager], which caches the load separately and goes
     * on reporting a hit for an engine that is gone. What that produced was
     * `Module … is not loaded` on the stream call for whichever modules lost
     * the race, one wasted attempt per track, on every track. Four was the
     * ported default and is below the size of a real index.
     */
    private const val MAX_MODULES = 12

    /**
     * How many callers one module can serve at once.
     *
     * Each is a whole interpreter with the module's script evaluated into it,
     * so this is not free — and each costs whatever the module does at load
     * time, which for several of them is an eager token fetch. Three is sized
     * against the download queue's own width: enough that the workers are not
     * queueing behind each other on the one slow module in the index, few
     * enough that a three-module index is nine VMs and not thirty.
     */
    private const val ENGINES_PER_MODULE = 3

    /**
     * How long one call into module JavaScript may run, enforced by the
     * interpreter itself (issue #15).
     *
     * Not a coroutine deadline: the call is a blocking native call, and
     * cancelling the coroutine does nothing while the interpreter sits inside
     * `while (true)` — the engine, and the Dispatchers.Default thread under
     * it, stay busy forever. The library's own interrupt handler aborts the
     * evaluate at this deadline with "interrupted", and that is what actually
     * brings the thread back; a call that trips it fails, and its engine is
     * dropped rather than returned (see [replace]). Sized well above the
     * ~7.7s worst measured module call so a slow module on a bad network
     * still works — agreed on the issue at 30s. It is set before the module's
     * own script is evaluated, so a module whose top level loops forever
     * fails its load instead of wedging an engine at load.
     *
     * Mutable only so tests can trip the deadline without waiting 30s.
     */
    internal var callTimeoutMillis = 30_000L

    /**
     * Per-interpreter JS heap ceiling — the "memory ceiling" the issue found
     * missing beside the timeout.
     *
     * QuickJS aborts the running script when its allocations pass this, so a
     * module building an ever-growing array fails its call with "out of
     * memory" instead of taking the whole app process down with it. 64MB is
     * far above anything a search/stream module holds — a ten-thousand-track
     * response is a few MB — and far below what a runaway loop can reach.
     */
    private const val MEMORY_LIMIT_BYTES = 64L * 1024 * 1024

    /**
     * One module's interpreters, and the source to make more from.
     *
     * [free] is the ones nobody is inside right now. It is unbounded and only
     * ever holds engines this pool made, so a send to it cannot fail or block;
     * a receive is a caller waiting its turn.
     */
    private class Pool(val jsCode: String, val fetchBase: String) {
        val free = Channel<QuickJs>(Channel.UNLIMITED)
        val lock = Mutex()
        /** Engines made or being made — the claim that stops two callers both growing the pool. */
        var started = 0
        /** Engines actually made, for closing them on unload. Guarded by [lock]. */
        val made = mutableListOf<QuickJs>()
    }

    /** Whether [moduleId] still has a live pool, i.e. hasn't been evicted. */
    fun isLoaded(moduleId: String): Boolean =
        synchronized(engineLock) { pools.containsKey(moduleId) }

    private val syncHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val engineLock = Any()

    /** LRU map: access-ordered so the oldest-used entry is first. */
    private val pools = LinkedHashMap<String, Pool>(16, 0.75f, true)

    // ── Load ─────────────────────────────────────────────────────────────

    suspend fun loadModule(moduleId: String, jsCode: String, fetchBase: String = ""): Result<Unit> {
        val evicted = synchronized(engineLock) {
            if (pools.containsKey(moduleId)) {
                TrackLog.d(TAG, "QuickJsExecutor.loadModule($moduleId) — ENGINE CACHE HIT")
                return Result.success(Unit)
            }
            buildList {
                while (pools.size >= MAX_MODULES) {
                    val lruId = pools.keys.firstOrNull() ?: break
                    TrackLog.d(TAG, "  Evicting LRU module engine: $lruId")
                    pools.remove(lruId)?.let { addAll(it.made) }
                }
            }
        }
        evicted.forEach { runCatching { it.close() } }

        TrackLog.d(TAG, "QuickJsExecutor.loadModule($moduleId) fetchBase=$fetchBase jsCodeLength=${jsCode.length}")
        return withContext(Dispatchers.Default) {
            runCatching { newEngine(jsCode, fetchBase) }
                .onSuccess { qjs ->
                    // The pool starts at one and grows only if two callers ever
                    // want this module at the same moment. A module that is
                    // never contended never pays for a second interpreter.
                    val pool = Pool(jsCode, fetchBase)
                    pool.started = 1
                    pool.made += qjs
                    pool.free.trySend(qjs)
                    synchronized(engineLock) { pools[moduleId] = pool }
                }
                .onFailure { TrackLog.e(TAG, "  ✗ loadModule FAILED for $moduleId: ${it.message}", it) }
                .map { }
        }
    }

    /** A fresh interpreter with [jsCode] evaluated into it, or a throw saying why not. */
    private suspend fun newEngine(jsCode: String, fetchBase: String): QuickJs {
        val qjs = QuickJs.create(Dispatchers.Default)
        qjs.maxStackSize = 512 * 1024L
        // Both limits go on before anything is evaluated into this engine, so
        // they cover the module's own load script, not just its exports (issue #15).
        qjs.evaluationTimeoutMillis = callTimeoutMillis
        qjs.memoryLimit = MEMORY_LIMIT_BYTES
        try {
            bindConsole(qjs)
            bindAsyncFetch(qjs, fetchBase)

            qjs.evaluate<String>(POLYFILLS)
            val cleanCode = preprocessModuleCode(jsCode)

            qjs.evaluate<String>(
                """
                var __spine_iife_error = null;
                var __spine_mod = (function() {
                    try {
                        var module = { exports: {} };
                        var exports = module.exports;
                        var self = {};
                        $cleanCode
                        if (module.exports && (module.exports.searchTracks || module.exports.getTrackStreamUrl)) {
                            return module.exports;
                        }
                        return {};
                    } catch(e) {
                        __spine_iife_error = e && e.message ? e.message : String(e);
                        return {};
                    }
                })();
                'ok'
                """.trimIndent()
            )

            val iifeError = qjs.evaluate<String>("__spine_iife_error || 'none'")
            if (iifeError != "none") throw IllegalStateException("Module init error: $iifeError")

            val keys = qjs.evaluate<String>("Object.keys(__spine_mod).join(', ')")
            TrackLog.d(TAG, "  Module exports: [$keys]")
            return qjs
        } catch (e: Throwable) {
            qjs.close()
            throw e
        }
    }

    // ── Pool ─────────────────────────────────────────────────────────────

    /**
     * An interpreter of [moduleId]'s, exclusively, until it is given back.
     *
     * Takes a free one if there is one, makes another if the pool has room, and
     * otherwise waits for whoever is using one to finish. The claim on a new
     * engine is taken *before* it is built and released if building throws, so
     * a module whose script fails to evaluate cannot permanently consume a slot
     * in the pool it never joined.
     */
    private suspend fun acquire(moduleId: String, pool: Pool): QuickJs {
        pool.free.tryReceive().getOrNull()?.let { return it }

        val mine = pool.lock.withLock {
            if (pool.started >= ENGINES_PER_MODULE) return@withLock false
            pool.started++
            true
        }
        if (!mine) return pool.free.receive()

        return try {
            newEngine(pool.jsCode, pool.fetchBase).also { fresh ->
                pool.lock.withLock { pool.made += fresh }
            }
        } catch (e: Throwable) {
            pool.lock.withLock { pool.started-- }
            throw e
        }
    }

    // ── Call ─────────────────────────────────────────────────────────────

    /**
     * Calls an export of [moduleId]'s module, with [args] handed over as data.
     *
     * Each element is a [JsonElement] — a string, a number, an object — and
     * [callOn] serialises the list with a real JSON encoder before any of it
     * reaches the interpreter. The arguments are *not* JavaScript source: they
     * used to be pre-quoted strings spliced into the call expression, which
     * meant a `"` in a query or a module setting closed the literal and
     * rewrote the script (a title like `Don't "Stop" Me Now` failed the call
     * outright, and a crafted value could inject statements — issue #14).
     */
    suspend fun callExport(moduleId: String, functionName: String, args: List<JsonElement>): Result<String> {
        val pool = synchronized(engineLock) { pools[moduleId] }
            ?: return Result.failure(IllegalStateException("Module $moduleId is not loaded"))

        TrackLog.d(TAG, "QuickJsExecutor.callExport($moduleId, $functionName) args=$args")
        return withContext(Dispatchers.Default) {
            val qjs = try {
                acquire(moduleId, pool)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@withContext Result.failure(e)
            }
            val result = try {
                callOn(qjs, moduleId, functionName, args)
            } catch (e: Throwable) {
                // callOn run-catches everything, so this is only belt and
                // braces — but an engine must never be simply lost.
                Result.failure(e)
            }
            // The interpreter's own abort — the deadline tripping, or the
            // heap ceiling being hit — arrives in two shapes: as a
            // QuickJsException from an evaluate outside the call's try/catch,
            // or, far more often, caught by that try/catch itself and written
            // back as the module error {"error":"interrupted"}. Either way it
            // is host-level, not a fault the module reported (issue #15).
            val abort = result.exceptionOrNull()?.takeIf { isEngineDeadly(it) }?.message
                ?: result.getOrNull()?.let { abortReason(it) }
            if (abort != null) {
                // The one path an engine does not come back: its state is
                // unknown, so it is swapped out rather than returned.
                replace(moduleId, pool, qjs)
                return@withContext Result.failure(
                    IllegalStateException(
                        "$moduleId.$functionName was aborted ($abort) after ${callTimeoutMillis}ms"
                    )
                )
            }
            // Back into circulation on every other path, including a throw:
            // an engine that is never returned is a slot the pool can never
            // hand out again, and three of those would deadlock the module.
            pool.free.trySend(qjs)
            result
        }
    }

    private suspend fun callOn(
        qjs: QuickJs,
        moduleId: String,
        functionName: String,
        args: List<JsonElement>,
    ): Result<String> =
        run {
            runCatching {
                val hasFn = qjs.evaluate<String>("typeof __spine_mod['$functionName']")
                if (hasFn != "function") {
                    throw IllegalStateException("$functionName is not a function on module $moduleId")
                }

                // The arguments reach the interpreter as encoded data, never as
                // source (issue #14): a JSON array from a real encoder, parked in
                // a global the call reads back with apply(). What this replaced
                // spliced the values straight into the call expression —
                // `__fn($argsStr)` with each argument pre-quoted by hand — so a
                // `"` in a query or a module setting ended the string literal and
                // rewrote the script: an ordinary title like
                // `Don't "Stop" Me Now` failed the call outright, and a crafted
                // value could run its own statements in the module's context.
                // JSON encoding is what makes that structurally impossible: quotes,
                // backslashes and newlines come out escaped, and a newline no
                // longer breaks the layout of the source below either.
                val argsJson = Json.encodeToString(ListSerializer(JsonElement.serializer()), args)

                // evaluate() converts a Promise via toString() instead of awaiting it.
                // Workaround: store the resolved JSON in a global var inside the async
                // IIFE, then read it in a second evaluate() call.
                qjs.evaluate<String>(
                    """
                    var __spine_resolved_json = undefined;
                    var __spine_args = $argsJson;
                    (async function() {
                        var __fn = __spine_mod['$functionName'];
                        if (!__fn) {
                            __spine_resolved_json = JSON.stringify({ error: '$functionName not found' });
                            return;
                        }
                        try {
                            var r = await __fn.apply(null, __spine_args);
                            __spine_resolved_json = typeof r === 'string' ? r : JSON.stringify(r);
                        } catch(e) {
                            __spine_resolved_json = JSON.stringify({ error: e && e.message ? e.message : String(e) });
                        }
                    })();
                    """.trimIndent()
                )

                val rawResult = qjs.evaluate<String>("__spine_resolved_json")
                TrackLog.d(TAG, "  callExport result (${rawResult.length} chars): ${rawResult.take(500)}")
                rawResult
            }.onFailure {
                TrackLog.e(TAG, "  ✗ callExport FAILED for $moduleId.$functionName: ${it.message}", it)
            }
        }

    /**
     * Whether [e] says the interpreter had to abort the call, as opposed to
     * the module throwing an error of its own.
     *
     * Two native failures leave an engine's state unknown: the deadline
     * tripping ("interrupted") and the heap ceiling being reached ("out of
     * memory"). QuickJS raises both from native code with no distinct Kotlin
     * type to catch, so they are matched on the messages it produces — this
     * covers the aborts thrown from an evaluate outside the call's own
     * try/catch; [abortReason] covers the ones that get caught inside it.
     */
    private fun isEngineDeadly(e: Throwable): Boolean =
        generateSequence(e) { it.cause?.takeIf { cause -> cause !== it } }
            .take(4)
            .filterIsInstance<QuickJsException>()
            .any { ex -> ex.message == "interrupted" || ex.message == "out of memory" }

    /**
     * The interpreter-abort message a "module error" payload is hiding, or
     * null if the payload is a real module error — or not that shape at all.
     *
     * The call expression wraps the module's function in try/catch, so an
     * abort landing while JS is running — the deadline firing inside a
     * `while (true)` — is a catchable JS exception, and the wrapper writes
     * it back as `{"error":"interrupted"}`. QuickJS's abort message is
     * exactly one of these two strings, so an exact match keeps a module's
     * own error from being mistaken for one.
     */
    private fun abortReason(payload: String): String? =
        runCatching {
            Json.decodeFromString<Map<String, String>>(payload)["error"]
        }.getOrNull()
            ?.takeIf { it == "interrupted" || it == "out of memory" }

    /**
     * Swaps an engine whose call was aborted out of its pool (issue #15).
     *
     * The dead one is closed and a fresh one takes its place, because a pool
     * that keeps aborted engines is this same issue with a different cause:
     * three aborted calls would shrink the pool to nothing and wedge the
     * module just as three hung calls used to. If the rebuild fails — the
     * module's script stopped evaluating — the claim on the slot goes back
     * instead, so a module that is now broken fails loudly on its next call
     * rather than quietly serving fewer of them.
     */
    private suspend fun replace(moduleId: String, pool: Pool, dead: QuickJs) {
        TrackLog.w(TAG, "Replacing $moduleId's engine after an aborted call")
        pool.lock.withLock { pool.made.remove(dead) }
        runCatching { dead.close() }
        val fresh = runCatching { newEngine(pool.jsCode, pool.fetchBase) }.getOrNull()
        if (fresh != null && synchronized(engineLock) { pools[moduleId] === pool }) {
            pool.lock.withLock { pool.made += fresh }
            // Hands a waiter in acquire() its engine; the channel only ever
            // holds engines this pool made, so the send cannot fail or block.
            pool.free.trySend(fresh)
        } else {
            // The module was evicted while the replacement was being built,
            // or no longer loads at all — either way this pool keeps one
            // fewer claim than its engines.
            runCatching { fresh?.close() }
            pool.lock.withLock { pool.started-- }
        }
    }

    // ── Unload ───────────────────────────────────────────────────────────

    fun unload(moduleId: String) {
        val closing = synchronized(engineLock) { pools.remove(moduleId) }?.made.orEmpty()
        closing.forEach { runCatching { it.close() } }
        TrackLog.d(TAG, "QuickJsExecutor.unload($moduleId)")
    }

    fun unloadAll() {
        val all = synchronized(engineLock) {
            val values = pools.values.flatMap { it.made }
            pools.clear()
            values
        }
        all.forEach { runCatching { it.close() } }
        TrackLog.d(TAG, "QuickJsExecutor.unloadAll()")
    }

    // ── Code pre-processing ──────────────────────────────────────────────

    /**
     * Strips ES-module `export` keywords so the code runs inside an IIFE
     * that wraps it in a CommonJS-style `module.exports` object.
     *
     * Also handles the template-literal export format some Spine sources use:
     * `export const x = \`…actual JS…\``
     */
    private fun preprocessModuleCode(jsCode: String): String {
        val code = jsCode.trim()

        val exportPattern = Regex("""^export\s+const\s+\w+\s*=\s*`""")
        val exportMatch = exportPattern.find(code)
        if (exportMatch != null) {
            val contentStart = exportMatch.range.last + 1
            var i = contentStart
            while (i < code.length) {
                if (code[i] == '\\' && i + 1 < code.length) { i += 2; continue }
                if (code[i] == '`') {
                    return code.substring(contentStart, i).trim()
                }
                i++
            }
            TrackLog.w(TAG, "  Template literal not closed, falling through to regex preprocess")
        }

        var result = code
        result = result.replace(Regex("""\bexport\s+default\s+(?=function|class|const|let|var|async)"""), "")
        result = result.replace(Regex("""\bexport\s+(const|let|var|function|class|async)\b"""), "$1")
        result = result.replace(Regex("""\bexport\s*\{[^}]*\}\s*;?"""), "")
        return result
    }

    // ── Console binding ──────────────────────────────────────────────────

    private fun bindConsole(qjs: QuickJs) {
        qjs.define("console") {
            function("log", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.d(TAG, "[JS] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
            function("error", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.e(TAG, "[JS-ERR] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
            function("warn", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.w(TAG, "[JS-WARN] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
            function("info", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    TrackLog.i(TAG, "[JS-INFO] ${args.joinToString(" ") { it?.toString() ?: "null" }}")
                }
            })
        }
    }

    // ── Fetch binding ────────────────────────────────────────────────────

    private suspend fun bindAsyncFetch(qjs: QuickJs, fetchBase: String) {
        qjs.define("__spine") {
            asyncFunction("fetch", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val rawUrl = args[0]?.toString()
                        ?: throw IllegalArgumentException("fetch requires a URL")
                    val method = args[1]?.toString() ?: "GET"
                    val headersJson = args[2]?.toString() ?: "{}"
                    val body = args[3]?.toString()
                    val url = resolveUrl(rawUrl, fetchBase)

                    TrackLog.d(TAG, "  → fetch $method ${redactModuleUrl(url)}")
                    val (statusCode, responseBody) = fetchUrlSync(url, method, headersJson, body)
                    TrackLog.d(TAG, "    HTTP $statusCode (${responseBody.length} bytes)")

                    val respObj = JSONObject().apply {
                        put("status", statusCode)
                        put("ok", statusCode in 200..299)
                        put("body", responseBody)
                    }
                    return respObj.toString()
                }
            })
            asyncFunction("setTimeout", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val ms = args.getOrNull(1)?.toString()?.toLongOrNull() ?: 0L
                    kotlinx.coroutines.delay(ms)
                    return "0"
                }
            })
            asyncFunction("clearTimeout", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String = "ok"
            })
        }

        // Expose a web-compatible fetch() surface over the native binding.
        qjs.evaluate<Unit>(
            """
            var fetch = async function(url, options) {
                var method = 'GET';
                var headers = '{}';
                var body = null;
                if (options) {
                    method = options.method || 'GET';
                    if (options.headers) {
                        if (typeof options.headers === 'string') {
                            headers = options.headers;
                        } else {
                            try { headers = JSON.stringify(options.headers); } catch(e) { headers = '{}'; }
                        }
                    }
                    if (options.body !== undefined && options.body !== null) {
                        body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
                    }
                    if (options.signal && options.signal.aborted) {
                        throw new Error('Aborted');
                    }
                }
                var raw = JSON.parse(await __spine.fetch(url, method, headers, body));
                var respBody = raw.body;
                return {
                    ok: raw.ok,
                    status: raw.status,
                    statusText: raw.ok ? 'OK' : 'Error',
                    json: function() { try { return JSON.parse(respBody); } catch(e) { throw new Error('Invalid JSON: ' + respBody.substring(0, 200)); } },
                    text: function() { return respBody; },
                    arrayBuffer: function() { throw new Error('Not implemented'); },
                    clone: function() { return this; },
                    headers: { get: function(k) { return null; } }
                };
            };

            var setTimeout = async function(fn, ms) {
                await __spine.setTimeout(null, ms || 0);
                if (typeof fn === 'function') fn();
                return 0;
            };
            var clearTimeout = function(id) {};
            """.trimIndent()
        )
    }

    // ── URL resolution ───────────────────────────────────────────────────

    private fun resolveUrl(url: String, base: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (base.isEmpty()) return url
        return if (url.startsWith("/")) {
            val scheme = base.substringBefore("://")
            val host = base.substringAfter("://").substringBefore("/")
            "$scheme://$host$url"
        } else {
            "$base/$url"
        }
    }

    private fun fetchUrlSync(
        url: String,
        method: String,
        headersJson: String,
        body: String?,
    ): Pair<Int, String> {
        val builder = Request.Builder().url(url)

        var hasUserAgent = false
        try {
            val headersObj = JSONObject(headersJson)
            for (key in headersObj.keys()) {
                val value = headersObj.optString(key, "")
                builder.header(key, value)
                if (key.equals("user-agent", ignoreCase = true)) hasUserAgent = true
            }
        } catch (_: Exception) {
        }

        if (!hasUserAgent) {
            builder.header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            )
        }

        when (method.uppercase(Locale.ROOT)) {
            "POST" -> builder.post((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
            "PUT" -> builder.put((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
            "DELETE" -> builder.delete()
            "HEAD" -> builder.head()
            else -> builder.get()
        }

        syncHttpClient.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            return response.code to responseBody
        }
    }

    // ── Polyfills ────────────────────────────────────────────────────────

    private const val POLYFILLS = """
        if (typeof AbortController === 'undefined') {
            var AbortController = function() { this.signal = { aborted: false }; };
            AbortController.prototype.abort = function() { this.signal.aborted = true; };
        }

        if (typeof Object.assign !== 'function') {
            Object.assign = function(target) {
                if (target == null) throw new TypeError('Cannot convert undefined or null to object');
                var to = Object(target);
                for (var i = 1; i < arguments.length; i++) {
                    var source = arguments[i];
                    if (source != null) {
                        for (var key in source) {
                            if (Object.prototype.hasOwnProperty.call(source, key)) {
                                to[key] = source[key];
                            }
                        }
                    }
                }
                return to;
            };
        }

        if (typeof Promise.any !== 'function') {
            Promise.any = function(promises) {
                return new Promise(function(resolve, reject) {
                    var errors = [];
                    var remaining = promises.length;
                    if (remaining === 0) { reject(new AggregateError([], 'All promises were rejected')); return; }
                    promises.forEach(function(p, i) {
                        Promise.resolve(p).then(resolve, function(e) {
                            errors[i] = e;
                            remaining--;
                            if (remaining === 0) reject(new AggregateError(errors, 'All promises were rejected'));
                        });
                    });
                });
            };
        }

        if (typeof Promise.allSettled !== 'function') {
            Promise.allSettled = function(promises) {
                return Promise.all(promises.map(function(p) {
                    return Promise.resolve(p).then(
                        function(value) { return { status: 'fulfilled', value: value }; },
                        function(reason) { return { status: 'rejected', reason: reason }; }
                    );
                }));
            };
        }

        if (typeof AggregateError === 'undefined') {
            var AggregateError = function(errors, message) {
                this.errors = errors;
                this.message = message || '';
                this.name = 'AggregateError';
            };
            AggregateError.prototype = Object.create(Error.prototype);
        }

        if (typeof atob === 'undefined') {
            var atob = function(input) {
                var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                var str = String(input).replace(/=+${'$'}/, '');
                var output = '';
                for (var bc = 0, bs, buffer, idx = 0; buffer = str.charAt(idx++); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
                    buffer = chars.indexOf(buffer);
                }
                return output;
            };
        }

        if (typeof setTimeout === 'undefined') {
            var __spine_timers = {};
            var __spine_timer_id = 0;
            var setTimeout = function(fn, ms) {
                var id = ++__spine_timer_id;
                __spine_timers[id] = { fn: fn, ms: ms || 0 };
                return id;
            };
            var clearTimeout = function(id) {
                if (__spine_timers[id]) delete __spine_timers[id];
            };
        }
        if (typeof clearTimeout === 'undefined') {
            var clearTimeout = function(id) {
                if (typeof __spine_timers !== 'undefined' && __spine_timers[id]) delete __spine_timers[id];
            };
        }

        if (typeof URL === 'undefined') {
            var URL = function(url, base) {
                this.href = url;
                try {
                    var a = url.replace(/^[^:]+:/, 'http:');
                    var match = a.match(/^\/\/([^/]+)(\/.*)?$/);
                    if (match) {
                        this.hostname = match[1];
                        this.pathname = match[2] || '/';
                    } else {
                        var m2 = a.match(/^https?:\/\/([^/]+)(\/.*)?$/);
                        if (m2) { this.hostname = m2[1]; this.pathname = m2[2] || '/'; }
                    }
                } catch(e) {}
            };
        }
    """
}
