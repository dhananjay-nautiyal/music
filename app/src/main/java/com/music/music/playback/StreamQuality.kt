package com.music.music.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/*
 * What a decoded stream actually is — depth, rate, channels — measured off the
 * bytes rather than off what the source claimed.
 *
 * This came out of `PlaybackService` (#23). It was written there as private
 * members of a service that holds the player, the queue, the session and the
 * crossfade in the same scope, so none of it could be tested without
 * instantiating all of that. Nothing here reads service state: every function
 * takes a `Format` and returns figures, which is what makes `StreamQualityTest`
 * possible — the FLAC bit-packing below is exactly the kind of off-by-one that
 * wants a test rather than a review.
 */

/** Length of a FLAC STREAMINFO block, which the format fixes at 34 bytes. */
private const val STREAM_INFO_BYTES = 34

/**
 * Depth, rate and channel count for one stream, taken from the stream.
 *
 * Kept as a value rather than read field-by-field off [Format] because
 * for some containers the fields are not all in the same place — see
 * [measure].
 */
internal class Measured(
    val sampleRateHz: Int?,
    val channels: Int?,
    val bitDepth: Int?,
) {
    /**
     * What the samples cost per second once decoded, in kbps.
     *
     * `16-bit · 44.1 kHz · stereo` is 1411, `24-bit · 96 kHz · stereo` is
     * 4608 — the figures Tidal, Qobuz and Apple Music all put next to a
     * lossless track, and the only bitrate that means anything for one.
     * A FLAC's *compressed* rate is a property of how compressible that
     * particular recording was, so two copies of the same master at the
     * same quality report different numbers and the comparison the reader
     * is trying to make doesn't survive it.
     *
     * Null unless all three are known: two thirds of this product is not
     * a bitrate.
     */
    val pcmBitrateKbps: Int?
        get() {
            val depth = bitDepth ?: return null
            val rate = sampleRateHz ?: return null
            val ch = channels ?: return null
            return (depth.toLong() * rate * ch / 1000).toInt().takeIf { it > 0 }
        }
}

/** Sample rate, channel count and bit depth as a FLAC STREAMINFO block states them. */
internal class FlacStreamInfo(val sampleRateHz: Int, val channels: Int, val bitDepth: Int)

/**
 * What the renderer was handed, with the gaps its container left filled in
 * from the stream's own header.
 *
 * A container is free not to state any of this, and one in use here
 * doesn't: Tidal's FLAC arrives inside MP4, whose audio sample entry
 * carries the sample rate as a 16.16 fixed-point field and is written
 * zero, the escape the FLAC-in-ISOBMFF spec allows precisely because the
 * real figure lives in the `dfLa` box below it. Media3 passes that box
 * through as initialization data without folding it back into the
 * `Format`, so `sampleRate` reached the stats line as 0 and `pcmEncoding`
 * as unset — `audio/flac 0.0kHz ?-bit`, on a track that is plain
 * 16-bit/44.1kHz and displayed as such everywhere else.
 *
 * Every figure here still comes from the bytes being decoded. This is not
 * a fallback to what a source claimed; it is the same stream read at the
 * one layer that always states it, because a FLAC frame cannot be decoded
 * without it.
 */
@UnstableApi
internal fun Format.measure(): Measured {
    val streamInfo = flacStreamInfo()
    // The renderer's PCM encoding first: it is the one that would show a
    // 24-bit master being truncated on the way to the sink, which is the
    // whole reason the figure is on screen. STREAMINFO describes the file,
    // so it can only answer what the file holds.
    //
    // Float is the exception, and has to be, because it is a carrier
    // rather than a measurement. `MediaCodecAudioRenderer.getMediaFormat`
    // asks the decoder for float output whenever the sink reports it can
    // take one, so a plain 16-bit/44.1kHz file comes back through this
    // path reading 32-bit exactly as readily as a master does — and then
    // gets called Hi-Res Lossless, and wears the shine under the
    // transport, on the strength of the container it happened to travel
    // in. Nothing about a float buffer says how many bits were in the
    // samples poured into it, so it is not asked.
    val rendererDepth = if (pcmEncoding == C.ENCODING_PCM_FLOAT) null else bitDepthOf(pcmEncoding)
    return Measured(
        sampleRateHz = sampleRate.takeIf { it > 0 } ?: streamInfo?.sampleRateHz,
        channels = channelCount.takeIf { it > 0 } ?: streamInfo?.channels,
        bitDepth = rendererDepth ?: streamInfo?.bitDepth,
    )
}

/**
 * The STREAMINFO block carried in this format's initialization data, for a
 * FLAC stream that has one.
 *
 * The offset is found rather than assumed. Media3 hands the block over
 * with a `fLaC` marker in front of it from both the MP4 and the raw path,
 * but that is its business and not something worth depending on, so all
 * three shapes — marker, bare block header, bare body — are recognised by
 * looking for the header itself: a metadata block type of 0 (STREAMINFO)
 * followed by a 24-bit length of 34.
 */
@UnstableApi
internal fun Format.flacStreamInfo(): FlacStreamInfo? {
    if (sampleMimeType != MimeTypes.AUDIO_FLAC) return null
    val data = initializationData.firstOrNull() ?: return null
    fun byteAt(i: Int) = data[i].toInt() and 0xFF
    fun headerAt(i: Int) = data.size > i + 3 &&
        (byteAt(i) and 0x7F) == 0 &&
        byteAt(i + 1) == 0 && byteAt(i + 2) == 0 && byteAt(i + 3) == STREAM_INFO_BYTES
    val body = when {
        data.size >= 4 && String(data, 0, 4, Charsets.US_ASCII) == "fLaC" ->
            if (headerAt(4)) 8 else 4
        headerAt(0) -> 4
        else -> 0
    }
    if (data.size < body + STREAM_INFO_BYTES) return null
    fun at(i: Int) = byteAt(body + i)
    // STREAMINFO opens with two 16-bit block sizes and two 24-bit frame
    // sizes — ten bytes — and then packs, without alignment, a 20-bit
    // sample rate, a 3-bit channel count and a 5-bit sample depth, the
    // last two both stored one less than they mean.
    val sampleRate = (at(10) shl 12) or (at(11) shl 4) or (at(12) shr 4)
    val channels = ((at(12) shr 1) and 0x07) + 1
    val bitDepth = (((at(12) and 0x01) shl 4) or (at(13) shr 4)) + 1
    return FlacStreamInfo(sampleRate, channels, bitDepth).takeIf { sampleRate > 0 }
}

/**
 * PCM sample depth the renderer settled on, in bits.
 *
 * This is the figure that decides whether a hi-res file is being played as
 * one. A 24-bit FLAC whose renderer reports 16-bit PCM has been truncated
 * somewhere between the decoder and the sink, and no other number on the
 * stats line would show it — the sample rate and the codec both survive
 * that unharmed.
 *
 * `ENCODING_INVALID` and `NO_VALUE` mean the renderer hasn't said, which is
 * common for pass-through and for formats decoded straight to float, and
 * is reported as unknown rather than as a failure.
 *
 * The big-endian encodings are still `@UnstableApi`, so this consumes the
 * opt-in rather than propagating it: the signature is a plain `Int`, and
 * making every caller acknowledge it would be noise.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun bitDepthOf(pcmEncoding: Int): Int? = when (pcmEncoding) {
    C.ENCODING_PCM_8BIT -> 8
    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
    C.ENCODING_PCM_FLOAT -> 32
    else -> null
}
