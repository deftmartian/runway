package com.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PairingCompletionTest {
    private val credential = AndroidCredential(
        deviceId = "device-1",
        token = "rwy1_test",
        expiresAtEpochMs = Long.MAX_VALUE,
    )

    @Test
    fun `paired credential is saved before connected is returned`() {
        val events = mutableListOf<String>()

        val completion = completePairing(PairingApiResult.Paired(credential)) {
            events += "saved"
            true
        }
        events += "rendered"

        assertEquals(listOf("saved", "rendered"), events)
        assertEquals(PairingCompletion.Connected(credential), completion)
    }

    @Test
    fun `storage failure is not reported as connected`() {
        assertSame(
            PairingCompletion.StorageFailed,
            completePairing(PairingApiResult.Paired(credential)) { false },
        )
    }

    @Test
    fun `invalid and retryable results do not touch credential storage`() {
        var saves = 0
        val save: (AndroidCredential) -> Boolean = {
            saves += 1
            true
        }

        assertSame(PairingCompletion.Invalid, completePairing(PairingApiResult.Invalid, save))
        assertSame(PairingCompletion.Retryable, completePairing(PairingApiResult.Retryable, save))
        assertEquals(0, saves)
    }
}
