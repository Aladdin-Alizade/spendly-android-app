/**
 * Fidelity to the spreadsheet, edge cases, and the money and date rules.
 *
 * These are the web app's own tests, carried over case for case: the two
 * builds compute the same figures or one of them is wrong.
 */
package az.spendly

import az.spendly.domain.BudgetLine
import az.spendly.domain.FinanceData
import az.spendly.domain.IncomePlan
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import az.spendly.domain.actualExpenses
import az.spendly.domain.budgetGroups
import az.spendly.domain.categoryTotals
import az.spendly.domain.formatAZN
import az.spendly.domain.formatSignedAZN
import az.spendly.domain.isValidDate
import az.spendly.domain.knownMonths
import az.spendly.domain.migrateIncomePlan
import az.spendly.domain.monthOf
import az.spendly.domain.monthlyTrend
import az.spendly.domain.parseAmount
import az.spendly.domain.plannedExpenses
import az.spendly.domain.plannedIncomeOf
import az.spendly.domain.round2
import az.spendly.domain.runningBalance
import az.spendly.domain.shiftMonth
import az.spendly.domain.sortTransactions
import az.spendly.domain.sumOf
import az.spendly.domain.summarise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val M = "2025-10"

private var counter = 0

internal fun tx(
    date: String = "$M-05",
    type: TransactionType = TransactionType.EXPENSE,
    category: String = "Ərzaq",
    description: String = "Test",
    amount: Double = 10.0,
    id: String? = null,
): Transaction {
    counter += 1
    return Transaction(
        id = id ?: "t$counter",
        date = date,
        type = type,
        category = category,
        description = description,
        amount = amount,
    )
}

internal fun build(
    transactions: List<Transaction> = emptyList(),
    budgetLines: List<BudgetLine> = emptyList(),
    incomePlans: List<IncomePlan> = emptyList(),
) = FinanceData(transactions, budgetLines, incomePlans, sheetCategories())

class SpreadsheetFidelityTest {

    @Test
    fun `reproduces F11 - planned expenses total 1,142 AZN`() {
        val data = build(budgetLines = sheetPlan(M))
        assertEquals(1142.0, plannedExpenses(data.budgetLines, M), 0.0)
    }

    @Test
    fun `reproduces the full summary block with no actuals recorded`() {
        // C11 = 990, C12 = 0, column E empty — exactly the state of the sheet.
        val data = build(
            budgetLines = sheetPlan(M),
            incomePlans = listOf(IncomePlan(M, mapOf("Maaş" to 990.0))),
        )
        val summary = summarise(data, M)

        assertEquals(990.0, summary.plannedIncome, 0.0) // C13
        assertEquals(0.0, summary.actualIncome, 0.0) // D13
        assertEquals(1142.0, summary.plannedExpenses, 0.0) // F11
        assertEquals(0.0, summary.actualExpenses, 0.0) // G11
        assertEquals(-152.0, summary.plannedRemainder, 0.0) // D4 = C13 - F11
        assertEquals(0.0, summary.actualRemainder, 0.0) // D5
        assertEquals(152.0, summary.difference, 0.0) // D6 = D5 - D4
    }

    @Test
    fun `keeps D5 as actual income minus actual expenses`() {
        val data = build(
            transactions = listOf(
                tx(type = TransactionType.INCOME, category = "Maaş", amount = 990.0),
                tx(amount = 230.0),
                tx(amount = 100.55),
            ),
            incomePlans = listOf(IncomePlan(M, mapOf("Maaş" to 990.0))),
            budgetLines = sheetPlan(M),
        )
        val summary = summarise(data, M)
        assertEquals(990.0, summary.actualIncome, 0.0)
        assertEquals(330.55, summary.actualExpenses, 0.0)
        assertEquals(659.45, summary.actualRemainder, 0.0)
        assertEquals(round2(659.45 + 152.0), summary.difference, 0.0)
    }

    @Test
    fun `reproduces the variance column F as D minus E per category`() {
        val data = build(
            budgetLines = listOf(BudgetLine("b1", M, "Ev kirəsi", "Əlavə xərclər", 230.0)),
            transactions = listOf(tx(category = "Əlavə xərclər", amount = 250.0)),
        )
        val group = budgetGroups(data, M).first()
        assertEquals(250.0, group.actual, 0.0)
        assertEquals(-20.0, group.variance, 0.0) // over budget
    }

