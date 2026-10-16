package com.music.music.data.apple

import com.music.music.data.spotify.SpotifyImportTrack

/**
 * Apple Music playlist import, mirroring the Spotify path: fetch the
 * playlist's tracks from the catalogue API, then match each to YouTube with
 * the shared resolver ([com.music.music.data.spotify.SpotifyImporter.resolveToSongs]).
 *
 * Only catalogue playlists are importable — a user's private library needs
 * their Music User Token, which this app never asks for. Curated and shared
 * public playlists resolve without any sign-in.
 */
object AppleMusicImporter {

    /** Playlist id out of pasted text; null when this isn't an Apple playlist link. */
    fun extractPlaylistId(raw: String): String? = AppleMusicService.extractPlaylistId(raw)

    /** The playlist's title and its tracks as matcher-ready title/artist pairs. */
    suspend fun fetchPlaylistTracks(playlistId: String): Pair<String, List<SpotifyImportTrack>> {
        val (title, entries) = AppleMusicService.playlistTracks(playlistId)
        return title to entries.map { SpotifyImportTrack(title = it.title, artist = it.artist) }
    }
}
