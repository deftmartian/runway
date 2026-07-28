package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class PendingPlanDecision(
    val source: String,
    val sourceId: String,
    val decision: String,
    val consequence: JSONObject,
)

@Composable
internal fun RunwayNativeApp(
    state: RunwayUiState,
    onStartAuthorization: () -> Unit,
    onCancelAuthorization: () -> Unit,
    onRetry: () -> Unit,
    onDestinationSelected: (NativeDestination) -> Unit,
    onCalendarMonthSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onAction: (String, JSONObject) -> Unit,
    onConfirmActionPreview: () -> Unit,
    onDismissActionPreview: () -> Unit,
    onSignOut: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    when (state) {
        RunwayUiState.Loading -> LoadingScreen()
        is RunwayUiState.SignedOut -> DeviceAuthorizationScreen(
            state = state,
            onStartAuthorization = onStartAuthorization,
            onCancelAuthorization = onCancelAuthorization,
        )
        is RunwayUiState.Failed -> FailureScreen(state.message, onRetry)
        is RunwayUiState.Ready -> NativeProductShell(
            state = state,
            onDestinationSelected = onDestinationSelected,
            onCalendarMonthSelected = onCalendarMonthSelected,
            onRefresh = onRefresh,
            onAction = onAction,
            onConfirmActionPreview = onConfirmActionPreview,
            onDismissActionPreview = onDismissActionPreview,
            onSignOut = onSignOut,
            onOpenServer = onOpenServer,
            onOpenFolder = onOpenFolder,
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Opening your training log")
        }
    }
}

