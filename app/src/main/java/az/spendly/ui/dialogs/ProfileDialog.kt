@file:OptIn(ExperimentalLayoutApi::class)

package az.spendly.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import az.spendly.data.AccountUser
import az.spendly.data.SyncState
import az.spendly.data.SyncStatus
import az.spendly.domain.FinanceData
import az.spendly.domain.MIN_PASSWORD_LENGTH
import az.spendly.domain.PasswordChangeErrors
import az.spendly.domain.PasswordChangeInput
import az.spendly.domain.validatePasswordChange
import az.spendly.domain.TransactionType
import az.spendly.domain.categoriesOfType
import az.spendly.domain.formatAZN
import az.spendly.domain.formatMonth
import az.spendly.domain.knownMonths
import az.spendly.domain.totalHoldings
import az.spendly.ui.components.AutoGrid
import az.spendly.ui.components.Micro
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors

/**
 * The account, and what it holds.
 *
 * The user id is shown deliberately, and can be copied. It is the one piece of
 * plumbing a user ever needs: every row is scoped to it, so restoring records
 * that belong to an older identity is impossible without being able to read
 * it. Hiding it would mean nobody could recover their own data.
 */
@Composable
fun ProfileDialog(
    data: FinanceData,
    /** Null in local-storage mode, where there is nobody signed in. */
    user: AccountUser?,
    sync: SyncState,
    onSync: () -> Unit,
    /** Null in local-storage mode, where there is no password to change. */
    onChangePassword: ((String, String) -> Unit)?,
    /** What the last attempt said: a confirmation, or why it was refused. */
    passwordNotice: String? = null,
    passwordFailure: String? = null,
    busy: Boolean = false,
    onSignOut: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = spendlyColors
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    val months = knownMonths(data, "").filter { it.isNotEmpty() }.sorted()
    val expenses = data.transactions.count { it.type == TransactionType.EXPENSE }
    val income = data.transactions.size - expenses

    DialogShell(
        title = "Profil",
        subtitle = if (user != null) "Hesab məlumatları" else "Bu cihazda saxlanılır",
        onDismiss = onDismiss,
        footer = {
            if (onSignOut != null) {
                ConfirmingDeleteButton("Çıxış", "Çıxışı təsdiqlə") {
                    onSignOut()
                    onDismiss()
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Bağla") }
        },
    ) {
        if (user != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (user.email ?: "?").take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.accent,
                    )
                }
                Column {
                    Text(
                        text = user.email ?: "e-poçt yoxdur",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.text,
                    )
                    user.createdAt?.let { created ->
                        Text(
                            text = "Hesab yaradılıb: ${created.take(10)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(colors.surfaceInset)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Micro("İstifadəçi ID")
                Text(
                    text = user.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                )
                Text(
                    text = "Bütün qeydləriniz bu ID-yə bağlıdır. Məlumat bərpası lazım olsa, " +
                        "bu lazım olacaq.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
                TextButton(
                    onClick = {
                        copyToClipboard(context, user.id)
                        copied = true
                    },
                ) {
                    Text(if (copied) "Kopyalandı" else "ID-ni kopyala")
                }
            }
        } else {
            Text(
                text = "Hesab yoxdur — məlumatlar yalnız bu cihazda saxlanılır. Tətbiqi " +
                    "silsəniz, onlar da silinəcək.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        }

        if (user != null) {
            // Where this device stands against the account, and the way to
            // push whatever is waiting without leaving the screen.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(colors.surfaceInset)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Micro("Sinxronizasiya")
                    Text(
                        text = when (sync.status) {
                            SyncStatus.SYNCED -> "Hər şey hesabda saxlanılıb"
                            SyncStatus.PENDING -> "Cihazda gözləyən dəyişiklik var"
                            SyncStatus.OFFLINE -> "Oflayn — serverə çıxış yoxdur"
                            SyncStatus.FAILED -> sync.message?.takeIf { it.isNotBlank() }
                                ?: "Son dəyişiklik göndərilmədi"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (sync.status) {
                            SyncStatus.SYNCED -> colors.positive
                            SyncStatus.FAILED -> colors.negative
                            else -> colors.textMuted
                        },
                    )
                }
                if (sync.status != SyncStatus.SYNCED) {
                    TextButton(onClick = onSync) {
                        Text("İndi göndər", maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        if (onChangePassword != null) {
            PasswordChange(
                onSubmit = onChangePassword,
                notice = passwordNotice,
                failure = passwordFailure,
                busy = busy,
            )
        }

        AutoGrid(
            minCellWidth = 124.dp,
            cells = listOf(
                { Stat("Əməliyyat", data.transactions.size.toString()) },
                { Stat("Xərc / gəlir", "$expenses / $income") },
                { Stat("Kateqoriya", data.categories.size.toString()) },
                { Stat("Balans", formatAZN(totalHoldings(data))) },
            ),
        )

        if (months.isNotEmpty()) {
            Text(
                text = (
                    if (months.size == 1) {
                        "Əhatə olunan ay: ${formatMonth(months.first())}"
                    } else {
                        "Əhatə olunan aylar: ${formatMonth(months.first())} — " +
                            "${formatMonth(months.last())} (${months.size} ay, " +
                            "planlaşdırılan aylar daxil)"
                    }
                    ) + " · ${categoriesOfType(data, TransactionType.EXPENSE).size} xərc, " +
                    "${categoriesOfType(data, TransactionType.INCOME).size} gəlir kateqoriyası",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textFaint,
            )
        }
    }
}

/**
 * Changing the password, without leaving the account screen.
 *
 * Closed by default: it is a thing you occasionally need, not a thing you came
 * here to look at, and three password fields sitting open in a dialog about an
 * account read as though something were wrong with it.
 */
@Composable
private fun PasswordChange(
    onSubmit: (String, String) -> Unit,
    notice: String?,
    failure: String?,
    busy: Boolean,
) {
    val colors = spendlyColors
    var open by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(PasswordChangeInput()) }
    var showErrors by remember { mutableStateOf(false) }

    val errors = validatePasswordChange(input)
    val visible = if (showErrors) errors else PasswordChangeErrors()

    // A confirmation from the store means the change went through; the form
    // has nothing left to show.
    LaunchedEffect(notice) {
        if (notice != null) {
            open = false
            input = PasswordChangeInput()
            showErrors = false
        }
    }

    if (!open) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(colors.surfaceInset)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Micro("Şifrə")
                Text(
                    text = notice ?: "Hesabınızın şifrəsini dəyişin",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notice != null) colors.positive else colors.textMuted,
                )
            }
            TextButton(onClick = { open = true }) {
                Text("Dəyiş", maxLines = 1, softWrap = false)
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.surfaceInset)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Micro("Şifrəni dəyiş")

        PasswordField(
            label = "Cari şifrə",
            value = input.current,
            onValueChange = { input = input.copy(current = it) },
            error = visible.current,
        )
        PasswordField(
            label = "Yeni şifrə",
            value = input.next,
            onValueChange = { input = input.copy(next = it) },
            error = visible.next,
            hint = "Ən azı $MIN_PASSWORD_LENGTH simvol.",
        )
        PasswordField(
            label = "Yeni şifrə (təkrar)",
            value = input.repeat,
            onValueChange = { input = input.copy(repeat = it) },
            error = visible.repeat,
        )

        if (failure != null) {
            Text(
                text = failure,
                style = MaterialTheme.typography.bodySmall,
                color = colors.negative,
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = {
                    open = false
                    showErrors = false
                },
            ) { Text("Ləğv et") }
            Button(
                enabled = !busy,
                onClick = {
                    if (errors.any) {
                        showErrors = true
                    } else {
                        onSubmit(input.current, input.next)
                    }
                },
            ) { Text(if (busy) "Gözləyin…" else "Şifrəni dəyiş") }
        }
    }
}

@Composable
private fun PasswordField(
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

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = spendlyColors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(colors.surfaceInset)
            .padding(12.dp),
    ) {
        Micro(label)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = colors.text,
        )
    }
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Spendly ID", value))
}
