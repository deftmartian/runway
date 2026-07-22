package com.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportConnectionGenerationTest {
    private val snapshot = ImportConnectionGeneration(
        origin = "https://runway.example",
        serverGeneration = 7,
        deviceId = "device-1",
        token = "rwy1_token",
        credentialGeneration = 3,
        treeUri = "content://provider/tree",
        treeGeneration = 4,
    )

    @Test
    fun `same credential tree and generation remain current`() {
        assertTrue(
            snapshot.matches(
                "https://runway.example",
                7,
                "https://runway.example",
                "device-1",
                "rwy1_token",
                3,
                "content://provider/tree",
                4,
            ),
        )
    }

    @Test
    fun `credential folder or generation change invalidates work`() {
        assertFalse(snapshot.matches(null, null, null, null, null, null, "content://provider/tree", 4))
        assertFalse(
            snapshot.matches(
                "https://other.example",
                7,
                "https://other.example",
                "device-1",
                "rwy1_token",
                3,
                "content://provider/tree",
                5,
            ),
        )
        assertFalse(
            snapshot.matches(
                "https://runway.example",
                8,
                "https://runway.example",
                "device-1",
                "rwy1_token",
                3,
                "content://provider/tree",
                4,
            ),
        )
        assertFalse(
            snapshot.matches(
                "https://runway.example",
                7,
                "https://other.example",
                "device-1",
                "rwy1_token",
                3,
                "content://provider/tree",
                4,
            ),
        )
        assertFalse(
            snapshot.matches(
                "https://runway.example",
                7,
                "https://runway.example",
                "device-1",
                "rwy1_replaced",
                3,
                "content://provider/tree",
                4,
            ),
        )
        assertFalse(
            snapshot.matches(
                "https://runway.example",
                7,
                "https://runway.example",
                "device-1",
                "rwy1_token",
                3,
                "content://provider/tree",
                5,
            ),
        )
        assertFalse(
            snapshot.matches(
                "https://runway.example",
                7,
                "https://runway.example",
                "device-1",
                "rwy1_token",
                4,
                "content://provider/tree",
                4,
            ),
        )
        assertFalse(
            snapshot.matches(
                "https://runway.example",
                7,
                "https://runway.example",
                "device-1",
                "rwy1_token",
                3,
                "content://provider/other",
                4,
            ),
        )
    }
}
