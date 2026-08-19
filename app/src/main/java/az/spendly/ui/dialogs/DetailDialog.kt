package az.spendly.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import az.spendly.domain.formatAZN
import az.spendly.domain.formatDayShort
import az.spendly.domain.sortTransactions
import az.spendly.domain.sumOf
import az.spendly.ui.components.MoneyRow
import az.spendly.ui.components.RowDivider
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors
import kotlin.math.abs

/**
 * The number behind a number. Opened by tapping any category or day on the
 * dashboard, so a figure can always be traced back to the transactions that
 * produced it.
 */
@Composable
fun DetailDialog(
    title: String,
    subtitle: String?,
    transactions: List<Transaction>,
    onSelect: (Transaction) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = spendlyColors
    val ordered = sortTransactions(transactions)
    val total = sumOf(
        ordered.map { if (it.type == TransactionType.INCOME) it.amount else -it.amount },
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.lg))
                .background(colors.surfaceTop),
        ) {
            Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }

            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                items(ordered, key = { it.id }) { transaction ->
                    val income = transaction.type == TransactionType.INCOME
                    MoneyRow(
                        leading = formatDayShort(transaction.date),
                        title = transaction.description,
                        meta = transaction.category + (transaction.note?.let { " · $it" } ?: ""),
                        amount = (if (income) "+" else "−") + formatAZN(transaction.amount),
                        amountColor = if (income) colors.positive else colors.text,
                        onClick = { onSelect(transaction) },
                    )
                    RowDivider()
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${ordered.size} əməliyyat",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                Text(
                    text = (if (total >= 0) "+" else "−") + formatAZN(abs(total)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (total < 0) colors.negative else colors.positive,
                )
            }
        }
    }
}
