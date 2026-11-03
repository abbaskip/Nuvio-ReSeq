package com.nuvio.tv.core.tmdb

import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbCredentialStoreTest {
    @Test
    fun `encrypted credential adapter isolates profiles and removes credentials with profile lifecycle`() {
        val values = mutableMapOf<Int, String?>()
        val protected = mockk<ProtectedProfilePreferences> {
            every { read(any()) } answers { values[firstArg()] }
            every { write(any(), any()) } answers { values[firstArg()] = secondArg(); Unit }
            every { clear() } answers { values.clear() }
        }
        val store = TmdbCredentialStore(protected)
        store.save(1, " first-local-key ")
        val revision = store.access(1).revision
        store.save(2, "second-local-key")
        assertEquals("first-local-key", store.access(1).apiKey)
        assertEquals("second-local-key", store.access(2).apiKey)
        assertEquals(revision, store.revision(1))
        store.removeProfile(1)
        assertTrue(store.access(1).apiKey.isBlank())
        assertTrue(store.revision(1) > revision)
        assertEquals("second-local-key", store.access(2).apiKey)
        store.clearAllProfiles()
        assertTrue(store.access(2).apiKey.isBlank())
        assertTrue(store.changes.value > revision)
    }

    @Test
    fun `credential diagnostics expose only configuration status`() {
        val access = TmdbCredentialAccess(1, 2, "synthetic-secret")
        assertFalse(access.toString().contains("synthetic-secret"))
        assertTrue(access.toString().contains("configured=true"))
    }
}
