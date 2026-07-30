package dev.deftmartian.runway.domain

import kotlin.math.floor

/**
 * JavaScript's Math.round chooses the value toward positive infinity at an exact half.
 * The original training model used that rule; Kotlin's round uses ties-to-even.
 */
internal fun roundLikeJavaScript(value: Double): Double {
    require(value.isFinite()) { "A finite value is required." }
    return floor(value + 0.5)
}

internal fun roundLikeJavaScriptToInt(value: Double): Int =
    roundLikeJavaScript(value).toInt()

internal fun roundOneDecimalLikeJavaScript(value: Double): Double =
    roundLikeJavaScript(value * 10) / 10
