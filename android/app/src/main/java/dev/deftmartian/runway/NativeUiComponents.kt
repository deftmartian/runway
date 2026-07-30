package dev.deftmartian.runway

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeList(
    loading: Boolean,
    state: LazyListState = rememberLazyListState(),
    bottomContentPadding: Dp = 18.dp,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .fillMaxHeight(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 18.dp,
                end = 16.dp,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (loading) item { LinearLoadingNotice() }
            content()
        }
    }
}

@Composable
internal fun ScreenIntro(title: String, body: String) {
    val railColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = railColor,
                    start = Offset(0f, 2.dp.toPx()),
                    end = Offset(0f, size.height - 2.dp.toPx()),
                    strokeWidth = 3.dp.toPx(),
                )
            }
            .padding(start = 13.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun WorkoutCard(
    workout: NativeWorkout,
    onRecord: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
) {
    val type = workout.type.orEmpty().replaceFirstChar { it.uppercase() }.ifBlank { "Run" }
    val isRest = workout.type == "rest"
    val measurement = formatPrescriptionMeasurement(
        distanceMeters = workout.targetDistanceMeters,
        durationSeconds = workout.targetDurationSeconds,
        rest = isRest,
    )
    val emphasis = if (isRest) LedgerEmphasis.Neutral else LedgerEmphasis.Planned
    LedgerSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                workout.scheduledDate?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(type, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                workout.purpose?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LedgerState(
                text = when {
                    isRest -> "Rest"
                    workout.isEdited == true -> "↺ Edited"
                    else -> workout.status.orEmpty().replaceFirstChar { it.uppercase() }
                },
                emphasis = if (workout.isEdited == true) LedgerEmphasis.Review else emphasis,
            )
        }
        Spacer(Modifier.height(12.dp))
        MeasurementReadout(if (isRest) "Schedule" else "Planned", measurement, emphasis)
        formatTimedStructure(workout.intervalStructure)?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (workout.isEdited == true && workout.generatedIntervalStructure != workout.intervalStructure) {
            formatTimedStructure(workout.generatedIntervalStructure)?.let { original ->
                Text(
                    "Original: $original",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onRecord != null || onEdit != null) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onRecord?.let {
                    Button(onClick = it, shape = MaterialTheme.shapes.small) {
                        Text("Record result")
                    }
                }
                onEdit?.let {
                    OutlinedButton(onClick = it, shape = MaterialTheme.shapes.small) {
                        Text("Adjust")
                    }
                }
            }
        }
        if (onReset != null || onUndo != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onUndo?.let { TextButton(onClick = it) { Text("Undo last change") } }
                onReset?.let { TextButton(onClick = it) { Text("Restore recommendation") } }
            }
        }
    }
}

@Composable
internal fun ActivityCard(
    activity: NativeActivity,
    title: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val measurement = listOfNotNull(
        activity.distanceMeters?.takeIf { it > 0 }?.let(::formatDistance),
        activity.durationSeconds?.let(::formatDuration),
    ).joinToString(" · ").ifBlank { "Recorded activity" }
    val date = activity.occurredDate.orEmpty().ifBlank { activity.activityDate.orEmpty() }.ifBlank { "Activity" }
    val state = when {
        activity.pain == true -> "! Pain noted"
        activity.feltHard == true -> "Hard effort"
        activity.reviewState == "review" -> "! Needs review"
        else -> "✓ Accepted"
    }
    val emphasis = when {
        activity.pain == true -> LedgerEmphasis.Danger
        activity.feltHard == true || activity.reviewState == "review" -> LedgerEmphasis.Review
        else -> LedgerEmphasis.Actual
    }
    LedgerSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                Text(date, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                activity.source?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LedgerState(state, emphasis)
        }
        Spacer(Modifier.height(12.dp))
        MeasurementReadout("Actual", measurement, emphasis)
        actions?.let {
            Spacer(Modifier.height(10.dp))
            it()
        }
    }
}

@Composable
internal fun WeekCard(week: NativeWeek, summary: NativeWeekSummary?) {
    val planned = summary?.targetDistanceMeters ?: week.targetDistanceMeters
    val actual = summary?.completedDistanceMeters ?: week.completedDistanceMeters
    val usesDuration =
        (planned ?: 0.0) <= 0 &&
            (week.targetDurationSeconds ?: 0.0) > 0
    val plannedReadout = if (usesDuration) {
        week.targetDurationSeconds?.let(::formatDuration)
    } else {
        planned?.let(::formatDistance)
    }
    val actualReadout = if (usesDuration) {
        summary?.completedDurationSeconds?.let(::formatDuration)
    } else {
        actual?.let(::formatDistance)
    }
    LedgerSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Week ${week.weekNumber ?: 0}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                week.startDate?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (week.hasMixedLoad == true) {
                LedgerState("Non-comparable", LedgerEmphasis.Neutral)
            } else week.risk?.takeIf(String::isNotBlank)?.let {
                LedgerState(nativeRampAssessment(it).label, LedgerEmphasis.Neutral)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            MeasurementReadout("Planned", plannedReadout.orDash(), LedgerEmphasis.Planned, Modifier.weight(1f))
            MeasurementReadout("Actual", actualReadout.orDash(), LedgerEmphasis.Actual, Modifier.weight(1f))
        }
        summary?.let {
            val count = listOfNotNull(it.completedRuns, it.plannedRuns).joinToString(" / ")
            if (count.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Runs recorded / planned: $count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SettingCard(title: String, content: @Composable () -> Unit) {
    LedgerSurface {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
internal fun SettingRow(label: String, value: String, monospace: Boolean = false) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stacked = usesStackedSettingRow(
            label = label,
            value = value,
            monospace = monospace,
            availableWidthDp = maxWidth.value,
            fontScale = LocalDensity.current.fontScale,
        )
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(
                    value,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(
                    value,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

internal fun usesStackedSettingRow(
    label: String,
    value: String,
    monospace: Boolean,
    availableWidthDp: Float,
    fontScale: Float,
): Boolean =
    availableWidthDp < 300f ||
        value.length > 24 ||
        label.length > 28 ||
        (monospace && value.length > 18) ||
        (fontScale > 1.15f && label.length + value.length > 26)

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun EmptyCard(message: String) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(vertical = 16.dp, horizontal = 14.dp), verticalAlignment = Alignment.Top) {
            Spacer(Modifier.width(3.dp).height(34.dp).background(MaterialTheme.colorScheme.outline))
            Spacer(Modifier.width(12.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun Notice(message: String, isError: Boolean = false) {
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
internal fun MeasurementReadout(
    label: String,
    value: String,
    emphasis: LedgerEmphasis,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = emphasis.color(),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun LedgerState(text: String, emphasis: LedgerEmphasis) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = emphasis.color(),
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.End,
    )
}

internal enum class LedgerEmphasis { Planned, Actual, Review, Danger, Neutral }

@Composable
private fun LedgerEmphasis.color() = when (this) {
    LedgerEmphasis.Planned -> RunwayThemeTokens.planned
    LedgerEmphasis.Actual -> RunwayThemeTokens.actual
    LedgerEmphasis.Review -> RunwayThemeTokens.review
    LedgerEmphasis.Danger -> RunwayThemeTokens.danger
    LedgerEmphasis.Neutral -> RunwayThemeTokens.neutral
}

@Composable
internal fun LedgerSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            content = content,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
internal fun CenteredSurface(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}
