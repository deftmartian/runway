package dev.deftmartian.runway.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandalonePlanRulesTest {
    private val today = LocalDate.parse("2026-07-30")
    private val window = PlanDateWindow(LocalDate.parse("2026-07-27"), LocalDate.parse("2026-08-30"))

    @Test fun `plan edits are rejected before preview for every protected boundary`() {
        val blocked = StandalonePlanRules.assessPlanChange(
            PlanChangeEligibilityInput(
                activePlan = false,
                planWindow = window,
                today = today,
                proposedDate = today,
                subjectType = WorkoutType.RACE,
                subjectHasLinkedResult = true,
                scheduledSlots = emptyList(),
            ),
        )
        assertFalse(blocked.allowed)
        assertEquals(
            setOf(
                PlanChangeIneligibility.NO_ACTIVE_PLAN,
                PlanChangeIneligibility.NOT_FUTURE,
                PlanChangeIneligibility.RACE_WORKOUT,
                PlanChangeIneligibility.LINKED_RESULT,
            ),
            blocked.reasons,
        )
    }

    @Test fun `plan change caps count every visible workout, exclude removed rows and exclude the edit subject`() {
        val date = LocalDate.parse("2026-08-03")
        val twoSameDay = listOf(slot("first", date), slot("rest", date, WorkoutType.REST), slot("removed", date, removed = true))
        val add = change(date, slots = twoSameDay)
        assertTrue(PlanChangeIneligibility.DAILY_LIMIT in StandalonePlanRules.assessPlanChange(add).reasons)

        val fourteen = (1..14).map { slot("week-$it", date.plusDays((it % 7).toLong())) }
        val weekly = StandalonePlanRules.assessPlanChange(change(date, slots = fourteen))
        assertTrue(PlanChangeIneligibility.WEEKLY_LIMIT in weekly.reasons)

        val editOwnSlot = StandalonePlanRules.assessPlanChange(change(date, subjectId = "first", slots = twoSameDay))
        assertTrue(editOwnSlot.allowed)
    }

    @Test fun `plan change rejects dates outside the actual generated plan window`() {
        val before = StandalonePlanRules.assessPlanChange(change(LocalDate.parse("2026-07-26")))
        val after = StandalonePlanRules.assessPlanChange(change(LocalDate.parse("2026-08-31")))
        assertTrue(PlanChangeIneligibility.OUTSIDE_PLAN_WINDOW in before.reasons)
        assertTrue(PlanChangeIneligibility.OUTSIDE_PLAN_WINDOW in after.reasons)
    }

    @Test fun `feedback is one-time and only for a due planned non-rest workout`() {
        val available = StandalonePlanRules.assessFeedback(FeedbackEligibilityInput(today, today, WorkoutType.EASY, FeedbackWorkoutStatus.PLANNED))
        assertTrue(available.allowed)
        val blocked = StandalonePlanRules.assessFeedback(FeedbackEligibilityInput(today, today.plusDays(1), WorkoutType.REST, FeedbackWorkoutStatus.DONE, hasFeedback = true))
        assertEquals(
            setOf(FeedbackIneligibility.NOT_DUE, FeedbackIneligibility.NOT_PLANNED_RUN, FeedbackIneligibility.ALREADY_RECORDED),
            blocked.reasons,
        )
    }

    @Test fun `only a completed active beginner phase with a final week can continue and never beyond 52 weeks`() {
        val ready = StandalonePlanRules.assessContinuation(ContinuationEligibilityInput(true, PlanPhase.FOUNDATION, today, today, 51, true))
        assertTrue(ready.allowed)
        val atLimit = StandalonePlanRules.assessContinuation(ContinuationEligibilityInput(true, PlanPhase.CALIBRATION, today, today, 52, true))
        assertTrue(ContinuationIneligibility.PLAN_WEEK_LIMIT in atLimit.reasons)
        val distance = StandalonePlanRules.assessContinuation(ContinuationEligibilityInput(true, PlanPhase.DISTANCE, today, today, 20, true))
        assertTrue(ContinuationIneligibility.NOT_A_BEGINNER_PHASE in distance.reasons)
    }

    @Test fun `accepted evidence uses paired weighted pace and duration weighted heart rate while retaining provenance`() {
        val result = AcceptedEvidenceStatistics.summarize(
            listOf(
                evidence("active", true, AcceptedEvidenceProvenance.ACTIVE_PLAN, 5_000, 1_500, 150),
                evidence("archived", true, AcceptedEvidenceProvenance.ARCHIVED_PLAN, 10_000, 3_600, 180),
                evidence("unlinked", true, AcceptedEvidenceProvenance.UNLINKED, 1_000, null, 200),
                evidence("review", false, AcceptedEvidenceProvenance.UNLINKED, 99_000, 99_000, 99),
            ),
        )
        assertEquals(3, result.acceptedActivityCount)
        assertEquals(16_000, result.distanceMeters)
        assertEquals(5_100, result.durationSeconds)
        assertEquals(340.0, requireNotNull(result.averagePaceSecondsPerKm), 0.0)
        assertEquals(171, result.averageHeartRate)
        assertEquals(1, result.byProvenance.getValue(AcceptedEvidenceProvenance.ACTIVE_PLAN).acceptedActivityCount)
        assertEquals(1_000, result.byProvenance.getValue(AcceptedEvidenceProvenance.UNLINKED).distanceMeters)
    }

    @Test fun `evidence without valid pairs does not invent pace or heart rate and duplicate ids are rejected`() {
        val emptyMetrics = AcceptedEvidenceStatistics.summarize(listOf(evidence("only", true, AcceptedEvidenceProvenance.UNLINKED, 500, null, null)))
        assertNull(emptyMetrics.averagePaceSecondsPerKm)
        assertNull(emptyMetrics.averageHeartRate)
        try {
            AcceptedEvidenceStatistics.summarize(listOf(evidence("same", true, AcceptedEvidenceProvenance.UNLINKED, 1, 1, null), evidence("same", true, AcceptedEvidenceProvenance.UNLINKED, 1, 1, null)))
            throw AssertionError("Expected duplicate evidence IDs to be rejected.")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun change(date: LocalDate, subjectId: String? = null, slots: List<ScheduledWorkoutSlot> = emptyList()) =
        PlanChangeEligibilityInput(true, window, today, date, subjectId = subjectId, scheduledSlots = slots)

    private fun slot(id: String, date: LocalDate, type: WorkoutType = WorkoutType.EASY, removed: Boolean = false) =
        ScheduledWorkoutSlot(id, date, type, removed)

    private fun evidence(id: String, accepted: Boolean, provenance: AcceptedEvidenceProvenance, distance: Int?, duration: Int?, heartRate: Int?) =
        AcceptedActivityEvidence(id, accepted, provenance, distance, duration, heartRate)
}
