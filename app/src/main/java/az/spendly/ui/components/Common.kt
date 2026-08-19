/**
 * The pieces every screen is built from: a panel that names itself, the rows
 * that carry money, and the small status marks that qualify a figure.
 */
package az.spendly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = spendlyColors.text,
        )
        action?.invoke()
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
                modifier = Modifier.width(52.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.text,
            )
            if (meta != null) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
        trailing?.invoke()
        Text(
            text = amount,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = amountColor ?: colors.text,
        )
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
