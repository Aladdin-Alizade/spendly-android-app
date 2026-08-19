package az.spendly.ui.dialogs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import az.spendly.domain.BudgetLine
import az.spendly.domain.FinanceData
import az.spendly.domain.TransactionType
import az.spendly.domain.categoryNames
import az.spendly.domain.parseAmount

data class BudgetLineValues(
    val description: String,
    val category: String,
    val planned: Double,
)

/** Edits one row of 'Aylıq rasxod': description, category, planned amount. */
@Composable
fun BudgetLineDialog(
    data: FinanceData,
    line: BudgetLine?,
    onSave: (BudgetLineValues) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val categories = categoryNames(data, TransactionType.EXPENSE)

    var description by remember { mutableStateOf(line?.description.orEmpty()) }
    var category by remember {
        mutableStateOf(line?.category ?: categories.firstOrNull().orEmpty())
    }
    var planned by remember {
        mutableStateOf(line?.planned?.let { trimZeros(it) }.orEmpty())
    }
    var showErrors by remember { mutableStateOf(false) }

    /* A line whose category has since been removed keeps it, so editing the
       line does not quietly move it somewhere else. */
    val options = if (categories.contains(category)) {
        categories
    } else {
        (categories + category).filter { it.isNotBlank() }
    }

    val amount = parseAmount(planned)
    // A planned amount of zero is valid — the sheet has such rows for lines
    // that are tracked but not budgeted this month.
    val descriptionError = if (description.isBlank()) "Təsvir yazın" else null
    val amountError = when {
        amount == null -> "Məbləği daxil edin"
        amount < 0 -> "Mənfi ola bilməz"
        else -> null
    }

    DialogShell(
        title = if (line != null) "Sətri dəyiş" else "Yeni sətir",
        onDismiss = onDismiss,
        footer = {
            if (onDelete != null) {
                ConfirmingDeleteButton("Sil", "Silinməni təsdiqlə", onDelete)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Ləğv et") }
            Button(
                onClick = {
                    if (descriptionError != null || amountError != null || amount == null) {
                        showErrors = true
                    } else {
                        onSave(BudgetLineValues(description.trim(), category, amount))
                    }
                },
            ) { Text("Yadda saxla") }
        },
    ) {
        LabelledField(
            label = "Təsvir",
            value = description,
            onValueChange = { description = it },
            error = if (showErrors) descriptionError else null,
        )

        CategoryPicker(
            label = "Kateqoriya",
            selected = category,
            options = options,
            onSelect = { category = it },
        )

        LabelledField(
            label = "Planlaşdırılan məbləğ",
            value = planned,
            onValueChange = { planned = it },
            error = if (showErrors) amountError else null,
            placeholder = "0.00",
            numeric = true,
        )
    }
}

private fun trimZeros(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
