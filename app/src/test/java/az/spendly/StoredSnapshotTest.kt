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
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import az.spendly.domain.emptyData
import az.spendly.domain.migrateIncomePlan
import az.spendly.domain.normaliseData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredSnapshotTest {

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
