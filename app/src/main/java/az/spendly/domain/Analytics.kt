/**
 * The analytics layer.
 *
 * It derives everything from the same [FinanceData] the rest of the app uses —
 * there is no second financial model and nothing is stored pre-computed.
 *
 * Two house rules:
 *   1. Every statement is a calculation, never a judgement. The app reports
 *      "Ərzaq is 24% higher than last month", never "you spend too much".
 *   2. Where a concept needs a definition that the spreadsheet does not supply
 *      (what counts as "unexpected", what counts as "recurring"), the rule is
 *      written down next to the code and surfaced in the UI.
 */
package az.spendly.domain

import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/* ------------------------------------------------------------------ *
 * Period basics
 * ------------------------------------------------------------------ */

data class PeriodSummary(
    /** Sum of income transactions in the period. */
    val income: Double,
    /** Sum of expense transactions in the period. */
    val expenses: Double,
    /** Planned income across the period's months ('BÜDCƏ İCMALI'!C13). */
    val plannedIncome: Double,
    /** Planned expenses across the period's months ('BÜDCƏ İCMALI'!F11). */
    val plannedExpenses: Double,
    /** income - expenses ('BÜDCƏ İCMALI'!D5, summed over the period). */
    val remainder: Double,
    /** plannedIncome - plannedExpenses ('BÜDCƏ İCMALI'!D4). */
    val plannedRemainder: Double,
    /** remainder - plannedRemainder ('BÜDCƏ İCMALI'!D6). */
    val difference: Double,
    /** Share of income retained, 0..1. Null when no income was recorded. */
    val savingsRate: Double?,
    val transactionCount: Int,
)

fun transactionsInPeriod(transactions: List<Transaction>, period: Period): List<Transaction> {
    val months = period.months.toSet()
    return transactions.filter { months.contains(monthOf(it.date)) }
}

fun summarisePeriod(data: FinanceData, period: Period): PeriodSummary {
    val income = sumOf(period.months.map { actualIncome(data.transactions, it) })
    val expenses = sumOf(period.months.map { actualExpenses(data.transactions, it) })
    val plannedIn = sumOf(
        period.months.map { month ->
            plannedIncomeOf(data.incomePlans.firstOrNull { it.month == month })
        },
    )
    val plannedOut = sumOf(period.months.map { plannedExpenses(data.budgetLines, it) })

    val remainder = round2(income - expenses)
    val plannedRemainder = round2(plannedIn - plannedOut)

    return PeriodSummary(
        income = income,
        expenses = expenses,
        plannedIncome = plannedIn,
        plannedExpenses = plannedOut,
        remainder = remainder,
        plannedRemainder = plannedRemainder,
        difference = round2(remainder - plannedRemainder),
        savingsRate = if (income > 0) remainder / income else null,
        transactionCount = transactionsInPeriod(data.transactions, period).size,
    )
}

/* ------------------------------------------------------------------ *
 * Category breakdown
 * ------------------------------------------------------------------ */

data class CategoryRow(
    val category: String,
    val actual: Double,
    val planned: Double,
    /** Share of the period's expenses, 0..1. */
    val share: Double,
    /** Same category in the preceding period of equal length. */
    val previous: Double,
    /** Change vs the previous period, 0..1 scale. Null when previous was zero. */
    val changeRatio: Double?,
    /** No planned line covered this category in this period. */
    val unplanned: Boolean,
)

/** Ranked spending by category — the 'Əlavə məlumatlar' SUMIF, per period. */
fun categoryBreakdown(data: FinanceData, period: Period): List<CategoryRow> {
    val current = expenseByCategory(data.transactions, period)
    val prior = expenseByCategory(data.transactions, previousPeriod(period))
    val planned = plannedByCategory(data.budgetLines, period)
    val total = sumOf(current.values.toList())

    val categories = LinkedHashSet<String>().apply {
        addAll(current.keys)
        addAll(planned.keys)
    }

    return categories
        .map { category ->
            val actual = current[category] ?: 0.0
            val previous = prior[category] ?: 0.0
            val plannedAmount = planned[category] ?: 0.0
            CategoryRow(
                category = category,
                actual = actual,
                planned = plannedAmount,
                share = if (total > 0) actual / total else 0.0,
                previous = previous,
                changeRatio = if (previous > 0) (actual - previous) / previous else null,
                unplanned = plannedAmount == 0.0,
            )
        }
        .filter { it.actual > 0 || it.planned > 0 }
        .sortedWith(compareByDescending<CategoryRow> { it.actual }.thenByDescending { it.planned })
}

