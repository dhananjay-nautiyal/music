package com.music.music.data.apple

import com.music.music.data.DebugLog as Log
import com.music.music.data.Http
import com.music.music.data.canvas.AppleMusicCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.util.Locale

private const val TAG = "Music"

/**
 * Apple Music catalogue over the same unofficial `amp-api` the canvas lookup
 * already uses — no developer token or Apple Developer account needed. The
 * anonymous web-player token is shared with [AppleMusicCanvas] rather than
 * scraped twice.
 *
 * What this API can and cannot do shapes the whole feature: catalogue search
 * and playlist contents are readable, but the only playable audio it hands
 * out is 30-second previews. Full-length Apple streams are FairPlay DRM and
 * unplayable in any third-party player, so this service never pretends
 * otherwise — previews for tapping, full tracks via match-to-YouTube import.
 */
object AppleMusicService {

    data class AppleTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long?,
        val artworkUrl: String?,
        val previewUrl: String?,
        val isExplicit: Boolean,
    )

    private const val AMP = "https://amp-api.music.apple.com/v1/catalog"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Catalogue region; the device's when sane, `us` otherwise — same rule as the canvas lookup. */
    val storefront: String
        get() = Locale.getDefault().country.takeIf { it.length == 2 }?.lowercase(Locale.ROOT) ?: "us"

    /** Catalogue reachability for the sources screen: true when a web-player token scrapes. */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        runCatching { AppleMusicCanvas.token() != null }.getOrDefault(false)
    }

    /** Catalogue song search, best matches first as Apple ranked them. */
    suspend fun searchSongs(query: String, limit: Int = 25): List<AppleTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val bearer = AppleMusicCanvas.token() ?: return@withContext emptyList()
        val url = "$AMP/$storefront/search?term=${encode(query)}&types=songs&limit=${limit.coerceIn(1, 25)}"
        val body = get(url, bearer) ?: return@withContext emptyList()
        runCatching { parseTracks(body, limit) }
            .onFailure { Log.w(TAG, "Apple catalogue search failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** One catalogue song by id, for resolving a preview stream URL. */
    suspend fun track(id: String): AppleTrack? = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext null
        val bearer = AppleMusicCanvas.token() ?: return@withContext null
        val body = get("$AMP/$storefront/songs/$id", bearer) ?: return@withContext null
        runCatching { parseTracks(body, 1).firstOrNull() }
            .onFailure { Log.w(TAG, "Apple catalogue lookup failed: ${it.message}") }
            .getOrNull()
    }

    /**
     * A catalogue playlist's title and tracks: title/artist pairs ready for
     * the YouTube matcher. Follows relationship pages up to [MAX_TRACKS];
     * most playlists fit in the first page.
     */
    suspend fun playlistTracks(playlistId: String): Pair<String, List<PlaylistEntry>> =
        withContext(Dispatchers.IO) {
            val bearer = AppleMusicCanvas.token()
                ?: error("Apple catalogue unreachable — try again later")
            var url: String? = "$AMP/$storefront/playlists/$playlistId"
            var title: String? = null
            val out = mutableListOf<PlaylistEntry>()
            while (url != null && out.size < MAX_TRACKS) {
                val body = get(url, bearer) ?: error("Apple playlist could not be read")
                val page = runCatching { parsePlaylistPage(body) }
                    .getOrElse { error("Apple playlist could not be parsed") }
                if (title == null) title = page.title
                out += page.tracks
                url = page.next?.let { if (it.startsWith("http")) it else "https://amp-api.music.apple.com$it" }
            }
            val name = title ?: error("Apple playlist is empty or private")
            if (out.isEmpty()) error("No tracks found in Apple playlist.")
            name to out
        }

    data class PlaylistEntry(val title: String, val artist: String)

    /**
     * An Apple Music playlist id from pasted text: a bare `pl.…` id or any
     * `music.apple.com/…/playlist/…` URL (slug form included). Pure string/URI
     * work — no Android classes, so unit tests run on plain JVM.
     */
    fun extractPlaylistId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("pl.") && PL_ID.matches(trimmed)) return trimmed
        val parsed = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        if (!parsed.host.orEmpty().lowercase(Locale.ROOT).endsWith("music.apple.com")) return null
        val segments = parsed.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val idx = segments.indexOf("playlist")
        if (idx == -1) return null
        // …/playlist/<id> or …/playlist/<slug>/<id>
        return listOfNotNull(segments.getOrNull(idx + 1), segments.getOrNull(idx + 2))
            .firstOrNull { it.startsWith("pl.") && PL_ID.matches(it) }
    }

    /** A playlist id is `pl.` plus base62/word characters and dots/dashes. */
    private val PL_ID = Regex("""pl\.[A-Za-z0-9._-]+""")

    /** Catalogue songs out of a search or lookup response. Pure — unit-tested with canned JSON. */
    internal fun parseTracks(body: String, limit: Int): List<AppleTrack> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        // Search nests under results.songs.data; a direct lookup under data.
        val data = root["results"]?.jsonObject
            ?.get("songs")?.jsonObject
            ?.get("data")?.jsonArray
            ?: root["data"]?.jsonArray
            ?: return emptyList()
        return data.take(limit.coerceAtLeast(1)).mapNotNull { element ->
            val song = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            parseTrack(song)
        }
    }

    internal fun parseTrack(song: kotlinx.serialization.json.JsonObject): AppleTrack? {
        val id = song["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val attributes = song["attributes"]?.jsonObject ?: return null
        val title = attributes["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val artist = attributes["artistName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val artwork = attributes["artwork"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?.replace("{w}", "500")?.replace("{h}", "500")
        val preview = attributes["previews"]?.jsonArray
            ?.firstOrNull() as? kotlinx.serialization.json.JsonObject
        val previewUrl = preview?.get("url")?.jsonPrimitive?.contentOrNull
        return AppleTrack(
            id = id,
            title = title,
            artist = artist.ifBlank { "Unknown Artist" },
            album = attributes["albumName"]?.jsonPrimitive?.contentOrNull,
            durationMs = attributes["durationInMillis"]?.jsonPrimitive?.longOrNull,
            artworkUrl = artwork,
            previewUrl = previewUrl,
            isExplicit = attributes["contentRating"]?.jsonPrimitive?.contentOrNull == "explicit",
        )
    }

    internal data class PlaylistPage(
        val title: String?,
        val tracks: List<PlaylistEntry>,
        val next: String?,
    )

    /** One playlist response page: title, inline tracks, and the next page path. Pure. */
    internal fun parsePlaylistPage(body: String): PlaylistPage {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return PlaylistPage(null, emptyList(), null)
        val entry = root["data"]?.jsonArray?.firstOrNull() as? kotlinx.serialization.json.JsonObject
            ?: return PlaylistPage(null, emptyList(), null)
        val title = entry["attributes"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        val rel = entry["relationships"]?.jsonObject?.get("tracks")?.jsonObject
        val tracks = rel?.get("data")?.jsonArray?.mapNotNull { element ->
            val track = (element as? kotlinx.serialization.json.JsonObject)
                ?.get("attributes")?.jsonObject ?: return@mapNotNull null
            val name = track["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            PlaylistEntry(
                title = name,
                artist = track["artistName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            )
        }.orEmpty()
        return PlaylistPage(title, tracks, rel?.get("next")?.jsonPrimitive?.contentOrNull)
    }

    private const val MAX_TRACKS = 300

    private fun encode(value: String): String =
        runCatching { java.net.URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)

    private fun get(url: String, bearer: String): String? {
        val request = Request.Builder().url(url).apply {
            header("Authorization", "Bearer $bearer")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
        }.build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }
}
