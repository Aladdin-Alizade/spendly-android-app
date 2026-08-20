/**
 * The merge rule: rows this device changed while it could not reach the
 * server win; every other row comes from the server.
 *
 * These are the cases that decide whether offline work survives, so each one
 * is written as the situation it stands for rather than as a shape.
 */
package az.spendly

import az.spendly.data.hasPendingWork
import az.spendly.data.mergeFinanceData
import az.spendly.data.mergeRows
import az.spendly.domain.BudgetLine
import az.spendly.domain.FinanceData
import az.spendly.domain.IncomePlan
import az.spendly.domain.SavingsDirection
import az.spendly.domain.SavingsEntry
import az.spendly.domain.SavingsPlan
import az.spendly.domain.SavingsPot
import az.spendly.domain.SavingsSource
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun t(id: String, amount: Double = 10.0, description: String = "Test") =
    Transaction(id, "2026-08-05", TransactionType.EXPENSE, "Ərzaq", description, amount)

class MergeRowsTest {

    @Test
    fun `keeps a row this device edited offline`() {
        val base = listOf(t("a"), t("b"))
        val local = listOf(t("a", amount = 99.0), t("b"))
        val remote = listOf(t("a"), t("b"))

        val merged = mergeRows(base, local, remote) { it.id }
        assertEquals(99.0, merged.first { it.id == "a" }.amount, 0.0)
    }

    @Test
    fun `takes a row another device changed`() {
        val base = listOf(t("a"), t("b"))
        val local = listOf(t("a"), t("b"))
        val remote = listOf(t("a"), t("b", amount = 55.0))

        val merged = mergeRows(base, local, remote) { it.id }
        assertEquals(55.0, merged.first { it.id == "b" }.amount, 0.0)
    }

    @Test
    fun `carries a row added offline over to the merged list`() {
        val merged = mergeRows(
            base = listOf(t("a")),
            local = listOf(t("a"), t("new")),
            remote = listOf(t("a")),
        ) { it.id }

        assertEquals(listOf("a", "new"), merged.map { it.id })
    }

    @Test
    fun `honours a deletion made offline`() {
        val merged = mergeRows(
            base = listOf(t("a"), t("b")),
            local = listOf(t("a")),
            remote = listOf(t("a"), t("b")),
        ) { it.id }

        assertEquals(listOf("a"), merged.map { it.id })
    }

    @Test
    fun `accepts a row another device deleted, when this one did not touch it`() {
        val merged = mergeRows(
            base = listOf(t("a"), t("b")),
            local = listOf(t("a"), t("b")),
            remote = listOf(t("a")),
        ) { it.id }

        assertEquals(listOf("a"), merged.map { it.id })
    }

    @Test
    fun `keeps a row this device edited even after another deleted it`() {
        // Two devices disagreeing about one row: the edit is work somebody did
        // and can see on screen, and a deletion elsewhere is not a reason to
        // discard it without saying so.
        val merged = mergeRows(
            base = listOf(t("a"), t("b")),
            local = listOf(t("a"), t("b", amount = 42.0)),
            remote = listOf(t("a")),
        ) { it.id }

        assertEquals(listOf("a", "b"), merged.map { it.id })
        assertEquals(42.0, merged.first { it.id == "b" }.amount, 0.0)
    }

    @Test
    fun `follows the server's order, with local additions at the end`() {
        val merged = mergeRows(
            base = emptyList(),
            local = listOf(t("local")),
            remote = listOf(t("x"), t("y")),
        ) { it.id }

        assertEquals(listOf("x", "y", "local"), merged.map { it.id })
    }
}

private fun savingsEntry(id: String, amount: Double = 400.0) = SavingsEntry(
    id = id,
    date = "2026-08-05",
    pot = "Ehtiyat fondu",
    amount = amount,
    direction = SavingsDirection.IN,
    source = SavingsSource.INCOME,
)

class MergeFinanceDataTest {