private fun expenseByCategory(
    transactions: List<Transaction>,
    period: Period,
): Map<String, Double> {
    val buckets = LinkedHashMap<String, MutableList<Double>>()
    for (transaction in transactionsInPeriod(transactions, period)) {
        if (transaction.type != TransactionType.EXPENSE) continue
        buckets.getOrPut(transaction.category) { mutableListOf() }.add(transaction.amount)
    }
    return buckets.mapValues { (_, amounts) -> sumOf(amounts) }
}

private fun plannedByCategory(
    budgetLines: List<BudgetLine>,
    period: Period,
): Map<String, Double> {
    val buckets = LinkedHashMap<String, MutableList<Double>>()
    for (month in period.months) {
        for (line in budgetLinesInMonth(budgetLines, month)) {
            buckets.getOrPut(line.category) { mutableListOf() }.add(line.planned)
        }
    }
    return buckets.mapValues { (_, amounts) -> sumOf(amounts) }
}

/* ------------------------------------------------------------------ *
 * Expected vs unexpected
 * ------------------------------------------------------------------ */

enum class UnexpectedReason { NO_PLAN, OVER_PLAN }

data class UnexpectedItem(
    val category: String,
    val amount: Double,
    /** Why this counts as unexpected. Factual, never a judgement. */
    val reason: UnexpectedReason,
    /** The planned amount for the category, for the OVER_PLAN reason. */
    val planned: Double,
)

data class ExpectedSplit(
    val expected: Double,
    val unexpected: Double,
    val items: List<UnexpectedItem>,
)

/**
 * The rule, applied per category:
 *   expected   = the part of the spend that the month's plan covered
 *              = min(actual, planned)
 *   unexpected = spend beyond the planned amount, plus everything spent in a
 *                category with no planned line at all.
 *
 * So expected + unexpected always equals total expenses, and both come
 * straight from 'Aylıq rasxod'. Nothing is guessed.
 */
fun expectedSplit(data: FinanceData, period: Period): ExpectedSplit {
    val rows = categoryBreakdown(data, period)
    val items = mutableListOf<UnexpectedItem>()
    var expected = 0.0
    var unexpected = 0.0

    for (row in rows) {
        if (row.actual <= 0) continue
        val covered = min(row.actual, row.planned)
        val excess = round2(row.actual - covered)
        expected = round2(expected + covered)
        if (excess > 0) {
            unexpected = round2(unexpected + excess)
            items.add(
                UnexpectedItem(
                    category = row.category,
                    amount = excess,
                    reason = if (row.planned == 0.0) {
                        UnexpectedReason.NO_PLAN
                    } else {
                        UnexpectedReason.OVER_PLAN
                    },
                    planned = row.planned,
                ),
            )
        }
    }

    return ExpectedSplit(expected, unexpected, items.sortedByDescending { it.amount })
}

/* ------------------------------------------------------------------ *
 * Money flow over time
 * ------------------------------------------------------------------ */

data class FlowBucket(
    val key: String,
    val label: String,
    val income: Double,
    val expenses: Double,
    /** Running balance at the end of this bucket, across all history. */
    val balance: Double,
)

/**
 * Buckets for the flow chart: by week inside a single month, by month across
 * a longer period. Weekly detail on a 6-month view would be unreadable, and
 * monthly granularity on a single month would be a single bar.
 */
fun flowBuckets(data: FinanceData, period: Period): List<FlowBucket> =
    if (period.months.size == 1) {
        weeklyBuckets(data, period.months.first())
    } else {
        monthlyBuckets(data, period.months)
    }

