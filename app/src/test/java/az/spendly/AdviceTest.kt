/**
 * The advice engine. Every rule either fires or does not, so every case here
 * is a pair of figures and the sentence they must or must not produce.
 */
package az.spendly

import az.spendly.domain.BudgetLine
import az.spendly.domain.FinanceData
import az.spendly.domain.IncomePlan
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import az.spendly.domain.defaultCategories
import az.spendly.domain.insights.Advice
import az.spendly.domain.insights.AdvicePriority
import az.spendly.domain.insights.AdviceReport
import az.spendly.domain.insights.MethodId
import az.spendly.domain.insights.budgetAdvice
import az.spendly.domain.insights.median
import az.spendly.domain.insights.robustScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val M = "2026-08"
private const val TODAY = "2026-08-20"

private var n = 0

private fun income(month: String, amount: Double): Transaction {
    n += 1
    return Transaction("i$n", "$month-01", TransactionType.INCOME, "Maaş", "Test", amount)
}

private fun spend(month: String, category: String, amount: Double): Transaction {
    n += 1
    return Transaction("s$n", "$month-10", TransactionType.EXPENSE, category, "Test", amount)
}

private fun line(
    month: String = M,
    category: String = "Ərzaq",
    planned: Double = 100.0,
    description: String = "Plan",
): BudgetLine {
    n += 1
    return BudgetLine("b$n", month, description, category, planned)
}

private fun financeData(
    transactions: List<Transaction> = emptyList(),
    budgetLines: List<BudgetLine> = emptyList(),
    incomePlans: List<IncomePlan> = emptyList(),
) = FinanceData(transactions, budgetLines, incomePlans, defaultCategories())

private fun AdviceReport.all(): List<Advice> = attention + good + review
private fun AdviceReport.ids(): List<String> = all().map { it.id }

class BudgetHealthTest {

    @Test
    fun `reports the month arithmetic`() {
        val data = build(
            transactions = listOf(income(M, 3000.0), spend(M, "Ərzaq", 2400.0)),
            budgetLines = listOf(line(planned = 2000.0)),
        )

        val health = budgetAdvice(data, M, TODAY).health
        assertEquals(3000.0, health.income, 0.0)
        assertEquals(2400.0, health.expenses, 0.0)
        assertEquals(600.0, health.remaining, 0.0)
        assertEquals(2000.0, health.plannedExpenses, 0.0)
        assertEquals(400.0, health.planVariance!!, 0.0)
        assertEquals(0.8, health.spendingRatio!!, 1e-9)
        assertEquals(0.2, health.retainedRate!!, 1e-9)
    }

    @Test
    fun `leaves the ratios null rather than dividing by nothing`() {
        val health = budgetAdvice(
            build(transactions = listOf(spend(M, "Ərzaq", 50.0))),
            M,
            TODAY,
        ).health
        assertNull(health.spendingRatio)
        assertNull(health.retainedRate)
        assertNull(health.planVariance)
    }
}

class AdviceSilenceTest {

    @Test
    fun `says nothing at all for an empty month`() {
        assertTrue(budgetAdvice(build(), M, TODAY).all().isEmpty())
    }

    @Test
    fun `records why each rule stayed silent`() {
        val report = budgetAdvice(build(), M, TODAY)
        val methods = report.unavailable.map { it.method }
        assertTrue(methods.contains(MethodId.SPENDING_RATIO))
        assertTrue(methods.contains(MethodId.VARIANCE))
        assertTrue(methods.contains(MethodId.ANOMALY))
        assertTrue(report.unavailable.all { it.reason.isNotEmpty() })
    }

    @Test
    fun `gives no plan advice when there is no plan`() {
        val data = build(transactions = listOf(income(M, 1000.0), spend(M, "Ərzaq", 400.0)))
        assertFalse(budgetAdvice(data, M, TODAY).ids().contains("total-variance"))
    }
}

class VarianceAdviceTest {

    @Test
    fun `reports a category over its plan, with the amount`() {
        val data = build(
            transactions = listOf(income(M, 1000.0), spend(M, "Ərzaq", 420.0)),
            budgetLines = listOf(line(planned = 300.0)),
        )

        val advice = budgetAdvice(data, M, TODAY).all().first { it.id == "variance-Ərzaq" }
        assertEquals(AdvicePriority.ATTENTION, advice.priority)
        assertTrue(advice.fact.contains("120.00 ₼"))
    }

    @Test
    fun `ignores a variance too small to matter`() {
        val data = build(
            transactions = listOf(income(M, 1000.0), spend(M, "Ərzaq", 302.0)),
            budgetLines = listOf(line(planned = 300.0)),
        )
        assertFalse(budgetAdvice(data, M, TODAY).ids().contains("variance-Ərzaq"))
    }

    @Test
    fun `treats coming in under plan as a good thing`() {
        val data = build(
            transactions = listOf(income(M, 1000.0), spend(M, "Ərzaq", 200.0)),
            budgetLines = listOf(line(planned = 300.0)),
        )
        val advice = budgetAdvice(data, M, TODAY).all().first { it.id == "variance-Ərzaq" }
        assertEquals(AdvicePriority.GOOD, advice.priority)
    }

