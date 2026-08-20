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
import az.spendly.domain.SavingsPot
import az.spendly.domain.formatAZN
import az.spendly.domain.parseAmount
import az.spendly.domain.potBalance
import az.spendly.domain.validatePotName
import az.spendly.ui.theme.spendlyColors

/**
 * Create, rename or remove one pot, and set what it is being filled towards.
 *
 * Removal is the interesting case, and it is the category dialog's problem
 * again: a pot that still holds money cannot simply go, because the entries
 * naming it would be left pointing at nothing and the balance would vanish
 * without anyone being told. So the dialog asks where the money should move.
 */
@Composable
fun SavingsPotDialog(
    data: FinanceData,
    pot: SavingsPot?,
    onAdd: (String, Double?) -> Unit,
    onRename: (String, String) -> Unit,
    onSetTarget: (String, Double?) -> Unit,
    onRemove: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = spendlyColors

    var name by remember { mutableStateOf(pot?.name.orEmpty()) }
    var target by remember { mutableStateOf(pot?.target?.let { trimZeros(it) }.orEmpty()) }
    var showErrors by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }

    val balance = pot?.let { potBalance(data.savingsEntries, it.name) } ?: 0.0
    val entries = pot?.let { p -> data.savingsEntries.count { it.pot == p.name } } ?: 0
    val holdsMoney = entries > 0

    val alternatives = data.savingsPots.filter { it.id != pot?.id }
    var reassignTo by remember { mutableStateOf(alternatives.firstOrNull()?.name.orEmpty()) }

    val nameError = validatePotName(data, name, pot?.id)
    // A target is optional. One that is there has to be a figure worth aiming
    // at — a target of zero is not a target, and saying so beats silently
    // storing nothing where somebody typed something.
    val parsedTarget = if (target.isBlank()) null else parseAmount(target)
    val targetError = if (target.isNotBlank() && (parsedTarget == null || parsedTarget <= 0)) {
        "Hədəf sıfırdan böyük olmalıdır"
    } else {
        null
    }

    DialogShell(
        title = if (pot != null) "Qabı dəyiş" else "Yeni qab",
        onDismiss = onDismiss,
        footer = {
            if (pot != null && !removing) {
                TextButton(onClick = { removing = true }) {
                    Text("Sil", color = colors.negative)
                }
            }
            Spacer(Modifier.weight(1f))

            if (removing) {
                TextButton(onClick = { removing = false }) { Text("Geri") }
                Button(
                    enabled = !(holdsMoney && alternatives.isEmpty()),
                    onClick = {
                        pot?.let { onRemove(it.id, if (holdsMoney) reassignTo else null) }
                        onDismiss()
                    },
                ) { Text(if (holdsMoney) "Köçür və sil" else "Sil") }
            } else {
                TextButton(onClick = onDismiss) { Text("Ləğv et") }
                Button(
                    onClick = {
                        if (nameError != null || targetError != null) {
                            showErrors = true
                        } else {
                            val amount = parsedTarget?.takeIf { it > 0 }
                            if (pot != null) {
                                onRename(pot.id, name)
                                if (amount != pot.target) onSetTarget(pot.id, amount)
                            } else {
                                onAdd(name.trim(), amount)
                            }
                            onDismiss()
                        }
                    },
                ) { Text("Yadda saxla") }
            }
        },
    ) {
        if (removing) {
            if (holdsMoney) {
                Text(
                    text = "${pot?.name} qabında ${formatAZN(balance)} var ($entries qeyd). " +
                        "Silinməmişdən əvvəl bu pul başqa qaba köçürülür.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text,
                )

                if (alternatives.isNotEmpty()) {
                    CategoryPicker(
                        label = "Bura köçürülsün",
                        selected = reassignTo,
                        options = alternatives.map { it.name },
                        onSelect = { reassignTo = it },
                    )
                } else {
                    Text(
                        text = "Köçürmək üçün başqa qab yoxdur. Əvvəlcə bir qab yaradın.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.negative,
                    )
                }
            } else {
                Text(
                    text = "${pot?.name} boşdur və silinə bilər.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text,
                )
            }
        } else {
            LabelledField(
                label = "Ad",
                value = name,
                onValueChange = { name = it },
                error = if (showErrors) nameError else null,
            )

            LabelledField(
                label = "Hədəf · istəyə bağlı",
                value = target,
                onValueChange = { target = it },
                error = if (showErrors) targetError else null,
                placeholder = "0.00",
                numeric = true,
            )
            Text(
                text = "Hədəf qoymasanız, qabın balansı göstərilir — tətbiq olmayan bir " +
                    "finiş xətti uydurmur.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
            )
        }
    }
}
