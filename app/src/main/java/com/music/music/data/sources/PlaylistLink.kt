package com.music.music.data.sources

import java.util.Locale

/**
 * A playlist/share link pasted for import, classified by where it points.
 *
 * Spotify import already exists ([SpotifyImporter]); YouTube and JioSaavn links
 * previously fell through to "invalid Spotify link". Classifying first gives a
 * useful error ("that's a YouTube playlist — open it via Search") and a single
 * entry point for future importers without touching the import dialog again.
 *
 * URL parsing is pure `java.net` work, deliberately not `android.net.Uri`:
 * the latter is unavailable in plain-JVM unit tests, which is exactly where
 * link parsing is tested.
 */
sealed interface ParsedPlaylistLink {
    data class Spotify(val playlistId: String) : ParsedPlaylistLink
    data class YouTubePlaylist(val playlistId: String) : ParsedPlaylistLink
    data class YouTubeVideo(val videoId: String) : ParsedPlaylistLink
    data class JioSaavn(val playlistId: String) : ParsedPlaylistLink
    data class AppleMusic(val playlistId: String) : ParsedPlaylistLink
    data class Unsupported(val reason: String) : ParsedPlaylistLink
}

object PlaylistLink {

    /**
     * Classify [raw] link text. Never throws — unrecognised input is
     * [ParsedPlaylistLink.Unsupported].
     */
    fun parse(raw: String): ParsedPlaylistLink {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ParsedPlaylistLink.Unsupported("empty link")

        // Spotify first: its own parser already handles URIs, open URLs, embeds.
        runCatching {
            com.music.music.data.spotify.SpotifyImporter.extractPlaylistId(trimmed)
        }.getOrNull()?.let { return ParsedPlaylistLink.Spotify(it) }

        val url = parseUrl(trimmed)

        // YouTube playlist: music.youtube.com / youtube.com watch?list= or /playlist?list=
        if (url != null && looksLikeYouTubeHost(url.host)) {
            // youtu.be never carries a playlist; watch / playlist / embed paths do.
            url.query["list"]?.takeIf { it.isNotBlank() }?.let {
                return ParsedPlaylistLink.YouTubePlaylist(it)
            }
        }
        youTubeVideoId(url)?.let { return ParsedPlaylistLink.YouTubeVideo(it) }

        // JioSaavn: saavn.com / jiosaavn.com ... /playlist|album|song/<slug>/<id>
        if (url != null && url.host.contains("saavn")) {
            // .../playlist/<slug>/<id> or .../album/.../<id> or ?listid=
            url.query["listid"]?.takeIf { it.isNotBlank() }?.let {
                return ParsedPlaylistLink.JioSaavn(it)
            }
            url.segments.lastOrNull()?.takeIf { it.isNotBlank() }?.let {
                return ParsedPlaylistLink.JioSaavn(it)
            }
        }

        // Apple Music catalogue playlists: music.apple.com/…/playlist/…/pl.…
        com.music.music.data.apple.AppleMusicService.extractPlaylistId(trimmed)?.let {
            return ParsedPlaylistLink.AppleMusic(it)
        }

        return ParsedPlaylistLink.Unsupported("not a Spotify, YouTube, JioSaavn or Apple Music link")
    }

    private fun youTubeVideoId(url: ParsedUrl?): String? {
        if (url == null) return null
        if (!looksLikeYouTubeHost(url.host) && !url.host.equals("youtu.be", ignoreCase = true)) return null
        return if (url.host.equals("youtu.be", ignoreCase = true)) {
            url.segments.firstOrNull()?.takeIf { it.length == 11 }
        } else {
            url.query["v"]?.takeIf { it.length == 11 }
                ?: url.segments.lastOrNull()?.takeIf { it.length == 11 }
        }
    }

    private fun looksLikeYouTubeHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.")
        return h == "youtube.com" || h == "music.youtube.com" || h == "youtu.be" ||
            h.endsWith(".youtube.com")
    }

    private data class ParsedUrl(
        val host: String,
        val segments: List<String>,
        val query: Map<String, String>,
    )

    private fun parseUrl(raw: String): ParsedUrl? = runCatching {
        val uri = java.net.URI(raw.trim())
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (host.isEmpty()) return@runCatching null
        val segments = uri.rawPath.orEmpty().split('/')
            .filter { it.isNotEmpty() }
            .map { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx < 0) null
            else {
                val name = runCatching { java.net.URLDecoder.decode(part.substring(0, idx), "UTF-8") }
                    .getOrDefault(part.substring(0, idx))
                val value = runCatching { java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8") }
                    .getOrDefault(part.substring(idx + 1))
                name to value
            }
        }.toMap()
        ParsedUrl(host, segments, query)
    }.getOrNull()
}