    @Test
    fun `does not repeat the health figures as advice when nothing is wrong`() {
        // Income and the retained rate are already displayed above the list.
        val data = build(
            transactions = listOf(income(M, 1000.0), spend(M, "Ərzaq", 200.0)),
            budgetLines = listOf(line(planned = 300.0)),
        )
        val found = budgetAdvice(data, M, TODAY).ids()
        assertFalse(found.contains("spending-ratio"))
        assertFalse(found.contains("retained"))
    }

    @Test
    fun `does raise them when the month does not pay for itself`() {
        val data = build(transactions = listOf(income(M, 100.0), spend(M, "Ərzaq", 400.0)))
        val attention = budgetAdvice(data, M, TODAY).attention.map { it.id }
        assertTrue(attention.contains("spending-ratio"))
        assertTrue(attention.contains("retained"))
    }
}

class RepeatedOverrunTest {

    private val months = listOf("2026-05", "2026-06", "2026-07", "2026-08")

    @Test
    fun `fires at three of the last four months`() {
        val data = build(
            transactions = months.map { income(it, 1000.0) } +
                months.mapIndexed { index, month ->
                    spend(month, "Ərzaq", if (index == 1) 80.0 else 150.0)
                },
            budgetLines = months.map { line(month = it, planned = 100.0) },
        )

        val advice = budgetAdvice(data, "2026-08", TODAY).all()
            .first { it.id == "repeated-Ərzaq" }
        assertEquals(AdvicePriority.ATTENTION, advice.priority)
        assertTrue(advice.fact.contains("4 ayın 3"))
    }

    @Test
    fun `stays quiet at two of four`() {
        val data = build(
            transactions = months.map { income(it, 1000.0) } +
                months.mapIndexed { index, month ->
                    spend(month, "Ərzaq", if (index < 2) 150.0 else 80.0)
                },
            budgetLines = months.map { line(month = it, planned = 100.0) },
        )
        assertFalse(budgetAdvice(data, "2026-08", TODAY).ids().contains("repeated-Ərzaq"))
    }
}

class AnomalyTest {

    private val steady = listOf("2026-04", "2026-05", "2026-06", "2026-07")

    @Test
    fun `flags a month far outside the usual range`() {
        val usual = listOf(150.0, 145.0, 155.0, 150.0)
        val data = build(
            transactions = steady.map { income(it, 1000.0) } +
                steady.mapIndexed { index, month -> spend(month, "Nəqliyyat", usual[index]) } +
                listOf(income(M, 1000.0), spend(M, "Nəqliyyat", 350.0)),
        )

        val advice = budgetAdvice(data, M, TODAY).all()
            .firstOrNull { it.id == "anomaly-Nəqliyyat" }
        assertNotNull(advice)
        assertTrue(advice!!.fact.contains("350.00 ₼"))
    }

    @Test
    fun `does not flag a month inside the usual variation`() {
        val data = build(
            transactions = steady.flatMapIndexed { index: Int, month: String ->
                listOf(income(month, 1000.0), spend(month, "Nəqliyyat", 140.0 + index * 10))
            } + listOf(income(M, 1000.0), spend(M, "Nəqliyyat", 165.0)),
        )
        assertFalse(budgetAdvice(data, M, TODAY).ids().contains("anomaly-Nəqliyyat"))
    }

    @Test
    fun `will not run on too little history`() {
        val data = build(
            transactions = listOf(
                income(M, 1000.0),
                spend(M, "Nəqliyyat", 999.0),
                spend("2026-07", "Nəqliyyat", 10.0),
            ),
        )
        val report = budgetAdvice(data, M, TODAY)
        assertFalse(report.ids().contains("anomaly-Nəqliyyat"))
        assertTrue(report.unavailable.any { it.method == MethodId.ANOMALY })
    }
}

class RobustStatisticsTest {

