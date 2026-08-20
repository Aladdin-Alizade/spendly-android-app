/**
 * Money set aside still exists. Every test here is a way of asking whether the
 * app still knows that — which is the one thing recording savings as spending
 * got wrong.
 */
package az.spendly

import az.spendly.domain.CategoryDef
import az.spendly.domain.CategoryKind
import az.spendly.domain.FinanceData
import az.spendly.domain.SavingsDirection
import az.spendly.domain.SavingsEntry
import az.spendly.domain.SavingsPlan
import az.spendly.domain.SavingsPot
import az.spendly.domain.SavingsSource
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import az.spendly.domain.addPot
import az.spendly.domain.convertSavingTransactions
import az.spendly.domain.convertibleSavingTransactions
import az.spendly.domain.depositedFromIncome
import az.spendly.domain.depositedFromOutside
import az.spendly.domain.insights.fundPace
import az.spendly.domain.knownMonths
import az.spendly.domain.plannedSavings
import az.spendly.domain.plannedSavingsRows
import az.spendly.domain.summarise
import az.spendly.domain.potBalance
import az.spendly.domain.PlannedSavingsRow
import az.spendly.domain.potRows
import az.spendly.domain.removePot
import az.spendly.domain.renamePot
import az.spendly.domain.savingsBalance
import az.spendly.domain.setPotTarget
import az.spendly.domain.spendableBalance
import az.spendly.domain.spendableDelta
import az.spendly.domain.totalHoldings
import az.spendly.domain.validatePotName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val M = "2026-08"

private fun savings(
    transactions: List<Transaction> = emptyList(),
    categories: List<CategoryDef> = emptyList(),
    pots: List<SavingsPot> = listOf(SavingsPot("p1", "Ehtiyat fondu")),
    entries: List<SavingsEntry> = emptyList(),
    plans: List<SavingsPlan> = emptyList(),
) = FinanceData(
    transactions = transactions,
    categories = categories,
    savingsPots = pots,
    savingsEntries = entries,
    savingsPlans = plans,
)

private fun entry(
    id: String = "e1",
    date: String = "2026-08-05",
    pot: String = "Ehtiyat fondu",
    amount: Double = 400.0,
    direction: SavingsDirection = SavingsDirection.IN,
    source: SavingsSource? = SavingsSource.INCOME,
    note: String? = null,
) = SavingsEntry(id, date, pot, amount, direction, source, note)

private fun spent(
    id: String = "t1",
    date: String = "2026-08-05",
    category: String = "Ərzaq",
    amount: Double = 100.0,
    description: String = "Test",
    type: TransactionType = TransactionType.EXPENSE,
) = Transaction(id, date, type, category, description, amount)

class SavingsBalanceTest {

    @Test
    fun `counts a deposit in and a withdrawal out`() {
        val entries = listOf(
            entry(id = "a", amount = 400.0),
            entry(id = "b", amount = 150.0, direction = SavingsDirection.OUT, source = null),
        )
        assertEquals(250.0, savingsBalance(entries), 0.0)
    }

    @Test
    fun `keeps each pot apart`() {
        val entries = listOf(
            entry(id = "a", amount = 400.0),
            entry(id = "b", pot = "Avtomobil", amount = 900.0),
        )
        assertEquals(400.0, potBalance(entries, "Ehtiyat fondu"), 0.0)
        assertEquals(900.0, potBalance(entries, "Avtomobil"), 0.0)
        assertEquals(1300.0, savingsBalance(entries), 0.0)
    }

    @Test
    fun `stops at the end of the month asked for`() {
        val entries = listOf(
            entry(id = "a", date = "2026-07-20", amount = 200.0),
            entry(id = "b", date = "2026-09-02", amount = 500.0),
        )
        assertEquals(200.0, savingsBalance(entries, "2026-08"), 0.0)
        assertEquals(700.0, savingsBalance(entries), 0.0)
    }
}

class SpendableDeltaTest {

    @Test
    fun `takes a deposit made out of income off the spendable side`() {
        assertEquals(
            -400.0,
            spendableDelta(listOf(entry(amount = 400.0, source = SavingsSource.INCOME))),
            0.0,
        )
    }

    @Test
    fun `leaves it alone for money that arrived from outside`() {
        // It was never spendable, so putting it in a pot cannot reduce what is.
        assertEquals(
            0.0,
            spendableDelta(listOf(entry(amount = 400.0, source = SavingsSource.EXTERNAL))),
            0.0,
        )
    }

