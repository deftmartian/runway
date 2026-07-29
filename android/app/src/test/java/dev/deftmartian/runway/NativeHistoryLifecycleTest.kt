package dev.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHistoryLifecycleTest {
    @Test
    fun `foundation plan can complete only after its target date`() {
        val beforeTarget = nativeHistoryLifecycleState(
            activePlan = activePlan(targetDate = "2026-08-01"),
            today = "2026-07-31",
            phaseReview = null,
        )
        val atTarget = nativeHistoryLifecycleState(
            activePlan = activePlan(targetDate = "2026-08-01"),
            today = "2026-08-01",
            phaseReview = null,
        )

        assertFalse(beforeTarget.targetReached)
        assertFalse(beforeTarget.canCompletePlan)
        assertTrue(atTarget.targetReached)
        assertTrue(atTarget.canCompletePlan)
    }

    @Test
    fun `race phase review exposes only server-supported decisions`() {
        val state = nativeHistoryLifecycleState(
            activePlan = activePlan(targetDate = "2026-07-01"),
            today = "2026-08-01",
            phaseReview = phaseReview(
                goalKind = "race",
                options = listOf(
                    "confirm_race_baseline",
                    "continue_calibration",
                    "later_date",
                ),
                racePlan = racePlan(),
            ),
        )

        assertTrue(state.targetReached)
        assertFalse(state.canCompletePlan)
        assertTrue(state.canConfirmBaseline)
        assertTrue(state.canContinuePhase)
        assertTrue(state.canChooseGoal)
    }

    @Test
    fun `closed plan cannot expose lifecycle actions`() {
        val state = nativeHistoryLifecycleState(
            activePlan = activePlan(
                targetDate = "2026-07-01",
                status = "archived",
            ),
            today = "2026-08-01",
            phaseReview = phaseReview(
                goalKind = "race",
                options = listOf(
                    "confirm_race_baseline",
                    "another_foundation_week",
                    "shorter_goal",
                ),
                racePlan = racePlan(),
            ),
        )

        assertFalse(state.targetReached)
        assertFalse(state.canCompletePlan)
        assertFalse(state.canConfirmBaseline)
        assertFalse(state.canContinuePhase)
        assertFalse(state.canChooseGoal)
    }

    private fun activePlan(
        targetDate: String,
        status: String = "active",
    ) = NativePlan(
        id = "plan-1",
        status = status,
        startDate = "2026-06-01",
        targetDate = targetDate,
        weeks = 12,
        risk = "conservative",
        summaryKind = "distance",
        completedAt = null,
        archivedAt = null,
        lifecycleReason = null,
    )

    private fun phaseReview(
        goalKind: String,
        options: List<String>,
        racePlan: NativeRacePlan?,
    ) = NativePhaseReview(
        planId = "plan-1",
        phase = "calibration",
        goalKind = goalKind,
        goalTitle = "Autumn 10K",
        baseline = null,
        recommended = null,
        options = options,
        preferredLongRunDay = 6,
        racePlan = racePlan,
    )

    private fun racePlan() = NativeRacePlan(
        risk = "conservative",
        weeks = 10,
        startDate = "2026-08-03",
        targetDate = "2026-10-11",
        summary = null,
        warnings = emptyList(),
    )
}
