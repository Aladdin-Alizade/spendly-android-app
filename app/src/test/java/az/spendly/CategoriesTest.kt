/**
 * Category management. The rule under test throughout is that a rename or a
 * removal moves the history with it — nothing that names a category may be
 * left pointing at one that no longer exists, and no amount may change.
 */
package az.spendly

import az.spendly.domain.BudgetLine
import az.spendly.domain.CategoryDef
import az.spendly.domain.FinanceData
import az.spendly.domain.IncomePlan
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import az.spendly.domain.addCategory
import az.spendly.domain.categoriesFromData
import az.spendly.domain.categoryNames
import az.spendly.domain.categoryUsage
import az.spendly.domain.removeCategory
import az.spendly.domain.renameCategory
import az.spendly.domain.sumOf
import az.spendly.domain.validateCategoryName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val M = "2026-08"

private var n = 0

private fun c(
    date: String = "$M-05",
    type: TransactionType = TransactionType.EXPENSE,
    category: String = "Ərzaq",
    amount: Double = 10.0,
): Transaction {
    n += 1
    return Transaction("x$n", date, type, category, "Test", amount)
}

private fun sample(
    transactions: List<Transaction> = emptyList(),
    budgetLines: List<BudgetLine> = emptyList(),
    incomePlans: List<IncomePlan> = emptyList(),
    categories: List<CategoryDef> = listOf(
        CategoryDef("c1", "Ərzaq", TransactionType.EXPENSE),
        CategoryDef("c2", "Nəqliyyat", TransactionType.EXPENSE),
        CategoryDef("c3", "Maaş", TransactionType.INCOME),
    ),
) = FinanceData(transactions, budgetLines, incomePlans, categories)

class CategoryListTest {

    @Test
    fun `separates the two sides of the ledger`() {
        val data = sample()
        assertEquals(listOf("Ərzaq", "Nəqliyyat"), categoryNames(data, TransactionType.EXPENSE))
        assertEquals(listOf("Maaş"), categoryNames(data, TransactionType.INCOME))
    }

    @Test
    fun `counts what depends on a category`() {
        val data = sample(
            transactions = listOf(c(), c(amount = 5.0)),
            budgetLines = listOf(BudgetLine("b1", M, "p", "Ərzaq", 100.0)),
        )
        val usage = categoryUsage(data, "Ərzaq")
        assertEquals(2, usage.transactions)
        assertEquals(1, usage.budgetLines)
        assertEquals(0, usage.incomePlans)
        assertTrue(usage.inUse)
        assertFalse(categoryUsage(data, "Nəqliyyat").inUse)
    }

    @Test
    fun `appends the category, trimmed`() {
        val data = addCategory(
            sample(),
            CategoryDef("c9", "  Ev  ", TransactionType.EXPENSE),
        )
        assertEquals(
            listOf("Ərzaq", "Nəqliyyat", "Ev"),
            categoryNames(data, TransactionType.EXPENSE),
        )
    }
}

class CategoryNameValidationTest {

    @Test
    fun `requires a name`() {
        assertEquals("Ad yazın", validateCategoryName(sample(), "   ", TransactionType.EXPENSE))
    }

    @Test
    fun `rejects a duplicate within the same type, whatever its case`() {
        assertEquals(
            "Belə kateqoriya artıq var",
            validateCategoryName(sample(), "ərzaq", TransactionType.EXPENSE),
        )
    }

    @Test
    fun `allows the same name on the other side of the ledger`() {
        assertNull(validateCategoryName(sample(), "Ərzaq", TransactionType.INCOME))
    }

    @Test
    fun `does not let a category clash with itself while being edited`() {
        assertNull(validateCategoryName(sample(), "Ərzaq", TransactionType.EXPENSE, "c1"))
    }

    @Test
    fun `accepts a new name`() {
        assertNull(validateCategoryName(sample(), "Kommunal", TransactionType.EXPENSE))
    }
}

class RenameCategoryTest {

