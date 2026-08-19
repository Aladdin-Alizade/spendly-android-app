/**
 * What the app does with an account that holds nothing.
 *
 * This is not an edge case any more — it is every account's first minute. A
 * new account has no categories, no plan and no transactions, so every figure
 * on all four screens is computed from an empty history. None of it may throw
 * or produce NaN: somebody who has just registered would meet a blank page
 * instead of the app they came for.
 */
package az.spendly

import az.spendly.domain.PERIODS
import az.spendly.domain.budgetGroups
import az.spendly.domain.categoryBreakdown
import az.spendly.domain.categoryTotals
import az.spendly.domain.dailyActivity
import az.spendly.domain.emptyData
import az.spendly.domain.expectedSplit
import az.spendly.domain.flowBuckets
import az.spendly.domain.frequentExpenses
import az.spendly.domain.incomeSources
import az.spendly.domain.insights
import az.spendly.domain.insights.budgetAdvice
import az.spendly.domain.insights.classifySpending
import az.spendly.domain.insights.emergencyFund
import az.spendly.domain.insights.fiftyThirtyTwenty
import az.spendly.domain.insights.fundPace
import az.spendly.domain.insights.spendingRigidity
import az.spendly.domain.knownMonths
import az.spendly.domain.largestTransactions
import az.spendly.domain.monthlyTrend
import az.spendly.domain.previousPeriod
import az.spendly.domain.recurringCommitments
import az.spendly.domain.resolvePeriod
import az.spendly.domain.runningBalance
import az.spendly.domain.spendingPace
import az.spendly.domain.summarise
import az.spendly.domain.summarisePeriod
import az.spendly.domain.transactionsInPeriod
import az.spendly.domain.weekdayPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val M = "2026-08"
private const val TODAY = "2026-08-19"

/** Every number the screens put on the page has to be a real one. */
private fun assertFinite(value: Any?) {
    when (value) {
        null -> Unit
        is Double -> assertTrue("$value", value.isFinite())
        is Float -> assertTrue("$value", value.isFinite())
        is Collection<*> -> value.forEach { assertFinite(it) }
        is Map<*, *> -> value.values.forEach { assertFinite(it) }
        else -> Unit
    }
}

class EmptyOverviewTest {

    @Test
    fun `computes every period without a figure going missing`() {
        for (option in PERIODS) {
            val period = resolvePeriod(option.id, M)

            listOf(
                summarisePeriod(emptyData, period).let {
                    listOf(
                        it.income,
                        it.expenses,
                        it.plannedIncome,
                        it.plannedExpenses,
                        it.remainder,
                        it.plannedRemainder,
                        it.difference,
                        it.savingsRate,
                    )
                },
                categoryBreakdown(emptyData, period).map { it.actual },
                listOf(expectedSplit(emptyData, period).expected),
                flowBuckets(emptyData, period).map { it.balance },
                incomeSources(emptyData, period).map { it.actual },
                insights(emptyData, period).map { it.id },
                largestTransactions(emptyData, period, 5).map { it.amount },
                weekdayPattern(emptyData, period).map { it.expenses },
                frequentExpenses(emptyData, period, 5).map { it.total },
                transactionsInPeriod(emptyData.transactions, period).map { it.amount },
                listOf(summarisePeriod(emptyData, previousPeriod(period)).remainder),
            ).forEach { assertFinite(it) }
        }
    }

    @Test
    fun `has nothing to show for the month itself`() {
        assertFinite(dailyActivity(emptyData, M).map { it.expenses })
        assertTrue(recurringCommitments(emptyData, M).isEmpty())
        // A month in progress has a rate to report even at zero; what it must
        // not do is divide by an empty history and hand back a NaN.
        val pace = spendingPace(emptyData, M, TODAY)
        assertFinite(listOf(pace?.spent, pace?.perDay, pace?.atThisRate))
        assertEquals(0.0, runningBalance(emptyData.transactions, M), 0.0)
        assertEquals(listOf(M), knownMonths(emptyData, M))
        assertEquals(1, monthlyTrend(emptyData, listOf(M)).size)
    }
}

class EmptyBudgetTest {

    @Test
    fun `shows an empty plan rather than failing to compute one`() {
        assertTrue(budgetGroups(emptyData, M).isEmpty())
        assertTrue(categoryTotals(emptyData, M).isEmpty())

        val summary = summarise(emptyData, M)
        assertEquals(0.0, summary.plannedRemainder, 0.0)
        assertEquals(0.0, summary.actualRemainder, 0.0)
        assertEquals(0.0, summary.difference, 0.0)
    }
}

class EmptyAdviceTest {

    @Test
    fun `produces a report instead of advice about nothing`() {
        val report = budgetAdvice(emptyData, M, TODAY)
        assertTrue(report.attention.isEmpty())
        assertTrue(report.good.isEmpty())
        assertTrue(report.review.isEmpty())
        assertFinite(listOf(report.health.remaining, report.health.expenses))
    }

    @Test
    fun `reports no coverage rather than dividing by an empty history`() {
        val split = classifySpending(emptyData, M)
        assertEquals(0.0, split.total, 0.0)
        assertFalse(split.hasCoverage)

        // Each of these is a framework that needs spending to measure. With
        // none, they decline to answer — which is what the screen renders as
        // "not enough to say", rather than a percentage of nothing.
        assertNull(fiftyThirtyTwenty(emptyData, M))
        assertNull(emergencyFund(emptyData, M, 3))
        assertNull(spendingRigidity(split))
        assertNull(fundPace(emptyData, M, 1000.0))
    }
}
