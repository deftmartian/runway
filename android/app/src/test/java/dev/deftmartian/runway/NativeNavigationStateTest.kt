package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeNavigationStateTest {
    @Test
    fun `notification actions open only their bounded product destination`() {
        assertEquals(
            NativeDestination.Calendar,
            notificationDestination(MainActivity.ACTION_OPEN_CALENDAR),
        )
        assertEquals(
            NativeDestination.Inbox,
            notificationDestination(MainActivity.ACTION_OPEN_INBOX),
        )
        assertEquals(null, notificationDestination("dev.deftmartian.runway.UNKNOWN"))
        assertEquals(null, notificationDestination(null))
    }

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
                currentGoal = null,
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
                currentGoal = NativeGoalSummary(
                    title = "10 km",
                    targetDate = "2026-10-01",
                    state = "active",
                    risk = null,
                ),
            ),
        )

        assertEquals(NativeDestination.History, surface.navigationParent())
    }

    @Test
    fun `settings origin overrides the generic active goal parent`() {
        val surface = NativeSurface.Setup(
            NativeOnboardingPayload(
                initialValues = null,
                minimumTargetDate = null,
                minimumCalibrationTargetDate = null,
                minimumFoundationTargetDate = null,
                maximumTargetDate = null,
                currentGoal = NativeGoalSummary(
                    title = "10 km",
                    targetDate = "2026-10-01",
                    state = "active",
                    risk = null,
                ),
            ),
        )

        assertEquals(
            NativeDestination.Settings,
            surface.navigationParent(NativeDestination.Settings),
        )
    }

    @Test
    fun `pending goal setup stays in the required setup flow`() {
        val surface = NativeSurface.Setup(
            NativeOnboardingPayload(
                initialValues = null,
                minimumTargetDate = null,
                minimumCalibrationTargetDate = null,
                minimumFoundationTargetDate = null,
                maximumTargetDate = null,
                currentGoal = NativeGoalSummary(
                    title = "5 km later",
                    targetDate = "2026-10-01",
                    state = "pending",
                    risk = null,
                ),
            ),
        )

        assertEquals(null, surface.navigationParent())
    }
}
