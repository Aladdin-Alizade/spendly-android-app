/**
 * The store.
 *
 * Local state updates immediately and the write follows, so editing never
 * waits on the network. A failed write is reported rather than rolled back
 * mid-edit — the edit is still on screen, and saying nothing would let it read
 * as though it were saved.
 */
package az.spendly.store

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import az.spendly.data.FinanceRepository
import az.spendly.data.LocalRepository
import az.spendly.data.NetworkMonitor
import az.spendly.data.SupabaseConfig
import az.spendly.data.SupabaseRepository
import az.spendly.data.SupabaseSession
import az.spendly.data.SyncState
import az.spendly.data.SyncStatus
import az.spendly.data.SyncingRepository
import az.spendly.data.describeError
import az.spendly.domain.BudgetLine
import az.spendly.domain.CategoryDef
import az.spendly.domain.CategoryKind
import az.spendly.domain.FinanceData
import az.spendly.domain.IncomePlan
import az.spendly.domain.MonthKey
import az.spendly.domain.SavingsEntry
import az.spendly.domain.SavingsPlan
import az.spendly.domain.SavingsPot
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import az.spendly.domain.addCategory as addCategoryTo
import az.spendly.domain.addPot as addPotTo
import az.spendly.domain.convertSavingTransactions
import az.spendly.domain.emptyData
import az.spendly.domain.removeCategory as removeCategoryFrom
import az.spendly.domain.removePot as removePotFrom
import az.spendly.domain.renameCategory as renameCategoryIn
import az.spendly.domain.renamePot as renamePotIn
import az.spendly.domain.setPotTarget as setPotTargetIn
import az.spendly.domain.round2
import az.spendly.domain.setCategoryKind as setCategoryKindIn
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LoadStatus { LOADING, READY, ERROR }

data class FinanceState(
    val data: FinanceData = emptyData,
    val status: LoadStatus = LoadStatus.LOADING,
    /** Set when loading failed, so the UI can say what went wrong. */
    val error: String? = null,
    /**
     * Where this device stands against the server. Local-only mode has nothing
     * to sync with, so it stays [SyncStatus.SYNCED].
     */
    val sync: SyncState = SyncState(),
    /** The user closed the current sync message; a new one shows again. */
    val syncMessageDismissed: Boolean = false,
)

