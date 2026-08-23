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
import androidx.compose.ui.res.stringResource
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
                stringResource(R.string.activity_detail_title),
                modifier = Modifier.semantics { heading() },
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            ActivitySummary(activity)
            ActivityRoleControls(
                activity, linkCandidates, feltHard, { feltHard = it }, actionPending, onAction,
            )
            ActivityFeedbackCorrection(
                activity, feltHard, { feltHard = it }, actionPending, onAction,
            )

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
            ) { Text(stringResource(R.string.activity_detail_delete)) }
        }
    }
    if (confirmDelete) {
        DestructiveConfirmationDialog(
            title = stringResource(R.string.activity_detail_delete_confirm_title),
            message = stringResource(R.string.activity_detail_delete_confirm_message),
            confirmLabel = stringResource(R.string.activity_detail_delete_confirm_action),
            actionPending = actionPending,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onAction(DeleteActivityCommand(activity.id.orEmpty()))
            },
        )
    }
}

@Composable
private fun ActivityRoleControls(
    activity: NativeActivity,
    linkCandidates: List<NativeWorkout>,
    feltHard: Boolean,
    onFeltHardChange: (Boolean) -> Unit,
    actionPending: Boolean,
    onAction: (MobileCommand) -> Unit,
) = when {
    activity.reviewState == "review" -> ActivityReviewControls(
        activity, linkCandidates, feltHard, onFeltHardChange, actionPending, onAction,
    )
    !activity.workoutId.isNullOrBlank() -> ActivityUnlinkControl(activity, actionPending, onAction)
    activity.extraPlanImpactConfirmed == true && activity.consequence?.appliedDecision == null ->
        ActivityReturnToReviewControl(activity, actionPending, onAction)
    else -> Unit
}

@Composable
private fun ActivityReviewControls(
    activity: NativeActivity,
    linkCandidates: List<NativeWorkout>,
    feltHard: Boolean,
    onFeltHardChange: (Boolean) -> Unit,
    actionPending: Boolean,
    onAction: (MobileCommand) -> Unit,
) {
    Text(stringResource(R.string.activity_feedback_question), fontWeight = FontWeight.SemiBold)
    CheckRow(stringResource(R.string.activity_felt_harder), feltHard, onFeltHardChange)
    Text(stringResource(R.string.activity_role_question), fontWeight = FontWeight.SemiBold)
    Text(
        stringResource(R.string.activity_role_explanation),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (linkCandidates.isEmpty()) {
        Text(stringResource(R.string.activity_no_link_candidates))
    } else {
        linkCandidates.forEach { workout ->
            OutlinedButton(
                onClick = {
                    onAction(LinkActivityCommand(activity.id.orEmpty(), workout.id.orEmpty(), feltHard))
                },
                enabled = !actionPending && activity.id != null && workout.id != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        R.string.activity_link_candidate,
                        workout.scheduledDate.orDash(),
                        workout.purpose.orDash(),
                    ),
                )
            }
        }
    }
    Button(
        onClick = {
            onAction(ConfirmActivityExtraCommand(activity.id.orEmpty(), feltHard))
        },
        enabled = !actionPending && activity.id != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.activity_count_as_extra)) }
}

@Composable
private fun ActivityUnlinkControl(
    activity: NativeActivity,
    actionPending: Boolean,
    onAction: (MobileCommand) -> Unit,
) {
    OutlinedButton(
        onClick = { onAction(UnlinkActivityCommand(activity.id.orEmpty())) },
        enabled = !actionPending && activity.id != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.activity_unlink)) }
    Text(stringResource(R.string.activity_unlink_explanation))
}

