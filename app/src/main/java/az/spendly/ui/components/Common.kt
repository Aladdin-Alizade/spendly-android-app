/**
 * The pieces every screen is built from: a panel that names itself, the rows
 * that carry money, and the small status marks that qualify a figure.
 */
@file:OptIn(ExperimentalLayoutApi::class)

package az.spendly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors

/** A card that names itself, the way each dashboard panel does on the web. */
@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    /** For content that draws its own full-width rows. */
    flush: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = spendlyColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(Radius.md)),
    ) {
        /* A panel head is a name and, sometimes, a sentence about it. Held to
           one line the sentence squeezed the name down to a letter a line, so
           it drops underneath instead — the same thing the web app does once
           the window is phone-width. */
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Micro(title)
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
        Column(
            modifier = if (flush) {
                Modifier
            } else {
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            },
            content = content,
        )
    }
}

/** The `.micro` label: small, upper, tracked out. */
@Composable
fun Micro(text: String, color: Color? = null, underlined: Boolean = false) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color ?: spendlyColors.textMuted,
        // A dotted underline is how a label says it can be tapped, without
        // dressing the whole cell up as a button.
        textDecoration = if (underlined) TextDecoration.Underline else null,
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    /*
     * Heading on the left, buttons on the right — until the two do not fit,
     * and then the buttons take a row of their own underneath.
     *
     * It is measured rather than flowed because the buttons are a row of their
     * own: handed less width than they want they quietly squeeze instead of
     * refusing, so nothing above them ever learns they did not fit. Asked
     * first how much they want, the answer is plain.
     */
    val gap = 12.dp
    val rowGap = 8.dp

    Layout(
        contents = listOf(
            {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = spendlyColors.text,
                )
            },
            { action?.invoke() },
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    ) { (titleMeasurables, actionMeasurables), constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)

        val actionPlaceable = actionMeasurables.firstOrNull()?.measure(loose)
        val wanted = actionMeasurables.firstOrNull()?.maxIntrinsicWidth(constraints.maxHeight) ?: 0
        val titleWanted = titleMeasurables.first().maxIntrinsicWidth(constraints.maxHeight)
        val actionWidth = maxOf(actionPlaceable?.width ?: 0, wanted)

        val sideBySide = actionPlaceable == null ||
            titleWanted + gap.roundToPx() + actionWidth <= width

        val titlePlaceable = titleMeasurables.first().measure(
            Constraints(
                maxWidth = if (sideBySide && actionPlaceable != null) {
                    (width - actionPlaceable.width - gap.roundToPx()).coerceAtLeast(0)
                } else {
                    width
                },
            ),
        )

        val height = if (sideBySide) {
            maxOf(titlePlaceable.height, actionPlaceable?.height ?: 0)
        } else {
            titlePlaceable.height + rowGap.roundToPx() + (actionPlaceable?.height ?: 0)
        }

        layout(width, height) {
            if (sideBySide) {
                titlePlaceable.place(0, (height - titlePlaceable.height) / 2)
                actionPlaceable?.place(
                    width - actionPlaceable.width,
                    (height - actionPlaceable.height) / 2,
                )
            } else {
                titlePlaceable.place(0, 0)
                actionPlaceable?.place(
                    width - actionPlaceable.width,
                    titlePlaceable.height + rowGap.roundToPx(),
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = spendlyColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 26.dp, horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(6.dp))
            action()
        }
    }
}

/** A short qualifier next to a figure: a change, a share, a status. */
@Composable
fun Pill(
    text: String,
    tone: PillTone = PillTone.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    val colors = spendlyColors
    val (background, foreground) = when (tone) {
        PillTone.POSITIVE -> colors.positiveSoft to colors.positive
        PillTone.NEGATIVE -> colors.negativeSoft to colors.negative
        PillTone.NEUTRAL -> colors.surfaceSunken to colors.textMuted
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = foreground,
        // A pill is a mark, not a paragraph. Allowed to wrap it became a blob
        // a letter wide next to whatever squeezed it.
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

enum class PillTone { NEUTRAL, POSITIVE, NEGATIVE }

/** The colour key that stands for one category. */
@Composable
fun Swatch(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** Proportional bar. Past 100% it flips to the warning colour. */
@Composable
fun Meter(value: Double, max: Double, modifier: Modifier = Modifier, color: Color? = null) {
    val colors = spendlyColors
    val ratio = if (max > 0) (value / max) else 0.0
    val clamped = ratio.coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.track),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (ratio > 1) colors.negative else color ?: colors.accent),
        )
    }
}

/** One line of a list: date, what it was, and the amount. */
@Composable
fun MoneyRow(
    title: String,
    meta: String?,
    amount: String,
    modifier: Modifier = Modifier,
    leading: String? = null,
    amountColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = spendlyColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (leading != null) {
            Text(
                text = leading,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
                maxLines = 1,
                modifier = Modifier.width(52.dp),
            )
        }
        /* One line each, cut with an ellipsis — what the web app's rows do.
           Wrapping let a long description push the amount off the row, and a
           row of money whose amount is missing is worse than a shortened
           description. */
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        Text(
            text = amount,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = amountColor ?: colors.text,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * As many equal columns as fit, and no fewer than one.
 *
 * The web app writes this as `repeat(auto-fit, minmax(<min>, 1fr))`, and it is
 * why three figures become two rows of two on a phone rather than three
 * columns too narrow for a sum in manat — held at three, the currency mark
 * dropped onto a line of its own.
 */
@Composable
fun AutoGrid(
    minCellWidth: Dp,
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 12.dp,
    verticalSpacing: Dp = 12.dp,
    cells: List<@Composable () -> Unit>,
) {
    if (cells.isEmpty()) return
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = ((maxWidth + horizontalSpacing) / (minCellWidth + horizontalSpacing))
            .toInt()
            .coerceIn(1, cells.size)

        Column(verticalArrangement = Arrangement.spacedBy(verticalSpacing)) {
            cells.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)) {
                    row.forEach { cell ->
                        Box(modifier = Modifier.weight(1f)) { cell() }
                    }
                    // A short last row keeps the column width of the ones above.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The hairline between rows of one card. */
@Composable
fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(spendlyColors.border),
    )
}

/** A card that holds rows and nothing else. */
@Composable
fun RowCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = spendlyColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(Radius.md)),
    ) {
        content()
    }
}