class FinanceViewModel(
    private val repository: FinanceRepository,
    /** Absent in local-only mode, where there is nothing to reach. */
    private val network: NetworkMonitor? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(FinanceState())
    val state: StateFlow<FinanceState> = _state.asStateFlow()

    private val syncing = repository as? SyncingRepository

    init {
        load()
        observeSync()
        observeNetwork()
    }

    /** The repository reports where it stands; the UI reads it from here. */
    private fun observeSync() {
        val source = syncing ?: return
        viewModelScope.launch {
            source.state.collect { sync ->
                _state.value = _state.value.copy(sync = sync, syncMessageDismissed = false)
            }
        }
    }

    /**
     * A network appearing is the moment queued work can go out. Nothing else
     * triggers a retry, because retrying on a timer would keep failing at
     * exactly the same rate as the thing that is not there.
     *
     * A device that is already in step with the server has nothing to say to
     * it, so a new network is not a reason to talk — which is also what keeps
     * the networks present at startup from re-running the load that has just
     * happened.
     */
    private fun observeNetwork() {
        val monitor = network ?: return
        val source = syncing ?: return
        viewModelScope.launch {
            monitor.available.collect {
                if (source.state.value.status == SyncStatus.SYNCED) return@collect
                val merged = runCatching { source.sync() }.getOrNull()
                if (merged != null) _state.value = _state.value.copy(data = merged)
            }
        }
    }

    /** Send whatever is queued, now. */
    fun syncNow() {
        val source = syncing ?: return
        viewModelScope.launch {
            val merged = runCatching { source.sync() }.getOrNull()
            if (merged != null) _state.value = _state.value.copy(data = merged)
        }
    }

    fun retry() = load()

    private fun load() {
        _state.value = _state.value.copy(status = LoadStatus.LOADING, error = null)
        viewModelScope.launch {
            try {
                val loaded = repository.load()
                _state.value = _state.value.copy(
                    data = loaded,
                    status = LoadStatus.READY,
                    error = null,
                )
            } catch (cause: Exception) {
                _state.value = _state.value.copy(
                    status = LoadStatus.ERROR,
                    error = describeError(cause).ifBlank {
                        "Məlumatlarınızı yükləmək mümkün olmadı"
                    },
                )
            }
        }
    }

    fun dismissSyncMessage() {
        _state.value = _state.value.copy(syncMessageDismissed = true)
    }

    private fun commit(update: (FinanceData) -> FinanceData) {
        val next = update(_state.value.data)
        _state.value = _state.value.copy(data = next)
        viewModelScope.launch {
            try {
                repository.save(next)
            } catch (cause: Exception) {
                // The syncing repository reports through its own state; this
                // catches the local-only path, where a failure to write the
                // device's own file is the whole story.
                _state.value = _state.value.copy(
                    sync = SyncState(SyncStatus.FAILED, describeError(cause)),
                )
            }
        }
    }

    /* --- transactions ------------------------------------------------- */

    fun addTransaction(transaction: Transaction) = commit { previous ->
        previous.copy(
            transactions = previous.transactions +
                transaction.copy(id = nextId(), amount = round2(transaction.amount)),
        )
    }

    fun updateTransaction(id: String, patch: Transaction) = commit { previous ->
        previous.copy(
            transactions = previous.transactions.map { existing ->
                if (existing.id == id) patch.copy(id = id, amount = round2(patch.amount)) else existing
            },
        )
    }

    fun removeTransaction(id: String) = commit { previous ->
        previous.copy(transactions = previous.transactions.filter { it.id != id })
    }

    /* --- the plan ------------------------------------------------------ */

    fun upsertBudgetLine(line: BudgetLine, isNew: Boolean) = commit { previous ->
        val planned = round2(line.planned)
        if (!isNew) {
            previous.copy(
                budgetLines = previous.budgetLines.map { existing ->
                    if (existing.id == line.id) line.copy(planned = planned) else existing
                },
            )
        } else {
            previous.copy(
                budgetLines = previous.budgetLines + line.copy(id = nextId(), planned = planned),
            )
        }
    }

    fun removeBudgetLine(id: String) = commit { previous ->
        previous.copy(budgetLines = previous.budgetLines.filter { it.id != id })
    }

    /** Remove every planned line for one month, leaving its transactions alone. */
    fun clearMonthPlan(month: MonthKey) = commit { previous ->
        previous.copy(
            budgetLines = previous.budgetLines.filter { it.month != month },
            incomePlans = previous.incomePlans.filter { it.month != month },
            // The savings figure is part of the month's plan, so "delete the
            // plan" has to take it too; leaving it would resurrect a number
            // whose context is gone.
            savingsPlans = previous.savingsPlans.filter { it.month != month },
        )
    }

    fun setIncomePlan(month: MonthKey, amounts: Map<String, Double>) = commit { previous ->
        val entry = IncomePlan(
            month = month,
            // A category planned at zero carries no information, so it is not
            // stored — an absent key and a zero mean the same thing.
            amounts = amounts
                .mapValues { (_, amount) -> round2(amount) }
                .filterValues { it > 0 },
        )
        val exists = previous.incomePlans.any { it.month == month }
        previous.copy(
            incomePlans = if (exists) {
                previous.incomePlans.map { if (it.month == month) entry else it }
            } else {
                previous.incomePlans + entry
            },
        )
    }

    fun setSavingsPlan(month: MonthKey, amounts: Map<String, Double>) = commit { previous ->
        val entry = SavingsPlan(
            month = month,
            // A pot planned at zero carries no information, so it is not
            // stored — an absent key and a zero mean the same thing.
            amounts = amounts
                .mapValues { (_, amount) -> round2(amount) }
                .filterValues { it > 0 },
        )
        val exists = previous.savingsPlans.any { it.month == month }
        previous.copy(
            savingsPlans = if (exists) {
                previous.savingsPlans.map { if (it.month == month) entry else it }
            } else {
                previous.savingsPlans + entry
            },
        )
    }

    /** Copy the recurring plan into a month that has none yet. */
    fun applyTemplate(month: MonthKey) = commit { previous ->
        if (previous.budgetLines.any { it.month == month }) return@commit previous

        // Carry the most recent month's plan forward. There is nothing else to
        // carry: an account holds only the plan its owner wrote, so with no
        // earlier month this does nothing rather than inventing one.
        val source = previous.budgetLines
            .map { it.month }
            .distinct()
            .filter { it < month }
            .maxOrNull() ?: return@commit previous

        val lines = previous.budgetLines
            .filter { it.month == source }
            .map { it.copy(id = nextId(), month = month) }

        val priorPlan = previous.incomePlans.firstOrNull { it.month == source }
        val priorSavings = previous.savingsPlans.firstOrNull { it.month == source }

        previous.copy(
            budgetLines = previous.budgetLines + lines,
            incomePlans = if (previous.incomePlans.any { it.month == month }) {
                previous.incomePlans
            } else {
                previous.incomePlans + IncomePlan(month, priorPlan?.amounts ?: emptyMap())
            },
            savingsPlans = if (previous.savingsPlans.any { it.month == month }) {
                previous.savingsPlans
            } else {
                previous.savingsPlans + SavingsPlan(month, priorSavings?.amounts ?: emptyMap())
            },
        )
    }

    /* --- categories ---------------------------------------------------- */

    fun addCategory(name: String, type: TransactionType, kind: CategoryKind? = null) =
        commit { previous -> addCategoryTo(previous, CategoryDef(nextId(), name, type, kind)) }

    /** Set or clear what a category is for. Touches no amount. */
    fun setCategoryKind(id: String, kind: CategoryKind?) = commit { previous ->
        setCategoryKindIn(previous, id, kind)
    }

    /** Renames the category and everything that referenced it, in one change. */
    fun renameCategory(id: String, name: String) = commit { previous ->
        renameCategoryIn(previous, id, name)
    }

    /** [reassignTo] is the category anything still using this one moves to. A
     *  category that is in use and has nowhere to go is left alone. */
    fun removeCategory(id: String, reassignTo: String? = null) = commit { previous ->
        removeCategoryFrom(previous, id, reassignTo)
    }

    /* --- savings ------------------------------------------------------- */

    fun addSavingsPot(name: String, target: Double? = null) = commit { previous ->
        addPotTo(
            previous,
            SavingsPot(
                id = nextId(),
                name = name,
                target = target?.takeIf { it > 0 }?.let { round2(it) },
            ),
        )
    }

    /** Renames the pot and every entry that named it, in one change. */
    fun renameSavingsPot(id: String, name: String) = commit { previous ->
        renamePotIn(previous, id, name)
    }

    /** Set or clear what the pot is being filled towards. Moves no money. */
    fun setSavingsPotTarget(id: String, target: Double?) = commit { previous ->
        setPotTargetIn(previous, id, target)
    }

    /** [reassignTo] is the pot anything still in this one moves to. A pot that
     *  still holds entries and has nowhere to send them is left alone. */
    fun removeSavingsPot(id: String, reassignTo: String? = null) = commit { previous ->
        removePotFrom(previous, id, reassignTo)
    }

    fun addSavingsEntry(entry: SavingsEntry) = commit { previous ->
        previous.copy(
            savingsEntries = previous.savingsEntries +
                entry.copy(id = nextId(), amount = round2(entry.amount)),
        )
    }

    fun updateSavingsEntry(id: String, patch: SavingsEntry) = commit { previous ->
        previous.copy(
            savingsEntries = previous.savingsEntries.map { existing ->
                if (existing.id == id) {
                    patch.copy(id = id, amount = round2(patch.amount))
                } else {
                    existing
                }
            },
        )
    }

    fun removeSavingsEntry(id: String) = commit { previous ->
        previous.copy(savingsEntries = previous.savingsEntries.filter { it.id != id })
    }

    /** Turn savings recorded the old way — as spending into a category marked
     *  `saving` — into pot deposits. The expenses go, so nothing counts twice. */
    fun convertSavingsFromTransactions() = commit { previous ->
        convertSavingTransactions(previous) { nextId() }
    }

    /** Delete every transaction, plan and budget line. Not reversible. */
    fun resetAll() = commit { previous ->
        // The category list and the pots are the user's own setup, not their
        // history, so a reset of the figures leaves them standing.
        FinanceData(categories = previous.categories, savingsPots = previous.savingsPots)
    }

    /**
     * Ids are generated on the device so the UI can update before a write
     * lands, and are unique across devices sharing one account.
     */
    private fun nextId(): String = UUID.randomUUID().toString()

    companion object {
        /**
         * Supabase when a project is configured, the device's own file when it
         * is not — the same fallback the web app made on its env vars.
         */
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (!SupabaseConfig.isConfigured) {
                        return FinanceViewModel(LocalRepository(application)) as T
                    }

                    // The same session the sign-in screen uses, so signing
                    // out empties the tokens this store reads too. The device's
                    // own snapshot is still the working copy — the account
                    // decides where the data belongs, not whether it is saved.
                    val session = SupabaseSession.get(application)
                    val owner = session.userId
                    // Bound to the account it was built for. A store keyed by
                    // user stays in the activity's store after that user signs
                    // out, and a network appearing still reaches it; naming its
                    // owner is what stops it writing into the account that
                    // signed in after.
                    val remote = SupabaseRepository(session, owner)
                    return FinanceViewModel(
                        // The snapshots belong to the account, not to the
                        // install: one file per account is what stops one
                        // account's rows being handed to the next one to sign
                        // in here and uploaded as its own.
                        repository = SyncingRepository(application, remote, owner),
                        network = NetworkMonitor(application),
                    ) as T
                }
            }
    }
}
