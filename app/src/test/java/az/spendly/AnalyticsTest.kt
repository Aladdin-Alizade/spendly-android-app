/**
 * The analytics layer, carried over from the web app's own suite.
 *
 * The reconciliation block is the important one: every panel of the dashboard
 * reads the same money, so they must all add up to the same total.
 */
package az.spendly

import az.spendly.domain.BudgetLine
import az.spendly.domain.FinanceData
import az.spendly.domain.IncomePlan
import az.spendly.domain.PeriodId
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import az.spendly.domain.UnexpectedReason
import az.spendly.domain.categoryBreakdown
import az.spendly.domain.comparisonLabel
import az.spendly.domain.dailyActivity
import az.spendly.domain.expectedSplit
import az.spendly.domain.flowBuckets
import az.spendly.domain.formatMonthShort
import az.spendly.domain.frequentExpenses
import az.spendly.domain.incomeSources
import az.spendly.domain.insights
import az.spendly.domain.largestTransactions
import az.spendly.domain.previousPeriod
import az.spendly.domain.recurringCommitments
import az.spendly.domain.resolvePeriod
import az.spendly.domain.spendingPace
import az.spendly.domain.sumOf
import az.spendly.domain.summarisePeriod
import az.spendly.domain.transactionsInPeriod
import az.spendly.domain.weekdayPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ANCHOR = "2026-08"
private val month = resolvePeriod(PeriodId.MONTH, ANCHOR)

private var seq = 0

private fun t(
    date: String = "$ANCHOR-05",
    type: TransactionType = TransactionType.EXPENSE,
    category: String = "Ərzaq",
    description: String = "Test",
    amount: Double = 10.0,
): Transaction {
    seq += 1
    return Transaction("a$seq", date, type, category, description, amount)
}

private fun data(
    transactions: List<Transaction> = emptyList(),
    budgetLines: List<BudgetLine> = emptyList(),
    incomePlans: List<IncomePlan> = emptyList(),
) = FinanceData(transactions, budgetLines, incomePlans, sheetCategories())

class PeriodTest {

    @Test
    fun `anchors every preset on the selected month`() {
        assertEquals(listOf("2026-08"), resolvePeriod(PeriodId.MONTH, ANCHOR).months)
        assertEquals(listOf("2026-07"), resolvePeriod(PeriodId.LAST, ANCHOR).months)
        assertEquals(
            listOf("2026-06", "2026-07", "2026-08"),
            resolvePeriod(PeriodId.QUARTER, ANCHOR).months,
        )
    }

    @Test
    fun `runs this-year from January to the anchor month`() {
        val year = resolvePeriod(PeriodId.YEAR, ANCHOR)
        assertEquals(8, year.months.size)
        assertEquals("2026-01", year.months.first())
        assertEquals("2026-08", year.months.last())
    }

    @Test
    fun `compares against an equally long preceding run`() {
        val quarter = resolvePeriod(PeriodId.QUARTER, ANCHOR)
        assertEquals(
            listOf("2026-03", "2026-04", "2026-05"),
            previousPeriod(quarter).months,
        )
    }

    @Test
    fun `crosses the year boundary correctly`() {
        assertEquals(
            listOf("2025-12", "2026-01", "2026-02"),
            resolvePeriod(PeriodId.QUARTER, "2026-02").months,
        )
    }

    @Test
    fun `never produces an empty period`() {
        PeriodId.entries.forEach { id ->
            assertTrue(resolvePeriod(id, ANCHOR).months.isNotEmpty())
        }
    }

    @Test
    fun `words the comparison to match the period length`() {
        assertEquals("keçən aya nisbətən", comparisonLabel(resolvePeriod(PeriodId.MONTH, ANCHOR)))
        assertEquals(
            "əvvəlki 3 aya nisbətən",
            comparisonLabel(resolvePeriod(PeriodId.QUARTER, ANCHOR)),
        )
    }
}

class PeriodSummaryTest {

