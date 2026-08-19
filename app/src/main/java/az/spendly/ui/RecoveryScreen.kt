/**
 * Setting the password a reset link was opened to set.
 *
 * The link signs the app in, so without this screen the user would land on the
 * dashboard with the thing they came to do still undone — and the link is
 * single-use, so they would have to ask for another one to try again.
 *
 * There is no current-password field: the link out of the mailbox is what
 * stands in for it.
 */
package az.spendly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import az.spendly.domain.MIN_PASSWORD_LENGTH
import az.spendly.domain.PasswordChangeErrors
import az.spendly.domain.validateNewPassword
import az.spendly.store.AuthState
import az.spendly.ui.components.Micro
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors

@Composable
fun RecoveryScreen(
    state: AuthState,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = spendlyColors
    var next by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }

    val errors = validateNewPassword(next, repeat)
    val visible = if (showErrors) errors else PasswordChangeErrors()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.lg))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(Radius.lg))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "S",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onAccent,
                    )
                }
                Text(
                    text = "Spendly",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                )
            }

            Text(
                text = state.user?.email?.let { "Yeni şifrənizi təyin edin — $it" }
                    ?: "Yeni şifrənizi təyin edin.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )

            Field(
                label = "Yeni şifrə",
                value = next,
                onValueChange = { next = it },
                error = visible.next,
                hint = "Ən azı $MIN_PASSWORD_LENGTH simvol.",
            )
            Field(
                label = "Yeni şifrə (təkrar)",
                value = repeat,
                onValueChange = { repeat = it },
                error = visible.repeat,
            )

            state.failure?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.negative,
                )
            }

            Button(
                onClick = {
                    if (errors.any) showErrors = true else onSubmit(next)
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.busy) "Gözləyin…" else "Şifrəni təyin et")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onCancel) { Text("Ləğv et və çıx") }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    hint: String? = null,
) {
    val colors = spendlyColors
    Column {
        Micro(label)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            singleLine = true,
            isError = error != null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        when {
            error != null -> Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = colors.negative,
                modifier = Modifier.padding(top = 3.dp),
            )

            hint != null -> Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
