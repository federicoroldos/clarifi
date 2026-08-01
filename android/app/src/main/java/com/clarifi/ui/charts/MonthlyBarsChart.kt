package com.clarifi.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarifi.core.money.Currency
import com.clarifi.core.money.Money
import com.clarifi.core.time.Dates
import com.clarifi.data.repo.MonthFlow
import com.clarifi.ui.theme.clarifiPalette

/**
 * Money in and out per month, drawn by hand.
 *
 * Handwritten on Compose's Canvas for the same reason the desktop draws its own:
 * a charting library would bring a second design language into an app whose look
 * is deliberately consistent - and none of this is complicated enough to justify it.
 *
 * Tapping a month selects it and shows the exact figures above the chart, which
 * replaces the hover tooltip the desktop uses and cannot exist on a touchscreen.
 */
@Composable
fun MonthlyBarsChart(
    monthly: Map<String, MonthFlow>,
    currency: Currency,
    modifier: Modifier = Modifier,
) {
    val palette = clarifiPalette
    val months = remember(monthly) { monthly.keys.sorted() }
    var selected by remember(monthly) { mutableStateOf<String?>(null) }

    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outline
    val labelColor = palette.textMuted
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    val maxValue = remember(monthly) {
        monthly.values.maxOfOrNull { maxOf(it.income, it.expense) } ?: 0.0
    }
    val scale = remember(maxValue) { niceScale(maxValue, targetTicks = 4) }

    // The gutter is measured, not guessed. At a fixed width the bars were drawn
    // over the tail of the widest label and "US$2,000" read as "US$2,0".
    val tickLabels = remember(scale, currency, labelStyle) {
        (0..scale.ticks).map { tick ->
            textMeasurer.measure(Money.formatAxis(currency, scale.step * tick), labelStyle)
        }
    }
    val labelGap = 8.dp
    val axisWidth = with(LocalDensity.current) {
        tickLabels.maxOf { it.size.width } + labelGap.toPx()
    }

    Column(modifier = modifier) {
        SelectionCaption(
            selected = selected,
            monthly = monthly,
            currency = currency,
            palette.green,
            palette.red,
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(months, axisWidth) {
                    detectTapGestures { offset ->
                        if (months.isEmpty()) return@detectTapGestures
                        val slotWidth = (size.width - axisWidth) / months.size
                        val index = ((offset.x - axisWidth) / slotWidth).toInt()
                        val month = months.getOrNull(index)
                        selected = if (month == selected) null else month
                    }
                },
        ) {
            if (months.isEmpty()) return@Canvas

            val plotLeft = axisWidth
            val plotBottom = size.height - X_LABEL_HEIGHT
            val plotHeight = plotBottom
            val plotWidth = size.width - plotLeft

            // Horizontal grid lines and their labels.
            for (tick in 0..scale.ticks) {
                val value = scale.step * tick
                val y = plotBottom - (value / scale.max * plotHeight).toFloat()
                drawLine(
                    color = axisColor,
                    start = Offset(plotLeft, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                // Right-aligned against the plot, the way an axis reads.
                val label = tickLabels[tick]
                drawText(
                    textLayoutResult = label,
                    topLeft = Offset(
                        plotLeft - labelGap.toPx() - label.size.width,
                        y - label.size.height / 2f,
                    ),
                )
            }

            val slotWidth = plotWidth / months.size
            val barWidth = (slotWidth * 0.28f).coerceAtMost(22.dp.toPx())
            val gap = barWidth * 0.24f

            months.forEachIndexed { index, month ->
                val flow = monthly[month] ?: MonthFlow()
                val slotCenter = plotLeft + slotWidth * index + slotWidth / 2f
                val isSelected = selected == null || selected == month

                drawBar(
                    x = slotCenter - barWidth - gap / 2f,
                    width = barWidth,
                    value = flow.income,
                    max = scale.max,
                    plotBottom = plotBottom,
                    plotHeight = plotHeight,
                    color = palette.green,
                    dimmed = !isSelected,
                )
                drawBar(
                    x = slotCenter + gap / 2f,
                    width = barWidth,
                    value = flow.expense,
                    max = scale.max,
                    plotBottom = plotBottom,
                    plotHeight = plotHeight,
                    color = palette.red,
                    dimmed = !isSelected,
                )

                val monthLabel = textMeasurer.measure(
                    Dates.shortMonth(month),
                    labelStyle.copy(color = if (selected == month) palette.green else labelColor),
                )
                drawText(
                    textLayoutResult = monthLabel,
                    topLeft = Offset(
                        slotCenter - monthLabel.size.width / 2f,
                        plotBottom + 6f,
                    ),
                )
            }
        }

        Legend(income = palette.green, expense = palette.red)
    }
}

@Composable
private fun SelectionCaption(
    selected: String?,
    monthly: Map<String, MonthFlow>,
    currency: Currency,
    incomeColor: Color,
    expenseColor: Color,
) {
    val flow = selected?.let { monthly[it] }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                selected != null -> Dates.displayMonth(selected)
                // With a single month there is nothing to compare, so name it instead
                // of calling it "the last 1 months".
                monthly.size == 1 -> Dates.displayMonth(monthly.keys.first())
                else -> "Last ${monthly.size} months"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        if (flow != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "+${Money.format(currency, flow.income)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = incomeColor,
                )
                Text(
                    text = "-${Money.format(currency, flow.expense)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = expenseColor,
                )
            }
        } else if (monthly.size > 1) {
            Text(
                text = "Tap a month",
                style = MaterialTheme.typography.bodySmall,
                color = clarifiPalette.textMuted,
            )
        }
    }
}

@Composable
private fun Legend(income: Color, expense: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot("Income", income)
        LegendDot("Spending", expense)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.bodySmall, color = clarifiPalette.textMuted)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBar(
    x: Float,
    width: Float,
    value: Double,
    max: Double,
    plotBottom: Float,
    plotHeight: Float,
    color: Color,
    dimmed: Boolean,
) {
    if (value <= 0) return
    val height = (value / max * plotHeight).toFloat().coerceAtLeast(2f)
    drawRoundRect(
        color = if (dimmed) color.copy(alpha = 0.28f) else color,
        topLeft = Offset(x, plotBottom - height),
        size = Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2.5f),
    )
}

private const val X_LABEL_HEIGHT = 22f
