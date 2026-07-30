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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.deftmartian.runway.domain.OnboardingValidation
import dev.deftmartian.runway.domain.StartMode
import dev.deftmartian.runway.domain.TargetDateBounds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

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
) {
    val initial = payload?.initialValues
    var step by rememberSaveable { mutableIntStateOf(goalStep) }
    var startMode by rememberSaveable(initial?.startMode) {
        mutableStateOf(initial?.startMode.orEmpty().ifBlank { "foundation_only" })
    }
    var raceDistance by rememberSaveable { mutableStateOf(initial?.raceDistance.orEmpty().ifBlank { "5k" }) }
    var targetDate by rememberSaveable { mutableStateOf(initial?.targetDate.orEmpty()) }
    var priority by rememberSaveable { mutableStateOf(initial?.priority.orEmpty().ifBlank { "finish_healthy" }) }
    var weeklyKm by rememberSaveable { mutableStateOf(initial?.currentWeeklyDistanceKm.orEmpty()) }
    var runsPerWeek by rememberSaveable { mutableStateOf(initial?.currentRunsPerWeek.orEmpty()) }
    var longestKm by rememberSaveable { mutableStateOf(initial?.longestRecentRunKm.orEmpty()) }
    var experience by rememberSaveable { mutableStateOf(initial?.experience.orEmpty().ifBlank { "new" }) }
    var calibrationMinutes by rememberSaveable { mutableStateOf(initial?.calibrationDurationMinutes.orEmpty().ifBlank { "20" }) }
    var preferredDay by rememberSaveable { mutableStateOf(initial?.preferredLongRunDay.orEmpty().ifBlank { "6" }) }
    var timeZone by rememberSaveable { mutableStateOf(initial?.timeZone.orEmpty().ifBlank { ZoneId.systemDefault().id }) }
    var availability by rememberSaveable {
        mutableStateOf(initial?.availability.orEmpty().distinct().ifEmpty { listOf(1, 3, 6) })
    }
    var recentInjury by rememberSaveable { mutableStateOf(initial?.recentInjury ?: false) }
    var currentPain by rememberSaveable { mutableStateOf(initial?.currentPain ?: false) }
    var recurringPain by rememberSaveable { mutableStateOf(initial?.recurringPain ?: false) }
    var medicalRestriction by rememberSaveable { mutableStateOf(initial?.medicalRestriction ?: false) }
    var injuryNotes by rememberSaveable { mutableStateOf(initial?.injuryNotes.orEmpty()) }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    var confirmConcentrated by rememberSaveable { mutableStateOf(false) }
    var choosingTargetDate by rememberSaveable { mutableStateOf(false) }
    var choosingTimeZone by rememberSaveable { mutableStateOf(false) }
    var healthContextExpanded by rememberSaveable {
        mutableStateOf(
            recentInjury ||
                currentPain ||
                recurringPain ||
                medicalRestriction ||
                injuryNotes.isNotBlank(),
        )
    }
    val listState = rememberLazyListState()

    LaunchedEffect(step) {
        listState.scrollToItem(0)
    }

    val isRaceGoal = startMode != "foundation_only"
    val liveTargetBounds = setupTargetDateBounds(timeZone, startMode)
    val minimumTargetDate = liveTargetBounds?.minimum ?: when (startMode) {
        "foundation_to_goal" -> payload?.minimumFoundationTargetDate
        "calibration" -> payload?.minimumCalibrationTargetDate
        else -> payload?.minimumTargetDate
    }
    val maximumTargetDate = liveTargetBounds?.maximum ?: payload?.maximumTargetDate
    val goalIssue = goalValidation(isRaceGoal, targetDate, minimumTargetDate, maximumTargetDate)
    val startingPointIssue = startingPointValidation(
        startMode, weeklyKm, runsPerWeek, longestKm, calibrationMinutes,
        healthBlocked = currentPain || medicalRestriction,
    )
    val scheduleIssue = scheduleValidation(
        mode = startMode,
        availability = availability,
        runsPerWeek = runsPerWeek,
        preferredDay = preferredDay,
        timeZone = timeZone,
        healthBlocked = currentPain || medicalRestriction,
    )
    val concentratedSchedule = requiresConcentratedScheduleAcceptance(
        startMode = startMode,
        runsPerWeek = runsPerWeek,
        raceDistance = raceDistance,
        healthBlocked = currentPain || medicalRestriction,
    )
    val reviewIssue = when {
        payload?.activeGoal != null && !confirmReplace -> "Confirm that the current goal will be archived."
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
            onAction(CreatePlanCommand(
                goalKind = if (isRaceGoal) "race" else "foundation", startMode = startMode,
                raceDistance = if (isRaceGoal) raceDistance else "", targetDate = if (isRaceGoal) targetDate else "",
                priority = priority, currentWeeklyDistanceKm = weeklyKm, currentRunsPerWeek = runsPerWeek,
                longestRecentRunKm = longestKm, experience = experience, calibrationDurationMinutes = calibrationMinutes,
                availability = availability.sorted(), preferredLongRunDay = preferredDay, timeZone = timeZone,
                recentInjury = recentInjury, currentPain = currentPain, recurringPain = recurringPain,
                medicalRestriction = medicalRestriction, injuryNotes = injuryNotes,
                confirmConcentratedSchedule = confirmConcentrated, confirmReplace = confirmReplace,
            ))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NativeList(
            loading = actionPending,
            state = listState,
            bottomContentPadding = 92.dp,
        ) {
        item { SetupProgress(step) }
        payload?.activeGoal?.let {
            item {
                Notice("A new plan archives ${it.title.orEmpty().ifBlank { "the current goal" }} after you confirm it in Review.")
            }
        }
        when (step) {
            goalStep -> {
                item {
                    SetupSection("Goal", "Choose what this setup should create.") {
                        ChoiceRow("Build a foundation", !isRaceGoal) { startMode = "foundation_only" }
                        ChoiceRow("Prepare for a race", isRaceGoal) {
                            if (!isRaceGoal) startMode = "foundation_to_goal"
                        }
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
            }
            startingPointStep -> {
                if (isRaceGoal) {
                    item {
                        SetupSection("Starting point", "Use the week you could repeat now. This changes the recommendation; it does not limit your later choices.") {
                            ChoiceRow("Foundation before the race", startMode == "foundation_to_goal") { startMode = "foundation_to_goal" }
                            ChoiceRow("Use a repeatable current week", startMode == "established") { startMode = "established" }
                            ChoiceRow("Two-week timed check-in", startMode == "calibration") { startMode = "calibration" }
                        }
                    }
                }
                item {
                    SetupSection("Current running", "Choose the description that fits best.") {
                        listOf("new" to "New runner", "returning" to "Returning after time away", "comfortable" to "Running comfortably").forEach { (value, label) ->
                            ChoiceRow(label, experience == value) { experience = value }
                        }
                        if (startMode == "established") {
                            NumberField("Repeatable weekly distance (km)", weeklyKm) { weeklyKm = it }
                            NumberField("Current runs per week", runsPerWeek) { runsPerWeek = it }
                            NumberField("Longest recent run (km)", longestKm) { longestKm = it }
                        }
                        if (startMode == "calibration") {
                            NumberField("Timed run duration (10–30 min)", calibrationMinutes) { calibrationMinutes = it }
                        }
                        startingPointIssue?.let { ValidationText(it) }
                    }
                }
                item {
                    SetupSection(
                        "Health and training limits",
                        "Optional. Add anything that should affect whether training starts now.",
                    ) {
                        if (!healthContextExpanded) {
                            SettingRow(
                                "Current",
                                healthContextSummary(
                                    recentInjury = recentInjury,
                                    currentPain = currentPain,
                                    recurringPain = recurringPain,
                                    medicalRestriction = medicalRestriction,
                                ),
                            )
                            OutlinedButton(
                                onClick = { healthContextExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    if (
                                        recentInjury ||
                                        currentPain ||
                                        recurringPain ||
                                        medicalRestriction ||
                                        injuryNotes.isNotBlank()
                                    ) {
                                        "Review health context"
                                    } else {
                                        "Add health context"
                                    },
                                )
                            }
                        } else {
                            CheckRow("A recent injury still affects training", recentInjury) { recentInjury = it }
                            CheckRow("Pain is present now", currentPain) { currentPain = it }
                            CheckRow("Pain tends to return while running", recurringPain) { recurringPain = it }
                            CheckRow("A clinician has limited current training", medicalRestriction) { medicalRestriction = it }
                            if (currentPain || medicalRestriction) {
                                Notice("runway will save the goal without scheduling workouts while pain is present or a clinician has limited training.")
                            } else if (recentInjury || recurringPain) {
                                Notice("This context stays visible beside the recommendation; it does not diagnose or prescribe.")
                            }
                            OutlinedTextField(
                                value = injuryNotes,
                                onValueChange = { injuryNotes = it.take(240) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Private note (optional)") },
                                minLines = 2,
                            )
                            TextButton(
                                onClick = { healthContextExpanded = false },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Done")
                            }
                        }
                    }
                }
            }
            scheduleStep -> {
                item {
                    SetupSection("Schedule", "Pick the days that are genuinely available. You can move future workouts later.") {
                        dayLabels.forEachIndexed { index, label ->
                            CheckRow(label, index in availability) { checked ->
                                availability = if (checked) (availability + index).distinct() else availability - index
                            }
                        }
                        if (startMode == "established") {
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
                        SettingRow("Goal", if (isRaceGoal) raceDistanceLabel(raceDistance) else "30 minutes of continuous easy running")
                        if (isRaceGoal) SettingRow("Target date", targetDate.ifBlank { "Not set" })
                        SettingRow("Starting point", startModeLabel(startMode))
                        SettingRow("Available days", availability.sorted().joinToString { dayLabels[it].take(3) }.ifBlank { "None" })
                        SettingRow("Time zone", timeZone.ifBlank { "Not set" })
                        if (currentPain || medicalRestriction) {
                            Notice("Goal stays pending — no active workouts will be created now.")
                        } else {
                            Notice("An active ${if (startMode == "calibration") "two-week calibration" else "training"} phase will be created now. You can edit future workouts after setup.")
                        }
                    }
                }
                if (payload?.activeGoal != null) {
                    item { CheckRow("Archive the current goal and replace it", confirmReplace) { confirmReplace = it } }
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
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
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
                            "Create plan"
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
}

@Composable
private fun SetupProgress(currentStep: Int) {
    val labels = listOf("Goal", "Starting point", "Schedule", "Review")
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
        "foundation_to_goal" -> StartMode.FOUNDATION_TO_GOAL
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

internal fun startingPointValidation(
    mode: String,
    weeklyKm: String,
    runsPerWeek: String,
    longestKm: String,
    calibrationMinutes: String,
    healthBlocked: Boolean,
): String? = when {
    mode == "established" && healthBlocked -> null
    mode == "established" -> when {
        weeklyKm.toDoubleOrNull()?.takeIf { it in 3.0..250.0 } == null ->
            "Enter a repeatable week from 3 to 250 km."
        runsPerWeek.toIntOrNull()?.takeIf { it in 2..5 } == null ->
            "Enter a whole number from 2 to 5 current runs."
        longestKm.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 80.0 } == null ->
            "Enter a positive recent longest run up to 80 km."
        else -> null
    }
    mode == "calibration" && calibrationMinutes.toIntOrNull()?.takeIf { it in 10..30 } == null ->
        "Choose a whole timed check-in from 10 to 30 minutes."
    else -> null
}

internal fun scheduleValidation(
    mode: String,
    availability: List<Int>,
    runsPerWeek: String,
    preferredDay: String,
    timeZone: String,
    healthBlocked: Boolean,
): String? {
    val requiredDays = if (mode == "foundation_to_goal" || mode == "foundation_only") 3 else 2
    return when {
        availability.distinct().size < requiredDays -> "Choose at least $requiredDays available days."
        runCatching { ZoneId.of(timeZone) }.isFailure -> "Enter a valid IANA time zone such as America/Halifax."
        mode == "established" && !healthBlocked &&
            preferredDay.toIntOrNull() !in availability -> "Choose an available long-run day."
        mode == "established" && !healthBlocked &&
            runsPerWeek.toIntOrNull()?.let { availability.size < it } == true ->
            "Choose at least as many available days as current weekly runs."
        else -> null
    }
}

internal fun requiresConcentratedScheduleAcceptance(
    startMode: String,
    runsPerWeek: String,
    raceDistance: String,
    healthBlocked: Boolean,
): Boolean = !healthBlocked &&
    startMode == "established" &&
    runsPerWeek == "2" &&
    raceDistance in setOf("half", "marathon")

internal fun healthContextSummary(
    recentInjury: Boolean,
    currentPain: Boolean,
    recurringPain: Boolean,
    medicalRestriction: Boolean,
): String = listOfNotNull(
    "Recent injury".takeIf { recentInjury },
    "Pain now".takeIf { currentPain },
    "Recurring pain".takeIf { recurringPain },
    "Training limited".takeIf { medicalRestriction },
).joinToString().ifBlank { "Nothing noted" }

private fun raceDistanceLabel(value: String) = when (value) {
    "5k" -> "5K"
    "10k" -> "10K"
    "half" -> "Half marathon"
    "marathon" -> "Marathon"
    else -> value
}

private fun startModeLabel(value: String) = when (value) {
    "foundation_only" -> "Nine-week run/walk foundation"
    "foundation_to_goal" -> "Foundation before the race"
    "established" -> "Repeatable current week"
    "calibration" -> "Two-week timed check-in"
    else -> value
}
