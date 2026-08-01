package dev.deftmartian.runway

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.max

private data class NativeWeeklyTrace(
    val label: String,
    val recommendation: Double?,
    val current: Double?,
    val acceptedActual: Double?,
    val summary: NativeWeekSummary?,
)

private enum class NativeTraceStyle {
    Generated,
    Current,
    Actual,
}

internal data class NativeNoActiveStatsSummary(
    val statusMessage: String,
    val recordedHistory: NativeRecordedHistorySummary?,
    val acceptedHeartRate: NativeHeartRateSample?,
)

/**
 * A plan-free stats state must stay factual. It can expose recorded work and accepted
 * heart-rate context, but it must not turn an archived plan or an activity total into a current
 * recommendation.
 */
internal fun noActiveStatsSummary(
    history: NativeTrainingHistory?,
): NativeNoActiveStatsSummary {
    val recorded = history?.recordedSummary?.takeIf {
        history.hasAcceptedActivities == true ||
            (it.totalRuns ?: 0) > 0 ||
            (it.totalDistanceMeters ?: 0.0) > 0.0 ||
            (it.totalDurationSeconds ?: 0.0) > 0.0
    }
    val acceptedHeartRate = history?.heartRateSample?.takeIf {
        (it.sampleCount ?: 0) > 0
    }
    val status = when {
        recorded != null || acceptedHeartRate != null ->
            "There is no active schedule or recommendation. Recorded work remains available below."
        else ->
            "There is no active schedule or recommendation. Add and review a run, or set up running, to see comparisons here."
    }
    return NativeNoActiveStatsSummary(status, recorded, acceptedHeartRate)
}

/** Plans become comparable only after recorded work exists. */
internal fun hasRecordedStatsHistory(history: NativeTrainingHistory?): Boolean =
    (history?.recordedSummary?.totalRuns ?: 0) > 0 ||
        history?.hasAcceptedActivities == true ||
        (history?.recentFeedbackCount ?: 0) > 0 ||
        history?.weeklySummaries.orEmpty().any {
            (it.completedRuns ?: 0) > 0 || (it.skippedRuns ?: 0) > 0
        }

/**
 * Shows the generated recommendation, the runner's current
 * editable plan, and only accepted actual activity.  The table-like ledger below each chart is
 * deliberately retained for TalkBack and for runners who need exact values rather than a shape.
 */
@Composable
internal fun NativeStatsTraces(
    planTrace: List<NativePlanTraceWeek>,
    weeklySummaries: List<NativeWeekSummary>,
) {
    val summariesByStart = weeklySummaries.associateBy { it.startDate.orEmpty() }
    val distance = planTrace.map { trace ->
        NativeWeeklyTrace(
            label = "Week ${trace.weekNumber ?: 0}",
            recommendation = trace.recommendedDistanceMeters,
            current = trace.currentDistanceMeters,
            acceptedActual = summariesByStart[trace.startDate.orEmpty()]?.completedDistanceMeters,
            summary = summariesByStart[trace.startDate.orEmpty()],
        )
    }
    val duration = planTrace.map { trace ->
        NativeWeeklyTrace(
            label = "Week ${trace.weekNumber ?: 0}",
            recommendation = trace.recommendedDurationSeconds,
            current = trace.currentDurationSeconds,
            acceptedActual = summariesByStart[trace.startDate.orEmpty()]?.completedDurationSeconds,
            summary = summariesByStart[trace.startDate.orEmpty()],
        )
    }

    if (distance.hasPositiveMeasurement()) {
        NativeWeeklyTraceChart(
            title = "Weekly distance",
            points = distance,
            format = ::formatDistance,
        )
    }
    if (duration.hasPositiveMeasurement()) {
        NativeWeeklyTraceChart(
            title = "Weekly training time",
            points = duration,
            format = ::formatDuration,
            plannedFormat = ::formatPlannedDurationEstimate,
        )
    }
    NativeAcceptedContext(weeklySummaries)
}

