/**
 * Reading a snapshot back.
 *
 * The interesting case is a snapshot written before categories were records of
 * their own: its rows name the categories it used, and that is where they come
 * back from. Nothing is invented, so a snapshot with no rows stays empty —
 * which is exactly what a new account is.
 */
package az.spendly

import az.spendly.domain.CategoryDef
import az.spendly.domain.FinanceData
import az.spendly.domain.IncomePlan
import az.spendly.domain.SavingsDirection
import az.spendly.domain.SavingsEntry
import az.spendly.domain.SavingsPlan
import az.spendly.domain.SavingsPot
import az.spendly.domain.SavingsSource
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import az.spendly.domain.emptyData
import az.spendly.domain.migrateIncomePlan
import az.spendly.domain.normaliseData
import az.spendly.domain.plannedSavings
import az.spendly.domain.savingsBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredSnapshotTest {

    @Test
    fun `carries the savings across, which is the whole point of storing them`() {
        val stored = FinanceData(
            savingsPots = listOf(SavingsPot("p1", "  Ehtiyat fondu  ", target = 3000.0)),
            savingsEntries = listOf(
                SavingsEntry("e1", "2026-08-05", "Ehtiyat fondu", 400.0, SavingsDirection.IN),
            ),
            savingsPlans = listOf(SavingsPlan("2026-08", mapOf("Ehtiyat fondu" to 400.0))),
        )

        val read = normaliseData(stored)
        assertEquals(1, read.savingsPots.size)
        assertEquals("Ehtiyat fondu", read.savingsPots[0].name)
        assertEquals(3000.0, read.savingsPots[0].target!!, 0.0)
        assertEquals(400.0, savingsBalance(read.savingsEntries), 0.0)
        assertEquals(400.0, plannedSavings(read.savingsPlans, "2026-08"), 0.0)
    }

    @Test
    fun `reads a target of zero and a plan of nothing as nothing planned`() {
        val stored = FinanceData(
            savingsPots = listOf(SavingsPot("p1", "Ehtiyat fondu", target = 0.0)),
            savingsPlans = listOf(SavingsPlan("2026-08", mapOf("Ehtiyat fondu" to 0.0))),
        )

        val read = normaliseData(stored)
        assertNull(read.savingsPots[0].target)
        assertTrue(read.savingsPlans[0].amounts.isEmpty())
    }

    @Test
    fun `strips a source from a withdrawal, which has none to give`() {
        val stored = FinanceData(
            savingsEntries = listOf(
                SavingsEntry(
                    id = "e1",
                    date = "2026-08-05",
                    pot = "Ehtiyat fondu",
                    amount = 150.0,
                    direction = SavingsDirection.OUT,
                    source = SavingsSource.INCOME,
                ),
            ),
        )
        assertNull(normaliseData(stored).savingsEntries[0].source)
    }

    @Test
    fun `leaves an emptied category list empty`() {
        assertTrue(normaliseData(emptyData).categories.isEmpty())
    }

    @Test
    fun `recovers the categories of a snapshot saved before they were stored`() {
        // Written when the category list was a hard-coded constant, so the rows
        // name their categories but no list came with them.
        val legacy = FinanceData(
            transactions = listOf(
                Transaction(
                    id = "t1",
                    date = "2026-08-05",
                    type = TransactionType.EXPENSE,
                    category = "Ərzaq",
                    description = "Bazarlıq",
                    amount = 40.0,
                ),
            ),
            incomePlans = listOf(migrateIncomePlan("2026-08", null, salary = 990.0)),
        )

        assertEquals(
            listOf(
                CategoryDef("expense-0", "Ərzaq", TransactionType.EXPENSE),
                CategoryDef("income-0", "Maaş", TransactionType.INCOME),
            ),
            normaliseData(legacy).categories,
        )
    }

    @Test
    fun `keeps a stored list exactly as it is`() {
        val stored = FinanceData(
            categories = listOf(CategoryDef("c1", "Kirayə", TransactionType.EXPENSE)),
        )
        assertEquals(stored.categories, normaliseData(stored).categories)
    }
}
