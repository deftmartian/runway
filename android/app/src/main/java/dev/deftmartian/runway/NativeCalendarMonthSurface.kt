package dev.deftmartian.runway

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun CalendarMonthLedger(
    month: String,
    today: String,
    selectedDay: String?,
    workouts: List<NativeWorkout>,
    activities: List<NativeActivity>,
    feedbackByWorkout: Map<String, NativeWorkoutFeedback>,
    onDaySelected: (String) -> Unit,
) {
    val calendarMonth = runCatching { YearMonth.parse(month) }.getOrNull()
    if (calendarMonth == null) {
        EmptyCard("Calendar dates are unavailable for this month.")
        return
    }
    val workoutsByDate = workouts.groupBy { it.scheduledDate.orEmpty() }
    val activitiesByDate = activities.groupBy { it.occurredDate.orEmpty().ifBlank { it.activityDate.orEmpty() } }
    val first = calendarMonth.atDay(1)
    val leadingDays = first.dayOfWeek.value % 7
    val monthDays = (1..calendarMonth.lengthOfMonth()).map(calendarMonth::atDay)
    val cells = buildList<LocalDate?> {
        repeat(leadingDays) { add(null) }
        addAll(monthDays)
        repeat((7 - size % 7) % 7) { add(null) }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = usesCompactCalendarLedger(
            availableWidthDp = maxWidth.value,
            fontScale = LocalDensity.current.fontScale,
        )
        if (compact) {
            CalendarDayLedger(
                month = month,
                today = today,
                selectedDay = selectedDay,
                weeks = cells.chunked(7).map { week -> week.filterNotNull() },
                workoutsByDate = workoutsByDate,
                activitiesByDate = activitiesByDate,
                feedbackByWorkout = feedbackByWorkout,
                onDaySelected = onDaySelected,
            )
        } else {
            CalendarMonthGrid(
                today = today,
                selectedDay = selectedDay,
                cells = cells,
                workoutsByDate = workoutsByDate,
                activitiesByDate = activitiesByDate,
                feedbackByWorkout = feedbackByWorkout,
                onDaySelected = onDaySelected,
            )
        }
    }
}

internal val CalendarWideGridMinimumWidth = 980.dp

internal fun usesCompactCalendarLedger(
    availableWidthDp: Float,
    fontScale: Float = 1f,
): Boolean = availableWidthDp < CalendarWideGridMinimumWidth.value * fontScale.coerceAtLeast(1f)

