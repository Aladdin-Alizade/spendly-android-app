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
import az.spendly.domain.CategoryDef
import az.spendly.domain.FinanceData
import az.spendly.domain.TransactionType
import az.spendly.domain.categoriesOfType
import az.spendly.domain.categoryUsage
import az.spendly.domain.validateCategoryName
import az.spendly.ui.theme.spendlyColors

/**
 * Create, rename or remove one category.
 *
 * Removal is the interesting case. A category that nothing uses is simply
 * dropped. One that is in use cannot be — the transactions naming it would be
 * left pointing at something that no longer exists — so the dialog asks where
 * that history should go instead, and says exactly how much of it there is.
 * The alternative, deleting the records too, would destroy money the user
 * never asked to remove.
 */
@Composable
fun CategoryDialog(
    data: FinanceData,
    category: CategoryDef?,
    type: TransactionType,
    onAdd: (String, TransactionType) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = spendlyColors
    val kind = category?.type ?: type

    var name by remember { mutableStateOf(category?.name.orEmpty()) }
    var showErrors by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    val usage = category?.let { categoryUsage(data, it.name) }
    val inUse = usage != null && usage.inUse

    val alternatives = categoriesOfType(data, kind).filter { it.id != category?.id }
    var reassignTo by remember { mutableStateOf(alternatives.firstOrNull()?.name.orEmpty()) }

    val error = validateCategoryName(data, name, kind, category?.id)

    DialogShell(
        title = if (category != null) "Kateqoriyanı dəyiş" else "Yeni kateqoriya",
        subtitle = if (kind == TransactionType.INCOME) "Gəlir" else "Xərc",
        onDismiss = onDismiss,
        footer = {
            if (category != null && !removing) {
                TextButton(onClick = { removing = true }) {
                    Text("Sil", color = colors.negative)
                }
            }
            Spacer(Modifier.weight(1f))

            if (removing) {
                TextButton(onClick = { removing = false }) { Text("Geri") }
                Button(
                    enabled = !(inUse && alternatives.isEmpty()),
                    onClick = {
                        // Nothing uses it, or everything that does has somewhere to go.
                        category?.let { onRemove(it.id, if (inUse) reassignTo else null) }
                        onDismiss()
                    },
                ) { Text(if (inUse) "Keçir və sil" else "Sil") }
            } else {
                TextButton(onClick = onDismiss) { Text("Ləğv et") }
                Button(
                    onClick = {
                        if (error != null) {
                            showErrors = true
                        } else {
                            if (category != null) {
                                onRename(category.id, name)
                            } else {
                                onAdd(name.trim(), kind)
                            }
                            onDismiss()
                        }
                    },
                ) { Text("Yadda saxla") }
            }
        },
    ) {
        if (removing) {
            if (inUse) {
                Text(
                    text = "${category.name} istifadə olunur: " +
                        listOfNotNull(
                            usage.transactions.takeIf { it > 0 }?.let { "$it əməliyyat" },
                            usage.budgetLines.takeIf { it > 0 }?.let { "$it büdcə sətri" },
                        ).joinToString(", ") +
                        ". Silinməmişdən əvvəl bunlar başqa kateqoriyaya keçirilir.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text,
                )

                if (alternatives.isNotEmpty()) {
                    CategoryPicker(
                        label = "Bura keçirilsin",
                        selected = reassignTo,
                        options = alternatives.map { it.name },
                        onSelect = { reassignTo = it },
                    )
                } else {
                    Text(
                        text = "Keçirmək üçün başqa kateqoriya yoxdur. Əvvəlcə bir kateqoriya əlavə edin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.negative,
                    )
                }
            } else {
                Text(
                    text = "${category?.name.orEmpty()} heç bir yerdə istifadə olunmur və silinə bilər.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text,
                )
            }
        } else {
            LabelledField(
                label = "Ad",
                value = name,
                onValueChange = { name = it },
                error = if (showErrors) error else null,
            )

            if (inUse) {
                Text(
                    text = "Adın dəyişməsi bu kateqoriyanı işlədən " +
                        listOfNotNull(
                            usage.transactions.takeIf { it > 0 }?.let { "$it əməliyyatı" },
                            usage.budgetLines.takeIf { it > 0 }?.let { "$it büdcə sətrini" },
                        ).joinToString(" və ") +
                        " də yeni ada keçirir. Məbləğlər dəyişmir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
        }
    }
}