/** Routine charts contain observations only; there is no generated or current load target. */
@Composable
internal fun NativeRoutineStatsTraces(
    weeklySummaries: List<NativeWeekSummary>,
    todayIso: String?,
    runsPerWeek: Int?,
) {
    NativeRoutineFrequencyLedger(weeklySummaries, todayIso, runsPerWeek)
    val distance = weeklySummaries.map { summary ->
        NativeWeeklyTrace(
            label = "Week ${summary.weekNumber ?: 0}",
            recommendation = null,
            current = null,
            acceptedActual = summary.completedDistanceMeters,
            summary = summary,
        )
    }
    val duration = weeklySummaries.map { summary ->
        NativeWeeklyTrace(
            label = "Week ${summary.weekNumber ?: 0}",
            recommendation = null,
            current = null,
            acceptedActual = summary.completedDurationSeconds,
            summary = summary,
        )
    }
    if (distance.hasPositiveMeasurement()) {
        NativeWeeklyTraceChart("Recorded weekly distance", distance, ::formatDistance, actualOnly = true)
    }
    if (duration.hasPositiveMeasurement()) {
        NativeWeeklyTraceChart("Recorded weekly time", duration, ::formatDuration, actualOnly = true)
    }
    NativeAcceptedContext(weeklySummaries)
}

@Composable
private fun NativeRoutineFrequencyLedger(
    weeklySummaries: List<NativeWeekSummary>,
    todayIso: String?,
    runsPerWeek: Int?,
) {
    if (weeklySummaries.isEmpty()) return
    var showAll by rememberSaveable { mutableStateOf(false) }
    val recentCount = 8
    val visible = if (showAll) weeklySummaries else weeklySummaries.takeLast(recentCount)
    SettingCard("Runs each week") {
        Text(
            runsPerWeek?.takeIf { it > 0 }?.let {
                "Your routine is $it ${if (it == 1) "run" else "runs"} each week. Distance and time remain optional observations."
            } ?: "Scheduled runs are shown by week. Distance and time remain optional observations.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        visible.forEach { summary ->
            SettingRow(
                routineWeekLabel(summary, todayIso),
                routineRunCountSummary(summary),
            )
        }
        if (weeklySummaries.size > recentCount) {
            TextButton(
                onClick = { showAll = !showAll },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showAll) "Show recent weeks" else "Show earlier weeks")
            }
        }
    }
}

internal fun routineRunCountSummary(summary: NativeWeekSummary): String = buildList {
    val extras = summary.extraRuns ?: 0
    val plannedRecorded = summary.plannedRunsRecorded
        ?: ((summary.completedRuns ?: 0) - extras).coerceAtLeast(0)
    add("${summary.completedRuns ?: 0} recorded")
    if (extras > 0) {
        add("$plannedRecorded on scheduled days")
        add("$extras on other days")
    }
    add("${summary.plannedRuns ?: 0} scheduled")
    summary.missedRuns?.takeIf { it > 0 }?.let { add("$it not recorded") }
    summary.skippedRuns?.takeIf { it > 0 }?.let { add("$it skipped") }
}.joinToString(" · ")

internal fun routineWeekLabel(summary: NativeWeekSummary, todayIso: String?): String {
    val start = summary.startDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        ?: return "Week ${summary.weekNumber ?: 0}"
    val today = todayIso?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    return if (today != null && today in start..start.plusDays(6)) {
        "This week"
    } else {
        friendlyDate(start.toString())
    }
}

private fun List<NativeWeeklyTrace>.hasPositiveMeasurement(): Boolean = any { point ->
    listOf(point.recommendation, point.current, point.acceptedActual).any { (it ?: 0.0) > 0.0 }
}

