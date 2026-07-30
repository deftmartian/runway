package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeTimeZonePickerTest {
    private val zones = setOf(
        "America/Halifax",
        "America/St_Johns",
        "Asia/Tokyo",
        "Etc/UTC",
        "Europe/London",
    )

    @Test
    fun `filter finds IANA names case insensitively with stable ordering`() {
        assertEquals(
            listOf("America/Halifax", "America/St_Johns"),
            filterTimeZoneIds("AMERICA", zones),
        )
        assertEquals(listOf("America/Halifax"), filterTimeZoneIds("halI", zones))
    }

    @Test
    fun `filter returns all zones in lexical order when search is blank`() {
        assertEquals(
            listOf("America/Halifax", "America/St_Johns", "Asia/Tokyo", "Etc/UTC", "Europe/London"),
            filterTimeZoneIds("  ", zones),
        )
    }

    @Test
    fun `filter bounds results and rejects nonpositive limits`() {
        assertEquals(
            listOf("America/Halifax", "America/St_Johns"),
            filterTimeZoneIds("", zones, limit = 2),
        )
        assertEquals(emptyList<String>(), filterTimeZoneIds("", zones, limit = 0))
    }
}
