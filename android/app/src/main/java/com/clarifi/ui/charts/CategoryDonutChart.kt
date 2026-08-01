package com.clarifi.ui.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.clarifi.core.money.Currency
import com.clarifi.core.money.Money
import com.clarifi.ui.theme.Motion
import com.clarifi.ui.theme.clarifiPalette
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.PI

/**
 * Spending by category.
 *
 * The desktop puts the legend beside the donut; on a phone that leaves both
 * cramped, so it wraps underneath instead. Tapping a slice - or its legend
 * entry - pulls the figure into the middle of the ring, which is where the eye
 * already is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryDonutChart(
    spendByCategory: Map<String, Double>,
    currency: Currency,
    modifier: Modifier = Modifier,
) {
    val palette = clarifiPalette
    val entries = remember(spendByCategory) {
        spendByCategory.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
    val total = remember(entries) { entries.sumOf { it.second } }
    var selected by remember(entries) { mutableStateOf<String?>(null) }

    if (entries.isEmpty() || total <= 0) return

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .pointerInput(entries) {
                        detectTapGestures { offset ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val outerRadius = minOf(size.width, size.height) / 2f - 8f
                            val innerRadius = outerRadius * 0.56f
                            val distance = hypot(offset.x - center.x, offset.y - center.y)
                            if (distance < innerRadius || distance > outerRadius) {
                                selected = null
                                return@detectTapGestures
                            }
                            // Angles start at 12 o'clock and run clockwise, matching the drawing.
                            var angle = Math.toDegrees(
                                atan2((offset.y - center.y).toDouble(), (offset.x - center.x).toDouble())
                            ) + 90.0
                            if (angle < 0) angle += 360.0

                            var sweepStart = 0.0
                            for ((category, value) in entries) {
                                val sweep = value / total * 360.0
                                if (angle >= sweepStart && angle < sweepStart + sweep) {
                                    selected = if (selected == category) null else category
                                    return@detectTapGestures
                                }
                                sweepStart += sweep
                            }
                        }
                    },
            ) {
                val outerRadius = minOf(size.width, size.height) / 2f - 8f
                val innerRadius = outerRadius * 0.56f
                val center = Offset(size.width / 2f, size.height / 2f)
                val ringWidth = outerRadius - innerRadius

                var startAngle = -90f
                entries.forEachIndexed { index, (category, value) ->
                    val sweep = (value / total * 360.0).toFloat()
                    val color = Color(CategoryChartColors[index % CategoryChartColors.size])
                    val dimmed = selected != null && selected != category

                    // Drawn as a thick arc rather than filled wedges: no seams where
                    // neighbouring slices meet, and the hole needs no cover-up circle.
                    drawArc(
                        color = if (dimmed) color.copy(alpha = 0.25f) else color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(
                            center.x - innerRadius - ringWidth / 2f,
                            center.y - innerRadius - ringWidth / 2f,
                        ),
                        size = Size(
                            (innerRadius + ringWidth / 2f) * 2f,
                            (innerRadius + ringWidth / 2f) * 2f,
                        ),
                        style = Stroke(width = ringWidth),
                    )
                    startAngle += sweep
                }
            }

            val shown = selected?.let { category -> entries.first { it.first == category } }
            val amount by animateFloatAsState(
                targetValue = (shown?.second ?: total).toFloat(),
                animationSpec = Motion.spring(),
                label = "donutCentre",
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = Money.format(currency, amount.toDouble()),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = shown?.first ?: "total spent",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                )
                if (shown != null) {
                    Text(
                        text = "${Math.round(shown.second / total * 100)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textMuted,
                    )
                }
            }
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.forEachIndexed { index, (category, value) ->
                LegendEntry(
                    label = category,
                    amount = Money.format(currency, value),
                    color = Color(CategoryChartColors[index % CategoryChartColors.size]),
                    dimmed = selected != null && selected != category,
                    onClick = { selected = if (selected == category) null else category },
                )
            }
        }
    }
}

@Composable
private fun LegendEntry(
    label: String,
    amount: String,
    color: Color,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .padding(vertical = 2.dp)
            .then(
                Modifier.pointerInput(Unit) {
                    detectTapGestures { onClick() }
                }
            ),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(if (dimmed) color.copy(alpha = 0.3f) else color, CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (dimmed) clarifiPalette.textMuted else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = amount,
            style = MaterialTheme.typography.bodySmall,
            color = clarifiPalette.textMuted,
        )
    }
}
