package com.music.music

import com.music.music.data.stats.Backup
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The backup file's compatibility contract.
 *
 * Two things have to stay true across releases, and both are easy to break
 * silently: a file written by an older build must still import, and the field
 * that an older build did not write must be distinguishable from a field it
 * wrote as empty. The second is what stops "restore an old backup" from
 * deleting every playlist on the device.
 */
class BackupSchemaTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Test
    fun `a version 1 file still reads`() {
        // Exactly what v1.0.9 wrote: no playlists field at all.
        val v1 = """
            {
              "app": "music",
              "version": 1,
              "versionName": "1.0.9",
              "exportedAt": "2026-10-16T18:00:00Z",
              "settings": {},
              "listening": []
            }
        """.trimIndent()

        val file = json.decodeFromString(Backup.BackupFile.serializer(), v1)

        assertEquals("music", file.app)
        assertEquals(1, file.version)
        // The distinction the import path depends on.
        assertNull(file.playlists)
    }

    @Test
    fun `a version 2 file carries its playlists`() {
        val v2 = """
            {
              "app": "music",
              "version": 2,
              "versionName": "1.1.0",
              "exportedAt": "2026-10-19T09:00:00Z",
              "settings": {},
              "listening": [],
              "playlists": {
                "version": 2,
                "playlists": [
                  {
                    "id": "sp_local_1",
                    "title": "Mix",
                    "songs": [
                      {
                        "videoId": "content://media/external/audio/media/7",
                        "title": "Local",
                        "artist": "Someone",
                        "localUri": "content://media/external/audio/media/7"
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val file = json.decodeFromString(Backup.BackupFile.serializer(), v2)

        assertNotNull(file.playlists)
        assertEquals(1, file.playlists!!.playlists.size)
        assertEquals("Mix", file.playlists!!.playlists[0].title)
        assertEquals(
            "content://media/external/audio/media/7",
            file.playlists!!.playlists[0].songs[0].localUri,
        )
    }

    @Test
    fun `an empty playlist list is not the same as no playlist field`() {
        // A backup written by a build that has the feature and simply had no
        // playlists: importing it legitimately clears the store.
        val empty = """
            {
              "app": "music",
              "version": 2,
              "playlists": { "version": 2, "playlists": [] }
            }
        """.trimIndent()

        val file = json.decodeFromString(Backup.BackupFile.serializer(), empty)

        assertNotNull(file.playlists)
        assertEquals(0, file.playlists!!.playlists.size)
    }

    @Test
    fun `a newer schema is recognisable before anything is written`() {
        val future = """
            { "app": "music", "version": 99 }
        """.trimIndent()

        val file = json.decodeFromString(Backup.BackupFile.serializer(), future)

        // importFrom refuses on this comparison; the test pins the field it
        // reads so a refactor cannot quietly drop the guard.
        assertEquals(99, file.version)
    }
}
