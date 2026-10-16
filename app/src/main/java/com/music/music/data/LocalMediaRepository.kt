package com.music.music.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.music.music.data.DebugLog as Log
import androidx.core.content.ContextCompat
import com.music.music.data.model.Song
import com.music.music.data.settings.AppSettings
import com.music.music.download.DownloadStore
import com.music.music.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

object LocalMediaRepository {

    private const val TAG = "Music"
    private const val MIN_LOCAL_MUSIC_DURATION_MS = 30_000L

    /** Thresholds offered for the "hide short clips" option (seconds). */
    val SHORT_TRACK_OPTIONS_SECS = listOf(15, 30, 60)

    // `mp4` only ever reaches this list as an audio row — MediaStore files a
    // video .mp4 under Video, not Audio — so it is the audio-only MP4s Dolby
    // and Apple music downloads come as (AC-4, E-AC-3, ALAC). `dsf`/`dff` are
    // DSD, which DsdExtractor plays.
    private val localMusicExtensions = setOf(
        "mp3", "m4a", "mp4", "flac", "ogg", "opus", "aac", "webm", "dsf", "dff", "wav",
    )

    private val nonMusicPathSegments = listOf(
        "/alarms/",
        "/notifications/",
        "/ringtones/",
        "/podcasts/",
        "/audiobooks/",
        "/recordings/",
        "/voice recorder/",
        "/sound_recorder/",
        "/call_rec/",
        "/whatsapp voice notes/",
    )

    /** Check if storage/audio permission is granted to query device local music. */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Retrieves all songs in the `Music/Music` directory, combining app downloads
     * with any local audio files present in that folder.
     *
     * The download record is the better source for a title and a credit — it
     * holds what the catalogue row said, not what a scanner guessed off a
     * filename — but it only started carrying the album at all recently, and
     * an album page's rows never name their own release. So whatever the media
     * scanner read off each file is collected alongside and used to fill the
     * gaps, which is what keeps the Albums tab from being empty for everything
     * downloaded before that field existed.
     */
    suspend fun getDownloadedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val appDownloads = Downloads.getDownloadedSongs(context)
        val knownUris = appDownloads.mapNotNull { it.localUri }.toSet()
        val extraSongs = mutableListOf<Song>()