    @Test
    fun `puts a withdrawal back`() {
        assertEquals(
            150.0,
            spendableDelta(
                listOf(
                    entry(amount = 150.0, direction = SavingsDirection.OUT, source = null),
                ),
            ),
            0.0,
        )
    }
}

class HoldingsTest {

    private val data = savings(
        transactions = listOf(
            spent(id = "i1", type = TransactionType.INCOME, category = "Maaş", amount = 2000.0),
            spent(id = "x1", amount = 1600.0),
        ),
        entries = listOf(entry(id = "a", amount = 400.0, source = SavingsSource.INCOME)),
    )

    @Test
    fun `sets a deposit aside without calling it spending`() {
        // 2000 earned, 1600 spent, 400 put away: nothing left to spend, but
        // the 400 is still held.
        assertEquals(0.0, spendableBalance(data), 0.0)
        assertEquals(400.0, savingsBalance(data.savingsEntries), 0.0)
        assertEquals(400.0, totalHoldings(data), 0.0)
    }

    @Test
    fun `grows the total by money that came from outside`() {
        val withGift = data.copy(
            savingsEntries = data.savingsEntries +
                entry(id = "g", amount = 1000.0, source = SavingsSource.EXTERNAL),
        )
        // The gift never passed through income, so only the total moves.
        assertEquals(0.0, spendableBalance(withGift), 0.0)
        assertEquals(1400.0, totalHoldings(withGift), 0.0)
    }

    @Test
    fun `returns a withdrawal to the spendable side without inventing income`() {
        val withdrawn = data.copy(
            savingsEntries = data.savingsEntries +
                entry(id = "w", amount = 250.0, direction = SavingsDirection.OUT, source = null),
        )
        assertEquals(250.0, spendableBalance(withdrawn), 0.0)
        assertEquals(150.0, savingsBalance(withdrawn.savingsEntries), 0.0)
        // The household is no richer for having moved its own money.
        assertEquals(400.0, totalHoldings(withdrawn), 0.0)
    }
}

class MonthlySavingsTest {

    private val entries = listOf(
        entry(id = "a", date = "2026-08-05", amount = 400.0, source = SavingsSource.INCOME),
        entry(id = "b", date = "2026-08-09", amount = 1000.0, source = SavingsSource.EXTERNAL),
        entry(id = "c", date = "2026-07-30", amount = 300.0, source = SavingsSource.INCOME),
    )

    @Test
    fun `separates what was set aside from what arrived`() {
        assertEquals(400.0, depositedFromIncome(entries, M), 0.0)
        assertEquals(1000.0, depositedFromOutside(entries, M), 0.0)
    }

    @Test
    fun `leaves other months out`() {
        assertEquals(300.0, depositedFromIncome(entries, "2026-07"), 0.0)
        assertEquals(0.0, depositedFromOutside(entries, "2026-07"), 0.0)
    }
}

class PotTest {

    @Test
    fun `reports progress only where there is a target`() {
        val data = savings(
            pots = listOf(
                SavingsPot("p1", "Ehtiyat fondu", target = 3000.0),
                SavingsPot("p2", "Avtomobil"),
            ),
            entries = listOf(
                entry(id = "a", amount = 750.0),
                entry(id = "b", pot = "Avtomobil", amount = 900.0),
            ),
        )

        val rows = potRows(data)
        assertEquals(0.25, rows[0].progress!!, 1e-9)
        assertNull(rows[1].progress)
        assertEquals(900.0, rows[1].balance, 0.0)
    }

    @Test
    fun `shows money left behind by a pot that was deleted`() {
        val data = savings(
            pots = emptyList(),
            entries = listOf(entry(id = "a", pot = "Köhnə qab", amount = 500.0)),
        )
        val rows = potRows(data)
        assertEquals(1, rows.size)
        assertEquals("Köhnə qab", rows[0].name)
        assertEquals(500.0, rows[0].balance, 0.0)
        assertTrue(rows[0].orphaned)
    }

    @Test
    fun `carries every entry across on a rename, without moving a manat`() {
        val data = savings(entries = listOf(entry(id = "a")))
        val renamed = renamePot(data, "p1", "Təhlükəsizlik yastığı")
        assertEquals("Təhlükəsizlik yastığı", renamed.savingsEntries[0].pot)
        assertEquals(
            savingsBalance(data.savingsEntries),
            savingsBalance(renamed.savingsEntries),
            0.0,
        )
    }

