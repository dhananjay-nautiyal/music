package com.music.music.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
) {
    val browseId: String get() = "local:playlist:$id"
}

/**
 * A playlist in the app's own store, in the shape it is written down.
 *
 * Separate from [LocalPlaylist] because a [Song] carries a lot the store has no
 * business writing: a queue entry id, the radio station it came from, the page
 * it was opened on. Those describe one session, not a playlist, and writing
 * them would pin a playlist to the screen it was last played from.
 *
 * [StoredSong.localUri] and [localPath] are the exception that matters: a local
 * file has no video id to resolve, so without them a playlist of device tracks
 * came back after a restart pointing at nothing. The legacy format did not
 * carry them — see [LocalPlaylistStore.load].
 */
@Serializable
data class StoredPlaylist(
    val id: String,
    val title: String,
    val songs: List<StoredSong> = emptyList(),
)

@Serializable
data class StoredSong(
    val videoId: String = "",
    val title: String = "",
    val artist: String = "",
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val isVideo: Boolean = false,
    val setVideoId: String? = null,
    val localUri: String? = null,
    val localPath: String? = null,
    val sourceQuality: String? = null,
) {
    fun toSong(): Song = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        isVideo = isVideo,
        setVideoId = setVideoId,
        localUri = localUri,
        localPath = localPath,
        sourceQuality = sourceQuality,
    )

    companion object {
        fun of(song: Song): StoredSong = StoredSong(
            videoId = song.videoId,
            title = song.title,
            artist = song.artist,
            thumbnailUrl = song.thumbnailUrl,
            durationText = song.durationText,
            artistId = song.artistId,
            albumId = song.albumId,
            albumName = song.albumName,
            isVideo = song.isVideo,
            setVideoId = song.setVideoId,
            localUri = song.localUri,
            localPath = song.localPath,
            sourceQuality = song.sourceQuality,
        )
    }
}

/** The whole store, as one document. Versioned so a newer file can be refused whole. */
@Serializable
data class PlaylistFile(
    val version: Int = LocalPlaylistStore.SCHEMA_VERSION,
    val playlists: List<StoredPlaylist> = emptyList(),
)

object LocalPlaylistStore {
    private const val PREF_NAME = "music_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    /** Bump when the shape below stops being readable by an older build. */
    const val SCHEMA_VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // Nulls are what most of these fields are; writing them out would triple
        // the size of a backup to say nothing.
        explicitNulls = false
    }

    private lateinit var prefs: SharedPreferences

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        _playlists.value = decode(raw)
    }

    /**
     * Reads either shape the store has ever been written in.
     *
     * The current one is a [PlaylistFile]. The original was a bare JSON array
     * written with `org.json`, which carried no local URI and no set-video id —
     * a playlist read back from it is as complete as it was when it was
     * written, and the next [persist] upgrades it.
     */
    internal fun decode(raw: String): List<LocalPlaylist> =
        runCatching { json.decodeFromString(PlaylistFile.serializer(), raw) }
            .map { file -> file.playlists.map(::toPlaylist) }
            .getOrElse { decodeLegacy(raw) }

    private fun decodeLegacy(raw: String): List<LocalPlaylist> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val songsArray = obj.getJSONArray("songs")
                val songs = buildList {
                    for (j in 0 until songsArray.length()) {
                        val s = songsArray.getJSONObject(j)
                        add(
                            Song(
                                videoId = s.optString("videoId", ""),
                                title = s.optString("title", ""),
                                artist = s.optString("artist", ""),
                                thumbnailUrl = s.optString("thumbnailUrl", null).cleanJsonNull(),
                                durationText = s.optString("durationText", null).cleanJsonNull(),
                                artistId = s.optString("artistId", null).cleanJsonNull(),
                                albumId = s.optString("albumId", null).cleanJsonNull(),
                                albumName = s.optString("albumName", null).cleanJsonNull(),
                                isVideo = s.optBoolean("isVideo", false),
                            ),
                        )
                    }
                }
                add(LocalPlaylist(obj.getString("id"), obj.getString("title"), songs))
            }
        }
    }.getOrElse { emptyList() }

    /**
     * `optString` answers the four-character string "null" for a key holding a
     * JSON null, which is not a title anyone typed.
     */
    private fun String?.cleanJsonNull(): String? =
        takeIf { !it.isNullOrBlank() && it != "null" }

    private fun toPlaylist(stored: StoredPlaylist) = LocalPlaylist(
        id = stored.id,
        title = stored.title,
        songs = stored.songs.map { it.toSong() },
    )

    /** The whole store as the document [importAll] and [Backup] both speak. */
    fun exportFile(): PlaylistFile = PlaylistFile(
        playlists = _playlists.value.map { playlist ->
            StoredPlaylist(
                id = playlist.id,
                title = playlist.title,
                songs = playlist.songs.map(StoredSong::of),
            )
        },
    )

    /**
     * Replaces the whole store with [file]'s contents.
     *
     * A replace, not a merge: an import is a move from one device to another,
     * and merging would leave the two libraries interleaved with no way to tell
     * which playlist came from where.
     */
    fun importAll(file: PlaylistFile) {
        val incoming = file.playlists.map(::toPlaylist)
        _playlists.value = incoming
        persist(incoming)
    }

    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val id = "sp_local_" + System.currentTimeMillis()
        val playlist = LocalPlaylist(id = id, title = title, songs = songs)
        val updated = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        _playlists.value = updated
        persist(updated)
        return playlist
    }

    fun deletePlaylist(id: String) {
        val updated = _playlists.value.filterNot { it.id == id || it.browseId == id || it.id == id.removePrefix("local:playlist:") }
        _playlists.value = updated
        persist(updated)
    }

    fun renamePlaylist(id: String, newTitle: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val updated = _playlists.value.map {
            if (it.id == cleanId || it.id == id || it.browseId == id) {
                it.copy(title = newTitle)
            } else it
        }
        _playlists.value = updated
        persist(updated)
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    private fun persist(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching {
            val file = PlaylistFile(
                playlists = list.map { playlist ->
                    StoredPlaylist(
                        id = playlist.id,
                        title = playlist.title,
                        songs = playlist.songs.map(StoredSong::of),
                    )
                },
            )
            prefs.edit().putString(KEY_PLAYLISTS, json.encodeToString(PlaylistFile.serializer(), file)).apply()
        }
    }
}
