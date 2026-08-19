/**
 * Transaction validation. The allowed categories are passed in, so these
 * tests exercise the same contract the dialog uses.
 */
package az.spendly

import az.spendly.domain.FinanceData
import az.spendly.domain.TransactionInput
import az.spendly.domain.TransactionType
import az.spendly.domain.categoryNames
import az.spendly.domain.defaultCategories
import az.spendly.domain.validateTransaction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val data = FinanceData(categories = defaultCategories())
private val EXPENSES = categoryNames(data, TransactionType.EXPENSE)
private val INCOMES = categoryNames(data, TransactionType.INCOME)

private val valid = TransactionInput(
    date = "2025-10-14",
    type = TransactionType.EXPENSE,
    category = "Ərzaq",
    description = "Ərzaq alışı",
    amount = "45.20",
    note = "",
)

private fun check(input: TransactionInput, allowed: List<String> = EXPENSES) =
    validateTransaction(input, allowed)

class TransactionValidationTest {

    @Test
    fun `accepts a well-formed transaction`() {
        assertFalse(check(valid).any)
    }

    @Test
    fun `rejects an empty transaction`() {
        val errors = check(TransactionInput())
        assertNotNull(errors.date)
        assertNotNull(errors.category)
        assertNotNull(errors.description)
        assertNotNull(errors.amount)
    }

    @Test
    fun `rejects zero and negative amounts`() {
        assertNotNull(check(valid.copy(amount = "0")).amount)
        assertNotNull(check(valid.copy(amount = "-5")).amount)
    }

    @Test
    fun `rejects an absurdly large amount`() {
        assertNotNull(check(valid.copy(amount = "999999999")).amount)
    }

    @Test
    fun `rejects a whitespace-only description`() {
        assertNotNull(check(valid.copy(description = "   ")).description)
    }

    @Test
    fun `rejects an impossible date`() {
        assertNotNull(check(valid.copy(date = "2025-02-30")).date)
    }

    @Test
    fun `rejects an expense category on an income transaction`() {
        val income = valid.copy(type = TransactionType.INCOME)
        assertNotNull(check(income.copy(category = "Ərzaq"), INCOMES).category)
        assertNull(check(income.copy(category = "Maaş"), INCOMES).category)
    }

    @Test
    fun `accepts a category the user has just created`() {
        val category = "Ev heyvanları"
        assertNotNull(check(valid.copy(category = category)).category)
        assertNull(check(valid.copy(category = category), EXPENSES + category).category)
    }

    @Test
    fun `rejects a category that has been removed since`() {
        val remaining = EXPENSES.filter { it != "Ərzaq" }
        assertNotNull(check(valid, remaining).category)
    }
}
