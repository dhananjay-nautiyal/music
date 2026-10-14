package com.music.bitchord.data.sources

/**
 * "music" APK facade over every catalogue BitChord speaks.
 *
 * Same logic, rebranded install identity (`com.music.music`, launcher name
 * "Music"). No protocol was reimplemented: each entry below delegates to the
 * existing source implementation so behaviour stays identical to BitChord v1.8.
 *
 * Platforms wired through [SourceRegistry] / [SourceResolver]:
 * - [SourceKind.YOUTUBE] -> [YouTubeSource]: full catalogue, home feed, radio,
 *   related queue. Lossy Opus ~171kbps. Only source for feeds/radio.
 * - [SourceKind.JIOSAAVN] -> [JioSaavnSource]: optional 320kbps AAC/MP4,
 *   prefetched (~0.4s) ahead of playback. Catalogue matching can mismatch.
 * - [SourceKind.ADDON] -> [AddonSource]: user-hosted HTTP addon server
 *   (/manifest.json, /search, /stream). Hi-Res/FLAC capable, no code download.
 * - [SourceKind.MODULE] / [SourceKind.CUSTOM_MODULE] -> [ModuleSource]:
 *   legacy QuickJS-sandboxed JS plugins (Tidal/Qobuz/Apple Music style
 *   backends via module index). Kept working, no longer offered as new.
 * - Spotify: import path, not a live source — see
 *   `data/spotify/SpotifyImporter.kt` + `SpotifyLibrary.kt` (playlists, Liked
 *   Songs import into local playlists).
 * - Local library: on-device audio via MediaStore (READ_MEDIA_AUDIO).
 * - Lyrics: 11-provider chain (lrc.red, BiniLyrics, BetterLyrics, PaxSenix,
 *   LyricsPlus, SimpMusic, Unison, Megalobiz, KuGou, LRCLIB, Musixmatch,
 *   Genius) + Apple-like word-synced animation.
 * - Scrobble/connect: Last.fm, ListenBrainz, Discord Rich Presence.
 *
 * Rank order (low first) is defined by [SourceKind.rank]: user addons (0),
 * module (1), JioSaavn (2), YouTube (3). See [SourceResolver] for the walk.
 */
object MusicPlatforms {
    const val APP_NAME = "Music"
    const val APPLICATION_ID = "com.music.music"
    const val DEV_APPLICATION_ID = "com.dev.music"
    const val UPSTREAM = "kushagrasinghx/BitChord (GPL-3.0)"
    const val UPSTREAM_VERSION = "v1.8"

    /** All live-stream kinds available in this build, in resolve order. */
    val liveKinds: List<SourceKind> = listOf(
        SourceKind.ADDON,
        SourceKind.CUSTOM_MODULE,
        SourceKind.MODULE,
        SourceKind.JIOSAAVN,
        SourceKind.YOUTUBE,
    )

    fun describe(): String = buildString {
        appendLine("$APP_NAME ($APPLICATION_ID), logic from $UPSTREAM $UPSTREAM_VERSION")
        liveKinds.forEach { appendLine("- ${it.label}: ${it.detail}") }
        appendLine("- Spotify: import-only (playlists + Liked Songs)")
        appendLine("- Local: MediaStore on-device library")
    }
}
