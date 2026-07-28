package dev.deftmartian.runway

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.ZoneId

@Composable
internal fun SetupScreen(
    payload: NativeOnboardingPayload?,
    actionPending: Boolean,
    onAction: (MobileCommand) -> Unit,
) {
    val initial = payload?.initialValues
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
    var calibrationMinutes by rememberSaveable {
        mutableStateOf(initial?.calibrationDurationMinutes.orEmpty().ifBlank { "20" })
    }
    var preferredDay by rememberSaveable { mutableStateOf(initial?.preferredLongRunDay.orEmpty().ifBlank { "6" }) }
    var timeZone by rememberSaveable {
        mutableStateOf(initial?.timeZone.orEmpty().ifBlank { ZoneId.systemDefault().id })
    }
    var availability by rememberSaveable {
        mutableStateOf(
            initial?.availability.orEmpty().distinct().ifEmpty { listOf(1, 3, 6) },
        )
    }
    var recentInjury by rememberSaveable { mutableStateOf(initial?.recentInjury ?: false) }
    var currentPain by rememberSaveable { mutableStateOf(initial?.currentPain ?: false) }
    var recurringPain by rememberSaveable { mutableStateOf(initial?.recurringPain ?: false) }
    var medicalRestriction by rememberSaveable {
        mutableStateOf(initial?.medicalRestriction ?: false)
    }
    var injuryNotes by rememberSaveable { mutableStateOf(initial?.injuryNotes.orEmpty()) }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    var confirmConcentrated by rememberSaveable { mutableStateOf(false) }

    NativeList(actionPending) {
        item {
            ScreenIntro(
                "Build a plan",
                "Choose the starting point that reflects what you could repeat now. You can adjust individual runs later.",
            )
        }
        payload?.activeGoal?.let {
            item {
                Notice(
                    "Creating a new plan will archive ${it.title.orEmpty().ifBlank { "the current goal" }}.",
                )
            }
        }
        item {
            SettingCard("Starting point") {
                ChoiceRow("New runner", startMode == "foundation_only") { startMode = "foundation_only" }
                ChoiceRow("Build toward a race", startMode == "foundation_to_goal") {
                    startMode = "foundation_to_goal"
                }
                ChoiceRow("Use current weekly training", startMode == "established") {
                    startMode = "established"
                }
                ChoiceRow("Start with a timed check-in", startMode == "calibration") {
                    startMode = "calibration"
                }
            }
        }
        if (startMode != "foundation_only") {
            item {
                SettingCard("Goal") {
                    Text("Race distance", style = MaterialTheme.typography.labelLarge)
                    listOf(
                        "5k" to "5K",
                        "10k" to "10K",
                        "half" to "Half marathon",
                        "marathon" to "Marathon",
                    ).forEach { (value, label) ->
                        ChoiceRow(label, raceDistance == value) { raceDistance = value }
                    }
                    OutlinedTextField(
                        value = targetDate,
                        onValueChange = { targetDate = it.take(10) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Target date (YYYY-MM-DD)") },
                        singleLine = true,
                    )
                    val minimumTargetDate = when (startMode) {
                        "foundation_to_goal" -> payload?.minimumFoundationTargetDate
                        "calibration" -> payload?.minimumCalibrationTargetDate
                        else -> payload?.minimumTargetDate
                    }
                    Text(
                        "Choose ${minimumTargetDate.orDash()} to ${payload?.maximumTargetDate.orDash()}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChoiceRow("Finish healthy", priority == "finish_healthy") {
                        priority = "finish_healthy"
                    }
                    ChoiceRow("Build consistency", priority == "consistency") {
                        priority = "consistency"
                    }
                }
            }
        }
        item {
            SettingCard("Current running") {
                Text("Experience", style = MaterialTheme.typography.labelLarge)
                listOf(
                    "new" to "New runner",
                    "returning" to "Returning after time away",
                    "comfortable" to "Running comfortably",
                ).forEach { (value, label) ->
                    ChoiceRow(label, experience == value) { experience = value }
                }
                if (startMode == "established") {
                    NumberField("Repeatable weekly distance (km)", weeklyKm) { weeklyKm = it }
                    NumberField("Current runs per week", runsPerWeek) { runsPerWeek = it }
                    NumberField("Longest recent run (km)", longestKm) { longestKm = it }
                }
                if (startMode == "calibration") {
                    NumberField("Timed run duration (10–30 min)", calibrationMinutes) {
                        calibrationMinutes = it
                    }
                }
            }
        }
        item {
            SettingCard("Available days") {
                dayLabels.forEachIndexed { index, label ->
                    CheckRow(label, index in availability) { checked ->
                        availability =
                            if (checked) (availability + index).distinct() else availability - index
                    }
                }
                if (startMode == "established") {
                    Text("Preferred long-run day", style = MaterialTheme.typography.labelLarge)
                    availability.sorted().forEach { day ->
                        ChoiceRow(dayLabels[day], preferredDay == day.toString()) {
                            preferredDay = day.toString()
                        }
                    }
                }
                OutlinedTextField(
                    value = timeZone,
                    onValueChange = { timeZone = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Training time zone") },
                    singleLine = true,
                )
            }
        }
        item {
            SettingCard("Health context") {
                Text(
                    "These answers make the plan more conservative. Pain or a current medical restriction pauses plan generation; it does not diagnose anything.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CheckRow("Recent injury affects current training", recentInjury) { recentInjury = it }
                CheckRow("Pain is present now", currentPain) { currentPain = it }
                CheckRow("Pain tends to recur while running", recurringPain) { recurringPain = it }
                CheckRow("A clinician has limited current training", medicalRestriction) {
                    medicalRestriction = it
                }
                OutlinedTextField(
                    value = injuryNotes,
                    onValueChange = { injuryNotes = it.take(240) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Optional context") },
                    minLines = 2,
                )
            }
        }
        if (payload?.activeGoal != null) {
            item {
                CheckRow("Archive the current goal and replace it", confirmReplace) {
                    confirmReplace = it
                }
            }
        }
        if (
            startMode == "established" &&
            runsPerWeek == "2" &&
            raceDistance in setOf("half", "marathon")
        ) {
            item {
                CheckRow("I understand this concentrates training into two days", confirmConcentrated) {
                    confirmConcentrated = it
                }
            }
        }
        item {
            Button(
                onClick = {
                    onAction(
                        CreatePlanCommand(
                            goalKind = if (startMode == "foundation_only") "foundation" else "race",
                            startMode = startMode,
                            raceDistance =
                                if (startMode == "foundation_only") "" else raceDistance,
                            targetDate = if (startMode == "foundation_only") "" else targetDate,
                            priority = priority,
                            currentWeeklyDistanceKm = weeklyKm,
                            currentRunsPerWeek = runsPerWeek,
                            longestRecentRunKm = longestKm,
                            experience = experience,
                            calibrationDurationMinutes = calibrationMinutes,
                            availability = availability.sorted(),
                            preferredLongRunDay = preferredDay,
                            timeZone = timeZone,
                            recentInjury = recentInjury,
                            currentPain = currentPain,
                            recurringPain = recurringPain,
                            medicalRestriction = medicalRestriction,
                            injuryNotes = injuryNotes,
                            confirmConcentratedSchedule = confirmConcentrated,
                            confirmReplace = confirmReplace,
                        ),
                    )
                },
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (actionPending) "Creating plan…" else "Create plan")
            }
        }
    }
}
