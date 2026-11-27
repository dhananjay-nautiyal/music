package com.music.music.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.music.data.settings.EdgeGlowColorMode
import com.music.music.playback.AudioVisualiser
import com.music.music.playback.SpectrumAnalyzer
import com.music.music.ui.graphics.ColorUtils
import com.music.music.ui.player.PlayerSettings
import com.music.music.ui.theme.rememberArtworkPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * A soft luminous band hugging the edges of whatever it is applied to — the
 * player's root frame, the floating widget's card — that comes alive with the
 * music.
 *
 * The level comes from the same [SpectrumAnalyzer] the visualiser strip uses,
 * weighted toward the low bands so a kick lands as a pulse rather than a
 * sizzle. The PCM tap itself is gated by [AudioVisualiser.enabled], which
 * belongs to the strip's setting; the glow borrows the gate while it is on
 * screen and hands it back when the last glow goes away, so the two consumers
 * never fight over the audio thread's switch.
 *
 * With no audio to read — the tap off, or silence — the edge settles into a
 * slow breath instead of going dead. "Reduce animation" pins it further: a
 * steady wash, no motion at all.
 *
 * Colour follows [EdgeGlowColorMode]: the artwork's own accent, the theme's
 * primary, or a hue that sweeps around the wheel with the music.
 *
 * Drawn as a stack of concentric strokes rather than a blurred layer — the
 * shared drawing API has no blur primitive, and sixteen strokes along a
 * falloff curve read as a glow for less than a render effect would cost.
 * Over the content rather than under it: the player's backdrop is opaque,
 * so a glow drawn behind it would never be seen, and edge lighting is light
 * falling *on* the surface anyway. The band stays inside the gutters — its
 * reach is shorter than the 30 dp the lists bleed to — so it tints backdrop,
 * never text.
 */
