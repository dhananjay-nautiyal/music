package com.music.music

import com.music.music.data.scrobbling.ScrobbleQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry queue's arithmetic, tested without a Context.
 *
 * [ScrobbleQueue] needs a `SharedPreferences` to persist, which a plain JVM test
 * has none of — so these cover the pure parts: which Last.fm failures are worth
 * retrying, and how a queued record is built from a song. The persistence and
 * flush paths are exercised by the app, and by construction share this code.
 */
class ScrobbleQueueTest {

    @After
    fun cleanUp() {
        ScrobbleQueue.clear()
    }

    @Test
    fun `auth and key failures are permanent`() {
        // 4 auth failed, 6 invalid params, 9 invalid session, 10 invalid key,
        // 13 invalid signature. Retrying any of these forever would keep a dead
        // session's plays in the queue until they aged out.
        for (code in listOf(4, 6, 9, 10, 13)) {
            assertTrue("$code should be permanent", code in ScrobbleQueue.PERMANENT_LAST_FM_ERRORS)
        }
    }

    @Test
    fun `outages and rate limits are retried`() {
        // 8 operation failed, 11 service offline, 16 temporarily unavailable,
        // 29 rate limit — all of these fix themselves.
        for (code in listOf(8, 11, 16, 29)) {
            assertTrue("$code should be retried", code !in ScrobbleQueue.PERMANENT_LAST_FM_ERRORS)
        }
    }

    @Test
    fun `a queued last fm listen keeps what the service needs`() {
        val song = com.music.music.data.model.Song(
            videoId = "abc",
            title = "Track",
            artist = "Artist",
            thumbnailUrl = null,
            albumName = "Album",
        )

        val listen = ScrobbleQueue.lastFm(
            song = song,
            startedAtMs = 1_700_000_000_000,
            durationSeconds = 245,
            primaryArtistOnly = false,
        )

        assertEquals(ScrobbleQueue.Service.LAST_FM, listen.service)
        assertEquals("Track", listen.track)
        assertEquals("Artist", listen.artist)
        assertEquals("Album", listen.album)
        assertEquals(1_700_000_000_000, listen.startedAtMs)
        // Duration arrives as seconds and is stored as ms, because that is what
        // the queue's ListenBrainz half needs from the same field.
        assertEquals(245_000L, listen.durationMs)
    }

    @Test
    fun `a queued listen brainz record keeps both ends`() {
        val song = com.music.music.data.model.Song(
            videoId = "abc",
            title = "Track",
            artist = "Artist",
            thumbnailUrl = null,
        )

        val listen = ScrobbleQueue.listenBrainz(
            song = song,
            startedAtMs = 1_700_000_000_000,
            endedAtMs = 1_700_000_200_000,
            durationMs = 200_000,
            primaryArtistOnly = false,
        )

        assertEquals(ScrobbleQueue.Service.LISTEN_BRAINZ, listen.service)
        assertEquals(1_700_000_000_000, listen.startedAtMs)
        assertEquals(1_700_000_200_000, listen.endedAtMs)
        assertEquals(200_000L, listen.durationMs)
    }

    @Test
    fun `an uninitialised queue reports nothing rather than crashing`() {
        // The queue is an improvement on losing a play, not a requirement for
        // playback — a scrobble that arrives before startup finished must not
        // take the process down.
        assertEquals(0, ScrobbleQueue.size())
        assertTrue(ScrobbleQueue.pending().isEmpty())
    }
}
