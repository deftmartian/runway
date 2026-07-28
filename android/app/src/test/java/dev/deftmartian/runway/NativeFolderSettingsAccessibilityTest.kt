package dev.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFolderSettingsAccessibilityTest {
    @Test
    fun `compose setup state keeps the health connect action unavailable until permission is known`() {
        val pending = NativeImportSetupUiState()
        val ready = pending.copy(showHealthSync = true, healthSyncActionEnabled = true)

        assertFalse(pending.healthSyncActionEnabled)
        assertTrue(ready.showHealthSync)
        assertTrue(ready.healthSyncActionEnabled)
    }
}