@Composable
fun Modifier.ambientEdgeGlow(
    /** The sleeve ARTWORK mode takes its colour from; null falls back to the theme. */
    artworkUrl: String? = null,
    /** Fixes the colour and bypasses the mode setting — a card's own tint. */
    color: Color? = null,
    /** How far the band reaches in from the edge. */
    spread: Dp = 26.dp,
    /** Corner the band follows; 0 for the full-bleed player. */
    cornerRadius: Dp = 0.dp,
): Modifier {
    val enabled by PlayerSettings.edgeGlowEnabled.collectAsStateWithLifecycle()
    val mode by PlayerSettings.edgeGlowColorMode.collectAsStateWithLifecycle()
    val reduceMotion by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()

    val scheme = MaterialTheme.colorScheme
    val artworkAccent = rememberArtworkPalette(artworkUrl).accent
    val resolved = color ?: when (mode) {
        EdgeGlowColorMode.ARTWORK -> artworkAccent
        EdgeGlowColorMode.ACCENT -> scheme.primary
        EdgeGlowColorMode.SPECTRUM -> artworkAccent
    }

    if (!enabled) return this

    // Both read only inside [drawBehind]: the loop writes them thirty times
    // a second, and a composition read would recompose the whole player to
    // change a colour that only the draw phase looks at.
    val intensity = remember { mutableFloatStateOf(0f) }
    val hueSweep = remember { mutableFloatStateOf(0f) }
    val hsl = remember { FloatArray(3) }

    LaunchedEffect(enabled, reduceMotion) {
        glowTapUsers.incrementAndGet()
        try {
            // The taps sit behind the strip's setting; borrow the gate so the
            // glow pulses with the music even for a listener who never turned
            // the strip on.
            if (!AudioVisualiser.enabled) AudioVisualiser.enabled = true
            val analyzer = SpectrumAnalyzer()
            var breath = 0
            while (isActive) {
                val bands = withContext(Dispatchers.Default) { analyzer.nextFrame() }
                val bassCount = min(BASS_BANDS, bands.size)
                var bass = 0f
                for (i in 0 until bassCount) bass += bands[i]
                bass /= bassCount
                val overall = bands.average().toFloat()
                val target = when {
                    reduceMotion -> STATIC_LEVEL
                    bass > 0f || overall > 0f -> {
                        bass * BASS_WEIGHT + overall * (1f - BASS_WEIGHT)
                    }
                    else -> {
                        breath = (breath + 1) % BREATH_TICKS
                        BREATH_BASE + BREATH_SWING * sin(breath * BREATH_STEP).toFloat()
                    }
                }.coerceIn(0f, 1f)
                val current = intensity.floatValue
                intensity.floatValue = current + (target - current) * EASE
                if (!reduceMotion) {
                    hueSweep.floatValue = (hueSweep.floatValue + HUE_STEP + bass * HUE_BASS_STEP) % 1f
                }
                delay(FRAME_MS)
            }
        } finally {
            // Last glow on screen hands the gate back to whichever setting
            // owns it — the strip's, or nobody's.
            if (glowTapUsers.decrementAndGet() == 0) {
                AudioVisualiser.enabled = PlayerSettings.audioVisualiser.value
            }
        }
    }

    return this.drawWithContent {
        drawContent()
        val level = intensity.floatValue
        if (level <= 0.01f) return@drawWithContent
        val glow = if (mode == EdgeGlowColorMode.SPECTRUM) {
            ColorUtils.colorToHSL(resolved.toArgb(), hsl)
            hsl[0] = (hsl[0] + hueSweep.floatValue * 360f) % 360f
            Color(ColorUtils.HSLToColor(hsl))
        } else {
            resolved
        }
        // Strokes sit centred on the edge, so their outer half falls outside
        // the node: reaching [spread] inward takes a stroke twice that wide.
        val reach = spread.toPx() * 2f
        val arc = cornerRadius.toPx()
        // Widest and faintest first, so each narrower stroke laid on top
        // accumulates toward the edge line: a falloff curve made of
        // source-over compositing instead of a blur.
        for (layer in GLOW_LAYERS downTo 1) {
            val t = layer / GLOW_LAYERS.toFloat()
            val alpha = CORE_ALPHA * (1f - t) * (1f - t) * level
            if (alpha <= MIN_ALPHA) continue
            drawRoundRect(
                color = glow.copy(alpha = alpha),
                cornerRadius = CornerRadius(arc),
                style = Stroke(width = reach * t),
            )
        }
        // The crisp line the falloff hangs from.
        drawRoundRect(
            color = glow.copy(alpha = HAIRLINE_ALPHA * level),
            cornerRadius = CornerRadius(arc),
            style = Stroke(width = HAIRLINE.toPx()),
        )
    }
}

/** Glows currently on screen, so the PCM gate is borrowed once and released once. */
private val glowTapUsers = AtomicInteger(0)

/** One analysis tick — the strip's cadence, so both react to the same frame. */
private const val FRAME_MS = 33L

/** Strokes in the falloff stack; see the draw loop. */
private const val GLOW_LAYERS = 16

/**
 * Per-layer alpha at full intensity. The layers all cover the edge line, so
 * they composite toward roughly 0.7 there and decay cubically outward — this
 * is the curve's height, not its peak.
 */
private const val CORE_ALPHA = 0.23f

/** Below this a layer adds nothing a pixel can see. */
private const val MIN_ALPHA = 0.004f

private val HAIRLINE = 1.5.dp
private const val HAIRLINE_ALPHA = 0.55f

/** Bands whose average makes up the bass half of the level: 40–180 Hz. */
private const val BASS_BANDS = 6
private const val BASS_WEIGHT = 0.65f

/** What the glow rests at under "reduce animation": lit, motionless. */
private const val STATIC_LEVEL = 0.4f

/** The no-audio breath: ~4 seconds a cycle, between these two levels. */
private const val BREATH_TICKS = 121
private const val BREATH_BASE = 0.30f
private const val BREATH_SWING = 0.16f
private val BREATH_STEP = 2.0 * PI / BREATH_TICKS

/** Per-tick ease toward the target — the analyzer already smooths; this only settles. */
private const val EASE = 0.5f

/** A full hue rotation at rest, sped up roughly sixfold by full-scale bass. */
private const val HUE_STEP = 0.0008f
private const val HUE_BASS_STEP = 0.006f