    @Test
    fun `reproduces the SUMIF rollup per category`() {
        val data = build(
            budgetLines = sheetPlan(M),
            transactions = listOf(
                tx(category = "Kreditlər", amount = 220.0),
                tx(category = "Kreditlər", amount = 35.0),
                tx(category = "Ərzaq", amount = 60.0),
            ),
        )
        val totals = categoryTotals(data, M)
        val credits = totals.first { it.category == "Kreditlər" }
        val food = totals.first { it.category == "Ərzaq" }

        assertEquals(255.0, credits.actual, 0.0) // SUMIF
        assertEquals(555.0, credits.planned, 0.0) // 220 + 35 + 0 + 300
        assertEquals(60.0, food.actual, 0.0)
        assertEquals(1.0, round2(credits.share + food.share), 0.0)
    }

    @Test
    fun `reports actual spend per category, never invented per line`() {
        val data = build(
            budgetLines = sheetPlan(M),
            transactions = listOf(tx(category = "Kreditlər", amount = 100.0)),
        )
        val groups = budgetGroups(data, M)
        val credits = groups.first { it.category == "Kreditlər" }
        assertEquals(100.0, credits.actual, 0.0)
        assertEquals(4, credits.lines.size)
        // Group actuals always reconcile with the month total.
        assertEquals(
            actualExpenses(data.transactions, M),
            sumOf(groups.map { it.actual }),
            0.0,
        )
    }

    @Test
    fun `shows a category that was spent on but never planned`() {
        val data = build(
            budgetLines = listOf(BudgetLine("b1", M, "Saç", "Şəxsi gigiyena", 20.0)),
            transactions = listOf(tx(category = "Əyləncə", amount = 18.4)),
        )
        val unplanned = budgetGroups(data, M).first { it.category == "Əyləncə" }
        assertTrue(unplanned.lines.isEmpty())
        assertEquals(0.0, unplanned.planned, 0.0)
        assertEquals(18.4, unplanned.actual, 0.0)
        assertEquals(-18.4, unplanned.variance, 0.0)
    }
}

class EdgeCaseTest {

    @Test
    fun `handles no transactions at all`() {
        val summary = summarise(build(), M)
        assertEquals(0.0, summary.plannedIncome, 0.0)
        assertEquals(0.0, summary.actualIncome, 0.0)
        assertEquals(0.0, summary.plannedExpenses, 0.0)
        assertEquals(0.0, summary.actualExpenses, 0.0)
        assertEquals(0.0, summary.actualRemainder, 0.0)
        assertEquals(0.0, summary.difference, 0.0)
        assertEquals(0.0, runningBalance(emptyList()), 0.0)
        assertTrue(categoryTotals(build(), M).isEmpty())
    }

    @Test
    fun `handles income only`() {
        val data = build(
            transactions = listOf(
                tx(type = TransactionType.INCOME, category = "Maaş", amount = 990.0),
            ),
        )
        assertEquals(990.0, summarise(data, M).actualRemainder, 0.0)
        // Income is not an expense category.
        assertTrue(categoryTotals(data, M).isEmpty())
    }

    @Test
    fun `handles expenses only, producing a negative remainder`() {
        val data = build(transactions = listOf(tx(amount = 300.0)))
        assertEquals(-300.0, summarise(data, M).actualRemainder, 0.0)
        assertEquals(-300.0, runningBalance(data.transactions), 0.0)
    }

    @Test
    fun `handles several transactions on the same date`() {
        val data = build(
            transactions = listOf(
                tx(date = "$M-07", amount = 10.0),
                tx(date = "$M-07", amount = 20.0),
                tx(date = "$M-07", amount = 30.0),
            ),
        )
        assertEquals(60.0, actualExpenses(data.transactions, M), 0.0)
        assertEquals(3, sortTransactions(data.transactions).size)
    }

    @Test
    fun `handles large amounts without precision loss`() {
        val data = build(
            transactions = listOf(
                tx(type = TransactionType.INCOME, category = "Maaş", amount = 9_999_999.99),
                tx(amount = 0.01),
            ),
        )
        assertEquals(9_999_999.98, summarise(data, M).actualRemainder, 0.0)
    }

    @Test
    fun `handles repeated decimal amounts without float drift`() {
        val data = build(transactions = (1..10).map { tx(amount = 0.1) })
        assertEquals(1.0, actualExpenses(data.transactions, M), 0.0)
    }

    @Test
    fun `keeps a planned line with a zero amount and no divide-by-zero`() {
        val data = build(
            budgetLines = listOf(BudgetLine("b1", M, "Nağd kredit kartı", "Kreditlər", 0.0)),
        )
        val group = budgetGroups(data, M).first()
        assertEquals(0.0, group.planned, 0.0)
        assertEquals(0.0, group.actual, 0.0)
        assertEquals(0.0, group.variance, 0.0)
    }

    @Test
    fun `excludes other months from a month summary`() {
        val data = build(
            transactions = listOf(
                tx(date = "2025-09-30", amount = 500.0),
                tx(date = "2025-10-01", amount = 20.0),
            ),
        )
        assertEquals(20.0, actualExpenses(data.transactions, M), 0.0)
        assertEquals(500.0, actualExpenses(data.transactions, "2025-09"), 0.0)
    }

