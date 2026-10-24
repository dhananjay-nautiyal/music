package com.music.music

import com.music.music.data.AppUpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateCheckerTest {

    private val release = listOf(
        "Music-v1.0.2.apk",
        "Music-v1.0.2_arm64-v8a.apk",
        "Music-v1.0.2_armeabi-v7a.apk",
        "Music-v1.0.2_x86_64.apk",
    )

    /**
     * Exactly what the Releases page carried before the pipeline renamed
     * assets (v1.1.2) — the names that made the updater pick wrongly on
     * every non-arm64 device, and the names still live on old releases.
     */
    private val agpRelease = listOf(
        "app-prod-arm64-v8a-release.apk",
        "app-prod-armeabi-v7a-release.apk",
        "app-prod-universal-release.apk",
        "app-prod-x86_64-release.apk",
    )

    @Test
    fun pickApk_readsTheRawAgpNamesRealReleasesCarried() {
        // arm64-v8a is listed first on v1.1.2, which is what every other
        // device used to download: INSTALL_FAILED_NO_MATCHING_ABIS.
        assertEquals(
            "app-prod-armeabi-v7a-release.apk",
            AppUpdateChecker.pickApkAsset(agpRelease, listOf("armeabi-v7a", "arm64-v8a")),
        )
        assertEquals(
            "app-prod-x86_64-release.apk",
            AppUpdateChecker.pickApkAsset(agpRelease, listOf("x86_64")),
        )
        assertEquals(
            "app-prod-arm64-v8a-release.apk",
            AppUpdateChecker.pickApkAsset(agpRelease, listOf("arm64-v8a", "armeabi-v7a")),
        )
        assertEquals(
            "app-prod-universal-release.apk",
            AppUpdateChecker.pickApkAsset(agpRelease, listOf("mips")),
        )
    }

    @Test
    fun archOfApk_readsAgpSplitNames() {
        assertEquals("arm64-v8a", AppUpdateChecker.archOfApk("app-prod-arm64-v8a-release.apk"))
        assertEquals("armeabi-v7a", AppUpdateChecker.archOfApk("app-prod-armeabi-v7a-release.apk"))
        assertEquals("x86_64", AppUpdateChecker.archOfApk("app-prod-x86_64-release.apk"))
        assertEquals(null, AppUpdateChecker.archOfApk("app-prod-universal-release.apk"))
    }

    @Test
    fun pickApk_prefersFirstSupportedAbi() {
        assertEquals(
            "Music-v1.0.2_arm64-v8a.apk",
            AppUpdateChecker.pickApkAsset(release, listOf("arm64-v8a", "armeabi-v7a")),
        )
        assertEquals(
            "Music-v1.0.2_x86_64.apk",
            AppUpdateChecker.pickApkAsset(release, listOf("x86_64", "x86")),
        )
    }

    @Test
    fun pickApk_fallsBackToUniversal() {
        assertEquals(
            "Music-v1.0.2.apk",
            AppUpdateChecker.pickApkAsset(release, listOf("mips", "mips64")),
        )
    }

    @Test
    fun pickApk_fallsBackToFirstWhenNoUniversal() {
        val splits = listOf("Music-v1.0.2_armeabi-v7a.apk", "Music-v1.0.2_x86_64.apk")
        assertEquals(
            "Music-v1.0.2_armeabi-v7a.apk",
            AppUpdateChecker.pickApkAsset(splits, listOf("mips")),
        )
    }

    @Test
    fun pickApk_emptyIsNull() {
        assertNull(AppUpdateChecker.pickApkAsset(emptyList(), listOf("arm64-v8a")))
    }

    @Test
    fun archOfApk_names() {
        assertEquals("arm64-v8a", AppUpdateChecker.archOfApk("Music-v1.0.2_arm64-v8a.apk"))
        assertEquals("x86_64", AppUpdateChecker.archOfApk("Music-v1.0.2_x86_64.apk"))
        assertEquals(null, AppUpdateChecker.archOfApk("Music-v1.0.2.apk"))
        assertEquals(null, AppUpdateChecker.archOfApk("Music-v1.0.2_beta2.apk"))
        assertEquals(null, AppUpdateChecker.archOfApk("notes.txt"))
    }
}
