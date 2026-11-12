package com.music.music.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.music.playback.AudioVisualiser
import com.music.music.ui.theme.AccentColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The toggles read far from the Settings sheet that sets them.
 *
 * Mirrors Android's `AppSettings` for these few: a composable deep in the player or the lyrics
 * pane can collect one without it being threaded down through every caller.
 */
internal object DesktopAppearanceSettings {

    private fun flag(key: String, default: Boolean) =
        MutableStateFlow(DesktopPersistence().boolean(key, default))

    private val _reduceAnimation = flag(KEY_REDUCE_ANIMATION, false)
    private val _reduceDynamicBlur = flag(KEY_REDUCE_DYNAMIC_BLUR, false)
    private val _hideVolumeBar = flag(KEY_HIDE_VOLUME_BAR, false)
    private val _ambientBackdrop = flag(KEY_AMBIENT_BACKDROP, false)

    /**
     * The accent in force — the colour the chrome's spinners, checks, ticks and
     * buttons are painted in.
     *
     * Snapshot state rather than a flow like the flags above, because
     * [DesktopAccent] reads it as a plain value at seventy-odd call sites and
     * only a snapshot read redraws the ones already on screen; a flow would
     * have to be collected at every one of them.
     */
    private var _accent by mutableStateOf(readAccent())

    /** The accent in force. See [_accent] for why this is not a flow. */
    val accent: AccentColor
        get() = _accent

    fun setAccent(value: AccentColor) {
        DesktopPersistence().saveString(KEY_ACCENT, value.name)
        _accent = value
    }

    /** Stored name, or the brand blue the app ships with when there is none. */
    private fun readAccent(): AccentColor = runCatching {
        AccentColor.valueOf(DesktopPersistence().string(KEY_ACCENT))
    }.getOrDefault(AccentColor.BLUE)

    private val _audioVisualiser = MutableStateFlow(
        DesktopPersistence().boolean(KEY_AUDIO_VISUALISER, false),
    )

    init {
        // Before the first frame of anything, or the tap would be reading a
        // setting the process had not been told about yet.
        AudioVisualiser.enabled = _audioVisualiser.value
    }

    /** Live spectrum bars between the artwork and the transport controls. */
    val audioVisualiser: StateFlow<Boolean> = _audioVisualiser

    fun setAudioVisualiser(value: Boolean) {
        DesktopPersistence().saveBoolean(KEY_AUDIO_VISUALISER, value)
        AudioVisualiser.enabled = value
        _audioVisualiser.value = value
    }

    /** Freezes the main player's gradient instead of drifting. */
    val reduceAnimation: StateFlow<Boolean> = _reduceAnimation

    /** Swaps frosted glass for solid fills across the app. */
    val reduceDynamicBlur: StateFlow<Boolean> = _reduceDynamicBlur

    /** Removes the volume slider from the main player. */
    val hideVolumeBar: StateFlow<Boolean> = _hideVolumeBar

    /** Diffuses current artwork colors under the window glass chrome like Apple Music. */
    val ambientBackdrop: StateFlow<Boolean> = _ambientBackdrop

    fun setReduceAnimation(value: Boolean) = write(KEY_REDUCE_ANIMATION, value, _reduceAnimation)

    fun setReduceDynamicBlur(value: Boolean) = write(KEY_REDUCE_DYNAMIC_BLUR, value, _reduceDynamicBlur)

    fun setHideVolumeBar(value: Boolean) = write(KEY_HIDE_VOLUME_BAR, value, _hideVolumeBar)

    fun setAmbientBackdrop(value: Boolean) = write(KEY_AMBIENT_BACKDROP, value, _ambientBackdrop)

    private fun write(key: String, value: Boolean, into: MutableStateFlow<Boolean>) {
        DesktopPersistence().saveBoolean(key, value)
        into.value = value
    }

    internal const val KEY_REDUCE_ANIMATION = "reduce_animation"
    internal const val KEY_REDUCE_DYNAMIC_BLUR = "reduce_dynamic_blur"
    internal const val KEY_HIDE_VOLUME_BAR = "hide_volume_bar"
    internal const val KEY_AMBIENT_BACKDROP = "ambient_backdrop"
    internal const val KEY_ACCENT = "accent_color"
    internal const val KEY_AUDIO_VISUALISER = "audio_visualiser"
}
