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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import az.spendly.data.AccountUser
import az.spendly.data.SyncState
import az.spendly.data.SyncStatus
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
import az.spendly.ui.dialogs.ProfileDialog
import az.spendly.ui.dialogs.SavingsEntryDialog
import az.spendly.ui.dialogs.SavingsPotDialog
import az.spendly.ui.dialogs.TransactionDialog
import az.spendly.ui.screens.AdviceScreen
import az.spendly.ui.screens.BudgetScreen
import az.spendly.ui.screens.DashboardScreen
import az.spendly.ui.screens.SavingsScreen
import az.spendly.ui.screens.TransactionsScreen
import az.spendly.ui.theme.Radius
import az.spendly.ui.theme.spendlyColors

private enum class Screen(val label: String) {
    DASHBOARD("İcmal"),
    TRANSACTIONS("Qeyd"),
    SAVINGS("Yığım"),
    ADVICE("Məsləhət"),
    BUDGET("Büdcə"),
}

@Composable
fun SpendlyApp(
    state: FinanceState,
    viewModel: FinanceViewModel,
    /** Who is signed in, for the profile. Null in local-storage mode. */
    user: AccountUser? = null,
    /** Null in local-storage mode, where there is no password to change. */
    onChangePassword: ((String, String) -> Unit)? = null,
    passwordNotice: String? = null,
    passwordFailure: String? = null,
    passwordBusy: Boolean = false,
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
    /** What the add button opens on Yığım: a movement, or the first pot. */
    var addingSavings by remember { mutableStateOf<SavingsAdd?>(null) }
    var profileOpen by remember { mutableStateOf(false) }

    // Returning to the app is the other moment queued work can go out; the
    // network callback covers the case where it happens while it is open.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.syncNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                    user = user,
                    onMonthChange = { month = it },
                    onProfile = { profileOpen = true },
                )
                if (!state.syncMessageDismissed) {
                    SyncBanner(
                        sync = state.sync,
                        onRetry = viewModel::syncNow,
                        onDismiss = viewModel::dismissSyncMessage,
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
                            /* Five words across a phone, and the longest of
                               them has to fit whatever text size the system is
                               set to. Held at one size it wrapped and spilled
                               out of the bar; the web app answers the same
                               squeeze on a narrow window by shrinking the tab
                               label, so this does too. */
                            BasicText(
                                text = entry.label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = if (screen == entry) {
                                        colors.accent
                                    } else {
                                        colors.textMuted
                                    },
                                    fontWeight = if (screen == entry) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                ),
                                maxLines = 1,
                                autoSize = TextAutoSize.StepBased(
                                    minFontSize = 9.sp,
                                    maxFontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                ),
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
                onClick = {
                    // The add button records whatever the screen is about. On
                    // Yığım that is a movement — and with no pot yet it is the
                    // pot itself, because a movement has nowhere to go until
                    // one exists.
                    if (screen != Screen.SAVINGS) {
                        adding = true
                    } else {
                        addingSavings = if (state.data.savingsPots.isNotEmpty()) {
                            SavingsAdd.ENTRY
                        } else {
                            SavingsAdd.POT
                        }
                    }
                },
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

                Screen.SAVINGS -> SavingsScreen(
                    data = state.data,
                    month = month,
                    defaultDate = defaultDate,
                    onAddPot = viewModel::addSavingsPot,
                    onRenamePot = viewModel::renameSavingsPot,
                    onSetPotTarget = viewModel::setSavingsPotTarget,
                    onRemovePot = viewModel::removeSavingsPot,
                    onAddEntry = viewModel::addSavingsEntry,
                    onUpdateEntry = viewModel::updateSavingsEntry,
                    onRemoveEntry = viewModel::removeSavingsEntry,
                    onConvertFromTransactions = viewModel::convertSavingsFromTransactions,
                )

                Screen.ADVICE -> AdviceScreen(data = state.data, month = month)

                Screen.BUDGET -> BudgetScreen(
                    data = state.data,
                    month = month,
                    onApplyTemplate = viewModel::applyTemplate,
                    onUpsertLine = viewModel::upsertBudgetLine,
                    onRemoveLine = viewModel::removeBudgetLine,
                    onSetIncomePlan = viewModel::setIncomePlan,
                    onSetSavingsPlan = viewModel::setSavingsPlan,
                    onClearMonthPlan = viewModel::clearMonthPlan,
                    onResetAll = viewModel::resetAll,
                    onAddCategory = viewModel::addCategory,
                    onRenameCategory = viewModel::renameCategory,
                    onSetCategoryKind = viewModel::setCategoryKind,
                    onRemoveCategory = viewModel::removeCategory,
                )
            }
        }
    }

    if (profileOpen) {
        ProfileDialog(
            data = state.data,
            user = user,
            sync = state.sync,
            onSync = viewModel::syncNow,
            onChangePassword = onChangePassword,
            passwordNotice = passwordNotice,
            passwordFailure = passwordFailure,
            busy = passwordBusy,
            onSignOut = onSignOut,
            onDismiss = { profileOpen = false },
        )
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

    when (addingSavings) {
        SavingsAdd.ENTRY -> SavingsEntryDialog(
            data = state.data,
            entry = null,
            defaultDate = defaultDate,
            defaultPot = null,
            onSave = { values ->
                viewModel.addSavingsEntry(values)
                month = monthOf(values.date)
                addingSavings = null
            },
            onDelete = null,
            onDismiss = { addingSavings = null },
        )

        SavingsAdd.POT -> SavingsPotDialog(
            data = state.data,
            pot = null,
            onAdd = viewModel::addSavingsPot,
            onRename = viewModel::renameSavingsPot,
            onSetTarget = viewModel::setSavingsPotTarget,
            onRemove = viewModel::removeSavingsPot,
            onDismiss = { addingSavings = null },
        )

        null -> Unit
    }
}

