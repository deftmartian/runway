package dev.deftmartian.runway.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object DateUtils {
    private val iso: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun parseIsoDate(value: String): LocalDate = try {
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value)) {
            throw IllegalArgumentException("Invalid date: $value")
        }
        LocalDate.parse(value, iso)
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("Invalid date: $value")
    }

    fun addDays(date: String, days: Long): String = parseIsoDate(date).plusDays(days).format(iso)

    fun daysBetween(startDate: String, endDate: String): Long = ChronoUnit.DAYS.between(parseIsoDate(startDate), parseIsoDate(endDate))

    fun toIsoDate(instant: Instant, timeZone: String = "UTC"): String = instant.atZone(zone(timeZone)).toLocalDate().format(iso)

    fun toIsoDateInTimeZone(instant: Instant, timeZone: String): String = toIsoDate(instant, timeZone)

    fun isValidTimeZone(timeZone: String): Boolean = try {
        ZoneId.of(timeZone)
        timeZone.isNotEmpty() && timeZone.length <= 255
    } catch (_: Exception) {
        false
    }

    fun localDateAtNoon(date: String, timeZone: String): Instant = ZonedDateTime.of(parseIsoDate(date).atTime(12, 0), zone(timeZone)).toInstant()

    fun weekStart(date: LocalDate): String = date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).format(iso)

    private fun zone(value: String): ZoneId {
        if (!isValidTimeZone(value)) throw IllegalArgumentException("Invalid IANA time zone.")
        return ZoneId.of(value)
    }
}
