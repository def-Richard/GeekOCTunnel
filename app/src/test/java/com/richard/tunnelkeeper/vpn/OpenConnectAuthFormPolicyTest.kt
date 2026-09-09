package com.richard.tunnelkeeper.vpn

import com.richard.tunnelkeeper.model.SslGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenConnectAuthFormPolicyTest {
    @Test
    fun `saved credentials rejection without groups restarts the session`() {
        val decision = OpenConnectAuthFormPolicy.afterCredentialsSubmitted(
            reason = "Login failed",
            sslGroups = emptyList(),
            credentialsCameFromSavedStore = true,
        )

        assertTrue(decision.restartSessionToRefreshGroups)
        assertTrue(decision.savedCredentialsRejected)
    }

    @Test
    fun `manually entered credentials rejection without groups also restarts the session`() {
        val decision = OpenConnectAuthFormPolicy.afterCredentialsSubmitted(
            reason = "Invalid password",
            sslGroups = emptyList(),
            credentialsCameFromSavedStore = false,
        )

        assertTrue(decision.restartSessionToRefreshGroups)
        assertFalse(decision.savedCredentialsRejected)
    }

    @Test
    fun `rejection with groups remains in the current session`() {
        val groups = sslGroups()
        val decision = OpenConnectAuthFormPolicy.afterCredentialsSubmitted(
            reason = "Authentication rejected",
            sslGroups = groups,
            credentialsCameFromSavedStore = true,
        )

        assertFalse(decision.restartSessionToRefreshGroups)
        assertTrue(decision.savedCredentialsRejected)
    }

    @Test
    fun `non rejection form without groups does not force a restart`() {
        val decision = OpenConnectAuthFormPolicy.afterCredentialsSubmitted(
            reason = "Please complete authentication",
            sslGroups = emptyList(),
            credentialsCameFromSavedStore = false,
        )

        assertFalse(decision.restartSessionToRefreshGroups)
        assertFalse(decision.savedCredentialsRejected)
    }

    @Test
    fun `last attempted group becomes the prompt default when still available`() {
        val preferred = OpenConnectAuthFormPolicy.preferSslGroup(
            groups = sslGroups(),
            preferredValue = "operations",
        )

        assertEquals(listOf(false, true), preferred.map(SslGroup::isDefault))
    }

    @Test
    fun `missing last attempted group keeps the server default`() {
        val groups = sslGroups()

        assertEquals(
            groups,
            OpenConnectAuthFormPolicy.preferSslGroup(groups, preferredValue = "retired"),
        )
    }

    @Test
    fun `first group selection writes its value and refreshes even when it is the server default`() {
        val selection = OpenConnectAuthGroupPolicy.select(
            choiceValues = listOf("research", "operations"),
            requestedValue = "research",
            appliedValue = null,
        )

        assertEquals(
            AuthGroupFormSelection(index = 0, value = "research", refreshForm = true),
            selection,
        )
    }

    @Test
    fun `refreshed form still receives the selected group value without another refresh`() {
        val selection = OpenConnectAuthGroupPolicy.select(
            choiceValues = listOf("research", "operations"),
            requestedValue = "operations",
            appliedValue = "operations",
        )

        assertEquals(
            AuthGroupFormSelection(index = 1, value = "operations", refreshForm = false),
            selection,
        )
    }

    @Test
    fun `changing group in the same session refreshes the form again`() {
        val selection = OpenConnectAuthGroupPolicy.select(
            choiceValues = listOf("research", "operations"),
            requestedValue = "research",
            appliedValue = "operations",
        )

        assertTrue(requireNotNull(selection).refreshForm)
    }

    @Test
    fun `blank or unavailable group cannot be written to native select option`() {
        val choices = listOf("research", "operations")

        assertNull(OpenConnectAuthGroupPolicy.select(choices, requestedValue = "", appliedValue = null))
        assertNull(OpenConnectAuthGroupPolicy.select(choices, requestedValue = "retired", appliedValue = null))
    }

    private fun sslGroups() = listOf(
        SslGroup(value = "research", label = "Research", isDefault = true),
        SslGroup(value = "operations", label = "Operations"),
    )
}
