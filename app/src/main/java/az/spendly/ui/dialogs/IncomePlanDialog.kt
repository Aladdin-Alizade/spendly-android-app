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
import az.spendly.domain.PlannedIncomeRow
import az.spendly.domain.formatAZN
import az.spendly.domain.parseAmount
import az.spendly.ui.theme.spendlyColors

/**
 * The planned side of income, one field per income category.
 *
 * The sheet had two fixed rows here. Income categories are the user's own now,
 * so the form is built from them — adding a category adds a line to plan for,
 * and no category is special.
 */
@Composable
fun IncomePlanDialog(
    rows: List<PlannedIncomeRow>,
    amounts: Map<String, Double>,
    onSave: (Map<String, Double>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = spendlyColors
    val inputs = remember {
        mutableStateMapOf<String, String>().apply {
            rows.forEach { row ->
                put(row.category, (amounts[row.category] ?: 0.0).let { trimZeros(it) })
            }
        }
    }
    var showErrors by remember { mutableStateOf(false) }

    fun errorFor(name: String): String? {
        val amount = parseAmount(inputs[name].orEmpty()) ?: return "Məbləği daxil edin"
        return if (amount < 0) "Mənfi ola bilməz" else null
    }

    val total = rows.sumOf { row ->
        parseAmount(inputs[row.category].orEmpty())?.takeIf { it > 0 } ?: 0.0
    }

    DialogShell(
        title = "Planlaşdırılan gəlir",
        onDismiss = onDismiss,
        footer = {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Ləğv et") }
            Button(
                enabled = rows.isNotEmpty(),
                onClick = {
                    if (rows.any { errorFor(it.category) != null }) {
                        showErrors = true
                    } else {
                        onSave(
                            rows.associate { row ->
                                row.category to (parseAmount(inputs[row.category].orEmpty()) ?: 0.0)
                            },
                        )
                    }
                },
            ) { Text("Yadda saxla") }
        },
    ) {
        if (rows.isEmpty()) {
            Text(
                text = "Hələ gəlir kateqoriyası yoxdur. Kateqoriyalar bölməsindən əlavə edin.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        } else {
            rows.forEach { row ->
                LabelledField(
                    label = row.category + if (row.orphaned) " · kateqoriya silinib" else "",
                    value = inputs[row.category].orEmpty(),
                    onValueChange = { inputs[row.category] = it },
                    error = if (showErrors) errorFor(row.category) else null,
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

private fun trimZeros(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
