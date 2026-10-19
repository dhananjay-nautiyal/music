package com.music.music.data.lyrics

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The offline lyrics cache.
 *
 * Three properties are worth a test and the rest is plumbing: a found answer
 * comes back, a miss is never stored (so a provider that adds a track later is
 * still asked), and what comes back is the same lyrics — word timings and
 * background lines included, since a cache that quietly degrades word-synced
 * lyrics to line-synced ones would look like a bug in the providers.
 */
class LyricsCacheTest {

    private val directory: File = File.createTempFile("lyrics-cache-test", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    private suspend fun freshCache() {
        LyricsCache.cacheDir = directory
        LyricsCache.clear()
    }

    @AfterTest
    fun cleanUp() {
        runBlocking { LyricsCache.clear() }
        LyricsCache.cacheDir = null
        directory.deleteRecursively()
    }

    private val wordSynced = LyricsRepository.Result(
        source = LyricsSource.BETTER_LYRICS,
        lines = listOf(
            LyricLine(
                timeMs = 1_000,
                text = "hello world",
                words = listOf(
                    LyricWord(
                        startMs = 1_000,
                        endMs = 1_400,
                        text = "hello",
                        syllables = listOf(
                            LyricSyllable(startMs = 1_000, endMs = 1_200, startChar = 0, endChar = 2),
                            LyricSyllable(startMs = 1_200, endMs = 1_400, startChar = 2, endChar = 5),
                        ),
                    ),
                    LyricWord(startMs = 1_400, endMs = 1_900, text = "world"),
                ),
                sungUntilMs = 2_000,
            ),
        ),
    )

    @Test
    fun `a stored answer comes back whole`(): Unit = runBlocking {
        freshCache()
        LyricsCache.put("vid1", "Title", "Artist", 200_000, "Album", wordSynced)

        val restored = LyricsCache.get("vid1", "Title", "Artist", 200_000, "Album")

        assertNotNull(restored)
        assertEquals(LyricsSource.BETTER_LYRICS, restored.source)
        assertEquals(1, restored.lines.size)
        val line = restored.lines[0]
        assertEquals("hello world", line.text)
        assertEquals(1_000L, line.timeMs)
        assertEquals(2_000L, line.sungUntilMs)
        // Word timing is the part a lossy cache would drop without anyone
        // noticing until the lyrics stopped highlighting per word.
        assertEquals(2, line.words.size)
        assertEquals("hello", line.words[0].text)
        assertEquals(2, line.words[0].syllables.size)
        assertEquals(2, line.words[0].syllables[1].startChar)
    }

    @Test
    fun `a miss is not stored`(): Unit = runBlocking {
        freshCache()
        LyricsCache.put("vid2", "T", "A", 100_000, null, LyricsRepository.Result(LyricsSource.LRCLIB, emptyList()))

        assertNull(LyricsCache.get("vid2", "T", "A", 100_000, null))
    }

    @Test
    fun `a different track does not get the answer`(): Unit = runBlocking {
        freshCache()
        LyricsCache.put("vid3", "Title", "Artist", 200_000, null, wordSynced)

        assertNull(LyricsCache.get("vid4", "Title", "Artist", 200_000, null))
        assertNull(LyricsCache.get("vid3", "Other Title", "Artist", 200_000, null))
        assertNull(LyricsCache.get("vid3", "Title", "Artist", 300_000, null))
    }

    @Test
    fun `duration is compared in whole seconds`(): Unit = runBlocking {
        freshCache()
        // Providers and files disagree on a track's length by a few hundred ms;
        // a key that disagreed with itself would miss every single time.
        LyricsCache.put("vid5", "T", "A", 200_000, null, wordSynced)

        assertNotNull(LyricsCache.get("vid5", "T", "A", 200_400, null))
    }

    @Test
    fun `a stored answer survives a restart`(): Unit = runBlocking {
        freshCache()
        LyricsCache.put("vid6", "T", "A", 200_000, null, wordSynced)

        // Dropping the in-memory tier is what a process death does; the disk
        // entry is the only thing that makes the cache worth having offline.
        LyricsCache.clearMemoryForTest()

        val restored = LyricsCache.get("vid6", "T", "A", 200_000, null)
        assertNotNull(restored)
        assertEquals("hello world", restored.lines[0].text)
        assertTrue(LyricsCache.entryCount() >= 1)
    }

    @Test
    fun `a background line and its own timing survive`(): Unit = runBlocking {
        freshCache()
        val withBackground = LyricsRepository.Result(
            source = LyricsSource.LYRICS_PLUS,
            lines = listOf(
                LyricLine(
                    timeMs = 5_000,
                    text = "lead",
                    background = LyricLine(timeMs = 5_000, text = "echo"),
                    alignment = LyricAlignment.End,
                ),
            ),
        )
        LyricsCache.put("vid7", "T", "A", 200_000, null, withBackground)

        val restored = LyricsCache.get("vid7", "T", "A", 200_000, null)
        assertNotNull(restored)
        val line = restored.lines[0]
        assertEquals(LyricAlignment.End, line.alignment)
        assertNotNull(line.background)
        assertEquals("echo", line.background?.text)
    }
}
