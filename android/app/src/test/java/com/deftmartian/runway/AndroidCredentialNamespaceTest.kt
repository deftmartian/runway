package com.deftmartian.runway

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AndroidCredentialNamespaceTest {
    @Test
    fun `origins have distinct credential slots keys and authenticated context`() {
        val first = "https://runway-a.example"
        val second = "https://runway-b.example"

        assertNotEquals(
            AndroidCredentialNamespace.credentialKey(first),
            AndroidCredentialNamespace.credentialKey(second),
        )
        assertNotEquals(
            AndroidCredentialNamespace.keyAlias(first),
            AndroidCredentialNamespace.keyAlias(second),
        )
        assertNotEquals(
            AndroidCredentialNamespace.associatedData("com.example.runway", first).toList(),
            AndroidCredentialNamespace.associatedData("com.example.runway", second).toList(),
        )
    }

    @Test
    fun `credential namespace is deterministic`() {
        val origin = "https://runway.example"
        assertEquals(
            AndroidCredentialNamespace.credentialKey(origin),
            AndroidCredentialNamespace.credentialKey(origin),
        )
        assertArrayEquals(
            AndroidCredentialNamespace.associatedData("com.example.runway", origin),
            AndroidCredentialNamespace.associatedData("com.example.runway", origin),
        )
    }
}
