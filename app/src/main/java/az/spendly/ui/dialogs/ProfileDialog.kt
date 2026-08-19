package az.spendly.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.spendly.data.AccountUser
import az.spendly.domain.FinanceData
import az.spendly.domain.TransactionType
import az.spendly.domain.categoriesOfType
import az.spendly.domain.formatAZN
import az.spendly.domain.formatMonth
import az.spendly.domain.knownMonths
import az.spendly.domain.runningBalance
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

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Əməliyyat", data.transactions.size.toString(), Modifier.weight(1f))
            Stat("Xərc / gəlir", "$expenses / $income", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Kateqoriya", data.categories.size.toString(), Modifier.weight(1f))
            Stat(
                "Balans",
                formatAZN(runningBalance(data.transactions)),
                Modifier.weight(1f),
            )
        }

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
