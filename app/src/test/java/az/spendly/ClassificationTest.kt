/**
 * The frameworks that need to know what spending is for.
 *
 * Every case here is about the same thing: what the app is allowed to say when
 * some of the spending has not been classified.
 */
package az.spendly

import az.spendly.domain.CategoryDef
import az.spendly.domain.CategoryKind
import az.spendly.domain.FinanceData
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import az.spendly.domain.insights.CLASSIFICATION_COVERAGE_MIN
import az.spendly.domain.insights.classifySpending
import az.spendly.domain.insights.emergencyFund
import az.spendly.domain.insights.fiftyThirtyTwenty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val M = "2026-08"

private val cats = listOf(
    CategoryDef("c1", "Ərzaq", TransactionType.EXPENSE, CategoryKind.ESSENTIAL),
    CategoryDef("c2", "Kirayə", TransactionType.EXPENSE, CategoryKind.ESSENTIAL),
    CategoryDef("c3", "Əyləncə", TransactionType.EXPENSE, CategoryKind.DISCRETIONARY),
    CategoryDef("c4", "Kredit", TransactionType.EXPENSE, CategoryKind.DEBT),
    CategoryDef("c5", "Yığım", TransactionType.EXPENSE, CategoryKind.SAVING),
    CategoryDef("c6", "Digər", TransactionType.EXPENSE),
    CategoryDef("i1", "Maaş", TransactionType.INCOME),
)

private var seq = 0

private fun spent(category: String, amount: Double, date: String = "$M-10"): Transaction {
    seq += 1
    return Transaction("k$seq", date, TransactionType.EXPENSE, category, "x", amount)
}

private fun earned(month: String, amount: Double): Transaction {
    seq += 1
    return Transaction("e$seq", "$month-01", TransactionType.INCOME, "Maaş", "x", amount)
}

private fun withCats(
    transactions: List<Transaction>,
    categories: List<CategoryDef> = cats,
) = FinanceData(transactions = transactions, categories = categories)

class ClassifySpendingTest {

    @Test
    fun `totals each kind and reports coverage`() {
        val data = withCats(
            listOf(
                spent("Ərzaq", 300.0),
                spent("Əyləncə", 200.0),
                spent("Kredit", 400.0),
                spent("Yığım", 100.0),
            ),
        )

        val split = classifySpending(data, M)
        assertEquals(300.0, split.essential, 0.0)
        assertEquals(200.0, split.discretionary, 0.0)
        assertEquals(400.0, split.debt, 0.0)
        assertEquals(100.0, split.saving, 0.0)
        assertEquals(0.0, split.unclassified, 0.0)
        assertEquals(1000.0, split.total, 0.0)
        assertEquals(1.0, split.coverage, 0.0)
        assertTrue(split.hasCoverage)
    }

    @Test
    fun `keeps unclassified spending in the total rather than dropping it`() {
        // Excluding it from the denominator would make every share look larger
        // than it is.
        val data = withCats(listOf(spent("Ərzaq", 700.0), spent("Digər", 300.0)))

        val split = classifySpending(data, M)
        assertEquals(1000.0, split.total, 0.0)
        assertEquals(300.0, split.unclassified, 0.0)
        assertEquals(0.7, split.coverage, 1e-9)
        assertFalse(split.hasCoverage)
        assertEquals(listOf("Digər"), split.missing)
    }

    @Test
    fun `names the unclassified categories, largest first`() {
        val data = withCats(
            transactions = listOf(spent("Digər", 50.0), spent("Başqa", 90.0)),
            categories = cats + CategoryDef("c7", "Başqa", TransactionType.EXPENSE),
        )
        assertEquals(listOf("Başqa", "Digər"), classifySpending(data, M).missing)
    }

    @Test
    fun `ignores income`() {
        val data = withCats(listOf(earned(M, 5000.0), spent("Ərzaq", 100.0)))
        assertEquals(100.0, classifySpending(data, M).total, 0.0)
    }
}

class FiftyThirtyTwentyTest {

    @Test
    fun `maps debt onto needs and counts unspent income as retained`() {
        val data = withCats(
            listOf(
                earned(M, 1000.0),
                spent("Ərzaq", 400.0),
                spent("Kredit", 100.0),
                spent("Əyləncə", 300.0),
            ),
        )

        val framework = fiftyThirtyTwenty(data, M)!!
        assertEquals(500.0, framework.needs, 0.0)
        assertEquals(300.0, framework.wants, 0.0)
        assertEquals(200.0, framework.savings, 0.0)
        assertEquals(0.5, framework.needsShare, 1e-9)
        assertEquals(0.3, framework.wantsShare, 1e-9)
        assertEquals(0.2, framework.savingsShare, 1e-9)
    }