private fun monthlyBuckets(data: FinanceData, months: List<MonthKey>): List<FlowBucket> {
    // Balance carried in from before the period, so the line starts truthfully.
    var balance = openingBalance(data, months.first())
    return months.map { month ->
        val income = actualIncome(data.transactions, month)
        val expenses = actualExpenses(data.transactions, month)
        // Money moved to or from a pot is not income or spending, so it is not
        // in either bar — but it does move the balance, so it is in the line.
        val moved = spendableDeltaOf(
            data.savingsEntries.filter { monthOf(it.date) == month },
        )
        balance = round2(balance + income - expenses + moved)
        FlowBucket(month, formatMonthShort(month), income, expenses, balance)
    }
}

/** Calendar weeks of the month: 1–7, 8–14, 15–21, 22–end. */
private fun weeklyBuckets(data: FinanceData, month: MonthKey): List<FlowBucket> {
    val last = daysInMonth(month)
    val edges = listOf(1, 8, 15, 22)
    var balance = openingBalance(data, month)
    val transactions = transactionsInMonth(data.transactions, month)
    val entries = data.savingsEntries.filter { monthOf(it.date) == month }
    val dayOf = { date: String -> date.substring(8, 10).toInt() }

    return edges.mapIndexed { index, start ->
        val end = if (index == edges.lastIndex) last else edges[index + 1] - 1
        val inRange = transactions.filter { dayOf(it.date) in start..end }
        val income = sumOf(inRange.filter { it.type == TransactionType.INCOME }.map { it.amount })
        val expenses = sumOf(inRange.filter { it.type == TransactionType.EXPENSE }.map { it.amount })
        val moved = spendableDeltaOf(entries.filter { dayOf(it.date) in start..end })
        balance = round2(balance + income - expenses + moved)
        FlowBucket("w${index + 1}", "$start–$end", income, expenses, balance)
    }
}

/** Net of everything strictly before [month], savings movements included, so
 *  the line starts where the balance on screen actually stands. */
private fun openingBalance(data: FinanceData, month: MonthKey): Double = round2(
    sumOf(
        data.transactions
            .filter { monthOf(it.date) < month }
            .map { if (it.type == TransactionType.INCOME) it.amount else -it.amount },
    ) + spendableDeltaOf(data.savingsEntries.filter { monthOf(it.date) < month }),
)

/* ------------------------------------------------------------------ *
 * Daily activity
 * ------------------------------------------------------------------ */

data class DayActivity(
    val date: DateKey,
    val day: Int,
    val income: Double,
    val expenses: Double,
    val transactions: List<Transaction>,
)

/** Every day of a single month, including the empty ones. */
fun dailyActivity(data: FinanceData, month: MonthKey): List<DayActivity> {
    val (year, monthIndex) = month.split("-").map { it.toInt() }
    val transactions = transactionsInMonth(data.transactions, month)

    return (1..daysInMonth(month)).map { day ->
        val date = toDateKey(year, monthIndex, day)
        val onDay = transactions.filter { it.date == date }
        DayActivity(
            date = date,
            day = day,
            income = sumOf(onDay.filter { it.type == TransactionType.INCOME }.map { it.amount }),
            expenses = sumOf(onDay.filter { it.type == TransactionType.EXPENSE }.map { it.amount }),
            transactions = onDay,
        )
    }
}

/** Biggest movements in the period, largest first. */
fun largestTransactions(data: FinanceData, period: Period, limit: Int = 5): List<Transaction> =
    transactionsInPeriod(data.transactions, period)
        .filter { it.type == TransactionType.EXPENSE }
        .sortedWith(compareByDescending<Transaction> { it.amount }.thenByDescending { it.date })
        .take(limit)

/* ------------------------------------------------------------------ *
 * Recurring commitments
 * ------------------------------------------------------------------ */

data class Recurring(
    val description: String,
    val category: String,
    val planned: Double,
    /** Transactions whose description matches this line, in the same month. */
    val matched: List<Transaction>,
    val actual: Double,
)

/**
 * The rule: a planned line counts as recurring when the same description is
 * planned in this month and in at least one earlier month. That is exactly how
 * the spreadsheet expressed recurrence — the same rows, copied forward.
 *
 * Payment status is reported by matching a transaction's description to the
 * line's, case- and space-insensitively. This describes the data ("no matching
 * transaction"), and deliberately does not claim a bill went unpaid.
 */