    private val data = sample(
        transactions = listOf(
            c(amount = 10.0),
            c(category = "Nəqliyyat", amount = 20.0),
            c(type = TransactionType.INCOME, category = "Maaş", amount = 900.0),
        ),
        budgetLines = listOf(
            BudgetLine("b1", M, "p", "Ərzaq", 100.0),
            BudgetLine("b2", M, "q", "Nəqliyyat", 50.0),
        ),
    )

    @Test
    fun `carries every transaction and budget line across with it`() {
        val next = renameCategory(data, "c1", "Yemək")

        assertEquals(listOf("Yemək", "Nəqliyyat"), categoryNames(next, TransactionType.EXPENSE))
        assertEquals(1, categoryUsage(next, "Yemək").transactions)
        assertEquals(1, categoryUsage(next, "Yemək").budgetLines)
        assertFalse(categoryUsage(next, "Ərzaq").inUse)
    }

    @Test
    fun `leaves every amount alone`() {
        val next = renameCategory(data, "c1", "Yemək")
        assertEquals(
            sumOf(data.transactions.map { it.amount }),
            sumOf(next.transactions.map { it.amount }),
            0.0,
        )
        assertEquals(data.transactions.size, next.transactions.size)
    }

    @Test
    fun `does not touch the other side of the ledger, or other categories`() {
        val next = renameCategory(data, "c1", "Yemək")
        assertEquals(1, categoryUsage(next, "Nəqliyyat").transactions)
        assertEquals(1, categoryUsage(next, "Nəqliyyat").budgetLines)
        assertEquals(1, categoryUsage(next, "Maaş").transactions)
    }

    @Test
    fun `renames an income category without disturbing a same-named expense one`() {
        val shared = sample(
            categories = listOf(
                CategoryDef("c1", "Bonus", TransactionType.EXPENSE),
                CategoryDef("c2", "Bonus", TransactionType.INCOME),
            ),
            transactions = listOf(
                c(category = "Bonus"),
                c(type = TransactionType.INCOME, category = "Bonus"),
            ),
        )

        val next = renameCategory(shared, "c2", "Mükafat")
        assertEquals(listOf("Bonus", "Mükafat"), next.transactions.map { it.category })
    }

    @Test
    fun `ignores an empty name, an unchanged name and an unknown id`() {
        assertEquals(data, renameCategory(data, "c1", "   "))
        assertEquals(data, renameCategory(data, "c1", "Ərzaq"))
        assertEquals(data, renameCategory(data, "nope", "Yemək"))
    }
}

class RemoveCategoryTest {

    private val unused = sample()
    private val used = sample(
        transactions = listOf(c(), c(amount = 5.0)),
        budgetLines = listOf(BudgetLine("b1", M, "p", "Ərzaq", 100.0)),
    )

    @Test
    fun `drops a category nothing uses`() {
        val next = removeCategory(unused, "c1")
        assertEquals(listOf("Nəqliyyat"), categoryNames(next, TransactionType.EXPENSE))
    }

    @Test
    fun `refuses to strand history - a used category with nowhere to go stays`() {
        assertEquals(used, removeCategory(used, "c1"))
    }

    @Test
    fun `moves the history over when given a destination`() {
        val next = removeCategory(used, "c1", "Nəqliyyat")

        assertEquals(listOf("Nəqliyyat"), categoryNames(next, TransactionType.EXPENSE))
        assertEquals(2, categoryUsage(next, "Nəqliyyat").transactions)
        assertEquals(1, categoryUsage(next, "Nəqliyyat").budgetLines)
        // Nothing was deleted along with the category.
        assertEquals(2, next.transactions.size)
        assertEquals(15.0, sumOf(next.transactions.map { it.amount }), 0.0)
    }

    @Test
    fun `ignores an unknown id`() {
        assertEquals(used, removeCategory(used, "nope"))
    }
}

class IncomePlanCategoryTest {

