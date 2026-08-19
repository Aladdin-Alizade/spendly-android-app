/**
 * The savings pots: what is in them, and what moving money changes.
 *
 * The rule the whole file follows: **a pot holds money that still exists.**
 * Setting money aside is not spending it, so a deposit never appears in a
 * spending total; taking it back out is not earning, so a withdrawal never
 * appears in income. What a deposit does change is which side of the line the
 * money sits on, and that is exactly what [spendableDelta] reports.
 *
 * Everything here is pure, so the arithmetic can be tested without a store,
 * a device or an account.
 */
package az.spendly.domain

/** Entries dated on or before the end of [month]. Null means all history. */
private fun upTo(entries: List<SavingsEntry>, month: MonthKey?): List<SavingsEntry> =
    if (month != null) entries.filter { monthOf(it.date) <= month } else entries

private fun signed(entry: SavingsEntry): Double =
    if (entry.direction == SavingsDirection.IN) entry.amount else -entry.amount

/** Everything in every pot, as of the end of [month]. */
fun savingsBalance(entries: List<SavingsEntry>, month: MonthKey? = null): Double =
    sumOf(upTo(entries, month).map(::signed))

/** One pot's balance, as of the end of [month]. */
fun potBalance(
    entries: List<SavingsEntry>,
    pot: String,
    month: MonthKey? = null,
): Double = sumOf(upTo(entries, month).filter { it.pot == pot }.map(::signed))

/**
 * What the savings pots did to the spendable side in one month.
 *
 * A deposit made out of income takes money off the spendable side; a
 * withdrawal puts it back. A deposit from outside was never spendable, so it
 * changes nothing here — it only grows the pot. This is the term that keeps
 * the balance on screen equal to the money actually available to spend.
 */
fun spendableDelta(entries: List<SavingsEntry>, month: MonthKey? = null): Double =
    spendableDeltaOf(upTo(entries, month))

/** The same sum over a list already narrowed to a window — a week of a chart,
 *  a month of a trend — where the caller has done the filtering. */
fun spendableDeltaOf(entries: List<SavingsEntry>): Double = sumOf(
    entries.map { entry ->
        when {
            entry.direction == SavingsDirection.OUT -> entry.amount
            entry.source == SavingsSource.EXTERNAL -> 0.0
            else -> -entry.amount
        }
    },
)

/**
 * Money that arrived from outside during one month and went straight to a pot.
 * It grows what the household holds without ever passing through income, which
 * is exactly why it needs its own figure — no income report will show it.
 */
fun depositedFromOutside(entries: List<SavingsEntry>, month: MonthKey): Double = sumOf(
    entries
        .filter {
            monthOf(it.date) == month &&
                it.direction == SavingsDirection.IN &&
                it.source == SavingsSource.EXTERNAL
        }
        .map { it.amount },
)

/** Set aside out of income during one month — the deliberate saving rate. */
fun depositedFromIncome(entries: List<SavingsEntry>, month: MonthKey): Double = sumOf(
    entries
        .filter {
            monthOf(it.date) == month &&
                it.direction == SavingsDirection.IN &&
                it.source != SavingsSource.EXTERNAL
        }
        .map { it.amount },
)

/** Entries dated inside one month, newest first. */
fun entriesInMonth(entries: List<SavingsEntry>, month: MonthKey): List<SavingsEntry> =
    entries.filter { monthOf(it.date) == month }.sortedByDescending { it.date }

data class PotRow(
    val pot: SavingsPot?,
    val name: String,
    val balance: Double,
    val target: Double?,
    /** 0..1 against the target, or null when the pot has none. */
    val progress: Double?,
    val entries: Int,
    /** True for money in a pot whose definition has gone. */
    val orphaned: Boolean,
)

/**
 * Every pot with what is in it, plus any balance left behind by a pot that was
 * deleted while it still held money.
 *
 * The orphan is shown rather than dropped, for the same reason an orphaned
 * planned-income figure is: a list that does not add up to its own total is
 * how money goes missing without anyone being told.
 */
fun potRows(data: FinanceData, month: MonthKey? = null): List<PotRow> {
    val known = data.savingsPots.map { it.name }.toSet()
    val visible = upTo(data.savingsEntries, month)

    val rows = data.savingsPots.map { pot ->
        val balance = potBalance(data.savingsEntries, pot.name, month)
        PotRow(
            pot = pot,
            name = pot.name,
            balance = balance,
            target = pot.target,
            progress = pot.target?.takeIf { it > 0 }?.let { balance / it },
            entries = visible.count { it.pot == pot.name },
            orphaned = false,
        )
    }

    val orphans = visible.map { it.pot }.filter { it !in known }.distinct()

    return rows + orphans.map { name ->
        PotRow(
            pot = null,
            name = name,
            balance = potBalance(data.savingsEntries, name, month),
            target = null,
            progress = null,
            entries = visible.count { it.pot == name },
            orphaned = true,
        )
    }
}

/**
 * A name has to be there and has to be unique. Returns the reason it is
 * rejected, or null when it is fine.
 */
fun validatePotName(
    data: FinanceData,
    name: String,
    /** The pot being edited, so a name does not clash with itself. */
    currentId: String? = null,
): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "Ad yazın"
    if (trimmed.length > 60) return "Bu ad həddindən artıq uzundur"

    val clash = data.savingsPots.any { pot ->
        pot.id != currentId && pot.name.trim().equals(trimmed, ignoreCase = true)
    }

    return if (clash) "Belə qab artıq var" else null
}

fun addPot(data: FinanceData, pot: SavingsPot): FinanceData =
    data.copy(savingsPots = data.savingsPots + pot.copy(name = pot.name.trim()))