    private val sample = data(
        transactions = listOf(
            t("2026-07-01", TransactionType.INCOME, "Maaş", amount = 990.0),
            t("2026-07-10", amount = 400.0),
            t("2026-08-01", TransactionType.INCOME, "Maaş", amount = 990.0),
            t("2026-08-10", amount = 200.0),
        ),
        budgetLines = listOf(
            BudgetLine("b1", "2026-07", "x", "Ərzaq", 500.0),
            BudgetLine("b2", "2026-08", "y", "Ərzaq", 300.0),
        ),
        incomePlans = listOf(
            IncomePlan("2026-07", mapOf("Maaş" to 990.0)),
            IncomePlan("2026-08", mapOf("Maaş" to 990.0, "Əlavə gəlir" to 10.0)),
        ),
    )

    @Test
    fun `matches the sheet for a single month`() {
        val summary = summarisePeriod(sample, month)
        assertEquals(990.0, summary.income, 0.0)
        assertEquals(200.0, summary.expenses, 0.0)
        assertEquals(1000.0, summary.plannedIncome, 0.0)
        assertEquals(300.0, summary.plannedExpenses, 0.0)
        assertEquals(790.0, summary.remainder, 0.0) // D5
        assertEquals(700.0, summary.plannedRemainder, 0.0) // D4
        assertEquals(90.0, summary.difference, 0.0) // D6
    }

    @Test
    fun `sums planned and actual across a multi-month period`() {
        val summary = summarisePeriod(sample, resolvePeriod(PeriodId.QUARTER, ANCHOR))
        assertEquals(1980.0, summary.income, 0.0)
        assertEquals(600.0, summary.expenses, 0.0)
        assertEquals(800.0, summary.plannedExpenses, 0.0) // 500 + 300
        assertEquals(1990.0, summary.plannedIncome, 0.0)
        assertEquals(1380.0, summary.remainder, 0.0)
    }

    @Test
    fun `reports a savings rate only when income exists`() {
        assertEquals(790.0 / 990.0, summarisePeriod(sample, month).savingsRate!!, 1e-9)
        assertNull(summarisePeriod(data(), month).savingsRate)
    }

    @Test
    fun `is all zeroes for an empty period, never NaN`() {
        val summary = summarisePeriod(data(), month)
        listOf(
            summary.income,
            summary.expenses,
            summary.plannedIncome,
            summary.plannedExpenses,
            summary.remainder,
            summary.plannedRemainder,
            summary.difference,
        ).forEach { assertEquals(0.0, it, 0.0) }
        assertNull(summary.savingsRate)
        assertEquals(0, summary.transactionCount)
    }
}

class CategoryBreakdownTest {

    private val sample = data(
        transactions = listOf(
            t("2026-07-05", category = "Ərzaq", amount = 100.0),
            t("2026-08-05", category = "Ərzaq", amount = 124.0),
            t("2026-08-06", category = "Əyləncə", amount = 76.0),
        ),
        budgetLines = listOf(BudgetLine("b", "2026-08", "x", "Ərzaq", 100.0)),
    )

    @Test
    fun `ranks by spend and computes each share of the total`() {
        val rows = categoryBreakdown(sample, month)
        assertEquals("Ərzaq", rows[0].category)
        assertEquals(124.0, rows[0].actual, 0.0)
        assertEquals(124.0 / 200.0, rows[0].share, 1e-9)
        assertEquals(76.0 / 200.0, rows[1].share, 1e-9)
        assertEquals(1.0, sumOf(rows.map { it.share }), 1e-9)
    }

    @Test
    fun `computes the change against the previous period`() {
        val food = categoryBreakdown(sample, month).first { it.category == "Ərzaq" }
        assertEquals(100.0, food.previous, 0.0)
        assertEquals(0.24, food.changeRatio!!, 1e-9) // 100 -> 124
    }

    @Test
    fun `leaves the change undefined when there is nothing to compare with`() {
        assertNull(categoryBreakdown(sample, month).first { it.category == "Əyləncə" }.changeRatio)
    }

    @Test
    fun `flags a category with no planned line`() {
        val rows = categoryBreakdown(sample, month)
        assertFalse(rows.first { it.category == "Ərzaq" }.unplanned)
        assertTrue(rows.first { it.category == "Əyləncə" }.unplanned)
    }

    @Test
    fun `keeps a planned category that was never spent on`() {
        val idle = data(budgetLines = listOf(BudgetLine("b", "2026-08", "x", "İdman", 40.0)))
        val row = categoryBreakdown(idle, month)[0]
        assertEquals("İdman", row.category)
        assertEquals(0.0, row.actual, 0.0)
        assertEquals(40.0, row.planned, 0.0)
        assertEquals(0.0, row.share, 0.0)
    }
}

