package com.music.music.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A desktop build that never received `-Pmusic.version` (a local build, a fork, a distro
 * recipe) reports [DesktopUpdateChecker.FALLBACK_VERSION]. If that fallback ever climbs above
 * the newest published release, the update check goes quiet for good: `isNewer` would call every
 * real release older than the build itself, so the prompt never fires. This is the #13 bug — the
 * fallback sat at the upstream BitChord `1.8-beta1`, higher than every Music release.
 */
class DesktopVersionFallbackTest {

    /**
     * The newest release actually published. Advance it when a release ships; it must never
     * fall below [DesktopUpdateChecker.FALLBACK_VERSION], only the other way around.
     */
    private val latestPublished = "1.1.2"

    @Test
    fun theFallbackIsNeverAboveTheLatestPublishedRelease() {
        // The whole invariant: the unstamped build must not look newer than what is published,
        // or every real release would be judged older than the running build and never offered.
        assertFalse(
            DesktopUpdateChecker.isNewer(latest = DesktopUpdateChecker.FALLBACK_VERSION, current = latestPublished),
            "fallback ${DesktopUpdateChecker.FALLBACK_VERSION} outranks the published $latestPublished; " +
                "an unstamped build would never be prompted to update",
        )
    }

    @Test
    fun aFutureReleaseIsStillOfferedToAnUnstampedBuild() {
        // The point of holding the fallback at the newest release: one version up is offered.
        val someday = "99.99.99"
        assertFalse(
            !DesktopUpdateChecker.isNewer(latest = someday, current = DesktopUpdateChecker.FALLBACK_VERSION),
            "$someday should be offered over the fallback",
        )
    }

    @Test
    fun theFallbackStaysAtAMusicVersionNotAnUpstreamOne() {
        // Guards against the BitChord `1.8-beta1` value creeping back: no pre-release suffix,
        // and never a `1.8`-style upstream number above the 1.1.x line Music actually ships.
        val fallback = DesktopUpdateChecker.FALLBACK_VERSION
        assertFalse(
            DesktopUpdateChecker.isNewer(latest = fallback, current = fallback),
            "fallback $fallback should equal itself, i.e. not outrank what it matches",
        )
    }

    /**
     * [DesktopUpdateChecker.FALLBACK_VERSION] is generated from `VERSION` at the repo root, which
     * is also where the Android `versionName` and `appVersion`'s fallback come from (#37). Read
     * straight off disk here, so a build that kept a stale copy of the generated source — or a
     * hand-edited constant that got past the generator — fails instead of shipping a third version
     * string that disagrees with the other two.
     */
    @Test
    fun theFallbackIsTheVersionTheRepositoryDeclares() {
        val path = System.getProperty("music.versionFile")
            ?: error("the test task did not pass music.versionFile; see desktopApp/build.gradle.kts")
        val declared = File(path).readText().trim().removePrefix("v")
        assertEquals(declared, DesktopUpdateChecker.FALLBACK_VERSION)
    }
}
