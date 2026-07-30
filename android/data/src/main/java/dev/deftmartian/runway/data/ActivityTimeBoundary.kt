package dev.deftmartian.runway.data

import java.time.Instant
import java.time.ZoneId

/** Imports are factual past/today records; a future local calendar date is never accepted. */
fun isFutureLocalActivity(
    startedAtEpochMillis: Long,
    nowEpochMillis: Long,
    timeZone: ZoneId,
): Boolean =
    Instant.ofEpochMilli(startedAtEpochMillis).atZone(timeZone).toLocalDate()
        .isAfter(Instant.ofEpochMilli(nowEpochMillis).atZone(timeZone).toLocalDate())