    private val base = FinanceData(
        transactions = listOf(t("a")),
        budgetLines = listOf(BudgetLine("b1", "2026-08", "Ev", "Əlavə xərclər", 230.0)),
        incomePlans = listOf(IncomePlan("2026-08", mapOf("Maaş" to 990.0))),
        categories = sheetCategories(),
        savingsPots = listOf(SavingsPot("p1", "Ehtiyat fondu")),
        savingsEntries = listOf(savingsEntry("s1")),
        savingsPlans = listOf(SavingsPlan("2026-08", mapOf("Ehtiyat fondu" to 300.0))),
    )

    @Test
    fun `merges every collection, each by its own identity`() {
        val local = base.copy(
            // Added on the phone with no signal.
            transactions = base.transactions + t("offline", amount = 25.0),
            incomePlans = listOf(IncomePlan("2026-08", mapOf("Maaş" to 1200.0))),
            savingsEntries = base.savingsEntries + savingsEntry("offline-s", amount = 120.0),
            savingsPlans = listOf(SavingsPlan("2026-08", mapOf("Ehtiyat fondu" to 500.0))),
        )
        val remote = base.copy(
            // Added in the browser meanwhile.
            transactions = base.transactions + t("browser", amount = 60.0),
            budgetLines = base.budgetLines + BudgetLine("b2", "2026-08", "İnternet", "Telefon və internet", 15.0),
            savingsEntries = base.savingsEntries + savingsEntry("browser-s", amount = 75.0),
        )

        val merged = mergeFinanceData(base, local, remote)

        assertEquals(listOf("a", "browser", "offline"), merged.transactions.map { it.id })
        assertEquals(listOf("b1", "b2"), merged.budgetLines.map { it.id })
        assertEquals(listOf("s1", "browser-s", "offline-s"), merged.savingsEntries.map { it.id })
        // The savings plan is keyed by month like the income one, and this
        // device is the one that changed it.
        assertEquals(mapOf("Ehtiyat fondu" to 500.0), merged.savingsPlans.first().amounts)
        // The plan was edited here, so this device's figure stands.
        assertEquals(mapOf("Maaş" to 1200.0), merged.incomePlans.first().amounts)
    }

    @Test
    fun `is the server's state when this device has nothing unsent`() {
        val remote = base.copy(transactions = base.transactions + t("elsewhere"))
        assertEquals(remote, mergeFinanceData(base, base, remote))
    }

    @Test
    fun `hands the whole account back to a device that has just been reinstalled`() {
        // Deleting the app takes the snapshots with it, so a fresh install
        // holds nothing and has no baseline either. Nothing was deleted here —
        // there is no baseline to have deleted it from — so every row on the
        // server comes back untouched.
        assertEquals(base, mergeFinanceData(FinanceData(), FinanceData(), base))
    }

    @Test
    fun `does not resurrect what was deleted before the app was reinstalled`() {
        // The mirror of the case above, and the one that would be a bug: a row
        // the account no longer has must not reappear just because this device
        // is starting from nothing.
        val merged = mergeFinanceData(FinanceData(), FinanceData(), base)
        assertFalse(merged.transactions.any { it.id == "gone" })
        assertEquals(base.transactions.map { it.id }, merged.transactions.map { it.id })
    }
}

class PendingWorkTest {

    private val data = FinanceData(transactions = listOf(t("a")))

    @Test
    fun `nothing is pending when the device matches the last sync`() {
        assertFalse(hasPendingWork(data, data))
    }

    @Test
    fun `an edit since the last sync is pending`() {
        assertTrue(hasPendingWork(data, data.copy(transactions = listOf(t("a", amount = 12.0)))))
    }
}

class DuplicateCategoryTest {

    private fun category(id: String, name: String) =
        az.spendly.domain.CategoryDef(id, name, TransactionType.EXPENSE)

    @Test
    fun `resolves the same category held under two ids in favour of the server`() {
        // A device that never synced seeded its own starting set; the account
        // was used elsewhere first and holds the same names under other ids.
        // Sending both is what the server rejects outright.
        val local = FinanceData(categories = listOf(category("cat-expense-0", "Ərzaq")))
        val remote = FinanceData(categories = listOf(category("uuid-1", "Ərzaq")))

        val merged = mergeFinanceData(FinanceData(), local, remote)

        assertEquals(1, merged.categories.size)
        assertEquals("uuid-1", merged.categories.first().id)
    }