fun recurringCommitments(data: FinanceData, month: MonthKey): List<Recurring> {
    val lines = budgetLinesInMonth(data.budgetLines, month)
    val earlier = data.budgetLines
        .filter { it.month < month }
        .map { normalise(it.description) }
        .toSet()
    val transactions = transactionsInMonth(data.transactions, month)
        .filter { it.type == TransactionType.EXPENSE }

    return lines
        .filter { earlier.contains(normalise(it.description)) }
        .map { line ->
            val matched = transactions.filter {
                normalise(it.description) == normalise(line.description)
            }
            Recurring(
                description = line.description,
                category = line.category,
                planned = line.planned,
                matched = matched,
                actual = sumOf(matched.map { it.amount }),
            )
        }
        .sortedByDescending { it.planned }
}

private fun normalise(value: String): String =
    value.trim().lowercase().replace(Regex("\\s+"), " ")

/* ------------------------------------------------------------------ *
 * Insights
 * ------------------------------------------------------------------ */

enum class InsightTone { NEUTRAL, POSITIVE, ATTENTION }

data class Insight(val id: String, val text: String, val tone: InsightTone)

/** Below this, a percentage change is noise rather than a pattern. */
private const val MATERIAL_CHANGE = 0.1

/** Below this, an amount is too small to be worth a line of the summary. */
private const val MATERIAL_AMOUNT = 5.0

/**
 * Deterministic observations, ordered by how much they matter.
 *
 * Every entry is an arithmetic fact about the data. None of them advise, and
 * an insight is omitted entirely when the data cannot support it — a month
 * with no predecessor produces no comparisons rather than invented ones.
 *
 * Sentences are impersonal ("qalıb", not "saxladınız") so the app reports on
 * the money rather than addressing the person spending it.
 */
fun insights(data: FinanceData, period: Period): List<Insight> {
    val previous = previousPeriod(period)
    val now = summarisePeriod(data, period)
    val before = summarisePeriod(data, previous)
    val rows = categoryBreakdown(data, period)
    val result = mutableListOf<Insight>()

    if (now.transactionCount == 0) return result

    val hasHistory = before.transactionCount > 0

    // Overall spend against the plan.
    if (now.plannedExpenses > 0 && now.expenses > 0) {
        val ratio = (now.expenses - now.plannedExpenses) / now.plannedExpenses
        if (abs(ratio) >= MATERIAL_CHANGE) {
            result.add(
                Insight(
                    id = "plan",
                    text = if (ratio > 0) {
                        "Xərclər plandan ${percent(ratio)} çoxdur — " +
                            "${money(now.expenses - now.plannedExpenses)} artıq xərclənib."
                    } else {
                        "Xərclər plandan ${percent(-ratio)} azdır — " +
                            "${money(now.plannedExpenses - now.expenses)} hələ büdcədə qalıb."
                    },
                    tone = if (ratio > 0) InsightTone.ATTENTION else InsightTone.POSITIVE,
                ),
            )
        }
    }

    // Total spending against the comparable previous period.
    if (hasHistory && before.expenses > 0 && now.expenses > 0) {
        val ratio = (now.expenses - before.expenses) / before.expenses
        if (abs(ratio) >= MATERIAL_CHANGE) {
            result.add(
                Insight(
                    id = "spend-change",
                    text = "Ümumi xərclər əvvəlki dövrə nisbətən ${percent(abs(ratio))} " +
                        (if (ratio > 0) "çoxdur" else "azdır") + ".",
                    tone = if (ratio > 0) InsightTone.ATTENTION else InsightTone.POSITIVE,
                ),
            )
        }
    }

    // Income against the comparable previous period.
    if (hasHistory && before.income > 0 && now.income > 0) {
        val delta = round2(now.income - before.income)
        if (abs(delta) >= MATERIAL_AMOUNT) {
            result.add(
                Insight(
                    id = "income-change",
                    text = "Gəlir əvvəlki dövrə nisbətən ${money(abs(delta))} " +
                        (if (delta > 0) "çoxdur" else "azdır") + ".",
                    tone = if (delta > 0) InsightTone.POSITIVE else InsightTone.ATTENTION,
                ),
            )
        }
    }

    // The largest category, and whether it took over the top spot.
    val top = rows.firstOrNull { it.actual > 0 }
    if (top != null) {
        val priorTop = categoryBreakdown(data, previous).firstOrNull { it.actual > 0 }
        val changedLead = hasHistory && priorTop != null && priorTop.category != top.category
        result.add(
            Insight(
                id = "top-category",
                text = if (changedLead) {
                    "Ən böyük xərc indi ${top.category} — ${money(top.actual)}; " +
                        "əvvəlki dövrdə ${priorTop.category} idi."
                } else {
                    "Ən böyük xərc ${top.category} — ${money(top.actual)}, " +
                        "bütün xərclərin ${percent(top.share)}-i."
                },
                tone = InsightTone.NEUTRAL,
            ),
        )
    }

    // The category that moved the most, in either direction.
    val movers = rows
        .filter { row ->
            row.changeRatio != null &&
                abs(row.changeRatio) >= MATERIAL_CHANGE &&
                abs(row.actual - row.previous) >= MATERIAL_AMOUNT
        }
        .sortedByDescending { abs(it.actual - it.previous) }

    for (row in movers.take(2)) {
        val ratio = row.changeRatio!!
        result.add(
            Insight(
                id = "mover-${row.category}",
                text = "${row.category} xərcləri əvvəlki dövrə nisbətən ${percent(abs(ratio))} " +
                    (if (ratio > 0) "çoxdur" else "azdır") +
                    " (${money(row.actual)} / ${money(row.previous)}).",
                tone = if (ratio > 0) InsightTone.ATTENTION else InsightTone.POSITIVE,
            ),
        )
    }

    // Money retained.
    if (now.income > 0) {
        result.add(
            Insight(
                id = "retained",
                text = if (now.remainder >= 0) {
                    "Daxil olan ${money(now.income)} məbləğdən ${money(now.remainder)} qalıb" +
                        (now.savingsRate?.let { " — gəlirin ${percent(it)}-i" } ?: "") + "."
                } else {
                    "Daxil olandan ${money(-now.remainder)} çox xərclənib."
                },
                tone = if (now.remainder >= 0) InsightTone.POSITIVE else InsightTone.ATTENTION,
            ),
        )
    }

    return result
}