/** What the add button opens on Yığım. */
private enum class SavingsAdd { ENTRY, POT }

@Composable
private fun TopBar(
    month: MonthKey,
    months: List<MonthKey>,
    /** Null in local-storage mode, where there is nobody signed in. */
    user: AccountUser?,
    onMonthChange: (MonthKey) -> Unit,
    onProfile: () -> Unit,
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
        /* The month switcher and the account are what this bar is for, so they
           keep their width and the wordmark gives way — the mark still says
           whose app this is. The web app drops the wordmark outright on the
           narrowest windows for the same reason. */
        Row(
            modifier = Modifier.weight(1f, fill = false),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Chevron("‹") { onMonthChange(shiftMonth(month, -1)) }
            Box {
                Text(
                    text = formatMonth(month),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                    maxLines = 1,
                    softWrap = false,
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

            /*
             * The account lives behind this: who is signed in, what the
             * account holds, and the way out.
             *
             * Signed in it wears the account's initial, because a hamburger
             * reads as "menu" and nobody looks for a way out of their account
             * in a menu. With no account there is no initial to show, so it
             * falls back to a neutral mark.
             */
            val initial = user?.email?.take(1)?.uppercase()
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (initial != null) colors.accentSoft else colors.surfaceSunken)
                    .clickable(onClick = onProfile),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial ?: "☰",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (initial != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (initial != null) colors.accent else colors.textMuted,
                )
            }
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
private fun SyncBanner(sync: SyncState, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val colors = spendlyColors

    /*
     * Three different things, and they must not be said in the same voice.
     *
     * Queued work is not a failure — the edit is on the device and will go out
     * on its own — so it gets a quiet line and no alarm. A rejection from the
     * server is a failure, needs a person, and says which step fixes it.
     * Everything in order says nothing at all.
     */
    when (sync.status) {
        SyncStatus.SYNCED -> return

        SyncStatus.OFFLINE, SyncStatus.PENDING -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (sync.status == SyncStatus.PENDING) {
                    "Dəyişikliklər cihazda saxlanılıb, sinxronizasiya gözləyir."
                } else {
                    "Oflayn rejim — məlumatlar cihazdan oxunur."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("İndi göndər") }
        }

        SyncStatus.FAILED -> {
            val message = sync.message.orEmpty()
            val hint = setupHint(message)
            // The headline already says the write did not land. Repeating a
            // generic message underneath it says it twice and adds nothing.
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
                        text = "Server dəyişikliyi qəbul etmədi." +
                            (guidance?.let { " $it" } ?: "") +
                            " Dəyişiklik cihazda saxlanılıb.",
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
