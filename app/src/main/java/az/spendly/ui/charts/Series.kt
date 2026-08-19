/**
 * The six series hues, in the order they are handed out.
 *
 * A category keeps the same colour everywhere it appears on a screen because
 * the colour is assigned by its rank in the breakdown, and every chart is fed
 * the same ranked list. Colour is identity here, not decoration — nothing is
 * coloured that does not stand for a distinct thing.
 */
package az.spendly.ui.charts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import az.spendly.ui.theme.spendlyColors

/**
 * Bind colours to categories once, from the ranked breakdown, and hand the
 * same lookup to every chart on the page. Each chart filters that breakdown
 * differently, so colouring by each chart's own row index would drift — the
 * same category would change colour between two panels sitting side by side.
 */
@Composable
fun rememberCategoryColors(order: List<String>): (String) -> Color {
    val series = spendlyColors.series
    val rest = spendlyColors.textFaint
    return remember(order, series) {
        val rank = order.withIndex().associate { (index, category) -> category to index }
        fun(category: String): Color {
            val index = rank[category] ?: return rest
            return series[index % series.size]
        }
    }
}