private fun percent(ratio: Double): String = "${(abs(ratio) * 100).roundToInt()}%"

private fun money(value: Double): String = String.format(Locale.US, "%,.2f ₼", round2(value))

/* ------------------------------------------------------------------ *
 * Income by source
 * ------------------------------------------------------------------ */

data class IncomeSource(
    val category: String,
    val actual: Double,
    /** 'BÜDCƏ İCMALI'!C11:C12 for the period's months, per row. */
    val planned: Double,
    /** Share of the period's income, 0..1. */
    val share: Double,
)

/**
 * Where income came from, against what was planned for it.
 *
 * Both sides are per category: the plan holds a figure for each income
 * category the user keeps, and anything that arrived under a category with no
 * planned figure is reported with a planned amount of zero rather than being
 * folded into something else.
 */
fun incomeSources(data: FinanceData, period: Period): List<IncomeSource> {
    val months = period.months.toSet()

    val actual = LinkedHashMap<String, Double>()
    for (transaction in data.transactions) {
        if (transaction.type != TransactionType.INCOME) continue
        if (!months.contains(monthOf(transaction.date))) continue
        actual[transaction.category] =
            round2((actual[transaction.category] ?: 0.0) + transaction.amount)
    }

    val planned = LinkedHashMap<String, Double>()
    for (plan in data.incomePlans) {
        if (!months.contains(plan.month)) continue
        for ((category, amount) in plan.amounts) {
            planned[category] = round2((planned[category] ?: 0.0) + amount)
        }
    }

    val total = sumOf(actual.values.toList())

    return LinkedHashSet<String>().apply { addAll(actual.keys); addAll(planned.keys) }
        .map { category ->
            IncomeSource(
                category = category,
                actual = actual[category] ?: 0.0,
                planned = planned[category] ?: 0.0,
                share = if (total > 0) (actual[category] ?: 0.0) / total else 0.0,
            )
        }
        .filter { it.actual > 0 || it.planned > 0 }
        .sortedWith(compareByDescending<IncomeSource> { it.actual }.thenByDescending { it.planned })
}

