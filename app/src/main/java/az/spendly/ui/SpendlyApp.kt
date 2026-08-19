/**
 * The shell: which screen is showing, which month everything is read against,
 * and the one dialog that can be opened from anywhere.
 */
package az.spendly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import az.spendly.data.setupHint
import az.spendly.domain.MonthKey
import az.spendly.domain.Transaction
import az.spendly.domain.currentMonth
import az.spendly.domain.formatMonth
import az.spendly.domain.knownMonths
import az.spendly.domain.monthOf
import az.spendly.domain.shiftMonth
import az.spendly.domain.today
import az.spendly.store.FinanceState
import az.spendly.store.FinanceViewModel
import az.spendly.store.LoadStatus
import az.spendly.ui.dialogs.TransactionDialog
import az.spendly.ui.screens.BudgetScreen
import az.spendly.ui.screens.DashboardScreen
import az.spendly.ui.screens.TransactionsScreen
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors

private enum class Screen(val label: String) {
    DASHBOARD("İcmal"),
    TRANSACTIONS("Əməliyyatlar"),
    BUDGET("Büdcə"),
}

@Composable
fun SpendlyApp(
    state: FinanceState,
    viewModel: FinanceViewModel,
    /** Null in local-storage mode, where there is nobody signed in. */
    onSignOut: (() -> Unit)? = null,
) {
    val colors = spendlyColors

    if (state.status != LoadStatus.READY) {
        Gate(state = state, onRetry = viewModel::retry)
        return
    }

    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    var month by remember { mutableStateOf(currentMonth()) }
    /** null = closed, a transaction = editing it, NEW_TRANSACTION = adding. */
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var adding by remember { mutableStateOf(false) }

    val months = knownMonths(state.data, currentMonth())

    /** New transactions default to today, or to the 1st of a non-current month. */
    val defaultDate = if (month == currentMonth()) today() else "$month-01"

    Scaffold(
        containerColor = colors.background,
        topBar = {
            Column {
                TopBar(
                    month = month,
                    months = months,
                    onMonthChange = { month = it },
                    onSignOut = onSignOut,
                )
                if (state.saveError != null) {
                    SaveBanner(
                        message = state.saveError,
                        onDismiss = viewModel::dismissSaveError,
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = colors.surface) {
                Screen.entries.forEach { entry ->
                    // The name is the whole item: three text tabs, the way the
                    // web app's header reads, so the indicator wraps the word
                    // rather than an empty icon slot above it.
                    NavigationBarItem(
                        selected = screen == entry,
                        onClick = { screen = entry },
                        icon = {
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (screen == entry) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.accent,
                            unselectedIconColor = colors.textMuted,
                            indicatorColor = colors.accentSoft,
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { adding = true },
                containerColor = colors.accent,
                contentColor = colors.onAccent,
            ) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (screen) {
                Screen.DASHBOARD -> DashboardScreen(
                    data = state.data,
                    month = month,
                    onSelectTransaction = { editing = it },
                    onAdd = { adding = true },
                )

                Screen.TRANSACTIONS -> TransactionsScreen(
                    data = state.data,
                    month = month,
                    onSelect = { editing = it },
                    onAdd = { adding = true },
                )

                Screen.BUDGET -> BudgetScreen(
                    data = state.data,
                    month = month,
                    onApplyTemplate = viewModel::applyTemplate,
                    onUpsertLine = viewModel::upsertBudgetLine,
                    onRemoveLine = viewModel::removeBudgetLine,
                    onSetIncomePlan = viewModel::setIncomePlan,
                    onClearMonthPlan = viewModel::clearMonthPlan,
                    onResetAll = viewModel::resetAll,
                    onAddCategory = viewModel::addCategory,
                    onRenameCategory = viewModel::renameCategory,
                    onRemoveCategory = viewModel::removeCategory,
                )
            }
        }
    }

    if (adding || editing != null) {
        val target = editing
        TransactionDialog(
            data = state.data,
            transaction = target,
            defaultDate = defaultDate,
            onSave = { values ->
                if (target == null) {
                    viewModel.addTransaction(values)
                } else {
                    viewModel.updateTransaction(target.id, values)
                }
                // Follow the money: if it landed in another month, switch to it
                // so the effect of what was just saved is on screen.
                month = monthOf(values.date)
                adding = false
                editing = null
            },
            onDelete = target?.let {
                {
                    viewModel.removeTransaction(it.id)
                    editing = null
                }
            },
            onDismiss = {
                adding = false
                editing = null
            },
        )
    }
}

@Composable
private fun TopBar(
    month: MonthKey,
    months: List<MonthKey>,
    onMonthChange: (MonthKey) -> Unit,
    onSignOut: (() -> Unit)?,
) {
    val colors = spendlyColors
    // The selected month is always present, and one month either side is
    // offered too, so a new month can be reached without leaving the control.
    val options = (listOf(month) + months + shiftMonth(month, -1) + shiftMonth(month, 1))
        .distinct()
        .sortedDescending()
    var open by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "S",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onAccent,
                )
            }
            Text(
                text = "Spendly",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )
            if (onSignOut != null) {
                TextButton(onClick = onSignOut) {
                    Text(
                        text = "Çıxış",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Chevron("‹") { onMonthChange(shiftMonth(month, -1)) }
            Box {
                Text(
                    text = formatMonth(month),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.xs))
                        .clickable { open = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(formatMonth(option)) },
                            onClick = {
                                onMonthChange(option)
                                open = false
                            },
                        )
                    }
                }
            }
            Chevron("›") { onMonthChange(shiftMonth(month, 1)) }
        }
    }
}

@Composable
private fun Chevron(glyph: String, onClick: () -> Unit) {
    val colors = spendlyColors
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = MaterialTheme.typography.titleMedium, color = colors.textMuted)
    }
}

/**
 * The last write did not reach the backend. The edit is still on screen — it
 * is in local state — but it is not saved, and saying nothing would let it
 * read as though it were.
 */
@Composable
private fun SaveBanner(message: String, onDismiss: () -> Unit) {
    val colors = spendlyColors
    val hint = setupHint(message)
    // The headline already says the write failed. Repeating a generic message
    // underneath it says it twice and adds nothing.
    val guidance = hint ?: message.trim().ifBlank { null }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.negativeSoft)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Dəyişiklik yadda saxlanılmadı." +
                    (guidance?.let { " $it" } ?: "") +
                    " Tətbiqi bağlasanız, son dəyişiklik itəcək.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Bağla", color = colors.negative) }
        }
        // The raw error, when a hint stood in for it.
        if (hint != null && message.isNotBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

/** Shown while the first load is in flight, or when it failed. */
@Composable
private fun Gate(state: FinanceState, onRetry: () -> Unit) {
    val colors = spendlyColors
    val hint = setupHint(state.error)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "S",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onAccent,
                )
            }
            Text(
                text = "Spendly",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
            )

            if (state.status == LoadStatus.LOADING) {
                Text(
                    text = "Məlumatlarınız yüklənir…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textMuted,
                )
            } else {
                Text(
                    text = hint ?: state.error ?: "Məlumatlarınızı yükləmək mümkün olmadı.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text,
                )
                if (hint != null && state.error != null) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                Button(onClick = onRetry) { Text("Yenidən cəhd et") }
            }
        }
    }
}
