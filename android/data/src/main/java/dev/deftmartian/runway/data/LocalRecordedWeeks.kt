package dev.deftmartian.runway.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** One Monday-to-Sunday week of accepted runs, in the profile's training time zone. */
data class LocalRecordedWeekReadModel(
    val startEpochDay: Long,
    val runs: Int,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val longestRunMeters: Int?,
    /** Total time over total distance, from runs that recorded both. */
    val paceSecondsPerKilometre: Double?,
    /** Duration-weighted, from runs whose imported heart rate was kept. */
    val averageHeartRateBpm: Int?,
)

/** Trends cover the most recent weeks only; older runs stay in History and backups. */
const val RECORDED_TREND_WEEKS = 12

/** Bounds the trend read: twelve weeks of even several runs a day stays well under this. */
const val RECORDED_TREND_ACTIVITY_LIMIT = 500

/** First local day of the trend window: the Monday [weeks] - 1 weeks before this week's Monday. */
fun recordedTrendStart(todayEpochDay: Long, weeks: Int = RECORDED_TREND_WEEKS): LocalDate =
    LocalDate.ofEpochDay(todayEpochDay)
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .minusWeeks((weeks - 1).toLong())

/**
 * Groups accepted runs into the last [weeks] local weeks, oldest first. Weeks without runs are
 * kept as zero so a gap reads as a gap.
 */
fun recordedWeeks(
    activities: List<ActivityEntity>,
    zone: ZoneId,
    todayEpochDay: Long,
    weeks: Int = RECORDED_TREND_WEEKS,
): List<LocalRecordedWeekReadModel> {
    val firstMonday = recordedTrendStart(todayEpochDay, weeks)
    val byWeek = activities
        .filter { it.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED }
        .groupBy { activity ->
            Instant.ofEpochMilli(activity.occurredAtEpochMillis).atZone(zone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }
    return (0 until weeks).map { index ->
        val monday = firstMonday.plusWeeks(index.toLong())
        recordedWeek(monday.toEpochDay(), byWeek[monday].orEmpty())
    }
}

private fun recordedWeek(startEpochDay: Long, runs: List<ActivityEntity>) = LocalRecordedWeekReadModel(
    startEpochDay = startEpochDay,
    runs = runs.size,
    distanceMeters = runs.sumOf { it.distanceMeters ?: 0 },
    durationSeconds = runs.sumOf { it.durationSeconds ?: 0 },
    longestRunMeters = runs.mapNotNull { it.distanceMeters }.filter { it > 0 }.maxOrNull(),
    paceSecondsPerKilometre = paceSecondsPerKilometre(runs),
    averageHeartRateBpm = durationWeightedHeartRate(runs),
)

private fun paceSecondsPerKilometre(runs: List<ActivityEntity>): Double? {
    val paced = runs.filter { (it.distanceMeters ?: 0) > 0 && (it.durationSeconds ?: 0) > 0 }
    val distance = paced.sumOf { it.distanceMeters ?: 0 }
    if (distance == 0) return null
    return paced.sumOf { (it.durationSeconds ?: 0).toLong() } * 1_000.0 / distance
}

private fun durationWeightedHeartRate(runs: List<ActivityEntity>): Int? {
    val measured = runs.filter { (it.averageHeartRateBpm ?: 0) > 0 && (it.durationSeconds ?: 0) > 0 }
    val seconds = measured.sumOf { (it.durationSeconds ?: 0).toLong() }
    if (seconds == 0L) return null
    val beats = measured.sumOf { (it.averageHeartRateBpm ?: 0).toLong() * (it.durationSeconds ?: 0) }
    return Math.round(beats.toDouble() / seconds).toInt()
}
