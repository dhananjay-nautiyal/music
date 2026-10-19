package com.music.music.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Lyrics kept between runs, so a track played twice does not ask ten providers
 * the same question twice.
 *
 * The immediate cost of not having this was bandwidth — every repeat play of a
 * song re-ran the whole race in [LyricsRepository] — but the failure that
 * actually shows is offline: lyrics are the one part of a downloaded track that
 * still needed a network, so a plane or a dead zone turned a saved song into one
 * with no words.
 *
 * ## Only hits are kept
 *
 * A miss is not an answer. A provider that has nothing today may have the track
 * next month, and a cached miss would be the app insisting otherwise forever.
 * The cost of being wrong this way is one extra lookup; the cost of the other is
 * lyrics that never appear again.
 *
 * ## Keyed by what was asked, not just by id
 *
 * The lookup is a function of the track's name, artist, album and duration, so
 * the key is too. A video id alone would serve a corrected title's old lyrics
 * after a metadata fix, and local files often share no id at all.
 *
 * ## Mirrors [LyricsTranslation]'s disk habits
 *
 * Same gzipped-JSON-per-entry shape, same [cacheDir] handed in by whichever
 * platform is running (see `MusicApplication`, `Main.kt`), same trim on write.
 * Two caches with two designs would be two things to get right.
 */
object LyricsCache {

    /**
     * Where entries are kept — the phone's cache directory, the desktop's media
     * cache. Null keeps them in memory only, which is what a unit test and any
     * caller that has not set it get.
     */
    @Volatile
    var cacheDir: File? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val diskMutex = Mutex()

