package com.music.music.playback

import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.music.music.data.model.BrowseItem
import com.music.music.data.model.BrowseType
import com.music.music.data.model.ShelfItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paging arithmetic and the media-id rules behind the Android Auto browse tree.
 *
 * These were private members of [PlaybackService] until #23 moved them into
 * `AutoBrowseItems.kt`, where exercising them meant driving a browser on a device. Two of the
 * rules here fail loudly and one fails quietly: a `page * pageSize` that overflows `Int` throws
 * inside [List.subList] and the folder arrives empty on the head unit, while a wrong `VL` prefix
 * hands back an id the browser resolves to a different kind of thing — a playlist opening as an
 * empty folder, or an album opening as a track list.
 *
 * The content-style extras are deliberately not asserted: [android.os.Bundle] is stubbed out in
 * a JVM test, so it answers defaults rather than whatever was put into it. Which keys a hint is
 * written under only shows up on a real head unit.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AutoBrowseItemsTest {

    private companion object {
        /** More items than one page, fewer than to bother generating. */
        const val LIBRARY_SIZE = 7

        /** Paging at this size puts the whole library on page 0. */
        const val WHOLE_LIBRARY = 100

        fun items(count: Int): List<Int> = (1..count).toList()

        fun shelf(
            title: String = "Late Night",
            subtitle: String = "Elara Vance • 3,102 plays",
            videoId: String? = null,
            browseId: String? = null,
        ) = ShelfItem(
            title = title,
            subtitle = subtitle,
            thumbnailUrl = "https://i.ytimg.com/vi/cover/hqdefault.jpg",
            videoId = videoId,
            browseId = browseId,
        )

        fun browse(browseId: String, title: String = "Blue River") = BrowseItem(
            browseId = browseId,
            title = title,
            subtitle = "Elara Vance",
            thumbnailUrl = null,
            type = BrowseType.ALBUM,
        )
    }

    // --- paginate ---------------------------------------------------------------

    @Test
    fun `returns exactly the slice the page asks for`() {
        assertEquals(listOf(4, 5, 6), paginate(items(LIBRARY_SIZE), page = 1, pageSize = 3))
    }

    @Test
    fun `the last page is short rather than padded`() {
        assertEquals(listOf(7), paginate(items(LIBRARY_SIZE), page = 2, pageSize = 3))
    }

    @Test
    fun `a page past the end is empty rather than an error`() {
        assertTrue(paginate(items(LIBRARY_SIZE), page = 3, pageSize = 3).isEmpty())
        assertTrue(paginate(items(6), page = 1, pageSize = 6).isEmpty())
    }

    @Test
    fun `the page offset is worked out wide enough not to wrap`() {
        // A controller may ask for any page it has ever seen. In Int arithmetic this offset
        // becomes negative on the way past the list, and subList answers that with a crash.
        assertEquals(emptyList<Int>(), paginate(items(LIBRARY_SIZE), page = Int.MAX_VALUE, pageSize = 50))
        assertEquals(emptyList<Int>(), paginate(items(LIBRARY_SIZE), page = 1 shl 30, pageSize = MAX_AUTO_PAGE_SIZE))
    }

    @Test
    fun `a negative page or a zero size asks for nothing`() {
        assertTrue(paginate(items(LIBRARY_SIZE), page = -1, pageSize = 3).isEmpty())
        assertTrue(paginate(items(LIBRARY_SIZE), page = 0, pageSize = 0).isEmpty())
        assertTrue(paginate(items(LIBRARY_SIZE), page = 0, pageSize = -5).isEmpty())
        assertTrue(paginate(emptyList<Int>(), page = 0, pageSize = 3).isEmpty())
    }

    @Test
    fun `an inflated page size is held to the cap`() {
        val page = paginate(items(WHOLE_LIBRARY), page = 0, pageSize = WHOLE_LIBRARY * 10)
        assertEquals(MAX_AUTO_PAGE_SIZE, page.size)
        assertEquals(1, page.first())
        assertEquals(MAX_AUTO_PAGE_SIZE, page.last())
    }

    @Test
    fun `page zero takes the first page rather than the whole list`() {
        assertEquals(items(3), paginate(items(LIBRARY_SIZE), page = 0, pageSize = 3))
    }

    // --- shelf cards ------------------------------------------------------------

    @Test
    fun `a playlist card is addressed as a playlist`() {
        val item = shelfToGridPlaylistItem(shelf(browseId = "VLplaylist42"))
        assertEquals("playlist:playlist42", item.mediaId)
    }

    @Test
    fun `a card whose browse id is not a playlist stays a folder`() {
        // `MPREb_` is an album and `UC` an artist channel. Prefixing either with `playlist:`
        // would send the tap to the song lookup, which has nothing to resolve them to.
        assertEquals("browse:MPREb_album", shelfToGridPlaylistItem(shelf(browseId = "MPREb_album")).mediaId)
        assertEquals("browse:UCchannel", shelfToGridPlaylistItem(shelf(browseId = "UCchannel")).mediaId)
    }

    @Test
    fun `a card with only a video id is a playlist of its own`() {
        assertEquals("playlist:vid123", shelfToGridPlaylistItem(shelf(videoId = "vid123")).mediaId)
    }

    @Test
    fun `a shelf card keeps its title and is browsable rather than playable`() {
        val item = shelfToGridPlaylistItem(shelf(title = "Late Night", subtitle = "Elara Vance", browseId = "VLx"))
        assertEquals("Late Night", item.mediaMetadata.title)
        assertEquals("Elara Vance", item.mediaMetadata.subtitle)
        assertTrue(item.mediaMetadata.isBrowsable!!)
        assertFalse(item.mediaMetadata.isPlayable!!)
    }

    @Test
    fun `a home card becomes a song with the artist read back out of the byline`() {
        val song = shelf(videoId = "vid1", subtitle = "Elara Vance • 3,102 plays").toSongOrNull()
        assertEquals("vid1", song?.videoId)
        assertEquals("Elara Vance", song?.artist)
    }

    @Test
    fun `the artist is the first part of the byline that is not a label or a tally`() {
        assertEquals("Elara Vance", shelf(videoId = "v", subtitle = "Album • Elara Vance").toSongOrNull()?.artist)
    }

    @Test
    fun `a card that is not a track is not a song`() {
        assertNull(shelf(browseId = "MPREb_album").toSongOrNull())
    }

    // --- the builders' shape ----------------------------------------------------

    @Test
    fun `a browse result is an album only when its id says so`() {
        assertEquals(
            MediaMetadata.FOLDER_TYPE_ALBUMS,
            browseItemToGridItem(browse("MPREb_9xQw")).mediaMetadata.folderType,
        )
        assertEquals(
            MediaMetadata.FOLDER_TYPE_PLAYLISTS,
            browseItemToGridItem(browse("VLplaylist42")).mediaMetadata.folderType,
        )
        assertEquals("browse:MPREb_9xQw", browseItemToGridItem(browse("MPREb_9xQw")).mediaId)
    }

    @Test
    fun `a grid folder with no declared kind is an album`() {
        assertEquals(
            MediaMetadata.FOLDER_TYPE_ALBUMS,
            createFolderItem("playlists", "Playlists", isGrid = true).mediaMetadata.folderType,
        )
        assertEquals(
            MediaMetadata.FOLDER_TYPE_MIXED,
            createFolderItem("more", "More").mediaMetadata.folderType,
        )
        assertEquals(
            MediaMetadata.FOLDER_TYPE_PLAYLISTS,
            createFolderItem("liked", "Liked", folderType = MediaMetadata.FOLDER_TYPE_PLAYLISTS, isGrid = true)
                .mediaMetadata.folderType,
        )
    }

    @Test
    fun `a folder browses and an info row does neither`() {
        val folder = createFolderItem("recents", "Recent")
        assertTrue(folder.mediaMetadata.isBrowsable!!)
        assertFalse(folder.mediaMetadata.isPlayable!!)

        val info = createInfoItem("msg:empty", "Nothing here yet")
        assertFalse(info.mediaMetadata.isBrowsable!!)
        assertFalse(info.mediaMetadata.isPlayable!!)
        assertEquals("Nothing here yet", info.mediaMetadata.title)
    }
}
