package com.music.music.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The FLAC header reading and the depth precedence that [Format.measure] applies.
 *
 * These were private members of [PlaybackService] until #23 moved them into
 * `StreamQuality.kt`, which meant the only way to exercise them was to play a track and read the
 * stats line: the bit packing below — a 20-bit rate, a 3-bit channel count and a 5-bit depth
 * sharing four bytes, the last two stored one less than they mean — is exactly the kind of
 * off-by-one that wants an assertion rather than a reviewer.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class StreamQualityTest {

    private companion object {
        /** Length of a STREAMINFO body, which the format fixes at 34 bytes. */
        const val STREAM_INFO_BYTES = 34

        /** A metadata block header for STREAMINFO: type 0, then a 24-bit length of 34. */
        val BLOCK_HEADER = byteArrayOf(0x00, 0x00, 0x00, STREAM_INFO_BYTES.toByte())

        /** The `fLaC` magic some demuxers put in front of the block. */
        val FLAC_MARKER = "fLaC".toByteArray(Charsets.US_ASCII)
    }

    /**
     * A STREAMINFO body stating one rate, channel count and depth.
     *
     * Packed as the format packs it, big-endian across bytes 10..13:
     * `rate:20 | channels-1:3 | depth-1:5`, then four unused bits.
     */
    private fun streamInfoBody(sampleRateHz: Int, channels: Int, bitDepth: Int): ByteArray {
        val packed = (sampleRateHz shl 12) or ((channels - 1) shl 9) or ((bitDepth - 1) shl 4)
        // The ten bytes before the packing are the min/max block and frame sizes. Real values, so
        // that nothing here depends on which of them the reader happens to look at.
        return ByteArray(STREAM_INFO_BYTES).also { body ->
            byteArrayOf(0x10, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                .copyInto(body, 0, 0, 10)
            body[10] = (packed ushr 24).toByte()
            body[11] = (packed ushr 16).toByte()
            body[12] = (packed ushr 8).toByte()
            body[13] = packed.toByte()
        }
    }

    private fun flacFormat(
        initializationData: ByteArray,
        sampleRate: Int = Format.NO_VALUE,
        channelCount: Int = Format.NO_VALUE,
        pcmEncoding: Int = C.ENCODING_INVALID,
    ): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_FLAC)
        .setInitializationData(listOf(initializationData))
        .setSampleRate(sampleRate)
        .setChannelCount(channelCount)
        .setPcmEncoding(pcmEncoding)
        .build()

    // ---- STREAMINFO reading ------------------------------------------------

    @Test
    fun readsThePackingOutOfABareStreamInfoBody() {
        val info = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setInitializationData(listOf(streamInfoBody(44100, 2, 16)))
            .build()
            .flacStreamInfo()
        assertEquals(44100, info?.sampleRateHz)
        assertEquals(2, info?.channels)
        assertEquals(16, info?.bitDepth)
    }

    /**
     * Media3 hands the block over in whichever of these shapes the demuxer happened to produce, and
     * the reader finds the offset by looking for the header rather than assuming a layout.
     */
    @Test
    fun acceptsEveryShapeTheBlockArrivesIn() {
        val body = streamInfoBody(96000, 2, 24)
        val shapes = listOf(
            "marker + header + body" to FLAC_MARKER + BLOCK_HEADER + body,
            "header + body" to BLOCK_HEADER + body,
            "marker + body, no header" to FLAC_MARKER + body,
            "body alone" to body,
        )
        for ((name, data) in shapes) {
            val info = flacFormat(data).flacStreamInfo()
            assertEquals("$name: rate", 96000, info?.sampleRateHz)
            assertEquals("$name: channels", 2, info?.channels)
            assertEquals("$name: depth", 24, info?.bitDepth)
        }
    }

    @Test
    fun saysNothingForAStreamThatIsNotFlac() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_MP4)
            .setInitializationData(listOf(streamInfoBody(44100, 2, 16)))
            .build()
        assertNull(format.flacStreamInfo())
    }

    @Test
    fun saysNothingWithoutInitializationData() {
        val format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).build()
        assertNull(format.flacStreamInfo())
    }

    @Test
    fun saysNothingWhenTheBlockIsTruncated() {
        val headerAndPart = BLOCK_HEADER + streamInfoBody(44100, 2, 16).copyOf(20)
        assertNull(flacFormat(headerAndPart).flacStreamInfo())
    }

    /** A zero rate is not a 34-byte block that happens to read as silence; it is no information. */
    @Test
    fun ignoresAHeaderWhoseSampleRateIsZero() {
        assertNull(flacFormat(streamInfoBody(0, 2, 16)).flacStreamInfo())
    }

    // ---- depth precedence inside measure() ----------------------------------

    /**
     * The bug this pins: Tidal's FLAC arrives inside MP4, whose sample entry writes the rate as
     * zero, so the container says nothing and only the header does.
     */
    @Test
    fun fillsTheGapsTheContainerLeftFromTheHeaders() {
        val measured = flacFormat(
            streamInfoBody(96000, 2, 24),
            sampleRate = 0,
            channelCount = 0,
            pcmEncoding = C.ENCODING_INVALID,
        ).measure()
        assertEquals(96000, measured.sampleRateHz)
        assertEquals(2, measured.channels)
        assertEquals(24, measured.bitDepth)
    }

    /**
     * A 24-bit file truncated to 16-bit PCM on the way to the sink has to *show* as 16-bit: the
     * renderer's word wins over the file's, because it is the one figure the listener is hearing.
     */
    @Test
    fun theRendererDepthBeatsWhatTheFileHolds() {
        val measured = flacFormat(
            streamInfoBody(96000, 2, 24),
            sampleRate = 96000,
            channelCount = 2,
            pcmEncoding = C.ENCODING_PCM_16BIT,
        ).measure()
        assertEquals(16, measured.bitDepth)
    }

    /**
     * Float is a carrier, not a measurement. A plain 16-bit/44.1 file played through a float sink
     * arrives reading 32-bit, and must not therefore be called 32-bit: the depth comes back from
     * the header instead.
     */
    @Test
    fun aFloatBufferIsNotTreatedAsAMeasuredDepth() {
        val measured = flacFormat(
            streamInfoBody(44100, 2, 16),
            sampleRate = 44100,
            channelCount = 2,
            pcmEncoding = C.ENCODING_PCM_FLOAT,
        ).measure()
        assertEquals(16, measured.bitDepth)
    }

    /** A stated rate is never second-guessed by the header, even when both are present. */
    @Test
    fun aRateFromTheContainerIsKept() {
        val measured = flacFormat(
            streamInfoBody(44100, 2, 16),
            sampleRate = 48000,
            channelCount = 1,
            pcmEncoding = C.ENCODING_PCM_24BIT,
        ).measure()
        assertEquals(48000, measured.sampleRateHz)
        assertEquals(1, measured.channels)
        assertEquals(24, measured.bitDepth)
    }

    @Test
    fun reportsNothingWhenNeitherLayerSpeaks() {
        val measured = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_MP4)
            .build()
            .measure()
        assertNull(measured.sampleRateHz)
        assertNull(measured.channels)
        assertNull(measured.bitDepth)
        assertNull(measured.pcmBitrateKbps)
    }

    // ---- the derived figure and the encoding table --------------------------

    @Test
    fun pcmBitrateIsTheProductOfAllThreeFigures() {
        assertEquals(1411, Measured(44100, 2, 16).pcmBitrateKbps)
        assertEquals(4608, Measured(96000, 2, 24).pcmBitrateKbps)
        // Null unless all three are known: two thirds of this product is not a bitrate.
        assertNull(Measured(44100, 2, null).pcmBitrateKbps)
        assertNull(Measured(null, 2, 16).pcmBitrateKbps)
        assertNull(Measured(44100, null, 16).pcmBitrateKbps)
    }

    @Test
    fun mapsEveryPcmEncodingTheRendererReports() {
        assertEquals(8, bitDepthOf(C.ENCODING_PCM_8BIT))
        assertEquals(16, bitDepthOf(C.ENCODING_PCM_16BIT))
        assertEquals(16, bitDepthOf(C.ENCODING_PCM_16BIT_BIG_ENDIAN))
        assertEquals(24, bitDepthOf(C.ENCODING_PCM_24BIT))
        assertEquals(24, bitDepthOf(C.ENCODING_PCM_24BIT_BIG_ENDIAN))
        assertEquals(32, bitDepthOf(C.ENCODING_PCM_32BIT))
        assertEquals(32, bitDepthOf(C.ENCODING_PCM_32BIT_BIG_ENDIAN))
        assertEquals(32, bitDepthOf(C.ENCODING_PCM_FLOAT))
        // Unknown is reported as unknown, not as a failure and not as a guess.
        assertNull(bitDepthOf(C.ENCODING_INVALID))
        assertNull(bitDepthOf(Format.NO_VALUE))
    }
}
