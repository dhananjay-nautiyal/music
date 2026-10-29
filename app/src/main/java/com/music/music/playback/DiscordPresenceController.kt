package com.music.music.playback

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.music.music.data.NerdStats
import com.music.music.data.TrackLog
import com.music.music.data.discord.DiscordRPC
import com.music.music.data.discord.discordAudioQualityLine
import com.music.music.data.model.Song
import com.music.music.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Owns the optional Discord gateway and the activity published for playback. */
internal class DiscordPresenceController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: () -> ExoPlayer?,
) {
    private var rpc: DiscordRPC? = null
    private var updateJob: Job? = null
    private var presenceUp = false

    fun observe() {
        scope.launch {
            combine(
                AppSettings.discordToken,
                AppSettings.discordRpcEnabled,
            ) { token, enabled -> token.takeIf { enabled && it.isNotBlank() } }
                .distinctUntilChanged()
                .collectLatest { token ->
                    updateJob?.cancel()
                    rpc?.let { oldRpc ->
                        val wasUp = presenceUp
                        presenceUp = false
                        withContext(Dispatchers.IO + NonCancellable) {
                            withTimeoutOrNull(DISCORD_TEARDOWN_TIMEOUT_MS) {
                                if (wasUp) runCatching { oldRpc.close() }
                            }
                            runCatching { oldRpc.closeRPC() }
                        }
                    }
                    rpc = null

                    if (token == null) return@collectLatest
                    rpc = DiscordRPC(context, token)
                    player()?.takeIf { it.isPlaying }?.let(::publish)
                }
        }

        scope.launch {
            combine<Any, List<Any>>(
                AppSettings.discordUseDetails,
                AppSettings.discordStatus,
                AppSettings.discordActivityType,
                AppSettings.discordActivityName,
                AppSettings.discordButton1Text,
                AppSettings.discordButton1Visible,
                AppSettings.discordButton2Text,
                AppSettings.discordButton2Visible,
                AppSettings.playbackSpeed,
            ) { it.toList() }
                .distinctUntilChanged()
                .drop(1)
                .collect { player()?.takeIf { it.isPlaying }?.let(::publish) }
        }

        scope.launch {
            combine(
                NerdStats.current,
                AppSettings.discordShowAudioQuality,
            ) { stats, enabled ->
                discordAudioQualityLine(stats).takeIf { enabled }
            }
                .distinctUntilChanged()
                .drop(1)
                .collect { player()?.takeIf { it.isPlaying }?.let(::publish) }
        }

        scope.launch {
            AppSettings.meteredConnection
                .drop(1)
                .collect { metered ->
                    if (metered == null) return@collect
                    val activeRpc = rpc ?: return@collect
                    withContext(Dispatchers.IO) { runCatching { activeRpc.wakeUp() } }
                    if (presenceUp) player()?.takeIf { it.isPlaying }?.let(::publish)
                }
        }
    }

    fun onSeek(exoPlayer: ExoPlayer) {
        if (exoPlayer.isPlaying) publish(exoPlayer)
    }

    fun onPlayerStateChanged(exoPlayer: ExoPlayer, isPlaying: Boolean) {
        if (isPlaying) publish(exoPlayer) else clear()
    }

    fun onTrackChanged(exoPlayer: ExoPlayer) {
        if (exoPlayer.isPlaying) publish(exoPlayer)
    }

    fun clear() {
        val activeRpc = rpc ?: return
        if (!presenceUp) return
        presenceUp = false
        updateJob?.cancel()
        updateJob = scope.launch(Dispatchers.IO) {
            runCatching { activeRpc.close() }
        }
    }

    fun destroy() {
        updateJob?.cancel()
        rpc?.let { activeRpc ->
            rpc = null
            val wasUp = presenceUp
            presenceUp = false
            CoroutineScope(Dispatchers.IO).launch {
                withTimeoutOrNull(DISCORD_TEARDOWN_TIMEOUT_MS) {
                    if (wasUp) runCatching { activeRpc.close() }
                }
                runCatching { activeRpc.closeRPC() }
            }
        }
    }

    private fun publish(exoPlayer: ExoPlayer) {
        val activeRpc = rpc ?: return
        val song: Song = exoPlayer.currentMediaItem?.toSong() ?: return
        val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        val durationMs = exoPlayer.duration.takeIf { it > 0 } ?: 0L
        val speed = AppSettings.playbackSpeed.value
        val audioQuality = discordAudioQualityLine(NerdStats.current.value)
            .takeIf { AppSettings.discordShowAudioQuality.value }

        updateJob?.cancel()
        presenceUp = true
        updateJob = scope.launch(Dispatchers.IO) {
            activeRpc.updateSong(
                song = song,
                currentPlaybackTimeMillis = positionMs,
                durationMillis = durationMs,
                playbackSpeed = speed,
                useDetails = AppSettings.discordUseDetails.value,
                status = AppSettings.discordStatus.value,
                button1Text = AppSettings.discordButton1Text.value,
                button1Visible = AppSettings.discordButton1Visible.value,
                button2Text = AppSettings.discordButton2Text.value,
                button2Visible = AppSettings.discordButton2Visible.value,
                activityType = AppSettings.discordActivityType.value,
                activityName = AppSettings.discordActivityName.value,
                audioQuality = audioQuality,
            ).onFailure {
                TrackLog.d("Music", "Discord presence failed: ${it.message}", about = song.videoId)
            }
        }
    }

    private companion object {
        const val DISCORD_TEARDOWN_TIMEOUT_MS = 3_000L
    }
}
