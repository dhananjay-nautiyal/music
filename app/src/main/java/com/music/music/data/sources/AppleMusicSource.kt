package com.music.music.data.sources

import com.music.music.data.TrackLog
import com.music.music.data.apple.AppleMusicService
import com.music.music.data.model.Song

private const val TAG = "Music"

/**
 * Apple Music catalogue as a source: search rows and 30-second previews.
 *
 * Ranked below YouTube on purpose ([SourceKind.APPLE_MUSIC]), so these rows
 * sit under YouTube's in search and are never picked as silent substitutes
 * or mid-play upgrades — both of those walks only ask sources ranked above
 * YouTube. Tapping an Apple row plays its preview explicitly, which is the
 * only Apple audio any third-party player can open (full tracks are
 * FairPlay DRM). The row's `0:30` duration says exactly that.
 */
class AppleMusicSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.APPLE_MUSIC
    override val displayName: String get() = config.label.ifBlank { SourceKind.APPLE_MUSIC.label }

    /** Reachable when the shared web-player token scrapes; no account needed. */
    override suspend fun health(): SourceHealth =
        if (AppleMusicService.ping()) SourceHealth.Ok("Catalog reachable")
        else SourceHealth.Unreachable("Apple catalogue unreachable")

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        TrackLog.d(TAG, "▶ Apple Music searchSongs() query=\"$query\" limit=$limit")
        val results = AppleMusicService.searchSongs(query, limit)
        TrackLog.d(TAG, "  ✓ Apple Music returned ${results.size} tracks")
        return results.map { track ->
            Song(
                videoId = SourceRegistry.trackKey(config.id, track.id),
                title = track.title,
                artist = track.artist,
                albumName = track.album,
                thumbnailUrl = track.artworkUrl,
                // Previews run about thirty seconds — the duration states what
                // tapping this row actually plays.
                durationText = PREVIEW_DURATION_TEXT,
                sourceQuality = PREVIEW_QUALITY,
                isExplicit = track.isExplicit,
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val preview = AppleMusicService.track(trackId)?.previewUrl?.takeIf { it.isNotBlank() }
        if (preview.isNullOrBlank()) {
            TrackLog.w(TAG, "  ✗ Apple Music had no preview for $trackId")
            return null
        }
        // Unstated bitrate on purpose: a download has to name a file before
        // the first byte lands, and an undescribed copy is rightly refused
        // there — previews must never be filed as tracks.
        return SourceStream(
            url = preview,
            format = StreamFormat(codec = "m4a"),
        )
    }

    private companion object {
        const val PREVIEW_DURATION_TEXT = "0:30"
        const val PREVIEW_QUALITY = "PREVIEW"
    }
}
