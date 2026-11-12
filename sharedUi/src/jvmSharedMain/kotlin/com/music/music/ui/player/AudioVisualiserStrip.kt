package com.music.music.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.music.playback.AudioVisualiser
import com.music.music.playback.SpectrumAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * The live spectrum: 24 accent-lit bars between the artwork and the transport.
 *
 * The strip itself draws nothing interesting — a row of rounded bars whose
 * height is [SpectrumAnalyzer]'s answer for this frame. All the interest is in
 * where the numbers come from: [AudioVisualiser]'s tap on each platform's
 * audio thread, and an analysis loop that runs off the composition thread so
 * the FFT never competes with a frame being drawn.
 *
 * Purely decorative, so it carries no semantics: a screen reader announcing
 * bar heights thirty times a second would be a bug, not accessibility.
 * Settings turns it off by default; this composable simply does not exist
 * until the setting says otherwise, tap and loop included.
 */
@Composable
internal fun AudioVisualiserStrip(modifier: Modifier = Modifier) {
    val enabled by PlayerSettings.audioVisualiser.collectAsStateWithLifecycle()
    if (!enabled) return

    val primary = MaterialTheme.colorScheme.primary
    var bands by remember { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(Unit) {
        val analyzer = SpectrumAnalyzer()
        while (isActive) {
            // Analysis on the default dispatcher: a 1024-point FFT is a few
            // hundred microseconds of work that has no business sitting in
            // front of a frame deadline, and the bars would not notice the
            // extra millisecond.
            bands = withContext(Dispatchers.Default) { analyzer.nextFrame() }
            delay(FRAME_MS)
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(HEIGHT),
    ) {
        if (bands.isEmpty()) return@Canvas
        val slot = size.width / bands.size
        val barWidth = slot * 0.58f
        val gradient = Brush.verticalGradient(
            listOf(primary, primary.copy(alpha = 0.45f)),
        )
        for (index in bands.indices) {
            val level = bands[index].coerceIn(0f, 1f)
            if (level <= 0f) continue
            val height = level * size.height
            drawRoundRect(
                brush = gradient,
                topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(minOf(barWidth, height) / 2f),
            )
        }
    }
}

/** One analysis tick, chosen to read as motion without burning a core. */
private const val FRAME_MS = 33L

/** The strip's band of the deck — a meter's height, not a chart's. */
private val HEIGHT = 40.dp
