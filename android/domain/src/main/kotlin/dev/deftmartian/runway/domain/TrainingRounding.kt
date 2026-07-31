package dev.deftmartian.runway.domain

import kotlin.math.floor

/**
 * Training arithmetic rounds exact half values upward. Keep this rule explicit so generated plans
 * and persisted decision previews remain stable across app versions.
 */
internal fun roundTrainingValue(value: Double): Double {
    require(value.isFinite()) { "A finite value is required." }
    return floor(value + 0.5)
}

internal fun roundTrainingValueToInt(value: Double): Int =
    roundTrainingValue(value).toInt()

internal fun roundTrainingValueToOneDecimal(value: Double): Double =
    roundTrainingValue(value * 10) / 10
