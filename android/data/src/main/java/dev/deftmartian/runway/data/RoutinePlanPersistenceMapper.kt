package dev.deftmartian.runway.data

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate

/** A first-class weekly running routine with a runner-selected distance or duration target. */
data class RoutinePlanMetadata(
    val goalId: String,
    val planId: String,
    val title: String,
    val startEpochDay: Long,
    /** Existing availability convention: Sunday=0 through Saturday=6. */
    val selectedDays: List<Int>,
    val weeklyTargetDistanceMeters: Int? = null,
    val weeklyTargetDurationSeconds: Int? = null,
    val startingWeeklyDistanceMeters: Int? = weeklyTargetDistanceMeters,
    val startingWeeklyDurationSeconds: Int? = weeklyTargetDurationSeconds,
    val weeklyChangeDistanceMeters: Int? = if (weeklyTargetDistanceMeters != null) 0 else null,
    val weeklyChangeDurationSeconds: Int? = if (weeklyTargetDurationSeconds != null) 0 else null,
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
            weeklyTargetDistanceMeters = metadata.weeklyTargetDistanceMeters,
            weeklyTargetDurationSeconds = metadata.weeklyTargetDurationSeconds,
            startingWeeklyDistanceMeters = metadata.startingWeeklyDistanceMeters,
            startingWeeklyDurationSeconds = metadata.startingWeeklyDurationSeconds,
            weeklyChangeDistanceMeters = metadata.weeklyChangeDistanceMeters,
            weeklyChangeDurationSeconds = metadata.weeklyChangeDurationSeconds,
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
        weeklyTargetDistanceMeters: Int? = null,
        weeklyTargetDurationSeconds: Int? = null,
        startingWeeklyDistanceMeters: Int? = weeklyTargetDistanceMeters,
        startingWeeklyDurationSeconds: Int? = weeklyTargetDurationSeconds,
        weeklyChangeDistanceMeters: Int? = if (weeklyTargetDistanceMeters != null) 0 else null,
        weeklyChangeDurationSeconds: Int? = if (weeklyTargetDurationSeconds != null) 0 else null,
        createdAtEpochMillis: Long,
    ): RoutinePlanPersistenceGraph {
        require(goalId.isNotBlank() && planId.isNotBlank())
        require(title.trim().length in 2..120)
        require(createdAtEpochMillis >= 0)
        require(priority in setOf("finish_healthy", "consistency"))
        val goalTarget = RoutineWeeklyTarget(
            distanceMeters = weeklyTargetDistanceMeters,
            durationSeconds = weeklyTargetDurationSeconds,
        )
        val ramp = RoutineRamp(
            start = RoutineWeeklyTarget(startingWeeklyDistanceMeters, startingWeeklyDurationSeconds),
            goal = goalTarget,
            weeklyChangeDistanceMeters = weeklyChangeDistanceMeters,
            weeklyChangeDurationSeconds = weeklyChangeDurationSeconds,
        )
        val days = normalizedDays(selectedDays)
        val start = LocalDate.ofEpochDay(startEpochDay)
        require(start.dayOfWeek == DayOfWeek.MONDAY) { "A routine must start on a Monday." }
        require(ramp.start.supportsRunCount(days.size) && ramp.goal.supportsRunCount(days.size)) {
            "The routine ramp cannot be divided into valid runs."
        }
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
            summaryBaselineMeters = ramp.start.distanceMeters,
            summarySessionsPerWeek = days.size,
            summarySessionDurationSeconds = ramp.start.durationSeconds,
            routineTargetDistanceMeters = ramp.goal.distanceMeters,
            routineTargetDurationSeconds = ramp.goal.durationSeconds,
            routineWeeklyChangeDistanceMeters = ramp.weeklyChangeDistanceMeters,
            routineWeeklyChangeDurationSeconds = ramp.weeklyChangeDurationSeconds,
        )
        val weeks = (0 until INITIAL_HORIZON_WEEKS).map { offset ->
            week(plan, start.plusWeeks(offset.toLong()), offset + 1)
        }
        return RoutinePlanPersistenceGraph(
            goal = goal,
            plan = plan,
            days = days.map { RoutineScheduleDayEntity(plan.planId, it) },
            weeks = weeks,
            workouts = weeks.flatMap { week ->
                prescribedWorkouts(
                    plan.planId,
                    week,
                    days,
                    createdAtEpochMillis,
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
            generatedLoadMeters = routineTargetForWeek(plan, ordinal).distanceMeters,
            generatedLoadDurationSeconds = routineTargetForWeek(plan, ordinal).durationSeconds,
            riskAssessment = null,
            isDownWeek = false,
            isTaperWeek = false,
            eventName = null,
            eventEpochDay = null,
            generatedLongRunDistanceMeters = null,
        )

    internal fun prescribedWorkouts(
        planId: String,
        week: PlanWeekEntity,
        selectedDays: List<Int>,
        updatedAtEpochMillis: Long,
    ): List<WorkoutEntity> {
        val scheduledDays = normalizedDays(selectedDays).mapIndexed { position, day ->
            val scheduled = LocalDate.ofEpochDay(week.startEpochDay)
                .plusDays(weekdayOffset(day).toLong())
            position to scheduled
        }
        val target = RoutineWeeklyTarget.optional(
            distanceMeters = week.generatedLoadMeters,
            durationSeconds = week.generatedLoadDurationSeconds,
        )
        return scheduledDays.mapIndexed { shareIndex, (position, scheduled) ->
            val prescription = target?.share(scheduledDays.size, shareIndex)
            WorkoutEntity(
                workoutId = stableId("routine-workout", planId, scheduled.toString()),
                planId = planId,
                weekId = week.weekId,
                position = position,
                generatedPurpose = prescription?.purpose ?: "Open run",
                generatedDistanceMeters = prescription?.distanceMeters,
                generatedDurationSeconds = prescription?.durationSeconds,
                currentPurpose = prescription?.purpose ?: "Open run",
                currentDistanceMeters = prescription?.distanceMeters,
                currentDurationSeconds = prescription?.durationSeconds,
                tombstonedAtEpochMillis = null,
                updatedAtEpochMillis = updatedAtEpochMillis,
                generatedScheduledEpochDay = scheduled.toEpochDay(),
                currentScheduledEpochDay = scheduled.toEpochDay(),
                generatedWorkoutType = "easy",
                currentWorkoutType = "easy",
                generatedPrescriptionKind = prescription?.kind ?: "open",
                currentPrescriptionKind = prescription?.kind ?: "open",
                generatedIntensity = "easy",
                currentIntensity = "easy",
                generatedReason = prescription?.reason ?: "Scheduled weekly routine.",
                currentReason = prescription?.reason ?: "Scheduled weekly routine.",
                currentStatus = "planned",
            )
        }
    }

    internal fun retargetWorkouts(
        workouts: List<WorkoutEntity>,
        target: RoutineWeeklyTarget,
        updatedAtEpochMillis: Long,
    ): List<WorkoutEntity> = workouts
        .sortedWith(compareBy(WorkoutEntity::currentScheduledEpochDay, WorkoutEntity::position))
        .mapIndexed { index, workout ->
            val prescription = target.share(workouts.size, index)
            workout.copy(
                generatedPurpose = prescription.purpose,
                generatedDistanceMeters = prescription.distanceMeters,
                generatedDurationSeconds = prescription.durationSeconds,
                currentPurpose = prescription.purpose,
                currentDistanceMeters = prescription.distanceMeters,
                currentDurationSeconds = prescription.durationSeconds,
                updatedAtEpochMillis = updatedAtEpochMillis,
                generatedPrescriptionKind = prescription.kind,
                currentPrescriptionKind = prescription.kind,
                generatedReason = prescription.reason,
                currentReason = prescription.reason,
            )
        }

    internal fun normalizedDays(days: List<Int>): List<Int> {
        require(days.size in 1..7 && days.distinct().size == days.size && days.all { it in 0..6 }) {
            "A routine needs one to seven distinct weekdays."
        }
        return days.sortedBy(::weekdayOffset)
    }

    private fun weekdayOffset(day: Int): Int = if (day == 0) 6 else day - 1

    internal fun routineTargetForWeek(plan: PlanEntity, ordinal: Int): RoutineWeeklyTarget {
        require(ordinal >= 1)
        val start = RoutineWeeklyTarget(plan.summaryBaselineMeters, plan.summarySessionDurationSeconds)
        val goal = RoutineWeeklyTarget(
            plan.routineTargetDistanceMeters,
            plan.routineTargetDurationSeconds,
        )
        val ramp = RoutineRamp(
            start = start,
            goal = goal,
            weeklyChangeDistanceMeters = plan.routineWeeklyChangeDistanceMeters,
            weeklyChangeDurationSeconds = plan.routineWeeklyChangeDurationSeconds,
        )
        return ramp.targetForWeek(ordinal)
    }

    internal fun stableId(prefix: String, vararg parts: String): String {
        val payload = parts.joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$prefix-$digest"
    }
}

data class RoutineWeeklyTarget(
    val distanceMeters: Int?,
    val durationSeconds: Int?,
) {
    init {
        require(
            (distanceMeters != null && distanceMeters in 500..250_000 && durationSeconds == null) ||
                (durationSeconds != null && durationSeconds in 600..252_000 && distanceMeters == null),
        ) { "A routine target must be either 0.5-250 km or 10-4,200 minutes." }
    }

    fun share(count: Int, index: Int): RoutineWorkoutPrescription {
        require(count in 1..7 && index in 0 until count)
        val total = distanceMeters ?: requireNotNull(durationSeconds)
        val unit = if (distanceMeters != null) DISTANCE_UNIT_METERS else DURATION_UNIT_SECONDS
        require(total % unit == 0) { "Routine prescriptions use readable increments." }
        val totalUnits = total / unit
        val amount = (totalUnits / count + if (index < totalUnits % count) 1 else 0) * unit
        return if (distanceMeters != null) {
            RoutineWorkoutPrescription(amount, null, "distance", "Routine run", "Scheduled from this week's distance goal.")
        } else {
            RoutineWorkoutPrescription(null, amount, "timed", "Routine run", "Scheduled from this week's time goal.")
        }
    }

    fun supportsRunCount(count: Int): Boolean = count in 1..7 && (0 until count).all { index ->
        val share = share(count, index)
        (share.distanceMeters?.let { it in 500..100_000 } ?: true) &&
            (share.durationSeconds?.let { it in 600..21_600 } ?: true)
    }

    fun usesReadableUnits(): Boolean =
        distanceMeters?.rem(DISTANCE_UNIT_METERS) == 0 ||
            durationSeconds?.rem(DURATION_UNIT_SECONDS) == 0

    companion object {
        const val DISTANCE_UNIT_METERS = 500
        const val DURATION_UNIT_SECONDS = 300

        fun optional(distanceMeters: Int?, durationSeconds: Int?): RoutineWeeklyTarget? =
            if (distanceMeters == null && durationSeconds == null) null else RoutineWeeklyTarget(distanceMeters, durationSeconds)
    }
}

data class RoutineRamp(
    val start: RoutineWeeklyTarget,
    val goal: RoutineWeeklyTarget,
    val weeklyChangeDistanceMeters: Int?,
    val weeklyChangeDurationSeconds: Int?,
) {
    init {
        val distanceRamp = start.distanceMeters != null && goal.distanceMeters != null &&
            start.durationSeconds == null && goal.durationSeconds == null &&
            weeklyChangeDistanceMeters != null && weeklyChangeDurationSeconds == null
        val durationRamp = start.durationSeconds != null && goal.durationSeconds != null &&
            start.distanceMeters == null && goal.distanceMeters == null &&
            weeklyChangeDurationSeconds != null && weeklyChangeDistanceMeters == null
        require(distanceRamp || durationRamp) { "A routine ramp must use one metric." }
        val change = weeklyChangeDistanceMeters ?: requireNotNull(weeklyChangeDurationSeconds)
        val startAmount = start.distanceMeters ?: requireNotNull(start.durationSeconds)
        val goalAmount = goal.distanceMeters ?: requireNotNull(goal.durationSeconds)
        require(change >= 0 && (change > 0 || startAmount == goalAmount)) {
            "A changing routine needs a positive weekly step."
        }
        val unit = if (distanceRamp) {
            RoutineWeeklyTarget.DISTANCE_UNIT_METERS
        } else {
            RoutineWeeklyTarget.DURATION_UNIT_SECONDS
        }
        require(startAmount % unit == 0 && goalAmount % unit == 0 && change % unit == 0) {
            "Routine targets and changes must use readable increments."
        }
    }

    fun targetForWeek(ordinal: Int): RoutineWeeklyTarget {
        require(ordinal >= 1)
        val startAmount = start.distanceMeters ?: requireNotNull(start.durationSeconds)
        val goalAmount = goal.distanceMeters ?: requireNotNull(goal.durationSeconds)
        val change = weeklyChangeDistanceMeters ?: requireNotNull(weeklyChangeDurationSeconds)
        val direction = goalAmount.compareTo(startAmount)
        val moved = if (direction == 0) {
            0
        } else {
            minOf(
                kotlin.math.abs(goalAmount - startAmount).toLong(),
                change.toLong() * (ordinal - 1),
            ).toInt()
        }
        val amount = startAmount + direction * moved
        return if (start.distanceMeters != null) {
            RoutineWeeklyTarget(amount, null)
        } else {
            RoutineWeeklyTarget(null, amount)
        }
    }
}

data class RoutineWorkoutPrescription(
    val distanceMeters: Int?,
    val durationSeconds: Int?,
    val kind: String,
    val purpose: String,
    val reason: String,
)
