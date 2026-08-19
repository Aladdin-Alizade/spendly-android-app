/**
 * Category management.
 *
 * Categories are referenced by name everywhere the money is — that is how the
 * spreadsheet worked and how every stored row already reads. So a rename is
 * not an edit to one record: it has to carry every transaction and every
 * budget line that names the old category across with it, in the same change,
 * or the history falls out of its own totals.
 *
 * All of this is pure so the rule can be tested without a store or a device.
 */
package az.spendly.domain

data class CategoryUsage(
    val transactions: Int,
    val budgetLines: Int,
    /** Months with a planned income figure under this category. */
    val incomePlans: Int,
) {
    val inUse: Boolean get() = transactions > 0 || budgetLines > 0 || incomePlans > 0
}

/** How much history depends on a category, which is what makes deleting it
 *  a decision rather than a click. */
fun categoryUsage(data: FinanceData, name: String): CategoryUsage = CategoryUsage(
    transactions = data.transactions.count { it.category == name },
    budgetLines = data.budgetLines.count { it.category == name },
    // A category with a planned income figure and no transactions yet is still
    // in use: dropping it would delete the plan without saying so.
    incomePlans = data.incomePlans.count { (it.amounts[name] ?: 0.0) > 0 },
)

/**
 * The categories a snapshot's own rows name.
 *
 * Recovery, not invention. A snapshot saved before categories were records of
 * their own carries transactions, budget lines and planned figures but no
 * list, and the names it used are still there to be read back. A snapshot with
 * no rows names nothing, which is exactly what a new account should get.
 */
fun categoriesFromData(data: FinanceData): List<CategoryDef> {
    val names = mapOf(
        TransactionType.EXPENSE to mutableListOf<String>(),
        TransactionType.INCOME to mutableListOf<String>(),
    )

    fun add(type: TransactionType, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val bucket = names.getValue(type)
        if (bucket.none { it.equals(trimmed, ignoreCase = true) }) bucket.add(trimmed)
    }

    data.transactions.forEach { add(it.type, it.category) }
    // A budget line is always an expense; a planned income figure always income.
    data.budgetLines.forEach { add(TransactionType.EXPENSE, it.category) }
    data.incomePlans.forEach { plan ->
        plan.amounts.keys.forEach { add(TransactionType.INCOME, it) }
    }

    return listOf(TransactionType.EXPENSE, TransactionType.INCOME).flatMap { type ->
        names.getValue(type).mapIndexed { index, name ->
            CategoryDef(id = "${type.wire}-$index", name = name, type = type)
        }
    }
}

/** Categories of one side of the ledger, in the order they were added. */
fun categoriesOfType(data: FinanceData, type: TransactionType): List<CategoryDef> =
    data.categories.filter { it.type == type }

/** The names only, which is what a picker and the validator want. */
fun categoryNames(data: FinanceData, type: TransactionType): List<String> =
    categoriesOfType(data, type).map { it.name }

/**
 * A name has to be there, and has to be unique within its own side of the
 * ledger — an expense and an income category may share a name without
 * ambiguity, because nothing ever looks a category up without its type.
 * Returns the reason it is rejected, or null when it is fine.
 */
fun validateCategoryName(
    data: FinanceData,
    name: String,
    type: TransactionType,
    /** The category being edited, so a name does not clash with itself. */
    currentId: String? = null,
): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "Ad yazın"
    if (trimmed.length > 60) return "Bu ad həddindən artıq uzundur"

    val clash = data.categories.any { category ->
        category.type == type &&
            category.id != currentId &&
            category.name.trim().lowercase() == trimmed.lowercase()
    }

    return if (clash) "Belə kateqoriya artıq var" else null
}

data class PlannedIncomeRow(
    val category: String,
    val planned: Double,
    /** The plan holds a figure for a category that no longer exists. */
    val orphaned: Boolean,
)

