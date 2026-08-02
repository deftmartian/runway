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
        is FoundationIntake -> generateFoundation(intake, today)
        is CalibrationIntake -> generateCalibration(intake, today)
    }
    fun hasInjuryRiskFlags(flags: InjuryFlags) =
        flags.recentInjury || flags.currentPain || flags.recurringPain || flags.medicalRestriction

    fun classifyRamp(requiredWeeklyIncreasePercent: Double, hasInjuryRisk: Boolean): RiskRating {
        val offset = if (hasInjuryRisk) 2 else 0
        return when {
            requiredWeeklyIncreasePercent <= 8 - offset -> RiskRating.CONSERVATIVE
            requiredWeeklyIncreasePercent <= 12 - offset -> RiskRating.MODERATE
            requiredWeeklyIncreasePercent <= 18 - offset -> RiskRating.AGGRESSIVE
            else -> RiskRating.UNSAFE
        }
    }

    fun generateEstablished(intake: EstablishedTrainingIntake, today: LocalDate = LocalDate.now()): GeneratedDistancePlan {
        assertSupportedBaseline(intake)
        val start = intake.startDate ?: nextPlanWeekStart(today)
        val planDays = DateUtils.daysBetween(start, intake.targetDate) + 1
        require(planDays >= 1) { "The target date must be on or after the plan start date." }
        val availableWeeks = max(1, ceil(planDays / 7.0).toInt())
        require(availableWeeks <= maxPlanWeeks) { "Training plans cannot exceed 52 weeks." }
        assertSchedulable(intake)
        val taper = min(taperWeeks.getValue(intake.raceDistance), max(1, availableWeeks - 1))
        val buildWeeks = max(1, availableWeeks - taper)
        val injuryRisk = hasInjuryRiskFlags(intake.injuryFlags)
        val peak = peakWeekly.getValue(intake.raceDistance)
        val longPeak = peakLong.getValue(intake.raceDistance)
        val initialLong = min(intake.longestRecentRunMeters, min(intake.currentWeeklyDistanceMeters, longPeak))
        val required = max(requiredBuildRamp(intake.currentWeeklyDistanceMeters, peak, buildWeeks), requiredBuildRamp(initialLong, longPeak, buildWeeks))
        val initialRisk = if (availableWeeks < 8 && intake.currentWeeklyDistanceMeters < peak * .55) RiskRating.UNSAFE else classifyRamp(required, injuryRisk)
        val warnings = warnings(intake, availableWeeks, required, initialRisk).toMutableList()
        val defaultRampPercent = priorityRampCap(intake) * 100
        val normalRamp = min(required, defaultRampPercent) / 100
        val weeks = mutableListOf<GeneratedWeek>()
        var previousDistance = intake.currentWeeklyDistanceMeters
        var previousLong = initialLong
        var peakDistance = previousDistance
        var peakTrainingLong = previousLong
        repeat(availableWeeks) { index ->
            val number = index + 1
            val isTaper = number > availableWeeks - taper
            val down = !isTaper && number % 4 == 0
            val taperPosition = if (isTaper) number - (availableWeeks - taper) else 0
            val weekStart = DateUtils.addDays(start, (index * 7).toLong())
            val nominalEnd = DateUtils.addDays(weekStart, 6)
            val weekEnd = if (nominalEnd < intake.targetDate) nominalEnd else intake.targetDate
            val raceWeek = intake.targetDate >= weekStart && intake.targetDate <= weekEnd
            val rampedDistance = if (number == 1) {
                intake.currentWeeklyDistanceMeters
            } else {
                roundTrainingValueToInt(previousDistance * (1 + normalRamp))
            }
            val buildDistance = min(peak, rampedDistance)
            val budget = when {
                isTaper -> taperTarget(peakDistance, taperPosition, taper)
                down -> roundTrainingValueToInt(buildDistance * .85)
                else -> buildDistance
            }
            val rampLong = if (number == 1) initialLong else roundTrainingValueToInt(previousLong * (1 + normalRamp))
            val adjustedLong = if (down) roundTrainingValueToInt(rampLong * .85) else rampLong
            val desiredLong = if (isTaper) {
                taperTarget(peakTrainingLong, taperPosition, taper)
            } else {
                min(longPeak, adjustedLong)
            }
            val scheduled = createWeekWorkouts(intake, weekStart, weekEnd, budget, desiredLong, if (raceWeek) intake.targetDate else null)
            val training = scheduled.workouts.filter { it.type != WorkoutType.RACE }.sumOf { it.targetDistanceMeters }
            val event = scheduled.workouts.filter { it.type == WorkoutType.RACE }.sumOf { it.targetDistanceMeters }
            val longRunRamp = if (scheduled.longRun > 0) {
                percentIncrease(previousLong, scheduled.longRun)
            } else {
                0.0
            }
            val risk = classifyRamp(max(percentIncrease(previousDistance, training), longRunRamp), injuryRisk)
            val totalDistance = scheduled.workouts.sumOf { it.targetDistanceMeters }
            val totalDuration = scheduled.workouts.sumOf { it.targetDurationSeconds ?: 0 }
            weeks += GeneratedWeek(
                number,
                weekStart,
                training,
                event,
                totalDistance,
                totalDuration,
                scheduled.longRun,
                risk,
                down,
                isTaper,
                scheduled.workouts,
            )
            previousDistance = training
            if (scheduled.longRun > 0) previousLong = scheduled.longRun
            if (!isTaper) {
                peakDistance = max(peakDistance, training)
                peakTrainingLong = max(peakTrainingLong, scheduled.longRun)
            }
        }
        val generatedPeak = weeks.maxOf { it.trainingTargetDistanceMeters }
        val generatedLong = weeks.maxOf { it.longRunMeters }
        if (generatedPeak < peak * .95) warnings += "The available weeks do not allow the usual peak distance for this goal. Move the target date or choose a shorter distance."
        val readiness = generatedLong < longRunFloor(intake.raceDistance)
        if (readiness) warnings += "The longest planned run is low for this race distance. Keep the goal completion-oriented or move the date."
        val marathonShortfall = intake.raceDistance == RaceDistance.MARATHON && (intake.currentWeeklyDistanceMeters < 32000 || intake.longestRecentRunMeters < 20000)
        if (marathonShortfall) warnings += "Marathon goals need a stronger recent base than shorter races. Build consistency first or move the date."
        val concentrated = (intake.raceDistance == RaceDistance.HALF || intake.raceDistance == RaceDistance.MARATHON) && plannedRuns(intake) < 3
        if (concentrated) warnings += "Two run days concentrate a high-volume goal into unusually large sessions. Add a third available day, choose a shorter goal, or explicitly accept the higher load concentration."
        var risk = highestRisk(listOf(initialRisk, highestRisk(weeks.map { it.risk })))
        if (marathonShortfall) risk = elevateRisk(risk, RiskRating.UNSAFE) else if (readiness) risk = elevateRisk(risk, RiskRating.AGGRESSIVE)
        if (concentrated) risk = elevateRisk(risk, RiskRating.AGGRESSIVE)
        val summary = DistanceSummary(
            intake.currentWeeklyDistanceMeters,
            generatedPeak,
            oneDecimal(required),
            oneDecimal(defaultRampPercent),
            generatedLong,
            warnings,
        )
        val sources = listOf(
            TrainingSourceRefs.MAYO_INJURY_AVOIDANCE,
            TrainingSourceRefs.MAYO_TAPER,
            raceRefs.getValue(intake.raceDistance),
            TrainingSourceRefs.RRCA,
            TrainingSourceRefs.NIAMS,
        )
        return GeneratedDistancePlan(start, intake.targetDate, weeks, risk, summary, sources)
    }

    fun generateFoundation(intake: FoundationIntake, today: LocalDate = LocalDate.now()): GeneratedFoundationPlan {
        assertPhaseCanStart(intake.injuryFlags)
        val start = intake.startDate ?: nextPlanWeekStart(today)
        val days = pickPhaseDays(intake.availability, 3)
        val weeks = foundationSessions.mapIndexed { index, sessions ->
            val weekStart = DateUtils.addDays(start, (index * 7).toLong())
            val workouts = phaseWeekWorkouts(weekStart, days) { session, date ->
                val blocks = sessions[session].map { block ->
                    block.copy(segments = block.segments.toList())
                }
                val total = 600 + blocksDuration(blocks)
                GeneratedWorkout(
                    date,
                    WorkoutType.EASY,
                    0,
                    WorkoutPrescription.Timed(total, 300, 300, blocks),
                    "easy",
                    "Foundation run/walk ${session + 1}",
                    "NHS Couch to 5K week ${index + 1}. Keep each running interval comfortable.",
                    listOf(TrainingSourceRefs.NHS_COUCH_TO_5K, TrainingSourceRefs.MAYO_BEGINNER_RUN_WALK),
                    total,
                )
            }
            timedWeek(index + 1, weekStart, workouts)
        }
        val warnings = mutableListOf(
            "Completion provides observed training data for confirmation; it does not create a race baseline automatically.",
        ) + timedHealthWarnings(intake.injuryFlags)
        val sources = listOf(
            TrainingSourceRefs.NHS_COUCH_TO_5K,
            TrainingSourceRefs.MAYO_BEGINNER_RUN_WALK,
        )
        return GeneratedFoundationPlan(
            start,
            DateUtils.addDays(start, 62),
            weeks,
            RiskRating.CONSERVATIVE,
            intake.startMode,
            FoundationSummary(warnings = warnings),
            sources,
        )
    }

    fun generateCalibration(intake: CalibrationIntake, today: LocalDate = LocalDate.now()): GeneratedCalibrationPlan {
        assertPhaseCanStart(intake.injuryFlags)
        require(intake.calibrationDurationSeconds in 600..1800) {
            "Calibration duration must be a whole number of seconds from 10 to 30 minutes."
        }
        val start = intake.startDate ?: nextPlanWeekStart(today)
        val days = pickPhaseDays(intake.availability, 2)
        val blocks = calibrationBlocks(intake.calibrationDurationSeconds - 240)
        val weeks = (0..1).map { index ->
            val weekStart = DateUtils.addDays(start, (index * 7).toLong())
            val workouts = phaseWeekWorkouts(weekStart, days) { session, date ->
                GeneratedWorkout(
                    date,
                    WorkoutType.EASY,
                    0,
                    WorkoutPrescription.Timed(intake.calibrationDurationSeconds, 120, 120, blocks),
                    "easy",
                    "Calibration run/walk ${session + 1}",
                    "Repeat the same comfortable time. Distance is observed, not prescribed.",
                    listOf(TrainingSourceRefs.MAYO_BEGINNER_RUN_WALK),
                    intake.calibrationDurationSeconds,
                )
            }
            timedWeek(index + 1, weekStart, workouts)
        }
        val warnings = mutableListOf(
            "Distance remains observational until the runner confirms the completed activities as a baseline.",
        ) + timedHealthWarnings(intake.injuryFlags)
        return GeneratedCalibrationPlan(
            start,
            DateUtils.addDays(start, 13),
            weeks,
            RiskRating.CONSERVATIVE,
            CalibrationSummary(sessionDurationSeconds = intake.calibrationDurationSeconds, warnings = warnings),
            listOf(TrainingSourceRefs.MAYO_BEGINNER_RUN_WALK),
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
            val raceWeekTrainingBudget = roundTrainingValueToInt(
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

        val minimum = ceil(total / weekdays.size.toDouble()).toInt()
        val maximum = max(minimum, total - minimumRunMeters * max(0, weekdays.size - 1))
        val long = min(maximum, max(minimum, desired))
        val easy = allocateEvenly(total - long, weekdays.size - 1).first
        var easyIndex = 0
        val distances = weekdays.indices.map { index ->
            if (index == longIndex) long else easy[easyIndex++]
        }
        return distances to long
    }

    private fun allocateEvenly(total: Int, count: Int): Pair<List<Int>, Int> {
        if (count <= 0) return emptyList<Int>() to 0
        val base = floor(total / count.toDouble()).toInt()
        var remaining = total - base * count
        val allocations = List(count) { base + if (remaining-- > 0) 1 else 0 }
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

    private fun calibrationBlocks(active: Int): List<RunWalkBlock> {
        val repeats = active / 150
        val remainder = active - repeats * 150
        return buildList {
            if (repeats > 0) add(RunWalkBlock(repeats, listOf(run(60), walk(90))))
            if (remainder > 0) {
                val finalSegments = if (remainder <= 60) {
                    listOf(run(remainder))
                } else {
                    listOf(run(60), walk(remainder - 60))
                }
                add(RunWalkBlock(1, finalSegments))
            }
        }
    }

    private fun blocksDuration(blocks: List<RunWalkBlock>) =
        blocks.sumOf { block ->
            block.repetitions * block.segments.sumOf { segment -> segment.durationSeconds }
        }

    private fun run(seconds: Int) = PrescriptionSegment(SegmentKind.RUN, seconds)

    private fun walk(seconds: Int) = PrescriptionSegment(SegmentKind.WALK, seconds)

    private fun requiredBuildRamp(start: Int, goal: Int, buildWeeks: Int): Double {
        if (buildWeeks <= 1 || start >= goal) return 0.0
        val cutback = .85.pow((buildWeeks / 4).toDouble())
        return ((goal.toDouble() / start / cutback).pow(1.0 / (buildWeeks - 1)) - 1) * 100
    }

    private fun priorityRampCap(intake: EstablishedTrainingIntake): Double {
        val baseCap = if (intake.priority == GoalPriority.FINISH_HEALTHY) .075 else .1
        val injuryAdjustment = if (hasInjuryRiskFlags(intake.injuryFlags)) .02 else 0.0
        return max(.04, baseCap - injuryAdjustment)
    }

    private fun taperTarget(peak: Int, position: Int, length: Int): Int {
        val fraction = when {
            length == 1 -> .5
            position == 1 -> .6
            position == 2 -> .45
            else -> .4
        }
        return roundTrainingValueToInt(peak * fraction)
    }

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

    private fun assertPhaseCanStart(flags: InjuryFlags) {
        require(!flags.currentPain) { "A workout phase cannot start while pain is present now." }
        require(!flags.medicalRestriction) {
            "A workout phase cannot start while a clinician has limited running."
        }
    }

    private fun timedHealthWarnings(flags: InjuryFlags) =
        if (!flags.recentInjury && !flags.recurringPain) {
            emptyList()
        } else {
            listOf(
                "Recent injury or recurring pain is noted with this plan. It does not change the timed prescription or assess whether running is appropriate.",
            )
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
        assertPhaseCanStart(intake.injuryFlags)
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
        if (hasInjuryRiskFlags(intake.injuryFlags)) {
            add("Injury recovery or recurring pain is included in the ramp assessment. Get qualified guidance if pain persists, worsens, or changes how you move.")
        }
        if (required > 10) {
            add("Weekly distance growth above 10% is outside runway's default, not a normal target.")
        }
        if (intake.raceDistance == RaceDistance.HALF && intake.currentRunsPerWeek > 4) {
            add("Half-marathon plans are capped at four run days per week in this planner.")
        }
    }

    private val foundationSessions: List<List<List<RunWalkBlock>>> = listOf(
        List(3) {
            listOf(
                RunWalkBlock(7, listOf(run(60), walk(90))),
                RunWalkBlock(1, listOf(run(60))),
            )
        },
        List(3) {
            listOf(
                RunWalkBlock(5, listOf(run(90), walk(120))),
                RunWalkBlock(1, listOf(run(90))),
            )
        },
        List(3) {
            listOf(
                RunWalkBlock(
                    1,
                    listOf(run(90), walk(90), run(180), walk(180), run(90), walk(90), run(180)),
                ),
            )
        },
        List(3) {
            listOf(
                RunWalkBlock(
                    1,
                    listOf(run(180), walk(90), run(300), walk(150), run(180), walk(90), run(300)),
                ),
            )
        },
        listOf(
            listOf(RunWalkBlock(1, listOf(run(300), walk(180), run(300), walk(180), run(300)))),
            listOf(RunWalkBlock(1, listOf(run(480), walk(300), run(480)))),
            listOf(RunWalkBlock(1, listOf(run(1200)))),
        ),
        listOf(
            listOf(RunWalkBlock(1, listOf(run(300), walk(180), run(480), walk(180), run(300)))),
            listOf(RunWalkBlock(1, listOf(run(600), walk(180), run(600)))),
            listOf(RunWalkBlock(1, listOf(run(1500)))),
        ),
        List(3) { listOf(RunWalkBlock(1, listOf(run(1500)))) },
        List(3) { listOf(RunWalkBlock(1, listOf(run(1680)))) },
        List(3) { listOf(RunWalkBlock(1, listOf(run(1800)))) },
    )
}