class ExpectedSplitTest {

    @Test
    fun `splits at the planned amount and always reconciles to the total`() {
        val sample = data(
            transactions = listOf(
                t(category = "Ərzaq", amount = 124.0), // 100 planned -> 24 over
                t(category = "Əyləncə", amount = 76.0), // nothing planned -> all 76
                t(category = "İdman", amount = 30.0), // 40 planned -> fully covered
            ),
            budgetLines = listOf(
                BudgetLine("b1", "2026-08", "a", "Ərzaq", 100.0),
                BudgetLine("b2", "2026-08", "b", "İdman", 40.0),
            ),
        )
        val split = expectedSplit(sample, month)

        assertEquals(130.0, split.expected, 0.0) // 100 covered + 30 covered
        assertEquals(100.0, split.unexpected, 0.0) // 24 over + 76 unbudgeted
        assertEquals(
            summarisePeriod(sample, month).expenses,
            split.expected + split.unexpected,
            1e-9,
        )
    }

    @Test
    fun `explains each unexpected item with its reason, biggest first`() {
        val sample = data(
            transactions = listOf(
                t(category = "Ərzaq", amount = 124.0),
                t(category = "Əyləncə", amount = 76.0),
            ),
            budgetLines = listOf(BudgetLine("b1", "2026-08", "a", "Ərzaq", 100.0)),
        )
        val items = expectedSplit(sample, month).items
        assertEquals("Əyləncə", items[0].category)
        assertEquals(76.0, items[0].amount, 0.0)
        assertEquals(UnexpectedReason.NO_PLAN, items[0].reason)
        assertEquals("Ərzaq", items[1].category)
        assertEquals(24.0, items[1].amount, 0.0)
        assertEquals(UnexpectedReason.OVER_PLAN, items[1].reason)
        assertEquals(100.0, items[1].planned, 0.0)
    }

    @Test
    fun `reports nothing unexpected when everything stayed within plan`() {
        val sample = data(
            transactions = listOf(t(category = "Ərzaq", amount = 50.0)),
            budgetLines = listOf(BudgetLine("b1", "2026-08", "a", "Ərzaq", 100.0)),
        )
        val split = expectedSplit(sample, month)
        assertEquals(0.0, split.unexpected, 0.0)
        assertTrue(split.items.isEmpty())
    }
}

class FlowBucketTest {

    @Test
    fun `uses weeks for a single month and months for longer periods`() {
        val sample = data(transactions = listOf(t("2026-08-03", amount = 10.0)))
        assertEquals(4, flowBuckets(sample, month).size)
        assertEquals(3, flowBuckets(sample, resolvePeriod(PeriodId.QUARTER, ANCHOR)).size)
    }

    @Test
    fun `assigns each day to exactly one week bucket`() {
        val sample = data(
            transactions = listOf(
                t("2026-08-01", amount = 1.0),
                t("2026-08-07", amount = 2.0),
                t("2026-08-08", amount = 4.0),
                t("2026-08-21", amount = 8.0),
                t("2026-08-22", amount = 16.0),
                t("2026-08-31", amount = 32.0),
            ),
        )
        val buckets = flowBuckets(sample, month)
        assertEquals(listOf(3.0, 4.0, 8.0, 48.0), buckets.map { it.expenses })
        assertEquals(63.0, sumOf(buckets.map { it.expenses }), 0.0)
    }

    @Test
    fun `carries the opening balance in so the line starts truthfully`() {
        val sample = data(
            transactions = listOf(
                t("2026-05-01", TransactionType.INCOME, "Maaş", amount = 500.0),
                t("2026-08-02", amount = 100.0),
            ),
        )
        val buckets = flowBuckets(sample, month)
        assertEquals(400.0, buckets.first().balance, 0.0) // 500 carried in, 100 out
        assertEquals(400.0, buckets.last().balance, 0.0)
    }

    @Test
    fun `gives every month a distinct short label`() {
        // İyun and İyul share their first three letters; sliced names collide.
        val labels = (1..12).map { formatMonthShort("2026-%02d".format(it)) }
        assertEquals(12, labels.toSet().size)
    }

