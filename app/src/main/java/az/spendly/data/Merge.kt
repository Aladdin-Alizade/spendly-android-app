/**
 * Bringing a device's unsent work together with what the server holds.
 *
 * The rule, in one sentence: **rows this device changed while it could not
 * reach the server win; every other row comes from the server.**
 *
 * That is last-writer-wins at row granularity, biased towards the device with
 * unsent work — which is the only rule that can be applied without storing a
 * timestamp on every row, and the only one that never silently discards
 * something the person typed on this phone. Two devices editing the same
 * transaction while both offline is the case it cannot resolve; the one that
 * syncs second wins, and nothing is lost that was not deliberately replaced.
 *
 * `base` is the last snapshot known to have been on the server. Without it
 * there is no way to tell a row this device deleted from a row the server has
 * not seen yet, which is why it is stored rather than recomputed.
 */
package az.spendly.data

import az.spendly.domain.CategoryDef
import az.spendly.domain.FinanceData
import az.spendly.domain.SavingsPot
import az.spendly.domain.TransactionType

fun mergeFinanceData(base: FinanceData, local: FinanceData, remote: FinanceData) = FinanceData(
    transactions = mergeRows(base.transactions, local.transactions, remote.transactions) { it.id },
    budgetLines = mergeRows(base.budgetLines, local.budgetLines, remote.budgetLines) { it.id },
    incomePlans = mergeRows(base.incomePlans, local.incomePlans, remote.incomePlans) { it.month },
    categories = dedupeCategories(
        mergeRows(base.categories, local.categories, remote.categories) { it.id },
    ),
    savingsPots = dedupePots(
        mergeRows(base.savingsPots, local.savingsPots, remote.savingsPots) { it.id },
    ),
    savingsEntries = mergeRows(
        base.savingsEntries,
        local.savingsEntries,
        remote.savingsEntries,
    ) { it.id },
    savingsPlans = mergeRows(
        base.savingsPlans,
        local.savingsPlans,
        remote.savingsPlans,
    ) { it.month },
)

/** Two ids, one pot — the same collision categories have, for the same reason:
 *  a pot is unique by name on the server, and every entry names its pot. */
private fun dedupePots(pots: List<SavingsPot>): List<SavingsPot> {
    val seen = mutableSetOf<String>()
    return pots.filter { seen.add(it.name.trim().lowercase()) }
}

/**
 * Two ids, one category.
 *
 * A device that has never synced holds the categories it was given here, under
 * ids of its own making. An account that was used elsewhere first holds
 * the same names under different ids. Merging by id alone keeps both, and the
 * server rejects the pair outright — a category is unique per (user, type,
 * name) there, which is the rule that makes a rename possible at all.
 *
 * So a duplicate by name is resolved in favour of the server's row. Nothing is
 * lost by dropping the local one: every transaction, budget line and planned
 * figure refers to a category by name, never by id.
 */
private fun dedupeCategories(categories: List<CategoryDef>): List<CategoryDef> {
    val seen = mutableSetOf<Pair<TransactionType, String>>()
    // Merged order is the server's first, so the surviving id is the
    // server's — the one every other device already agrees on.
    return categories.filter { seen.add(it.type to it.name.trim().lowercase()) }
}

/**
 * One collection.
 *
 * Order follows the server's, with anything this device added appended — a
 * merge should not reshuffle a list the user has not touched.
 */
internal fun <T> mergeRows(
    base: List<T>,
    local: List<T>,
    remote: List<T>,
    idOf: (T) -> String,
): List<T> {
    val baseById = base.associateBy(idOf)
    val localById = local.associateBy(idOf)

    // What this device did since the last sync.
    val changedHere = localById.filter { (id, row) -> baseById[id] != row }
    val deletedHere = baseById.keys - localById.keys

    val merged = LinkedHashMap<String, T>()
    for (row in remote) {
        val id = idOf(row)
        if (id in deletedHere) continue
        merged[id] = changedHere[id] ?: row
    }
    // Rows added here that the server has never seen keep their place at the end.
    for ((id, row) in changedHere) {
        if (id !in merged) merged[id] = row
    }

    return merged.values.toList()
}

/** True when the device holds work the server has not acknowledged. */
fun hasPendingWork(base: FinanceData, local: FinanceData): Boolean = local != base
