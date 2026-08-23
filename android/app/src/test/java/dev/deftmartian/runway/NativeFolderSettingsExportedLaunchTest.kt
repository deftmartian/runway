package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NativeFolderSettingsActivity is exported solely for the androidx.health
 * ACTION_SHOW_PERMISSIONS_RATIONALE intent-filter. These tests pin that a cold launch from an
 * arbitrary app can only choose the initially shown settings section: the mapping reads only the
 * intent action, so extras can never influence state, storage, or imports.
 */
class NativeFolderSettingsExportedLaunchTest {
    @Test
    fun `health connect rationale action cannot reach a specific section`() {
        // The exported intent-filter action is not privileged here: it lands on Overview like any
        // other unrecognized action, so an external app sees only the general settings screen.
        assertEquals(
            NativeImportSection.Overview,
            nativeImportSectionForAction("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"),
        )
    }

    @Test
    fun `runway actions open their own sections`() {
        assertEquals(
            NativeImportSection.HealthConnect,
            nativeImportSectionForAction(MainActivity.ACTION_OPEN_HEALTH_CONNECT_SETTINGS),
        )
        assertEquals(
            NativeImportSection.Folder,
            nativeImportSectionForAction(MainActivity.ACTION_OPEN_FOLDER_SETTINGS),
        )
    }

    @Test
    fun `arbitrary or absent actions fall back to the overview section`() {
        assertEquals(NativeImportSection.Overview, nativeImportSectionForAction(null))
        assertEquals(NativeImportSection.Overview, nativeImportSectionForAction(""))
        assertEquals(
            NativeImportSection.Overview,
            nativeImportSectionForAction("android.intent.action.VIEW"),
        )
        assertEquals(
            NativeImportSection.Overview,
            nativeImportSectionForAction("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE ".trim() + "x"),
        )
    }
}
