package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeSettingsSummaryTest {
    @Test
    fun `current pain remains visible in the standalone settings summary`() {
        assertEquals(
            "Current pain reported",
            healthContextSummary(
                NativeHealthContext(
                    recentInjury = false,
                    currentPain = true,
                    recurringPain = false,
                    clinicianRestriction = false,
                    notes = "private detail",
                ),
            ),
        )
    }

    @Test
    fun `folder status keeps a permission consequence visible`() {
        assertEquals(
            "Permission needed",
            importConnectionSummary(NativeImportConnection.PermissionRequired),
        )
    }

    @Test
    fun `health connect attention retains its actionable local detail`() {
        assertEquals(
            "Grant activity access in Health Connect",
            importConnectionSummary(NativeImportConnection.Attention("Grant activity access in Health Connect")),
        )
    }

    @Test
    fun `route discard is destructive only when changing away from retained routes`() {
        assertEquals(
            true,
            routePrivacyChangeDeletesStoredRoutes(
                NativeRoutePrivacy.KeepPrivate,
                NativeRoutePrivacy.Discard,
            ),
        )
        assertEquals(
            false,
            routePrivacyChangeDeletesStoredRoutes(
                NativeRoutePrivacy.Discard,
                NativeRoutePrivacy.Discard,
            ),
        )
    }
}
