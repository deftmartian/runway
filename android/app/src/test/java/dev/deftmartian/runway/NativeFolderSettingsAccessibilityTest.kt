package dev.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFolderSettingsAccessibilityTest {
    @Test
    fun `compose setup state distinguishes unavailable permission and ready health connect`() {
        val pending = NativeImportSettingsUiState()
        val ready = pending.copy(
            healthAvailable = true,
            healthPermissionsGranted = true,
        )

        assertFalse(pending.healthAvailable)
        assertFalse(pending.healthPermissionsGranted)
        assertTrue(ready.healthAvailable)
        assertTrue(ready.healthPermissionsGranted)
    }
}
