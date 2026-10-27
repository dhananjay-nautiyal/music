// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.3.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.compose") version "1.10.3" apply false
}

// Reproducibility (dhananjay-nautiyal/music#21): pin every resolved version to a
// committed lock file so a dependency cannot silently move between builds — a
// bumped or substituted artifact fails the build instead of resolving quietly.
//
// lockAllConfigurations() rather than a hand-maintained list: new configurations
// introduced by plugins are locked by default, so a fresh lock file cannot go stale.
// Regenerate with `./gradlew --write-locks <tasks>` after an intentional bump.
//
// `:desktopApp` is deliberately left out, because its dependency graph is not the
// same on all three desktop OSes and a lock file records module *identity*:
//
//   * haze is 1.7.3 on Linux/macOS but pinned to 1.7.2 on Windows
//     (desktopApp/build.gradle.kts — keeps Compose Desktop 1.10.3 on its own runtime)
//   * Compose Desktop resolves `desktop-jvm-linux-x64` / `-windows-x64` / `-macos-*`
//   * Skiko resolves `skiko-awt-runtime-linux-x64` / `-windows-x64` / `-macos-*`
//
// Those are different modules, so a single committed lock file cannot satisfy all
// three hosts at once: locking here would fail the Windows and macOS CI legs on
// "existing entries in the lock state must be matched". Locking the other three
// projects is still sound — their graphs are host-independent. Per-OS artifacts
// are covered by gradle/verification-metadata.xml instead, which is checksum-based
// and holds one entry per file rather than one per module.
allprojects {
    if (name != "desktopApp") {
        dependencyLocking {
            lockAllConfigurations()
        }
    }
}