    @Test
    fun `takes the median of both odd and even runs`() {
        assertEquals(2.0, median(listOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(2.5, median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
        assertEquals(0.0, median(emptyList()), 0.0)
    }

    @Test
    fun `is not dragged by the outlier it is looking for`() {
        // The mean of this history is pulled upward by the 900; the median is not.
        val history = listOf(100.0, 105.0, 98.0, 102.0, 900.0)
        assertEquals(102.0, median(history), 0.0)
        assertTrue(robustScore(101.0, history)!! < 2.5)
    }

    @Test
    fun `treats an unprecedented value against a flat history as unbounded`() {
        // Four identical months have no spread; a fifth at 500 is still the
        // case the rule exists for, so it must not be silently skipped.
        val flat = listOf(100.0, 100.0, 100.0, 100.0)
        assertEquals(Double.POSITIVE_INFINITY, robustScore(500.0, flat))
        assertNull(robustScore(100.0, flat))
        assertNull(robustScore(1.0, emptyList()))
    }
}

class ZeroBasedTest {

    @Test
    fun `names planned income that has no job`() {
        val data = build(
            incomePlans = listOf(IncomePlan(M, mapOf("Maaş" to 1000.0))),
            budgetLines = listOf(line(planned = 700.0)),
        )
        val advice = budgetAdvice(data, M, TODAY).all().first { it.id == "zero-based" }
        assertTrue(advice.fact.contains("300.00 ₼"))
        assertEquals(AdvicePriority.REVIEW, advice.priority)
    }

    @Test
    fun `flags a plan that spends more than it expects to earn`() {
        val data = build(
            incomePlans = listOf(IncomePlan(M, mapOf("Maaş" to 700.0))),
            budgetLines = listOf(line(planned = 1000.0)),
        )
        val advice = budgetAdvice(data, M, TODAY).all().first { it.id == "zero-based" }
        assertEquals(AdvicePriority.ATTENTION, advice.priority)
    }
}

class SinkingFundTest {

    @Test
    fun `divides a future planned expense across the months until it`() {
        val data = build(
            budgetLines = listOf(
                line(month = "2027-02", description = "Sığorta", planned = 600.0),
            ),
        )

        val advice = budgetAdvice(data, M, TODAY).all().first { it.id.startsWith("sinking-") }
        assertTrue(advice.fact.contains("600.00 ₼"))
        assertTrue(advice.suggestion!!.contains("100.00 ₼")) // 600 over six months
    }

    @Test
    fun `ignores past and current months`() {
        val data = build(budgetLines = listOf(line(month = M, planned = 600.0)))
        assertFalse(budgetAdvice(data, M, TODAY).ids().any { it.startsWith("sinking-") })
    }
}

class PrioritisationTest {

    @Test
    fun `caps each bucket at three`() {
        val months = listOf("2026-05", "2026-06", "2026-07", "2026-08")
        val categories = listOf("Ərzaq", "Nəqliyyat", "İdman", "Təhsil", "Əyləncə")
        val data = build(
            transactions = months.flatMap { month ->
                listOf(income(month, 5000.0)) +
                    categories.map { spend(month, it, if (month == M) 500.0 else 100.0) }
            },
            budgetLines = months.flatMap { month ->
                categories.map { line(month = month, category = it, planned = 100.0) }
            },
        )

        val report = budgetAdvice(data, M, TODAY)
        assertTrue(report.attention.size <= 3)
        assertTrue(report.good.size <= 3)
        assertTrue(report.review.size <= 3)
    }

    @Test
    fun `ranks by manat at stake, not by percentage`() {
        val data = build(
            transactions = listOf(
                income(M, 5000.0),
                spend(M, "Ərzaq", 700.0),
                spend(M, "İdman", 20.0),
            ),
            budgetLines = listOf(
                line(category = "Ərzaq", planned = 500.0),
                line(category = "İdman", planned = 10.0),
            ),
        )

        // İdman is 100% over; Ərzaq is 40% over but by 200 ₼. Between the two
        // category findings, the manat amount decides.
        val order = budgetAdvice(data, M, TODAY).attention
            .map { it.id }
            .filter { it.startsWith("variance-") }
        assertEquals(listOf("variance-Ərzaq", "variance-İdman"), order)
    }
}

class AdviceLanguageTest {

    @Test
    fun `never instructs, only observes or suggests`() {
        val months = listOf("2026-05", "2026-06", "2026-07", "2026-08")
        val data = build(
            transactions = months.flatMap { listOf(income(it, 1000.0), spend(it, "Ərzaq", 400.0)) } +
                listOf(spend(M, "Nəqliyyat", 300.0)),
            budgetLines = months.map { line(month = it, planned = 200.0) },
            incomePlans = listOf(IncomePlan(M, mapOf("Maaş" to 1000.0))),
        )

        val instructing = Regex("məcbur|mütləq|etməlisiniz|olmalısınız", RegexOption.IGNORE_CASE)
        val suggesting = Regex("dəyər|düşünməyə|yoxlamağa|keçirməyə")

        for (advice in budgetAdvice(data, M, TODAY).all()) {
            assertFalse(advice.fact, instructing.containsMatchIn(advice.fact))
            advice.suggestion?.let {
                assertTrue(it, suggesting.containsMatchIn(it))
            }
        }
    }
}

class OneSubjectPerBucketTest {

    @Test
    fun `keeps only the largest finding for a category`() {
        // Ərzaq is simultaneously over plan, repeatedly over plan, and
        // unusually high. Three slots spent on one category hide the rest of
        // the month.
        val months = listOf("2026-05", "2026-06", "2026-07", "2026-08")
        val data = build(
            transactions = months.map { income(it, 2000.0) } +
                months.map { spend(it, "Ərzaq", if (it == M) 400.0 else 150.0) } +
                months.map { spend(it, "Nəqliyyat", if (it == M) 260.0 else 40.0) },
            budgetLines = months.flatMap {
                listOf(
                    line(month = it, category = "Ərzaq", planned = 100.0),
                    line(month = it, category = "Nəqliyyat", planned = 40.0),
                )
            },
        )

        val subjects = budgetAdvice(data, M, TODAY).attention.map { it.subject }
        assertEquals(subjects.size, subjects.toSet().size)
        assertTrue(subjects.contains("Ərzaq"))
        assertTrue(subjects.contains("Nəqliyyat"))
    }
}
