package az.spendly.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.spendly.domain.CategoryRow
import az.spendly.domain.formatAZN
import az.spendly.ui.components.Swatch
import az.spendly.ui.theme.spendlyColors
import kotlin.math.roundToInt

data class RingSlice(
    val label: String,
    val value: Double,
    val color: Color,
    /** Absent for the aggregated "everything else" slice, which cannot drill. */
    val category: String? = null,
)

/**
 * Spending against the plan, as one ring.
 *
 * The full circle is whichever is larger — planned or spent — so the drawn
 * arcs are always a true proportion of the same whole. Segments are the ranked
 * categories, in their series colours; whatever is left of the plan stays as
 * empty track. A ring that closes means the plan is used up.
 */
@Composable
fun SpendRing(
    slices: List<RingSlice>,
    spent: Double,
    planned: Double,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = spendlyColors
    val whole = maxOf(spent, planned, 0.01)
    val over = maxOf(spent - planned, 0.0)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(168.dp)) {
                val stroke = 16.dp.toPx()
                val gap = 2f
                val inset = stroke / 2
                val diameter = size.minDimension - stroke
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)

                drawArc(
                    color = colors.track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke),
                )

                var start = -90f
                for (slice in slices.filter { it.value > 0 }) {
                    val sweep = (slice.value / whole).toFloat() * 360f
                    drawArc(
                        color = slice.color,
                        startAngle = start,
                        sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    start += sweep
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "XƏRCLƏNƏN",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textFaint,
                )
                Text(
                    text = formatAZN(spent),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.text,
                )
                Text(
                    text = if (over > 0) {
                        "${formatAZN(over)} plandan artıq"
                    } else {
                        "${formatAZN(planned)} plandan"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (over > 0) colors.negative else colors.textMuted,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (slice in slices) {
                val share = if (spent > 0) ((slice.value / spent) * 100).roundToInt() else 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (slice.category != null) {
                                Modifier.clickable { onSelect(slice.category) }
                            } else {
                                Modifier
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Swatch(slice.color)
                    Text(
                        text = "${slice.label} · $share%",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatAZN(slice.value),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = colors.text,
                    )
                }
            }
        }
    }
}

/** Top [count] categories in their series colours, plus one aggregated rest. */
@Composable
fun ringSlices(
    rows: List<CategoryRow>,
    colorOf: (String) -> Color,
    count: Int = 3,
): List<RingSlice> {
    val named = rows.take(count).map { row ->
        RingSlice(row.category, row.actual, colorOf(row.category), row.category)
    }
    val rest = rows.drop(count).sumOf { it.actual }
    return if (rest > 0) {
        named + RingSlice("Digərləri", rest, spendlyColors.textFaint)
    } else {
        named
    }
}
