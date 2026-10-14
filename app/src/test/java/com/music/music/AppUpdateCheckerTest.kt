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
        assertEquals(null, AppUpdateChecker.archOfApk("Music-v1.0.2.apk"))
        assertEquals(null, AppUpdateChecker.archOfApk("notes.txt"))
    }
}