    @Test
    fun `labels multi-month buckets with the month name`() {
        val buckets = flowBuckets(data(), resolvePeriod(PeriodId.QUARTER, ANCHOR))
        assertEquals(listOf("İyn", "İyl", "Avq"), buckets.map { it.label })
    }
}

class DailyActivityTest {

    @Test
    fun `covers every day of the month including empty ones`() {
        assertEquals(31, dailyActivity(data(), "2026-08").size)
    }

    @Test
    fun `handles February in a leap year`() {
        assertEquals(29, dailyActivity(data(), "2024-02").size)
    }

    @Test
    fun `groups several transactions on the same day`() {
        val sample = data(
            transactions = listOf(
                t("2026-08-05", amount = 10.0),
                t("2026-08-05", amount = 15.0),
                t("2026-08-05", TransactionType.INCOME, "Maaş", amount = 100.0),
            ),
        )
        val day = dailyActivity(sample, "2026-08").first { it.day == 5 }
        assertEquals(25.0, day.expenses, 0.0)
        assertEquals(100.0, day.income, 0.0)
        assertEquals(3, day.transactions.size)
    }
}

class LargestTest {

    @Test
    fun `ranks expenses only, biggest first, and respects the limit`() {
        val sample = data(
            transactions = listOf(
                t(amount = 20.0),
                t(amount = 300.0),
                t(amount = 50.0),
                t(type = TransactionType.INCOME, category = "Maaş", amount = 990.0),
            ),
        )
        assertEquals(
            listOf(300.0, 50.0, 20.0),
            largestTransactions(sample, month).map { it.amount },
        )
        assertEquals(2, largestTransactions(sample, month, 2).size)
    }
}

class RecurringTest {

    private val sample = data(
        budgetLines = listOf(
            BudgetLine("a", "2026-07", "Ev kirəsi", "Əlavə xərclər", 230.0),
            BudgetLine("b", "2026-08", "Ev kirəsi", "Əlavə xərclər", 230.0),
            BudgetLine("c", "2026-08", "Yeni xərc", "Ərzaq", 50.0),
        ),
        transactions = listOf(
            t("2026-08-01", category = "Əlavə xərclər", description = "ev kirəsi", amount = 230.0),
        ),
    )

    @Test
    fun `counts a line as recurring only when it was planned before too`() {
        assertEquals(
            listOf("Ev kirəsi"),
            recurringCommitments(sample, "2026-08").map { it.description },
        )
    }

    @Test
    fun `matches a payment by description, ignoring case and spacing`() {
        val rent = recurringCommitments(sample, "2026-08").first()
        assertEquals(1, rent.matched.size)
        assertEquals(230.0, rent.actual, 0.0)
    }

    @Test
    fun `reports no match rather than claiming a bill is unpaid`() {
        val unmatched = data(
            budgetLines = sample.budgetLines,
            transactions = listOf(t("2026-08-01", description = "something else", amount = 230.0)),
        )
        val rent = recurringCommitments(unmatched, "2026-08").first()
        assertTrue(rent.matched.isEmpty())
        assertEquals(0.0, rent.actual, 0.0)
    }

    @Test
    fun `finds nothing in the very first month`() {
        assertTrue(recurringCommitments(sample, "2026-07").isEmpty())
    }
}

class InsightTest {

    @Test
    fun `says nothing at all when there are no transactions`() {
        assertTrue(insights(data(), month).isEmpty())
    }

    @Test
    fun `never advises, only states`() {
        val sample = data(
            transactions = listOf(
                t("2026-07-05", amount = 100.0),
                t("2026-08-05", amount = 400.0),
                t("2026-08-01", TransactionType.INCOME, "Maaş", amount = 900.0),
            ),
            budgetLines = listOf(BudgetLine("b", "2026-08", "x", "Ərzaq", 100.0)),
        )
        // Advisory language in both the app's language and the one it was
        // translated from, so the guard cannot quietly become vacuous.
        val banned = listOf(
            "çox xərcləyirsiniz",
            "azaltmalısınız",
            "məsləhət",
            "çalışın",
            "lazımdır",
            "tövsiyə",
            "should",
            "too much",
            "try to",
            "consider",
            "you need",
        )
        insights(sample, month).forEach { fact ->
            banned.forEach { phrase ->
                assertFalse(
                    "\"${fact.text}\" contains \"$phrase\"",
                    fact.text.lowercase().contains(phrase),
                )
            }
        }
    }

