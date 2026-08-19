/**
 * Sign in, or create an account.
 *
 * One form with two modes rather than two screens: the fields are identical
 * and someone who mistook one for the other should not have to navigate to
 * fix it.
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import az.spendly.domain.AuthMode
import az.spendly.domain.CredentialErrors
import az.spendly.domain.MIN_PASSWORD_LENGTH
import az.spendly.domain.validateCredentials
import az.spendly.store.AuthState
import az.spendly.ui.components.Micro
import az.spendly.ui.dialogs.Segmented
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors

@Composable
fun AuthScreen(
    state: AuthState,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
) {
    val colors = spendlyColors
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }

    val errors = validateCredentials(email, password, mode)
    val visible = if (showErrors) errors else CredentialErrors()

    fun submit() {
        if (errors.any) {
            showErrors = true
            return
        }
        if (mode == AuthMode.SIGN_UP) onSignUp(email, password) else onSignIn(email, password)
    }

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
                text = if (mode == AuthMode.SIGN_IN) {
                    "Məlumatlarınıza çıxış üçün hesabınıza daxil olun."
                } else {
                    "Məlumatlarınız hesabınıza bağlanır — istənilən cihazdan açıla bilər."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )

            Segmented(
                options = listOf(
                    "Daxil ol" to (mode == AuthMode.SIGN_IN),
                    "Qeydiyyat" to (mode == AuthMode.SIGN_UP),
                ),
                onSelect = { index ->
                    mode = if (index == 0) AuthMode.SIGN_IN else AuthMode.SIGN_UP
                    showErrors = false
                },
            )

            Column {
                Micro("E-poçt")
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    singleLine = true,
                    isError = visible.email != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                )
                visible.email?.let { FieldError(it) }
            }

            Column {
                Micro("Şifrə")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    singleLine = true,
                    isError = visible.password != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                )
                val error = visible.password
                if (error != null) {
                    FieldError(error)
                } else if (mode == AuthMode.SIGN_UP) {
                    Text(
                        text = "Ən azı $MIN_PASSWORD_LENGTH simvol.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textFaint,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }

            state.failure?.let { failure ->
                Text(
                    text = failure,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.negative,
                )
            }
            state.notice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.positive,
                )
            }

            Button(
                onClick = ::submit,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.busy -> "Gözləyin…"
                        mode == AuthMode.SIGN_IN -> "Daxil ol"
                        else -> "Hesab yarat"
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (mode == AuthMode.SIGN_IN) "Hesabınız yoxdur?" else "Hesabınız var?",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                TextButton(
                    onClick = {
                        mode = if (mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN
                        showErrors = false
                    },
                ) {
                    Text(
                        if (mode == AuthMode.SIGN_IN) "Qeydiyyatdan keçin" else "Daxil olun",
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = spendlyColors.negative,
        modifier = Modifier.padding(top = 3.dp),
    )
}