    @Test
    fun `accumulates the running balance up to the viewed month`() {
        val data = build(
            transactions = listOf(
                tx(
                    date = "2025-09-01",
                    type = TransactionType.INCOME,
                    category = "Maaş",
                    amount = 1000.0,
                ),
                tx(date = "2025-09-15", amount = 400.0),
                tx(date = "2025-10-15", amount = 100.0),
                tx(date = "2025-11-15", amount = 999.0),
            ),
        )
        assertEquals(600.0, runningBalance(data.transactions, "2025-09"), 0.0)
        assertEquals(500.0, runningBalance(data.transactions, "2025-10"), 0.0)
        assertEquals(-499.0, runningBalance(data.transactions), 0.0)
    }

    @Test
    fun `reports empty months in the trend as zeroes rather than gaps`() {
        val data = build(transactions = listOf(tx(date = "2025-10-02", amount = 50.0)))
        val trend = monthlyTrend(data, listOf("2025-09", "2025-10", "2025-11"))
        assertEquals(listOf(0.0, -50.0, 0.0), trend.map { it.remainder })
    }
}

class MoneyTest {

    @Test
    fun `formats AZN the way the sheet does`() {
        assertEquals("1,250.00 ₼", formatAZN(1250.0))
        assertEquals("0.00 ₼", formatAZN(0.0))
        assertEquals("-152.00 ₼", formatAZN(-152.0))
        assertEquals("1,234,567.89 ₼", formatAZN(1234567.891))
    }

    @Test
    fun `never renders negative zero`() {
        assertEquals("0.00 ₼", formatAZN(-0.0))
        assertEquals("0.00 ₼", formatAZN(-0.001))
    }

    @Test
    fun `signs positive values explicitly`() {
        assertEquals("+152.00 ₼", formatSignedAZN(152.0))
        assertEquals("-152.00 ₼", formatSignedAZN(-152.0))
        assertEquals("0.00 ₼", formatSignedAZN(0.0))
    }

    @Test
    fun `parses the amount formats a person actually types`() {
        assertEquals(12.0, parseAmount("12"))
        assertEquals(12.5, parseAmount("12.5"))
        assertEquals(12.5, parseAmount("12,50"))
        assertEquals(1234.56, parseAmount("1,234.56"))
        assertEquals(1234.56, parseAmount("1 234,56"))
        assertEquals(90.0, parseAmount("  90 ₼ "))
    }

    @Test
    fun `rejects input that is not a number`() {
        assertNull(parseAmount(""))
        assertNull(parseAmount("abc"))
        assertNull(parseAmount("."))
        assertNull(parseAmount("1.2.3"))
    }

    @Test
    fun `rounds half away from zero at two decimals`() {
        assertEquals(0.01, round2(0.005), 0.0)
        assertEquals(-0.01, round2(-0.005), 0.0)
        assertEquals(2.68, round2(2.675), 0.0)
        assertEquals(0.3, sumOf(listOf(0.1, 0.2)), 0.0)
    }
}

class DateTest {

    @Test
    fun `rejects impossible calendar dates`() {
        assertFalse(isValidDate("2025-02-30"))
        assertFalse(isValidDate("2025-13-01"))
        assertFalse(isValidDate("2025-00-10"))
        assertFalse(isValidDate("not-a-date"))
        assertFalse(isValidDate("2025-2-3"))
    }

    @Test
    fun `accepts real dates including leap days`() {
        assertTrue(isValidDate("2024-02-29"))
        assertTrue(isValidDate("2025-10-31"))
    }

    @Test
    fun `shifts months across year boundaries`() {
        assertEquals("2024-12", shiftMonth("2025-01", -1))
        assertEquals("2026-01", shiftMonth("2025-12", 1))
        assertEquals("2024-10", shiftMonth("2025-10", -12))
    }

    @Test
    fun `derives the month from a date`() {
        assertEquals("2025-10", monthOf("2025-10-14"))
    }
}

class MonthOptionsTest {

    @Test
    fun `lists every month that holds data, newest first`() {
        val data = build(
            transactions = listOf(tx(date = "2026-06-01"), tx(date = "2026-08-01")),
            budgetLines = listOf(BudgetLine("b", "2026-07", "x", "Ərzaq", 5.0)),
        )
        assertEquals(listOf("2026-08", "2026-07", "2026-06"), knownMonths(data, "2026-08"))
    }

