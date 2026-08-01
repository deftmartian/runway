package dev.deftmartian.runway.data

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * A first-class, target-free weekly running routine.  It intentionally bypasses the finite-plan
 * mapper: a routine has no race date, load ramp, or generated distance/duration prescription.
 */
data class RoutinePlanMetadata(
    val goalId: String,
    val planId: String,
    val title: String,
    val startEpochDay: Long,
    /** Existing availability convention: Sunday=0 through Saturday=6. */
    val selectedDays: List<Int>,
    val priority: String,
    val createdAtEpochMillis: Long,
)

data class RoutinePlanPersistenceGraph(
    val goal: GoalEntity,
    val plan: PlanEntity,
    val days: List<RoutineScheduleDayEntity>,
    val weeks: List<PlanWeekEntity>,
    val workouts: List<WorkoutEntity>,
)

object RoutinePlanPersistenceMapper {
    const val INITIAL_HORIZON_WEEKS = 8

    fun map(metadata: RoutinePlanMetadata): RoutinePlanPersistenceGraph {
        require(metadata.goalId.isNotBlank() && metadata.planId.isNotBlank())
        require(metadata.title.trim().length in 2..120)
        require(metadata.createdAtEpochMillis >= 0)
        return map(
            goalId = metadata.goalId,
            planId = metadata.planId,
            title = metadata.title,
            priority = metadata.priority,
            startEpochDay = metadata.startEpochDay,
            selectedDays = metadata.selectedDays,
            createdAtEpochMillis = metadata.createdAtEpochMillis,
        )
    }

    fun map(
        goalId: String,
        planId: String,
        title: String,
        priority: String,
        startEpochDay: Long,
        selectedDays: List<Int>,
        createdAtEpochMillis: Long,
    ): RoutinePlanPersistenceGraph {
        require(goalId.isNotBlank() && planId.isNotBlank())
        require(title.trim().length in 2..120)
        require(createdAtEpochMillis >= 0)
        require(priority in setOf("finish_healthy", "consistency"))
        val days = normalizedDays(selectedDays)
        val start = LocalDate.ofEpochDay(startEpochDay)
        val firstMonday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val goal = GoalEntity(
            goalId = goalId,
            title = title.trim(),
            targetDateEpochDay = null,
            state = "active",
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = createdAtEpochMillis,
            kind = "routine",
            startMode = "routine",
            raceDistanceMeters = null,
            priority = priority,
        )
        val plan = PlanEntity(
            planId = planId,
            goalId = goalId,
            phaseType = "routine",
            state = "active",
            startEpochDay = start.toEpochDay(),
            endEpochDay = null,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = createdAtEpochMillis,
            riskAssessment = null,
            summarySessionsPerWeek = days.size,
        )
        val weeks = (0 until INITIAL_HORIZON_WEEKS).map { offset ->
            week(plan, firstMonday.plusWeeks(offset.toLong()), offset + 1)
        }
        return RoutinePlanPersistenceGraph(
            goal = goal,
            plan = plan,
            days = days.map { RoutineScheduleDayEntity(plan.planId, it) },
            weeks = weeks,
            workouts = weeks.flatMap { week ->
                openWorkouts(
                    plan.planId,
                    week,
                    days,
                    createdAtEpochMillis,
                    notBeforeEpochDay = plan.startEpochDay,
                )
            },
        )
    }

    internal fun week(plan: PlanEntity, start: LocalDate, ordinal: Int): PlanWeekEntity =
        PlanWeekEntity(
            weekId = stableId("routine-week", plan.planId, start.toString()),
            planId = plan.planId,
            ordinal = ordinal,
            startEpochDay = start.toEpochDay(),
            generatedLoadMeters = null,
            generatedLoadDurationSeconds = null,
            riskAssessment = null,
            isDownWeek = false,
            isTaperWeek = false,
            eventName = null,
            eventEpochDay = null,
            generatedLongRunDistanceMeters = null,
        )

    internal fun openWorkouts(
        planId: String,
        week: PlanWeekEntity,
        selectedDays: List<Int>,
        updatedAtEpochMillis: Long,
        notBeforeEpochDay: Long? = null,
    ): List<WorkoutEntity> = normalizedDays(selectedDays).mapIndexed { position, day ->
        val scheduled = LocalDate.ofEpochDay(week.startEpochDay).plusDays(weekdayOffset(day).toLong())
        scheduled.takeIf { notBeforeEpochDay == null || it.toEpochDay() >= notBeforeEpochDay }?.let {
            WorkoutEntity(
            workoutId = stableId("routine-workout", planId, scheduled.toString()),
            planId = planId,
            weekId = week.weekId,
            position = position,
            generatedPurpose = "Open run",
            generatedDistanceMeters = null,
            generatedDurationSeconds = null,
            currentPurpose = "Open run",
            currentDistanceMeters = null,
            currentDurationSeconds = null,
            tombstonedAtEpochMillis = null,
            updatedAtEpochMillis = updatedAtEpochMillis,
            generatedScheduledEpochDay = scheduled.toEpochDay(),
            currentScheduledEpochDay = scheduled.toEpochDay(),
            generatedWorkoutType = "easy",
            currentWorkoutType = "easy",
            generatedPrescriptionKind = "open",
            currentPrescriptionKind = "open",
            generatedIntensity = "easy",
            currentIntensity = "easy",
            generatedReason = "Scheduled weekly routine.",
            currentReason = "Scheduled weekly routine.",
                currentStatus = "planned",
            )
        }
    }.filterNotNull()

    internal fun normalizedDays(days: List<Int>): List<Int> {
        require(days.size in 1..7 && days.distinct().size == days.size && days.all { it in 0..6 }) {
            "A routine needs one to seven distinct weekdays."
        }
        return days.sortedBy(::weekdayOffset)
    }

    private fun weekdayOffset(day: Int): Int = if (day == 0) 6 else day - 1

    internal fun stableId(prefix: String, vararg parts: String): String {
        val payload = parts.joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$prefix-$digest"
    }
}
