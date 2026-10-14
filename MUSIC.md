# Music (APK name: "music")

Modern multi-platform music client, install identity `com.music.music`
(dev: `com.dev.music`, launcher "Music" / "Music Dev").

Same logic as Music v1.8 — this repo is a rebrand, not a rewrite:
internal Kotlin package stays `com.music.music` so all 683 sources keep
building; only the Play-store identity (`applicationId`), launcher name
(`app_name`), and repo name changed.

Upstream: https://github.com/kushagrasinghx/BitChord — GPL-3.0, see LICENSE.
All GPL notices and attribution preserved. This fork stays GPL-3.0.

## Music platforms (same as Music)

| Platform | Type | Implementation |
|---|---|---|
| YouTube Music | full catalogue, radio, feeds | `app/.../data/sources/YouTubeSource.kt` + `shared/.../data/innertube/` (InnerTube, StreamResolver, PoToken) |
| JioSaavn | 320kbps fast prefetch | `app/.../data/sources/JioSaavnSource.kt` + `data/jiosaavn/JioSaavnService.kt` (Android); `desktopApp/.../DesktopJioSaavn.kt` (desktop) |
| Addon server | user Hi-Res/FLAC over HTTP | `app/.../data/sources/AddonSource.kt` |
| Module index (legacy) | sandboxed JS plugins, e.g. Tidal/Qobuz style | `app/.../data/sources/ModuleSource.kt` |
| Spotify | import playlists + Liked Songs | `shared/.../data/spotify/SpotifyImporter.kt`, `SpotifyLibrary.kt` |
| Local | on-device MediaStore | local library integration + offline downloads |
| Lyrics (11) | word-synced | lrc.red, BiniLyrics, BetterLyrics, PaxSenix, LyricsPlus, SimpMusic, Unison, Megalobiz, KuGou, LRCLIB, Musixmatch, Genius |
| Social | scrobble/presence | Last.fm, ListenBrainz, Discord Rich Presence, Listen Together backend (`backend/`, Go) |

Unified entry point: `app/src/main/java/com/music/music/data/sources/MusicPlatforms.kt`.

## Build the "music" APK

Android SDK required (this machine has none — Music's `settings.gradle.kts`
skips `:app` without one and builds desktop only).

```bash
./gradlew :app:assembleProdRelease
# output: app/build/outputs/apk/prod/release/app-prod-release.apk  (app name "Music")
./gradlew :app:assembleDevDebug   # side-by-side dev build "Music Dev"
```

Unsigned without `keystore.properties` (see `keystore.properties.example`);
signing config falls back to unsigned rather than failing.

## Reverse-engineering notes

See `REA_REVERSE_NOTES.md` for the REA shipped-artifact evidence
(APK `BitChord-v1.8.apk`, SHA-256 `cdd7b955...`, 1323-node inventory) and the
source-corroborated logic map used for this rebrand.
