package com.music.music.data.sources

/**
 * A playlist/share link pasted for import, classified by where it points.
 *
 * Spotify import already exists ([SpotifyImporter]); YouTube and JioSaavn links
 * previously fell through to "invalid Spotify link". Classifying first gives a
 * useful error ("that's a YouTube playlist — open it via Search") and a single
 * entry point for future importers (Apple Music, Deezer) without touching the
 * import dialog again.
 */
sealed interface ParsedPlaylistLink {
    data class Spotify(val playlistId: String) : ParsedPlaylistLink
    data class YouTubePlaylist(val playlistId: String) : ParsedPlaylistLink
    data class YouTubeVideo(val videoId: String) : ParsedPlaylistLink
    data class JioSaavn(val playlistId: String) : ParsedPlaylistLink
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

        // YouTube playlist: music.youtube.com / youtube.com watch?list= or /playlist?list=
        youTubePlaylistId(trimmed)?.let { return ParsedPlaylistLink.YouTubePlaylist(it) }
        youTubeVideoId(trimmed)?.let { return ParsedPlaylistLink.YouTubeVideo(it) }

        // JioSaavn: saavn.com / jiosaavn.com ... /playlist|album|song/<slug>/<id>
        jioSaavnId(trimmed)?.let { return ParsedPlaylistLink.JioSaavn(it) }

        return ParsedPlaylistLink.Unsupported("not a Spotify, YouTube or JioSaavn link")
    }

    private fun youTubePlaylistId(raw: String): String? = runCatching {
        val url = android.net.Uri.parse(raw.trim())
        // youtu.be never carries a playlist; watch / playlist / embed paths do.
        val list = url.getQueryParameter("list")
        if (!list.isNullOrBlank() && looksLikeYouTubeHost(url.host.orEmpty())) list else null
    }.getOrNull()

    private fun youTubeVideoId(raw: String): String? = runCatching {
        val text = raw.trim()
        val host = runCatching { android.net.Uri.parse(text).host.orEmpty() }.getOrDefault("")
        if (!looksLikeYouTubeHost(host) && !host.equals("youtu.be", ignoreCase = true)) return null
        val url = android.net.Uri.parse(text)
        if (host.equals("youtu.be", ignoreCase = true)) {
            url.pathSegments.firstOrNull()?.takeIf { it.length == 11 }
        } else {
            url.getQueryParameter("v")?.takeIf { it.length == 11 }
                ?: url.pathSegments.lastOrNull()?.takeIf { it.length == 11 }
        }
    }.getOrNull()

    private fun looksLikeYouTubeHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.").removePrefix("m.")
        return h == "youtube.com" || h == "music.youtube.com" || h == "youtu.be" ||
            h.endsWith(".youtube.com")
    }

    private fun jioSaavnId(raw: String): String? = runCatching {
        val url = android.net.Uri.parse(raw.trim())
        val host = url.host.orEmpty().lowercase()
        if (!host.contains("saavn")) return null
        // .../playlist/<slug>/<id> or .../album/.../<id> or ?listid=
        url.getQueryParameter("listid")?.takeIf { it.isNotBlank() }
            ?: url.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
