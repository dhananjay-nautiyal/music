# Contributing to Music

Thank you for contributing to Music. Follow these guidelines to ensure an efficient development and review process.

## Proposing Contributions
- Substantial features, refactors, or UI redesigns should normally be discussed before implementation. Open an issue on GitHub to outline your proposal.
- Straightforward bug fixes, documentation improvements, or minor corrections can be submitted directly as a Pull Request.

## Help Wanted — Where to Start

Issues that are open for contribution carry the **`help wanted`** label. Those marked
**`good first issue`** are self-contained: one focused pull request, with a clear way to verify
the fix, and no deep context on the codebase required.

| Label | Meaning |
| --- | --- |
| `good first issue` | Self-contained — one PR, clear verification, a good place to start |
| `help wanted` | Open for contribution. Some need a maintainer decision before code is written |
| `bug` | Current behaviour contradicts what the code is documented to do |
| `enhancement` | Nothing is broken — this is a new capability or a cleanup |
| `severity: high` | User-visible breakage or a security weakness |

Find the current list:

- [Open issues labelled `help wanted`](https://github.com/dhananjay-nautiyal/music/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22)
- [Open issues labelled `good first issue`](https://github.com/dhananjay-nautiyal/music/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)

**Every `help wanted` issue carries a "Where to work on this" comment.** It names the module, the
files to open, the command that verifies the fix, and — where it matters — a note about which
decision still has to be made by a maintainer first. Please read it before you start; several of
these issues deliberately ask you to open a short issue to agree on the approach, because the
fix is not obvious from the bug report alone.

### Which part of the repository am I working in?

| Path | What lives there |
| --- | --- |
| `app/` | The Android application — UI, playback service, downloads, sources |
| `shared/` | Shared Kotlin Multiplatform code — Innertube, lyrics providers, presence, models |
| `sharedUi/` | Compose UI shared between the Android and desktop apps |
| `desktopApp/` | The Compose Desktop application and its packaging |
| `backend/` | The Go "Listen Together" party server |
| `native/` | The C++ audio analysis library (`music_analysis`) |
| `.github/` | CI workflows, release pipelines, issue templates |

## Branch and Pull Request Workflow
Always check the repository for the current active version or development branch before starting work.

New work should normally be based on the latest active version or development branch rather than `main`, unless maintainers explicitly instruct otherwise. For example, during the `v1.6.x` release cycle, contributions branch from `v1.6.1`. As new release cycles begin, the target branch will advance accordingly.

When contributing:
- Fork the repository and create a descriptive branch (e.g., `fix/streaming-buffer`, `docs/translation-guide`).
- Keep pull requests focused on a single change or bug fix. Avoid mixing unrelated refactors, cosmetic tweaks, or mass reformatting with functional changes.
- Keep your branch reasonably synchronized with the target base branch.
- Provide a clear PR description explaining what was changed and why.
- Include relevant testing and validation details with your PR.
- Address review feedback promptly and keep discussions focused on technical merits.

## Development Setup
The project requires the following tools:
- **JDK**: Java Development Kit 17 (Eclipse Temurin 17 recommended).
- **Android SDK**: `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`.
- **C/C++ NDK & CMake**: CMake 3.22.1+ and Android NDK (for native audio DSP components configured under `app/src/main/cpp`).
- **Listen Together Backend (Optional)**: Go 1.22+ if developing or testing the party server (`backend/`).

## Build and Test Commands
Run Gradle commands from the repository root.

Build:
```bash
./gradlew assembleDevDebug
```

Windows:
```powershell
.\gradlew.bat assembleDevDebug
```

Tests:
```bash
./gradlew testDevDebugUnitTest
```

Windows:
```powershell
.\gradlew.bat testDevDebugUnitTest
```

Run a specific test:
```bash
./gradlew testDevDebugUnitTest --tests "com.music.music.playback.audio.DirectAudioStreamingRegressionTest"
```

## Code Quality
- Follow idiomatic Kotlin and Jetpack Compose conventions.
- Maintain the project's existing architecture and separation of concerns (UI, domain logic, playback services, and data repositories).
- Write unit tests for new logic, fixes, and edge cases.
- Contributors should run relevant tests, must not introduce new failures, and should document known pre-existing failures when applicable.
- Keep diffs focused and minimal. Avoid unnecessary third-party dependencies.

## Audio Changes and Telemetry
For audio changes: application telemetry must not be presented as proof of physical hardware behavior unless that hardware behavior was actually verified.

Distinguish clearly between source metadata, decoder format, internal DSP format, AudioTrack output, AudioFlinger/HAL state, advertised device capability, and external physical endpoint behavior.

## Review Process
All pull requests require review and approval by repository maintainers before merging. Maintainers review changes for correctness, architecture fit, and maintainability.

## Dependencies and Licensing
Music is licensed under the **GNU General Public License v3.0 (GPLv3)**.

All contributed code and dependencies must be strictly compatible with GPLv3. Avoid introducing external dependencies unless strictly necessary; any new dependency must be evaluated for necessity, binary size, and license compliance.