/**
 * The planned-income lines for a month: one per income category, plus any
 * figure left behind by a category that has since gone.
 *
 * An orphan is shown rather than dropped. The alternative is a list of rows
 * that does not add up to its own total, which is how a planned amount goes
 * missing without anyone being told — and the figure is still editable here,
 * so it can be cleared or moved on purpose.
 */
fun plannedIncomeRows(
    categories: List<CategoryDef>,
    amounts: Map<String, Double>,
): List<PlannedIncomeRow> {
    val known = categories.map { it.name }.toSet()

    return categories.map { category ->
        PlannedIncomeRow(
            category = category.name,
            planned = amounts[category.name] ?: 0.0,
            orphaned = false,
        )
    } + amounts.entries
        .filter { (category, amount) -> !known.contains(category) && amount > 0 }
        .map { (category, planned) -> PlannedIncomeRow(category, planned, orphaned = true) }
}

fun addCategory(data: FinanceData, category: CategoryDef): FinanceData =
    data.copy(categories = data.categories + category.copy(name = category.name.trim()))

/**
 * Set or clear what a category is for.
 *
 * Only the definition moves. No transaction, budget line or planned figure is
 * touched, so classifying a category cannot change a single total the app
 * reports — it only decides which frameworks can read it.
 */
fun setCategoryKind(data: FinanceData, id: String, kind: CategoryKind?): FinanceData =
    data.copy(
        categories = data.categories.map { category ->
            if (category.id == id) category.copy(kind = kind) else category
        },
    )

/**
 * Rename the category and everything that names it, in one step. Nothing here
 * touches an amount, so every total the app reports is unchanged by a rename.
 */
fun renameCategory(data: FinanceData, id: String, name: String): FinanceData {
    val target = data.categories.firstOrNull { it.id == id }
    val trimmed = name.trim()
    if (target == null || trimmed.isEmpty() || target.name == trimmed) return data

    val moved = applyRename(data, target.name, trimmed, target.type)
    return moved.copy(
        categories = data.categories.map { category ->
            if (category.id == id) category.copy(name = trimmed) else category
        },
    )
}

/**
 * Remove a category, moving anything that used it to [reassignTo] first.
 *
 * Without a destination, a category still in use is left alone: dropping it
 * would leave transactions pointing at a category that no longer exists, and
 * silently deleting the money behind it would be worse still.
 */
fun removeCategory(data: FinanceData, id: String, reassignTo: String? = null): FinanceData {
    val target = data.categories.firstOrNull { it.id == id } ?: return data

    val moved = if (reassignTo != null) {
        applyRename(data, target.name, reassignTo, target.type)
    } else {
        data
    }

    if (reassignTo == null && categoryUsage(data, target.name).inUse) return data

    return moved.copy(categories = moved.categories.filter { it.id != id })
}

/**
 * Carry every reference across.
 *
 * Each side of the ledger is named in different places: an expense category by
 * transactions and by budget lines, an income category by transactions and by
 * the planned-income figures. Missing one of these is how a rename quietly
 * drops a planned amount, so all of them move together.
 */
private fun applyRename(
    data: FinanceData,
    from: String,
    to: String,
    type: TransactionType,
): FinanceData = data.copy(
    transactions = data.transactions.map { transaction ->
        if (transaction.type == type && transaction.category == from) {
            transaction.copy(category = to)
        } else {
            transaction
        }
    },
    budgetLines = if (type == TransactionType.EXPENSE) {
        data.budgetLines.map { line ->
            if (line.category == from) line.copy(category = to) else line
        }
    } else {
        data.budgetLines
    },
    incomePlans = if (type == TransactionType.INCOME) {
        data.incomePlans.map { renameKey(it, from, to) }
    } else {
        data.incomePlans
    },
)

/** Move a planned figure onto the new name, adding to whatever is already
 *  planned there — a rename onto an existing category merges the two. */
private fun renameKey(plan: IncomePlan, from: String, to: String): IncomePlan {
    val moved = plan.amounts[from] ?: return plan
    val rest = plan.amounts.filterKeys { it != from }
    return plan.copy(amounts = rest + (to to (rest[to] ?: 0.0) + moved))
}
