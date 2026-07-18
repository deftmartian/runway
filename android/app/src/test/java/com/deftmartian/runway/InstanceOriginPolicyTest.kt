package com.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceOriginPolicyTest {
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
