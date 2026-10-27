pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor is published via JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "music"
include(":shared")
include(":sharedUi")
include(":desktopApp")

/*
 * The human version of the app lives in `VERSION` at the repo root and nowhere else (#37).
 * Read and validated here, once, so the Android module's `versionName`, `appVersion` in
 * `desktopApp/build.gradle.kts` and the desktop update check's fallback cannot each hold a
 * slightly different copy of it. Modules pick it up as `rootProject.extra["repoVersion"]`.
 *
 * The two *numeric* counters — Android's `versionCode` and `desktopVersionCode` — stay separate
 * on purpose; see the comments where each is declared.
 */
val versionFile = file("VERSION")
require(versionFile.isFile) {
    "VERSION at the repo root is missing. It is the single source of truth for the app version."
}
val repoVersion = versionFile.readText().trim().removePrefix("v").also {
    require(it.matches(Regex("""\d+\.\d+(\.\d+)?(-[0-9A-Za-z][0-9A-Za-z._-]*)?"""))) {
        "VERSION must hold a plain version like 1.1.2 or 1.1.3-beta1, got \"$it\""
    }
}
gradle.rootProject { extra["repoVersion"] = repoVersion }

// The Android module only when there is an SDK to build it against. A machine
// set up for the desktop alone has none, and including it there fails at
// configuration time — before the desktop module it has nothing to do with can
// build at all.
// Read as a Properties file, not line by line: Android Studio writes the path
// escaped (`C\:\Users\...` on Windows), and only Properties undoes that.
val androidSdk: String? = file("local.properties")
    .takeIf { it.isFile }
    ?.let { f -> java.util.Properties().apply { f.inputStream().use(::load) } }
    ?.getProperty("sdk.dir")
    ?.trim()
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")

if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK found — building the desktop targets only.")
}
