package dev.deftmartian.runway.data

import dev.deftmartian.runway.domain.StandalonePlanRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxPlanBoundTest {
    @Test
    fun `ui reachable active plan workout cap fits the inbox projection`() {
        val productScheduleCeiling =
            StandalonePlanRules.MAX_PLAN_WEEKS * StandalonePlanRules.MAX_VISIBLE_WORKOUTS_PER_WEEK

        assertEquals(productScheduleCeiling, MAX_INBOX_PLAN_WORKOUTS)
        assertTrue(LocalWorkoutChangePolicy().maximumWorkoutsPerPlan <= MAX_INBOX_PLAN_WORKOUTS)
    }
}
