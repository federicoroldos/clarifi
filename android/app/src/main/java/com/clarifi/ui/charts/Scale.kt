package com.clarifi.ui.charts

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * A y-axis that lands on round numbers.
 *
 * Direct port of `niceScale` in templates/index.html: the rough step is snapped
 * up to the next 1, 2, 5 or 10 times a power of ten, so the axis reads
 * 0/50/100/150 rather than 0/47/94/141.
 */
data class NiceScale(val max: Double, val step: Double, val ticks: Int)

fun niceScale(maxValue: Double, targetTicks: Int): NiceScale {
    if (maxValue <= 0 || !maxValue.isFinite()) {
        return NiceScale(max = targetTicks.toDouble(), step = 1.0, ticks = targetTicks)
    }
    val roughStep = maxValue / targetTicks
    val magnitude = 10.0.pow(floor(log10(roughStep)))
    val normalized = roughStep / magnitude
    val niceFactor = when {
        normalized <= 1 -> 1.0
        normalized <= 2 -> 2.0
        normalized <= 5 -> 5.0
        else -> 10.0
    }
    val step = niceFactor * magnitude
    val niceMax = ceil(maxValue / step) * step
    return NiceScale(max = niceMax, step = step, ticks = Math.round(niceMax / step).toInt())
}

/** Category slice colours, in the desktop's order (`CAT_COLORS_CHART`). */
val CategoryChartColors = listOf(
    0xFF3B82F6, 0xFFEF4444, 0xFFF59E0B, 0xFFA855F7,
    0xFF06B6D4, 0xFFEC4899, 0xFFEAB308, 0xFF84CC16,
)