    @Test
    fun `carries the month's planned figure across on a rename`() {
        // The plan is keyed by pot name, the way the income plan is keyed by
        // category name. Moving only the entries left the figure behind under
        // a pot that no longer existed, so the screen reported it as an orphan
        // and the pot it belonged to looked unplanned.
        val data = savings(
            entries = listOf(entry(id = "a", amount = 400.0)),
            plans = listOf(SavingsPlan("2026-08", mapOf("Ehtiyat fondu" to 400.0))),
        )

        val renamed = renamePot(data, "p1", "Təhlükəsizlik yastığı")
        assertEquals(
            mapOf("Təhlükəsizlik yastığı" to 400.0),
            renamed.savingsPlans[0].amounts,
        )
    }

    @Test
    fun `adds the planned figures together when a pot is merged into another`() {
        val data = savings(
            pots = listOf(SavingsPot("p1", "Ehtiyat fondu"), SavingsPot("p2", "Avtomobil")),
            entries = listOf(entry(id = "a", amount = 400.0)),
            plans = listOf(
                SavingsPlan("2026-08", mapOf("Ehtiyat fondu" to 400.0, "Avtomobil" to 100.0)),
            ),
        )

        val removed = removePot(data, "p1", "Avtomobil")
        assertEquals(mapOf("Avtomobil" to 500.0), removed.savingsPlans[0].amounts)
    }

    @Test
    fun `refuses to delete a pot that still holds something`() {
        val data = savings(entries = listOf(entry(id = "a")))
        assertEquals(data, removePot(data, "p1"))
    }

    @Test
    fun `moves what it holds when a destination is given`() {
        val data = savings(
            pots = listOf(SavingsPot("p1", "Ehtiyat fondu"), SavingsPot("p2", "Avtomobil")),
            entries = listOf(entry(id = "a", amount = 400.0)),
        )
        val removed = removePot(data, "p1", "Avtomobil")
        assertEquals(listOf("Avtomobil"), removed.savingsPots.map { it.name })
        assertEquals(400.0, potBalance(removed.savingsEntries, "Avtomobil"), 0.0)
    }

    @Test
    fun `drops an empty pot outright`() {
        assertTrue(removePot(savings(), "p1").savingsPots.isEmpty())
    }

    @Test
    fun `treats a target of zero as no target at all`() {
        assertNull(setPotTarget(savings(), "p1", 0.0).savingsPots[0].target)
    }

    @Test
    fun `rejects a name that is blank or already taken`() {
        val data = addPot(savings(), SavingsPot("p2", "Avtomobil"))
        assertEquals("Ad yazın", validatePotName(data, "  "))
        assertEquals("Belə qab artıq var", validatePotName(data, "avtomobil"))
        assertNull(validatePotName(data, "Avtomobil", "p2"))
        assertNull(validatePotName(data, "Təhsil"))
    }
}

class ConvertSavingsTest {

    private val categories = listOf(
        CategoryDef("c1", "Yığım", TransactionType.EXPENSE, CategoryKind.SAVING),
        CategoryDef("c2", "Ərzaq", TransactionType.EXPENSE, CategoryKind.ESSENTIAL),
    )

    private val data = savings(
        categories = categories,
        pots = emptyList(),
        transactions = listOf(
            spent(id = "s1", category = "Yığım", amount = 400.0, description = "Avqust yığımı"),
            spent(id = "s2", category = "Yığım", amount = 300.0, date = "2026-07-05"),
            spent(id = "x1", category = "Ərzaq", amount = 120.0),
        ),
    )

    @Test
    fun `finds them without touching anything`() {
        val found = convertibleSavingTransactions(data)
        assertEquals(listOf("s1", "s2"), found.transactions)
        assertEquals(listOf("Yığım"), found.pots)
        assertEquals(700.0, found.total, 0.0)
    }

    @Test
    fun `moves the money across and leaves the spending alone`() {
        var counter = 0
        val converted = convertSavingTransactions(data) { "n${++counter}" }

        assertEquals(listOf("x1"), converted.transactions.map { it.id })
        assertEquals(listOf("Yığım"), converted.savingsPots.map { it.name })
        assertEquals(700.0, savingsBalance(converted.savingsEntries), 0.0)

        // Nothing moved on the spendable side: the money had already left it,
        // and the conversion only changes what the app thinks became of it.
        assertEquals(spendableBalance(data), spendableBalance(converted), 0.0)

        // The total does move, and that is the correction itself. Recorded as
        // spending, the 700 read as consumed; recorded as a deposit, it reads
        // as held — which is what it always was.
        assertEquals(-820.0, totalHoldings(data), 0.0)
        assertEquals(-120.0, totalHoldings(converted), 0.0)
    }

