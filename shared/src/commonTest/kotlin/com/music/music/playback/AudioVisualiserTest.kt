package com.music.music.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tap and the analyzer that feeds the player's visualiser strip.
 *
 * Every test starts from [AudioVisualiser.reset]: the ring is a process-wide
 * singleton, the same one both platforms' audio threads write to, so a test
 * that inherited the previous test's buffer would be asserting on a song that
 * is no longer playing.
 */
class AudioVisualiserTest {

    private fun clear(enabled: Boolean = true) {
        AudioVisualiser.reset()
        AudioVisualiser.enabled = enabled
    }

    /** [frames] of a bin-centred [hz] sine, same on both channels. */
    private fun pushSine(hz: Double, frames: Int = 4096, sampleRate: Int = 48_000) {
        val samples = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val value = sin(2.0 * PI * hz * i / sampleRate).toFloat()
            samples[i * 2] = value
            samples[i * 2 + 1] = value
        }
        AudioVisualiser.push(samples, samples.size, 2, sampleRate)
    }

    @Test
    fun aDisabledTapIsIgnored() {
        clear(enabled = false)
        pushSine(984.375)
        assertEquals(0, AudioVisualiser.latestWindow(FloatArray(AudioVisualiser.FFT_SIZE)))
    }

    @Test
    fun anUntouchedRingReportsNoAudioRatherThanSilence() {
        clear()
        assertEquals(0, AudioVisualiser.latestWindow(FloatArray(AudioVisualiser.FFT_SIZE)))
    }

    @Test
    fun theRingDownmixesToMonoAtTheRightRate() {
        clear()
        val stereo = FloatArray(2048) { 0.5f }
        AudioVisualiser.push(stereo, stereo.size, 2, 44_100)

        val window = FloatArray(16)
        val rate = AudioVisualiser.latestWindow(window)
        assertEquals(44_100, rate)
        assertTrue(window.all { abs(it - 0.5f) < 1e-6f })
    }

    @Test
    fun oppositeChannelsCancelInTheDownmix() {
        clear()
        // A stereo signal whose channels are perfectly out of phase — the
        // case a naive "left channel only" tap would draw as full scale.
        val stereo = FloatArray(2048) { if (it % 2 == 0) 1f else -1f }
        AudioVisualiser.push(stereo, stereo.size, 2, 48_000)

        val window = FloatArray(32)
        AudioVisualiser.latestWindow(window)
        assertTrue(window.all { abs(it) < 1e-6f })
    }

    @Test
    fun bandsSpanTheSpectrumInLogarithmicSteps() {
        val analyzer = SpectrumAnalyzer()
        val edges = analyzer.edgesFor(48_000)

        assertEquals(AudioVisualiser.BAND_COUNT + 1, edges.size)
        assertEquals(0, edges.first())
        for (i in 1 until edges.size) {
            assertTrue(edges[i] > edges[i - 1], "edge $i (${edges[i]}) must clear ${edges[i - 1]}")
        }
        // The top edge lands just under 16 kHz at 48 kHz: 341 bins wide.
        val topHz = edges.last() * 48_000.0 / AudioVisualiser.FFT_SIZE
        assertTrue(topHz in 15_000.0..17_000.0, "top edge at $topHz Hz")
    }

    @Test
    fun aSineLightsItsOwnBandAboveTheRest() {
        clear()
        val analyzer = SpectrumAnalyzer()
        // 984.375 Hz is exactly bin 21 at 48 kHz, so the tone lands in one
        // bin rather than straddling two and splitting its level between them.
        pushSine(984.375)

        analyzer.nextFrame() // the attack's first step
        val bands = analyzer.nextFrame()
        val edges = analyzer.edgesFor(48_000)
        val toneBin = (984.375 * AudioVisualiser.FFT_SIZE / 48_000).toInt()
        val expected = edges.indexOfLast { it <= toneBin }

        val loudest = bands.indices.maxBy { bands[it] }
        assertEquals(expected, loudest, "loudest band $loudest, expected $expected")
        assertTrue(bands[loudest] >= 0.8f, "loudest band at ${bands[loudest]}")
        // Far from the tone there is only the Hann window's floor, which is
        // nothing: the neighbouring bands carry the leakage, not the sky.
        val far = bands.filterIndexed { i, _ -> abs(i - loudest) >= 4 }
        assertTrue(far.all { it < 0.2f }, "far bands: ${far.joinToString()}")
    }

    @Test
    fun digitalSilenceResolvesAsNoBarsRatherThanAllOfThem() {
        clear()
        val analyzer = SpectrumAnalyzer()
        // Quiet enough to sit under the silence floor. The frame's loudness
        // normalisation would otherwise be its undoing: with every band tied
        // at zero, each would be zero distance from the loudest and drawn
        // full height — silence as a solid wall of bars.
        val zeros = FloatArray(8192)
        AudioVisualiser.push(zeros, zeros.size, 2, 48_000)

        val bands = repeat(4) { analyzer.nextFrame() }.let { analyzer.nextFrame() }
        assertTrue(bands.all { it < 0.01f }, "digital silence: ${bands.joinToString()}")
    }

    @Test
    fun aPauseFallsAwayButAHeldFrameDoesNotFlash() {
        clear()
        val analyzer = SpectrumAnalyzer()
        pushSine(984.375)

        val first = analyzer.nextFrame()
        val edges = analyzer.edgesFor(48_000)
        val toneBin = (984.375 * AudioVisualiser.FFT_SIZE / 48_000).toInt()
        val tone = edges.indexOfLast { it <= toneBin }
        assertTrue(first[tone] >= 0.5f, "first frame already up at ${first[tone]}")

        // The tap goes quiet — a pause, a track ending. The first few ticks
        // are ordinary block cadence and must not drop the bars; after the
        // hold they ease down rather than vanish in one frame.
        AudioVisualiser.reset()
        val duringHold = (0 until 10).fold(first[tone]) { level, tick ->
            analyzer.nextFrame()[tone].also { held ->
                if (tick < 6) assertTrue(held >= level - 0.15f, "fell at tick $tick")
            }
        }
        assertTrue(duringHold >= 0.25f, "still visible during the fall: $duringHold")

        val settled = repeat(40) { analyzer.nextFrame() }.let { analyzer.nextFrame() }
        assertTrue(settled.all { it < 0.05f }, "never settled: ${settled.joinToString()}")
    }

    @Test
    fun theTapStopsTheMomentTheSettingDoes() {
        clear(enabled = true)
        val silent = FloatArray(64)
        AudioVisualiser.push(silent, silent.size, 2, 48_000)
        val before = AudioVisualiser.currentSequence()
        assertTrue(before > 0)

        AudioVisualiser.enabled = false
        AudioVisualiser.push(silent, silent.size, 2, 48_000)
        assertEquals(before, AudioVisualiser.currentSequence())
    }
}
