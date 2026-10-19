package com.music.music.data.scrobbling

import com.music.music.data.DebugLog as Log
import com.music.music.data.settings.AppSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Delivers what [ScrobbleQueue] is holding, once there is somewhere to deliver
 * it to.
 *
 * Runs on three occasions, which between them cover every way a queue drains in
 * practice: when the app starts, when a live scrobble succeeds (so the network
 * is demonstrably back), and when the user asks for it from Settings. Not on a
 * timer — a periodic wake-up to retry something that has already failed once is
 * a battery cost paid for the rare case, and the first two occasions catch that
 * case anyway.
 *
 * One flush at a time. Two overlapping runs would deliver the same listen twice
 * before either had removed it, which is exactly the duplicate both services
 * would then record.
 */
object ScrobbleRetry {

    private val mutex = Mutex()

    /**
     * Sends everything queued, oldest first, and stops at the first failure.
     *
     * Stopping rather than carrying on is deliberate: the entries are in
     * chronological order and a failure is almost always about the connection,
     * so the next one would fail the same way. It also keeps a long queue from
     * turning into a hundred timeouts the one time the network is flaky.
     *
     * @return how many were delivered.
     */
    suspend fun flush(): Int = mutex.withLock {
        val pending = ScrobbleQueue.pending()
        if (pending.isEmpty()) return@withLock 0

        // Credentials are read at flush time, not captured when the play was
        // queued: a session restored between then and now is the ordinary case
        // for a queue that outlived an app restart.
        val lastFmReady = AppSettings.lastfmEnabled.value &&
            AppSettings.lastfmScrobbleEnabled.value &&
            AppSettings.lastfmSessionKey.value.isNotBlank() &&
            LastFM.isInitialized()
        val listenBrainzToken = AppSettings.listenBrainzToken.value
        val listenBrainzReady = AppSettings.scrobblingAvailable &&
            AppSettings.listenBrainzEnabled.value &&
            listenBrainzToken.isNotBlank()

        var delivered = 0
        for (listen in pending) {
            val sent = when (listen.service) {
                ScrobbleQueue.Service.LAST_FM -> {
                    if (!lastFmReady) continue
                    sendLastFm(listen)
                }
                ScrobbleQueue.Service.LISTEN_BRAINZ -> {
                    if (!listenBrainzReady) continue
                    sendListenBrainz(listen, listenBrainzToken)
                }
            }
            when (sent) {
                Outcome.DELIVERED, Outcome.REFUSED -> {
                    // A refusal is permanent — a bad session key will not fix
                    // itself — so the record goes rather than sitting in the
                    // queue for two weeks pretending it might.
                    ScrobbleQueue.remove(listen)
                    if (sent == Outcome.DELIVERED) delivered++
                }
                Outcome.RETRY -> return@withLock delivered
            }
        }
        if (delivered > 0) Log.d(TAG, "Delivered $delivered queued scrobbles")
        delivered
    }

    private enum class Outcome { DELIVERED, REFUSED, RETRY }

    private suspend fun sendLastFm(listen: ScrobbleQueue.PendingListen): Outcome {
        val result = LastFM.scrobble(
            artist = listen.artist,
            track = listen.track,
            timestamp = listen.startedAtMs / 1000L,
            album = listen.album,
            duration = listen.durationMs?.let { (it / 1000L).toInt() }?.takeIf { it > 0 },
        )
        return result.fold(
            onSuccess = { Outcome.DELIVERED },
            onFailure = { throwable ->
                val code = (throwable as? LastFM.LastFmException)?.code
                if (code != null && code in ScrobbleQueue.PERMANENT_LAST_FM_ERRORS) {
                    Log.w(TAG, "Dropping queued scrobble, Last.fm refused it ($code): ${listen.track}")
                    Outcome.REFUSED
                } else {
                    Log.w(TAG, "Queued scrobble still undeliverable: ${throwable.message}")
                    Outcome.RETRY
                }
            },
        )
    }

    private suspend fun sendListenBrainz(listen: ScrobbleQueue.PendingListen, token: String): Outcome {
        // ListenBrainzManager answers a boolean and swallows its own errors, so
        // a false is read as "not now". There is no permanent-failure code to
        // tell apart here: a bad token fails identically to a dead network, and
        // the two-week age limit is what eventually clears it.
        val ok = ListenBrainzManager.submitFinishedRaw(
            token = token,
            artist = listen.artist,
            track = listen.track,
            album = listen.album,
            startedAtMs = listen.startedAtMs,
            endedAtMs = listen.endedAtMs,
            durationMs = listen.durationMs,
        )
        return if (ok) Outcome.DELIVERED else Outcome.RETRY
    }

    private const val TAG = "ScrobbleRetry"
}