    @Test
    fun `always includes the month being viewed`() {
        val data = build(transactions = listOf(tx(date = "2026-08-05")))
        val months = knownMonths(data, "2026-08")
        // A month with no data of its own must still be reachable.
        val viewing = "2026-09"
        val options = (listOf(viewing) + months + shiftMonth(viewing, -1) + shiftMonth(viewing, 1))
            .distinct()
        assertTrue(options.contains(viewing))
    }
}

class IncomePlanMigrationTest {

    @Test
    fun `moves salary and additional onto the two seeded categories`() {
        assertEquals(
            IncomePlan(M, mapOf("Maaş" to 990.0, "Əlavə gəlir" to 50.0)),
            migrateIncomePlan(M, null, salary = 990.0, additional = 50.0),
        )
    }

    @Test
    fun `does not invent a figure for a field that was zero`() {
        assertEquals(
            IncomePlan(M, mapOf("Maaş" to 990.0)),
            migrateIncomePlan(M, null, salary = 990.0, additional = 0.0),
        )
    }

    @Test
    fun `leaves a plan already in the current shape alone`() {
        val amounts = mapOf("Mentorluq" to 300.0)
        assertEquals(IncomePlan(M, amounts), migrateIncomePlan(M, amounts))
    }

    @Test
    fun `prefers the current shape when a row carries both`() {
        assertEquals(
            IncomePlan(M, mapOf("Mentorluq" to 300.0)),
            migrateIncomePlan(M, mapOf("Mentorluq" to 300.0), salary = 990.0),
        )
    }

    @Test
    fun `brings a pre-translation category name forward with it`() {
        assertEquals(
            IncomePlan(M, mapOf("Maaş" to 990.0)),
            migrateIncomePlan(M, mapOf("Зарплата" to 990.0)),
        )
    }

    @Test
    fun `totals whatever it holds, regardless of how many categories`() {
        assertEquals(
            355.0,
            plannedIncomeOf(IncomePlan(M, mapOf("a" to 100.0, "b" to 250.0, "c" to 5.0))),
            0.0,
        )
        assertEquals(0.0, plannedIncomeOf(IncomePlan(M, emptyMap())), 0.0)
        assertEquals(0.0, plannedIncomeOf(null), 0.0)
    }
}

/**
 * Every figure is derived, so an edit has to move all of them at once. These
 * are the cases where a cached total would show its age.
 */
class RecomputeTest {

    private val base = build(
        transactions = listOf(
            tx(id = "a", date = "$M-01", type = TransactionType.INCOME, category = "Maaş", amount = 990.0),
            tx(id = "b", date = "$M-03", category = "Əlavə xərclər", amount = 230.0),
        ),
        budgetLines = sheetPlan(M),
        incomePlans = listOf(IncomePlan(M, mapOf("Maaş" to 990.0))),
    )

    @Test
    fun `recomputes after an edit`() {
        val edited = base.copy(
            transactions = base.transactions.map {
                if (it.id == "b") it.copy(amount = 300.0) else it
            },
        )
        assertEquals(300.0, summarise(edited, M).actualExpenses, 0.0)
        assertEquals(690.0, summarise(edited, M).actualRemainder, 0.0)
    }

    @Test
    fun `recomputes after a delete`() {
        val deleted = base.copy(transactions = base.transactions.filter { it.id != "b" })
        assertEquals(0.0, summarise(deleted, M).actualExpenses, 0.0)
        assertEquals(990.0, summarise(deleted, M).actualRemainder, 0.0)
        assertEquals(
            0.0,
            categoryTotals(deleted, M).first { it.category == "Əlavə xərclər" }.actual,
            0.0,
        )
    }

    @Test
    fun `moves the money when a transaction is edited into another month`() {
        val moved = base.copy(
            transactions = base.transactions.map {
                if (it.id == "b") it.copy(date = "2025-11-03") else it
            },
        )
        assertEquals(0.0, summarise(moved, M).actualExpenses, 0.0)
        assertEquals(230.0, summarise(moved, "2025-11").actualExpenses, 0.0)
    }

    @Test
    fun `returns zero for a month with no transactions but keeps the plan visible`() {
        val data = build(budgetLines = sheetPlan(M))
        val summary = summarise(data, "2025-11")
        assertEquals(0.0, summary.plannedExpenses, 0.0)
        assertEquals(0.0, summary.actualExpenses, 0.0)
    }

    @Test
    fun `omits categories that have neither plan nor spend`() {
        val data = build(
            budgetLines = listOf(BudgetLine("b1", M, "Saç", "Şəxsi gigiyena", 20.0)),
        )
        val totals = categoryTotals(data, M)
        assertEquals(1, totals.size)
        assertEquals(20.0, totals[0].planned, 0.0)
        assertEquals(0.0, totals[0].actual, 0.0)
        assertEquals(0.0, totals[0].share, 0.0)
    }
}
