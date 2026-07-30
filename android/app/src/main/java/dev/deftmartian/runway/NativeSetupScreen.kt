package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId

private const val goalStep = 0
private const val startingPointStep = 1
private const val scheduleStep = 2
private const val reviewStep = 3

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

    val isRaceGoal = startMode != "foundation_only"
    val minimumTargetDate = when (startMode) {
        "foundation_to_goal" -> payload?.minimumFoundationTargetDate
        "calibration" -> payload?.minimumCalibrationTargetDate
        else -> payload?.minimumTargetDate
    }
    val goalIssue = goalValidation(isRaceGoal, targetDate, minimumTargetDate, payload?.maximumTargetDate)
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

    NativeList(actionPending) {
        item {
            ScreenIntro(
                "Set up your plan",
                "Choose a useful default, then keep control of every later training decision.",
            )
        }
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
                            OutlinedTextField(
                                value = targetDate,
                                onValueChange = { targetDate = it.take(10) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Target date (YYYY-MM-DD)") },
                                singleLine = true,
                                isError = goalIssue != null,
                            )
                            Text("Available range: ${minimumTargetDate.orDash()} to ${payload?.maximumTargetDate.orDash()}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            goalIssue?.let { ValidationText(it) }
                            ChoiceRow("Finish healthy", priority == "finish_healthy") { priority = "finish_healthy" }
                            ChoiceRow("Build consistency", priority == "consistency") { priority = "consistency" }
                        }
                    }
                }
            }
            startingPointStep -> {
                item {
                    SetupSection("Starting point", "Use the week you could repeat now. This changes the recommendation; it does not limit your later choices.") {
                        if (isRaceGoal) {
                            ChoiceRow("Foundation before the race", startMode == "foundation_to_goal") { startMode = "foundation_to_goal" }
                            ChoiceRow("Use a repeatable current week", startMode == "established") { startMode = "established" }
                            ChoiceRow("Two-week timed check-in", startMode == "calibration") { startMode = "calibration" }
                        } else {
                            ChoiceRow("Nine-week run/walk foundation", startMode == "foundation_only") { startMode = "foundation_only" }
                        }
                    }
                }
                item {
                    SetupSection("Health context", "This is separate from the plan arithmetic and is never a diagnosis.") {
                        CheckRow("Recent injury affects current training", recentInjury) { recentInjury = it }
                        CheckRow("Pain is present now", currentPain) { currentPain = it }
                        CheckRow("Pain tends to recur while running", recurringPain) { recurringPain = it }
                        CheckRow("A clinician has limited current training", medicalRestriction) { medicalRestriction = it }
                        if (currentPain || medicalRestriction) {
                            Notice("No active workout phase will be created while pain is present or a clinician has limited training. Your goal can remain pending.")
                        } else if (recentInjury || recurringPain) {
                            Notice("The timed phase keeps its fixed schedule. This health context remains visible and separate from the plan recommendation.")
                        }
                        OutlinedTextField(
                            value = injuryNotes,
                            onValueChange = { injuryNotes = it.take(240) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Optional private context") },
                            minLines = 2,
                        )
                    }
                }
                item {
                    SetupSection("Current running", "These details make the first recommendation legible.") {
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
                        OutlinedTextField(
                            value = timeZone,
                            onValueChange = { timeZone = it.take(100) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Training time zone") },
                            singleLine = true,
                            isError = scheduleIssue != null,
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
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step > goalStep) {
                    OutlinedButton(
                        onClick = { step -= 1 },
                        modifier = Modifier.weight(1f),
                        enabled = !actionPending,
                        shape = MaterialTheme.shapes.small,
                    ) { Text("Back") }
                }
                val issue = when (step) {
                    goalStep -> goalIssue
                    startingPointStep -> startingPointIssue
                    scheduleStep -> scheduleIssue
                    else -> reviewIssue
                }
                Button(
                    onClick = {
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
                    },
                    enabled = !actionPending && issue == null,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) { Text(if (actionPending) "Creating plan…" else if (step == reviewStep) "Create plan" else "Continue") }
            }
        }
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
