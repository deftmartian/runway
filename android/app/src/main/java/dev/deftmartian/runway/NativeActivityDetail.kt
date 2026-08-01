package dev.deftmartian.runway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.deftmartian.runway.domain.ACTIVITY_WORKOUT_MATCH_WINDOW_DAYS
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reusable, deliberately un-routed detail surface for a local activity candidate. It receives a
 * selected record from a native view and only emits existing, idempotent mobile commands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityDetailSheet(
    activity: NativeActivity,
    candidates: List<NativeWorkout>,
    evidence: NativeActivityEvidence?,
    evidenceLoading: Boolean,
    evidenceFailed: Boolean,
    actionPending: Boolean,
    onDismiss: () -> Unit,
    onLoadRouteTrace: () -> Unit,
    onAction: (MobileCommand) -> Unit,
) {
    var feltHard by rememberSaveable(activity.id, activity.feltHard) { mutableStateOf(activity.feltHard == true) }
    var pain by rememberSaveable(activity.id, activity.pain) { mutableStateOf(activity.pain == true) }
    var confirmDelete by rememberSaveable(activity.id) { mutableStateOf(false) }
    val linkCandidates = activityLinkCandidates(activity, candidates)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Run detail",
                modifier = Modifier.semantics { heading() },
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            ActivitySummary(activity)

            if (activity.reviewState == "review") {
                Text("How did this run feel?", fontWeight = FontWeight.SemiBold)
                CheckRow("This run felt harder than expected", feltHard) { feltHard = it }
                CheckRow("Pain during or after this run", pain) { pain = it }
                Text("Where does this run belong?", fontWeight = FontWeight.SemiBold)
                Text(
                    "Link counts this as the result for one planned workout. Extra counts it in actual totals without changing a planned workout. Neither changes future workouts unless you choose a separate plan decision.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (linkCandidates.isEmpty()) {
                    Text("No planned workout within three days is available for this run.")
                } else {
                    linkCandidates.forEach { workout ->
                        OutlinedButton(
                            onClick = {
                                onAction(
                                    LinkActivityCommand(
                                        activityId = activity.id.orEmpty(),
                                        workoutId = workout.id.orEmpty(),
                                        feltHard = feltHard,
                                        pain = pain,
                                    ),
                                )
                            },
                            enabled = !actionPending && activity.id != null && workout.id != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Link: ${workout.scheduledDate.orDash()} · ${workout.purpose.orDash()}")
                        }
                    }
                }
                Button(
                    onClick = {
                        onAction(
                            ConfirmActivityExtraCommand(
                                activityId = activity.id.orEmpty(),
                                feltHard = feltHard,
                                pain = pain,
                            ),
                        )
                    },
                    enabled = !actionPending && activity.id != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Count as an extra run") }
            } else if (!activity.workoutId.isNullOrBlank()) {
                OutlinedButton(
                    onClick = { onAction(UnlinkActivityCommand(activity.id.orEmpty())) },
                    enabled = !actionPending && activity.id != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Unlink from planned run") }
                Text("Unlinking returns this run to Inbox review. It stops counting until you accept a new role.")
            } else if (
                activity.extraPlanImpactConfirmed == true &&
                    activity.consequence?.appliedDecision == null
            ) {
                OutlinedButton(
                    onClick = { onAction(ReturnExtraActivityToReviewCommand(activity.id.orEmpty())) },
                    enabled = !actionPending && activity.id != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Return extra run to review") }
                Text(
                    "This keeps the recorded run and feedback, but stops counting it in actual " +
                        "totals and removes its unapplied plan options until you choose its role again.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (activity.reviewState != "review") {
                Text("Correct recorded feedback", fontWeight = FontWeight.SemiBold)
                if (activity.consequence?.appliedDecision != null) {
                    Text(
                        "This updates the run record. It does not undo or recalculate the plan " +
                            "choice already applied from the earlier feedback.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CheckRow("This run felt harder than expected", feltHard) { feltHard = it }
                CheckRow("Pain during or after this run", pain) { pain = it }
                OutlinedButton(
                    onClick = {
                        onAction(UpdateActivityFeedbackCommand(activity.id.orEmpty(), feltHard, pain))
                    },
                    enabled = !actionPending && activity.id != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save feedback correction") }
            }

            ActivityProvenance(activity)
            ActivityHeartRate(activity, evidence)
            ActivityRouteDisclosure(
                activity = activity,
                evidence = evidence,
                loading = evidenceLoading,
                failed = evidenceFailed,
                onRetry = onLoadRouteTrace,
            )

            TextButton(
                onClick = { confirmDelete = true },
                enabled = !actionPending && activity.id != null,
            ) { Text("Delete this run") }
        }
    }
    if (confirmDelete) {
        DestructiveConfirmationDialog(
            title = "Delete this run?",
            message =
                "This deletes the run and any saved route or heart-rate data. If it was imported, " +
                    "runway will not import the same source record again. This cannot be undone.",
            confirmLabel = "Delete run",
            actionPending = actionPending,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onAction(DeleteActivityCommand(activity.id.orEmpty()))
            },
        )
    }
}

/**
 * Mirrors the persisted activity-link guard so the sheet never offers a link the ledger rejects.
 */
internal fun activityLinkCandidates(
    activity: NativeActivity,
    candidates: List<NativeWorkout>,
): List<NativeWorkout> {
    val activityDate = (activity.occurredDate ?: activity.activityDate)
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return emptyList()
    return candidates.mapNotNull { workout ->
        val workoutDate = workout.scheduledDate
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return@mapNotNull null
        val offsetDays = ChronoUnit.DAYS.between(activityDate, workoutDate)
        workout.takeIf { kotlin.math.abs(offsetDays) <= ACTIVITY_WORKOUT_MATCH_WINDOW_DAYS }
    }.sortedWith(
        compareBy<NativeWorkout> { workout ->
            val workoutDate = requireNotNull(workout.scheduledDate)
            kotlin.math.abs(
                ChronoUnit.DAYS.between(activityDate, LocalDate.parse(workoutDate)),
            )
        }.thenBy(NativeWorkout::scheduledDate)
            .thenBy { it.id.orEmpty() },
    )
}

@Composable
private fun ActivitySummary(activity: NativeActivity) {
    SettingCard("Exact summary") {
        SettingRow("Date", activity.occurredDate.orDash(), monospace = true)
        SettingRow(
            "Distance",
            activity.distanceMeters?.takeIf { it > 0 }?.let(::formatDistance).orDash(),
        )
        SettingRow(
            "Duration",
            activity.durationSeconds?.let(::formatDuration).orDash(),
            monospace = true,
        )
        activity.averagePaceSecondsPerKm?.let {
            SettingRow("Average pace", formatPace(it), monospace = true)
        }
    }
}

@Composable
private fun ActivityProvenance(activity: NativeActivity) {
    SettingCard("Source and plan state") {
        SettingRow("Source", activitySourceLabel(activity))
        SettingRow("Review", activityReviewLabel(activity))
        when {
            !activity.matchedWorkoutPurpose.isNullOrBlank() ->
                SettingRow("Matched run", "${activity.matchedWorkoutDate.orDash()} · ${activity.matchedWorkoutPurpose}")
            activity.extraPlanImpactConfirmed == true -> SettingRow("Plan state", "Counted as extra training")
            else -> SettingRow("Plan state", "Not matched to a planned run")
        }
    }
}

@Composable
private fun ActivityHeartRate(activity: NativeActivity, evidence: NativeActivityEvidence?) {
    val summary = activity.heartRateSummary
    if (
        activity.averageHeartRate == null &&
        activity.maxHeartRate == null &&
        summary == null &&
        evidence?.heartRateSeries == null &&
        evidence?.averageCadence == null
    ) {
        return
    }
    SettingCard("Recorded metrics") {
        activity.averageHeartRate?.let { SettingRow("Average", "$it bpm", monospace = true) }
        activity.maxHeartRate?.let { SettingRow("Maximum", "$it bpm", monospace = true) }
        summary?.let {
            it.highSeconds?.let { seconds ->
                SettingRow("High-zone time", formatDuration(seconds.toDouble()), monospace = true)
            }
            it.highShare?.let { share ->
                SettingRow("High-zone share", "${(share * 100).roundToInt()}%", monospace = true)
            }
            Text("Zone time is descriptive; it does not change the plan automatically.")
        }
        evidence?.averageCadence?.let {
            SettingRow("Average cadence", "$it rpm", monospace = true)
        }
        evidence?.heartRateSeries?.let { HeartRateTrace(it) }
    }
}

@Composable
private fun ActivityRouteDisclosure(
    activity: NativeActivity,
    evidence: NativeActivityEvidence?,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
) {
    val route = activity.routeSummary
    val disclosure = evidence?.disclosure
    if (route == null && disclosure == null && evidence?.routeTrace == null) return
    SettingCard("Private activity detail") {
        if (route != null || disclosure?.routeTraceRetained != null || evidence?.routeTrace != null) {
            val message = if (route?.traceRetained == true || disclosure?.routeTraceRetained == true) {
                "This private route trace is drawn on this phone. Runway does not contact a map or tile service."
            } else {
                "No route trace is retained for this activity."
            }
            Text(message)
        }
        when {
            evidence?.routeTrace != null -> PrivateRouteTrace(requireNotNull(evidence.routeTrace))
            loading -> {
                CircularProgressIndicator()
                Text("Loading the private route trace…")
            }
            failed && (route?.traceRetained == true || disclosure?.routeTraceRetained == true) -> {
                Text("The private route trace could not be loaded.")
                OutlinedButton(
                    onClick = onRetry,
                    shape = MaterialTheme.shapes.small,
                ) { Text("Try again") }
            }
        }
        (disclosure?.routePointCount ?: route?.pointCount)?.let {
            SettingRow("Imported route points", it.toString(), monospace = true)
        }
        if (disclosure?.hasElevation == true || route?.hasElevation == true) {
            SettingRow("Elevation", "Present in import")
        }
        if (disclosure?.startEndRedacted == true || route?.startEndRedacted == true) {
            Text("Start and end details are redacted.")
        }
        disclosure?.heartRateSeriesRetained?.let {
            SettingRow(
                "Heart-rate samples",
                if (it) "${disclosure.heartRateSampleCount ?: 0} retained" else "Not retained",
                monospace = it,
            )
        }
    }
}

@Composable
private fun HeartRateTrace(series: NativeHeartRateSeries) {
    val points = series.points.filter { it.elapsedSeconds != null && it.bpm != null }
    if (points.isEmpty()) return
    val low = points.minOf { requireNotNull(it.bpm) }
    val high = points.maxOf { requireNotNull(it.bpm) }
    val latest = points.last()
    val traceColor = androidx.compose.material3.MaterialTheme.colorScheme.tertiary
    val sourceCount = series.sourceSampleCount ?: points.size
    val countLabel = if (sourceCount > points.size) {
        "${points.size} shown of $sourceCount retained"
    } else {
        "$sourceCount retained"
    }
    SettingRow("Heart-rate samples", "$countLabel · $low–$high bpm", monospace = true)
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(120.dp)
            .semantics {
                contentDescription =
                    "Heart-rate trace. $countLabel, ranging from $low to $high beats per minute. " +
                        "Latest ${latest.bpm} beats per minute at " +
                        formatDuration(latest.elapsedSeconds?.toDouble() ?: 0.0) + "."
            },
    ) {
        val maxElapsed = points.maxOf { requireNotNull(it.elapsedSeconds) }.coerceAtLeast(1)
        val range = (high - low).coerceAtLeast(10)
        val inset = 8.dp.toPx()
        fun project(point: NativeHeartRatePoint): Offset = Offset(
            inset + (requireNotNull(point.elapsedSeconds).toFloat() / maxElapsed) * (size.width - inset * 2),
            size.height - inset - ((requireNotNull(point.bpm) - low).toFloat() / range) * (size.height - inset * 2),
        )
        points.zipWithNext().forEach { (left, right) ->
            drawLine(
                color = traceColor,
                start = project(left), end = project(right), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
    Text(
        "Recorded heart rate over elapsed time. Latest ${latest.bpm} bpm at ${formatDuration(latest.elapsedSeconds?.toDouble() ?: 0.0)}.",
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PrivateRouteTrace(trace: NativeRouteTrace) {
    val points = trace.points.filter { it.latitudeE6 != null && it.longitudeE6 != null }
    if (points.size < 2) return
    val lats = points.map { requireNotNull(it.latitudeE6).toFloat() }
    val lons = points.map { requireNotNull(it.longitudeE6).toFloat() }
    val minLat = lats.min()
    val maxLat = lats.max()
    val minLon = lons.min()
    val maxLon = lons.max()
    val meanLatitudeRadians =
        ((minLat + maxLat).toDouble() / 2.0 / 1_000_000.0) * (PI / 180.0)
    val longitudeScale = cos(meanLatitudeRadians).coerceAtLeast(0.01).toFloat()
    val minX = minLon * longitudeScale
    val maxX = maxLon * longitudeScale
    val routeColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val sourceCount = trace.sourcePointCount ?: points.size
    val countLabel = if (sourceCount > points.size) {
        "${points.size} shown of $sourceCount retained"
    } else {
        "$sourceCount retained points"
    }
    SettingRow("Private route", countLabel)
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(160.dp)
            .semantics {
                contentDescription =
                    "Private route trace. $countLabel. Start and finish markers are shown; " +
                        "location details are not announced."
            },
    ) {
        val inset = 10.dp.toPx()
        val availableWidth = (size.width - inset * 2).coerceAtLeast(1f)
        val availableHeight = (size.height - inset * 2).coerceAtLeast(1f)
        val routeWidth = (maxX - minX).coerceAtLeast(1f)
        val routeHeight = (maxLat - minLat).coerceAtLeast(1f)
        val scale = min(availableWidth / routeWidth, availableHeight / routeHeight)
        val drawnWidth = routeWidth * scale
        val drawnHeight = routeHeight * scale
        val leftInset = (size.width - drawnWidth) / 2f
        val topInset = (size.height - drawnHeight) / 2f
        fun project(lat: Float, lon: Float): Offset {
            val x = leftInset + ((lon * longitudeScale) - minX) * scale
            val y = topInset + (maxLat - lat) * scale
            return Offset(x, y)
        }
        points.zipWithNext().forEach { (left, right) ->
            drawLine(
                color = routeColor,
                start = project(
                    requireNotNull(left.latitudeE6).toFloat(),
                    requireNotNull(left.longitudeE6).toFloat(),
                ),
                end = project(
                    requireNotNull(right.latitudeE6).toFloat(),
                    requireNotNull(right.longitudeE6).toFloat(),
                ),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
            )
        }
        val markerRadius = 5.dp.toPx()
        val start = points.first()
        val end = points.last()
        drawCircle(
            color = routeColor,
            radius = markerRadius,
            center = project(
                requireNotNull(start.latitudeE6).toFloat(),
                requireNotNull(start.longitudeE6).toFloat(),
            ),
            style = Stroke(width = 2.dp.toPx()),
        )
        val endCenter = project(
            requireNotNull(end.latitudeE6).toFloat(),
            requireNotNull(end.longitudeE6).toFloat(),
        )
        drawRect(
            color = routeColor,
            topLeft = Offset(endCenter.x - markerRadius, endCenter.y - markerRadius),
            size = Size(markerRadius * 2, markerRadius * 2),
        )
    }
    Text(
        "○ Start · ■ Finish",
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun activitySourceLabel(activity: NativeActivity): String = when (activity.source) {
    "gpx" -> "GPX import"
    "health_connect" -> "Health Connect"
    else -> activity.source.orDash()
}

private fun activityReviewLabel(activity: NativeActivity): String = when (activity.reviewState) {
    "review" -> "Review — excluded from training totals"
    "accepted" -> "Accepted"
    else -> activity.reviewState.orDash()
}

internal fun formatPace(secondsPerKm: Double): String {
    if (!secondsPerKm.isFinite() || secondsPerKm < 0) return "—"
    val rounded = secondsPerKm.roundToInt()
    return "%d:%02d /km".format(rounded / 60, rounded % 60)
}
