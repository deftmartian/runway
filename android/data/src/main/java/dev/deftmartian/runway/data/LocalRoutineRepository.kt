package dev.deftmartian.runway.data

import androidx.room.withTransaction
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Maintains a small persisted future horizon for an ongoing routine.  It is deliberately invoked
 * at read/action boundaries rather than relying on background execution to make future runs exist.
 */
class LocalRoutineRepository(
    private val database: RunwayLedgerDatabase,
) {
    suspend fun ensureHorizon(
        planId: String,
        throughEpochDay: Long,
        updatedAtEpochMillis: Long,
    ): LocalRoutineHorizonResult = database.withTransaction {
        if (planId.isBlank() || updatedAtEpochMillis < 0) {
            return@withTransaction LocalRoutineHorizonResult.Rejected
        }
        val plans = database.goalPlanDao()
        val plan = plans.plan(planId) ?: return@withTransaction LocalRoutineHorizonResult.Rejected
        if (plan.state != "active" || plan.phaseType != "routine" || plan.endEpochDay != null) {
            return@withTransaction LocalRoutineHorizonResult.Rejected
        }
        val days = plans.routineScheduleDays(planId).map(RoutineScheduleDayEntity::dayOfWeek)
        val normalizedDays = runCatching { RoutinePlanPersistenceMapper.normalizedDays(days) }
            .getOrElse { return@withTransaction LocalRoutineHorizonResult.Rejected }
        val targetStart = LocalDate.ofEpochDay(throughEpochDay)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val last = plans.lastWeekForPlan(planId)
            ?: return@withTransaction LocalRoutineHorizonResult.Rejected
        if (last.startEpochDay > targetStart.toEpochDay()) {
            return@withTransaction LocalRoutineHorizonResult.Unchanged
        }
        var nextStart = LocalDate.ofEpochDay(last.startEpochDay).plusWeeks(1)
        var ordinal = last.ordinal + 1
        var addedWeeks = 0
        while (!nextStart.isAfter(targetStart)) {
            val week = RoutinePlanPersistenceMapper.week(plan, nextStart, ordinal)
            plans.appendRoutineWeek(
                week,
                RoutinePlanPersistenceMapper.openWorkouts(planId, week, normalizedDays, updatedAtEpochMillis),
            )
            addedWeeks += 1
            nextStart = nextStart.plusWeeks(1)
            ordinal += 1
        }
        if (addedWeeks == 0) LocalRoutineHorizonResult.Unchanged else LocalRoutineHorizonResult.Extended(addedWeeks)
    }
}

sealed interface LocalRoutineHorizonResult {
    data class Extended(val addedWeeks: Int) : LocalRoutineHorizonResult
    data object Unchanged : LocalRoutineHorizonResult
    data object Rejected : LocalRoutineHorizonResult
}
