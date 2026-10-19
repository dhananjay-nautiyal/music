package com.music.music

import com.music.music.data.spotify.LocalPlaylistStore
import com.music.music.data.spotify.PlaylistFile
import com.music.music.data.spotify.StoredPlaylist
import com.music.music.data.spotify.StoredSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playlist store's two shapes.
 *
 * Both matter and for different reasons. The current one has to round-trip a
 * local track — losing `localUri` would bring back a playlist of files it can no
 * longer play, which is what the old format did. The legacy one has to keep
 * reading, because the only copy of somebody's playlist is in that format until
 * their next save upgrades it.
 */
class LocalPlaylistStoreTest {

    @Test
    fun `a playlist with local tracks round-trips`() {
        val file = PlaylistFile(
            playlists = listOf(
                StoredPlaylist(
                    id = "sp_local_1",
                    title = "Device mix",
                    songs = listOf(
                        StoredSong(
                            videoId = "content://media/external/audio/media/42",
                            title = "Local Song",
                            artist = "Someone",
                            localUri = "content://media/external/audio/media/42",
                            localPath = "/storage/emulated/0/Music/Local Song.flac",
                        ),
                    ),
                ),
            ),
        )

        val json = kotlinx.serialization.json.Json {
            encodeDefaults = true
            explicitNulls = false
        }
        val text = json.encodeToString(PlaylistFile.serializer(), file)
        val decoded = LocalPlaylistStore.decode(text)

        assertEquals(1, decoded.size)
        assertEquals("Device mix", decoded[0].title)
        assertEquals(1, decoded[0].songs.size)
        // The point of the new format: these survive, where the old one had no
        // field to put them in.
        assertEquals("content://media/external/audio/media/42", decoded[0].songs[0].localUri)
        assertEquals("/storage/emulated/0/Music/Local Song.flac", decoded[0].songs[0].localPath)
    }

    @Test
    fun `the legacy array format still reads`() {
        val legacy = """
            [
              {
                "id": "sp_local_99",
                "title": "Old playlist",
                "songs": [
                  {
                    "videoId": "dQw4w9WgXcQ",
                    "title": "Old Song",
                    "artist": "Old Artist",
                    "thumbnailUrl": null,
                    "durationText": "3:32",
                    "artistId": null,
                    "albumId": null,
                    "albumName": "Old Album",
                    "isVideo": false
                  }
                ]
              }
            ]
        """.trimIndent()

        val decoded = LocalPlaylistStore.decode(legacy)

        assertEquals(1, decoded.size)
        assertEquals("Old playlist", decoded[0].title)
        assertEquals("Old Song", decoded[0].songs[0].title)
        assertEquals("Old Album", decoded[0].songs[0].albumName)
        // The legacy writer emitted JSON null for absent fields; a key holding
        // null is not a title anyone typed.
        assertNull(decoded[0].songs[0].thumbnailUrl)
        assertNull(decoded[0].songs[0].artistId)
    }

    @Test
    fun `unreadable text reads as no playlists rather than throwing`() {
        // A truncated write, a file from something else entirely. Losing the
        // playlists is bad; crashing on every launch because of it is worse.
        assertTrue(LocalPlaylistStore.decode("not json at all").isEmpty())
        assertTrue(LocalPlaylistStore.decode("").isEmpty())
    }

    @Test
    fun `a stored song keeps the fields a playlist row needs`() {
        val song = com.music.music.data.model.Song(
            videoId = "abc",
            title = "T",
            artist = "A",
            thumbnailUrl = "https://example.test/art.jpg",
            durationText = "4:01",
            albumName = "Album",
            sourceQuality = "LOSSLESS",
        )
        val restored = StoredSong.of(song).toSong()

        assertEquals(song.videoId, restored.videoId)
        assertEquals(song.title, restored.title)
        assertEquals(song.artist, restored.artist)
        assertEquals(song.thumbnailUrl, restored.thumbnailUrl)
        assertEquals(song.durationText, restored.durationText)
        assertEquals(song.albumName, restored.albumName)
        // Which catalogue a row came from is what lets it resolve to a stream
        // again; dropping it turns a lossless row into a YouTube lookup.
        assertEquals(song.sourceQuality, restored.sourceQuality)
    }
}
