package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.deftmartian.runway.domain.GeneratedDistancePlan
import dev.deftmartian.runway.domain.OnboardingValidation
import dev.deftmartian.runway.domain.RaceDistance
import dev.deftmartian.runway.domain.RiskRating
import dev.deftmartian.runway.domain.StartMode
import dev.deftmartian.runway.domain.TargetDateBounds
import dev.deftmartian.runway.domain.TrainingAssessments
import dev.deftmartian.runway.domain.canLeaveRecoveryDayAfterLongRun
import dev.deftmartian.runway.domain.isRepeatableWeekCoherent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

private const val goalStep = 0
private const val startingPointStep = 1
private const val scheduleStep = 2
private const val reviewStep = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupScreen(
    payload: NativeOnboardingPayload?,
    actionPending: Boolean,
    onAction: (MobileCommand) -> Unit,
    onRestoreBackup: () -> Unit,
    nowProvider: () -> Instant = { Instant.now() },
) {
    val initial = payload?.initialValues
    var step by rememberSaveable { mutableIntStateOf(goalStep) }
    var goalKind by rememberSaveable(initial?.goalKind) {
        mutableStateOf(
            initial?.goalKind.orEmpty().ifBlank {
                if (initial?.startMode == "routine") "routine" else "race"
            },
        )
    }
    var startMode by rememberSaveable(initial?.startMode) {
        mutableStateOf(
            initial?.startMode
                ?.takeIf { it == "established" || it == "calibration" }
                ?: if (initial?.currentWeeklyDistanceKm.isNullOrBlank()) "calibration" else "established",
        )
    }
    var routineGoalKm by rememberSaveable(initial?.routineWeeklyGoalKm) {
        mutableStateOf(initial?.routineWeeklyGoalKm.orEmpty())
    }
    var routineGoalMinutes by rememberSaveable(initial?.routineWeeklyGoalMinutes) {
        mutableStateOf(initial?.routineWeeklyGoalMinutes.orEmpty())
    }
    var routineGoalMetric by rememberSaveable(initial?.routineGoalMetric) {
        mutableStateOf(initial?.routineGoalMetric?.takeIf { it in setOf("distance", "time") } ?: "distance")
    }
    var raceDistance by rememberSaveable { mutableStateOf(initial?.raceDistance.orEmpty().ifBlank { "5k" }) }
    var targetDate by rememberSaveable { mutableStateOf(initial?.targetDate.orEmpty()) }
    var priority by rememberSaveable { mutableStateOf(initial?.priority.orEmpty().ifBlank { "finish_healthy" }) }
    var weeklyKm by rememberSaveable { mutableStateOf(initial?.currentWeeklyDistanceKm.orEmpty()) }
    var weeklyMinutes by rememberSaveable { mutableStateOf(initial?.currentWeeklyMinutes.orEmpty()) }
    var runsPerWeek by rememberSaveable { mutableStateOf(initial?.currentRunsPerWeek.orEmpty()) }
    var longestKm by rememberSaveable { mutableStateOf(initial?.longestRecentRunKm.orEmpty()) }
    var routineChangeKm by rememberSaveable {
        mutableStateOf(initial?.routineWeeklyChangeKm.orEmpty().ifBlank { "1" })
    }
    var routineChangeMinutes by rememberSaveable {
        mutableStateOf(initial?.routineWeeklyChangeMinutes.orEmpty().ifBlank { "15" })
    }
    var preferredDay by rememberSaveable { mutableStateOf(initial?.preferredLongRunDay.orEmpty().ifBlank { "6" }) }
    var timeZone by rememberSaveable { mutableStateOf(initial?.timeZone.orEmpty().ifBlank { ZoneId.systemDefault().id }) }
    var availability by rememberSaveable {
        mutableStateOf(initial?.availability.orEmpty().distinct().ifEmpty { listOf(1, 3, 6) })
    }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    var confirmConcentrated by rememberSaveable { mutableStateOf(false) }
    var confirmedHighIncreaseKey by rememberSaveable { mutableStateOf<String?>(null) }
    var dateChangedDuringSetup by rememberSaveable { mutableStateOf(false) }
    var confirmingRestore by rememberSaveable { mutableStateOf(false) }
    var choosingTargetDate by rememberSaveable { mutableStateOf(false) }
    var choosingTimeZone by rememberSaveable { mutableStateOf(false) }
    val setupOperationScope = payload?.currentGoal?.id.orEmpty()
    val setupOperationId = rememberSaveable(setupOperationScope) { UUID.randomUUID().toString() }
    var setupOccurredAtEpochMillis by rememberSaveable(setupOperationScope) {
        mutableLongStateOf(nowProvider().toEpochMilli())
    }
    val setupInstant = Instant.ofEpochMilli(setupOccurredAtEpochMillis)
    val listState = rememberLazyListState()
    LaunchedEffect(step) {
        listState.scrollToItem(0)
    }

    val isRoutineGoal = goalKind == "routine"
    val isRaceGoal = !isRoutineGoal
    val effectiveStartMode = if (isRoutineGoal) "routine" else startMode
    val usesEnteredBaseline = isRoutineGoal || startMode == "established"
    val liveTargetBounds = setupTargetDateBounds(timeZone, effectiveStartMode, setupInstant)
    val minimumTargetDate = liveTargetBounds?.minimum ?: when (effectiveStartMode) {
        "calibration" -> payload?.minimumCalibrationTargetDate
        else -> payload?.minimumTargetDate
    }
    val maximumTargetDate = liveTargetBounds?.maximum ?: payload?.maximumTargetDate
    val goalIssue = if (isRoutineGoal) {
        routineWeeklyGoalValidation(
            routineGoalMetric,
            routineGoalKm,
            routineGoalMinutes,
            availability.size.coerceAtLeast(1),
        )
    } else {
        goalValidation(isRaceGoal, targetDate, minimumTargetDate, maximumTargetDate)
    }
    val startingPointIssue = if (isRoutineGoal) {
        routineStartingPointValidation(
            mode = "entered",
            metric = routineGoalMetric,
            weeklyKm = weeklyKm,
            weeklyMinutes = weeklyMinutes,
            runsPerWeek = runsPerWeek,
            scheduledRuns = availability.size.coerceAtLeast(1),
            goalKm = routineGoalKm,
            goalMinutes = routineGoalMinutes,
            changeKm = routineChangeKm,
            changeMinutes = routineChangeMinutes,
        )
    } else {
        startingPointValidation(
            if (usesEnteredBaseline) "established" else "learn",
            weeklyKm,
            runsPerWeek,
            longestKm,
        )
    }
    val scheduleIssue = scheduleValidation(
        mode = effectiveStartMode,
        availability = availability,
        runsPerWeek = runsPerWeek,
        preferredDay = preferredDay,
        timeZone = timeZone,
        raceDistance = raceDistance,
    ) ?: goalIssue.takeIf { isRoutineGoal }
        ?: startingPointIssue.takeIf { isRoutineGoal }
    val concentratedSchedule = requiresConcentratedScheduleAcceptance(
        startMode = effectiveStartMode,
        runsPerWeek = runsPerWeek,
        raceDistance = raceDistance,
    )
    val candidateCommand = CreatePlanCommand(
        goalKind = if (isRoutineGoal) "routine" else "race",
        startMode = effectiveStartMode,
        raceDistance = if (isRaceGoal) raceDistance else "",
        targetDate = if (isRaceGoal) targetDate else "",
        priority = priority,
        currentWeeklyDistanceKm = weeklyKm,
        currentRunsPerWeek = runsPerWeek,
        longestRecentRunKm = longestKm,
        availability = availability.sorted(),
        preferredLongRunDay = preferredDay,
        timeZone = timeZone,
        confirmConcentratedSchedule = confirmConcentrated,
        confirmedPlanKey = null,
        confirmReplace = confirmReplace,
        operationId = setupOperationId,
        occurredAtEpochMillis = setupOccurredAtEpochMillis,
        baselineMode = if (usesEnteredBaseline) "entered" else "learn",
        routineGoalMetric = routineGoalMetric,
        routineWeeklyGoalKm = if (isRoutineGoal) routineGoalKm else "",
        routineWeeklyGoalMinutes = if (isRoutineGoal) routineGoalMinutes else "",
        currentWeeklyMinutes = if (isRoutineGoal) weeklyMinutes else "",
        routineWeeklyChangeKm = if (isRoutineGoal) routineChangeKm else "",
        routineWeeklyChangeMinutes = if (isRoutineGoal) routineChangeMinutes else "",
    )
    val planPreview = remember(candidateCommand, setupOccurredAtEpochMillis) {
        if (startMode != "established" || !isRaceGoal) {
            null
        } else {
            (StandaloneOnboardingAdapter.preview(
                candidateCommand,
                setupInstant,
            ) as? StandaloneOnboardingOutcome.Planned)?.plan as? GeneratedDistancePlan
        }
    }
    val routinePreview = remember(candidateCommand, setupOccurredAtEpochMillis) {
        if (!isRoutineGoal) {
            null
        } else {
            StandaloneOnboardingAdapter.preview(candidateCommand, setupInstant)
                as? StandaloneOnboardingOutcome.Routine
        }
    }
    val highIncreaseKey = planPreview?.let(::generatedPlanConfirmationKey)
    val highIncreaseConfirmed =
        highIncreaseKey != null && confirmedHighIncreaseKey == highIncreaseKey
    val currentGoalLabel = currentGoalStateLabel(payload?.currentGoal?.state)
    val adjustingRoutine = initial?.goalKind == "routine" && isRoutineGoal
    val assessmentIssue = planAssessmentIssue(planPreview?.risk, highIncreaseConfirmed)
    val reviewIssue = when {
        assessmentIssue != null -> assessmentIssue
        payload?.currentGoal != null && !confirmReplace -> if (adjustingRoutine) {
            "Confirm that the current routine will move to History before the adjustment is applied."
        } else {
            "Confirm that the $currentGoalLabel will be archived."
        }
        concentratedSchedule && !confirmConcentrated -> "Confirm the two-day schedule before creating this plan."
        else -> null
    }
    val currentIssue = when (step) {
        goalStep -> goalIssue
        startingPointStep -> startingPointIssue
        scheduleStep -> scheduleIssue
        else -> reviewIssue
    }
    val continueSetup = {
        if (step < reviewStep) {
            step += 1
        } else {
            val submissionInstant = nowProvider()
            if (setupDateChanged(setupInstant, submissionInstant, timeZone)) {
                setupOccurredAtEpochMillis = submissionInstant.toEpochMilli()
                confirmedHighIncreaseKey = null
                dateChangedDuringSetup = true
                step = goalStep
            } else {
                onAction(
                    candidateCommand.copy(
                        confirmedPlanKey = highIncreaseKey.takeIf { highIncreaseConfirmed },
                        occurredAtEpochMillis = submissionInstant.toEpochMilli(),
                    ),
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            NativeList(
                loading = actionPending,
                state = listState,
            ) {
        item { SetupProgress(step) }
        if (dateChangedDuringSetup) {
            item {
                Notice("The date changed while Setup was open. Review the updated dates before creating the plan.")
            }
        }
        payload?.currentGoal?.let {
            item {
                val title = it.title.orEmpty().ifBlank { currentGoalLabel }
                Notice(if (adjustingRoutine) {
                    "Adjusting $title keeps the current routine in History and creates the revised progression after confirmation."
                } else if (it.state == "pending") {
                    "$title is saved as a pending goal. Replacing it keeps its record and archives it after confirmation."
                } else {
                    "Replacing $title keeps its history and archives the current plan after confirmation."
                })
            }
        }
        when (step) {
            goalStep -> {
                item {
                    SetupSection("Goal", "Choose what this setup should create.") {
                        ChoiceRow("Train for a distance", isRaceGoal) { goalKind = "race" }
                        ChoiceRow("Run consistently each week", isRoutineGoal) { goalKind = "routine" }
                    }
                }
                if (isRaceGoal) {
                    item {
                        SetupSection("Race target", "This is a planning target, not a promise about readiness.") {
                            listOf("5k" to "5K", "10k" to "10K", "half" to "Half marathon", "marathon" to "Marathon").forEach { (value, label) ->
                                ChoiceRow(label, raceDistance == value) { raceDistance = value }
                            }
                            OutlinedButton(
                                onClick = { choosingTargetDate = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = minimumTargetDate != null && maximumTargetDate != null,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    targetDate.takeIf(String::isNotBlank)?.let(::friendlyDate)
                                        ?: "Choose target date",
                                )
                            }
                            Text(
                                "Choose a date from ${minimumTargetDate.orDash()} to ${maximumTargetDate.orDash()}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            goalIssue?.let { ValidationText(it) }
                            ChoiceRow("Finish healthy", priority == "finish_healthy") { priority = "finish_healthy" }
                            ChoiceRow("Build consistency", priority == "consistency") { priority = "consistency" }
                        }
                    }
                }
                if (isRoutineGoal) {
                    item {
                        SetupSection(
                            "Final weekly goal",
                            "Choose the weekly distance or time you want the routine to reach.",
                        ) {
                            ChoiceRow("Distance", routineGoalMetric == "distance") {
                                routineGoalMetric = "distance"
                            }
                            ChoiceRow("Time", routineGoalMetric == "time") {
                                routineGoalMetric = "time"
                            }
                            if (routineGoalMetric == "distance") {
                                NumberField("Weekly distance goal (km)", routineGoalKm) {
                                    routineGoalKm = it
                                }
                            } else {
                                NumberField("Weekly running time (minutes)", routineGoalMinutes) {
                                    routineGoalMinutes = it
                                }
                            }
                            goalIssue?.let { ValidationText(it) }
                        }
                    }
                }
                if (payload?.currentGoal == null) {
                    item {
                        TextButton(
                            onClick = { confirmingRestore = true },
                            enabled = !actionPending,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Restore a backup instead")
                        }
                    }
                }
            }
            startingPointStep -> {
                item {
                    SetupSection("Starting point", "Choose how runway should understand your current running.") {
                        if (isRoutineGoal) {
                            Text(
                                "Enter a recent repeatable week so the routine starts from what you can do now.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            ChoiceRow("Enter a recent repeatable week", startMode == "established") {
                                startMode = "established"
                            }
                            ChoiceRow("Learn from two weeks of open runs", startMode == "calibration") {
                                startMode = "calibration"
                            }
                        }
                    }
                }
                if (usesEnteredBaseline) {
                    item {
                        SetupSection("Current running", "Use one recent week you could repeat now.") {
                            if (isRoutineGoal && routineGoalMetric == "time") {
                                NumberField("Repeatable weekly running time (minutes)", weeklyMinutes) {
                                    weeklyMinutes = it
                                }
                            } else {
                                NumberField("Repeatable weekly distance (km)", weeklyKm) { weeklyKm = it }
                            }
                            NumberField("Current runs per week", runsPerWeek) { runsPerWeek = it }
                            if (isRaceGoal) {
                                NumberField("Longest run in that week (km)", longestKm) { longestKm = it }
                            } else {
                                NumberField(
                                    if (routineGoalMetric == "time") {
                                        "Change each week (minutes)"
                                    } else {
                                        "Change each week (km)"
                                    },
                                    if (routineGoalMetric == "time") routineChangeMinutes else routineChangeKm,
                                ) {
                                    if (routineGoalMetric == "time") routineChangeMinutes = it else routineChangeKm = it
                                }
                                Text(
                                    "The weekly plan moves by this amount until it reaches your final goal.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (routineGoalMetric == "distance") {
                                    Text(
                                        "Planned runs use 0.5 km steps. Your reported starting week is rounded only for the plan.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            startingPointIssue?.let { ValidationText(it) }
                        }
                    }
                } else if (!isRoutineGoal) {
                    item {
                        Notice("Without a starting amount, the first week uses your final weekly goal. Enter a recent repeatable week if you want a week-by-week ramp.")
                    }
                }
            }
            scheduleStep -> {
                item {
                    SetupSection(
                        "Schedule",
                        if (isRoutineGoal) {
                            "Choose the days you intend to run most weeks. Runway divides the weekly ${routineGoalMetricLabel(routineGoalMetric)} goal across those days."
                        } else {
                            "Pick the days that are genuinely available. You can move future workouts later."
                        },
                    ) {
                        dayLabels.forEachIndexed { index, label ->
                            CheckRow(label, index in availability) { checked ->
                                availability = if (checked) (availability + index).distinct() else availability - index
                            }
                        }
                        if (isRaceGoal && startMode == "established") {
                            Text("Preferred long-run day", style = MaterialTheme.typography.labelLarge)
                            availability.sorted().forEach { day ->
                                ChoiceRow(dayLabels[day], preferredDay == day.toString()) { preferredDay = day.toString() }
                            }
                        }
                        OutlinedButton(
                            onClick = { choosingTimeZone = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text("Training time zone · ${timeZone.ifBlank { "Not set" }}")
                        }
                        Text(
                            "This controls which calendar day a run belongs to.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        scheduleIssue?.let { ValidationText(it) }
                    }
                }
            }
            reviewStep -> {
                item {
                    SetupSection("Review", "Check the outcome before runway creates anything.") {
                        SettingRow(
                            "Goal",
                            when {
                                isRoutineGoal -> routineGoalMeasurement(
                                    routineGoalMetric,
                                    routineGoalKm,
                                    routineGoalMinutes,
                                )
                                isRaceGoal -> raceDistanceLabel(raceDistance)
                                else -> raceDistanceLabel(raceDistance)
                            },
                        )
                        if (isRaceGoal) SettingRow("Target date", targetDate.ifBlank { "Not set" })
                        SettingRow(
                            if (isRoutineGoal) "Planned run days" else "Available days",
                            availability.sorted().joinToString { dayLabels[it].take(3) }.ifBlank { "None" },
                        )
                        SettingRow("Time zone", timeZone.ifBlank { "Not set" })
                        SettingRow("Starting point", if (usesEnteredBaseline) "Entered recent week" else "Learn from recorded runs")
                        Notice(
                            if (isRoutineGoal) {
                                if (routineGoalMetric == "time") {
                                    "Each week shows its total and each planned run. You can change a future week's total or move 5 minutes between its run days."
                                } else {
                                    "Each week shows its total and each planned run. You can change a future week's total or move 0.5 km between its run days."
                                }
                            } else if (startMode == "calibration") {
                                "Two weeks of open run days will be created. Distance and duration remain unprescribed until you confirm an observed baseline."
                            } else {
                                "An active distance plan will be created now. You can edit future workouts after setup."
                            },
                        )
                    }
                }
                routinePreview?.let { preview ->
                    item {
                        SetupSection(
                            "Weekly progression",
                            "The first week starts from your repeatable amount and moves toward the final goal.",
                        ) {
                            val outline = routineRampOutline(preview)
                            SettingRow("First week", outline.firstWeek)
                            SettingRow("Weekly change", outline.weeklyChange)
                            SettingRow("Final goal", outline.finalGoal)
                            SettingRow("Goal reached", outline.goalWeek)
                            outline.visibleWeeks.forEach { week ->
                                SettingRow("Week ${week.number}", week.measurement)
                            }
                            outline.finalWeekAfterGap?.let { week ->
                                Text(
                                    "…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SettingRow("Week ${week.number}", week.measurement)
                            }
                        }
                    }
                }
                planPreview?.let { preview ->
                    item {
                        SetupSection(
                            "Training outline",
                            "Training totals exclude the race itself. Review the ramp before creating the plan.",
                        ) {
                            SettingRow("Plan length", "${preview.weeks.size} weeks")
                            SettingRow(
                                "First week",
                                formatDistance(
                                    preview.weeks.first().trainingTargetDistanceMeters.toDouble(),
                                ),
                            )
                            SettingRow(
                                "Peak planned week",
                                formatDistance(preview.summary.peakMeters.toDouble()),
                            )
                            SettingRow(
                                "Longest planned run",
                                formatDistance(preview.summary.longRunPeakMeters.toDouble()),
                            )
                            SettingRow("Plan assessment", planAssessmentLabel(preview.risk))
                            SettingRow(
                                "Required and default",
                                TrainingAssessments.formatRampEvidence(
                                    preview.summary.requiredWeeklyIncreasePercent,
                                    preview.summary.defaultWeeklyIncreasePercent,
                                ),
                            )
                            preview.summary.warnings.forEach { warning -> Notice(warning) }
                        }
                    }
                    if (preview.risk == RiskRating.AGGRESSIVE) {
                        item {
                            Notice(
                                "At least one part of this plan is outside runway's usual recommendation. Review the ramp and warnings, move the race later if needed, or confirm the schedule as shown.",
                            )
                            CheckRow("Use this schedule as shown", highIncreaseConfirmed) { checked ->
                                confirmedHighIncreaseKey = if (checked) highIncreaseKey else null
                            }
                        }
                    }
                    if (preview.risk == RiskRating.UNSAFE) {
                        item {
                            Notice(
                                "This schedule is outside Runway's plan-generation limit. Move the race later, choose a shorter goal, or change the starting point.",
                            )
                        }
                    }
                }
                if (payload?.currentGoal != null) {
                    item {
                        CheckRow(
                            if (adjustingRoutine) {
                                "Keep the current routine in History and apply these adjustments"
                            } else {
                                "Archive the $currentGoalLabel and replace it"
                            },
                            confirmReplace,
                        ) { confirmReplace = it }
                    }
                }
                if (concentratedSchedule) {
                    item {
                        Notice("A half-marathon or marathon plan on two days concentrates the weekly distance into larger sessions. Add a day, choose another goal, or continue with this schedule.")
                        CheckRow("I understand this concentrates training into two days", confirmConcentrated) { confirmConcentrated = it }
                    }
                }
                reviewIssue?.let { item { ValidationText(it) } }
            }
            }
        }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (step > goalStep) {
                    OutlinedButton(
                        onClick = { step -= 1 },
                        modifier = Modifier.weight(1f),
                        enabled = !actionPending,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text("Back")
                    }
                }
                Button(
                    onClick = continueSetup,
                    enabled = !actionPending && currentIssue == null,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        if (actionPending) {
                            "Creating plan…"
                        } else if (step == reviewStep) {
                            when {
                                isRoutineGoal -> "Start routine"
                                else -> "Create plan"
                            }
                        } else {
                            "Continue"
                        },
                    )
                }
            }
        }
    }

    if (choosingTargetDate && minimumTargetDate != null && maximumTargetDate != null) {
        SetupTargetDateDialog(
            selectedDate = targetDate,
            minimumDate = minimumTargetDate,
            maximumDate = maximumTargetDate,
            onDismiss = { choosingTargetDate = false },
            onSelected = {
                targetDate = it
                choosingTargetDate = false
            },
        )
    }
    if (choosingTimeZone) {
        NativeTimeZonePicker(
            currentTimeZoneId = timeZone,
            onDismiss = { choosingTimeZone = false },
            onSelected = {
                timeZone = it
                choosingTimeZone = false
            },
        )
    }
    if (confirmingRestore) {
        RestoreBackupDialog(
            actionPending = actionPending,
            onDismiss = { confirmingRestore = false },
        ) {
            confirmingRestore = false
            onRestoreBackup()
        }
    }
}

internal fun currentGoalStateLabel(state: String?): String =
    if (state == "pending") "pending goal" else "current goal"

@Composable
private fun SetupProgress(currentStep: Int) {
    val labels = listOf("Goal", "Starting point", "Weekly schedule", "Review")
    val stepNumber = currentStep + 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Setup progress: step $stepNumber of ${labels.size}, ${labels[currentStep]}."
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Step $stepNumber of ${labels.size} · ${labels[currentStep]}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { stepNumber.toFloat() / labels.size },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SetupSection(title: String, body: String, content: @Composable () -> Unit) {
    SettingCard(title) {
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun ValidationText(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupTargetDateDialog(
    selectedDate: String,
    minimumDate: String,
    maximumDate: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val minimum = LocalDate.parse(minimumDate)
    val maximum = LocalDate.parse(maximumDate)
    val minimumMillis = minimum.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val maximumMillis = maximum.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val initialMillis = runCatching { LocalDate.parse(selectedDate) }
        .getOrNull()
        ?.takeIf { it in minimum..maximum }
        ?.atStartOfDay(ZoneOffset.UTC)
        ?.toInstant()
        ?.toEpochMilli()
        ?: minimumMillis
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis,
        yearRange = minimum.year..maximum.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis in minimumMillis..maximumMillis

            override fun isSelectableYear(year: Int): Boolean =
                year in minimum.year..maximum.year
        },
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onSelected(
                            Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toString(),
                        )
                    }
                },
                enabled = pickerState.selectedDateMillis != null,
            ) {
                Text("Use date")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    ) {
        DatePicker(
            state = pickerState,
            title = { Text("Target date") },
            showModeToggle = false,
        )
    }
}

internal fun setupTargetDateBounds(
    timeZone: String,
    startMode: String,
    now: Instant = Instant.now(),
): TargetDateBounds? {
    val mode = when (startMode) {
        "established" -> StartMode.ESTABLISHED
        "calibration" -> StartMode.CALIBRATION
        else -> return null
    }
    val zone = runCatching { ZoneId.of(timeZone) }.getOrNull() ?: return null
    return OnboardingValidation.targetDateBounds(now.atZone(zone).toLocalDate(), mode)
}

internal fun goalValidation(
    isRaceGoal: Boolean,
    targetDate: String,
    minimumTargetDate: String?,
    maximumTargetDate: String?,
): String? = when {
    !isRaceGoal -> null
    targetDate.isBlank() -> "Enter a target date to continue."
    runCatching { LocalDate.parse(targetDate) }.isFailure -> "Use a calendar date in YYYY-MM-DD form."
    minimumTargetDate != null && maximumTargetDate != null &&
        (targetDate < minimumTargetDate || targetDate > maximumTargetDate) ->
        "Choose a date from $minimumTargetDate to $maximumTargetDate."
    else -> null
}

internal fun routineWeeklyGoalValidation(
    metric: String,
    goalKm: String,
    goalMinutes: String,
    scheduledRuns: Int = 1,
): String? = when (metric) {
    "distance" -> {
        val range = (0.5 * scheduledRuns)..minOf(250.0, 100.0 * scheduledRuns)
        boundedRoutineGoalValidation(
            goalKm,
            range,
            "Enter a weekly distance goal to continue.",
            "Enter a weekly distance goal from ${formatRoutineNumber(range.start)} to ${routineDistanceMaximum(scheduledRuns)} km.",
        ) ?: "Use 0.5 km steps for the weekly goal."
            .takeUnless { goalKm.toDoubleOrNull()?.isHalfStep() == true }
    }
    "time" -> {
        boundedRoutineGoalValidation(
            goalMinutes,
            (10.0 * scheduledRuns)..(360.0 * scheduledRuns),
            "Enter a weekly running time to continue.",
            "Enter a weekly running time from ${routineWholeNumber(10 * scheduledRuns)} to ${routineWholeNumber(360 * scheduledRuns)} minutes.",
        ) ?: "Use 5-minute steps for the weekly goal."
            .takeUnless { goalMinutes.toDoubleOrNull()?.isFiveMinuteStep() == true }
    }
    else -> "Choose distance or time for the weekly goal."
}

private fun routineDistanceMaximum(scheduledRuns: Int) = minOf(250, 100 * scheduledRuns)

private fun formatRoutineNumber(value: Double) =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

private fun routineWholeNumber(value: Int) = String.format(Locale.US, "%,d", value)

private fun boundedRoutineGoalValidation(
    raw: String,
    range: ClosedFloatingPointRange<Double>,
    requiredMessage: String,
    rangeMessage: String,
): String? {
    val value = raw.toDoubleOrNull()
    return when {
        value == null || !value.isFinite() -> requiredMessage
        value !in range -> rangeMessage
        else -> null
    }
}

internal fun routineStartingPointValidation(
    mode: String,
    metric: String,
    weeklyKm: String,
    weeklyMinutes: String,
    runsPerWeek: String,
    scheduledRuns: Int,
    goalKm: String,
    goalMinutes: String,
    changeKm: String,
    changeMinutes: String,
): String? {
    if (mode != "entered") return null
    val runs = runsPerWeek.toIntOrNull()
    if (runs == null || runs !in 1..7) return "Enter a whole number from 1 to 7 current runs."
    return if (metric == "time") {
        routineTimeStartingPointIssue(
            weeklyMinutes,
            goalMinutes,
            changeMinutes,
            scheduledRuns,
        )
    } else {
        routineDistanceStartingPointIssue(weeklyKm, goalKm, changeKm, scheduledRuns)
    }
}

private fun routineTimeStartingPointIssue(
    weeklyMinutes: String,
    goalMinutes: String,
    changeMinutes: String,
    scheduledRuns: Int,
): String? {
    val baseline = weeklyMinutes.toDoubleOrNull()
    val minimum = 10.0 * scheduledRuns
    if (baseline == null || !baseline.isFinite() || baseline !in minimum..4_200.0) {
        return "Enter repeatable weekly running time from ${formatRoutineNumber(minimum)} to 4,200 minutes."
    }
    val changing = readableMinutes(baseline) != readableMinutes(goalMinutes.toDoubleOrNull())
    val validStep = changeMinutes.toDoubleOrNull()?.let { it > 0 && it.isFiveMinuteStep() } == true
    return "Use a positive 5-minute step for the weekly change."
        .takeIf { changing && !validStep }
}

private fun routineDistanceStartingPointIssue(
    weeklyKm: String,
    goalKm: String,
    changeKm: String,
    scheduledRuns: Int,
): String? {
    val baseline = weeklyKm.toDoubleOrNull()
    val minimum = 0.5 * scheduledRuns
    if (baseline == null || !baseline.isFinite() || baseline !in minimum..250.0) {
        return "Enter a repeatable week from ${formatRoutineNumber(minimum)} to 250 km."
    }
    val changing = readableKilometres(baseline) != readableKilometres(goalKm.toDoubleOrNull())
    val validStep = changeKm.toDoubleOrNull()?.let { it > 0 && it.isHalfStep() } == true
    return "Use a positive 0.5 km step for the weekly change."
        .takeIf { changing && !validStep }
}

private fun Double.isHalfStep() =
    isFinite() && kotlin.math.abs(this * 2 - kotlin.math.round(this * 2)) < 0.000_001

private fun Double.isFiveMinuteStep() =
    isFinite() && kotlin.math.abs(this / 5 - kotlin.math.round(this / 5)) < 0.000_001

private fun readableKilometres(value: Double?): Int? = value?.takeIf(Double::isFinite)?.let {
    kotlin.math.floor(it * 2 + .5).toInt()
}

private fun readableMinutes(value: Double?): Int? = value?.takeIf(Double::isFinite)?.let {
    kotlin.math.floor(it / 5 + .5).toInt()
}

internal data class RoutineRampWeekOutline(val number: Int, val measurement: String)

internal data class RoutineRampOutline(
    val firstWeek: String,
    val weeklyChange: String,
    val finalGoal: String,
    val goalWeek: String,
    val visibleWeeks: List<RoutineRampWeekOutline>,
    val finalWeekAfterGap: RoutineRampWeekOutline?,
)

internal fun routineRampOutline(preview: StandaloneOnboardingOutcome.Routine): RoutineRampOutline {
    val distance = preview.weeklyTargetDistanceMeters != null
    val start = if (distance) {
        requireNotNull(preview.startingWeeklyDistanceMeters)
    } else {
        requireNotNull(preview.startingWeeklyDurationSeconds)
    }
    val goal = if (distance) {
        requireNotNull(preview.weeklyTargetDistanceMeters)
    } else {
        requireNotNull(preview.weeklyTargetDurationSeconds)
    }
    val change = if (distance) {
        requireNotNull(preview.weeklyChangeDistanceMeters)
    } else {
        requireNotNull(preview.weeklyChangeDurationSeconds)
    }
    val direction = goal.compareTo(start)
    val goalWeekNumber = if (direction == 0) 1 else
        kotlin.math.ceil(kotlin.math.abs(goal - start).toDouble() / change).toInt() + 1
    fun measurement(amount: Int) = if (distance) {
        formatDistance(amount.toDouble())
    } else {
        formatPlannedDurationEstimate(amount.toDouble())
    }
    fun amountForWeek(number: Int): Int {
        if (direction == 0) return goal
        val moved = minOf(kotlin.math.abs(goal - start), change * (number - 1))
        return start + direction * moved
    }
    val visible = (1..minOf(6, goalWeekNumber)).map { number ->
        RoutineRampWeekOutline(number, measurement(amountForWeek(number)))
    }
    val changeLabel = when (direction) {
        1 -> "Increase by ${measurement(change)}"
        -1 -> "Decrease by ${measurement(change)}"
        else -> "No change"
    }
    return RoutineRampOutline(
        firstWeek = measurement(start),
        weeklyChange = changeLabel,
        finalGoal = measurement(goal),
        goalWeek = "Week $goalWeekNumber",
        visibleWeeks = visible,
        finalWeekAfterGap = goalWeekNumber.takeIf { it > visible.size }?.let { number ->
            RoutineRampWeekOutline(number, measurement(goal))
        },
    )
}

private fun routineGoalMetricLabel(metric: String) = if (metric == "time") "time" else "distance"

private fun routineGoalMeasurement(metric: String, distanceKm: String, durationMinutes: String): String =
    if (metric == "time") {
        durationMinutes.toDoubleOrNull()?.let { formatPlannedDurationEstimate(it * 60) }
            ?: "Weekly time not set"
    } else {
        distanceKm.toDoubleOrNull()?.let { formatDistance(it * 1_000) }
            ?: "Weekly distance not set"
    }

internal fun startingPointValidation(
    mode: String,
    weeklyKm: String,
    runsPerWeek: String,
    longestKm: String,
): String? = when {
    mode == "established" -> {
        val weekly = weeklyKm.toDoubleOrNull()
        val runs = runsPerWeek.toIntOrNull()
        val longest = longestKm.toDoubleOrNull()
        when {
            weekly?.takeIf { it in 3.0..250.0 } == null ->
                "Enter a repeatable week from 3 to 250 km."
            runs?.takeIf { it in 2..5 } == null ->
                "Enter a whole number from 2 to 5 current runs."
            longest?.takeIf { it > 0.0 && it <= 80.0 } == null ->
                "Enter a positive longest run in that week up to 80 km."
            !isRepeatableWeekCoherent(weekly, runs, longest) ->
                "Make the weekly distance fit the longest run and number of runs in that week."
            else -> null
        }
    }
    else -> null
}

internal fun planAssessmentIssue(
    risk: RiskRating?,
    confirmedHighIncrease: Boolean,
): String? = when (risk) {
    RiskRating.UNSAFE ->
        "Choose a later race date, a shorter goal, or a different starting point."
    RiskRating.AGGRESSIVE -> if (confirmedHighIncrease) {
        null
    } else {
        "Confirm the schedule after reviewing its warnings, or change the plan inputs."
    }
    else -> null
}

internal fun planAssessmentLabel(risk: RiskRating): String = when (risk) {
    RiskRating.CONSERVATIVE -> "Within default"
    RiskRating.MODERATE -> "Above default"
    RiskRating.AGGRESSIVE -> "Needs confirmation"
    RiskRating.UNSAFE -> "Not supported"
}

internal fun setupDateChanged(
    previewInstant: Instant,
    submissionInstant: Instant,
    timeZone: String,
): Boolean {
    val zone = runCatching { ZoneId.of(timeZone) }.getOrNull() ?: return false
    return previewInstant.atZone(zone).toLocalDate() != submissionInstant.atZone(zone).toLocalDate()
}

internal fun scheduleValidation(
    mode: String,
    availability: List<Int>,
    runsPerWeek: String,
    preferredDay: String,
    timeZone: String,
    raceDistance: String = "5k",
): String? {
    val requiredDays = when (mode) {
        "routine" -> 1
        else -> 2
    }
    return when {
        availability.distinct().size < requiredDays ->
            "Choose at least $requiredDays available ${if (requiredDays == 1) "day" else "days"}."
        runCatching { ZoneId.of(timeZone) }.isFailure -> "Enter a valid IANA time zone such as America/Halifax."
        mode == "established" &&
            preferredDay.toIntOrNull() !in availability -> "Choose an available long-run day."
        mode == "established" &&
            runsPerWeek.toIntOrNull()?.let { availability.size < it } == true ->
            "Choose at least as many available days as current weekly runs."
        mode == "established" &&
            runsPerWeek.toIntOrNull()?.let { runs ->
                preferredDay.toIntOrNull()?.let { longDay ->
                    !canLeaveRecoveryDayAfterLongRun(
                        availability,
                        runs,
                        longDay,
                        when (raceDistance) {
                            "half" -> RaceDistance.HALF
                            "marathon" -> RaceDistance.MARATHON
                            "10k" -> RaceDistance.TEN_K
                            else -> RaceDistance.FIVE_K
                        },
                    )
                }
            } == true ->
            "Choose another available day so the plan can leave the day after the long run free."
        else -> null
    }
}

internal fun requiresConcentratedScheduleAcceptance(
    startMode: String,
    runsPerWeek: String,
    raceDistance: String,
): Boolean = startMode == "established" &&
    runsPerWeek == "2" &&
    raceDistance in setOf("half", "marathon")

private fun raceDistanceLabel(value: String) = when (value) {
    "5k" -> "5K"
    "10k" -> "10K"
    "half" -> "Half marathon"
    "marathon" -> "Marathon"
    else -> value
}

private fun startModeLabel(value: String) = when (value) {
    "routine" -> "Weekly running routine"
    "established" -> "Repeatable current week"
    "calibration" -> "Two weeks of open runs"
    else -> value
}
