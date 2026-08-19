/**
 * A period is a contiguous run of calendar months.
 *
 * Months are the unit because the spreadsheet budgets by month: planned
 * amounts only exist per month, so any period that is not a whole number of
 * months could not be compared against a plan.
 *
 * Every period is anchored to the month chosen in the header, so the two
 * controls compose: the switcher picks where, the selector picks how wide.
 */
package az.spendly.domain

enum class PeriodId { MONTH, LAST, QUARTER, HALF, YEAR }

data class Period(
    val id: PeriodId,
    val label: String,
    /** Oldest first. Never empty. */
    val months: List<MonthKey>,
)

data class PeriodOption(val id: PeriodId, val label: String, val short: String)

val PERIODS = listOf(
    PeriodOption(PeriodId.MONTH, "Bu ay", "Ay"),
    PeriodOption(PeriodId.LAST, "Keçən ay", "Keçən"),
    PeriodOption(PeriodId.QUARTER, "3 ay", "3 ay"),
    PeriodOption(PeriodId.HALF, "6 ay", "6 ay"),
    PeriodOption(PeriodId.YEAR, "Bu il", "İl"),
)

/** Build the month list for a period anchored on [anchor]. */
fun resolvePeriod(id: PeriodId, anchor: MonthKey): Period {
    val label = PERIODS.firstOrNull { it.id == id }?.label ?: "Bu ay"

    return when (id) {
        PeriodId.LAST -> Period(id, label, listOf(shiftMonth(anchor, -1)))
        PeriodId.QUARTER -> Period(id, label, span(anchor, 3))
        PeriodId.HALF -> Period(id, label, span(anchor, 6))
        PeriodId.YEAR -> Period(id, label, yearToDate(anchor))
        PeriodId.MONTH -> Period(PeriodId.MONTH, label, listOf(anchor))
    }
}

/**
 * The equally-long run of months immediately before [period], used for every
 * "compared with" figure. Comparing like with like keeps the deltas honest.
 */
fun previousPeriod(period: Period): Period {
    val length = period.months.size
    val end = shiftMonth(period.months.first(), -1)
    return Period(
        id = period.id,
        label = "əvvəlki ${if (length == 1) "ay" else "$length ay"}",
        months = span(end, length),
    )
}

/** True when the period covers exactly one month, which unlocks daily detail. */
fun isSingleMonth(period: Period): Boolean = period.months.size == 1

/** How a comparison should be worded, e.g. "keçən aya nisbətən". */
fun comparisonLabel(period: Period): String {
    val length = period.months.size
    return if (length == 1) "keçən aya nisbətən" else "əvvəlki $length aya nisbətən"
}

/** [n] months ending at [end], oldest first. */
private fun span(end: MonthKey, n: Int): List<MonthKey> =
    (0 until n).map { index -> shiftMonth(end, index - (n - 1)) }

/** January of the anchor's year through the anchor month itself. */
private fun yearToDate(anchor: MonthKey): List<MonthKey> {
    val (year, month) = anchor.split("-").map { it.toInt() }
    return (1..month).map { "%d-%02d".format(year, it) }
}
