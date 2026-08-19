package az.spendly.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import az.spendly.domain.FinanceData
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionInput
import az.spendly.domain.TransactionType
import az.spendly.domain.FieldErrors
import az.spendly.domain.categoryNames
import az.spendly.domain.formatMonth
import az.spendly.domain.isValidDate
import az.spendly.domain.parseAmount
import az.spendly.domain.toDateKey
import az.spendly.domain.validateTransaction
import az.spendly.ui.components.Micro
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Add / edit. Deliberately six fields, five of which are pre-filled or
 * one-tap, so logging a spend takes a description and an amount.
 */
@Composable
fun TransactionDialog(
    data: FinanceData,
    transaction: Transaction?,
    defaultDate: String,
    onSave: (Transaction) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val expenseCategories = categoryNames(data, TransactionType.EXPENSE)
    val incomeCategories = categoryNames(data, TransactionType.INCOME)
    val isEditing = transaction != null

    var input by remember {
        mutableStateOf(
            TransactionInput(
                date = transaction?.date ?: defaultDate,
                type = transaction?.type ?: TransactionType.EXPENSE,
                category = transaction?.category ?: expenseCategories.firstOrNull().orEmpty(),
                description = transaction?.description.orEmpty(),
                amount = transaction?.let { trimZeros(it.amount) }.orEmpty(),
                note = transaction?.note.orEmpty(),
            ),
        )
    }
    var showErrors by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }

    val categories = if (input.type == TransactionType.INCOME) incomeCategories else expenseCategories

    /* A transaction being edited keeps a category that has since been removed,
       so it stays selectable here — editing an old record must not silently
       move it to a different category. */
    val options = if (categories.contains(input.category)) {
        categories
    } else {
        (categories + input.category).filter { it.isNotBlank() }
    }

    val errors = validateTransaction(input, options)
    val visible = if (showErrors) errors else FieldErrors()

    DialogShell(
        title = if (isEditing) "Əməliyyatı dəyiş" else "Yeni əməliyyat",
        onDismiss = onDismiss,
        footer = {
            if (isEditing && onDelete != null) {
                ConfirmingDeleteButton("Sil", "Silinməni təsdiqlə", onDelete)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Ləğv et") }
            Button(
                onClick = {
                    if (errors.any) {
                        showErrors = true
                    } else {
                        onSave(
                            Transaction(
                                id = transaction?.id.orEmpty(),
                                date = input.date,
                                type = input.type,
                                category = input.category,
                                description = input.description.trim(),
                                amount = parseAmount(input.amount) ?: 0.0,
                                note = input.note.trim().ifBlank { null },
                            ),
                        )
                    }
                },
            ) {
                Text(if (isEditing) "Yadda saxla" else "Əlavə et")
            }
        },
    ) {
        Segmented(
            options = listOf(
                "Xərc" to (input.type == TransactionType.EXPENSE),
                "Gəlir" to (input.type == TransactionType.INCOME),
            ),
            onSelect = { index ->
                val type = if (index == 0) TransactionType.EXPENSE else TransactionType.INCOME
                input = input.copy(
                    type = type,
                    // Category lists differ per type, so reset to a valid one.
                    category = if (type == TransactionType.INCOME) {
                        incomeCategories.firstOrNull().orEmpty()
                    } else {
                        expenseCategories.firstOrNull().orEmpty()
                    },
                )
            },
        )

        LabelledField(
            label = "Məbləğ",
            value = input.amount,
            onValueChange = { input = input.copy(amount = it) },
            error = visible.amount,
            placeholder = "0.00",
            numeric = true,
        )

        LabelledField(
            label = "Təsvir",
            value = input.description,
            onValueChange = { input = input.copy(description = it) },
            error = visible.description,
            placeholder = if (input.type == TransactionType.INCOME) "Avqust maaşı" else "Nə aldınız?",
        )

        CategoryPicker(
            label = "Kateqoriya",
            selected = input.category,
            options = options,
            onSelect = { input = input.copy(category = it) },
        )

        DateField(
            label = "Tarix",
            value = input.date,
            error = visible.date,
            onClick = { pickingDate = true },
        )

        LabelledField(
            label = "Qeyd · seçimli",
            value = input.note,
            onValueChange = { input = input.copy(note = it) },
        )
    }

    if (pickingDate) {
        DatePickerSheet(
            initial = input.date,
            onPick = {
                input = input.copy(date = it)
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
        )
    }
}

/** The date, as a field that opens the calendar rather than one to type into. */
@Composable
private fun DateField(label: String, value: String, error: String?, onClick: () -> Unit) {
    val colors = spendlyColors
    Column(modifier = Modifier.fillMaxWidth()) {
        Micro(label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(colors.surfaceInset)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isValidDate(value)) formatDayLong(value) else value,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
            )
            Text(text = "▾", color = colors.textMuted)
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = colors.negative,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** `2026-08-19` -> `19 Avqust 2026`. */
private fun formatDayLong(date: String): String {
    val day = date.substring(8, 10).trimStart('0')
    return "$day ${formatMonth(date.substring(0, 7))}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(initial: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val startMillis = remember(initial) {
        val date = if (isValidDate(initial)) LocalDate.parse(initial) else LocalDate.now()
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val state = rememberDatePickerState(initialSelectedDateMillis = startMillis)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onPick(toDateKey(picked.year, picked.monthValue, picked.dayOfMonth))
                    } else {
                        onDismiss()
                    }
                },
            ) { Text("Seç") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Ləğv et") } },
    ) {
        DatePicker(state = state)
    }
}

/** `12.5` reads better in a field than `12.5000000001`. */
private fun trimZeros(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
