package dev.deftmartian.runway.domain

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object TrainingPlanner {
    private const val maxPlanWeeks = 52
    private const val minimumRunMeters = 500
    private const val plannedDistanceIncrementMeters = 500
    private val peakWeekly = mapOf(
        RaceDistance.FIVE_K to 14000,
        RaceDistance.TEN_K to 22000,
        RaceDistance.HALF to 34000,
        RaceDistance.MARATHON to 58000,
    )
    private val peakLong = mapOf(
        RaceDistance.FIVE_K to 5000,
        RaceDistance.TEN_K to 9000,
        RaceDistance.HALF to 18000,
        RaceDistance.MARATHON to 32000,
    )
    private val taperWeeks = mapOf(
        RaceDistance.FIVE_K to 1,
        RaceDistance.TEN_K to 1,
        RaceDistance.HALF to 2,
        RaceDistance.MARATHON to 3,
    )
    private val raceRefs = mapOf(
        RaceDistance.FIVE_K to TrainingSourceRefs.REI_5K,
        RaceDistance.TEN_K to TrainingSourceRefs.REI_10K,
        RaceDistance.HALF to TrainingSourceRefs.REI_HALF,
        RaceDistance.MARATHON to TrainingSourceRefs.REI_MARATHON,
    )

    fun getRaceMeters(distance: RaceDistance) = distance.meters

    fun generatePlan(intake: PlannerIntake, today: LocalDate = LocalDate.now()): GeneratedPlan = when (intake) {
        is EstablishedTrainingIntake -> generateEstablished(intake, today)
        is CalibrationIntake -> generateCalibration(intake, today)
    }
    fun classifyRamp(requiredWeeklyIncreasePercent: Double): RiskRating {
        return when {
            requiredWeeklyIncreasePercent <= 8 -> RiskRating.CONSERVATIVE
            requiredWeeklyIncreasePercent <= 12 -> RiskRating.MODERATE
            requiredWeeklyIncreasePercent <= 18 -> RiskRating.AGGRESSIVE
            else -> RiskRating.UNSAFE
        }
    }

    private data class EstablishedPlanContext(
        val start: String,
        val availableWeeks: Int,
        val taperWeeks: Int,
        val peakDistance: Int,
        val peakLongRun: Int,
        val initialLongRun: Int,
        val requiredRampPercent: Double,
        val initialRisk: RiskRating,
        val defaultRampPercent: Double,
        val normalRamp: Double,
    )

    private data class EstablishedWeekState(
        var previousDistance: Int,
        var previousLongRun: Int,
        var peakDistance: Int,
        var peakLongRun: Int,
    )

    private data class EstablishedWeekFrame(
        val number: Int,
        val start: String,
        val end: String,
        val taper: Boolean,
        val down: Boolean,
        val taperPosition: Int,
        val raceDate: String?,
    )

    private data class EstablishedWeekTargets(val distance: Int, val longRun: Int)

    private data class EstablishedCompletionFlags(
        val readinessShortfall: Boolean,
        val marathonBaselineShortfall: Boolean,
        val concentratedSchedule: Boolean,
    )

    fun generateEstablished(
        intake: EstablishedTrainingIntake,
        today: LocalDate = LocalDate.now(),
    ): GeneratedDistancePlan {
        val context = establishedPlanContext(intake, today)
        val weeks = generateEstablishedWeeks(intake, context)
        return completeEstablishedPlan(intake, context, weeks)
    }

    private fun establishedPlanContext(
        intake: EstablishedTrainingIntake,
        today: LocalDate,
    ): EstablishedPlanContext {
        assertSupportedBaseline(intake)
        val start = intake.startDate ?: nextPlanWeekStart(today)
        val planDays = DateUtils.daysBetween(start, intake.targetDate) + 1
        require(planDays >= 1) { "The target date must be on or after the plan start date." }
        val availableWeeks = max(1, ceil(planDays / 7.0).toInt())
        require(availableWeeks <= maxPlanWeeks) { "Training plans cannot exceed 52 weeks." }
        assertSchedulable(intake)
        val taper = min(taperWeeks.getValue(intake.raceDistance), max(1, availableWeeks - 1))
        val buildWeeks = max(1, availableWeeks - taper)
        val peak = peakWeekly.getValue(intake.raceDistance)
        val longPeak = peakLong.getValue(intake.raceDistance)
        val initialLong = min(intake.longestRecentRunMeters, min(intake.currentWeeklyDistanceMeters, longPeak))
        val required = max(requiredBuildRamp(intake.currentWeeklyDistanceMeters, peak, buildWeeks), requiredBuildRamp(initialLong, longPeak, buildWeeks))
        val initialRisk = if (availableWeeks < 8 && intake.currentWeeklyDistanceMeters < peak * .55) RiskRating.UNSAFE else classifyRamp(required)
        val defaultRampPercent = priorityRampCap(intake) * 100
        val normalRamp = min(required, defaultRampPercent) / 100
        return EstablishedPlanContext(
            start,
            availableWeeks,
            taper,
            peak,
            longPeak,
            initialLong,
            required,
            initialRisk,
            defaultRampPercent,
            normalRamp,
        )
    }

    private fun generateEstablishedWeeks(
        intake: EstablishedTrainingIntake,
        context: EstablishedPlanContext,
    ): List<GeneratedWeek> {
        val weeks = mutableListOf<GeneratedWeek>()
        val state = EstablishedWeekState(
            intake.currentWeeklyDistanceMeters,
            context.initialLongRun,
            intake.currentWeeklyDistanceMeters,
            context.initialLongRun,
        )
        repeat(context.availableWeeks) { index ->
            val frame = establishedWeekFrame(intake, context, index)
            val week = generateEstablishedWeek(intake, context, state, frame)
            weeks += week
            updateEstablishedWeekState(state, week)
        }
        return weeks
    }

    private fun establishedWeekFrame(
        intake: EstablishedTrainingIntake,
        context: EstablishedPlanContext,
        index: Int,
    ): EstablishedWeekFrame {
        val number = index + 1
        val taper = number > context.availableWeeks - context.taperWeeks
        val start = DateUtils.addDays(context.start, (index * 7).toLong())
        val nominalEnd = DateUtils.addDays(start, 6)
        val end = if (nominalEnd < intake.targetDate) nominalEnd else intake.targetDate
        val raceWeek = intake.targetDate >= start && intake.targetDate <= end
        return EstablishedWeekFrame(
            number,
            start,
            end,
            taper,
            !taper && number % 4 == 0,
            if (taper) number - (context.availableWeeks - context.taperWeeks) else 0,
            intake.targetDate.takeIf { raceWeek },
        )
    }

    private fun generateEstablishedWeek(
        intake: EstablishedTrainingIntake,
        context: EstablishedPlanContext,
        state: EstablishedWeekState,
        frame: EstablishedWeekFrame,
    ): GeneratedWeek {
        val targets = establishedWeekTargets(intake, context, state, frame)
        val scheduled = createWeekWorkouts(
            intake,
            frame.start,
            frame.end,
            targets.distance,
            targets.longRun,
            frame.raceDate,
        )
        val training = scheduled.workouts
            .filter { it.type != WorkoutType.RACE }
            .sumOf { it.targetDistanceMeters }
        val event = scheduled.workouts
            .filter { it.type == WorkoutType.RACE }
            .sumOf { it.targetDistanceMeters }
        val longRunRamp = scheduled.longRun
            .takeIf { it > 0 }
            ?.let { percentIncrease(state.previousLongRun, it) }
            ?: 0.0
        val risk = classifyRamp(max(percentIncrease(state.previousDistance, training), longRunRamp))
        return GeneratedWeek(
            frame.number,
            frame.start,
            training,
            event,
            scheduled.workouts.sumOf { it.targetDistanceMeters },
            scheduled.workouts.sumOf { it.targetDurationSeconds ?: 0 },
            scheduled.longRun,
            risk,
            frame.down,
            frame.taper,
            scheduled.workouts,
        )
    }

    private fun establishedWeekTargets(
        intake: EstablishedTrainingIntake,
        context: EstablishedPlanContext,
        state: EstablishedWeekState,
        frame: EstablishedWeekFrame,
    ): EstablishedWeekTargets {
        val rampedDistance = if (frame.number == 1) {
            plannedDistance(intake.currentWeeklyDistanceMeters.toDouble())
        } else {
            plannedDistance(state.previousDistance * (1 + context.normalRamp))
        }
        val buildDistance = min(context.peakDistance, rampedDistance)
        val distance = when {
            frame.taper -> taperTarget(state.peakDistance, frame.taperPosition, context.taperWeeks)
            frame.down -> plannedDistance(buildDistance * .85)
            else -> buildDistance
        }
        val rampedLongRun = if (frame.number == 1) {
            plannedDistance(context.initialLongRun.toDouble())
        } else {
            plannedDistance(state.previousLongRun * (1 + context.normalRamp))
        }
        val adjustedLongRun = if (frame.down) plannedDistance(rampedLongRun * .85) else rampedLongRun
        val longRun = if (frame.taper) {
            taperTarget(state.peakLongRun, frame.taperPosition, context.taperWeeks)
        } else {
            min(context.peakLongRun, adjustedLongRun)
        }
        return EstablishedWeekTargets(distance, longRun)
    }

    private fun updateEstablishedWeekState(state: EstablishedWeekState, week: GeneratedWeek) {
        state.previousDistance = week.trainingTargetDistanceMeters
        if (week.longRunMeters > 0) state.previousLongRun = week.longRunMeters
        if (!week.isTaper) {
            state.peakDistance = max(state.peakDistance, week.trainingTargetDistanceMeters)
            state.peakLongRun = max(state.peakLongRun, week.longRunMeters)
        }
    }

    private fun completeEstablishedPlan(
        intake: EstablishedTrainingIntake,
        context: EstablishedPlanContext,
        weeks: List<GeneratedWeek>,
    ): GeneratedDistancePlan {
        val generatedPeak = weeks.maxOf { it.trainingTargetDistanceMeters }
        val generatedLong = weeks.maxOf { it.longRunMeters }
        val flags = establishedCompletionFlags(intake, generatedLong)
        val warnings = establishedPlanWarnings(intake, context, generatedPeak, flags)
        val risk = establishedPlanRisk(context.initialRisk, weeks, flags)
        val summary = DistanceSummary(
            intake.currentWeeklyDistanceMeters,
            generatedPeak,
            oneDecimal(context.requiredRampPercent),
            oneDecimal(context.defaultRampPercent),
            generatedLong,
            warnings,
        )
        val sources = listOf(
            TrainingSourceRefs.MAYO_INJURY_AVOIDANCE,
            TrainingSourceRefs.MAYO_TAPER,
            raceRefs.getValue(intake.raceDistance),
            TrainingSourceRefs.RRCA,
        )
        return GeneratedDistancePlan(context.start, intake.targetDate, weeks, risk, summary, sources)
    }

    private fun establishedCompletionFlags(
        intake: EstablishedTrainingIntake,
        generatedLong: Int,
    ) = EstablishedCompletionFlags(
        readinessShortfall = generatedLong < longRunFloor(intake.raceDistance),
        marathonBaselineShortfall = intake.raceDistance == RaceDistance.MARATHON &&
            (intake.currentWeeklyDistanceMeters < 32000 || intake.longestRecentRunMeters < 20000),
        concentratedSchedule = intake.raceDistance in setOf(RaceDistance.HALF, RaceDistance.MARATHON) &&
            plannedRuns(intake) < 3,
    )

    private fun establishedPlanWarnings(
        intake: EstablishedTrainingIntake,
        context: EstablishedPlanContext,
        generatedPeak: Int,
        flags: EstablishedCompletionFlags,
    ): MutableList<String> {
        val result = warnings(
            intake,
            context.availableWeeks,
            context.requiredRampPercent,
            context.initialRisk,
        ).toMutableList()
        if (generatedPeak < context.peakDistance * .95) result += "The available weeks do not allow the usual peak distance for this goal. Move the target date or choose a shorter distance."
        if (flags.readinessShortfall) result += "The longest planned run is low for this race distance. Keep the goal completion-oriented or move the date."
        if (flags.marathonBaselineShortfall) result += "Marathon goals need a stronger recent base than shorter races. Build consistency first or move the date."
        if (flags.concentratedSchedule) result += "Two run days concentrate a high-volume goal into unusually large sessions. Add a third available day, choose a shorter goal, or explicitly accept the higher load concentration."
        return result
    }

    private fun establishedPlanRisk(
        initialRisk: RiskRating,
        weeks: List<GeneratedWeek>,
        flags: EstablishedCompletionFlags,
    ): RiskRating {
        var result = highestRisk(listOf(initialRisk, highestRisk(weeks.map { it.risk })))
        result = when {
            flags.marathonBaselineShortfall -> elevateRisk(result, RiskRating.UNSAFE)
            flags.readinessShortfall -> elevateRisk(result, RiskRating.AGGRESSIVE)
            else -> result
        }
        return if (flags.concentratedSchedule) elevateRisk(result, RiskRating.AGGRESSIVE) else result
    }

    fun generateCalibration(intake: CalibrationIntake, today: LocalDate = LocalDate.now()): GeneratedCalibrationPlan {
        val start = intake.startDate ?: nextPlanWeekStart(today)
        val days = pickPhaseDays(intake.availability, 2)
        val weeks = (0..1).map { index ->
            val weekStart = DateUtils.addDays(start, (index * 7).toLong())
            val workouts = phaseWeekWorkouts(weekStart, days) { session, date ->
                GeneratedWorkout(
                    date,
                    WorkoutType.EASY,
                    0,
                    WorkoutPrescription.Open,
                    "easy",
                    "Open baseline run ${session + 1}",
                    "Run on the chosen day. No distance or duration is prescribed.",
                    emptyList(),
                )
            }
            timedWeek(index + 1, weekStart, workouts)
        }
        val warnings = mutableListOf(
            "Distance and duration remain observations until the runner confirms the completed runs as a baseline.",
        )
        return GeneratedCalibrationPlan(
            start,
            DateUtils.addDays(start, 13),
            weeks,
            RiskRating.CONSERVATIVE,
            CalibrationSummary(warnings = warnings),
            emptyList(),
        )
    }

    private data class Scheduled(val workouts: List<GeneratedWorkout>, val longRun: Int)

    private fun createWeekWorkouts(
        intake: EstablishedTrainingIntake,
        start: String,
        end: String,
        total: Int,
        desiredLong: Int,
        raceDate: String?,
    ): Scheduled {
        val runDays = pickRunDays(intake)
        val dates = generateWeekDates(start, end)
        val eligibleTraining = dates.filter { date ->
            val isoDate = date.toString()
            val isAvailableRunDay = date.dayOfWeek.value % 7 in runDays
            val isRaceDay = isoDate == raceDate
            val isDayBeforeRace = raceDate != null && isoDate >= DateUtils.addDays(raceDate, -1)
            isAvailableRunDay && !isRaceDay && !isDayBeforeRace
        }
        // The goal event supplies one of the runner's usual weekly runs; retain the
        // earliest eligible training days before it rather than adding a full run week.
        val training = if (raceDate == null) {
            eligibleTraining
        } else {
            eligibleTraining.take(max(0, plannedRuns(intake) - 1))
        }
        val allocation = if (raceDate != null) {
            // The event replaces one normal run. Scale the training-only taper budget to the
            // remaining slots so two short easy runs do not absorb a three-run week's volume.
            val raceWeekTrainingBudget = plannedDistance(
                total.toDouble() * training.size / plannedRuns(intake),
            )
            allocateEvenly(raceWeekTrainingBudget, training.size)
        } else {
            allocateRunDistances(
                total,
                desiredLong,
                training.map { it.dayOfWeek.value % 7 },
                intake.preferredLongRunDay,
            )
        }
        var trainingIndex = 0
        val workouts = dates.map { date ->
            val isoDate = date.toString()
            when {
                isoDate == raceDate -> raceWorkout(intake, isoDate)
                date !in training -> restWorkout(isoDate)
                else -> {
                    val distance = allocation.first[trainingIndex++]
                    val isLongRun = raceDate == null &&
                        date.dayOfWeek.value % 7 == intake.preferredLongRunDay
                    plannedRunWorkout(intake, isoDate, distance, isLongRun, raceDate != null)
                }
            }
        }
        return Scheduled(workouts, if (raceDate == null) allocation.second else 0)
    }

    private fun generateWeekDates(start: String, end: String): List<LocalDate> =
        generateSequence(DateUtils.parseIsoDate(start)) { date ->
            if (date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE) < end) {
                date.plusDays(1)
            } else {
                null
            }
        }.toList()

    private fun plannedRunWorkout(
        intake: EstablishedTrainingIntake,
        date: String,
        distance: Int,
        isLongRun: Boolean,
        isRaceWeek: Boolean,
    ): GeneratedWorkout {
        val title = when {
            isLongRun -> "Long run"
            isRaceWeek -> "Race-week easy run"
            else -> "Easy run"
        }
        val rationale = when {
            isLongRun -> "Builds endurance below race effort."
            isRaceWeek -> "Keeps an easy run in race week without using the day before the goal."
            else -> "Adds easy weekly distance."
        }
        val sourceRefs = if (isLongRun) {
            listOf(raceRefs.getValue(intake.raceDistance), TrainingSourceRefs.MAYO_INJURY_AVOIDANCE)
        } else {
            listOf(TrainingSourceRefs.MAYO_INJURY_AVOIDANCE, TrainingSourceRefs.RRCA)
        }
        return GeneratedWorkout(
            date,
            if (isLongRun) WorkoutType.LONG else WorkoutType.EASY,
            distance,
            WorkoutPrescription.Distance(distance),
            "easy",
            title,
            rationale,
            sourceRefs,
        )
    }

    private fun allocateRunDistances(
        total: Int,
        desired: Int,
        weekdays: List<Int>,
        longDay: Int,
    ): Pair<List<Int>, Int> {
        if (weekdays.isEmpty()) return emptyList<Int>() to 0
        val longIndex = weekdays.indexOf(longDay)
        if (longIndex < 0) return allocateEvenly(total, weekdays.size)

        val roundedTotal = plannedDistance(total.toDouble())
        val minimum = plannedDistance(
            ceil(roundedTotal / weekdays.size.toDouble() / plannedDistanceIncrementMeters) *
                plannedDistanceIncrementMeters,
        )
        val maximum = max(
            minimum,
            roundedTotal - minimumRunMeters * max(0, weekdays.size - 1),
        )
        val long = min(maximum, max(minimum, plannedDistance(desired.toDouble())))
        val easy = allocateEvenly(roundedTotal - long, weekdays.size - 1).first
        var easyIndex = 0
        val distances = weekdays.indices.map { index ->
            if (index == longIndex) long else easy[easyIndex++]
        }
        return distances to long
    }

    private fun allocateEvenly(total: Int, count: Int): Pair<List<Int>, Int> {
        if (count <= 0) return emptyList<Int>() to 0
        val totalUnits = plannedDistance(total.toDouble()) / plannedDistanceIncrementMeters
        val baseUnits = floor(totalUnits / count.toDouble()).toInt()
        var remainingUnits = totalUnits - baseUnits * count
        val allocations = List(count) {
            (baseUnits + if (remainingUnits-- > 0) 1 else 0) * plannedDistanceIncrementMeters
        }
        return allocations to 0
    }

    private fun timedWeek(number: Int, start: String, workouts: List<GeneratedWorkout>) =
        GeneratedWeek(
            number,
            start,
            0,
            0,
            0,
            workouts.sumOf { it.targetDurationSeconds ?: 0 },
            0,
            RiskRating.CONSERVATIVE,
            false,
            false,
            workouts,
        )

    private fun phaseWeekWorkouts(
        start: String,
        days: List<Int>,
        session: (Int, String) -> GeneratedWorkout,
    ): List<GeneratedWorkout> {
        var sessionIndex = 0
        return (0..6).map { offset ->
            val date = DateUtils.addDays(start, offset.toLong())
            val dayOfWeek = DateUtils.parseIsoDate(date).dayOfWeek.value % 7
            if (dayOfWeek !in days) restWorkout(date) else session(sessionIndex++, date)
        }
    }

    private fun restWorkout(date: String) = GeneratedWorkout(
        date,
        WorkoutType.REST,
        0,
        WorkoutPrescription.Rest,
        "rest",
        "Rest day",
        "Recovery is part of the plan, especially around long or hard work.",
        listOf(TrainingSourceRefs.RRCA),
    )

    private fun raceWorkout(intake: EstablishedTrainingIntake, date: String): GeneratedWorkout {
        val distance = getRaceMeters(intake.raceDistance)
        return GeneratedWorkout(
            date,
            WorkoutType.RACE,
            distance,
            WorkoutPrescription.Distance(distance),
            "race",
            "Goal event",
            "This is the plan endpoint. Do not add missed taper distance before the event.",
            listOf(raceRefs.getValue(intake.raceDistance), TrainingSourceRefs.MAYO_TAPER),
        )
    }

    private fun pickPhaseDays(days: List<Int>, count: Int): List<Int> {
        validateDays(days)
        val unique = days.sorted()
        require(unique.size >= count) { "Choose at least $count available run days." }
        val candidates = combinations(unique, count).sortedWith(
            compareByDescending<List<Int>> { circularSpacing(it) }
                .thenComparator { first, second -> compareDays(first, second) },
        )
        val picked = candidates.first()
        require(circularSpacing(picked) >= 2) {
            "Choose available days that leave a rest day between beginner sessions."
        }
        return picked
    }

    private fun combinations(days: List<Int>, count: Int): List<List<Int>> {
        fun pick(start: Int, selected: List<Int>): List<List<Int>> {
            if (selected.size == count) return listOf(selected)
            val lastStart = days.size - (count - selected.size)
            if (start > lastStart) return emptyList()
            return (start..lastStart).flatMap { index ->
                pick(index + 1, selected + days[index])
            }
        }
        return pick(0, emptyList())
    }
    private fun circularSpacing(days: List<Int>) =
        days.flatMapIndexed { index, day ->
            days.drop(index + 1).map { other ->
                min(kotlin.math.abs(day - other), 7 - kotlin.math.abs(day - other))
            }
        }.minOrNull() ?: 0

    private fun compareDays(first: List<Int>, second: List<Int>): Int {
        first.indices.forEach { index ->
            if (first[index] != second[index]) return first[index] - second[index]
        }
        return first.size - second.size
    }

    private fun requiredBuildRamp(start: Int, goal: Int, buildWeeks: Int): Double {
        if (buildWeeks <= 1 || start >= goal) return 0.0
        val cutback = .85.pow((buildWeeks / 4).toDouble())
        return ((goal.toDouble() / start / cutback).pow(1.0 / (buildWeeks - 1)) - 1) * 100
    }

    private fun priorityRampCap(intake: EstablishedTrainingIntake): Double {
        return if (intake.priority == GoalPriority.FINISH_HEALTHY) .075 else .1
    }

    private fun taperTarget(peak: Int, position: Int, length: Int): Int {
        val fraction = when {
            length == 1 -> .5
            position == 1 -> .6
            position == 2 -> .45
            else -> .4
        }
        return plannedDistance(peak * fraction)
    }

    private fun plannedDistance(value: Double) =
        roundTrainingValueToIncrement(value, plannedDistanceIncrementMeters)

    private fun percentIncrease(previous: Int, current: Int) = when {
        previous > 0 -> (current - previous).toDouble() / previous * 100
        current > 0 -> 100.0
        else -> 0.0
    }

    private fun plannedRuns(intake: EstablishedTrainingIntake) =
        min(intake.currentRunsPerWeek, if (intake.raceDistance == RaceDistance.HALF) 4 else 5)

    private fun pickRunDays(intake: EstablishedTrainingIntake): List<Int> {
        val unavailableDayAfterLongRun = (intake.preferredLongRunDay + 1) % 7
        val available = intake.availability
            .distinct()
            .sorted()
            .filter { it != unavailableDayAfterLongRun }
            .toMutableList()
        val picked = mutableListOf(intake.preferredLongRunDay)
        while (picked.size < plannedRuns(intake) && available.isNotEmpty()) {
            val next = available
                .filter { it != intake.preferredLongRunDay }
                .maxWithOrNull(
                    compareBy<Int> { spacingScore(it, picked) }.thenByDescending { -it },
                ) ?: break
            picked += next
            available.remove(next)
        }
        return picked.sorted()
    }

    private fun spacingScore(day: Int, picked: List<Int>) = picked.minOf {
        min(kotlin.math.abs(day - it), 7 - kotlin.math.abs(day - it))
    }

    private fun elevateRisk(current: RiskRating, minimum: RiskRating) =
        if (current.ordinal >= minimum.ordinal) current else minimum

    private fun highestRisk(risks: List<RiskRating>) =
        risks.fold(RiskRating.CONSERVATIVE, ::elevateRisk)

    private fun oneDecimal(value: Double) = roundTrainingValueToOneDecimal(value)

    private fun longRunFloor(distance: RaceDistance) = mapOf(
        RaceDistance.FIVE_K to 4000,
        RaceDistance.TEN_K to 8000,
        RaceDistance.HALF to 16000,
        RaceDistance.MARATHON to 28000,
    ).getValue(distance)

    private fun nextPlanWeekStart(today: LocalDate): String {
        val current = DateUtils.weekStart(today)
        return if (current < today.toString()) DateUtils.addDays(current, 7) else today.toString()
    }

    private fun validateDays(days: List<Int>) {
        require(days.all { it in 0..6 } && days.distinct().size == days.size) {
            "Available run days must be unique weekdays from 0 through 6."
        }
    }

    private fun assertSupportedBaseline(intake: EstablishedTrainingIntake) {
        require(intake.currentWeeklyDistanceMeters >= 3000) {
            "The planner requires a current weekly baseline of at least 3 km."
        }
        require(intake.currentRunsPerWeek in 2..5) {
            "The planner requires a current baseline of 2 to 5 runs per week."
        }
        require(intake.longestRecentRunMeters > 0) {
            "The planner requires a positive recent long-run distance."
        }
    }

    private fun assertSchedulable(intake: EstablishedTrainingIntake) {
        validateDays(intake.availability)
        val valid = intake.availability.distinct().sorted()
        require(valid.size >= 2) { "Choose at least two unique available run days." }
        require(intake.preferredLongRunDay in valid) {
            "The preferred long-run day must be one of the available run days."
        }
        val count = plannedRuns(intake)
        require(valid.size >= count) {
            "Availability must include enough unique days for the planned run frequency."
        }
        val dayAfterLongRun = (intake.preferredLongRunDay + 1) % 7
        require(valid.count { it != dayAfterLongRun } >= count) {
            "Availability must leave a recovery day after the long run."
        }
    }

    private fun warnings(
        intake: EstablishedTrainingIntake,
        weeks: Int,
        required: Double,
        risk: RiskRating,
    ): List<String> = buildList {
        if (weeks < 8) {
            add("The target date is less than eight weeks away. A conservative plan may require a later date.")
        }
        if (intake.longestRecentRunMeters > intake.currentWeeklyDistanceMeters) {
            add("The longest recent run exceeds the reported weekly distance. Weekly distance is used as the baseline.")
        }
        if (risk == RiskRating.AGGRESSIVE || risk == RiskRating.UNSAFE) {
            add("The required weekly increase is above runway's default. Move the target date later or choose a shorter distance.")
        }
        if (required > 10) {
            add("Weekly distance growth above 10% is outside runway's default, not a normal target.")
        }
        if (intake.raceDistance == RaceDistance.HALF && intake.currentRunsPerWeek > 4) {
            add("Half-marathon plans are capped at four run days per week in this planner.")
        }
    }
}
