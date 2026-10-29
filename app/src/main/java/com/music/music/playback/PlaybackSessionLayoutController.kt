package com.music.music.playback

import android.content.Context
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.common.util.UnstableApi
import com.music.music.R
import com.music.music.data.LikeState
import com.music.music.data.model.LikeStatus
import com.music.music.data.model.Song

/**
 * Keeps the notification and automotive custom layouts in sync with playback.
 *
 * The notification controller only gets Favorite and Shuffle. While a car is
 * connected, its layout is mirrored to the notification controller too because
 * Android Auto reads the platform session's custom actions.
 */
@UnstableApi
internal class PlaybackSessionLayoutController(
    private val context: Context,
    private val favoriteCommand: SessionCommand,
    private val shuffleCommand: SessionCommand,
    private val startStationCommand: SessionCommand,
    private val revertToOriginalCommand: SessionCommand,
    private val currentSong: () -> Song?,
    private val favoriteStatus: () -> LikeStatus?,
    private val shuffleEnabled: () -> Boolean,
) {
    private val carControllers = mutableSetOf<MediaSession.ControllerInfo>()

    fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)) {
            carControllers += controller
            refresh(session)
        }
    }

    fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (carControllers.remove(controller)) refresh(session)
    }

    fun refresh(session: MediaSession?) {
        session ?: return
        val carLayout = carButtons()
        session.setCustomLayout(carLayout)
        session.getMediaNotificationControllerInfo()?.let { controller ->
            session.setCustomLayout(
                controller,
                if (carControllers.isEmpty()) notificationButtons() else carLayout,
            )
        }
    }

    private fun notificationButtons(): List<CommandButton> {
        val favorite = CommandButton.Builder(
            if (favoriteStatus() == LikeStatus.LIKE) {
                CommandButton.ICON_HEART_FILLED
            } else {
                CommandButton.ICON_HEART_UNFILLED
            },
        )
            .setSessionCommand(favoriteCommand)
            .setDisplayName("Favorite")
            .build()
        val isShuffleEnabled = shuffleEnabled()
        val shuffle = CommandButton.Builder(
            if (isShuffleEnabled) {
                CommandButton.ICON_SHUFFLE_ON
            } else {
                CommandButton.ICON_SHUFFLE_OFF
            },
        )
            .setSessionCommand(shuffleCommand)
            .setDisplayName(if (isShuffleEnabled) "Shuffle off" else "Shuffle on")
            .build()
        return listOf(favorite, shuffle)
    }

    private fun carButtons(): List<CommandButton> {
        val current = currentSong()
        val station = current
            ?.takeIf { it.canStartStation() }
            ?.let {
                CommandButton.Builder(CommandButton.ICON_RADIO)
                    .setSessionCommand(startStationCommand)
                    .setDisplayName(context.getString(R.string.start_radio))
                    .build()
            }
        val revert = current
            ?.takeIf {
                it.hasYouTubeOriginal() &&
                    it.localUri == null &&
                    !OriginalVersion.isPinned(it.videoId)
            }
            ?.let {
                CommandButton.Builder(CommandButton.ICON_SYNC)
                    .setSessionCommand(revertToOriginalCommand)
                    .setDisplayName(context.getString(R.string.revert_to_original))
                    .build()
            }
        return notificationButtons() + listOfNotNull(station, revert)
    }
}
