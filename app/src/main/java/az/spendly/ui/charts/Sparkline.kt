package az.spendly.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import az.spendly.ui.theme.spendlyColors

/**
 * The shape of a run of values, at the size of a line of text.
 *
 * No axis and no labels: it sits beside the number it describes, and its job
 * is the direction and the turning points, not the readings.
 */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val colors = spendlyColors

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp),
    ) {
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it != 0.0 } ?: 1.0
        val inset = 5f

        val points = values.mapIndexed { index, value ->
            Offset(
                x = (index.toFloat() / (values.size - 1)) * size.width,
                y = size.height - ((value - min) / range).toFloat() * (size.height - inset * 2) - inset,
            )
        }

        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }

        drawPath(
            path = path,
            color = colors.accent,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )

        // The end marker, so the latest reading is the one the eye lands on.
        val last = points.last()
        drawCircle(color = colors.surface, radius = 4.5.dp.toPx(), center = last)
        drawCircle(color = colors.accent, radius = 2.5.dp.toPx(), center = last)
    }
}
