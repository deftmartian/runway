package com.deftmartian.runway

import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFolderSettingsAccessibilityTest {
    @Test
    fun `health connect section is available through heading navigation`() {
        assertTrue(NATIVE_IMPORTS_ACCESSIBILITY_HEADING_IDS.contains(R.id.health_connect_heading))
    }
}