@Composable
private fun CalendarMonthGrid(
    today: String,
    selectedDay: String?,
    cells: List<LocalDate?>,
    workoutsByDate: Map<String, List<NativeWorkout>>,
    activitiesByDate: Map<String, List<NativeActivity>>,
    feedbackByWorkout: Map<String, NativeWorkoutFeedback>,
    onDaySelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("calendar-month-grid"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            dayLabels.forEach { day ->
                Text(
                    day.take(3),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                week.forEach { date ->
                    if (date == null) {
                        Text(
                            "",
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 92.dp),
                        )
                    } else {
                        val dateValue = date.toString()
                        CalendarDayCell(
                            date = date,
                            isToday = dateValue == today,
                            isPast = dateValue < today,
                            isSelected = dateValue == selectedDay,
                            workouts = workoutsByDate[dateValue].orEmpty(),
                            activities = activitiesByDate[dateValue].orEmpty(),
                            feedbackByWorkout = feedbackByWorkout,
                            onClick = { onDaySelected(dateValue) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayLedger(
    month: String,
    today: String,
    selectedDay: String?,
    weeks: List<List<LocalDate>>,
    workoutsByDate: Map<String, List<NativeWorkout>>,
    activitiesByDate: Map<String, List<NativeActivity>>,
    feedbackByWorkout: Map<String, NativeWorkoutFeedback>,
    onDaySelected: (String) -> Unit,
) {
    var expandedQuietWeeks by rememberSaveable(month) {
        mutableStateOf(arrayListOf<String>())
    }
    val locale = LocalLocale.current.platformLocale
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("calendar-day-ledger"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        weeks.filter(List<LocalDate>::isNotEmpty).forEach { days ->
            val weekId = days.first().toString()
            val containsToday = days.any { it.toString() == today }
            val containsSelection = days.any { it.toString() == selectedDay }
            val quiet = days.none { date ->
                val value = date.toString()
                workoutsByDate[value].orEmpty().any { it.isRemoved != true } ||
                    activitiesByDate[value].orEmpty().isNotEmpty()
            }
            val expanded = !quiet || containsToday || containsSelection || weekId in expandedQuietWeeks
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    if (quiet && !containsToday && !containsSelection) {
                        Surface(
                            onClick = {
                                expandedQuietWeeks = ArrayList(expandedQuietWeeks).apply {
                                    if (expanded) remove(weekId) else add(weekId)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("calendar-quiet-week-$weekId")
                                .semantics(mergeDescendants = true) {
                                    contentDescription =
                                        "${calendarWeekRangeLabel(days, locale)}. " +
                                            "No runs planned or recorded. " +
                                            if (expanded) "Hide days" else "Show days"
                                    role = Role.Button
                                },
                            color = Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        calendarWeekRangeLabel(days, locale),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "No runs planned or recorded",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    if (expanded) "Hide" else "Show days",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                calendarWeekRangeLabel(days, locale),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (containsToday) {
                                Text(
                                    "This week",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    if (expanded) {
                        days.forEachIndexed { index, date ->
                            if (index > 0 || quiet) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            val dateValue = date.toString()
                            CalendarLedgerDayRow(
                                date = date,
                                isToday = dateValue == today,
                                isPast = dateValue < today,
                                isSelected = dateValue == selectedDay,
                                workouts = workoutsByDate[dateValue].orEmpty(),
                                activities = activitiesByDate[dateValue].orEmpty(),
                                feedbackByWorkout = feedbackByWorkout,
                                locale = locale,
                                onClick = { onDaySelected(dateValue) },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal data class CalendarLedgerDayPresentation(
    val title: String,
    val detail: String?,
    val stateDescription: String,
    val emphasis: CalendarCellEmphasis,
)

internal fun calendarLedgerDayPresentation(
    workouts: List<NativeWorkout>,
    activities: List<NativeActivity>,
    feedbackByWorkout: Map<String, NativeWorkoutFeedback>,
    routineDateIsPast: Boolean = false,
): CalendarLedgerDayPresentation {
    val activeWorkouts = workouts.filter { it.isRemoved != true }
    val runWorkouts = activeWorkouts.filter { it.type != "rest" }
    val feedback = runWorkouts.mapNotNull { feedbackByWorkout[it.id.orEmpty()] }
    val base = calendarDayPresentation(
        workouts = workouts,
        activities = activities,
        feedbackByWorkout = feedbackByWorkout,
        routineDateIsPast = routineDateIsPast,
    )
    val primaryWorkout = runWorkouts.firstOrNull()
    val workoutTitle = primaryWorkout?.purpose.orEmpty().ifBlank {
        primaryWorkout?.type.orEmpty().replaceFirstChar(Char::uppercase)
    }.ifBlank { "Run" }
    val activity = activities.firstOrNull()
    val activityTitle = activity?.matchedWorkoutPurpose.orEmpty().ifBlank { workoutTitle }
    val activityMeasurement = activity?.let {
        formatPrescriptionMeasurement(it.distanceMeters, it.durationSeconds)
            .takeUnless { value -> value == "Plan details" }
    }
    val firstFeedback = feedback.firstOrNull()
    val feedbackMeasurement = firstFeedback?.let {
        formatPrescriptionMeasurement(it.completedDistanceMeters, it.completedDurationSeconds)
            .takeUnless { value -> value == "Plan details" }
    }
    val reviewActivities = activities.filter {
        it.reviewState == "review" || it.consequence.hasUnappliedPlanDecision()
    }
    val reviewFeedback = feedback.filter { it.consequence.hasUnappliedPlanDecision() }
    val reviewCount = reviewActivities.size + reviewFeedback.size
    val reviewActivity = reviewActivities.firstOrNull()
    val reviewFeedbackRecord = reviewFeedback.firstOrNull()
    val reviewWorkout = runWorkouts.firstOrNull {
        it.id == reviewFeedbackRecord?.workoutId
    }
    val reviewTitle = when {
        reviewCount > 1 -> "$reviewCount items need review"
        reviewActivity != null -> reviewActivity.matchedWorkoutPurpose.orEmpty().ifBlank { "Recorded run" }
        reviewWorkout != null -> reviewWorkout.purpose.orEmpty().ifBlank { workoutTitle }
        else -> "Run needs review"
    }
    val reviewMeasurement = reviewActivity?.let {
        formatPrescriptionMeasurement(it.distanceMeters, it.durationSeconds)
            .takeUnless { value -> value == "Plan details" }
    } ?: reviewFeedbackRecord?.let {
        formatPrescriptionMeasurement(it.completedDistanceMeters, it.completedDurationSeconds)
            .takeUnless { value -> value == "Plan details" }
    }

    return when {
        base.emphasis == CalendarCellEmphasis.Review -> CalendarLedgerDayPresentation(
            title = reviewTitle,
            detail = listOfNotNull("Needs review", reviewMeasurement).joinToString(" · "),
            stateDescription = "needs review",
            emphasis = base.emphasis,
        )
        feedback.size > 1 -> {
            val outcomes = feedback.map { record ->
                when (record.completionState) {
                    "skipped" -> "skipped"
                    "shortened" -> "shortened"
                    else -> "completed"
                }
            }
            val detail = listOf("completed", "shortened", "skipped")
                .mapNotNull { outcome ->
                    outcomes.count { it == outcome }
                        .takeIf { it > 0 }
                        ?.let { count -> "$count $outcome" }
                }
                .joinToString(" · ")
            CalendarLedgerDayPresentation(
                title = "${feedback.size} run results",
                detail = detail,
                stateDescription = detail,
                emphasis = base.emphasis,
            )
        }
        firstFeedback != null -> {
            val outcome = when (firstFeedback.completionState) {
                "skipped" -> "Skipped"
                "shortened" -> "Shortened"
                else -> "Completed"
            }
            CalendarLedgerDayPresentation(
                title = workoutTitle,
                detail = listOfNotNull(outcome, feedbackMeasurement).joinToString(" · "),
                stateDescription = outcome.lowercase(),
                emphasis = base.emphasis,
            )
        }
        activity != null -> CalendarLedgerDayPresentation(
            title = activityTitle.ifBlank { "Recorded run" },
            detail = listOfNotNull("Recorded", activityMeasurement).joinToString(" · "),
            stateDescription = "recorded",
            emphasis = base.emphasis,
        )
        routineDateIsPast && runWorkouts.any { it.planPhase == "routine" } ->
            CalendarLedgerDayPresentation(
                title = if (runWorkouts.size == 1) {
                    calendarWorkoutPlanSummary(runWorkouts.single())
                } else {
                    "${runWorkouts.size} routine runs"
                },
                detail = "Not recorded",
                stateDescription = "not recorded",
                emphasis = base.emphasis,
            )
        runWorkouts.size == 1 -> CalendarLedgerDayPresentation(
            title = calendarWorkoutPlanSummary(runWorkouts.single()),
            detail = if (runWorkouts.single().isEdited == true) "Edited plan" else "Planned",
            stateDescription = if (runWorkouts.single().isEdited == true) "edited plan" else "planned",
            emphasis = base.emphasis,
        )
        runWorkouts.size > 1 -> CalendarLedgerDayPresentation(
            title = "${runWorkouts.size} runs planned",
            detail = runWorkouts.joinToString(" · ", transform = ::calendarWorkoutPlanSummary),
            stateDescription = "${runWorkouts.size} runs planned",
            emphasis = base.emphasis,
        )
        else -> CalendarLedgerDayPresentation(
            title = "No run planned",
            detail = null,
            stateDescription = "no run planned",
            emphasis = CalendarCellEmphasis.Neutral,
        )
    }
}

@Composable
private fun CalendarLedgerDayRow(
    date: LocalDate,
    isToday: Boolean,
    isPast: Boolean,
    isSelected: Boolean,
    workouts: List<NativeWorkout>,
    activities: List<NativeActivity>,
    feedbackByWorkout: Map<String, NativeWorkoutFeedback>,
    locale: Locale,
    onClick: () -> Unit,
) {
    val presentation = calendarLedgerDayPresentation(
        workouts = workouts,
        activities = activities,
        feedbackByWorkout = feedbackByWorkout,
        routineDateIsPast = isPast,
    )
    val description = buildString {
        append(friendlyDate(date.toString()))
        if (isToday) append(", today")
        if (isSelected) append(", selected")
        append(", ${presentation.title}")
        presentation.detail?.let { append(", $it") }
        append(". View day details")
    }
    val unscheduled = presentation.title == "No run planned"
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag("calendar-ledger-day-$date")
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            },
        color = calendarDayContainerColor(isToday, isSelected, presentation.emphasis),
        shape = MaterialTheme.shapes.small,
        border = calendarDayBorder(isToday, isSelected),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.width(58.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Medium,
                )
                if (isToday) {
                    Text(
                        "Today",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    presentation.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (unscheduled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (unscheduled) FontWeight.Normal else FontWeight.SemiBold,
                )
                presentation.detail?.let { detail ->
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun calendarWeekRangeLabel(days: List<LocalDate>, locale: Locale): String {
    val first = days.first()
    val last = days.last()
    val month = first.month.getDisplayName(TextStyle.SHORT, locale)
    return if (first == last) "$month ${first.dayOfMonth}" else "$month ${first.dayOfMonth}–${last.dayOfMonth}"
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    isToday: Boolean,
    isPast: Boolean,
    isSelected: Boolean,
    workouts: List<NativeWorkout>,
    activities: List<NativeActivity>,
    feedbackByWorkout: Map<String, NativeWorkoutFeedback>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = calendarLedgerDayPresentation(
        workouts,
        activities,
        feedbackByWorkout,
        routineDateIsPast = isPast,
    )
    val description = buildString {
        append(friendlyDate(date.toString()))
        if (isToday) append(", today")
        if (isSelected) append(", selected")
        append(", ${presentation.title}")
        presentation.detail?.let { append(", $it") }
        append(". View day details")
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 92.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
            },
        shape = MaterialTheme.shapes.small,
        color = calendarDayContainerColor(isToday, isSelected, presentation.emphasis),
        border = calendarDayBorder(isToday, isSelected),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            )
            if (presentation.title != "No run planned") {
                val labelColor = when {
                    isSelected || isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                    presentation.emphasis == CalendarCellEmphasis.Review ->
                        RunwayThemeTokens.onReviewContainer
                    else -> presentation.emphasis.color()
                }
                Text(
                    presentation.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = labelColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                presentation.detail?.let { detail ->
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (isToday) {
                Text(
                    "Today",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

internal enum class CalendarCellEmphasis { Neutral, Planned, Actual, Review }

internal data class CalendarDayPresentation(
    val label: String?,
    val stateDescription: String?,
    val emphasis: CalendarCellEmphasis,
)

internal fun calendarDayPresentation(
    workouts: List<NativeWorkout>,
    activities: List<NativeActivity>,
    feedbackByWorkout: Map<String, NativeWorkoutFeedback>,
    routineDateIsPast: Boolean = false,
): CalendarDayPresentation {
    val activeWorkouts = workouts.filter { it.isRemoved != true }
    val runWorkouts = activeWorkouts.filter { it.type != "rest" }
    val feedback = runWorkouts.mapNotNull { feedbackByWorkout[it.id.orEmpty()] }
    val reviewNeeded =
        activities.any { it.reviewState == "review" || it.consequence.hasUnappliedPlanDecision() } ||
            feedback.any { it.consequence.hasUnappliedPlanDecision() }
    if (reviewNeeded) {
        return CalendarDayPresentation("! Review", "needs review", CalendarCellEmphasis.Review)
    }
    if (feedback.isNotEmpty()) {
        return when {
            feedback.any { it.completionState == "skipped" } ->
                CalendarDayPresentation("— Skipped", "skipped", CalendarCellEmphasis.Neutral)
            feedback.any { it.completionState == "shortened" } ->
                CalendarDayPresentation("↘ Short", "shortened", CalendarCellEmphasis.Actual)
            else -> CalendarDayPresentation("✓ Done", "completed", CalendarCellEmphasis.Actual)
        }
    }
    if (activities.isNotEmpty()) {
        val label = if (activities.size > 1) {
            "✓ ${activities.size} done"
        } else {
            val activity = activities.single()
            val amount = formatPrescriptionMeasurement(
                activity.distanceMeters,
                activity.durationSeconds,
            )
            "✓ ${amount.takeUnless { it == "Plan details" } ?: "Done"}"
        }
        return CalendarDayPresentation(label, "recorded", CalendarCellEmphasis.Actual)
    }
    if (routineDateIsPast && runWorkouts.any { it.planPhase == "routine" }) {
        return CalendarDayPresentation("— Not recorded", "not recorded", CalendarCellEmphasis.Neutral)
    }
    if (runWorkouts.any { it.isEdited == true }) {
        return CalendarDayPresentation("↺ Edited", "edited plan", CalendarCellEmphasis.Planned)
    }
    if (runWorkouts.isNotEmpty()) {
        val label = if (runWorkouts.size > 1) {
            "${runWorkouts.size} runs"
        } else {
            formatPlannedPrescriptionMeasurement(
                runWorkouts.single().targetDistanceMeters,
                runWorkouts.single().targetDurationSeconds,
                open = runWorkouts.single().prescriptionKind == "open",
            ).takeUnless { it == "Plan details" } ?: "Run"
        }
        return CalendarDayPresentation(label, "planned", CalendarCellEmphasis.Planned)
    }
    return CalendarDayPresentation(null, null, CalendarCellEmphasis.Neutral)
}

private fun NativeConsequence?.hasUnappliedPlanDecision(): Boolean =
    this?.planChangeAvailable == true && appliedDecision == null

@Composable
private fun calendarDayContainerColor(
    isToday: Boolean,
    isSelected: Boolean,
    emphasis: CalendarCellEmphasis,
) = when {
    isSelected -> MaterialTheme.colorScheme.primaryContainer
    isToday -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    emphasis == CalendarCellEmphasis.Actual ->
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
    emphasis == CalendarCellEmphasis.Review -> RunwayThemeTokens.reviewContainer
    emphasis == CalendarCellEmphasis.Planned ->
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
    else -> Color.Transparent
}

@Composable
private fun calendarDayBorder(isToday: Boolean, isSelected: Boolean) =
    if (isSelected || isToday) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }

@Composable
private fun CalendarCellEmphasis.color() = when (this) {
    CalendarCellEmphasis.Neutral -> RunwayThemeTokens.neutral
    CalendarCellEmphasis.Planned -> RunwayThemeTokens.planned
    CalendarCellEmphasis.Actual -> RunwayThemeTokens.actual
    CalendarCellEmphasis.Review -> RunwayThemeTokens.review
}