@Composable
private fun FailureScreen(message: String, onRetry: () -> Unit) {
    CenteredSurface {
        Text("Couldn’t open Runway", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun DeviceAuthorizationScreen(
    state: RunwayUiState.SignedOut,
    onStartAuthorization: () -> Unit,
    onCancelAuthorization: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    CenteredSurface {
        Text(
            "runway",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text("Your training, on this phone.", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in in your browser, then return here. The browser keeps your account credentials; this app receives only its own session.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        when {
            state.starting -> CircularProgressIndicator()
            state.pending != null -> {
                Text("Enter this code if the browser asks:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        state.pending.userCode.chunked(4).joinToString(" "),
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { uriHandler.openUri(state.pending.verificationUri) }) {
                    Text("Open secure sign-in")
                }
                TextButton(onClick = onCancelAuthorization) { Text("Cancel") }
                Text(
                    "Waiting for approval…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Button(onClick = onStartAuthorization) { Text("Sign in") }
        }
        state.message?.let {
            Spacer(Modifier.height(16.dp))
            Notice(it, isError = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeProductShell(
    state: RunwayUiState.Ready,
    onDestinationSelected: (NativeDestination) -> Unit,
    onCalendarMonthSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onAction: (String, JSONObject) -> Unit,
    onConfirmActionPreview: () -> Unit,
    onDismissActionPreview: () -> Unit,
    onSignOut: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "runway",
                            modifier = Modifier.semantics { heading() },
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            listOfNotNull(
                                state.destination.label,
                                state.bootstrap.optJSONObject("user")?.optString("name")
                                    ?.takeIf(String::isNotBlank),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !state.loading) {
                        Text(if (state.loading) "Loading" else "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            if (state.destination != NativeDestination.Setup) {
                NavigationBar {
                    NativeDestination.entries.filterNot { it == NativeDestination.Setup }.forEach { destination ->
                        NavigationBarItem(
                            selected = state.destination == destination,
                            onClick = { onDestinationSelected(destination) },
                            icon = {
                                Icon(
                                    painter = painterResource(destination.iconRes),
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label) },
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.notice?.let { Notice(it.message, isError = it.isError) }
            when (state.destination) {
                NativeDestination.Setup -> SetupScreen(
                    payload = state.payload,
                    actionPending = state.actionPending,
                    onAction = onAction,
                )
                NativeDestination.Today -> TodayScreen(
                    payload = state.payload,
                    loading = state.loading,
                    actionPending = state.actionPending,
                    actionNotice = state.notice,
                    completedAction = state.completedAction,
                    onDestinationSelected = onDestinationSelected,
                    onAction = onAction,
                )
                NativeDestination.Calendar -> CalendarScreen(
                    state.payload,
                    state.loading,
                    state.actionPending,
                    state.notice,
                    state.completedAction,
                    onCalendarMonthSelected,
                    onAction,
                )
                NativeDestination.Review -> ReviewScreen(
                    state.payload,
                    state.loading,
                    state.actionPending,
                    state.notice,
                    state.completedAction,
                    onAction,
                )
                NativeDestination.Progress -> ProgressScreen(
                    state.payload,
                    state.loading,
                    state.actionPending,
                    onAction,
                )
                NativeDestination.Settings -> SettingsScreen(
                    payload = state.payload,
                    loading = state.loading,
                    actionPending = state.actionPending,
                    onAction = onAction,
                    onOpenServer = onOpenServer,
                    onOpenFolder = onOpenFolder,
                    onSignOut = onSignOut,
                )
            }
        }
    }
    state.actionPreview?.let {
        WorkoutPreviewDialog(
            preview = it.preview,
            actionPending = state.actionPending,
            errorMessage = state.notice?.takeIf { notice -> notice.isError }?.message,
            onDismiss = onDismissActionPreview,
            onConfirm = onConfirmActionPreview,
        )
    }
}

@Composable
private fun SetupScreen(
    payload: JSONObject?,
    actionPending: Boolean,
    onAction: (String, JSONObject) -> Unit,
) {
    val initial = payload?.optJSONObject("initialValues")
    var startMode by rememberSaveable(initial?.optString("startMode")) {
        mutableStateOf(initial?.optString("startMode").orEmpty().ifBlank { "foundation_only" })
    }
    var raceDistance by rememberSaveable { mutableStateOf(initial?.optString("raceDistance").orEmpty().ifBlank { "5k" }) }
    var targetDate by rememberSaveable { mutableStateOf(initial?.optString("targetDate").orEmpty()) }
    var priority by rememberSaveable { mutableStateOf(initial?.optString("priority").orEmpty().ifBlank { "finish_healthy" }) }
    var weeklyKm by rememberSaveable { mutableStateOf(initial?.optString("currentWeeklyDistanceKm").orEmpty()) }
    var runsPerWeek by rememberSaveable { mutableStateOf(initial?.optString("currentRunsPerWeek").orEmpty()) }
    var longestKm by rememberSaveable { mutableStateOf(initial?.optString("longestRecentRunKm").orEmpty()) }
    var experience by rememberSaveable { mutableStateOf(initial?.optString("experience").orEmpty().ifBlank { "new" }) }
    var calibrationMinutes by rememberSaveable {
        mutableStateOf(initial?.optString("calibrationDurationMinutes").orEmpty().ifBlank { "20" })
    }
    var preferredDay by rememberSaveable { mutableStateOf(initial?.optString("preferredLongRunDay").orEmpty().ifBlank { "6" }) }
    var timeZone by rememberSaveable {
        mutableStateOf(initial?.optString("timeZone").orEmpty().ifBlank { ZoneId.systemDefault().id })
    }
    var availability by rememberSaveable {
        mutableStateOf(
            initial?.optJSONArray("availability").asInts().distinct().ifEmpty { listOf(1, 3, 6) },
        )
    }
    var recentInjury by rememberSaveable { mutableStateOf(initial?.optBoolean("recentInjury") ?: false) }
    var currentPain by rememberSaveable { mutableStateOf(initial?.optBoolean("currentPain") ?: false) }
    var recurringPain by rememberSaveable { mutableStateOf(initial?.optBoolean("recurringPain") ?: false) }
    var medicalRestriction by rememberSaveable {
        mutableStateOf(initial?.optBoolean("medicalRestriction") ?: false)
    }
    var injuryNotes by rememberSaveable { mutableStateOf(initial?.optString("injuryNotes").orEmpty()) }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    var confirmConcentrated by rememberSaveable { mutableStateOf(false) }

    NativeList(actionPending) {
        item {
            ScreenIntro(
                "Build a plan",
                "Choose the starting point that reflects what you could repeat now. You can adjust individual runs later.",
            )
        }
        payload?.optJSONObject("activeGoal")?.let {
            item {
                Notice(
                    "Creating a new plan will archive ${it.optString("title").ifBlank { "the current goal" }}.",
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
                    Text(
                        "Choose ${payload?.optString(targetMinimumKey(startMode)).orDash()} to ${payload?.optString("maximumTargetDate").orDash()}.",
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
        if (payload?.optJSONObject("activeGoal") != null) {
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
                        "create_plan",
                        JSONObject()
                            .put(
                                "goalKind",
                                if (startMode == "foundation_only") "foundation" else "race",
                            )
                            .put("startMode", startMode)
                            .put(
                                "raceDistance",
                                if (startMode == "foundation_only") "" else raceDistance,
                            )
                            .put(
                                "targetDate",
                                if (startMode == "foundation_only") "" else targetDate,
                            )
                            .put("priority", priority)
                            .put("currentWeeklyDistanceKm", weeklyKm)
                            .put("currentRunsPerWeek", runsPerWeek)
                            .put("longestRecentRunKm", longestKm)
                            .put("experience", experience)
                            .put("calibrationDurationMinutes", calibrationMinutes)
                            .put("availability", JSONArray(availability.sorted()))
                            .put("preferredLongRunDay", preferredDay)
                            .put("timeZone", timeZone)
                            .put("recentInjury", recentInjury)
                            .put("currentPain", currentPain)
                            .put("recurringPain", recurringPain)
                            .put("medicalRestriction", medicalRestriction)
                            .put("injuryNotes", injuryNotes)
                            .put("confirmConcentratedSchedule", confirmConcentrated)
                            .put("confirmReplace", confirmReplace),
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

@Composable
private fun TodayScreen(
    payload: JSONObject?,
    loading: Boolean,
    actionPending: Boolean,
    actionNotice: NativeNotice?,
    completedAction: String?,
    onDestinationSelected: (NativeDestination) -> Unit,
    onAction: (String, JSONObject) -> Unit,
) {
    val calendar = payload?.optJSONObject("calendar")
    val workouts = calendar?.optJSONArray("workouts").asObjects()
    val feedbackByWorkout = calendar?.optJSONArray("feedback").asObjects()
        .associateBy { it.optString("workoutId") }
    val today = calendar?.optString("today").orEmpty()
    val todaysWorkouts = workouts.filter { it.optString("scheduledDate") == today && !it.optBoolean("isRemoved") }
    var feedbackWorkout by remember { mutableStateOf<JSONObject?>(null) }
    var pendingDecision by remember { mutableStateOf<PendingPlanDecision?>(null) }
    var showManualRun by rememberSaveable { mutableStateOf(false) }
    var submittedDialogAction by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(completedAction) {
        if (completedAction != submittedDialogAction) return@LaunchedEffect
        when (completedAction) {
            "record_feedback" -> feedbackWorkout = null
            "record_manual_run" -> showManualRun = false
            "apply_plan_decision" -> pendingDecision = null
        }
        submittedDialogAction = null
    }
    NativeList(loading) {
        item { ScreenIntro("Today", if (today.isBlank()) "Your next training decision." else today) }
        when {
            payload == null -> item { EmptyCard("Loading today’s plan…") }
            payload.optBoolean("onboardingRequired") -> item {
                EmptyCard("Finish your training setup to see today’s plan.")
            }
            todaysWorkouts.isEmpty() -> item {
                EmptyCard("No scheduled run today. Rest is part of the plan too.")
            }
            else -> items(todaysWorkouts, key = { it.optString("id") }) { workout ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkoutCard(
                        workout = workout,
                        onRecord = if (
                            workout.optString("type") != "rest" &&
                            workout.optString("status") == "planned"
                        ) {
                            { feedbackWorkout = workout }
                        } else {
                            null
                        },
                    )
                    feedbackByWorkout[workout.optString("id")]?.let { feedback ->
                        FeedbackOutcomeCard(
                            feedback = feedback,
                            actionPending = actionPending,
                            onDecision = { decision ->
                                pendingDecision = pendingPlanDecision("feedback", feedback, decision)
                            },
                            onDelete = if (feedback.optBoolean("canDelete")) {
                                {
                                    onAction(
                                        "delete_feedback",
                                        JSONObject().put("workoutId", workout.optString("id")),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        calendar?.optJSONObject("activityOverflow")?.takeIf { it.optBoolean("truncated") }?.let {
            item { Notice("Only the most recent activities are shown for this month.") }
        }
        item {
            OutlinedButton(
                onClick = { showManualRun = true },
                enabled = !actionPending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Record an unplanned run")
            }
            TextButton(onClick = { onDestinationSelected(NativeDestination.Calendar) }) {
                Text("See the calendar")
            }
        }
    }
    feedbackWorkout?.let { workout ->
        FeedbackDialog(
            workout = workout,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { feedbackWorkout = null },
            onSubmit = {
                submittedDialogAction = "record_feedback"
                onAction("record_feedback", it)
            },
        )
    }
    if (showManualRun) {
        ManualRunDialog(
            actionPending = actionPending,
            defaultDate = today.ifBlank { LocalDate.now().toString() },
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { showManualRun = false },
            onSubmit = {
                submittedDialogAction = "record_manual_run"
                onAction("record_manual_run", it)
            },
        )
    }
    pendingDecision?.let { decision ->
        PlanDecisionDialog(
            pending = decision,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { pendingDecision = null },
            onConfirm = {
                submittedDialogAction = "apply_plan_decision"
                onAction("apply_plan_decision", planDecisionPayload(decision))
            },
        )
    }
}

@Composable
private fun CalendarScreen(
    payload: JSONObject?,
    loading: Boolean,
    actionPending: Boolean,
    actionNotice: NativeNotice?,
    completedAction: String?,
    onCalendarMonthSelected: (String) -> Unit,
    onAction: (String, JSONObject) -> Unit,
) {
    val calendar = payload?.optJSONObject("calendar")
    val month = calendar?.optString("month").orEmpty()
    val today = calendar?.optString("today").orEmpty()
    var feedbackWorkout by remember { mutableStateOf<JSONObject?>(null) }
    var editWorkout by remember { mutableStateOf<JSONObject?>(null) }
    var showAddWorkout by rememberSaveable { mutableStateOf(false) }
    var pendingDecision by remember { mutableStateOf<PendingPlanDecision?>(null) }
    var submittedDialogAction by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(completedAction) {
        if (completedAction != submittedDialogAction) return@LaunchedEffect
        when (completedAction) {
            "record_feedback" -> feedbackWorkout = null
            "preview_workout_edit", "preview_workout_removal" -> editWorkout = null
            "preview_workout_add" -> showAddWorkout = false
            "apply_plan_decision" -> pendingDecision = null
        }
        submittedDialogAction = null
    }
    NativeList(loading) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { calendar?.optString("previousMonth")?.let(onCalendarMonthSelected) },
                    enabled = calendar != null,
                ) { Text("Earlier") }
                Text(monthLabel(month), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = { calendar?.optString("nextMonth")?.let(onCalendarMonthSelected) },
                    enabled = calendar != null,
                ) { Text("Later") }
            }
        }
        item {
            OutlinedButton(
                onClick = { showAddWorkout = true },
                enabled = !actionPending && payload?.optBoolean("onboardingRequired") != true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add a future run")
            }
        }
        when {
            payload == null -> item { EmptyCard("Loading the calendar…") }
            payload.optBoolean("onboardingRequired") -> item {
                EmptyCard("Finish your training setup to build a calendar.")
            }
            else -> {
                val workouts = calendar?.optJSONArray("workouts").asObjects()
                val activities = calendar?.optJSONArray("activities").asObjects()
                val feedbackByWorkout = calendar?.optJSONArray("feedback").asObjects()
                    .associateBy { it.optString("workoutId") }
                if (workouts.isEmpty() && activities.isEmpty()) {
                    item { EmptyCard("Nothing is scheduled in this month yet.") }
                }
                items(workouts, key = { "workout-${it.optString("id")}" }) { workout ->
                    val canRecord =
                        workout.optString("type") != "rest" &&
                            workout.optString("status") == "planned" &&
                            workout.optString("scheduledDate") <= today
                    val canEdit =
                        workout.optString("type") != "race" &&
                            workout.optString("status") == "planned" &&
                            workout.optString("scheduledDate") > today &&
                            !workout.optBoolean("isRemoved")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkoutCard(
                            workout = workout,
                            onRecord = if (canRecord) ({ feedbackWorkout = workout }) else null,
                            onEdit = if (canEdit) ({ editWorkout = workout }) else null,
                            onReset = if (canEdit && workout.optBoolean("isEdited")) {
                                {
                                    onAction(
                                        "reset_workout",
                                        JSONObject().put("workoutId", workout.optString("id")),
                                    )
                                }
                            } else {
                                null
                            },
                            onUndo = workout.optJSONObject("adjustment")?.optString("id")
                                ?.takeIf { canEdit && it.isNotBlank() }
                                ?.let { adjustmentId ->
                                    {
                                        onAction(
                                            "undo_workout_adjustment",
                                            JSONObject().put("adjustmentId", adjustmentId),
                                        )
                                    }
                                },
                        )
                        feedbackByWorkout[workout.optString("id")]?.let { feedback ->
                            FeedbackOutcomeCard(
                                feedback = feedback,
                                actionPending = actionPending,
                                onDecision = { decision ->
                                    pendingDecision = pendingPlanDecision("feedback", feedback, decision)
                                },
                                onDelete = if (feedback.optBoolean("canDelete")) {
                                    {
                                        onAction(
                                            "delete_feedback",
                                            JSONObject().put("workoutId", workout.optString("id")),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
                if (activities.isNotEmpty()) {
                    item { SectionLabel("Completed activity") }
                    items(activities, key = { "activity-${it.optString("id")}" }) { activity -> ActivityCard(activity) }
                }
            }
        }
    }
    feedbackWorkout?.let { workout ->
        FeedbackDialog(
            workout = workout,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { feedbackWorkout = null },
            onSubmit = {
                submittedDialogAction = "record_feedback"
                onAction("record_feedback", it)
            },
        )
    }
    editWorkout?.let { workout ->
        WorkoutEditDialog(
            workout = workout,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { editWorkout = null },
            onSubmit = {
                submittedDialogAction = "preview_workout_edit"
                onAction("preview_workout_edit", it)
            },
            onRemove = {
                submittedDialogAction = "preview_workout_removal"
                onAction(
                    "preview_workout_removal",
                    JSONObject().put("workoutId", workout.optString("id")),
                )
            },
        )
    }
    if (showAddWorkout) {
        WorkoutAddDialog(
            defaultDate = runCatching { LocalDate.parse(today).plusDays(1).toString() }
                .getOrDefault(LocalDate.now().plusDays(1).toString()),
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { showAddWorkout = false },
            onSubmit = {
                submittedDialogAction = "preview_workout_add"
                onAction("preview_workout_add", it)
            },
        )
    }
    pendingDecision?.let { decision ->
        PlanDecisionDialog(
            pending = decision,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { pendingDecision = null },
            onConfirm = {
                submittedDialogAction = "apply_plan_decision"
                onAction("apply_plan_decision", planDecisionPayload(decision))
            },
        )
    }
}

@Composable
private fun ReviewScreen(
    payload: JSONObject?,
    loading: Boolean,
    actionPending: Boolean,
    actionNotice: NativeNotice?,
    completedAction: String?,
    onAction: (String, JSONObject) -> Unit,
) {
    val candidates = payload?.optJSONArray("candidates").asObjects()
    val activities = payload?.optJSONArray("activities").asObjects()
    var selectedActivity by remember { mutableStateOf<JSONObject?>(null) }
    var pendingDecision by remember { mutableStateOf<PendingPlanDecision?>(null) }
    var submittedDialogAction by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(completedAction) {
        if (completedAction == submittedDialogAction) {
            if (completedAction == "apply_plan_decision") {
                pendingDecision = null
            } else {
                selectedActivity = null
            }
            submittedDialogAction = null
        }
    }
    NativeList(loading) {
        item { ScreenIntro("Review", "Imported and unplanned activity, kept separate until you decide where it belongs.") }
        when {
            payload == null -> item { EmptyCard("Loading activity review…") }
            activities.none { it.optString("reviewState") == "review" } ->
                item { EmptyCard("Nothing needs a decision right now.") }
            else -> {
                items(
                    activities.filter { it.optString("reviewState") == "review" },
                    key = { it.optString("id") },
                ) { activity ->
                    ActivityCard(
                        activity = activity,
                        title = "Needs review",
                        actions = {
                            Button(
                                onClick = { selectedActivity = activity },
                                enabled = !actionPending,
                            ) {
                                Text("Decide")
                            }
                        },
                    )
                }
            }
        }
        val acceptedActivities = activities.filter { it.optString("reviewState") != "review" }
        if (acceptedActivities.isNotEmpty()) {
            item { SectionLabel("Recent activity") }
            items(acceptedActivities, key = { "activity-${it.optString("id")}" }) { activity ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActivityCard(
                        activity = activity,
                        actions = if (activity.optString("workoutId").isNotBlank()) {
                            {
                                TextButton(
                                    onClick = {
                                        onAction(
                                            "unlink_activity",
                                            JSONObject().put("activityId", activity.optString("id")),
                                        )
                                    },
                                    enabled = !actionPending,
                                ) {
                                    Text("Unlink from planned run")
                                }
                            }
                        } else {
                            null
                        },
                    )
                    activity.optJSONObject("consequence")?.let { consequence ->
                        ConsequenceChoices(
                            consequence = consequence,
                            actionPending = actionPending,
                            onDecision = { decision ->
                                pendingDecision = PendingPlanDecision(
                                    source = "activity",
                                    sourceId = activity.optString("id"),
                                    decision = decision,
                                    consequence = consequence,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
    selectedActivity?.let { activity ->
        ActivityReviewDialog(
            activity = activity,
            candidates = candidates,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { selectedActivity = null },
            onAction = { action, body ->
                submittedDialogAction = action
                onAction(action, body)
            },
        )
    }
    pendingDecision?.let { decision ->
        PlanDecisionDialog(
            pending = decision,
            actionPending = actionPending,
            errorMessage = actionNotice?.takeIf { it.isError }?.message,
            onDismiss = { pendingDecision = null },
            onConfirm = {
                submittedDialogAction = "apply_plan_decision"
                onAction("apply_plan_decision", planDecisionPayload(decision))
            },
        )
    }
}

@Composable
private fun ProgressScreen(
    payload: JSONObject?,
    loading: Boolean,
    actionPending: Boolean,
    onAction: (String, JSONObject) -> Unit,
) {
    val detail = payload?.optJSONObject("detail")
    val weeks = detail?.optJSONArray("weeks").asObjects()
    val active = payload?.optJSONObject("active")
    val phaseReview = payload?.optJSONObject("phaseReview")
    val planHistory = payload?.optJSONObject("planHistory")?.optJSONArray("items").asObjects()
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    NativeList(loading) {
        item { ScreenIntro("Progress", "A clear view of the ramp, with recovery visible too.") }
        when {
            payload == null -> item { EmptyCard("Loading plan progress…") }
            payload.optBoolean("onboardingRequired") -> item {
                EmptyCard("Finish your training setup to see progress.")
            }
            active == null -> item { EmptyCard("There is no active plan. Your history is still kept here.") }
            weeks.isEmpty() -> item { EmptyCard("This plan does not have weekly progress yet.") }
            else -> items(weeks, key = { it.optString("id") }) { week -> WeekCard(week) }
        }
        phaseReview?.let {
            item {
                SettingCard("Beginner phase complete") {
                    Text(
                        "Choose whether the recorded work supports moving on or whether another week would help.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            onAction("confirm_phase_baseline", JSONObject())
                        },
                        enabled = !actionPending,
                    ) {
                        Text("Use this baseline")
                    }
                    OutlinedButton(
                        onClick = {
                            onAction("continue_beginner_phase", JSONObject())
                        },
                        enabled = !actionPending,
                    ) {
                        Text("Repeat another week")
                    }
                }
            }
        }
        if (active != null) {
            item {
                SettingCard("Plan controls") {
                    OutlinedButton(
                        onClick = { onAction("complete_plan", JSONObject()) },
                        enabled = !actionPending,
                    ) {
                        Text("Complete plan")
                    }
                    TextButton(onClick = { confirmArchive = true }, enabled = !actionPending) {
                        Text("Archive plan")
                    }
                }
            }
        }
        if (planHistory.isNotEmpty()) {
            item { SectionLabel("Plan history") }
            items(planHistory, key = { it.optJSONObject("plan")?.optString("id").orEmpty() }) {
                val plan = it.optJSONObject("plan")
                Card {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            it.optJSONObject("goal")?.optString("title").orDash(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${plan?.optString("startDate").orDash()} – ${plan?.optString("targetDate").orDash()}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("Archive this plan?") },
            text = { Text("Completed work stays in history. Planned runs will no longer be active.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmArchive = false
                        onAction(
                            "archive_plan",
                            JSONObject().put("confirmation", "ARCHIVE"),
                        )
                    },
                    enabled = !actionPending,
                ) {
                    Text("Archive")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmArchive = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsScreen(
    payload: JSONObject?,
    loading: Boolean,
    actionPending: Boolean,
    onAction: (String, JSONObject) -> Unit,
    onOpenServer: () -> Unit,
    onOpenFolder: () -> Unit,
    onSignOut: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var editingTimeZone by rememberSaveable { mutableStateOf(false) }
    var editingHealthContext by rememberSaveable { mutableStateOf(false) }
    var editingHeartRate by rememberSaveable { mutableStateOf(false) }
    var connectingNextcloud by rememberSaveable { mutableStateOf(false) }
    var disconnectingSource by remember { mutableStateOf<JSONObject?>(null) }
    var timeZone by rememberSaveable(payload?.optJSONObject("profile")?.optString("timeZone")) {
        mutableStateOf(payload?.optJSONObject("profile")?.optString("timeZone").orEmpty())
    }
    NativeList(loading) {
        item { ScreenIntro("Settings", "Training preferences, imports, and this phone’s connection.") }
        if (payload == null) {
            item { EmptyCard("Loading settings…") }
        } else {
            val profile = payload.optJSONObject("profile")
            val about = payload.optJSONObject("about")
            item {
                SettingCard("Training") {
                    SettingRow("Time zone", profile?.optString("timeZone").orDash())
                    SettingRow("Route privacy", profile?.optString("routeDataMode").orDash())
                    SettingRow(
                        "Heart-rate zones",
                        profile?.optString("heartRateSettingsSource")
                            ?.takeUnless { it == "not_configured" }
                            ?.replaceFirstChar { it.uppercase() }
                            ?: "Not configured",
                    )
                    OutlinedButton(
                        onClick = { editingTimeZone = true },
                        enabled = !actionPending,
                    ) {
                        Text("Change time zone")
                    }
                    OutlinedButton(
                        onClick = {
                            val next =
                                if (profile?.optString("routeDataMode") == "discard") {
                                    "private"
                                } else {
                                    "discard"
                                }
                            onAction(
                                "update_route_data_mode",
                                JSONObject().put("routeDataMode", next),
                            )
                        },
                        enabled = !actionPending,
                    ) {
                        Text(
                            if (profile?.optString("routeDataMode") == "discard") {
                                "Keep future route points"
                            } else {
                                "Discard route points"
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = { editingHeartRate = true },
                        enabled = !actionPending,
                    ) {
                        Text("Heart-rate profile")
                    }
                    OutlinedButton(
                        onClick = { editingHealthContext = true },
                        enabled = !actionPending,
                    ) {
                        Text("Health context")
                    }
                }
            }
            item {
                SettingCard("Imports") {
                    val sources = payload.optJSONArray("sources").asObjects()
                    if (sources.isEmpty()) {
                        Text(
                            "No Nextcloud GPX folders are connected.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        sources.forEach { source ->
                            SettingRow(
                                source.optString("label").ifBlank { "Nextcloud GPX folder" },
                                source.optString("lastError").takeIf(String::isNotBlank)
                                    ?: if (source.optBoolean("enabled")) "Connected" else "Disabled",
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        onAction(
                                            "test_nextcloud",
                                            JSONObject().put("sourceId", source.optString("id")),
                                        )
                                    },
                                    enabled = !actionPending,
                                ) {
                                    Text("Test")
                                }
                                TextButton(
                                    onClick = {
                                        onAction(
                                            "sync_nextcloud",
                                            JSONObject().put("sourceId", source.optString("id")),
                                        )
                                    },
                                    enabled = !actionPending,
                                ) {
                                    Text("Sync")
                                }
                                TextButton(
                                    onClick = { disconnectingSource = source },
                                    enabled = !actionPending,
                                ) {
                                    Text("Disconnect")
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { connectingNextcloud = true },
                        enabled = !actionPending,
                    ) {
                        Text("Connect Nextcloud folder")
                    }
                    val health = payload.optJSONObject("healthConnect")
                    SettingRow(
                        "Health Connect",
                        when (health?.optString("state")) {
                            "connected" -> "Connected"
                            "needs_attention" -> "Needs attention"
                            "unavailable" -> "Unavailable"
                            else -> "Not connected"
                        },
                    )
                    OutlinedButton(onClick = onOpenFolder) { Text("Imports and Health Connect") }
                }
            }
            item {
                SettingCard("Server") {
                    SettingRow("Connected to", about?.optString("serverOrigin").orDash())
                    OutlinedButton(onClick = onOpenServer) { Text("Change server") }
                }
            }
            item {
                SettingCard("About") {
                    SettingRow("Android app", BuildConfig.VERSION_NAME)
                    SettingRow("Android source", BuildConfig.SOURCE_COMMIT)
                    SettingRow("Server release", about?.optString("release").orDash())
                    SettingRow("Server build", about?.optString("commit").orDash())
                    SettingRow("Native API", "v2 · connected")
                }
            }
            item {
                SettingCard("Account") {
                    Text(
                        "Passkeys, two-factor authentication, exports, and account deletion use the secure server page.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    payload.optString("accountSecurityUrl").takeIf(String::isNotBlank)?.let { url ->
                        OutlinedButton(onClick = { uriHandler.openUri(url) }) {
                            Text("Account security")
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                    Text("Sign out from this phone")
                }
            }
        }
    }
    if (editingTimeZone) {
        AlertDialog(
            onDismissRequest = { editingTimeZone = false },
            title = { Text("Training time zone") },
            text = {
                OutlinedTextField(
                    value = timeZone,
                    onValueChange = { timeZone = it.take(100) },
                    label = { Text("IANA time zone") },
                    supportingText = { Text("Example: America/Halifax") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        editingTimeZone = false
                        onAction("update_time_zone", JSONObject().put("timeZone", timeZone))
                    },
                    enabled = !actionPending && timeZone.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTimeZone = false }) { Text("Cancel") }
            },
        )
    }
    if (editingHealthContext) {
        HealthContextDialog(
            injuryFlags = payload?.optJSONObject("profile")?.optJSONObject("injuryFlags"),
            actionPending = actionPending,
            onDismiss = { editingHealthContext = false },
            onSubmit = {
                editingHealthContext = false
                onAction("update_health_context", it)
            },
        )
    }
    if (editingHeartRate) {
        HeartRateProfileDialog(
            profile = payload?.optJSONObject("profile"),
            actionPending = actionPending,
            onDismiss = { editingHeartRate = false },
            onSubmit = {
                editingHeartRate = false
                onAction("update_training_profile", it)
            },
        )
    }
    if (connectingNextcloud) {
        NextcloudConnectionDialog(
            actionPending = actionPending,
            onDismiss = { connectingNextcloud = false },
            onSubmit = {
                connectingNextcloud = false
                onAction("connect_nextcloud", it)
            },
        )
    }
    disconnectingSource?.let { source ->
        AlertDialog(
            onDismissRequest = { disconnectingSource = null },
            title = { Text("Disconnect this folder?") },
            text = {
                Text(
                    "${source.optString("label").ifBlank { "This Nextcloud folder" }} will stop syncing. Imported runs remain in runway.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        disconnectingSource = null
                        onAction(
                            "disconnect_nextcloud",
                            JSONObject().put("sourceId", source.optString("id")),
                        )
                    },
                    enabled = !actionPending,
                ) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { disconnectingSource = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun NextcloudConnectionDialog(
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (JSONObject) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("Nextcloud GPX folder") }
    var shareUrl by rememberSaveable { mutableStateOf("") }
    var sharePassword by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect a Nextcloud folder") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "Use a password-protected public folder share. Runway stores the token and password encrypted on the server.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Folder name") },
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = shareUrl,
                        onValueChange = { shareUrl = it.take(2_048) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Public share URL") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = sharePassword,
                        onValueChange = { sharePassword = it.take(1_024) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Share password") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        JSONObject()
                            .put("label", label.trim())
                            .put("shareUrl", shareUrl.trim())
                            .put("sharePassword", sharePassword),
                    )
                },
                enabled = !actionPending && shareUrl.isNotBlank() && sharePassword.isNotEmpty(),
            ) {
                Text("Connect")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HealthContextDialog(
    injuryFlags: JSONObject?,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (JSONObject) -> Unit,
) {
    var recentInjury by rememberSaveable { mutableStateOf(injuryFlags?.optBoolean("recentInjury") ?: false) }
    var currentPain by rememberSaveable { mutableStateOf(injuryFlags?.optBoolean("currentPain") ?: false) }
    var recurringPain by rememberSaveable { mutableStateOf(injuryFlags?.optBoolean("recurringPain") ?: false) }
    var medicalRestriction by rememberSaveable {
        mutableStateOf(injuryFlags?.optBoolean("medicalRestriction") ?: false)
    }
    var notes by rememberSaveable { mutableStateOf(injuryFlags?.optString("notes").orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Health context") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "These answers can make the plan more conservative. They are not a diagnosis.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    CheckRow("Recent injury affects current training", recentInjury) {
                        recentInjury = it
                    }
                    CheckRow("Pain is present now", currentPain) { currentPain = it }
                    CheckRow("Pain tends to recur while running", recurringPain) {
                        recurringPain = it
                    }
                    CheckRow("A clinician has limited current training", medicalRestriction) {
                        medicalRestriction = it
                    }
                }
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it.take(240) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Optional private context") },
                        minLines = 2,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        JSONObject()
                            .put("recentInjury", recentInjury)
                            .put("currentPain", currentPain)
                            .put("recurringPain", recurringPain)
                            .put("medicalRestriction", medicalRestriction)
                            .put("injuryNotes", notes),
                    )
                },
                enabled = !actionPending,
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HeartRateProfileDialog(
    profile: JSONObject?,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (JSONObject) -> Unit,
) {
    var sex by rememberSaveable { mutableStateOf(profile?.optString("sexForEstimates").orEmpty().ifBlank { "not_specified" }) }
    var age by rememberSaveable {
        mutableStateOf(profile?.optInt("ageYears", 0)?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var source by rememberSaveable {
        mutableStateOf(profile?.optString("heartRateSettingsSource").orEmpty().takeIf { it in setOf("estimated", "custom") } ?: "custom")
    }
    var max by rememberSaveable { mutableStateOf(profile?.optInt("maxHeartRateBpm", 190)?.toString() ?: "190") }
    var z2 by rememberSaveable { mutableStateOf(profile?.optInt("zone2FloorBpm", 114)?.toString() ?: "114") }
    var z3 by rememberSaveable { mutableStateOf(profile?.optInt("zone3FloorBpm", 133)?.toString() ?: "133") }
    var z4 by rememberSaveable { mutableStateOf(profile?.optInt("zone4FloorBpm", 152)?.toString() ?: "152") }
    var z5 by rememberSaveable { mutableStateOf(profile?.optInt("zone5FloorBpm", 171)?.toString() ?: "171") }
    fun applyEstimate() {
        val ageNumber = age.toIntOrNull()?.takeIf { it in 18..100 } ?: return
        val estimatedMax =
            if (sex == "female") (206 - 0.88 * ageNumber).toInt() else (208 - 0.7 * ageNumber).toInt()
        max = estimatedMax.toString()
        z2 = (estimatedMax * 0.60).toInt().toString()
        z3 = (estimatedMax * 0.70).toInt().toString()
        z4 = (estimatedMax * 0.80).toInt().toString()
        z5 = (estimatedMax * 0.90).toInt().toString()
    }
    val ageValue = age.toIntOrNull()
    val maxValue = max.toIntOrNull()
    val floors = listOf(z2, z3, z4, z5).map { it.toIntOrNull() }
    val valid =
        (age.isBlank() || (ageValue != null && ageValue in 18..100)) &&
            maxValue != null &&
            maxValue in 120..230 &&
            floors.all { it != null } &&
            floors.filterNotNull().zipWithNext().all { (left, right) -> right > left } &&
            (floors.lastOrNull() ?: Int.MAX_VALUE) <= maxValue
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Heart-rate profile") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "Heart rate is descriptive context. It does not turn a run into a medical assessment.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Text("Estimate profile", style = MaterialTheme.typography.labelLarge)
                    listOf(
                        "not_specified" to "Not specified",
                        "female" to "Female estimate",
                        "male" to "Male estimate",
                    ).forEach { (value, label) ->
                        ChoiceRow(label, sex == value) { sex = value }
                    }
                    NumberField("Age (optional)", age) { age = it.substringBefore(".") }
                    OutlinedButton(
                        onClick = {
                            source = "estimated"
                            applyEstimate()
                        },
                        enabled = ageValue != null && ageValue in 18..100,
                    ) {
                        Text("Apply estimate")
                    }
                }
                item {
                    NumberField("Max heart rate", max) { max = it.substringBefore("."); source = "custom" }
                    NumberField("Easy zone starts", z2) { z2 = it.substringBefore("."); source = "custom" }
                    NumberField("Steady zone starts", z3) { z3 = it.substringBefore("."); source = "custom" }
                    NumberField("Hard zone starts", z4) { z4 = it.substringBefore("."); source = "custom" }
                    NumberField("Max zone starts", z5) { z5 = it.substringBefore("."); source = "custom" }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val body = JSONObject()
                        .put("sexForEstimates", sex)
                        .put("heartRateSettingsSource", source)
                        .put("maxHeartRateBpm", max.toInt())
                        .put("zone2FloorBpm", z2.toInt())
                        .put("zone3FloorBpm", z3.toInt())
                        .put("zone4FloorBpm", z4.toInt())
                        .put("zone5FloorBpm", z5.toInt())
                    age.toIntOrNull()?.let { body.put("ageYears", it) }
                    onSubmit(body)
                },
                enabled = !actionPending && valid,
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FeedbackDialog(
    workout: JSONObject,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (JSONObject) -> Unit,
) {
    val timed = workout.optString("prescriptionKind") == "timed"
    var status by rememberSaveable { mutableStateOf("done") }
    var distance by rememberSaveable {
        mutableStateOf(
            workout.optDoubleOrNull("targetDistanceMeters")
                ?.div(1_000)
                ?.takeIf { it > 0 }
                ?.let { String.format(Locale.US, "%.1f", it) }
                .orEmpty(),
        )
    }
    var duration by rememberSaveable {
        mutableStateOf(
            workout.optDoubleOrNull("targetDurationSeconds")
                ?.div(60)
                ?.takeIf { it > 0 }
                ?.let { String.format(Locale.US, "%.0f", it) }
                .orEmpty(),
        )
    }
    var harderThanExpected by rememberSaveable { mutableStateOf(false) }
    var painDuringOrAfter by rememberSaveable { mutableStateOf(false) }
    val measurementValid =
        status == "skipped" ||
            (timed && (duration.toDoubleOrNull() ?: 0.0) > 0) ||
            (!timed && (distance.toDoubleOrNull() ?: 0.0) > 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record this run") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { message ->
                    item { Notice(message, isError = true) }
                }
                item {
                    Text(
                        "${workout.optString("scheduledDate")} · ${workout.optString("purpose")}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    ChoiceRow("Completed", status == "done") { status = "done" }
                    ChoiceRow("Completed less than planned", status == "shortened") {
                        status = "shortened"
                    }
                    ChoiceRow("Skipped", status == "skipped") { status = "skipped" }
                }
                if (status != "skipped") {
                    item {
                        NumberField(
                            if (timed) "Completed duration (minutes)" else "Completed distance (km)",
                            if (timed) duration else distance,
                        ) {
                            if (timed) duration = it else distance = it
                        }
                    }
                }
                item {
                    CheckRow("Effort was unusually hard", harderThanExpected) {
                        harderThanExpected = it
                    }
                }
                item {
                    CheckRow("Pain changed or limited this run", painDuringOrAfter) {
                        painDuringOrAfter = it
                    }
                }
                item {
                    Text(
                        "Hard effort changes the next-run advice. Pain adds health guidance. Select both when both were true.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val body = JSONObject()
                        .put("workoutId", workout.optString("id"))
                        .put("status", status)
                        .put("feltHard", harderThanExpected)
                        .put("pain", painDuringOrAfter)
                        .put("choice", "skip_continue")
                    if (status != "skipped") {
                        if (timed) {
                            body.put("completedDurationMinutes", duration.toDouble())
                        } else {
                            body.put("completedDistanceKm", distance.toDouble())
                        }
                    }
                    onSubmit(body)
                },
                enabled = !actionPending && measurementValid,
            ) {
                Text("Save result")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ManualRunDialog(
    actionPending: Boolean,
    defaultDate: String,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (JSONObject) -> Unit,
) {
    var date by rememberSaveable { mutableStateOf(defaultDate) }
    var distance by rememberSaveable { mutableStateOf("") }
    var duration by rememberSaveable { mutableStateOf("") }
    var harderThanExpected by rememberSaveable { mutableStateOf(false) }
    var painDuringOrAfter by rememberSaveable { mutableStateOf(false) }
    val valid =
        runCatching { LocalDate.parse(date) }.isSuccess &&
            (distance.toDoubleOrNull() ?: 0.0) >= 0.1 &&
            (duration.isBlank() || (duration.toDoubleOrNull() ?: 0.0) >= 1)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record an unplanned run") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { message ->
                    item { Notice(message, isError = true) }
                }
                item {
                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it.take(10) },
                        label = { Text("Date (YYYY-MM-DD)") },
                        singleLine = true,
                    )
                }
                item { NumberField("Distance (km)", distance) { distance = it } }
                item { NumberField("Duration (minutes, optional)", duration) { duration = it } }
                item {
                    CheckRow("Felt harder than expected", harderThanExpected) {
                        harderThanExpected = it
                    }
                }
                item {
                    CheckRow("Pain occurred during or after this run", painDuringOrAfter) {
                        painDuringOrAfter = it
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val body = JSONObject()
                        .put("occurredDate", date)
                        .put("distanceKm", distance.toDouble())
                        .put("feltHard", harderThanExpected)
                        .put("pain", painDuringOrAfter)
                    duration.toDoubleOrNull()?.let { body.put("durationMinutes", it) }
                    onSubmit(body)
                },
                enabled = !actionPending && valid,
            ) {
                Text("Record run")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WorkoutAddDialog(
    defaultDate: String,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (JSONObject) -> Unit,
) {
    var scheduledDate by rememberSaveable { mutableStateOf(defaultDate) }
    var type by rememberSaveable { mutableStateOf("easy") }
    var distance by rememberSaveable { mutableStateOf("") }
    var purpose by rememberSaveable { mutableStateOf("Easy aerobic run") }
    var reason by rememberSaveable { mutableStateOf("") }
    var rebalance by rememberSaveable { mutableStateOf(false) }
    val distanceNumber = distance.toDoubleOrNull()
    val valid =
        runCatching { LocalDate.parse(scheduledDate) }.isSuccess &&
            distanceNumber != null &&
            distanceNumber >= 0.1 &&
            purpose.trim().length >= 2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a future run") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { message ->
                    item { Notice(message, isError = true) }
                }
                item {
                    Text(
                        "Runway will show the weekly-load effect before adding it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    OutlinedTextField(
                        value = scheduledDate,
                        onValueChange = { scheduledDate = it.take(10) },
                        label = { Text("Date (YYYY-MM-DD)") },
                        singleLine = true,
                    )
                }
                item {
                    listOf("easy" to "Easy", "long" to "Long", "recovery" to "Recovery")
                        .forEach { (value, label) ->
                            ChoiceRow(label, type == value) { type = value }
                        }
                }
                item { NumberField("Distance (km)", distance) { distance = it } }
                item {
                    OutlinedTextField(
                        value = purpose,
                        onValueChange = { purpose = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Purpose") },
                    )
                }
                item {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Why you’re adding it (optional)") },
                    )
                }
                item {
                    CheckRow("Rebalance the rest of this week", rebalance) { rebalance = it }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(
                        JSONObject()
                            .put("scheduledDate", scheduledDate)
                            .put("type", type)
                            .put("prescriptionKind", "distance")
                            .put("targetDistanceMeters", (distanceNumber!! * 1_000).toInt())
                            .put("targetDurationSeconds", JSONObject.NULL)
                            .put("intervalStructure", JSONObject.NULL)
                            .put("intensity", "easy")
                            .put("purpose", purpose.trim())
                            .put("userReason", reason.trim())
                            .put("rebalance", rebalance)
                            .put("confirmRisk", false),
                    )
                },
                enabled = !actionPending && valid,
            ) {
                Text("Review addition")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WorkoutEditDialog(
    workout: JSONObject,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (JSONObject) -> Unit,
    onRemove: () -> Unit,
) {
    val timed = workout.optString("prescriptionKind") == "timed"
    var scheduledDate by rememberSaveable { mutableStateOf(workout.optString("scheduledDate")) }
    var type by rememberSaveable { mutableStateOf(workout.optString("type").ifBlank { "easy" }) }
    var load by rememberSaveable {
        mutableStateOf(
            if (timed) {
                String.format(
                    Locale.US,
                    "%.0f",
                    workout.optDouble("targetDurationSeconds", 0.0) / 60,
                )
            } else {
                String.format(
                    Locale.US,
                    "%.1f",
                    workout.optDouble("targetDistanceMeters", 0.0) / 1_000,
                )
            },
        )
    }
    var purpose by rememberSaveable { mutableStateOf(workout.optString("purpose")) }
    var reason by rememberSaveable { mutableStateOf("") }
    var rebalance by rememberSaveable { mutableStateOf(false) }
    val loadNumber = load.toDoubleOrNull()
    val isRest = type == "rest"
    val valid =
        runCatching { LocalDate.parse(scheduledDate) }.isSuccess &&
            (isRest || (loadNumber != null && loadNumber > 0)) &&
            purpose.trim().length >= 2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust this workout") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { message ->
                    item { Notice(message, isError = true) }
                }
                item {
                    Text(
                        "Runway will show the weekly-load effect before applying this change.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    OutlinedTextField(
                        value = scheduledDate,
                        onValueChange = { scheduledDate = it.take(10) },
                        label = { Text("Date (YYYY-MM-DD)") },
                        singleLine = true,
                    )
                }
                item {
                    Text("Workout type", style = MaterialTheme.typography.labelLarge)
                    listOf(
                        "easy" to "Easy run",
                        "long" to "Long run",
                        "recovery" to "Recovery run",
                        "rest" to "Rest",
                    )
                        .forEach { (value, label) ->
                            ChoiceRow(label, type == value) { type = value }
                        }
                }
                if (!isRest) {
                    item {
                        NumberField(
                            if (timed) "Duration (minutes)" else "Distance (km)",
                            load,
                        ) { load = it }
                    }
                }
                item {
                    OutlinedTextField(
                        value = purpose,
                        onValueChange = { purpose = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Purpose") },
                    )
                }
                item {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Why you’re changing it (optional)") },
                    )
                }
                item {
                    CheckRow("Rebalance the rest of this week", rebalance) { rebalance = it }
                }
                item {
                    TextButton(onClick = onRemove, enabled = !actionPending) {
                        Text("Remove this workout")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val targetDurationSeconds =
                        if (!isRest && timed) (loadNumber!! * 60).toInt() else null
                    onSubmit(
                        JSONObject()
                            .put("workoutId", workout.optString("id"))
                            .put("scheduledDate", scheduledDate)
                            .put("type", type)
                            .put(
                                "prescriptionKind",
                                if (isRest) "rest" else if (timed) "timed" else "distance",
                            )
                            .put(
                                "targetDistanceMeters",
                                if (isRest || timed) 0 else (loadNumber!! * 1_000).toInt(),
                            )
                            .put(
                                "targetDurationSeconds",
                                targetDurationSeconds ?: JSONObject.NULL,
                            )
                            .put(
                                "intervalStructure",
                                if (!isRest && timed) {
                                    resizeIntervalStructure(
                                        workout.optJSONObject("intervalStructure"),
                                        targetDurationSeconds!!,
                                    )
                                } else {
                                    JSONObject.NULL
                                },
                            )
                            .put("intensity", if (isRest) "rest" else "easy")
                            .put("purpose", purpose.trim())
                            .put("userReason", reason.trim())
                            .put("rebalance", rebalance)
                            .put("confirmRisk", false),
                    )
                },
                enabled = !actionPending && valid,
            ) {
                Text("Review change")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActivityReviewDialog(
    activity: JSONObject,
    candidates: List<JSONObject>,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onAction: (String, JSONObject) -> Unit,
) {
    var harderThanExpected by rememberSaveable(activity.optBoolean("feltHard")) {
        mutableStateOf(activity.optBoolean("feltHard"))
    }
    var painDuringOrAfter by rememberSaveable(activity.optBoolean("pain")) {
        mutableStateOf(activity.optBoolean("pain"))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where does this run belong?") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { message ->
                    item { Notice(message, isError = true) }
                }
                item {
                    ActivityCard(activity)
                }
                item {
                    CheckRow("Felt harder than expected", harderThanExpected) {
                        harderThanExpected = it
                    }
                    CheckRow("Pain occurred during or after this run", painDuringOrAfter) {
                        painDuringOrAfter = it
                    }
                    OutlinedButton(
                        onClick = {
                            onAction(
                                "update_activity_feedback",
                                JSONObject()
                                    .put("activityId", activity.optString("id"))
                                    .put("feltHard", harderThanExpected)
                                    .put("pain", painDuringOrAfter),
                            )
                        },
                        enabled = !actionPending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save how it felt")
                    }
                }
                if (candidates.isNotEmpty()) {
                    item { Text("Match to a planned run", fontWeight = FontWeight.SemiBold) }
                    items(candidates, key = { it.optString("id") }) { workout ->
                        OutlinedButton(
                            onClick = {
                                onAction(
                                    "link_activity",
                                    JSONObject()
                                        .put("activityId", activity.optString("id"))
                                        .put("workoutId", workout.optString("id")),
                                )
                            },
                            enabled = !actionPending,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${workout.optString("scheduledDate")} · " +
                                    workout.optString("purpose").ifBlank { "Planned run" },
                            )
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            onAction(
                                "confirm_activity_extra",
                                JSONObject().put("activityId", activity.optString("id")),
                            )
                        },
                        enabled = !actionPending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Count as an extra run")
                    }
                }
                item {
                    TextButton(
                        onClick = {
                            onAction(
                                "delete_activity",
                                JSONObject().put("activityId", activity.optString("id")),
                            )
                        },
                        enabled = !actionPending,
                    ) {
                        Text("Delete imported activity")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WorkoutPreviewDialog(
    preview: JSONObject,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val risk = preview.optString("risk").replaceFirstChar { it.uppercase() }.ifBlank { "Conservative" }
    val weeklyChange = preview.optDoubleOrNull("weeklyLoadChangePercent")
    val spacingConflicts = preview.optJSONArray("spacingConflicts")?.length() ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review the plan effect") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { Notice(it, isError = true) }
                SettingRow("Load assessment", risk)
                weeklyChange?.let {
                    SettingRow("Largest weekly load change", "${String.format(Locale.US, "%.1f", it)}%")
                }
                if (spacingConflicts > 0) {
                    Notice(
                        "$spacingConflicts nearby run${if (spacingConflicts == 1) "" else "s"} may leave little recovery time.",
                        isError = true,
                    )
                }
                Text(
                    "The original recommendation stays in the adjustment ledger, so this can be undone later.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !actionPending) { Text("Apply change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Go back") } },
    )
}

@Composable
private fun FeedbackOutcomeCard(
    feedback: JSONObject,
    actionPending: Boolean,
    onDecision: (String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recorded result", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val distance = feedback.optDoubleOrNull("completedDistanceMeters")?.let(::formatDistance)
            val duration = feedback.optDoubleOrNull("completedDurationSeconds")?.let(::formatDuration)
            if (distance != null || duration != null) {
                Text(
                    listOfNotNull(distance, duration).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (feedback.optBoolean("pain")) {
                Text("Pain was reported", color = MaterialTheme.colorScheme.error)
            } else if (feedback.optBoolean("feltHard")) {
                Text("This felt harder than expected", color = MaterialTheme.colorScheme.primary)
            }
            feedback.optJSONObject("consequence")?.let {
                ConsequenceChoices(it, actionPending, onDecision)
            }
            onDelete?.let {
                TextButton(onClick = it, enabled = !actionPending) {
                    Text("Remove saved result")
                }
            }
        }
    }
}

@Composable
private fun ConsequenceChoices(
    consequence: JSONObject,
    actionPending: Boolean,
    onDecision: (String) -> Unit,
) {
    val applied = consequence.optNullableString("appliedDecision")
    val options = consequence.optJSONArray("options").asStrings()
    val recommended = consequence.optNullableString("recommendedDecision")
    val deviation = consequence.optNullableString("deviation")
    val risk = consequence.optNullableString("risk")
    if (deviation != null) {
        SettingRow("Plan difference", deviation.replace('_', ' ').replaceFirstChar { it.uppercase() })
    }
    if (risk != null) {
        SettingRow("Assessment", risk.replace('_', ' ').replaceFirstChar { it.uppercase() })
    }
    if (consequence.optString("kind") == "pain_reported") {
        Notice(
            "A plan adjustment is not clearance to continue. Seek qualified guidance if pain is sharp, persists, worsens, or changes your gait.",
            isError = true,
        )
    }
    when {
        applied != null -> {
            Text(
                "Next choice: ${planDecisionLabel(applied)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        consequence.optBoolean("planChangeAvailable", true) && options.isNotEmpty() -> {
            Text(
                "Choose what changes next. The recorded work is already counted.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            options.forEach { decision ->
                val label = planDecisionLabel(decision) +
                    if (decision == recommended) " · Recommended" else ""
                if (decision == recommended) {
                    Button(
                        onClick = { onDecision(decision) },
                        enabled = !actionPending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onDecision(decision) },
                        enabled = !actionPending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanDecisionDialog(
    pending: PendingPlanDecision,
    actionPending: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val risk = pending.consequence.optNullableString("risk")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(planDecisionLabel(pending.decision)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                errorMessage?.let { Notice(it, isError = true) }
                Text(planDecisionExplanation(pending.decision))
                risk?.let {
                    SettingRow(
                        "Current assessment",
                        it.replace('_', ' ').replaceFirstChar { character -> character.uppercase() },
                    )
                }
                Text(
                    "Only future planned work changes. This recorded result remains unchanged.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !actionPending) { Text("Apply choice") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Go back") } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        TextButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        TextButton(onClick = { onCheckedChange(!checked) }) {
            Text(label, textAlign = TextAlign.Start)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            if (next.length <= 10 && next.matches(Regex("\\d*(\\.\\d*)?"))) {
                onValueChange(next)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}

private fun resizeIntervalStructure(structure: JSONObject?, targetSeconds: Int): JSONObject {
    if (structure == null) {
        return JSONObject()
            .put("warmupSeconds", 0)
            .put("cooldownSeconds", 0)
            .put(
                "blocks",
                JSONArray().put(
                    JSONObject()
                        .put("repetitions", 1)
                        .put(
                            "segments",
                            JSONArray().put(
                                JSONObject().put("kind", "run").put("durationSeconds", targetSeconds),
                            ),
                        ),
                ),
            )
    }
    val blocks = structure.optJSONArray("blocks") ?: JSONArray()
    val sourceTotal =
        structure.optInt("warmupSeconds") +
            structure.optInt("cooldownSeconds") +
            (0 until blocks.length()).sumOf { index ->
                val block = blocks.optJSONObject(index) ?: return@sumOf 0
                val segments = block.optJSONArray("segments") ?: JSONArray()
                block.optInt("repetitions", 1) *
                    (0 until segments.length()).sumOf { segmentIndex ->
                        segments.optJSONObject(segmentIndex)?.optInt("durationSeconds") ?: 0
                    }
            }
    if (sourceTotal <= 0) return resizeIntervalStructure(null, targetSeconds)
    val factor = targetSeconds.toDouble() / sourceTotal
    val resizedBlocks = JSONArray()
    for (index in 0 until blocks.length()) {
        val sourceBlock = blocks.optJSONObject(index) ?: continue
        val sourceSegments = sourceBlock.optJSONArray("segments") ?: JSONArray()
        val segments = JSONArray()
        for (segmentIndex in 0 until sourceSegments.length()) {
            val segment = sourceSegments.optJSONObject(segmentIndex) ?: continue
            segments.put(
                JSONObject()
                    .put("kind", segment.optString("kind"))
                    .put(
                        "durationSeconds",
                        (segment.optInt("durationSeconds") * factor).toInt().coerceAtLeast(1),
                    ),
            )
        }
        resizedBlocks.put(
            JSONObject()
                .put("repetitions", sourceBlock.optInt("repetitions", 1))
                .put("segments", segments),
        )
    }
    val resized = JSONObject()
        .put("warmupSeconds", (structure.optInt("warmupSeconds") * factor).toInt())
        .put("cooldownSeconds", (structure.optInt("cooldownSeconds") * factor).toInt())
        .put("blocks", resizedBlocks)
    val resizedTotal =
        resized.optInt("warmupSeconds") +
            resized.optInt("cooldownSeconds") +
            (0 until resizedBlocks.length()).sumOf { index ->
                val block = resizedBlocks.optJSONObject(index) ?: return@sumOf 0
                val segments = block.optJSONArray("segments") ?: JSONArray()
                block.optInt("repetitions", 1) *
                    (0 until segments.length()).sumOf { segmentIndex ->
                        segments.optJSONObject(segmentIndex)?.optInt("durationSeconds") ?: 0
                    }
            }
    resized.put(
        "cooldownSeconds",
        (resized.optInt("cooldownSeconds") + targetSeconds - resizedTotal).coerceAtLeast(0),
    )
    return resized
}

private fun targetMinimumKey(startMode: String): String = when (startMode) {
    "foundation_to_goal" -> "minimumFoundationTargetDate"
    "calibration" -> "minimumCalibrationTargetDate"
    else -> "minimumTargetDate"
}

private val dayLabels = listOf(
    "Sunday",
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
)

@Composable
private fun NativeList(loading: Boolean, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) item { LinearLoadingNotice() }
        content()
    }
}

@Composable
private fun ScreenIntro(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkoutCard(
    workout: JSONObject,
    onRecord: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
) {
    val type = workout.optString("type").replaceFirstChar { it.uppercase() }
    val distance = workout.optDoubleOrNull("targetDistanceMeters")?.let(::formatDistance)
    val duration = workout.optDoubleOrNull("targetDurationSeconds")?.let(::formatDuration)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(workout.optString("scheduledDate"), style = MaterialTheme.typography.labelLarge)
            Text(type.ifBlank { "Run" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(distance, duration).joinToString(" · ").ifBlank { "Use the plan details" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            workout.optString("purpose").takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (workout.optBoolean("isEdited")) {
                Text("Adjusted", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (onRecord != null || onEdit != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onRecord?.let {
                        Button(onClick = it) { Text("Record result") }
                    }
                    onEdit?.let {
                        OutlinedButton(onClick = it) { Text("Adjust") }
                    }
                }
            }
            if (onReset != null || onUndo != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onUndo?.let {
                        TextButton(onClick = it) { Text("Undo last change") }
                    }
                    onReset?.let {
                        TextButton(onClick = it) { Text("Restore recommendation") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    activity: JSONObject,
    title: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val distance = activity.optDoubleOrNull("distanceMeters")?.let(::formatDistance)
    val duration = activity.optDoubleOrNull("durationSeconds")?.let(::formatDuration)
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            Text(
                activity.optString("occurredDate").ifBlank { activity.optString("activityDate") }.ifBlank { "Activity" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(distance, duration, activity.optString("source").takeIf(String::isNotBlank))
                    .joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (activity.optBoolean("pain")) Text("Pain noted", color = MaterialTheme.colorScheme.error)
            else if (activity.optBoolean("feltHard")) Text("Hard effort noted", color = MaterialTheme.colorScheme.primary)
            actions?.invoke()
        }
    }
}

@Composable
private fun WeekCard(week: JSONObject) {
    val target = week.optDoubleOrNull("targetDistanceMeters")?.let(::formatDistance)
    val completed = week.optDoubleOrNull("completedDistanceMeters")?.let(::formatDistance)
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Week ${week.optInt("weekNumber", 0)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${completed ?: "0 km"} completed · ${target ?: "—"} planned")
            Text(
                listOfNotNull(
                    week.optString("startDate").takeIf(String::isNotBlank),
                    week.optString("risk").takeIf(String::isNotBlank)?.replaceFirstChar { it.uppercase() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun EmptyCard(message: String) {
    Card {
        Text(
            message,
            modifier = Modifier.padding(18.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Notice(message: String, isError: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun LinearLoadingNotice() {
    Text(
        "Refreshing…",
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.End,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CenteredSurface(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

private fun JSONArray?.asObjects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) optJSONObject(index)?.let(::add)
    }
}

private fun JSONArray?.asInts(): List<Int> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optInt(index, -1).takeIf { it in 0..6 }?.let(::add)
        }
    }
}

private fun JSONArray?.asStrings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}

private fun pendingPlanDecision(
    source: String,
    record: JSONObject,
    decision: String,
): PendingPlanDecision = PendingPlanDecision(
    source = source,
    sourceId = record.optString("id"),
    decision = decision,
    consequence = record.optJSONObject("consequence") ?: JSONObject(),
)

private fun planDecisionPayload(pending: PendingPlanDecision): JSONObject =
    JSONObject()
        .put("source", pending.source)
        .put("sourceId", pending.sourceId)
        .put("decision", pending.decision)
        .put("confirmRisk", true)

private fun planDecisionLabel(decision: String): String = when (decision) {
    "keep_plan" -> "Keep the plan"
    "reduce_next" -> "Reduce the next run"
    "next_rest" -> "Make the next run a rest day"
    "repeat_prescription" -> "Repeat this prescription"
    "rebalance_week" -> "Rebalance this week"
    else -> decision.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun planDecisionExplanation(decision: String): String = when (decision) {
    "keep_plan" -> "Leave future workouts as they are."
    "reduce_next" -> "Reduce the amount in the next compatible planned run."
    "next_rest" -> "Replace the next compatible planned run with rest."
    "repeat_prescription" -> "Use this prescription again for the next compatible planned run."
    "rebalance_week" -> "Spread the remaining work across compatible runs in this week."
    else -> "Apply this change to future planned work."
}

private fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf(String::isNotBlank) else null

private fun String?.orDash(): String = this?.takeIf(String::isNotBlank) ?: "—"

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

private fun formatDistance(meters: Double): String =
    String.format(Locale.US, "%.1f km", meters / 1_000).replace(".0 km", " km")

private fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60).toInt()
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

private fun monthLabel(month: String): String = runCatching {
    YearMonth.parse(month).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
}.getOrDefault("Calendar")
