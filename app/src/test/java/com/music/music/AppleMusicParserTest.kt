package com.music.music

import com.music.music.data.apple.AppleMusicService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicParserTest {

    @Test
    fun parsesSearchTracks() {
        val tracks = AppleMusicService.parseTracks(SEARCH_JSON, 25)
        assertEquals(2, tracks.size)
        val first = tracks[0]
        assertEquals("1440833847", first.id)
        assertEquals("Blinding Lights", first.title)
        assertEquals("The Weeknd", first.artist)
        assertEquals("After Hours", first.album)
        assertEquals("https://example.com/500x500bb.jpg", first.artworkUrl)
        assertEquals("https://example.com/preview.m4a", first.previewUrl)
        assertTrue(first.isExplicit)
        // A hit without a name is not a track.
        assertTrue(tracks.none { it.title.isBlank() })
    }

    @Test
    fun parsesDirectLookupShape() {
        val tracks = AppleMusicService.parseTracks(LOOKUP_JSON, 1)
        assertEquals(1, tracks.size)
        assertEquals("Levitating", tracks[0].title)
    }

    @Test
    fun parsesPlaylistPage() {
        val page = AppleMusicService.parsePlaylistPage(PLAYLIST_JSON)
        assertEquals("Today's Hits", page.title)
        assertEquals(2, page.tracks.size)
        assertEquals("Espresso", page.tracks[0].title)
        assertEquals("Sabrina Carpenter", page.tracks[0].artist)
        assertEquals(
            "/v1/catalog/us/playlists/pl.f4d106fed2bd41149/relationships/tracks?offset=2&limit=2",
            page.next,
        )
    }

    @Test
    fun extractPlaylistIdAcceptsForms() {
        assertEquals(
            "pl.f4d106fed2bd41149",
            AppleMusicService.extractPlaylistId("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149"),
        )
        assertEquals(
            "pl.u-9N9LLz4d4d4d4d4",
            AppleMusicService.extractPlaylistId("https://music.apple.com/in/playlist/bollywood-hits/pl.u-9N9LLz4d4d4d4d4?i=123"),
        )
        assertEquals("pl.abc123", AppleMusicService.extractPlaylistId("pl.abc123"))
    }

    @Test
    fun extractPlaylistIdRejectsOthers() {
        assertNull(AppleMusicService.extractPlaylistId("https://music.apple.com/us/album/after-hours/1559523357"))
        assertNull(AppleMusicService.extractPlaylistId("https://open.spotify.com/playlist/abc"))
        assertNull(AppleMusicService.extractPlaylistId("not a link"))
        assertNull(AppleMusicService.extractPlaylistId(""))
    }

    private companion object {
        const val SEARCH_JSON = """{"results":{"songs":{"data":[
          {"id":"1440833847","attributes":{"name":"Blinding Lights","artistName":"The Weeknd","albumName":"After Hours","durationInMillis":200040,"contentRating":"explicit","artwork":{"url":"https://example.com/{w}x{h}bb.jpg"},"previews":[{"url":"https://example.com/preview.m4a"}],"url":"https://music.apple.com/us/song/1"}},
          {"id":"1440833848","attributes":{"name":" ","artistName":"Nobody","url":"https://music.apple.com/us/song/2"}}
        ]}}}"""

        const val LOOKUP_JSON = """{"data":[
          {"id":"1559523357","attributes":{"name":"Levitating","artistName":"Dua Lipa","albumName":"Future Nostalgia","durationInMillis":203000,"contentRating":"clean","previews":[{"url":"https://example.com/lev.m4a"}]}}
        ]}"""

        const val PLAYLIST_JSON = """{"data":[{
          "id":"pl.f4d106fed2bd41149",
          "attributes":{"name":"Today's Hits"},
          "relationships":{"tracks":{
            "data":[
              {"id":"1","attributes":{"name":"Espresso","artistName":"Sabrina Carpenter"}},
              {"id":"2","attributes":{"name":"Birds of a Feather","artistName":"Billie Eilish"}}
            ],
            "next":"/v1/catalog/us/playlists/pl.f4d106fed2bd41149/relationships/tracks?offset=2&limit=2"
          }}
        }]}"""
    }
}