    private val planned = sample(
        categories = listOf(
            CategoryDef("i1", "Maaş", TransactionType.INCOME),
            CategoryDef("i2", "Mentorluq", TransactionType.INCOME),
            CategoryDef("c1", "Ərzaq", TransactionType.EXPENSE),
        ),
        incomePlans = listOf(
            IncomePlan(M, mapOf("Maaş" to 990.0, "Mentorluq" to 200.0)),
            IncomePlan("2026-07", mapOf("Maaş" to 900.0)),
        ),
    )

    @Test
    fun `counts a planned figure as usage, even with no transactions yet`() {
        val usage = categoryUsage(planned, "Mentorluq")
        assertEquals(0, usage.transactions)
        assertEquals(0, usage.budgetLines)
        assertEquals(1, usage.incomePlans)
        assertTrue(usage.inUse)
    }

    @Test
    fun `carries the planned figure across a rename, in every month`() {
        val next = renameCategory(planned, "i1", "Əsas iş")

        assertEquals(
            mapOf("Mentorluq" to 200.0, "Əsas iş" to 990.0),
            next.incomePlans[0].amounts,
        )
        assertEquals(mapOf("Əsas iş" to 900.0), next.incomePlans[1].amounts)
    }

    @Test
    fun `will not silently drop a planned figure on delete`() {
        assertEquals(planned, removeCategory(planned, "i2"))
    }

    @Test
    fun `moves the planned figure to the destination on delete, adding to it`() {
        val next = removeCategory(planned, "i2", "Maaş")

        assertEquals(mapOf("Maaş" to 1190.0), next.incomePlans[0].amounts)
        assertFalse(next.categories.any { it.name == "Mentorluq" })
    }

    @Test
    fun `leaves expense renames out of the income plan entirely`() {
        val next = renameCategory(planned, "c1", "Yemək")
        assertEquals(planned.incomePlans, next.incomePlans)
    }
}

/* ------------------------------------------------------------------ *
 * Categories implied by the data itself
 * ------------------------------------------------------------------ */

class CategoriesFromDataTest {

    @Test
    fun `gives a new account nothing, because it has nothing`() {
        assertTrue(categoriesFromData(sample(categories = emptyList())).isEmpty())
    }

    @Test
    fun `reads the categories a stored history already names`() {
        val data = sample(
            categories = emptyList(),
            transactions = listOf(
                c(category = "Ərzaq"),
                c(category = "Nəqliyyat"),
                c(type = TransactionType.INCOME, category = "Maaş"),
            ),
        )

        assertEquals(
            listOf(
                CategoryDef("expense-0", "Ərzaq", TransactionType.EXPENSE),
                CategoryDef("expense-1", "Nəqliyyat", TransactionType.EXPENSE),
                CategoryDef("income-0", "Maaş", TransactionType.INCOME),
            ),
            categoriesFromData(data),
        )
    }

    @Test
    fun `files a budget line under expenses and a planned figure under income`() {
        val data = sample(
            categories = emptyList(),
            budgetLines = listOf(BudgetLine("b1", M, "Ev", "Kirayə", 230.0)),
            incomePlans = listOf(IncomePlan(M, mapOf("Mentorluq" to 200.0))),
        )

        assertEquals(
            listOf(
                CategoryDef("expense-0", "Kirayə", TransactionType.EXPENSE),
                CategoryDef("income-0", "Mentorluq", TransactionType.INCOME),
            ),
            categoriesFromData(data),
        )
    }

    @Test
    fun `names a category once, however many rows use it`() {
        val data = sample(
            categories = emptyList(),
            transactions = listOf(c(category = "Ərzaq"), c(category = " ərzaq ")),
        )
        assertEquals(1, categoriesFromData(data).size)
    }

    @Test
    fun `keeps the same name on both sides of the ledger apart`() {
        val data = sample(
            categories = emptyList(),
            transactions = listOf(
                c(category = "Bonus"),
                c(type = TransactionType.INCOME, category = "Bonus"),
            ),
        )
        assertEquals(
            listOf(TransactionType.EXPENSE, TransactionType.INCOME),
            categoriesFromData(data).map { it.type },
        )
    }
}