@Composable
private fun NativeWeeklyTraceChart(
    title: String,
    points: List<NativeWeeklyTrace>,
    format: (Double) -> String,
    plannedFormat: (Double) -> String = format,
    actualOnly: Boolean = false,
) {
    var showExactValues by rememberSaveable(title) { mutableStateOf(false) }
    val recommendationColor = MaterialTheme.colorScheme.primary
    val currentColor = MaterialTheme.colorScheme.secondary
    val actualColor = MaterialTheme.colorScheme.tertiary
    val railColor = MaterialTheme.colorScheme.outlineVariant
    SettingCard(title) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!actualOnly) {
                NativeTraceLegend("Generated · dashed", recommendationColor, NativeTraceStyle.Generated)
                NativeTraceLegend("Current · solid", currentColor, NativeTraceStyle.Current)
            }
            NativeTraceLegend(
                if (actualOnly) "Recorded · square" else "Accepted actual · square",
                actualColor,
                NativeTraceStyle.Actual,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .semantics {
                    contentDescription = if (actualOnly) {
                        "$title chart. Recorded values only. Open Weekly values for each week."
                    } else {
                        nativeTraceChartDescription(title)
                    }
                },
        ) {
            val left = 14.dp.toPx()
            val right = size.width - 14.dp.toPx()
            val top = 18.dp.toPx()
            val bottom = size.height - 24.dp.toPx()
            val maximum = points.flatMap { listOfNotNull(it.recommendation, it.current, it.acceptedActual) }
                .maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
            val span = max(right - left, 1f)
            val step = if (points.size > 1) span / (points.size - 1) else 0f
            drawLine(railColor, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
            drawLine(railColor, Offset(left, top), Offset(right, top), 1.dp.toPx())
            if (!actualOnly) {
                drawNativeTraceLine(
                    points,
                    { it.recommendation },
                    recommendationColor,
                    NativeTraceStyle.Generated,
                    maximum,
                    left,
                    step,
                    top,
                    bottom,
                )
                drawNativeTraceLine(
                    points,
                    { it.current },
                    currentColor,
                    NativeTraceStyle.Current,
                    maximum,
                    left,
                    step,
                    top,
                    bottom,
                )
            }
            drawNativeTraceLine(
                points,
                { it.acceptedActual },
                actualColor,
                NativeTraceStyle.Actual,
                maximum,
                left,
                step,
                top,
                bottom,
            )
        }
        TextButton(
            onClick = { showExactValues = !showExactValues },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    stateDescription = if (showExactValues) "Expanded" else "Collapsed"
                },
            shape = MaterialTheme.shapes.small,
        ) {
            Text(if (showExactValues) "Hide weekly values" else "Weekly values")
        }
        if (showExactValues) {
            points.forEach { point ->
                if (!actualOnly) {
                    NativeTraceValueRow(
                        "${point.label} · generated",
                        point.recommendation?.let(plannedFormat).orDash(),
                    )
                    NativeTraceValueRow(
                        "${point.label} · current",
                        point.current?.let(plannedFormat).orDash(),
                    )
                }
                NativeTraceValueRow(
                    "${point.label} · ${if (actualOnly) "recorded" else "accepted actual"}",
                    point.acceptedActual?.let(format).orDash(),
                )
            }
        }
    }
}

internal fun nativeTraceChartDescription(title: String): String =
    "$title chart. Generated recommendation, current plan, and accepted actual are shown. Open Weekly values for each week."

internal fun usesStackedNativeTraceRow(availableWidthDp: Float, fontScale: Float): Boolean =
    availableWidthDp < 480f || fontScale > 1f

@Composable
internal fun NativeTraceValueRow(label: String, value: String) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (usesStackedNativeTraceRow(maxWidth.value, LocalDensity.current.fontScale)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(
                    value,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                )
            }
        } else {
            SettingRow(label, value, monospace = true)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNativeTraceLine(
    points: List<NativeWeeklyTrace>,
    value: (NativeWeeklyTrace) -> Double?,
    color: Color,
    style: NativeTraceStyle,
    maximum: Double,
    left: Float,
    step: Float,
    top: Float,
    bottom: Float,
) {
    var previous: Offset? = null
    points.forEachIndexed { index, point ->
        val currentValue = value(point)
        if (currentValue == null) {
            previous = null
            return@forEachIndexed
        }
        val x = if (points.size == 1) size.width / 2 else left + step * index
        val y = bottom - (bottom - top) * (currentValue / maximum).coerceIn(0.0, 1.0).toFloat()
        val current = Offset(x, y)
        previous?.let {
            drawLine(
                color = color,
                start = it,
                end = current,
                strokeWidth = if (style == NativeTraceStyle.Current) 3.dp.toPx() else 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (style == NativeTraceStyle.Generated) {
                    PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 6.dp.toPx()))
                } else {
                    null
                },
            )
        }
        when (style) {
            NativeTraceStyle.Generated ->
                drawCircle(color, 4.dp.toPx(), current, style = Stroke(width = 2.dp.toPx()))
            NativeTraceStyle.Current ->
                drawCircle(color, 4.dp.toPx(), current)
            NativeTraceStyle.Actual -> {
                val radius = 4.dp.toPx()
                drawRect(
                    color = color,
                    topLeft = Offset(current.x - radius, current.y - radius),
                    size = Size(radius * 2, radius * 2),
                )
            }
        }
        previous = current
    }
}