    /**
     * Recent answers, ahead of the disk.
     *
     * Bounded and least-recently-used: a listening session revisits the same few
     * dozen tracks far more often than it plays new ones, and a hit here skips
     * even the file read.
     */
    private val memory = object : LinkedHashMap<String, CachedLyrics>(MEMORY_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyrics>) =
            size > MEMORY_ENTRIES
    }

    /**
     * What [LyricsRepository] found for this track last time, if anything.
     *
     * The returned [LyricsRepository.Result.source] is the provider that won,
     * not necessarily the one the user now ranks first — a cached answer is a
     * cache hit, and re-ranking would mean asking everyone again, which is the
     * thing this exists to avoid. It is still a valid answer to "show this
     * track's lyrics".
     */
    suspend fun get(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): LyricsRepository.Result? {
        val key = key(videoId, title, artist, durationMs, album)
        synchronized(memory) { memory[key] }?.let { return it.toResult() }
        val stored = readDisk(key) ?: return null
        synchronized(memory) { memory[key] = stored }
        return stored.toResult()
    }

    /** Keeps a found answer. Never called for a miss — see the class doc. */
    suspend fun put(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        result: LyricsRepository.Result,
    ) {
        // A result with no lines is a miss wearing a source name; storing it
        // would pin "nothing here" to a track that a provider may fix tomorrow.
        if (result.lines.isEmpty()) return
        val key = key(videoId, title, artist, durationMs, album)
        val cached = CachedLyrics.of(result)
        synchronized(memory) { memory[key] = cached }
        writeDisk(key, cached)
    }

    /** Empties both tiers. For Settings' clear-data path. */
    suspend fun clear() {
        synchronized(memory) { memory.clear() }
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                runCatching { cacheDir?.let { File(it, DIRECTORY).deleteRecursively() } }
            }
        }
    }

    /**
     * Drops the in-memory tier only, keeping the disk entries.
     *
     * What a process death does, and the only way to test that the disk half is
     * actually being written rather than the memory tier answering everything.
     */
    internal fun clearMemoryForTest() {
        synchronized(memory) { memory.clear() }
    }

    /**
     * How many entries are on disk, for the Storage screen. Cheap enough to call
     * from a settings row; a directory listing, not a read.
     */
    suspend fun entryCount(): Int = withContext(Dispatchers.IO) {
        runCatching { cacheDir?.let { File(it, DIRECTORY) }?.listFiles()?.count { it.isFile } }
            .getOrNull() ?: 0
    }

    // ---- the disk half -------------------------------------------------------

    private suspend fun readDisk(key: String): CachedLyrics? = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            val root = cacheDir ?: return@withLock null
            val file = File(File(root, DIRECTORY), "$key.json.gz")
            if (!file.isFile) return@withLock null
            runCatching {
                val value = GZIPInputStream(FileInputStream(file)).bufferedReader().use {
                    json.decodeFromString(CachedLyrics.serializer(), it.readText())
                }
                // Touch on read so the trim below is least-recently-*used*
                // rather than oldest-written.
                file.setLastModified(System.currentTimeMillis())
                value.takeIf { it.version == CACHE_VERSION }
            }.getOrNull()
        }
    }

    private suspend fun writeDisk(key: String, value: CachedLyrics) = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            val root = cacheDir ?: return@withLock
            val directory = File(root, DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) return@withLock
            val destination = File(directory, "$key.json.gz")
            val temporary = File(directory, "$key.tmp")
            runCatching {
                GZIPOutputStream(FileOutputStream(temporary)).bufferedWriter().use {
                    it.write(json.encodeToString(CachedLyrics.serializer(), value))
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                trim(directory)
            }.onFailure { temporary.delete() }
        }
    }

    /**
     * Keeps the directory under [MAX_ENTRIES], oldest-touched first.
     *
     * By count rather than bytes because an entry's size varies with the song,
     * and the thing being bounded is really "how many tracks do I listen to" —
     * a few thousand of these is a couple of megabytes, which is not worth the
     * arithmetic to measure.
     */
    private fun trim(directory: File) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_ENTRIES)
            .forEach { it.delete() }
    }

    private fun key(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): String {
        val source = buildString {
            append(videoId).append('\u0000')
            append(title).append('\u0000')
            append(artist).append('\u0000')
            // Rounded to the second: providers state durations that differ by
            // a few hundred ms between catalogue and file, and a key that
            // disagrees with itself misses every time.
            append(durationMs / 1000).append('\u0000')
            append(album.orEmpty())
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ---- the wire shape ------------------------------------------------------

    /**
     * A cached answer, in the shape it is written.
     *
     * [LyricLine] is not itself serialisable and should not become so: it is a
     * rendering model with a nested background line and a timing source, and
     * annotating it for this would tie the lyrics UI's shape to a cache file
     * forever. The mirror below is the smaller, stabler thing — and the one
     * [CACHE_VERSION] guards.
     */
    @Serializable
    private data class CachedLyrics(
        val version: Int = CACHE_VERSION,
        val source: String,
        val lines: List<CachedLine>,
    ) {
        fun toResult(): LyricsRepository.Result? {
            val provider = runCatching { LyricsSource.valueOf(source) }.getOrNull() ?: return null
            return LyricsRepository.Result(provider, lines.map { it.toLine() })
        }

        companion object {
            fun of(result: LyricsRepository.Result) = CachedLyrics(
                source = result.source.name,
                lines = result.lines.map(CachedLine::of),
            )
        }
    }

    @Serializable
    private data class CachedLine(
        val timeMs: Long,
        val text: String,
        val words: List<CachedWord> = emptyList(),
        val sungUntilMs: Long? = null,
        val background: CachedLine? = null,
        val alignment: String = LyricAlignment.Start.name,
        val timingSource: CachedLine? = null,
    ) {
        fun toLine(): LyricLine = LyricLine(
            timeMs = timeMs,
            text = text,
            words = words.map { it.toWord() },
            sungUntilMs = sungUntilMs,
            background = background?.toLine(),
            alignment = runCatching { LyricAlignment.valueOf(alignment) }.getOrDefault(LyricAlignment.Start),
            timingSource = timingSource?.toLine(),
        )

        companion object {
            /**
             * Explicit types throughout: [CachedLine] holds two of itself, and
             * without them the compiler cannot resolve `of` against the type it
             * is still inferring.
             */
            fun of(line: LyricLine): CachedLine {
                val background: CachedLine? = line.background?.let { CachedLine.of(it) }
                val timing: CachedLine? = line.timingSource?.let { CachedLine.of(it) }
                val words: List<CachedWord> = line.words.map { CachedWord.of(it) }
                return CachedLine(
                    timeMs = line.timeMs,
                    text = line.text,
                    words = words,
                    sungUntilMs = line.sungUntilMs,
                    background = background,
                    alignment = line.alignment.name,
                    timingSource = timing,
                )
            }
        }
    }

    @Serializable
    private data class CachedWord(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val syllables: List<CachedSyllable> = emptyList(),
    ) {
        fun toWord(): LyricWord = LyricWord(
            startMs = startMs,
            endMs = endMs,
            text = text,
            syllables = syllables.map { it.toSyllable() },
        )

        companion object {
            fun of(word: LyricWord) = CachedWord(
                startMs = word.startMs,
                endMs = word.endMs,
                text = word.text,
                syllables = word.syllables.map(CachedSyllable::of),
            )
        }
    }

    @Serializable
    private data class CachedSyllable(
        val startMs: Long,
        val endMs: Long,
        val startChar: Int,
        val endChar: Int,
    ) {
        fun toSyllable() = LyricSyllable(startMs, endMs, startChar, endChar)

        companion object {
            fun of(syllable: LyricSyllable) = CachedSyllable(
                syllable.startMs,
                syllable.endMs,
                syllable.startChar,
                syllable.endChar,
            )
        }
    }

    /** Bump when the shape above changes; an older entry is then re-fetched. */
    private const val CACHE_VERSION = 1

    private const val DIRECTORY = "lyrics"

    /** Long enough to cover a few months of one listener's repeats. */
    private const val MAX_ENTRIES = 2_000

    private const val MEMORY_ENTRIES = 40
}
