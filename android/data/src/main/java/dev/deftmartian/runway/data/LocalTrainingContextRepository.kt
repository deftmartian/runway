package dev.deftmartian.runway.data

/**
 * Small typed read boundary for app orchestration that does not belong to a rendered surface.
 *
 * The Android app must not reach through [RunwayLedgerDatabase] to Room DAOs. Mutation
 * repositories still re-read and validate all authoritative state before committing.
 */
class LocalTrainingContextRepository(
    private val database: RunwayLedgerDatabase,
) {
    suspend fun activePlan(): PlanEntity? =
        database.goalPlanDao().activePlans(limit = 2).singleOrNull()

    suspend fun workout(workoutId: String): WorkoutEntity? {
        require(workoutId.isNotBlank())
        return database.goalPlanDao().workout(workoutId)
    }

    suspend fun weeks(planId: String): List<PlanWeekEntity> {
        require(planId.isNotBlank())
        return database.goalPlanDao().weeksForPlan(planId, limit = MAX_PLAN_WEEKS)
    }

    suspend fun profile(): ProfileSettingsEntity? =
        database.profileSettingsDao().get()

    private companion object {
        const val MAX_PLAN_WEEKS = 128
    }
}
