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
    fun rejectsGarbage() {
        assertTrue(PlaylistLink.parse("not a link") is ParsedPlaylistLink.Unsupported)
        assertTrue(PlaylistLink.parse("") is ParsedPlaylistLink.Unsupported)
    }
}
