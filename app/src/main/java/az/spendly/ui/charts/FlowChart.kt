package az.spendly.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import az.spendly.domain.DayActivity
import az.spendly.domain.FlowBucket
import az.spendly.domain.Transaction
import az.spendly.ui.theme.spendlyColors

/**
 * Money in, money out, and the balance that results — one picture.
 *
 * Bars compare income against expenses per bucket; the overlaid line is the
 * running balance, so the chart also answers "how did I get from the start of
 * the period to where I am now" without needing a second visual.
 */
@Composable
fun FlowChart(buckets: List<FlowBucket>, modifier: Modifier = Modifier) {
    val colors = spendlyColors
    val peak = buckets.flatMap { listOf(it.income, it.expenses) }.maxOrNull() ?: 0.0
    val balances = buckets.map { it.balance }
    val balanceMax = maxOf(balances.maxOrNull() ?: 0.0, 0.0)
    val balanceMin = minOf(balances.minOrNull() ?: 0.0, 0.0)
    val balanceRange = (balanceMax - balanceMin).takeIf { it != 0.0 } ?: 1.0
    val showLine = buckets.size > 1 && balances.any { it != 0.0 }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (bucket in buckets) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Bar(bucket.income, peak, colors.series[1], Modifier.weight(1f))
                        Bar(bucket.expenses, peak, colors.series[0], Modifier.weight(1f))
                    }
                }
            }

            if (showLine) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // The extremes sit inside the plot rather than on its
                    // edges, so the highest and lowest markers are not clipped
                    // in half by the top and bottom of the card.
                    val inset = 6.dp.toPx()
                    val plot = size.height - inset * 2
                    val points = buckets.mapIndexed { index, bucket ->
                        Offset(
                            x = ((index + 0.5f) / buckets.size) * size.width,
                            y = size.height - inset -
                                ((bucket.balance - balanceMin) / balanceRange).toFloat() * plot,
                        )
                    }
                    val path = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(
                        path = path,
                        color = colors.text,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                    points.forEach { point ->
                        drawCircle(colors.surface, radius = 4.dp.toPx(), center = point)
                        drawCircle(colors.text, radius = 2.dp.toPx(), center = point)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (bucket in buckets) {
                Text(
                    text = bucket.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendItem("Gəlir", colors.series[1])
            LegendItem("Xərc", colors.series[0])
            if (showLine) LegendItem("Balans", colors.text)
        }
    }
}

@Composable
private fun Bar(value: Double, peak: Double, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val fraction = if (peak > 0) (value / peak).toFloat().coerceIn(0f, 1f) else 0f
    Box(
        modifier = modifier
            .fillMaxHeight(fraction.coerceAtLeast(if (value > 0) 0.02f else 0f))
            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
            .background(color),
    )
}

@Composable
private fun LegendItem(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = spendlyColors.textMuted,
        )
    }
}

/**
 * When money moved, across one month.
 *
 * Income rises above the day axis, spending drops below it, and bar length is
 * the amount — so a heavy week is visible as a cluster of long bars rather
 * than as a row of numbers. Tapping a day opens what happened on it.
 */
@Composable
fun DayStrip(
    days: List<DayActivity>,
    onSelect: (List<Transaction>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = spendlyColors
    val peak = days.flatMap { listOf(it.income, it.expenses) }.maxOrNull() ?: 0.0

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (day in days) {
                val active = day.transactions.isNotEmpty()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (active) {
                                Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .clickable { onSelect(day.transactions) }
                            } else {
                                Modifier
                            },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(scale(day.income, peak))
                                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                .background(colors.series[1]),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.border),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(scale(day.expenses, peak))
                                .clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp))
                                .background(colors.series[0]),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("1", "${days.size / 2}", "${days.size}").forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                )
            }
        }
    }
}

private fun scale(value: Double, peak: Double): Float = when {
    peak <= 0 -> 0f
    value <= 0 -> 0f
    else -> ((value / peak).toFloat()).coerceAtLeast(0.06f).coerceAtMost(1f)
}
