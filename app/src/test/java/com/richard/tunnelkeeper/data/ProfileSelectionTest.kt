package com.richard.tunnelkeeper.data

import com.richard.tunnelkeeper.model.ConnectionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSelectionTest {
    private val profiles = listOf(
        ConnectionProfile(id = "first", name = "First", server = "first.example.com"),
        ConnectionProfile(id = "second", name = "Second", server = "second.example.com"),
    )

    @Test
    fun `restores a persisted profile instead of selecting the first profile`() {
        assertEquals("second", ProfileSelection.resolve(profiles, "second"))
    }

    @Test
    fun `finds a pending profile by its saved id`() {
        assertEquals(profiles[1], ProfileSelection.findById(profiles, "second"))
        assertNull(ProfileSelection.findById(profiles, "removed"))
        assertNull(ProfileSelection.findById(profiles, null))
    }

    @Test
    fun `falls back to the first profile when persisted profile no longer exists`() {
        assertEquals("first", ProfileSelection.resolve(profiles, "removed"))
    }

    @Test
    fun `returns null when there are no profiles`() {
        assertNull(ProfileSelection.resolve(emptyList(), "removed"))
    }
}