    @Test
    fun `does nothing when there is nothing to convert`() {
        val clean = savings(categories = categories)
        assertEquals(clean, convertSavingTransactions(clean) { "x" })
    }
}

class FundProgressTest {

    private val data = savings(
        categories = listOf(
            CategoryDef("c1", "Ərzaq", TransactionType.EXPENSE, CategoryKind.ESSENTIAL),
        ),
        transactions = listOf(
            spent(id = "i1", type = TransactionType.INCOME, category = "Maaş", amount = 2000.0),
            spent(id = "x1", category = "Ərzaq", amount = 1000.0),
        ),
        entries = listOf(entry(id = "a", amount = 500.0, source = SavingsSource.INCOME)),
    )

    @Test
    fun `measures the remaining distance, not the whole target`() {
        val pace = fundPace(data, M, 3000.0)
        assertNotNull(pace)
        assertEquals(500.0, pace!!.saved, 0.0)
        assertEquals(2500.0, pace.remaining, 0.0)
        // 500 a month into the pot, 2500 to go.
        assertEquals(500.0, pace.savingMonthly, 0.0)
        assertEquals(5.0, pace.monthsAtSaving!!, 1e-9)
    }

    @Test
    fun `reports nothing left to do once the target is met`() {
        val pace = fundPace(data, M, 400.0)
        assertEquals(0.0, pace!!.remaining, 0.0)
        assertEquals(0.0, pace.monthsAtSaving!!, 0.0)
    }
}

class SelectableKindsTest {

    @Test
    fun `does not offer saving as a spending category any more`() {
        // One act, one home: a pot deposit and a saving-kind expense would be
        // the same 400 manat written two ways, and no reader could tell which.
        assertTrue(CategoryKind.SAVING !in CategoryKind.SELECTABLE)
        assertTrue(CategoryKind.SAVING in CategoryKind.ALL)
    }
}

class PlannedSavingsTest {

    private val data = savings(
        pots = listOf(SavingsPot("p1", "Ehtiyat fondu"), SavingsPot("p2", "Avtomobil")),
        entries = listOf(
            entry(id = "a", amount = 250.0, source = SavingsSource.INCOME),
            entry(id = "b", amount = 1000.0, source = SavingsSource.EXTERNAL),
        ),
        plans = listOf(
            SavingsPlan(M, mapOf("Ehtiyat fondu" to 400.0, "Avtomobil" to 200.0)),
        ),
    )

    @Test
    fun `adds the month up across its pots`() {
        assertEquals(600.0, plannedSavings(data.savingsPlans, M), 0.0)
        assertEquals(0.0, plannedSavings(data.savingsPlans, "2026-07"), 0.0)
    }

    @Test
    fun `measures the plan against income deposits only`() {
        val summary = summarise(data, M)
        assertEquals(600.0, summary.plannedSavings, 0.0)
        // The 1,000 came from outside; meeting a plan out of a windfall is not
        // meeting it, so only the 250 counts.
        assertEquals(250.0, summary.actualSavings, 0.0)
    }

    @Test
    fun `leaves the sheet's own remainder untouched`() {
        // plannedRemainder is C13 − F11 and nothing else, savings plan or not.
        assertEquals(0.0, summarise(data, M).plannedRemainder, 0.0)
    }

    @Test
    fun `keeps a figure planned for a pot that has gone`() {
        val rows = plannedSavingsRows(
            listOf(SavingsPot("p1", "Ehtiyat fondu")),
            mapOf("Ehtiyat fondu" to 400.0, "Köhnə qab" to 90.0),
        )
        assertEquals(2, rows.size)
        assertEquals(PlannedSavingsRow("Köhnə qab", 90.0, orphaned = true), rows[1])
    }
}

class KnownMonthsTest {

    @Test
    fun `includes a month whose only record is a savings movement`() {
        val data = savings(entries = listOf(entry(id = "a", date = "2026-05-11")))
        // Without this the entry exists in a month you cannot navigate to.
        assertTrue("2026-05" in knownMonths(data, M))
    }
}
