/**
 * Every financial number in the app is derived here. Nothing is stored
 * pre-computed, and nothing is hard-coded.
 *
 * Each function names the spreadsheet cell it reproduces.
 */
package az.spendly.domain

data class MonthSummary(
    val month: MonthKey,
    /** 'BÜDCƏ İCMALI'!C13 — SUM(C11:C12) */
    val plannedIncome: Double,
    /** 'BÜDCƏ İCMALI'!D13 — SUM(D11:D12) */
    val actualIncome: Double,
    /** 'BÜDCƏ İCMALI'!F11 — SUM('Aylıq rasxod'!D:D) */
    val plannedExpenses: Double,
    /** 'BÜDCƏ İCMALI'!G11 — SUM('Aylıq rasxod'!E:E) */
    val actualExpenses: Double,
    /** 'BÜDCƏ İCMALI'!D4 — C13 - F11 */
    val plannedRemainder: Double,
    /** 'BÜDCƏ İCMALI'!D5 — actual income - actual expenses */
    val actualRemainder: Double,
    /** 'BÜDCƏ İCMALI'!D6 — D5 - D4 */
    val difference: Double,
)

/**
 * Planned lines of one category, with the actual spend for that category.
 *
 * Actual is reported per category rather than per line: a transaction records
 * a category, so line-level actuals do not exist and would have to be invented.
 */
data class BudgetGroup(
    val category: String,
    val lines: List<BudgetLine>,
    /** Sum of the group's planned amounts. */
    val planned: Double,
    /** SUMIF over actual spend for this category. */
    val actual: Double,
    /** Column F — planned - actual. Positive means under budget. */
    val variance: Double,
)

/** One row of the 'Əlavə məlumatlar' SUMIF rollup. */
data class CategoryTotal(
    val category: String,
    val planned: Double,
    val actual: Double,
    /** Share of actual expenses, 0..1. Zero when there is no spending at all. */
    val share: Double,
)

fun transactionsInMonth(transactions: List<Transaction>, month: MonthKey): List<Transaction> =
    transactions.filter { monthOf(it.date) == month }

/** Newest first; ties on the same date resolve to most recently added first. */
fun sortTransactions(transactions: List<Transaction>): List<Transaction> =
    transactions.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.id })

fun budgetLinesInMonth(budgetLines: List<BudgetLine>, month: MonthKey): List<BudgetLine> =
    budgetLines.filter { it.month == month }

/** 'BÜDCƏ İCMALI'!D13 — actual income is the sum of income transactions. */
fun actualIncome(transactions: List<Transaction>, month: MonthKey): Double =
    sumOf(
        transactionsInMonth(transactions, month)
            .filter { it.type == TransactionType.INCOME }
            .map { it.amount },
    )

/** 'BÜDCƏ İCMALI'!G11 — actual expenses are the sum of expense transactions. */
fun actualExpenses(transactions: List<Transaction>, month: MonthKey): Double =
    sumOf(
        transactionsInMonth(transactions, month)
            .filter { it.type == TransactionType.EXPENSE }
            .map { it.amount },
    )

/** 'BÜDCƏ İCMALI'!F11 — SUM of every planned line for the month. */
fun plannedExpenses(budgetLines: List<BudgetLine>, month: MonthKey): Double =
    sumOf(budgetLinesInMonth(budgetLines, month).map { it.planned })

/** The full 'BÜDCƏ İCMALI' summary block for one month. */
fun summarise(data: FinanceData, month: MonthKey): MonthSummary {
    val plan = data.incomePlans.firstOrNull { it.month == month }
    val plannedIncome = round2(plannedIncomeOf(plan))
    val income = actualIncome(data.transactions, month)
    val expensesPlanned = plannedExpenses(data.budgetLines, month)
    val expensesActual = actualExpenses(data.transactions, month)

    val plannedRemainder = round2(plannedIncome - expensesPlanned)
    val actualRemainder = round2(income - expensesActual)

    return MonthSummary(
        month = month,
        plannedIncome = plannedIncome,
        actualIncome = income,
        plannedExpenses = expensesPlanned,
        actualExpenses = expensesActual,
        plannedRemainder = plannedRemainder,
        actualRemainder = actualRemainder,
        difference = round2(actualRemainder - plannedRemainder),
    )
}

