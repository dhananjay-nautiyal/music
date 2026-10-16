package com.music.music

import com.music.music.data.sources.ParsedPlaylistLink
import com.music.music.data.sources.PlaylistLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistLinkTest {

    @Test
    fun parsesSpotifyLinks() {
        val parsed = PlaylistLink.parse("https://open.spotify.com/playlist/37i9dQZEVXbMDoHDwVN2tF?si=abc")
        assertTrue(parsed is ParsedPlaylistLink.Spotify)
        assertEquals("37i9dQZEVXbMDoHDwVN2tF", (parsed as ParsedPlaylistLink.Spotify).playlistId)
    }

    @Test
    fun parsesYouTubePlaylistLinks() {
        val parsed = PlaylistLink.parse("https://music.youtube.com/playlist?list=PLx0sYbCqOb8Q_GYD5YQ")
        assertTrue(parsed is ParsedPlaylistLink.YouTubePlaylist)
    }

    @Test
    fun parsesYouTubeVideoLinks() {
        val parsed = PlaylistLink.parse("https://youtu.be/dQw4w9WgXcQ")
        assertTrue(parsed is ParsedPlaylistLink.YouTubeVideo)
        assertEquals("dQw4w9WgXcQ", (parsed as ParsedPlaylistLink.YouTubeVideo).videoId)
    }

    @Test
    fun parsesAppleMusicPlaylistLinks() {
        val slug = PlaylistLink.parse("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149")
        assertTrue(slug is ParsedPlaylistLink.AppleMusic)
        assertEquals("pl.f4d106fed2bd41149", (slug as ParsedPlaylistLink.AppleMusic).playlistId)
        val bare = PlaylistLink.parse("pl.f4d106fed2bd41149")
        assertTrue(bare is ParsedPlaylistLink.AppleMusic)
    }

    @Test
    fun rejectsNonPlaylistAppleLinks() {
        assertTrue(
            PlaylistLink.parse("https://music.apple.com/us/album/after-hours/1559523357") is ParsedPlaylistLink.Unsupported,
        )
    }

    @Test
    fun rejectsGarbage() {
        assertTrue(PlaylistLink.parse("not a link") is ParsedPlaylistLink.Unsupported)
        assertTrue(PlaylistLink.parse("") is ParsedPlaylistLink.Unsupported)
    }
}
