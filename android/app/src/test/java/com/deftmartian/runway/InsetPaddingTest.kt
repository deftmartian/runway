package com.deftmartian.runway

import androidx.core.graphics.Insets
import org.junit.Assert.assertEquals
import org.junit.Test

class InsetPaddingTest {
    @Test
    fun `safe area is added to intentional content padding`() {
        val result = InsetPadding(24, 24, 24, 32).plus(Insets.of(3, 31, 5, 48))

        assertEquals(InsetPadding(27, 55, 29, 80), result)
    }
}
