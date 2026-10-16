package com.music.music

import com.music.music.data.LocalMediaRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaFilterTest {

    @Test
    fun rejectsShortAudio() {
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 29_999,
                displayName = "effect.mp3",
                path = "/storage/emulated/0/Music/effect.mp3",
            ),
        )
    }

    @Test
    fun rejectsWaveAndRecorderFiles() {
        // WAV in a non-music folder is still rejected via path segment.
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 180_000,
                displayName = "recording.wav",
                path = "/storage/emulated/0/Recordings/recording.wav",
            ),
        )
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 180_000,
                displayName = "voice-note.mp3",
                path = "/storage/emulated/0/Voice Recorder/voice-note.mp3",
            ),
        )
    }

    @Test
    fun keepsWavInMusicFolder() {
        // S4: WAV is a supported music extension when under a music folder.
        assertTrue(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 180_000,
                displayName = "Song.wav",
                path = "/storage/emulated/0/Music/Song.wav",
            ),
        )
    }

    @Test
    fun shortTrackThresholdIsConfigurable() {
        // 20s clip: hidden at 30s, shown at 15s, shown when filter off.
        assertFalse(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 20_000,
                displayName = "intro.mp3",
                path = "/storage/emulated/0/Music/intro.mp3",
                filterShort = true,
                shortMinSecs = 30,
                filterNonMusic = true,
            ),
        )
        assertTrue(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 20_000,
                displayName = "intro.mp3",
                path = "/storage/emulated/0/Music/intro.mp3",
                filterShort = true,
                shortMinSecs = 15,
                filterNonMusic = true,
            ),
        )
        assertTrue(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 20_000,
                displayName = "intro.mp3",
                path = "/storage/emulated/0/Music/intro.mp3",
                filterShort = false,
                shortMinSecs = 30,
                filterNonMusic = true,
            ),
        )
    }

    @Test
    fun keepsSupportedMusicFiles() {
        assertTrue(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 50_000,
                displayName = "Song.flac",
                path = "/storage/emulated/0/Music/Artist/Song.flac",
            ),
        )
    }

    @Test
    fun keepsAudioOnlyMp4AndDsd() {
        for (name in listOf("Song.mp4", "Song.dsf", "Song.dff")) {
            assertTrue(
                name,
                LocalMediaRepository.isEligibleLocalMusic(
                    durationMs = 180_000,
                    displayName = name,
                    path = "/storage/emulated/0/Music/$name",
                ),
            )
        }
    }

    @Test
    fun keepsTracksTheScannerCouldNotTime() {
        // Fragmented-MP4 ALAC scans with duration 0, high-rate DSD with NULL
        // (read back as 0): an unknown length, not a short clip.
        assertTrue(
            LocalMediaRepository.isEligibleLocalMusic(
                durationMs = 0,
                displayName = "Starboy.m4a",
                path = "/storage/emulated/0/Music/Starboy.m4a",
            ),
        )
    }

    @Test
    fun multiFolderEmptyMeansAll() {
        assertTrue(
            LocalMediaRepository.isInSelectedFolders(
                "/storage/emulated/0/Music/song.mp3",
                emptySet(),
            ),
        )
        assertTrue(
            LocalMediaRepository.isInSelectedFolders(
                "/storage/emulated/0/Download/song.mp3",
                emptySet(),
            ),
        )
    }

    @Test
    fun excludedPathsWin() {
        val excluded = setOf("/storage/emulated/0/Download".lowercase())
        assertTrue(
            LocalMediaRepository.isExcludedPath(
                "/storage/emulated/0/Download/song.mp3",
                excluded,
            ),
        )
        assertFalse(
            LocalMediaRepository.isExcludedPath(
                "/storage/emulated/0/Music/song.mp3",
                excluded,
            ),
        )
    }

    @Test
    fun folderGroupingCountsSongs() {
        val songs = listOf(
            com.music.music.data.model.Song(
                videoId = "1",
                title = "A",
                artist = "X",
                thumbnailUrl = null,
                localPath = "/storage/emulated/0/Music/A.mp3",
            ),
            com.music.music.data.model.Song(
                videoId = "2",
                title = "B",
                artist = "X",
                thumbnailUrl = null,
                localPath = "/storage/emulated/0/Download/B.mp3",
            ),
            com.music.music.data.model.Song(
                videoId = "3",
                title = "C",
                artist = "X",
                thumbnailUrl = null,
                localPath = "/storage/emulated/0/Music/sub/C.mp3",
            ),
        )
        val folders = LocalMediaRepository.musicFoldersOf(songs)
        // Music, Download, sub = 3 groups; Music has 1 here (sub is separate).
        assertTrue(folders.size == 3)
        assertTrue(folders.firstOrNull { it.name == "Music" }?.songCount == 1)
    }
}
