/**
 * The bar charts: ranked spending, plan against actual, income by source and
 * the weekday pattern. All four are laid out with real composables rather than
 * drawn, so text stays selectable and scales with the system font size.
 */
package az.spendly.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.spendly.domain.CategoryRow
import az.spendly.domain.IncomeSource
import az.spendly.domain.WeekdayLoad
import az.spendly.domain.formatAZN
import az.spendly.domain.formatWeekdayShort
import az.spendly.domain.round2
import az.spendly.ui.components.Pill
import az.spendly.ui.components.PillTone
import az.spendly.ui.components.Swatch
import az.spendly.ui.theme.spendlyColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ranked spending by category. A horizontal bar per category, because ranking
 * is what matters here and a donut makes ten similar slices hard to order —
 * the ring answers "how much of the plan", this answers "in what order".
 */
@Composable
fun RankedBars(
    rows: List<CategoryRow>,
    colorOf: (String) -> Color,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = spendlyColors
    val peak = rows.maxOfOrNull { it.actual } ?: 0.0

    Column(modifier = modifier.fillMaxWidth()) {
        for (row in rows) {
            val moved = row.changeRatio?.takeIf { abs(it) >= 0.01 }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(row.category) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Swatch(colorOf(row.category))
                    Text(
                        text = row.category,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatAZN(row.actual),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text,
                    )
                }

                Track {
                    Fill(
                        fraction = fraction(row.actual, peak),
                        color = colorOf(row.category),
                        dimmed = row.unplanned,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${(row.share * 100).roundToInt()}% xərclərin payı",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                    if (moved != null) {
                        Text(
                            text = "${if (moved > 0) "↑" else "↓"}${(abs(moved) * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (moved > 0) colors.negative else colors.positive,
                        )
                    }
                    if (row.unplanned) {
                        Text(
                            text = "planlaşdırılmayıb",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textFaint,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Planned against actual, per category.
 *
 * The planned amount is a track that actual spend fills, so over and under are
 * read from the shape before any number is read. Spend beyond the plan is
 * drawn in the warning colour and continues past the track — which is what
 * going over budget looks like.
 */
@Composable
fun PlanBars(
    rows: List<CategoryRow>,
    colorOf: (String) -> Color,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = spendlyColors
    // One scale across all rows, so bar lengths are comparable between them.
    val peak = rows.flatMap { listOf(it.planned, it.actual) }.maxOrNull() ?: 0.0

    Column(modifier = modifier.fillMaxWidth()) {
        for (row in rows) {
            val over = round2(row.actual - row.planned)
            val covered = minOf(row.actual, row.planned)
            val untouched = row.actual == 0.0 && row.planned > 0

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(row.category) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = row.category,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatAZN(row.actual),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text,
                    )
                    Text(
                        text = " / ${formatAZN(row.planned)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textFaint,
                    )
                }

                // The plan as a recess, the actual spend filling it, and the
                // overspend continuing past its end.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(9.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction(row.planned, peak))
                            .height(9.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.track),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction(covered, peak))
                            .height(9.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(colorOf(row.category)),
                    )
                    if (over > 0) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth(fraction(row.planned, peak)))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction(over, peak - row.planned))
                                    .height(9.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(colors.negative),
                            )
                        }
                    }
                }

                Pill(
                    text = when {
                        untouched -> "istifadə olunmayıb"
                        over > 0 -> "+${formatAZN(over)}"
                        over < 0 -> "−${formatAZN(-over)}"
                        else -> "plana uyğun"
                    },
                    tone = when {
                        over > 0 -> PillTone.NEGATIVE
                        over < 0 -> PillTone.POSITIVE
                        else -> PillTone.NEUTRAL
                    },
                )
            }
        }
    }
}

/** Income keeps to its own end of the palette, so a bar is never mistaken for
 *  spending at a glance. */
@Composable
fun IncomeBars(rows: List<IncomeSource>, modifier: Modifier = Modifier) {
    val colors = spendlyColors
    val hues = listOf(colors.series[1], colors.series[5], colors.series[3])
    val peak = rows.flatMap { listOf(it.actual, it.planned) }.maxOrNull() ?: 0.0

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        rows.forEachIndexed { index, row ->
            val short = round2(row.planned - row.actual)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = row.category,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatAZN(row.actual),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(9.dp),
                ) {
                    if (row.planned > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction(row.planned, peak))
                                .height(9.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(colors.track),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction(row.actual, peak))
                            .height(9.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(hues[index % hues.size]),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (row.planned > 0) {
                        Text(
                            text = "${formatAZN(row.planned)} planlaşdırılıb",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                        if (abs(short) >= 0.01) {
                            Text(
                                text = "${if (short > 0) "−" else "+"}${formatAZN(abs(short))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (short > 0) colors.negative else colors.positive,
                            )
                        }
                    } else {
                        Text(
                            text = "planlaşdırılmayıb",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textFaint,
                        )
                    }
                    if (row.share > 0) {
                        Text(
                            text = "${(row.share * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Spending by day of the week.
 *
 * One series, so one hue — the heaviest day is picked out by weight rather
 * than by a second colour, which would imply a second kind of thing. Days with
 * nothing on them keep their column, because an empty Sunday is part of the
 * pattern.
 */
@Composable
fun WeekdayBars(
    rows: List<WeekdayLoad>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = spendlyColors
    val peak = rows.maxOfOrNull { it.expenses } ?: 0.0

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (row in rows) {
            val isPeak = peak > 0 && row.expenses == peak
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (row.count > 0) Modifier.clickable { onSelect(row.weekday) } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    val height = if (peak > 0) {
                        ((row.expenses / peak).toFloat()).coerceAtLeast(if (row.expenses > 0) 0.04f else 0f)
                    } else {
                        0f
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(height)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (isPeak) colors.accent else colors.accentSoft),
                    )
                }
                Text(
                    text = formatWeekdayShort(row.weekday),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/* --- shared bits ---------------------------------------------------- */

@Composable
private fun Track(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(9.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(spendlyColors.track),
    ) {
        content()
    }
}

@Composable
private fun Fill(fraction: Float, color: Color, dimmed: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(9.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (dimmed) color.copy(alpha = 0.55f) else color),
    )
}

private fun fraction(value: Double, of: Double): Float =
    if (of > 0) (value / of).toFloat().coerceIn(0f, 1f) else 0f
