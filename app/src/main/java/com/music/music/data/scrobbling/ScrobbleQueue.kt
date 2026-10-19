package com.music.music.data.scrobbling

import android.content.Context
import android.content.SharedPreferences
import com.music.music.data.DebugLog as Log
import com.music.music.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Plays that were counted but not yet delivered, kept until a network can carry
 * them.
 *
 * A scrobble is fired once, when the track crosses its threshold, and the
 * answer was only ever logged: a play made in a tunnel, on a plane, or during a
 * Last.fm outage existed in the listening stats and nowhere else. Both services
 * accept a listen long after the fact — Last.fm for two weeks, ListenBrainz
 * indefinitely — so the only thing standing between "lost" and "delivered late"
 * was remembering to try again.
 *
 * ## Both services, one queue
 *
 * A queued record names its [Service] and carries the fields that service
 * needs, because they differ: Last.fm wants a start timestamp and a duration,
 * ListenBrainz wants the start and end of the listen. Splitting this into two
 * queues would mean two caps, two flush points and two chances to forget one.
 *
 * ## What is never retried
 *
 * A rejected credential is not a network problem. If Last.fm answers 4, 9, 10
 * or 13 — bad session, bad key, bad signature — retrying forever would keep a
 * dead session's plays in the queue until they aged out, and would hammer the
 * API on every flush. Those drop immediately, with a log line saying why. The
 * codes that mean "try later" (offline, rate limit, service error) stay.
 *
 * ## Bounded, and bounded by age
 *
 * [MAX_ENTRIES] keeps a long offline stretch from growing without limit, and
 * [MAX_AGE_MS] drops anything both services would refuse anyway. Oldest goes
 * first in both cases: the newest play is the one still worth delivering.
 */
object ScrobbleQueue {

    enum class Service { LAST_FM, LISTEN_BRAINZ }

    @Serializable
    data class PendingListen(
        val service: Service,
        /** When the play started, in epoch milliseconds. */
        val startedAtMs: Long,
        /** When it ended, in epoch milliseconds — ListenBrainz needs both ends. */
        val endedAtMs: Long,
        val artist: String,
        val track: String,
        val album: String? = null,
        val durationMs: Long? = null,
        val queuedAtMs: Long = System.currentTimeMillis(),
    )

    private const val PREF_NAME = "music_scrobble_queue"
    private const val KEY_PENDING = "pending_listens"

    /** Two weeks is Last.fm's own window; older than that is refused anyway. */
    private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(14)

    /** Enough for a long flight plus a dead week; small enough to stay a few KB. */
    private const val MAX_ENTRIES = 200

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Records a play that could not be delivered now.
     *
     * A no-op before [init] rather than a crash: the queue is an improvement on
     * losing the play, not a requirement for playback, and a scrobble arriving
     * before the application has finished starting up is not worth failing on.
     */
    fun enqueue(listen: PendingListen) {
        if (!this::prefs.isInitialized) return
        val now = System.currentTimeMillis()
        val kept = read()
            .filter { now - it.queuedAtMs <= MAX_AGE_MS }
            .toMutableList()
        // The same play can be queued twice when a retry overlaps the original
        // failure — the timer firing as the flush runs. Identity is the service
        // plus the moment it started; nothing else about a listen is unique.
        if (kept.any { it.service == listen.service && it.startedAtMs == listen.startedAtMs }) return
        kept.add(listen)
        write(
            if (kept.size > MAX_ENTRIES) {
                kept.sortedByDescending { it.queuedAtMs }.take(MAX_ENTRIES)
            } else {
                kept
            },
        )
    }

    /** Everything waiting, oldest first — the order both services expect. */
    fun pending(): List<PendingListen> {
        if (!this::prefs.isInitialized) return emptyList()
        val now = System.currentTimeMillis()
        return read()
            .filter { now - it.queuedAtMs <= MAX_AGE_MS }
            .sortedBy { it.startedAtMs }
    }

    /** Drops a delivered (or permanently refused) listen. */
    fun remove(listen: PendingListen) {
        if (!this::prefs.isInitialized) return
        write(read().filterNot { it.service == listen.service && it.startedAtMs == listen.startedAtMs })
    }

    /** How many plays are waiting, for a settings row. */
    fun size(): Int = pending().size

    fun clear() {
        if (!this::prefs.isInitialized) return
        prefs.edit().remove(KEY_PENDING).apply()
    }

    private fun read(): List<PendingListen> {
        val raw = prefs.getString(KEY_PENDING, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PendingListen.serializer()), raw)
        }.getOrElse {
            Log.w(TAG, "Unreadable scrobble queue, starting over: ${it.message}")
            emptyList()
        }
    }

    private fun write(list: List<PendingListen>) {
        runCatching {
            prefs.edit().putString(
                KEY_PENDING,
                json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PendingListen.serializer()), list),
            ).apply()
        }
    }

    /**
     * Builds the Last.fm record for [song].
     *
     * Album and duration are carried as the song states them now rather than
     * re-derived at flush time, because the song object is long gone by then.
     */
    fun lastFm(
        song: Song,
        startedAtMs: Long,
        durationSeconds: Int?,
        primaryArtistOnly: Boolean,
    ): PendingListen = PendingListen(
        service = Service.LAST_FM,
        startedAtMs = startedAtMs,
        endedAtMs = startedAtMs,
        artist = if (primaryArtistOnly) song.artist.primaryArtist() else song.artist,
        track = song.title,
        album = song.albumName,
        durationMs = durationSeconds?.takeIf { it > 0 }?.let { it * 1000L },
    )

    /** Builds the ListenBrainz record for a finished listen. */
    fun listenBrainz(
        song: Song,
        startedAtMs: Long,
        endedAtMs: Long,
        durationMs: Long?,
        primaryArtistOnly: Boolean,
    ): PendingListen = PendingListen(
        service = Service.LISTEN_BRAINZ,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        artist = if (primaryArtistOnly) song.artist.primaryArtist() else song.artist,
        track = song.title,
        album = song.albumName,
        durationMs = durationMs,
    )

    private const val TAG = "ScrobbleQueue"

    /**
     * Last.fm error codes that mean "this will never work", so the record is
     * dropped rather than retried: 4 authentication failed, 6 invalid
     * parameters, 9 invalid session key, 10 invalid API key, 13 invalid method
     * signature. Everything else — 8 operation failed, 11 service offline, 16
     * temporarily unavailable, 29 rate limit — is worth another try later.
     */
    val PERMANENT_LAST_FM_ERRORS = setOf(4, 6, 9, 10, 13)
}
