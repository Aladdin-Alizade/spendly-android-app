package az.spendly.ui.dialogs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import az.spendly.domain.FinanceData
import az.spendly.domain.SavingsDirection
import az.spendly.domain.SavingsEntry
import az.spendly.domain.SavingsSource
import az.spendly.domain.formatAZN
import az.spendly.domain.isValidDate
import az.spendly.domain.parseAmount
import az.spendly.domain.potBalance
import az.spendly.ui.components.Micro
import az.spendly.ui.theme.spendlyColors

/**
 * One movement into or out of a pot.
 *
 * The two questions this asks that a transaction never does: which way the
 * money went, and — for a deposit — where it came from. The second one is what
 * keeps money that arrived from outside out of the income figures, so it is
 * asked plainly rather than inferred.
 */
@Composable
fun SavingsEntryDialog(
    data: FinanceData,
    entry: SavingsEntry?,
    defaultDate: String,
    defaultPot: String?,
    onSave: (SavingsEntry) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = spendlyColors
    val pots = data.savingsPots.map { it.name }

    var direction by remember { mutableStateOf(entry?.direction ?: SavingsDirection.IN) }
    var source by remember { mutableStateOf(entry?.source ?: SavingsSource.INCOME) }
    var amount by remember { mutableStateOf(entry?.let { trimZeros(it.amount) }.orEmpty()) }
    var pot by remember { mutableStateOf(entry?.pot ?: defaultPot ?: pots.firstOrNull() ?: "") }
    var date by remember { mutableStateOf(entry?.date ?: defaultDate) }
    var note by remember { mutableStateOf(entry?.note.orEmpty()) }
    var showErrors by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }

    /* An entry whose pot has since been removed keeps it, so editing the entry
       does not quietly move the money somewhere else. */
    val options = if (pots.contains(pot)) pots else (pots + pot).filter { it.isNotBlank() }

    /* What the pot holds without this entry — so editing one does not measure
       a withdrawal against money the entry itself put there. */
    val available = potBalance(
        data.savingsEntries.filter { it.id != entry?.id },
        pot,
    )

    val parsed = parseAmount(amount)
    val amountError = when {
        parsed == null -> "Məbləği daxil edin"
        parsed <= 0 -> "Məbləğ sıfırdan böyük olmalıdır"
        direction == SavingsDirection.OUT && parsed > available ->
            "Bu qabda cəmi ${formatAZN(available)} var"
        else -> null
    }
    val dateError = if (isValidDate(date)) null else "Tarixi yoxlayın"
    val potError = if (pot.isBlank()) "Əvvəlcə bir qab yaradın" else null

    DialogShell(
        title = if (entry != null) "Qeydi dəyiş" else "Yığım hərəkəti",
        onDismiss = onDismiss,
        footer = {
            if (entry != null && onDelete != null) {
                ConfirmingDeleteButton("Sil", "Silinməni təsdiqlə", onDelete)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Ləğv et") }
            Button(
                onClick = {
                    if (amountError != null || dateError != null || potError != null ||
                        parsed == null
                    ) {
                        showErrors = true
                    } else {
                        onSave(
                            SavingsEntry(
                                id = entry?.id.orEmpty(),
                                date = date,
                                pot = pot,
                                amount = parsed,
                                direction = direction,
                                // A withdrawal has no source. Carrying one over
                                // from the form would put a meaningless value
                                // in the record and in the database.
                                source = if (direction == SavingsDirection.IN) source else null,
                                note = note.trim().ifBlank { null },
                            ),
                        )
                    }
                },
            ) { Text(if (entry != null) "Yadda saxla" else "Əlavə et") }
        },
    ) {
        Segmented(
            options = listOf(
                "Qoyuram" to (direction == SavingsDirection.IN),
                "Götürürəm" to (direction == SavingsDirection.OUT),
            ),
            onSelect = {
                direction = if (it == 0) SavingsDirection.IN else SavingsDirection.OUT
            },
        )

        if (direction == SavingsDirection.IN) {
            Micro("Pul haradan gəlir?")
            Segmented(
                options = listOf(
                    "Gəlirimdən" to (source == SavingsSource.INCOME),
                    "Kənardan" to (source == SavingsSource.EXTERNAL),
                ),
                onSelect = {
                    source = if (it == 0) SavingsSource.INCOME else SavingsSource.EXTERNAL
                },
            )
            Text(
                text = if (source == SavingsSource.INCOME) {
                    "Qazandığınız puldan kənara qoyulur: xərcləyə biləcəyiniz məbləğ " +
                        "azalır, amma bu xərc sayılmır."
                } else {
                    "Hədiyyə, satış, qaytarılan borc — kənardan birbaşa qaba gəlir. " +
                        "Gəlirinizə də, xərcinizə də toxunmur."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
            )
        }

        LabelledField(
            label = "Məbləğ",
            value = amount,
            onValueChange = { amount = it },
            error = if (showErrors) amountError else null,
            placeholder = "0.00",
            numeric = true,
        )

        CategoryPicker(
            label = "Qab",
            selected = pot,
            options = options,
            onSelect = { pot = it },
        )
        if (showErrors && potError != null) {
            Text(
                text = potError,
                style = MaterialTheme.typography.bodySmall,
                color = colors.negative,
            )
        }

        DateField(
            label = "Tarix",
            value = date,
            error = if (showErrors) dateError else null,
            onClick = { pickingDate = true },
        )

        LabelledField(
            label = "Qeyd · istəyə bağlı",
            value = note,
            onValueChange = { note = it },
        )
    }

    if (pickingDate) {
        DatePickerSheet(
            initial = date,
            onPick = {
                date = it
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
        )
    }
}
