@file:OptIn(ExperimentalLayoutApi::class)

package az.spendly.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.spendly.domain.FinanceData
import az.spendly.domain.MonthKey
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import az.spendly.domain.formatAZN
import az.spendly.domain.formatDayShort
import az.spendly.domain.formatMonth
import az.spendly.domain.formatSignedAZN
import az.spendly.domain.sortTransactions
import az.spendly.domain.sumOf
import az.spendly.domain.transactionsInMonth
import az.spendly.ui.components.EmptyState
import az.spendly.ui.components.MoneyRow
import az.spendly.ui.components.RowCard
import az.spendly.ui.components.RowDivider
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors

private enum class Filter { ALL, EXPENSE, INCOME }

/**
 * The full log for a month. One filter only — type — because the month
 * switcher already handles the period and categories are shown on the
 * dashboard breakdown.
 */
@Composable
fun TransactionsScreen(
    data: FinanceData,
    month: MonthKey,
    onSelect: (Transaction) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = spendlyColors
    var filter by remember { mutableStateOf(Filter.ALL) }

    val all = sortTransactions(transactionsInMonth(data.transactions, month))
    val visible = when (filter) {
        Filter.ALL -> all
        Filter.EXPENSE -> all.filter { it.type == TransactionType.EXPENSE }
        Filter.INCOME -> all.filter { it.type == TransactionType.INCOME }
    }

    // Adding income to expenses would be meaningless, so the unfiltered view
    // shows the net instead of a combined magnitude.
    val total = if (filter == Filter.ALL) {
        sumOf(visible.map { if (it.type == TransactionType.INCOME) it.amount else -it.amount })
    } else {
        sumOf(visible.map { it.amount })
    }

    if (all.isEmpty()) {
        Column(modifier = modifier.padding(16.dp)) {
            RowCard {
                EmptyState(
                    title = "${formatMonth(month)} üçün qeyd yoxdur",
                    body = "Bu ay üçün əlavə etdiyiniz əməliyyatlar burada görünəcək.",
                    action = {
                        Button(onClick = onAdd) { Text("Əməliyyat əlavə et") }
                    },
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            /* The filter strip and the total. Given no room the total used to
               be squeezed onto two crushed lines, so it drops below instead —
               the same thing the web app does at phone width. */
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterTab("Hamısı", filter == Filter.ALL) { filter = Filter.ALL }
                    FilterTab("Xərclər", filter == Filter.EXPENSE) { filter = Filter.EXPENSE }
                    FilterTab("Gəlirlər", filter == Filter.INCOME) { filter = Filter.INCOME }
                }
                Text(
                    text = "${visible.size} · " +
                        if (filter == Filter.ALL) formatSignedAZN(total) else formatAZN(total),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }

        if (visible.isEmpty()) {
            item {
                RowCard {
                    EmptyState(
                        title = "Uyğun nəticə yoxdur",
                        body = "${formatMonth(month)} üçün " +
                            (if (filter == Filter.INCOME) "gəlir" else "xərc") +
                            " qeydə alınmayıb.",
                    )
                }
            }
        } else {
            item {
                RowCard {
                    visible.forEachIndexed { index, transaction ->
                        if (index > 0) RowDivider()
                        val income = transaction.type == TransactionType.INCOME
                        MoneyRow(
                            leading = formatDayShort(transaction.date),
                            title = transaction.description,
                            meta = transaction.category +
                                (transaction.note?.let { " · $it" } ?: ""),
                            amount = (if (income) "+" else "−") + formatAZN(transaction.amount),
                            amountColor = if (income) colors.positive else colors.text,
                            onClick = { onSelect(transaction) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = spendlyColors
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) colors.text else colors.textMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(if (selected) colors.surfaceSunken else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