/** Rename the pot and every entry that names it, in one change. Touches no
 *  amount, so no balance moves. */
fun renamePot(data: FinanceData, id: String, name: String): FinanceData {
    val target = data.savingsPots.firstOrNull { it.id == id }
    val trimmed = name.trim()
    if (target == null || trimmed.isEmpty() || target.name == trimmed) return data

    return data.copy(
        savingsPots = data.savingsPots.map {
            if (it.id == id) it.copy(name = trimmed) else it
        },
        savingsEntries = data.savingsEntries.map {
            if (it.pot == target.name) it.copy(pot = trimmed) else it
        },
    )
}

/** Set or clear what the pot is being filled towards. */
fun setPotTarget(data: FinanceData, id: String, target: Double?): FinanceData =
    data.copy(
        savingsPots = data.savingsPots.map { pot ->
            if (pot.id == id) {
                pot.copy(target = target?.takeIf { it > 0 }?.let { round2(it) })
            } else {
                pot
            }
        },
    )

/**
 * Remove a pot, moving whatever it holds to [reassignTo] first.
 *
 * Without a destination, a pot that still has entries is left alone: deleting
 * it would either strand the money or silently destroy a record of it, and
 * both are worse than refusing.
 */
fun removePot(data: FinanceData, id: String, reassignTo: String? = null): FinanceData {
    val target = data.savingsPots.firstOrNull { it.id == id } ?: return data

    val used = data.savingsEntries.any { it.pot == target.name }
    if (used && reassignTo == null) return data

    return data.copy(
        savingsPots = data.savingsPots.filter { it.id != id },
        savingsEntries = if (reassignTo != null) {
            data.savingsEntries.map {
                if (it.pot == target.name) it.copy(pot = reassignTo) else it
            }
        } else {
            data.savingsEntries
        },
    )
}

data class ConvertibleSavings(
    val transactions: List<String>,
    val pots: List<String>,
    val total: Double,
)

private fun savingCategoryNames(data: FinanceData): Set<String> = data.categories
    .filter { it.type == TransactionType.EXPENSE && it.kind == CategoryKind.SAVING }
    .map { it.name }
    .toSet()

/**
 * Money already recorded as a saving-kind expense, ready to become entries.
 *
 * Before pots existed the only way to record setting money aside was to spend
 * it into a category marked `saving`. Those rows are the same event written
 * the only way the app allowed at the time, so they convert exactly: the
 * category becomes the pot, and the source is income, because an expense is
 * by definition money the household already had.
 *
 * Nothing is applied here — the screen offers it and the person decides.
 */
fun convertibleSavingTransactions(data: FinanceData): ConvertibleSavings {
    val saving = savingCategoryNames(data)
    val matched = data.transactions.filter {
        it.type == TransactionType.EXPENSE && it.category in saving
    }

    return ConvertibleSavings(
        transactions = matched.map { it.id },
        pots = matched.map { it.category }.distinct(),
        total = sumOf(matched.map { it.amount }),
    )
}

/**
 * Apply that conversion: every matching expense becomes a deposit, and the
 * expense itself goes, because leaving it would count the same money twice.
 *
 * [mintId] is passed in rather than imported so this stays pure and the ids
 * are the app's own.
 */
fun convertSavingTransactions(data: FinanceData, mintId: () -> String): FinanceData {
    val saving = savingCategoryNames(data)
    val matched = data.transactions.filter {
        it.type == TransactionType.EXPENSE && it.category in saving
    }
    if (matched.isEmpty()) return data

    val existing = data.savingsPots.map { it.name }.toSet()
    val newPots = matched.map { it.category }
        .distinct()
        .filter { it !in existing }
        .map { SavingsPot(id = mintId(), name = it) }

    val entries = matched.map { transaction ->
        SavingsEntry(
            id = mintId(),
            date = transaction.date,
            pot = transaction.category,
            amount = transaction.amount,
            direction = SavingsDirection.IN,
            source = SavingsSource.INCOME,
            note = transaction.description,
        )
    }

    val converted = matched.map { it.id }.toSet()

    return data.copy(
        transactions = data.transactions.filter { it.id !in converted },
        savingsPots = data.savingsPots + newPots,
        savingsEntries = data.savingsEntries + entries,
    )
}

/* ------------------------------------------------------------------ *
 * The planned side
 * ------------------------------------------------------------------ */

/** Everything meant to be put away in one month, across its pots. */
fun plannedSavings(plans: List<SavingsPlan>, month: MonthKey): Double {
    val plan = plans.firstOrNull { it.month == month } ?: return 0.0
    return round2(sumOf(plan.amounts.values.toList()))
}

data class PlannedSavingsRow(
    val pot: String,
    val planned: Double,
    /** The plan holds a figure for a pot that no longer exists. */
    val orphaned: Boolean,
)

/**
 * The planned-savings lines for a month: one per pot, plus any figure left
 * behind by a pot that has since gone.
 *
 * The orphan stays visible for the same reason it does on the income side —
 * a list of rows that does not add up to its own total is how a planned
 * amount disappears without anyone being told.
 */
fun plannedSavingsRows(
    pots: List<SavingsPot>,
    amounts: Map<String, Double>,
): List<PlannedSavingsRow> {
    val known = pots.map { it.name }.toSet()

    return pots.map { pot ->
        PlannedSavingsRow(pot = pot.name, planned = amounts[pot.name] ?: 0.0, orphaned = false)
    } + amounts.entries
        .filter { (pot, amount) -> pot !in known && amount > 0 }
        .map { (pot, planned) -> PlannedSavingsRow(pot = pot, planned = planned, orphaned = true) }
}
