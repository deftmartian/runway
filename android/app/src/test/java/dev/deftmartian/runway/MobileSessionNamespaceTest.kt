package dev.deftmartian.runway

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileSessionNamespaceTest {
    @Test
    fun `separates session storage by origin and purpose`() {
        val first = "https://runway.example"
        val second = "https://other.example"
        assertNotEquals(
            MobileSessionNamespace.sessionKey(first),
            MobileSessionNamespace.sessionKey(second),
        )
        assertNotEquals(
            MobileSessionNamespace.sessionKey(first),
            MobileSessionNamespace.pendingKey(first),
        )
        assertNotEquals(
            MobileSessionNamespace.keyAlias(first),
            MobileSessionNamespace.keyAlias(second),
        )
        assertArrayEquals(
            "dev.deftmartian.runway\u0000$first\u0000session".toByteArray(),
            MobileSessionNamespace.associatedData("dev.deftmartian.runway", first, "session"),
        )
        assertTrue(MobileSessionNamespace.sessionKey(first).startsWith("mobile_session_v1_"))
    }
}