    @Test
    fun `withholds itself when too little spending is classified`() {
        val data = withCats(listOf(earned(M, 1000.0), spent("Digər", 500.0)))
        assertNull(fiftyThirtyTwenty(data, M))
    }

    @Test
    fun `withholds itself when there is no income to take a share of`() {
        assertNull(fiftyThirtyTwenty(withCats(listOf(spent("Ərzaq", 500.0))), M))
    }

    @Test
    fun `needs at least the coverage floor, not merely most of it`() {
        val data = withCats(
            listOf(earned(M, 1000.0), spent("Ərzaq", 895.0), spent("Digər", 105.0)),
        )
        assertTrue(classifySpending(data, M).coverage < CLASSIFICATION_COVERAGE_MIN)
        assertNull(fiftyThirtyTwenty(data, M))
    }
}

class EmergencyFundTest {

    private val months = listOf("2026-06", "2026-07", "2026-08")

    @Test
    fun `takes the median of essential spending, not the mean`() {
        // One unusual month must not set a target you then chase.
        val data = withCats(
            months.map { earned(it, 2000.0) } + listOf(
                spent("Ərzaq", 500.0, "2026-06-10"),
                spent("Ərzaq", 520.0, "2026-07-10"),
                spent("Ərzaq", 2000.0, "2026-08-10"),
            ),
        )

        val fund = emergencyFund(data, M, 3)!!
        assertEquals(520.0, fund.essentialMonthly, 0.0)
        assertEquals(1560.0, fund.target, 0.0)
        assertEquals(3, fund.sampleMonths)
    }

    @Test
    fun `multiplies by the months the user chose`() {
        val data = withCats(
            months.flatMap { listOf(earned(it, 2000.0), spent("Ərzaq", 500.0, "$it-10")) },
        )
        assertEquals(1500.0, emergencyFund(data, M, 3)!!.target, 0.0)
        assertEquals(3000.0, emergencyFund(data, M, 6)!!.target, 0.0)
    }

    @Test
    fun `counts debt payments as essential, since they still have to be met`() {
        val data = withCats(
            months.flatMap {
                listOf(spent("Ərzaq", 300.0, "$it-10"), spent("Kredit", 200.0, "$it-11"))
            },
        )
        assertEquals(500.0, emergencyFund(data, M, 3)!!.essentialMonthly, 0.0)
    }

    @Test
    fun `will not estimate from fewer than three months`() {
        val data = withCats(listOf(spent("Ərzaq", 500.0, "2026-08-10")))
        assertNull(emergencyFund(data, M, 3))
    }

    @Test
    fun `skips months it cannot classify rather than understating the figure`() {
        val data = withCats(
            listOf(
                spent("Ərzaq", 500.0, "2026-06-10"),
                spent("Digər", 500.0, "2026-07-10"),
                spent("Ərzaq", 500.0, "2026-08-10"),
            ),
        )
        // Only two usable months remain, which is under the minimum.
        assertNull(emergencyFund(data, M, 3))
    }
}

class SignedRateTest {

    @Test
    fun `does not print a negative retained rate as a positive one`() {
        // A month that overspent has a negative retained rate. Showing it as
        // its magnitude turned the comparison into "16% / 16%", which says
        // nothing.
        val history = listOf("2026-05", "2026-06", "2026-07")
        val data = FinanceData(
            transactions = history.map { earned(it, 1000.0) } +
                history.map { spent("Ərzaq", 800.0, "$it-10") } +
                listOf(earned(M, 1000.0), spent("Ərzaq", 1200.0)),
            categories = cats,
        )

        val advice = az.spendly.domain.insights.budgetAdvice(data, M, "2026-08-20")
            .let { it.attention + it.good + it.review }
            .first { it.id == "retained-trend" }

        // Both sides keep their sign, so the two figures cannot read as identical.
        assertTrue(advice.fact, advice.fact.contains("-20%"))
        assertTrue(advice.fact, advice.fact.contains("20%"))
        assertTrue(advice.fact, advice.fact.contains("daha azını saxladınız"))
    }
}