    @Test
    fun `matches by name regardless of case or padding`() {
        val local = FinanceData(categories = listOf(category("a", " ərzaq ")))
        val remote = FinanceData(categories = listOf(category("b", "Ərzaq")))

        assertEquals(1, mergeFinanceData(FinanceData(), local, remote).categories.size)
    }

    @Test
    fun `leaves the same name on the other side of the ledger alone`() {
        // An expense and an income category may share a name; nothing looks a
        // category up without its type.
        val local = FinanceData(
            categories = listOf(
                az.spendly.domain.CategoryDef("a", "Bonus", TransactionType.EXPENSE),
                az.spendly.domain.CategoryDef("b", "Bonus", TransactionType.INCOME),
            ),
        )

        val merged = mergeFinanceData(FinanceData(), local, FinanceData())
        assertEquals(2, merged.categories.size)
    }

    @Test
    fun `carries the rows of the dropped spelling onto the one that survives`() {
        // The server keeps names apart by exact spelling, the app by name
        // regardless of case — so the two devices can each be right and still
        // hold "ərzaq" and "Ərzaq". Dropping the definition alone left this
        // device's transactions naming a category the picker no longer offered,
        // and opening one of them asked for a category that could not be
        // chosen.
        val local = FinanceData(
            categories = listOf(category("a", "ərzaq")),
            transactions = listOf(
                Transaction("t1", "2026-08-05", TransactionType.EXPENSE, "ərzaq", "Bazarlıq", 40.0),
            ),
            budgetLines = listOf(BudgetLine("b1", "2026-08", "Bazarlıq", "ərzaq", 300.0)),
        )
        val remote = FinanceData(categories = listOf(category("b", "Ərzaq")))

        val merged = mergeFinanceData(FinanceData(), local, remote)

        assertEquals(listOf("Ərzaq"), merged.categories.map { it.name })
        assertEquals("Ərzaq", merged.transactions.single().category)
        assertEquals("Ərzaq", merged.budgetLines.single().category)
    }

    @Test
    fun `carries a planned income figure onto the surviving spelling`() {
        val income = { id: String, name: String ->
            az.spendly.domain.CategoryDef(id, name, TransactionType.INCOME)
        }
        val local = FinanceData(
            categories = listOf(income("a", "maaş")),
            incomePlans = listOf(IncomePlan("2026-08", mapOf("maaş" to 990.0))),
        )
        val remote = FinanceData(categories = listOf(income("b", "Maaş")))

        val merged = mergeFinanceData(FinanceData(), local, remote)
        assertEquals(mapOf("Maaş" to 990.0), merged.incomePlans.single().amounts)
    }
}

class DuplicatePotTest {

    @Test
    fun `carries the entries and the plan of the dropped pot onto the survivor`() {
        // A pot is unique by name on the server and every entry names its pot,
        // so the same collision has the same answer — and the same duty to
        // take everything that named the loser with it.
        val local = FinanceData(
            savingsPots = listOf(SavingsPot("a", "ehtiyat fondu")),
            savingsEntries = listOf(
                SavingsEntry("e1", "2026-08-05", "ehtiyat fondu", 400.0, SavingsDirection.IN),
            ),
            savingsPlans = listOf(SavingsPlan("2026-08", mapOf("ehtiyat fondu" to 400.0))),
        )
        val remote = FinanceData(savingsPots = listOf(SavingsPot("b", "Ehtiyat fondu")))

        val merged = mergeFinanceData(FinanceData(), local, remote)

        assertEquals(listOf("Ehtiyat fondu"), merged.savingsPots.map { it.name })
        assertEquals("Ehtiyat fondu", merged.savingsEntries.single().pot)
        assertEquals(mapOf("Ehtiyat fondu" to 400.0), merged.savingsPlans.single().amounts)
    }
}
