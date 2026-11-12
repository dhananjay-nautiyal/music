package com.music.music.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The live PCM behind the player's visualiser strip.
 *
 * The taps sit on each platform's audio thread — Android's precision sink
 * after the DSP chain's last processor, the desktop engine after its crossfade
 * mix — and hand their finished blocks to [push]. What they hand over is what
 * the listener is actually hearing: post-EQ, post-loudness, pre-hardware-gain.
 *
 * [push] only downmixes to mono into a ring buffer; the FFT and the smoothing
 * belong to [SpectrumAnalyzer], which runs on the strip's own frame loop. That
 * split is the point: the audio thread does a few hundred float adds per block
 * and allocates nothing, while the comparatively expensive transform runs off
 * it entirely, at whatever rate the UI happens to be drawing.
 *
 * Single-writer by construction: one playback pipeline feeds the ring, and
 * only one of the two platforms is ever playing.
 */
object AudioVisualiser {
    /** Bars the strip draws, and so bands the analyzer resolves. */
    const val BAND_COUNT = 24

    /**
     * Samples per analysis window — a power of two for the FFT, and about
     * 21 ms at 48 kHz, short enough that a kick drum's attack is one frame
     * rather than smeared across a quarter-second of them.
     */
    const val FFT_SIZE = 1024

    /** Power-of-two ring, so the window wrap is a mask rather than a branch. */
    private const val RING_SIZE = 8192

    private const val RING_MASK = RING_SIZE - 1

    /**
     * Whether the taps run at all — the Audio visualiser setting, mirrored
     * here because the audio thread must read it without touching a flow.
     * False almost always: the default, and what a user who never opened the
     * setting would leave it at.
     */
    @Volatile
    var enabled: Boolean = false

    private val ring = FloatArray(RING_SIZE)

    /**
     * Even when idle, odd while a [push] is writing — see [latestWindow] for
     * how a reader tells a whole window from a half-written one.
     */
    @Volatile
    private var sequence: Int = 0

    @Volatile
    private var writeAt: Int = 0

    @Volatile
    private var rate: Int = 0

    /**
     * Takes one finished block of interleaved Float32 samples — the audio
     * thread's to call, and cheap enough to leave enabled for every block of
     * every track: a downmix into the ring and two writes to [sequence].
     *
     * @param count total samples in [samples], channels interleaved, so
     *   `count / channels` frames.
     * @param sampleRate what the block is being played at; the analyzer needs
     *   it to place the FFT's bins on a frequency axis.
     */
    fun push(samples: FloatArray, count: Int, channels: Int, sampleRate: Int) {
        if (!enabled || channels <= 0 || count <= 0) return
        rate = sampleRate
        sequence += 1 // odd: a write is in flight
        var at = writeAt
        var index = 0
        while (index + channels <= count) {
            var sum = 0f
            for (channel in 0 until channels) sum += samples[index + channel]
            ring[at] = sum / channels
            at = (at + 1) and RING_MASK
            index += channels
        }
        writeAt = at
        sequence += 1 // even: every sample below [writeAt] is now final
    }

    /**
     * Copies the most recent [out.size] mono samples into [out] and returns
     * the sample rate they were captured at, or 0 when nothing has ever been
     * pushed — an untouched ring holds no audio, and saying so beats drawing
     * whatever zeros happen to be there as if they were a quiet song.
     *
     * The copy is retried when a write lands in the middle of it: [sequence]
     * is odd for the whole of [push], so a reader that saw the same even
     * value before and after the copy read a window no push was touching.
     * A writer starting immediately after the second read can still leave a
     * torn sample or two in a frame that is about to be drawn and thrown
     * away — the visualiser's answer to that is a bar, not a wrong note.
     */
    fun latestWindow(out: FloatArray): Int {
        var attempts = 0
        while (true) {
            val before = sequence
            if (before == 0) return 0
            if (before and 1 == 0) {
                val start = (writeAt - out.size) and RING_MASK
                var i = 0
                while (i < out.size) {
                    out[i] = ring[(start + i) and RING_MASK]
                    i++
                }
                if (sequence == before) return rate
            }
            if (++attempts > 64) return rate
        }
    }

    /** The push count, for a reader deciding whether its window is current. */
    fun currentSequence(): Int = sequence

