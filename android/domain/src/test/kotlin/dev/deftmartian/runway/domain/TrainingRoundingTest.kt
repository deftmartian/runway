package dev.deftmartian.runway.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrainingRoundingTest {
    @Test
    fun `training values round exact halves upward`() {
        assertEquals(3.0, roundTrainingValue(2.5), 0.0)
        assertEquals(-2.0, roundTrainingValue(-2.5), 0.0)
        assertEquals(13, roundTrainingValueToInt(12.5))
        assertEquals(1.3, roundTrainingValueToOneDecimal(1.25), 0.000_001)
    }

    @Test
    fun `training rounding rejects non-finite values`() {
        assertThrows(IllegalArgumentException::class.java) {
            roundTrainingValue(Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `training values round to runner readable increments`() {
        assertEquals(3_500, roundTrainingValueToIncrement(3_250.0, 500))
        assertEquals(3_000, roundTrainingValueToIncrement(3_249.0, 500))
    }
}
