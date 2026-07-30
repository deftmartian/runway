package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeNavigationStateTest {
    @Test
    fun `restoration keeps a detail only when its local plan identity is present`() {
        assertEquals(
            NativeDestination.HistoryDetail,
            restoredNativeDestination(NativeDestination.HistoryDetail.name, "plan-1"),
        )
        assertEquals(
            NativeDestination.History,
            restoredNativeDestination(NativeDestination.HistoryDetail.name, null),
        )
    }

    @Test
    fun `unknown or absent destinations restore to calendar`() {
        assertEquals(NativeDestination.Calendar, restoredNativeDestination("Today", null))
        assertEquals(NativeDestination.Calendar, restoredNativeDestination(null, null))
    }

    @Test
    fun `fresh setup has no navigation parent`() {
        val surface = NativeSurface.Setup(
            NativeOnboardingPayload(
                initialValues = null,
                minimumTargetDate = null,
                minimumCalibrationTargetDate = null,
                minimumFoundationTargetDate = null,
                maximumTargetDate = null,
                activeGoal = null,
            ),
        )

        assertEquals(null, surface.navigationParent())
    }

    @Test
    fun `replacing an active goal returns setup to history`() {
        val surface = NativeSurface.Setup(
            NativeOnboardingPayload(
                initialValues = null,
                minimumTargetDate = null,
                minimumCalibrationTargetDate = null,
                minimumFoundationTargetDate = null,
                maximumTargetDate = null,
                activeGoal = NativeGoalSummary(
                    title = "10 km",
                    targetDate = "2026-10-01",
                    state = "active",
                    risk = null,
                ),
            ),
        )

        assertEquals(NativeDestination.History, surface.navigationParent())
    }
}
