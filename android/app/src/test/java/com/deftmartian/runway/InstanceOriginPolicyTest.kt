package com.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceOriginPolicyTest {
    @Test
    fun `normalizes a server address to an exact https origin`() {
        assertEquals(
            "https://runway.example",
            InstanceOriginPolicy.normalizeOrigin(" runway.example/ ", false),
        )
        assertEquals(
            "https://runway.example",
            InstanceOriginPolicy.normalizeOrigin("https://RUNWAY.example:443", false),
        )
        assertEquals(
            "https://runway.example:8443",
            InstanceOriginPolicy.normalizeOrigin("https://runway.example:8443/", false),
        )
    }

    @Test
    fun `rejects ambiguous and credential-bearing server addresses`() {
        listOf(
            "https://runway.example/app",
            "https://runway.example?next=/app",
            "https://user@runway.example",
            "ftp://runway.example",
            "runway example",
        ).forEach { assertNull(InstanceOriginPolicy.normalizeOrigin(it, false)) }
    }

    @Test
    fun `permits private cleartext only when a debug caller opts in`() {
        assertNull(InstanceOriginPolicy.normalizeOrigin("http://192.168.1.20:4100", false))
        assertEquals(
            "http://192.168.1.20:4100",
            InstanceOriginPolicy.normalizeOrigin("http://192.168.1.20:4100", true),
        )
        assertNull(InstanceOriginPolicy.normalizeOrigin("http://public.example", true))
        assertNull(InstanceOriginPolicy.normalizeOrigin("http://fcastle.example", true))
        assertEquals(
            "http://[febf::1]:4100",
            InstanceOriginPolicy.normalizeOrigin("http://[febf::1]:4100", true),
        )
        assertNull(InstanceOriginPolicy.normalizeOrigin("http://[fec0::1]:4100", true))
    }

    @Test
    fun `accepts any path on the configured origin`() {
        assertTrue(
            InstanceOriginPolicy.belongsTo(
                "https://runway.example/app/import?month=2026-07",
                "https://runway.example",
            ),
        )
    }

    @Test
    fun `normalizes the default https port`() {
        assertTrue(
            InstanceOriginPolicy.belongsTo(
                "https://runway.example:443/app",
                "https://runway.example",
            ),
        )
    }

    @Test
    fun `rejects another scheme host port or user info`() {
        val candidates = listOf(
            "http://runway.example/app",
            "https://other.example/app",
            "https://runway.example:8443/app",
            "https://user@runway.example/app",
        )

        candidates.forEach {
            assertFalse(InstanceOriginPolicy.belongsTo(it, "https://runway.example"))
        }
    }
}