    @Test
    fun `reports money retained from real figures`() {
        val sample = data(
            transactions = listOf(
                t("2026-08-01", TransactionType.INCOME, "Maaş", amount = 1000.0),
                t("2026-08-05", amount = 250.0),
            ),
        )
        val kept = insights(sample, month).first { it.id == "retained" }
        assertTrue(kept.text.contains("750.00 ₼"))
    }
}

class ReconciliationTest {

    private val sample = data(
        transactions = listOf(
            t("2026-08-02", category = "Kreditlər", amount = 220.0),
            t("2026-08-05", category = "Ərzaq", amount = 63.25),
            t("2026-08-09", category = "Ərzaq", amount = 48.6),
            t("2026-08-16", category = "Əyləncə", amount = 18.4),
            t("2026-08-18", TransactionType.INCOME, "Maaş", amount = 990.0),
        ),
        budgetLines = listOf(
            BudgetLine("b1", "2026-08", "a", "Kreditlər", 220.0),
            BudgetLine("b2", "2026-08", "b", "Ərzaq", 100.0),
        ),
    )

    @Test
    fun `agrees across every view of the same period`() {
        val summary = summarisePeriod(sample, month)
        val rows = categoryBreakdown(sample, month)
        val split = expectedSplit(sample, month)
        val buckets = flowBuckets(sample, month)
        val days = dailyActivity(sample, "2026-08")

        assertEquals(350.25, summary.expenses, 0.0)
        assertEquals(summary.expenses, sumOf(rows.map { it.actual }), 0.0)
        assertEquals(summary.expenses, split.expected + split.unexpected, 1e-9)
        assertEquals(summary.expenses, sumOf(buckets.map { it.expenses }), 0.0)
        assertEquals(summary.expenses, sumOf(days.map { it.expenses }), 0.0)
        assertEquals(summary.income, sumOf(buckets.map { it.income }), 0.0)
        assertEquals(5, transactionsInPeriod(sample.transactions, month).size)
    }
}

class IncomeSourceTest {

    @Test
    fun `reports each source against its planned row`() {
        val sample = data(
            transactions = listOf(
                t(type = TransactionType.INCOME, category = "Maaş", amount = 900.0),
                t(type = TransactionType.INCOME, category = "Əlavə gəlir", amount = 100.0),
            ),
            incomePlans = listOf(
                IncomePlan(ANCHOR, mapOf("Maaş" to 990.0, "Əlavə gəlir" to 50.0)),
            ),
        )
        val rows = incomeSources(sample, month)
        assertEquals("Maaş", rows[0].category)
        assertEquals(900.0, rows[0].actual, 0.0)
        assertEquals(990.0, rows[0].planned, 0.0)
        assertEquals(0.9, rows[0].share, 1e-9)
    }

    @Test
    fun `keeps a source that arrived without a planned row`() {
        val sample = data(
            transactions = listOf(
                t(type = TransactionType.INCOME, category = "Mentorluq", amount = 300.0),
            ),
        )
        val rows = incomeSources(sample, month)
        assertEquals(1, rows.size)
        assertEquals(0.0, rows[0].planned, 0.0)
    }

    @Test
    fun `is empty when nothing was earned or planned`() {
        assertTrue(incomeSources(data(), month).isEmpty())
    }
}

class SpendingPaceTest {

    private val sample = data(
        transactions = listOf(
            t("$ANCHOR-01", amount = 100.0),
            t("$ANCHOR-02", amount = 100.0),
        ),
        budgetLines = listOf(BudgetLine("b1", ANCHOR, "p", "Ərzaq", 1000.0)),
    )

    @Test
    fun `divides by the days elapsed, not by the whole month`() {
        val pace = spendingPace(sample, ANCHOR, "$ANCHOR-10")!!
        assertEquals(10, pace.elapsed)
        assertEquals(31, pace.days)
        assertEquals(200.0, pace.spent, 0.0)
        assertEquals(20.0, pace.perDay, 0.0)
        assertEquals(620.0, pace.atThisRate, 0.0)
        assertFalse(pace.complete)
    }

