# REA reverse notes — BitChord v1.8 -> Music

Method: REA shipped-artifact + source corroboration (source repo is complete,
so per skill no decompilation loop was needed beyond inventory).

## 1. Shipped artifact (observed, REA)

- File: `BitChord-v1.8.apk` (universal, from
  `gh release download v1.8 --repo kushagrasinghx/BitChord`)
- SHA-256: `cdd7b955fbcf9ff9e8286bb8334cb2c6887461af78582bcc0ca1da658f447a03`
- Size: ~151 MB
- REA: `open_binary({path})` + `inspect_artifact({})`
  - evidence_id: `ev_86ff7d657c29f7562d6098c3080e5f726e016ef04fd203ea25d26a8836dff9ba`
  - inspection_id: `ai_5ef39ef318b6fb9147fe3067a0f01e15f8a782a77fc29c7b102bff568bc3ef61`
  - root_format: `apk`, root_sha256 matches above
  - manifest graph: 1323 nodes / 1479 occurrences / 1478 edges
  - coverage: `complete-within-substeps`
- NOT performed (explicit limits): JADX class/method decompile and apktool
  resource decode — providers unavailable in this host (`REA_JADX_MCP_JAR`
  and `REA_APKTOOL_COMMAND` unset; REA fails closed with
  `capability_unavailable`). No DEX contents, signatures, or runtime calls
  claimed from static inventory. No execution, no network fetch of recorded URLs.

## 2. Source-corroborated logic (historical reference)

Clone: `https://github.com/kushagrasinghx/BitChord` (`main`, 716 commits,
Kotlin 8.8M, 683 `.kt` files, GPL-3.0).

- Install identity (observed in `app/build.gradle.kts` + `AndroidManifest.xml`):
  `namespace/applicationId com.music.bitchord`, `compileSdk 37`,
  `minSdk 26 / targetSdk 36`, `versionName 1.8 / versionCode 26`,
  per-ABI splits + universal APK, `dev` flavor `com.dev.bitchord`.
- Playback sources (observed): `MusicSource.kt` interface
  (`search` + `resolve stream` + `health`), `SourceKind.kt` rank
  ADDON/CUSTOM_MODULE 0, MODULE 1, JIOSAAVN 2, YOUTUBE 3;
  `SourceResolver.kt` walk, `SourceRegistry.kt` encrypted-prefs configs;
  `YouTubeSource.kt`, `JioSaavnSource.kt`, `AddonSource.kt`, `ModuleSource.kt`;
  `shared/.../data/innertube/` (Innertube, StreamResolver, PlayerClient,
  PoToken), `shared/.../data/spotify/` (Importer, Library),
  `desktopApp/.../DesktopJioSaavn.kt`.
- App surface (from manifest + README, not execution): Media3 foreground
  playback, downloads, local library, Android Auto, per-network quality,
  crossfade/automix, lyrics animation, dynamic theming, equalizer, sleep
  timer, stats-for-nerds, update + installer permission.

## 3. What the Music rebrand changes (design choices, not observations)

- `applicationId com.music.bitchord` -> `com.music.music`
  (`com.dev.bitchord` -> `com.dev.music`); `app_name` `BitChord` -> `Music`
  (`BitChord Dev` -> `Music Dev`); `rootProject.name` -> `music`.
- Internal package `com.music.bitchord` kept (all sources + manifest
  references unchanged) — install identity differs, code identical.
- Added `MusicPlatforms.kt` facade (delegates, no new protocol) + `MUSIC.md`.
- License: GPL-3.0 retained with upstream attribution.

Unknowns: DEX-level behaviour, signature validity, runtime playback quality,
server-side module/addon contents. Nothing above implies execution.