    /** Drops everything buffered — a stop, and the clean slate tests want. */
    fun reset() {
        sequence = 0
        writeAt = 0
        rate = 0
    }
}

/**
 * Turns the ring's newest window into the smoothed 0..1 band levels the
 * visualiser strip draws.
 *
 * One instance per strip, driven by the strip's own ~30 fps loop — never by
 * the audio thread, which is why it is free to allocate, use doubles, and
 * recompute the band edges whenever the output's sample rate changes.
 *
 * Each frame goes three steps: read the window, resolve it into [bandCount]
 * raw band levels, then ease the drawn levels toward them — fast when a band
 * rises, slow when it falls, so a kick reads as a hit rather than a spike the
 * display could not keep up with.
 *
 * Silence deserves a note of its own. There are two ways for the levels to be
 * zero: fresh audio that contains none, and no fresh audio at all — paused,
 * or a track that just ended. The second cannot be read off the samples (an
 * old window is indistinguishable from a quiet one), so [nextFrame] watches
 * [AudioVisualiser.currentSequence] instead: a handful of ticks without a
 * push is normal block cadence and holds the last levels, and past that the
 * strip falls to silence on its own rather than freezing on a frame from a
 * song that stopped minutes ago.
 */
class SpectrumAnalyzer(
    /** Bars to resolve — [AudioVisualiser.BAND_COUNT] unless a test wants fewer. */
    val bandCount: Int = AudioVisualiser.BAND_COUNT,
) {
    private val window = FloatArray(AudioVisualiser.FFT_SIZE)
    private val real = DoubleArray(AudioVisualiser.FFT_SIZE)
    private val imag = DoubleArray(AudioVisualiser.FFT_SIZE)

    /** Hann coefficients, so the window's edges do not leak into its middle. */
    private val hann = FloatArray(AudioVisualiser.FFT_SIZE).also { table ->
        for (i in table.indices) {
            table[i] = (0.5 - 0.5 * cos(2.0 * PI * i / (AudioVisualiser.FFT_SIZE - 1))).toFloat()
        }
    }

    /** The last levels the analyzer resolved, eased toward — see class docs. */
    private val raw = FloatArray(bandCount)
    private val silence = FloatArray(bandCount)
    private val bands = FloatArray(bandCount)

    /** Bin edges for the last sample rate seen; see [edgesFor]. */
    private val edges = IntArray(bandCount + 1)
    private var edgesRate = 0

    private var lastSequence = AudioVisualiser.currentSequence()
    private var ticksWithoutAudio = 0

    /**
     * Reads the tap and returns this frame's levels — a fresh copy each call,
     * because the strip holds on to what it is handed while it draws it.
     */
    fun nextFrame(): FloatArray {
        val sequence = AudioVisualiser.currentSequence()
        val fresh = sequence != 0 && sequence != lastSequence
        if (fresh) {
            lastSequence = sequence
            ticksWithoutAudio = 0
            val rate = AudioVisualiser.latestWindow(window)
            if (rate > 0) resolve(rate)
        } else {
            ticksWithoutAudio++
        }

        // Normal cadence keeps easing toward the last resolve; only a long
        // stretch without a push switches the target to silence.
        val target = if (ticksWithoutAudio > HOLD_TICKS) silence else raw
        for (i in bands.indices) {
            val goal = target[i].toDouble()
            val step = if (goal > bands[i]) ATTACK else RELEASE
            bands[i] = (bands[i] + (goal - bands[i]) * step).toFloat()
        }
        return bands.copyOf()
    }

    /**
     * The analyzer's bin edges for [sampleRate]: [bandCount] + 1 increasing
     * bin numbers, so band `i` covers bins `edges[i] ..< edges[i+1]`. Exposed
     * because tests place a sine by its band and the edges are the only thing
     * that says which band that is.
     */
    fun edgesFor(sampleRate: Int): IntArray {
        if (edgesRate == sampleRate) return edges
        edgesRate = sampleRate
        // Log spacing: an octave gets the same width of bar however high it
        // is, which is both how ears work and how spectrum displays have
        // looked since the first one.
        var previous = 0
        for (i in 0..bandCount) {
            val hz = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(i.toDouble() / bandCount)
            val bin = (hz * AudioVisualiser.FFT_SIZE / sampleRate).toInt()
                .coerceIn(1, AudioVisualiser.FFT_SIZE / 2)
            edges[i] = if (i == 0) 0 else min(max(bin, previous + 1), AudioVisualiser.FFT_SIZE / 2)
            previous = edges[i]
        }
        return edges
    }

    /** Window -> FFT -> per-band magnitude -> levels relative to the loudest. */
    private fun resolve(rate: Int) {
        for (i in real.indices) {
            real[i] = window[i].toDouble() * hann[i]
            imag[i] = 0.0
        }
        fft()

        val edges = edgesFor(rate)
        var loudest = Double.NEGATIVE_INFINITY
        for (band in 0 until bandCount) {
            val lo = edges[band]
            val hi = edges[band + 1].coerceIn(lo + 1, AudioVisualiser.FFT_SIZE / 2 + 1)
            var sum = 0.0
            for (bin in lo until hi) sum += hypot(real[bin], imag[bin])
            // Averaged rather than summed, so a band's level does not ride on
            // how many bins wide it happens to be.
            val db = 20.0 * log10(sum / (hi - lo) / FULL_SCALE + 1e-9)
            raw[band] = db.toFloat()
            if (db > loudest) loudest = db
        }

        if (loudest < SILENCE_DB) {
            // Below anything a player would call sound: resolve as silence
            // rather than letting every band tie for loudest at full height.
            for (band in 0 until bandCount) raw[band] = 0f
            return
        }
        // Normalise against the frame's loudest band, so the display keeps its
        // full height whether the track was mastered loud or quiet. The range
        // is what keeps a pure tone's spectral skirts — thirty-odd dB down —
        // as skirt rather than as a second bar at half height.
        for (band in 0 until bandCount) {
            raw[band] = ((raw[band] - loudest + RANGE_DB) / RANGE_DB).coerceIn(0.0, 1.0).toFloat()
        }
    }

    /**
     * Unnormalized radix-2 Cooley-Tukey over [real]/[imag] in place — the
     * same transform as the offline analyzer's (`native/analyzer/audio_analysis.cpp`),
     * here because this one has to run in Kotlin on a phone's UI loop rather
     * than be linked into a native tool.
     */
    private fun fft() {
        val size = AudioVisualiser.FFT_SIZE
        var swapped = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (swapped and bit != 0) {
                swapped = swapped xor bit
                bit = bit shr 1
            }
            swapped = swapped xor bit
            if (index < swapped) {
                val realAt = real[index]
                real[index] = real[swapped]
                real[swapped] = realAt
                val imagAt = imag[index]
                imag[index] = imag[swapped]
                imag[swapped] = imagAt
            }
        }
        var length = 2
        while (length <= size) {
            val rootReal = cos(-2.0 * PI / length)
            val rootImag = sin(-2.0 * PI / length)
            var start = 0
            while (start < size) {
                var weightReal = 1.0
                var weightImag = 0.0
                var offset = 0
                while (offset < length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * weightReal - imag[odd] * weightImag
                    val oddImag = real[odd] * weightImag + imag[odd] * weightReal
                    val evenReal = real[even]
                    val evenImag = imag[even]
                    real[even] = evenReal + oddReal
                    imag[even] = evenImag + oddImag
                    real[odd] = evenReal - oddReal
                    imag[odd] = evenImag - oddImag
                    val nextReal = weightReal * rootReal - weightImag * rootImag
                    weightImag = weightReal * rootImag + weightImag * rootReal
                    weightReal = nextReal
                    offset++
                }
                start += length
            }
            length = length shl 1
        }
    }

    private companion object {
        /** Lowest bar's floor and the highest's ceiling, in Hz. */
        const val LOW_HZ = 40.0
        const val HIGH_HZ = 16_000.0

        /** Magnitude a full-scale sine puts in its own bin: FFT_SIZE / 4. */
        const val FULL_SCALE = AudioVisualiser.FFT_SIZE / 4.0

        /** Decibels of range drawn between the loudest band and the floor. */
        const val RANGE_DB = 48.0

        /** A frame whose loudest band sits under this is silence, not music. */
        const val SILENCE_DB = -70.0

        /** Ticks a pause gets to look like a pause before the bars fall. */
        const val HOLD_TICKS = 6

        /** Per-tick ease toward a rising level, and toward a falling one. */
        const val ATTACK = 0.65
        const val RELEASE = 0.16
    }
}
