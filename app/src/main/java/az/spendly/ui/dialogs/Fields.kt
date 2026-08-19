/**
 * Form pieces shared by the dialogs: a labelled text field that can show its
 * own error, a dropdown that picks one of the user's categories, and the
 * dialog shell they all sit in.
 */
package az.spendly.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import az.spendly.ui.components.Micro
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors

/** The shell every editing dialog uses: a title, a scrolling body, a footer. */
@Composable
fun DialogShell(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    footer: @Composable RowScope.() -> Unit,
    body: @Composable () -> Unit,
) {
    val colors = spendlyColors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.lg))
                .background(colors.surfaceTop)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Tall enough that a form with three fields is not read
                    // through a slot; still bounded, so a long list scrolls
                    // inside the dialog rather than pushing the actions off.
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                body()
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = footer,
            )
        }
    }
}

@Composable
fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    placeholder: String? = null,
    numeric: Boolean = false,
) {
    val colors = spendlyColors
    Column(modifier = modifier.fillMaxWidth()) {
        Micro(label)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            singleLine = true,
            isError = error != null,
            placeholder = placeholder?.let { { Text(it, color = colors.textFaint) } },
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.Decimal)
            } else {
                KeyboardOptions.Default
            },
        )
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

/** One of the user's own categories. Options are passed in rather than read
 *  from a constant, because the list is theirs to change. */
@Composable
fun CategoryPicker(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = spendlyColors
    var open by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Micro(label)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(colors.surfaceInset)
                    .clickable { open = true }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selected.ifBlank { "Seçin" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected.isBlank()) colors.textFaint else colors.text,
                )
                Text(text = "▾", color = colors.textMuted)
            }

            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelect(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/** A two-way switch for the side of the ledger a record belongs to. */
@Composable
fun Segmented(
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = spendlyColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.surfaceSunken)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, (label, selected) ->
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) colors.text else colors.textMuted,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(if (selected) colors.surface else colors.surfaceSunken)
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** A footer button that only removes things, and only on a second tap. */
@Composable
fun ConfirmingDeleteButton(label: String, confirmLabel: String, onDelete: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    TextButton(onClick = { if (confirming) onDelete() else confirming = true }) {
        Text(
            text = if (confirming) confirmLabel else label,
            color = spendlyColors.negative,
        )
    }
}
