package com.music.music.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.music.music.data.DebugLog as Log
import java.io.File
import java.security.MessageDigest

/**
 * Cover art for tracks MediaStore has none for.
 *
 * MediaStore's `albumart` provider answers only when the scanner managed to
 * group a file into an album with a cover it recognises, which leaves two
 * ordinary libraries blank: files whose cover is embedded in the audio but never
 * extracted into the album-art table, and folders that carry a `folder.jpg` /
 * `cover.jpg` beside the music and no tag at all.
 *
 * ## Why this writes files
 *
 * Coil, which draws every cover in the app, loads a `content://` or `file://`
 * URI — not a byte array held in memory, and not a `MediaMetadataRetriever`.
 * Extracting on each row would also mean decoding the same album's cover once
 * per track. So the bytes are pulled once per album, written to the app's cache
 * and handed back as a URI; every row of that album then shares one file and one
 * decode.
 *
 * ## What it cannot do
 *
 * `folder.jpg` needs a real path, and scoped storage does not hand one out for
 * arbitrary folders — [Song.localPath] is populated from `MediaStore.DATA`,
 * which is often readable and sometimes not. Every step here is best-effort for
 * that reason: a miss falls through to no artwork, which is exactly what the app
 * showed before.
 */
object LocalArtwork {

    private const val TAG = "LocalArtwork"
    private const val DIRECTORY = "local_art"

    /** Names a folder-level cover is conventionally given, best first. */
    private val FOLDER_COVER_NAMES = listOf(
        "cover.jpg", "cover.jpeg", "cover.png", "cover.webp",
        "folder.jpg", "folder.jpeg", "folder.png", "folder.webp",
        "album.jpg", "albumart.jpg", "front.jpg",
    )

    /** How many extracted covers to keep before trimming the least recent. */
    private const val MAX_FILES = 400

    /** Per-run memory of what was already answered, so a scan asks once per album. */
    private val resolved = HashMap<String, String?>()

    /**
     * Album ids MediaStore's own art provider turned out to have nothing for.
     *
     * An id is not a promise of art. The scanner assigns one whenever it groups
     * files into an album, whether or not it found a cover — so checking only
     * for `albumId > 0` would leave the exact library this exists for still
     * blank. Opening the descriptor once per album is cheap; remembering the
     * misses keeps it to once.
     */
    private val missingMediaStoreArt = HashSet<Long>()

    /** Albums already known to have art, so their descriptor is opened once. */
    private val presentMediaStoreArt = HashMap<Long, String>()

    private val albumArtBaseUri = Uri.parse("content://media/external/audio/albumart")

    /**
     * MediaStore's indexed cover for [albumId], or null when it has none.
     *
     * Null is a real answer here, not a failure: the caller falls through to the
     * file's own tags, which is where a cover that was never extracted into the
     * album-art table lives.
     */
    fun mediaStoreArtwork(context: Context, albumId: Long): String? {
        if (albumId <= 0) return null
        synchronized(missingMediaStoreArt) {
            if (albumId in missingMediaStoreArt) return null
            presentMediaStoreArt[albumId]?.let { return it }
        }
        val uri = ContentUris.withAppendedId(albumArtBaseUri, albumId).toString()
        val present = runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
        }.getOrDefault(false)
        synchronized(missingMediaStoreArt) {
            if (present) presentMediaStoreArt[albumId] = uri else missingMediaStoreArt.add(albumId)
        }
        return if (present) uri else null
    }

    /**
     * A usable artwork URI for the track at [path] in [albumKey]'s album, or
     * null when neither the file nor its folder carries one.
     *
     * [albumKey] groups tracks: two files in one album share a key, so the
     * cover is extracted from the first and reused for the rest. Pass anything
     * stable — the album name and artist, or the parent directory.
     *
     * Blocking on purpose: the local scan reads its cursor in one pass on a
     * worker thread and cannot suspend inside that loop.
     */
    fun artworkFor(context: Context, path: String?, albumKey: String): String? {
        if (path.isNullOrBlank()) return null
        val key = hash(albumKey)
        synchronized(resolved) { if (resolved.containsKey(key)) return resolved[key] }
        val answer = resolve(context, path, key)
        synchronized(resolved) { resolved[key] = answer }
        return answer
    }

    /** Forgets what was resolved, so a re-scan can pick up files added since. */
    fun invalidate() {
        synchronized(resolved) { resolved.clear() }
        synchronized(missingMediaStoreArt) {
            missingMediaStoreArt.clear()
            presentMediaStoreArt.clear()
        }
    }

    private fun resolve(context: Context, path: String, key: String): String? {
        val directory = File(context.cacheDir, DIRECTORY)
        val existing = directory.listFiles()?.firstOrNull { it.name.startsWith("$key.") }
        if (existing != null) {
            existing.setLastModified(System.currentTimeMillis())
            return existing.toURI().toString()
        }

        val bytes = embeddedPicture(path) ?: folderCover(path) ?: return null
        if (!directory.exists() && !directory.mkdirs()) return null
        val extension = extensionOf(bytes)
        val destination = File(directory, "$key.$extension")
        return runCatching {
            destination.writeBytes(bytes)
            trim(directory)
            destination.toURI().toString()
        }.getOrElse {
            Log.w(TAG, "Couldn't cache artwork for $path: ${it.message}")
            destination.delete()
            null
        }
    }

    /**
     * The picture in the file's own tags.
     *
     * `embeddedPicture` decodes the container and reads only the picture frame,
     * which is cheap enough to do once per album. A file it cannot open — a
     * format it does not parse, a path the process may not read — is a miss.
     */
    private fun embeddedPicture(path: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.embeddedPicture?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** A cover sitting beside the music, for a library that keeps them there. */
    private fun folderCover(path: String): ByteArray? {
        val folder = File(path).parentFile ?: return null
        // Case-insensitive because a folder made on a desktop may be either.
        val present = runCatching { folder.listFiles()?.filter { it.isFile } }.getOrNull() ?: return null
        for (name in FOLDER_COVER_NAMES) {
            val match = present.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: continue
            val bytes = runCatching { match.readBytes() }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    /**
     * The right extension for what was extracted.
     *
     * Coil sniffs content, but a wrong extension still reads wrong in a file
     * listing and some decoders trust it over the bytes. The three formats that
     * actually occur: JPEG in ID3/MP4, PNG in FLAC, WebP occasionally.
     */
    private fun extensionOf(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes.size >= 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "webp"
        else -> "img"
    }

    /** Keeps the cache from growing one cover per album forever. */
    private fun trim(directory: File) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { it.delete() }
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
}