/* ------------------------------------------------------------------ *
 * Spending pace
 * ------------------------------------------------------------------ */

data class SpendingPace(
    /** Days of the month that have happened, 1..days. */
    val elapsed: Int,
    val days: Int,
    val spent: Double,
    val planned: Double,
    /** Spent so far divided by the days it was spent over. */
    val perDay: Double,
    /** `perDay × days`. An extrapolation of the rate so far, and nothing more. */
    val atThisRate: Double,
    /** The month is over, so [atThisRate] is simply what was spent. */
    val complete: Boolean,
)

/**
 * How fast the month is being spent.
 *
 * [SpendingPace.atThisRate] extends the rate so far across the whole month. It
 * is arithmetic on days elapsed, not a forecast of behaviour, and the UI says
 * so — for a month that has already ended it is simply the total, and is
 * labelled as an average instead.
 */
fun spendingPace(data: FinanceData, month: MonthKey, asOf: DateKey): SpendingPace? {
    val days = daysInMonth(month)
    val current = monthOf(asOf)

    // A month that has not started yet has no rate to report.
    if (month > current) return null

    val complete = month < current
    val elapsed = if (complete) days else min(asOf.substring(8, 10).toInt(), days)
    if (elapsed <= 0) return null

    val spent = actualExpenses(data.transactions, month)
    val perDay = round2(spent / elapsed)

    return SpendingPace(
        elapsed = elapsed,
        days = days,
        spent = spent,
        planned = plannedExpenses(data.budgetLines, month),
        perDay = perDay,
        atThisRate = if (complete) spent else round2(perDay * days),
        complete = complete,
    )
}

/* ------------------------------------------------------------------ *
 * Weekday pattern
 * ------------------------------------------------------------------ */

data class WeekdayLoad(
    /** 0 = Monday, 6 = Sunday. */
    val weekday: Int,
    val expenses: Double,
    val count: Int,
)

/** Spending by day of the week. Always seven entries, Monday first. */
fun weekdayPattern(data: FinanceData, period: Period): List<WeekdayLoad> {
    val expenses = DoubleArray(7)
    val counts = IntArray(7)

    for (transaction in transactionsInPeriod(data.transactions, period)) {
        if (transaction.type != TransactionType.EXPENSE) continue
        val weekday = weekdayOf(transaction.date)
        expenses[weekday] = round2(expenses[weekday] + transaction.amount)
        counts[weekday] += 1
    }

    return (0..6).map { WeekdayLoad(it, expenses[it], counts[it]) }
}

/* ------------------------------------------------------------------ *
 * What repeats
 * ------------------------------------------------------------------ */

data class FrequentExpense(
    val description: String,
    val category: String,
    val count: Int,
    val total: Double,
)

/**
 * The expenses that keep coming back, by description.
 *
 * This is the transaction-side counterpart of [recurringCommitments], which
 * only sees what the plan named. Something bought every week without a budget
 * line for it is invisible there and is exactly what this surfaces, so a
 * single entry is not interesting — two or more is the threshold.
 */
fun frequentExpenses(data: FinanceData, period: Period, limit: Int): List<FrequentExpense> {
    val groups = LinkedHashMap<String, FrequentExpense>()

    for (transaction in transactionsInPeriod(data.transactions, period)) {
        if (transaction.type != TransactionType.EXPENSE) continue
        val key = normalise(transaction.description)
        if (key.isEmpty()) continue

        val existing = groups[key]
        groups[key] = if (existing != null) {
            existing.copy(
                count = existing.count + 1,
                total = round2(existing.total + transaction.amount),
            )
        } else {
            FrequentExpense(
                description = transaction.description,
                category = transaction.category,
                count = 1,
                total = round2(transaction.amount),
            )
        }
    }

    return groups.values
        .filter { it.count > 1 }
        .sortedWith(compareByDescending<FrequentExpense> { it.count }.thenByDescending { it.total })
        .take(limit)
}
