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
import az.spendly.domain.moveCategoryReferences
import az.spendly.domain.movePotReferences

fun mergeFinanceData(
    base: FinanceData,
    local: FinanceData,
    remote: FinanceData,
): FinanceData {
    val merged = FinanceData(
        transactions = mergeRows(base.transactions, local.transactions, remote.transactions) { it.id },
        budgetLines = mergeRows(base.budgetLines, local.budgetLines, remote.budgetLines) { it.id },
        incomePlans = mergeRows(base.incomePlans, local.incomePlans, remote.incomePlans) { it.month },
        categories = mergeRows(base.categories, local.categories, remote.categories) { it.id },
        savingsPots = mergeRows(base.savingsPots, local.savingsPots, remote.savingsPots) { it.id },
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

    return dedupePots(dedupeCategories(merged))
}

/** Two ids, one pot — the same collision categories have, for the same reason:
 *  a pot is unique by name on the server, and every entry names its pot. */
private fun dedupePots(data: FinanceData): FinanceData {
    val seen = mutableMapOf<String, SavingsPot>()
    val kept = mutableListOf<SavingsPot>()
    val moves = mutableListOf<Pair<String, String>>()

    for (pot in data.savingsPots) {
        val survivor = seen[pot.name.trim().lowercase()]
        when {
            survivor == null -> {
                seen[pot.name.trim().lowercase()] = pot
                kept += pot
            }
            survivor.name != pot.name -> moves += pot.name to survivor.name
        }
    }

    var next = data.copy(savingsPots = kept)
    for ((from, to) in moves) next = movePotReferences(next, from, to)
    return next
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
 * So a duplicate by name is resolved in favour of the server's row — and every
 * row that named the one being dropped is moved onto the survivor. Dropping
 * the definition alone was not enough: the app matches a category by name and
 * the two spellings differ only in case, so the transactions left behind named
 * a category the picker no longer offered, and editing one of them asked for a
 * category that could not be chosen.
 */
private fun dedupeCategories(data: FinanceData): FinanceData {
    val seen = mutableMapOf<Pair<TransactionType, String>, CategoryDef>()
    val kept = mutableListOf<CategoryDef>()
    val moves = mutableListOf<Triple<String, String, TransactionType>>()

    // Merged order is the server's first, so the surviving id is the
    // server's — the one every other device already agrees on.
    for (category in data.categories) {
        val key = category.type to category.name.trim().lowercase()
        val survivor = seen[key]
        when {
            survivor == null -> {
                seen[key] = category
                kept += category
            }
            survivor.name != category.name ->
                moves += Triple(category.name, survivor.name, category.type)
        }
    }

    var next = data.copy(categories = kept)
    for ((from, to, type) in moves) next = moveCategoryReferences(next, from, to, type)
    return next
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
