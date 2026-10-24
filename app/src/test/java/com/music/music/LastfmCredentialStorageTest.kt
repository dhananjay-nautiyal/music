package com.music.music

import com.music.music.data.settings.storedOrLegacy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The precedence that keeps a Last.fm credential encrypted but never lost —
 * see [storedOrLegacy] and issue #19.
 */
class LastfmCredentialStorageTest {

    @Test
    fun `the plaintext copy wins over the stored one`() {
        // While only the plain file was being written it was the newer value,
        // so it is the one that has to survive the move into the encrypted store.
        assertEquals("newer", storedOrLegacy(stored = "older", legacy = "newer", fallback = "build"))
    }

    @Test
    fun `the stored value is used once the plain copy is gone`() {
        assertEquals("stored", storedOrLegacy(stored = "stored", legacy = null, fallback = "build"))
        assertEquals("stored", storedOrLegacy(stored = "stored", legacy = "", fallback = "build"))
    }

    @Test
    fun `a blank plaintext copy is not an override`() {
        assertEquals("stored", storedOrLegacy(stored = "stored", legacy = "   ", fallback = "build"))
    }

    @Test
    fun `the build constant is the last resort`() {
        assertEquals("build", storedOrLegacy(stored = null, legacy = null, fallback = "build"))
        assertEquals("build", storedOrLegacy(stored = "", legacy = " ", fallback = "build"))
    }
}
