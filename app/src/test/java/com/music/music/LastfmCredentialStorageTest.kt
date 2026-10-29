package com.music.music

import com.music.music.data.settings.credentialToMove
import com.music.music.data.settings.storedOrLegacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The precedence that keeps a credential encrypted but never lost — see
 * [storedOrLegacy], [credentialToMove] and issues #19 and #29.
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

    // --- what the one-time move out of the plain file carries (#29) -------------

    @Test
    fun `an empty plaintext entry is not a credential worth moving`() {
        // An older version wrote "" here when the listener unlinked the service, so treating a
        // blank entry as a value would commit nothing over the token already stored — the app
        // would go on scrobbling as far as its settings row says, with nothing to send with.
        assertNull(credentialToMove(stored = "session-key", legacy = "", fallback = ""))
        assertNull(credentialToMove(stored = "session-key", legacy = "   ", fallback = ""))
    }

    @Test
    fun `a plain file that never held one has nothing to move`() {
        assertNull(credentialToMove(stored = "session-key", legacy = null, fallback = ""))
        assertNull(credentialToMove(stored = null, legacy = null, fallback = ""))
    }

    @Test
    fun `a plaintext credential moves as the value it states`() {
        // Neither the session key nor the ListenBrainz token has a build-time fallback, unlike
        // the API pair: an empty store is what "not linked" means, so the blank that comes back
        // from here has to be the plaintext copy's own value rather than anything invented.
        assertEquals("session-key", credentialToMove(stored = null, legacy = "session-key", fallback = ""))
        assertEquals("newer", credentialToMove(stored = "older", legacy = "newer", fallback = "build"))
    }
}
