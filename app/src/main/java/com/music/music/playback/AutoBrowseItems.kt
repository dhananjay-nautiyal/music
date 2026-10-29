package com.music.music.playback

import android.os.Bundle
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.music.music.data.innertube.InnertubeParser
import com.music.music.data.model.BrowseItem
import com.music.music.data.model.NOTIFICATION_ART_PX
import com.music.music.data.model.ShelfItem
import com.music.music.data.model.Song
import com.music.music.data.model.artworkAt

/*
 * The items Android Auto is shown: folders, playlist cards and info rows.
 *
 * This came out of `PlaybackService` (#23). It was written there as private
 * members of the service, so reading one builder meant scrolling past the
 * player, the queue and the session that had nothing to do with it. None of it
 * reads service state: each function takes figures and returns a `MediaItem`,
 * which is what makes the paging and id rules below testable rather than
 * something to eyeball in a diff.
 *
 * The helpers that do touch state — the search caches, the song cache, the
 * network search and the login prompt, which needs a `Context` for its
 * strings — stayed behind.
 */

/** Largest page Auto may ask for, so a bogus `pageSize` can't ask for the whole library. */
internal const val MAX_AUTO_PAGE_SIZE = 50

// Android Auto Content Style Hints
internal const val EXTRA_CONTENT_STYLE_SUPPORTED = "android.media.browse.extra.CONTENT_STYLE_SUPPORTED"
internal const val EXTRA_CONTENT_STYLE_SUPPORTED_AX = "androidx.media.contentstyle.CONTENT_STYLE_SUPPORTED"
internal const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_BROWSABLE_HINT"
internal const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.extra.CONTENT_STYLE_PLAYABLE_HINT"
internal const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_BROWSABLE_HINT"
internal const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX = "androidx.media.contentstyle.CONTENT_STYLE_PLAYABLE_HINT"
internal const val EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_BROWSABLE_HINT"
internal const val EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY = "androidx.media.utils.CONTENT_STYLE_PLAYABLE_HINT"
internal const val CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1
internal const val CONTENT_STYLE_GRID_ITEM_HINT_VALUE = 4

/**
 * Content-style hints, written out for every key any Auto-era client reads.
 *
 * A client that finds none of these falls back to a list for everything, which
 * is why an album row arrives as a wall of text instead of covers. The legacy
 * and `androidx` keys are the same hint under the names different generations
 * of the platform asked for; there is no version check because the extras
 * bundle is ignored by whatever doesn't understand it.
 */
internal fun buildContentStyleBundle(browsableHint: Int, playableHint: Int): Bundle =
    Bundle().apply {
        putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT, browsableHint)
        putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT, playableHint)
        putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_AX, browsableHint)
        putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_AX, playableHint)
        putInt(EXTRA_CONTENT_STYLE_BROWSABLE_HINT_LEGACY, browsableHint)
        putInt(EXTRA_CONTENT_STYLE_PLAYABLE_HINT_LEGACY, playableHint)
        putInt("android.media.extras.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
        putInt("android.media.extras.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
        putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", browsableHint)
        putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", playableHint)
        putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
        putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED_AX, true)
    }

/**
 * One page of a folder's children, capped per request.
 *
 * The offsets are worked out in `Long` because a controller may ask for a page
 * far past the end of the list, and `page * pageSize` in `Int` wraps into a
 * negative index on the way there — which [List.subList] reports as an
 * argument error rather than as an empty page.
 */
internal fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0 || items.isEmpty()) return emptyList()
    val safePageSize = pageSize.coerceAtMost(MAX_AUTO_PAGE_SIZE)
    val fromLong = page.toLong() * safePageSize.toLong()
    if (fromLong >= items.size.toLong()) return emptyList()
    val from = fromLong.toInt()
    val to = (fromLong + safePageSize).coerceAtMost(items.size.toLong()).toInt()
    return items.subList(from, to)
}

/** A browsable card in a grid: an album, an artist, a playlist. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun createGridPlaylistItem(
    mediaId: String,
    title: String,
    subtitle: String? = null,
    artworkUrl: String? = null,
    folderType: Int = MediaMetadata.FOLDER_TYPE_PLAYLISTS,
): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUrl?.artworkAt(NOTIFICATION_ART_PX)?.let(Uri::parse))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setFolderType(folderType)
            .setExtras(buildContentStyleBundle(CONTENT_STYLE_GRID_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
            .build(),
    )
    .build()

/**
 * A home-feed card as a grid item.
 *
 * The media id is the promise the browser has to keep: `playlist:` is resolved
 * by [Song]-shaped lookup, `browse:` by a folder fetch, and picking the wrong
 * one here is a folder that opens empty. Only a `VL`-prefixed browse id is a
 * playlist — an album's `MPREb_` id or an artist channel id has to stay a
 * folder, however the card is worded.
 */
internal fun shelfToGridPlaylistItem(playlist: ShelfItem): MediaItem {
    val browseId = playlist.browseId ?: "VL${playlist.videoId}"
    val mediaId = if (browseId.startsWith("VL")) "playlist:${browseId.removePrefix("VL")}" else "browse:$browseId"
    return createGridPlaylistItem(
        mediaId = mediaId,
        title = playlist.title,
        subtitle = playlist.subtitle,
        artworkUrl = playlist.thumbnailUrl,
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun browseItemToGridItem(item: BrowseItem): MediaItem = createGridPlaylistItem(
    mediaId = "browse:${item.browseId}",
    title = item.title,
    subtitle = item.subtitle,
    artworkUrl = item.thumbnailUrl,
    folderType = if (item.browseId.startsWith("MPREb_")) MediaMetadata.FOLDER_TYPE_ALBUMS else MediaMetadata.FOLDER_TYPE_PLAYLISTS,
)

/** A folder in the browse tree, laid out as a list unless it holds covers. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun createFolderItem(
    mediaId: String,
    title: String,
    subtitle: String? = null,
    folderType: Int = MediaMetadata.FOLDER_TYPE_MIXED,
    isGrid: Boolean = false,
): MediaItem {
    val style = if (isGrid) CONTENT_STYLE_GRID_ITEM_HINT_VALUE else CONTENT_STYLE_LIST_ITEM_HINT_VALUE
    val extras = buildContentStyleBundle(style, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setFolderType(if (isGrid && folderType == MediaMetadata.FOLDER_TYPE_MIXED) MediaMetadata.FOLDER_TYPE_ALBUMS else folderType)
                .setExtras(extras)
                .build(),
        )
        .build()
}

/** A row that explains something instead of playing or opening: an error, a hint. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun createInfoItem(mediaId: String, title: String, subtitle: String? = null): MediaItem =
    MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .setExtras(buildContentStyleBundle(CONTENT_STYLE_LIST_ITEM_HINT_VALUE, CONTENT_STYLE_LIST_ITEM_HINT_VALUE))
                .build(),
        )
        .build()

/**
 * A home card as a [Song], or nothing if it isn't a track.
 *
 * The artist is read back out of the byline because that is all a shelf card
 * carries; a playlist row says "Playlist · 12 songs" and an album row says the
 * artist and year, and only the latter has a name to lift.
 */
internal fun ShelfItem.toSongOrNull(): Song? {
    val id = videoId ?: return null
    return Song(
        videoId = id,
        title = title,
        artist = InnertubeParser.artistFromSubtitle(subtitle),
        thumbnailUrl = thumbnailUrl,
    )
}