@Composable
private fun ActivityReturnToReviewControl(
    activity: NativeActivity,
    actionPending: Boolean,
    onAction: (MobileCommand) -> Unit,
) {
    OutlinedButton(
        onClick = { onAction(ReturnExtraActivityToReviewCommand(activity.id.orEmpty())) },
        enabled = !actionPending && activity.id != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.activity_return_to_review)) }
    Text(
        stringResource(R.string.activity_return_to_review_explanation),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ActivityFeedbackCorrection(
    activity: NativeActivity,
    feltHard: Boolean,
    onFeltHardChange: (Boolean) -> Unit,
    actionPending: Boolean,
    onAction: (MobileCommand) -> Unit,
) {
    if (activity.reviewState == "review") return
    Text(stringResource(R.string.activity_correct_feedback), fontWeight = FontWeight.SemiBold)
    if (activity.consequence?.appliedDecision != null) {
        Text(
            stringResource(R.string.activity_correction_explanation),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    CheckRow(stringResource(R.string.activity_felt_harder), feltHard, onFeltHardChange)
    OutlinedButton(
        onClick = { onAction(UpdateActivityFeedbackCommand(activity.id.orEmpty(), feltHard)) },
        enabled = !actionPending && activity.id != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.activity_save_correction)) }
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
    SettingCard(stringResource(R.string.activity_summary_title)) {
        SettingRow(
            stringResource(R.string.activity_date_label),
            activity.occurredDate.orDash(),
            monospace = true,
        )
        SettingRow(
            stringResource(R.string.activity_distance_label),
            activity.distanceMeters?.takeIf { it > 0 }?.let(::formatDistance).orDash(),
        )
        SettingRow(
            stringResource(R.string.activity_duration_label),
            activity.durationSeconds?.let(::formatDuration).orDash(),
            monospace = true,
        )
        activity.averagePaceSecondsPerKm?.let {
            SettingRow(
                stringResource(R.string.activity_average_pace_label),
                formatPace(it),
                monospace = true,
            )
        }
    }
}

@Composable
private fun ActivityProvenance(activity: NativeActivity) {
    SettingCard(stringResource(R.string.activity_provenance_title)) {
        SettingRow(stringResource(R.string.activity_source_label), activitySourceLabel(activity))
        SettingRow(stringResource(R.string.activity_review_label), activityReviewLabel(activity))
        when {
            !activity.matchedWorkoutPurpose.isNullOrBlank() ->
                SettingRow(
                    stringResource(R.string.activity_matched_run_label),
                    stringResource(
                        R.string.activity_matched_run_value,
                        activity.matchedWorkoutDate.orDash(),
                        activity.matchedWorkoutPurpose.orEmpty(),
                    ),
                )
            activity.extraPlanImpactConfirmed == true -> SettingRow(
                stringResource(R.string.plan_state_label),
                stringResource(R.string.activity_plan_state_extra),
            )
            else -> SettingRow(
                stringResource(R.string.plan_state_label),
                stringResource(R.string.activity_plan_state_unmatched),
            )
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
    SettingCard(stringResource(R.string.activity_metrics_title)) {
        activity.averageHeartRate?.let {
            SettingRow(
                stringResource(R.string.activity_hr_average_label),
                stringResource(R.string.activity_hr_bpm_value, it),
                monospace = true,
            )
        }
        activity.maxHeartRate?.let {
            SettingRow(
                stringResource(R.string.activity_hr_maximum_label),
                stringResource(R.string.activity_hr_bpm_value, it),
                monospace = true,
            )
        }
        summary?.let {
            it.highSeconds?.let { seconds ->
                SettingRow(
                    stringResource(R.string.activity_high_zone_time_label),
                    formatDuration(seconds.toDouble()),
                    monospace = true,
                )
            }
            it.highShare?.let { share ->
                SettingRow(
                    stringResource(R.string.activity_high_zone_share_label),
                    stringResource(R.string.activity_high_zone_share_value, (share * 100).roundToInt()),
                    monospace = true,
                )
            }
            Text(stringResource(R.string.activity_zone_time_note))
        }
        evidence?.averageCadence?.let {
            SettingRow(
                stringResource(R.string.activity_cadence_label),
                stringResource(R.string.activity_cadence_value, it),
                monospace = true,
            )
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
    SettingCard(stringResource(R.string.activity_private_detail_title)) {
        ActivityRouteTrace(route, disclosure, evidence, loading, failed, onRetry)
        ActivityRouteMetadata(route, disclosure)
        ActivityHeartRateRetention(disclosure)
    }
}

@Composable
private fun ActivityRouteTrace(
    route: NativeRouteSummary?,
    disclosure: NativeActivityDisclosure?,
    evidence: NativeActivityEvidence?,
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
) {
    val retained = route?.traceRetained == true || disclosure?.routeTraceRetained == true
    if (route != null || disclosure?.routeTraceRetained != null || evidence?.routeTrace != null) {
        Text(
            if (retained) {
                stringResource(R.string.route_trace_retained)
            } else {
                stringResource(R.string.route_trace_not_retained)
            },
        )
    }
    when {
        evidence?.routeTrace != null -> PrivateRouteTrace(requireNotNull(evidence.routeTrace))
        loading -> {
            CircularProgressIndicator()
            Text(stringResource(R.string.route_trace_loading))
        }
        failed && retained -> {
            Text(stringResource(R.string.route_trace_failed))
            OutlinedButton(
                onClick = onRetry,
                shape = MaterialTheme.shapes.small,
            ) { Text(stringResource(R.string.route_trace_retry)) }
        }
    }
}

@Composable
private fun ActivityRouteMetadata(route: NativeRouteSummary?, disclosure: NativeActivityDisclosure?) {
    (disclosure?.routePointCount ?: route?.pointCount)?.let {
        SettingRow(
            stringResource(R.string.route_points_label),
            it.toString(),
            monospace = true,
        )
    }
    if (disclosure?.hasElevation == true || route?.hasElevation == true) {
        SettingRow(stringResource(R.string.elevation_label), stringResource(R.string.elevation_present))
    }
    if (disclosure?.startEndRedacted == true || route?.startEndRedacted == true) {
        Text(stringResource(R.string.start_end_redacted))
    }
}

@Composable
private fun ActivityHeartRateRetention(disclosure: NativeActivityDisclosure?) {
    disclosure?.heartRateSeriesRetained?.let {
        SettingRow(
            stringResource(R.string.hr_samples_label),
            if (it) {
                stringResource(R.string.hr_retained_value, disclosure.heartRateSampleCount ?: 0)
            } else {
                stringResource(R.string.hr_not_retained)
            },
            monospace = it,
        )
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
        stringResource(R.string.hr_shown_of_retained, points.size, sourceCount)
    } else {
        stringResource(R.string.hr_retained_value, sourceCount)
    }
    val rangeValue = stringResource(R.string.hr_trace_range_value, countLabel, low, high)
    val traceDescription = stringResource(
        R.string.heart_rate_trace_description,
        countLabel,
        low,
        high,
        requireNotNull(latest.bpm),
        formatDuration(latest.elapsedSeconds?.toDouble() ?: 0.0),
    )
    val traceCaption = stringResource(
        R.string.heart_rate_trace_caption,
        requireNotNull(latest.bpm),
        formatDuration(latest.elapsedSeconds?.toDouble() ?: 0.0),
    )
    SettingRow(stringResource(R.string.hr_samples_label), rangeValue, monospace = true)
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(120.dp)
            .semantics { contentDescription = traceDescription },
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
        traceCaption,
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
        stringResource(R.string.hr_shown_of_retained, points.size, sourceCount)
    } else {
        stringResource(R.string.private_route_retained_points, sourceCount)
    }
    val routeDescription = stringResource(R.string.private_route_trace_description, countLabel)
    SettingRow(stringResource(R.string.private_route_label), countLabel)
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(160.dp)
            .semantics { contentDescription = routeDescription },
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
        stringResource(R.string.route_markers_caption),
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun activitySourceLabel(activity: NativeActivity): String = when (activity.source) {
    "gpx" -> stringResource(R.string.source_gpx_import)
    "health_connect" -> stringResource(R.string.source_health_connect)
    else -> activity.source.orDash()
}

@Composable
private fun activityReviewLabel(activity: NativeActivity): String = when (activity.reviewState) {
    "review" -> stringResource(R.string.review_state_review)
    "accepted" -> stringResource(R.string.review_state_accepted)
    else -> activity.reviewState.orDash()
}

internal fun formatPace(secondsPerKm: Double): String {
    if (!secondsPerKm.isFinite() || secondsPerKm < 0) return "—"
    val rounded = secondsPerKm.roundToInt()
    return "%d:%02d /km".format(rounded / 60, rounded % 60)
}
