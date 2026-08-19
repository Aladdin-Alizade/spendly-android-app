/**
 * The frameworks that need to know what spending is *for*.
 *
 * All three of these — needs vs wants, 50/30/20, and an emergency-fund target
 * — are worthless unless the categories behind them are classified. So the
 * governing idea here is coverage: every result reports what share of the
 * month's spending it could actually account for, and refuses to draw a
 * conclusion below [CLASSIFICATION_COVERAGE_MIN].
 *
 * The alternative — assuming an unclassified category is a need, or quietly
 * leaving it out of the denominator — produces a confident number that is
 * wrong, which is worse than no number.
 */
package az.spendly.domain.insights

import az.spendly.domain.CategoryKind
import az.spendly.domain.FinanceData
import az.spendly.domain.MonthKey
import az.spendly.domain.TransactionType
import az.spendly.domain.actualIncome
import az.spendly.domain.monthOf
import az.spendly.domain.round2
import az.spendly.domain.shiftMonth
import az.spendly.domain.sumOf

/** Below this share of spending classified, the frameworks stay silent. */
const val CLASSIFICATION_COVERAGE_MIN = 0.9

/** How the four kinds read on screen. */
val KIND_LABEL: Map<CategoryKind, String> = mapOf(
    CategoryKind.ESSENTIAL to "Zəruri",
    CategoryKind.DISCRETIONARY to "İstəyə bağlı",
    CategoryKind.DEBT to "Borc ödənişi",
    CategoryKind.SAVING to "Yığım",
)

data class SpendingSplit(
    val essential: Double,
    val discretionary: Double,
    val debt: Double,
    val saving: Double,
    /** Spending in categories with no kind set. */
    val unclassified: Double,
    val total: Double,
    /** Share of spending that carried a kind, 0..1. */
    val coverage: Double,
    /** Names of the categories still unclassified, largest first. */
    val missing: List<String>,
) {
    fun of(kind: CategoryKind): Double = when (kind) {
        CategoryKind.ESSENTIAL -> essential
        CategoryKind.DISCRETIONARY -> discretionary
        CategoryKind.DEBT -> debt
        CategoryKind.SAVING -> saving
    }

    /** True when there is enough classified spending to draw on. */
    val hasCoverage: Boolean
        get() = total > 0 && coverage >= CLASSIFICATION_COVERAGE_MIN
}

fun classifySpending(data: FinanceData, month: MonthKey): SpendingSplit {
    val kindOf = data.categories
        .filter { it.type == TransactionType.EXPENSE }
        .associate { it.name to it.kind }

    val totals = mutableMapOf<CategoryKind, Double>()
    val unclassifiedByCategory = LinkedHashMap<String, Double>()

    for (transaction in data.transactions) {
        if (transaction.type != TransactionType.EXPENSE) continue
        if (monthOf(transaction.date) != month) continue

        val kind = kindOf[transaction.category]
        if (kind != null) {
            totals[kind] = round2((totals[kind] ?: 0.0) + transaction.amount)
        } else {
            unclassifiedByCategory[transaction.category] =
                round2((unclassifiedByCategory[transaction.category] ?: 0.0) + transaction.amount)
        }
    }

    val unclassified = sumOf(unclassifiedByCategory.values.toList())
    val classified = sumOf(totals.values.toList())
    val total = round2(classified + unclassified)

    return SpendingSplit(
        essential = totals[CategoryKind.ESSENTIAL] ?: 0.0,
        discretionary = totals[CategoryKind.DISCRETIONARY] ?: 0.0,
        debt = totals[CategoryKind.DEBT] ?: 0.0,
        saving = totals[CategoryKind.SAVING] ?: 0.0,
        unclassified = unclassified,
        total = total,
        coverage = if (total > 0) classified / total else 0.0,
        missing = unclassifiedByCategory.entries
            .sortedByDescending { it.value }
            .map { it.key },
    )
}

/* ------------------------------------------------------------------ *
 * 50/30/20
 * ------------------------------------------------------------------ */

/**
 * The reference split, from Warren & Tyagi's *All Your Worth* (2005), which
 * the CFPB teaches as one budgeting rule among several rather than as a
 * requirement.
 */
object Reference503020 {
    const val NEEDS = 0.5
    const val WANTS = 0.3
    const val SAVINGS = 0.2
}

data class FrameworkSplit(
    val needs: Double,
    val wants: Double,
    val savings: Double,
    val income: Double,
    val needsShare: Double,
    val wantsShare: Double,
    val savingsShare: Double,
    val coverage: Double,
)

/**
 * Mapped onto the app's four kinds:
 *
 *   needs   = essential + debt
 *   wants   = discretionary
 *   savings = money set aside, plus whatever was simply not spent
 *
 * Debt sits with needs rather than with savings because *All Your Worth* puts
 * required debt payments among the must-haves; the 20% is what is saved, not
 * what is owed. The mapping is stated on screen so it can be disagreed with.
 */
fun fiftyThirtyTwenty(data: FinanceData, month: MonthKey): FrameworkSplit? {
    val split = classifySpending(data, month)
    val income = actualIncome(data.transactions, month)
    if (income <= 0 || !split.hasCoverage) return null

    val needs = round2(split.essential + split.debt)
    val wants = split.discretionary
    // What was not spent is retained, so it belongs on the savings side along
    // with anything deliberately set aside.
    val savings = round2(split.saving + (income - split.total))

    return FrameworkSplit(
        needs = needs,
        wants = wants,
        savings = savings,
        income = income,
        needsShare = needs / income,
        wantsShare = wants / income,
        savingsShare = savings / income,
        coverage = split.coverage,
    )
}

/* ------------------------------------------------------------------ *
 * Emergency fund
 * ------------------------------------------------------------------ */

/** Months of history the estimate is drawn from. */
private const val ESTIMATE_WINDOW = 6

/** Fewer than this and a monthly figure is not an estimate, it is one month. */
private const val ESTIMATE_MIN_MONTHS = 3

data class EmergencyFund(
    /** Median monthly essential spending — median, so one unusual month does
     *  not set the target. */
    val essentialMonthly: Double,
    /** [essentialMonthly] × the chosen number of months. */
    val target: Double,
    val months: Int,
    /** How many months the estimate was drawn from. */
    val sampleMonths: Int,
)

/**
 * A target, and only a target.
 *
 * The app never sees an account balance, so it cannot say how far along you
 * are — and it does not pretend to. The number of months is the user's to
 * choose: the CFPB deliberately publishes no universal figure, saying the
 * amount "depends on your situation".
 */
fun emergencyFund(data: FinanceData, month: MonthKey, months: Int): EmergencyFund? {
    val history = mutableListOf<Double>()

    for (index in 0 until ESTIMATE_WINDOW) {
        val split = classifySpending(data, shiftMonth(month, -index))
        if (split.total <= 0 || !split.hasCoverage) continue
        history.add(round2(split.essential + split.debt))
    }

    if (history.size < ESTIMATE_MIN_MONTHS) return null

    val essentialMonthly = round2(median(history))
    if (essentialMonthly <= 0) return null

    return EmergencyFund(
        essentialMonthly = essentialMonthly,
        target = round2(essentialMonthly * months),
        months = months,
        sampleMonths = history.size,
    )
}