    @Test
    fun `treats a month that has ended as complete, and stops extrapolating`() {
        val pace = spendingPace(sample, ANCHOR, "2026-09-04")!!
        assertEquals(31, pace.elapsed)
        assertTrue(pace.complete)
        assertEquals(200.0, pace.spent, 0.0)
        assertEquals(200.0, pace.atThisRate, 0.0)
    }

    @Test
    fun `has nothing to report for a month that has not started`() {
        assertNull(spendingPace(sample, "2026-12", "$ANCHOR-10"))
    }

    @Test
    fun `carries the plan through for comparison`() {
        assertEquals(1000.0, spendingPace(sample, ANCHOR, "$ANCHOR-10")!!.planned, 0.0)
    }
}

class WeekdayPatternTest {

    @Test
    fun `always returns seven days, Monday first`() {
        val rows = weekdayPattern(data(), month)
        assertEquals(7, rows.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), rows.map { it.weekday })
        assertTrue(rows.all { it.expenses == 0.0 && it.count == 0 })
    }

    @Test
    fun `files each expense under its own weekday`() {
        // 2026-08-03 is a Monday, 2026-08-09 a Sunday.
        val sample = data(
            transactions = listOf(
                t("2026-08-03", amount = 30.0),
                t("2026-08-10", amount = 20.0),
                t("2026-08-09", amount = 5.0),
                t("2026-08-04", TransactionType.INCOME, "Maaş", amount = 999.0),
            ),
        )
        val rows = weekdayPattern(sample, month)
        assertEquals(50.0, rows[0].expenses, 0.0)
        assertEquals(2, rows[0].count)
        assertEquals(5.0, rows[6].expenses, 0.0)
        assertEquals(1, rows[6].count)
        // Income is not spending, so Tuesday stays empty.
        assertEquals(0.0, rows[1].expenses, 0.0)
        assertEquals(0, rows[1].count)
    }

    @Test
    fun `accounts for every expense in the period`() {
        val sample = data(
            transactions = listOf(t(amount = 12.5), t("$ANCHOR-20", amount = 7.5)),
        )
        assertEquals(
            summarisePeriod(sample, month).expenses,
            sumOf(weekdayPattern(sample, month).map { it.expenses }),
            0.0,
        )
    }
}

class FrequentExpenseTest {

    @Test
    fun `groups by description, case- and space-insensitively`() {
        val sample = data(
            transactions = listOf(
                t(description = "Metro", amount = 1.0),
                t(description = "  metro ", amount = 2.0),
                t(description = "METRO", amount = 3.0),
            ),
        )
        val rows = frequentExpenses(sample, month, 5)
        assertEquals(1, rows.size)
        assertEquals("Metro", rows[0].description)
        assertEquals(3, rows[0].count)
        assertEquals(6.0, rows[0].total, 0.0)
    }

    @Test
    fun `ignores anything bought only once`() {
        val sample = data(
            transactions = listOf(
                t(description = "Metro", amount = 1.0),
                t(description = "Metro", amount = 1.0),
                t(description = "One-off", amount = 500.0),
            ),
        )
        assertEquals(
            listOf("Metro"),
            frequentExpenses(sample, month, 5).map { it.description },
        )
    }

    @Test
    fun `ranks by how often, then by how much, and honours the limit`() {
        val sample = data(
            transactions = listOf(
                t(description = "Often", amount = 1.0),
                t(description = "Often", amount = 1.0),
                t(description = "Often", amount = 1.0),
                t(description = "Big", amount = 300.0),
                t(description = "Big", amount = 300.0),
                t(description = "Small", amount = 2.0),
                t(description = "Small", amount = 2.0),
            ),
        )
        assertEquals(
            listOf("Often", "Big"),
            frequentExpenses(sample, month, 2).map { it.description },
        )
    }

    @Test
    fun `leaves income out of it`() {
        val sample = data(
            transactions = listOf(
                t(type = TransactionType.INCOME, category = "Maaş", description = "Maaş", amount = 900.0),
                t(type = TransactionType.INCOME, category = "Maaş", description = "Maaş", amount = 900.0),
            ),
        )
        assertTrue(frequentExpenses(sample, month, 5).isEmpty())
    }
}
