package dev.deftmartian.runway

import java.time.YearMonth
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class PendingPlanDecision(
    val source: String,
    val sourceId: String,
    val decision: String,
    val consequence: NativeConsequence,
)

internal data class CalendarActivityPlacement(
    val byWorkoutId: Map<String, List<NativeActivity>>,
    val unplaced: List<NativeActivity>,
)

internal fun placeCalendarActivities(
    workouts: List<NativeWorkout>,
    activities: List<NativeActivity>,
): CalendarActivityPlacement {
    val workoutsById = workouts
        .mapNotNull { workout ->
            workout.id?.takeIf(String::isNotBlank)?.let { id -> id to workout }
        }
        .toMap()
    val placed = linkedMapOf<String, MutableList<NativeActivity>>()
    val unplaced = mutableListOf<NativeActivity>()

    activities.forEach { activity ->
        val linkedId = activity.workoutId?.takeIf(workoutsById::containsKey)
        if (linkedId == null) {
            unplaced += activity
        } else {
            placed.getOrPut(linkedId) { mutableListOf() } += activity
        }
    }

    return CalendarActivityPlacement(
        byWorkoutId = placed.mapValues { (_, values) -> values.toList() },
        unplaced = unplaced,
    )
}

internal fun resizeIntervalStructure(
    structure: TimedIntervalStructureDto?,
    targetSeconds: Int,
): TimedIntervalStructureDto {
    if (structure == null) return singleRunStructure(targetSeconds)

    val sourceTotal =
        (structure.warmupSeconds ?: 0) +
            (structure.cooldownSeconds ?: 0) +
            structure.blocks.sumOf { block ->
                (block.repetitions ?: 1) *
                    block.segments.sumOf { segment -> segment.durationSeconds ?: 0 }
            }
    if (sourceTotal <= 0) return singleRunStructure(targetSeconds)

    val factor = targetSeconds.toDouble() / sourceTotal
    val resizedBlocks = structure.blocks.map { block ->
        TimedBlockDto(
            repetitions = block.repetitions ?: 1,
            segments = block.segments.map { segment ->
                TimedSegmentDto(
                    kind = segment.kind,
                    durationSeconds =
                        ((segment.durationSeconds ?: 0) * factor).toInt().coerceAtLeast(1),
                )
            },
        )
    }
    val resizedWarmup = ((structure.warmupSeconds ?: 0) * factor).toInt()
    val initialCooldown = ((structure.cooldownSeconds ?: 0) * factor).toInt()
    val resizedTotal =
        resizedWarmup +
            initialCooldown +
            resizedBlocks.sumOf { block ->
                (block.repetitions ?: 1) *
                    block.segments.sumOf { segment -> segment.durationSeconds ?: 0 }
            }
    return TimedIntervalStructureDto(
        warmupSeconds = resizedWarmup,
        cooldownSeconds = (initialCooldown + targetSeconds - resizedTotal).coerceAtLeast(0),
        blocks = resizedBlocks,
    )
}

private fun singleRunStructure(targetSeconds: Int) = TimedIntervalStructureDto(
    warmupSeconds = 0,
    cooldownSeconds = 0,
    blocks = listOf(
        TimedBlockDto(
            repetitions = 1,
            segments = listOf(
                TimedSegmentDto(kind = "run", durationSeconds = targetSeconds),
            ),
        ),
    ),
)

internal val dayLabels = listOf(
    "Sunday",
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
)

internal fun pendingPlanDecision(
    source: String,
    record: NativeWorkoutFeedback,
    decision: String,
): PendingPlanDecision? = record.consequence?.let { consequence ->
    PendingPlanDecision(
        source = source,
        sourceId = record.id.orEmpty(),
        decision = decision,
        consequence = consequence,
    )
}

/** Uses the consequence's durable source rather than assuming every activity owns it. */
internal fun pendingActivityPlanDecision(
    activity: NativeActivity,
    decision: String,
): PendingPlanDecision? = activity.consequence?.let { consequence ->
    val isWorkoutFeedback = consequence.sourceKind == "WorkoutFeedback"
    PendingPlanDecision(
        source = if (isWorkoutFeedback) "feedback" else "activity",
        sourceId = consequence.sourceId ?: activity.id.orEmpty(),
        decision = decision,
        consequence = consequence,
    )
}

internal fun planDecisionCommand(pending: PendingPlanDecision) = PreviewPlanDecisionCommand(
    source = pending.source,
    sourceId = pending.sourceId,
    decision = pending.decision,
)

internal fun planDecisionLabel(decision: String): String = when (decision) {
    "keep_plan" -> "Keep the plan"
    "reduce_next" -> "Reduce the next run"
    "next_rest" -> "Make the next run a rest day"
    "repeat_prescription" -> "Repeat this prescription"
    "rebalance_week" -> "Rebalance this week"
    else -> decision.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

internal fun planDecisionExplanation(decision: String): String = when (decision) {
    "keep_plan" -> "Leave future workouts as they are."
    "reduce_next" -> "Reduce the amount in the next compatible planned run."
    "next_rest" -> "Replace the next compatible planned run with rest."
    "repeat_prescription" -> "Use this prescription again for the next compatible planned run."
    "rebalance_week" -> "Spread the remaining work across compatible runs in this week."
    else -> "Apply this change to future planned work."
}

internal fun String?.orDash(): String = this?.takeIf(String::isNotBlank) ?: "—"

internal fun formatDistance(meters: Double): String =
    String.format(Locale.US, "%.1f km", meters / 1_000).replace(".0 km", " km")

internal fun formatPrescriptionMeasurement(
    distanceMeters: Double?,
    durationSeconds: Double?,
    rest: Boolean = false,
): String {
    if (rest) return "Recovery day"
    return listOfNotNull(
        distanceMeters?.takeIf { it > 0 }?.let(::formatDistance),
        durationSeconds?.takeIf { it > 0 }?.let(::formatDuration),
    ).joinToString(" · ").ifBlank { "Plan details" }
}

/** Compact, readable interval detail for cards; the typed structure remains the source of truth. */
internal fun formatTimedStructure(structure: TimedIntervalStructureDto?): String? {
    structure ?: return null
    val parts = buildList {
        structure.warmupSeconds?.takeIf { it > 0 }?.let { add("Warm up ${formatDuration(it.toDouble())}") }
        structure.blocks.forEach { block ->
            val segments = block.segments.mapNotNull { segment ->
                val kind = segment.kind?.replaceFirstChar(Char::uppercase) ?: return@mapNotNull null
                segment.durationSeconds?.takeIf { it > 0 }?.let { "$kind ${formatDuration(it.toDouble())}" } ?: kind
            }
            if (segments.isNotEmpty()) {
                val repeats = block.repetitions?.takeIf { it > 1 }?.let { "$it × " }.orEmpty()
                add(repeats + segments.joinToString(" / "))
            }
        }
        structure.cooldownSeconds?.takeIf { it > 0 }?.let { add("Cool down ${formatDuration(it.toDouble())}") }
    }
    return parts.joinToString(" · ").takeIf(String::isNotBlank)
}

internal fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60).toInt()
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

internal fun formatDecimal(value: Double): String =
    String.format(Locale.US, "%.1f", value).removeSuffix(".0")

internal fun monthLabel(month: String): String = runCatching {
    YearMonth.parse(month).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
}.getOrDefault("Calendar")

internal fun friendlyDate(date: String): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEEE, LLLL d", Locale.getDefault()))
}.getOrDefault(date)