/**
 * Running balance across every month up to and including [month]: accumulated
 * actual income minus actual expenses. This is the "how much do I have right
 * now" number — the sheet's D5 logic applied to all history rather than to a
 * single file.
 */
fun runningBalance(transactions: List<Transaction>, month: MonthKey? = null): Double {
    val relevant = if (month != null) {
        transactions.filter { monthOf(it.date) <= month }
    } else {
        transactions
    }
    return sumOf(
        relevant.map { if (it.type == TransactionType.INCOME) it.amount else -it.amount },
    )
}

/**
 * The 'Aylıq rasxod' plan for one month, grouped by category so that planned
 * amounts and actual spend can be compared on the same footing.
 *
 * A category that was spent on without being planned still appears, so the
 * group actuals always add up to total actual expenses.
 */
fun budgetGroups(data: FinanceData, month: MonthKey): List<BudgetGroup> {
    val lines = budgetLinesInMonth(data.budgetLines, month)
    val spendByCategory = expenseTotalsByCategory(data.transactions, month)

    val categories = LinkedHashSet<String>()
    lines.forEach { categories.add(it.category) }
    spendByCategory.keys.forEach { categories.add(it) }

    return categories
        .map { category ->
            val groupLines = lines.filter { it.category == category }
            val planned = sumOf(groupLines.map { it.planned })
            val actual = spendByCategory[category] ?: 0.0
            BudgetGroup(
                category = category,
                lines = groupLines,
                planned = planned,
                actual = actual,
                variance = round2(planned - actual),
            )
        }
        .sortedWith(compareByDescending<BudgetGroup> { it.planned }.thenByDescending { it.actual })
}

private fun expenseTotalsByCategory(
    transactions: List<Transaction>,
    month: MonthKey,
): Map<String, Double> {
    val totals = LinkedHashMap<String, MutableList<Double>>()
    for (transaction in transactionsInMonth(transactions, month)) {
        if (transaction.type != TransactionType.EXPENSE) continue
        totals.getOrPut(transaction.category) { mutableListOf() }.add(transaction.amount)
    }
    return totals.mapValues { (_, amounts) -> sumOf(amounts) }
}

/**
 * The 'Əlavə məlumatlar' rollup: SUMIF(category) over actual spend, alongside
 * the planned total for the same category.
 *
 * Categories with neither planned nor actual money are omitted — an empty row
 * carries no information. A category that is planned but unspent is kept,
 * because "budgeted and untouched" is meaningful.
 */
fun categoryTotals(data: FinanceData, month: MonthKey): List<CategoryTotal> {
    val spendByCategory = expenseTotalsByCategory(data.transactions, month)
    val lines = budgetLinesInMonth(data.budgetLines, month)
    val totalActual = actualExpenses(data.transactions, month)

    // Include every known category plus any legacy category still present in data.
    val known = LinkedHashSet<String>(EXPENSE_CATEGORIES)
    spendByCategory.keys.forEach { known.add(it) }
    lines.forEach { known.add(it.category) }

    return known
        .map { category ->
            val planned = sumOf(lines.filter { it.category == category }.map { it.planned })
            val actual = spendByCategory[category] ?: 0.0
            CategoryTotal(
                category = category,
                planned = planned,
                actual = actual,
                share = if (totalActual > 0) actual / totalActual else 0.0,
            )
        }
        .filter { it.planned > 0 || it.actual > 0 }
        .sortedWith(compareByDescending<CategoryTotal> { it.actual }.thenByDescending { it.planned })
}

/** Months that hold any data, newest first. Always includes [extra]. */
fun knownMonths(data: FinanceData, extra: MonthKey): List<MonthKey> {
    val months = LinkedHashSet<MonthKey>()
    months.add(extra)
    data.transactions.forEach { months.add(monthOf(it.date)) }
    data.budgetLines.forEach { months.add(it.month) }
    data.incomePlans.forEach { months.add(it.month) }
    return months.sortedDescending()
}

data class TrendPoint(
    val month: MonthKey,
    val income: Double,
    val expenses: Double,
    val remainder: Double,
)

/** Actual income / expenses / remainder per month, oldest first, for the trend. */
fun monthlyTrend(data: FinanceData, months: List<MonthKey>): List<TrendPoint> =
    months.map { month ->
        val income = actualIncome(data.transactions, month)
        val expenses = actualExpenses(data.transactions, month)
        TrendPoint(month, income, expenses, round2(income - expenses))
    }