@Composable
private fun NativeTraceLegend(
    label: String,
    color: Color,
    style: NativeTraceStyle,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.width(18.dp).height(12.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            drawLine(
                color = color,
                start = Offset(0f, center.y),
                end = Offset(size.width, center.y),
                strokeWidth = if (style == NativeTraceStyle.Current) 3.dp.toPx() else 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (style == NativeTraceStyle.Generated) {
                    PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
                } else {
                    null
                },
            )
            when (style) {
                NativeTraceStyle.Generated ->
                    drawCircle(color, 3.dp.toPx(), center, style = Stroke(width = 1.dp.toPx()))
                NativeTraceStyle.Current -> drawCircle(color, 3.dp.toPx(), center)
                NativeTraceStyle.Actual -> {
                    val radius = 3.dp.toPx()
                    drawRect(
                        color,
                        Offset(center.x - radius, center.y - radius),
                        Size(radius * 2, radius * 2),
                    )
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun NativeAcceptedContext(summaries: List<NativeWeekSummary>) {
    val contextualWeeks = summaries.filter {
        it.averagePaceSecondsPerKm != null || it.averageHeartRate != null ||
            (it.hardFlags ?: 0) > 0 || (it.painFlags ?: 0) > 0
    }
    if (contextualWeeks.isEmpty()) return
    SettingCard("Accepted-record context") {
        Text(
            "Pace and heart-rate values describe accepted records only. They do not diagnose effort or change the plan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        contextualWeeks.forEach { week ->
            val label = "Week ${week.weekNumber ?: 0}"
            week.averagePaceSecondsPerKm?.let { SettingRow("$label · average pace", formatPace(it)) }
            week.averageHeartRate?.let { SettingRow("$label · average heart rate", "$it bpm") }
            if ((week.hardFlags ?: 0) > 0) SettingRow("$label · felt harder than expected", week.hardFlags.toString())
            if ((week.painFlags ?: 0) > 0) SettingRow("$label · reported pain", week.painFlags.toString())
        }
    }
}

@Composable
internal fun NativeNoActiveStats(summary: NativeNoActiveStatsSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EmptyCard(summary.statusMessage)
        summary.recordedHistory?.let { recorded ->
            SettingCard("Recorded history") {
                Text(
                    "Completed and accepted work kept by runway. With no active plan, these are facts rather than a plan comparison.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingRow("Recorded runs", (recorded.totalRuns ?: 0).toString())
                SettingRow("Recorded distance", formatDistance(recorded.totalDistanceMeters ?: 0.0))
                if ((recorded.totalDurationSeconds ?: 0.0) > 0.0) {
                    SettingRow("Recorded time", formatDuration(recorded.totalDurationSeconds ?: 0.0))
                }
                if ((recorded.longestRunMeters ?: 0.0) > 0.0) {
                    SettingRow("Longest recorded run", formatDistance(recorded.longestRunMeters ?: 0.0))
                }
                if ((recorded.archivedPlanRuns ?: 0) > 0) {
                    SettingRow(
                        "Archived-plan work",
                        "${recorded.archivedPlanRuns ?: 0} runs · ${formatDistance(recorded.archivedPlanDistanceMeters ?: 0.0)}",
                    )
                }
                if ((recorded.unlinkedRuns ?: 0) > 0) {
                    SettingRow(
                        "Unmatched records",
                        "${recorded.unlinkedRuns ?: 0} runs · ${formatDistance(recorded.unlinkedDistanceMeters ?: 0.0)}",
                    )
                }
            }
        }
        summary.acceptedHeartRate?.let { sample ->
            SettingCard("Accepted heart-rate context") {
                Text(
                    "Descriptive values from accepted activities only. They do not create a recommendation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val window = listOfNotNull(
                    sample.windowDays?.let { "$it days" },
                    sample.windowEnd?.takeIf(String::isNotBlank)?.let { "ending $it" },
                ).joinToString(" · ")
                if (window.isNotBlank()) SettingRow("Window", window)
                SettingRow("Runs with heart rate", (sample.sampleCount ?: 0).toString())
                sample.averageHeartRate?.let { SettingRow("Average heart rate", "$it bpm") }
                if ((sample.highZoneSeconds ?: 0.0) > 0.0) {
                    SettingRow("Recorded high-zone time", formatDuration(sample.highZoneSeconds ?: 0.0))
                }
                sample.latest?.maxHeartRate?.let { SettingRow("Latest maximum", "$it bpm") }
            }
        }
    }
}
