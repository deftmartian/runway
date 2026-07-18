package com.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportConnectionGenerationTest {
    private val snapshot = ImportConnectionGeneration(
        deviceId = "device-1",
        token = "rwy1_token",
        treeUri = "content://provider/tree",
        treeGeneration = 4,
    )

    @Test
    fun `same credential tree and generation remain current`() {
        assertTrue(snapshot.matches("device-1", "rwy1_token", "content://provider/tree", 4))
    }

    @Test
    fun `credential folder or generation change invalidates work`() {
        assertFalse(snapshot.matches(null, null, "content://provider/tree", 4))
        assertFalse(snapshot.matches("device-1", "rwy1_replaced", "content://provider/tree", 4))
        assertFalse(snapshot.matches("device-1", "rwy1_token", "content://provider/tree", 5))
        assertFalse(snapshot.matches("device-1", "rwy1_token", "content://provider/other", 4))
    }
}