        /** uri to what the media scanner read off that file. */
        val scanned = mutableMapOf<String, ScannedTags>()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                )
                val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("%${DownloadStore.FOLDER}%")

                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                    val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        val albumId = cursor.getLong(albumIdCol)
                        val tags = ScannedTags(
                            albumName = cursor.getString(albumCol).cleanTag(),
                            artworkUrl = if (albumId > 0) {
                                ContentUris.withAppendedId(albumArtBaseUri, albumId).toString()
                            } else {
                                null
                            },
                            dateAddedSeconds = cursor.getLong(dateAddedCol),
                            dateModifiedSeconds = cursor.getLong(dateModifiedCol),
                        )
                        scanned[contentUri] = tags
                        if (contentUri !in knownUris && isAudioFileName(name)) {
                            extraSongs.add(buildSongFromUri(context, contentUri, name, tags))
                        }
                    }
                }
            } else {
                val folder = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MUSIC,
                    ),
                    DownloadStore.FOLDER,
                )
                if (folder.exists() && folder.isDirectory) {
                    folder.listFiles()?.forEach { file ->
                        if (file.isFile && isAudioFileName(file.name)) {
                            val uriStr = Uri.fromFile(file).toString()
                            if (uriStr !in knownUris) {
                                val modified = file.lastModified().takeIf { it > 0 }?.div(1_000)
                                extraSongs.add(
                                    buildSongFromUri(
                                        context,
                                        uriStr,
                                        file.name,
                                        ScannedTags(null, null, modified, modified),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Failed scanning Music/Music directory: ${it.message}") }

        val filled = appDownloads.map { song ->
            val uri = song.localUri ?: return@map song
            val tags = scanned[uri] ?: return@map song
            song.copy(
                albumName = song.albumName ?: tags.albumName,
                localDateAddedSeconds = tags.dateAddedSeconds,
                localDateModifiedSeconds = tags.dateModifiedSeconds,
            )
        }

        (filled + extraSongs).distinctBy { it.localUri ?: it.videoId }
    }

    /**
     * The parts of a scanner row worth reading back — everything else about a
     * download is better known from the record that made it.
     */
    private class ScannedTags(
        val albumName: String?,
        val artworkUrl: String?,
        val dateAddedSeconds: Long?,
        val dateModifiedSeconds: Long?,
    )

    /** What MediaStore writes into a column it has nothing for. */
    private fun String?.cleanTag(): String? =
        takeUnless { it.isNullOrBlank() || it == "<unknown>" }

    /**
     * Queries MediaStore for all audio files available on the device.
     */
    suspend fun getLocalMusic(context: Context): List<Song> = withContext(Dispatchers.IO) {
        if (!hasStoragePermission(context)) return@withContext emptyList()

        val songs = mutableListOf<Song>()
        // This scan runs over every audio file on the device, which includes
        // whatever this app has downloaded into Music/Music alongside
        // everything else — but by content URI, the only thing MediaStore
        // offers here, that download is indistinguishable from a file the
        // user copied on by hand. Reversing [Downloads.saved] hands a
        // downloaded track its real YouTube id back, which is what lets
        // PlaybackTracker recognise it as a video worth registering a play
        // for — a content URI fails its id check on purpose, since most rows
        // here really are just local files with nothing to sync.
        val videoIdByUri = Downloads.saved.value.entries.associate { (id, uri) -> uri to id }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )

        val filterNonMusic = AppSettings.filterNonMusicAudio.value
        val filterShort = AppSettings.filterShortTracks.value
        val shortMinSecs = AppSettings.shortTrackMinSecs.value.coerceIn(5, 120)
        val shortThresholdMs = shortMinSecs * 1_000L
        // Combined folder set: new multi-pick + legacy single value.
        val folderUris: Set<String> = buildSet {
            addAll(AppSettings.localMusicFolderUris.value)
            val legacy = AppSettings.localMusicFolderUri.value
            if (legacy.isNotBlank()) add(legacy)
        }
        val excludedPaths: Set<String> = AppSettings.localMusicExcludedPaths.value
            .map { it.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }.toSet()
        val selection = if (filterNonMusic || filterShort) {
            buildString {
                append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
                // A duration of 0 or NULL is the scanner failing to read one,
                // not a short clip — fragmented MP4 (how ALAC downloads often
                // come) reads as 0, high-rate DSD as NULL — so it is let
                // through to the extension and path checks rather than
                // dropped as too short.
                if (filterShort) {
                    append(
                        " AND (${MediaStore.Audio.Media.DURATION} >= ?" +
                            " OR ${MediaStore.Audio.Media.DURATION} <= 0" +
                            " OR ${MediaStore.Audio.Media.DURATION} IS NULL)",
                    )
                }
                if (filterNonMusic) {
                    append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
                    append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
                    append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        append(" AND ${MediaStore.Audio.Media.IS_PODCAST} = 0")
                    }
                }
            }
        } else {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        }
        val selectionArgs = if (filterShort) {
            arrayOf(shortThresholdMs.toString())
        } else {
            null
        }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val displayNameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(displayNameCol).orEmpty()
                    val rawTitle = cursor.getString(titleCol)
                    val rawArtist = cursor.getString(artistCol)
                    val rawAlbum = cursor.getString(albumCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val durationMs = cursor.getLong(durationCol)
                    val path = cursor.getString(dataCol)

                    if (!isEligibleLocalMusic(
                            durationMs, displayName, path,
                            filterShort = filterShort, shortMinSecs = shortMinSecs,
                            filterNonMusic = filterNonMusic,
                        )
                    ) continue
                    if (!isInSelectedFolders(path, folderUris)) continue
                    if (isExcludedPath(path, excludedPaths)) continue

                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                    val title = rawTitle.cleanTag()?.removeAudioExtension()
                        ?: displayName.substringBeforeLast('.').takeIf { it.isNotBlank() }
                        ?: "Track $id"
                    val artist = rawArtist.takeUnless { it.isNullOrBlank() || it == "<unknown>" } ?: "Unknown Artist"
                    val albumName = rawAlbum.takeUnless { it.isNullOrBlank() || it == "<unknown>" }
                    val artworkUrl = if (albumId > 0) ContentUris.withAppendedId(albumArtBaseUri, albumId).toString() else null
                    val durationText = durationMs.takeIf { it > 0 }?.let(::formatDuration)

                    songs.add(
                        Song(
                            videoId = videoIdByUri[contentUri] ?: contentUri,
                            title = title,
                            artist = artist,
                            thumbnailUrl = artworkUrl,
                            durationText = durationText,
                            albumName = albumName,
                            localUri = contentUri,
                            localPath = path,
                            localDateAddedSeconds = cursor.getLong(dateAddedCol),
                            localDateModifiedSeconds = cursor.getLong(dateModifiedCol),
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Failed scanning device local music: ${it.message}") }

        runCatching {
            AppSettings.setLocalMusicLastScanMs(System.currentTimeMillis())
        }

        songs
    }

    /** Human-readable path for the folder setting without exposing provider internals. */
    fun selectedFolderLabel(treeUri: String): String? = selectedFolder(treeUri)?.label

    /** Labels for every picked folder, in no particular order. Blank URIs skipped. */
    fun selectedFolderLabels(treeUris: Set<String>): List<String> =
        treeUris.filter { it.isNotBlank() }.mapNotNull { selectedFolderLabel(it) }

    internal fun isInSelectedFolder(path: String?, treeUri: String): Boolean {
        if (treeUri.isBlank()) return true
        val folder = selectedFolder(treeUri) ?: return false
        val candidate = path?.normalizedPath() ?: return false
        return candidate == folder.absolutePath || candidate.startsWith("${folder.absolutePath}/")
    }

    /**
     * Multi-folder version: empty set means scan all folders where music
     * exists. Otherwise the path must sit under at least one picked folder.
     */
    internal fun isInSelectedFolders(path: String?, treeUris: Set<String>): Boolean {
        val cleaned = treeUris.filter { it.isNotBlank() }
        if (cleaned.isEmpty()) return true
        return cleaned.any { isInSelectedFolder(path, it) }
    }

    internal fun isExcludedPath(path: String?, excludedNormalized: Set<String>): Boolean {
        if (excludedNormalized.isEmpty()) return false
        val candidate = path?.normalizedPath() ?: return false
        return excludedNormalized.any { ex ->
            val e = ex.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)
            e.isNotBlank() && (candidate == e || candidate.startsWith("$e/"))
        }
    }

    /** Parent folder display name for grouping, e.g. "Music" or "Download". */
    fun folderNameOf(path: String?): String {
        val clean = path?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: return "Unknown folder"
        val parent = clean.substringBeforeLast('/', "")
        return parent.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "Root"
    }

    /** Full parent folder path for exclusion toggles. */
    fun folderPathOf(path: String?): String? {
        val clean = path?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        return clean.substringBeforeLast('/', "").takeIf { it.isNotBlank() }
    }

    /** Group songs by parent folder with counts, sorted by folder name. */
    fun musicFoldersOf(songs: List<Song>): List<MusicFolder> =
        songs.groupBy { folderNameOf(it.localPath) }
            .map { (name, rows) ->
                MusicFolder(
                    name = name,
                    path = folderPathOf(rows.firstOrNull()?.localPath),
                    songCount = rows.size,
                )
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    data class MusicFolder(
        val name: String,
        val path: String?,
        val songCount: Int,
    )

    /**
     * Folders that look like they hold only short/system clips: every sampled
     * row is short or sits under a known non-music segment. Used to suggest
     * exclusions, never auto-applied.
     */
    fun suggestExcludedFolders(
        rows: List<Triple<Long, String, String?>>,
        shortMinSecs: Int = 30,
    ): List<String> {
        val thresholdMs = shortMinSecs * 1_000L
        return rows.groupBy { folderPathOf(it.third) ?: folderNameOf(it.third) }
            .filterValues { group ->
                group.isNotEmpty() && group.all { (dur, name, path) ->
                    (dur in 1 until thresholdMs) || !isMusicPath(path, name)
                }
            }
            .keys.filterIsInstance<String>()
            .sorted()
    }

    private fun isMusicPath(path: String?, displayName: String): Boolean {
        val fileName = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: displayName
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in localMusicExtensions) return false
        val normalizedPath = path?.replace('\\', '/')?.lowercase(Locale.ROOT) ?: return true
        return nonMusicPathSegments.none(normalizedPath::contains)
    }

    private data class SelectedFolder(val absolutePath: String, val label: String)

    private fun selectedFolder(treeUri: String): SelectedFolder? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        if (documentId.startsWith("raw:")) {
            val path = documentId.removePrefix("raw:").normalizedPath()
            return@runCatching SelectedFolder(path, path.substringAfterLast('/'))
        }
        val volume = documentId.substringBefore(':')
        val relative = documentId.substringAfter(':', "").trim('/')
        val volumeRoot = if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
        SelectedFolder(
            absolutePath = listOf(volumeRoot, relative).filter { it.isNotBlank() }.joinToString("/").normalizedPath(),
            label = relative.ifBlank { volume },
        )
    }.getOrNull()

    private fun String.normalizedPath(): String =
        replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

    /**
     * MediaStore's `IS_MUSIC` flag is advisory and often includes notification
     * sounds, voice notes and recorder output. Keep this second gate independent
     * of scanner metadata so the same bad rows stay out across Android vendors.
     *
     * @param filterShort when true, clips shorter than [shortMinSecs] are
     * rejected (duration 0/NULL still kept — the scanner failing, not a clip).
     * @param filterNonMusic when true, extension + system-path checks apply.
     */
    internal fun isEligibleLocalMusic(
        durationMs: Long,
        displayName: String,
        path: String?,
        filterShort: Boolean,
        shortMinSecs: Int,
        filterNonMusic: Boolean,
    ): Boolean {
        val thresholdMs = shortMinSecs.coerceIn(5, 120) * 1_000L
        if (filterShort && durationMs in 1 until thresholdMs) return false
        if (!filterNonMusic) {
            // Short-clip gate only; extension/path checks skipped.
            return true
        }

        val fileName = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: displayName
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in localMusicExtensions) return false

        val normalizedPath = path
            ?.replace('\\', '/')
            ?.lowercase(Locale.ROOT)
            ?: return true
        return nonMusicPathSegments.none(normalizedPath::contains)
    }

    /** Legacy 3-arg check: 30s threshold + non-music filtering on. */
    internal fun isEligibleLocalMusic(durationMs: Long, displayName: String, path: String?): Boolean =
        isEligibleLocalMusic(durationMs, displayName, path, filterShort = true, shortMinSecs = 30, filterNonMusic = true)

    private fun String.removeAudioExtension(): String {
        val extension = substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in localMusicExtensions || extension == "wav") {
            substringBeforeLast('.').ifBlank { this }
        } else {
            this
        }
    }

    private fun isAudioFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") ||
            lower.endsWith(".flac") || lower.endsWith(".wav") ||
            lower.endsWith(".ogg") || lower.endsWith(".opus") ||
            lower.endsWith(".aac") || lower.endsWith(".webm") ||
            lower.endsWith(".3gp")
    }

    /**
     * A song built from a file in the downloads folder the app has no record of
     * — one copied in by hand, or left behind by an install whose record is
     * gone. The file's own tags are the only thing there is to go on; [scanned]
     * fills in what the retriever couldn't read, since the media scanner and
     * `MediaMetadataRetriever` do not agree on every container.
     */
    private fun buildSongFromUri(
        context: Context,
        uriStr: String,
        fileName: String,
        scanned: ScannedTags? = null,
    ): Song {
        var title = fileName.substringBeforeLast(".")
        var artist = "Unknown Artist"
        var albumName: String? = null
        var durationText: String? = null

        runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, Uri.parse(uriStr))
            val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val metaDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

            if (!metaTitle.isNullOrBlank()) title = metaTitle
            if (!metaArtist.isNullOrBlank()) artist = metaArtist
            albumName = metaAlbum.cleanTag()
            if (metaDur != null && metaDur > 0) durationText = formatDuration(metaDur)
            retriever.release()
        }

        return Song(
            videoId = uriStr,
            title = title,
            artist = artist,
            thumbnailUrl = scanned?.artworkUrl,
            durationText = durationText,
            albumName = albumName ?: scanned?.albumName,
            localUri = uriStr,
            localDateAddedSeconds = scanned?.dateAddedSeconds,
            localDateModifiedSeconds = scanned?.dateModifiedSeconds,
        )
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }
}
