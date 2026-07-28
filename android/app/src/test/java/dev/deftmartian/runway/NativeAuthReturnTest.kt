package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeAuthReturnTest {
    @Test
    fun `accepts only the fixed approval and denial callbacks`() {
        assertEquals(
            NativeAuthReturnResult.Approved,
            parseNativeAuthReturn("runway-native://auth?result=approved"),
        )
        assertEquals(
            NativeAuthReturnResult.Denied,
            parseNativeAuthReturn("runway-native://auth?result=denied"),
        )
    }

    @Test
    fun `rejects callback parameter and authority confusion`() {
        listOf(
            null,
            "",
            "https://auth?result=approved",
            "runway-native://evil?result=approved",
            "runway-native://user@auth?result=approved",
            "runway-native://auth:443?result=approved",
            "runway-native://auth/path?result=approved",
            "runway-native://auth?result=approved&code=secret",
            "runway-native://auth?result=approved&result=denied",
            "runway-native://auth?result%3Dapproved",
            "runway-native://auth?result=approved#fragment",
        ).forEach { callback ->
            assertNull(callback, parseNativeAuthReturn(callback))
        }
    }
}
