package az.spendly.ui.dialogs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import az.spendly.domain.formatAZN
import az.spendly.domain.parseAmount
import az.spendly.ui.theme.spendlyColors

/** One line of a plan: something the account holds, and whether it still does. */
data class PlannedRow(
    val name: String,
    /** The plan holds a figure for something that no longer exists. */
    val orphaned: Boolean,
)

/**
 * A planned figure per named thing: income per category, savings per pot.
 *
 * Both sides of the plan ask the same question in the same shape, so they get
 * the same form. The list is built from what the account actually holds —
 * adding a category or a pot adds a line to plan for, and none is special.
 */
@Composable
fun PlannedAmountsDialog(
    title: String,
    /** Shown when there is nothing to plan for yet, naming the way out. */
    emptyText: String,
    /** One per row, plus any figure a removed one left behind — editable here
     *  so it can be cleared rather than stranded. */
    rows: List<PlannedRow>,
    amounts: Map<String, Double>,
    onSave: (Map<String, Double>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = spendlyColors
    val inputs = remember {
        mutableStateMapOf<String, String>().apply {
            rows.forEach { row -> put(row.name, trimZeros(amounts[row.name] ?: 0.0)) }
        }
    }
    var showErrors by remember { mutableStateOf(false) }

    fun errorFor(name: String): String? {
        val amount = parseAmount(inputs[name].orEmpty()) ?: return "Məbləği daxil edin"
        return if (amount < 0) "Mənfi ola bilməz" else null
    }

    val total = rows.sumOf { row ->
        parseAmount(inputs[row.name].orEmpty())?.takeIf { it > 0 } ?: 0.0
    }

    DialogShell(
        title = title,
        onDismiss = onDismiss,
        footer = {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Ləğv et") }
            Button(
                enabled = rows.isNotEmpty(),
                onClick = {
                    if (rows.any { errorFor(it.name) != null }) {
                        showErrors = true
                    } else {
                        onSave(
                            rows.associate { row ->
                                row.name to (parseAmount(inputs[row.name].orEmpty()) ?: 0.0)
                            },
                        )
                    }
                },
            ) { Text("Yadda saxla") }
        },
    ) {
        if (rows.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        } else {
            rows.forEach { row ->
                LabelledField(
                    label = row.name + if (row.orphaned) " · silinib" else "",
                    value = inputs[row.name].orEmpty(),
                    onValueChange = { inputs[row.name] = it },
                    error = if (showErrors) errorFor(row.name) else null,
                    placeholder = "0.00",
                    numeric = true,
                )
            }

            if (rows.size > 1) {
                Text(
                    text = "Cəmi: ${formatAZN(total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}
