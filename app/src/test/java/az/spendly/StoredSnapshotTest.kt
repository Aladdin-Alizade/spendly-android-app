/**
 * Reading a snapshot back.
 *
 * The interesting case is a snapshot written before categories were records of
 * their own: its rows name the categories it used, and that is where they come
 * back from. Nothing is invented, so a snapshot with no rows stays empty —
 * which is exactly what a new account is.
 */
package az.spendly

import az.spendly.data.SYNCED_SNAPSHOT
import az.spendly.data.WORKING_SNAPSHOT
import az.spendly.data.decodeSnapshot
import az.spendly.data.syncedSnapshot
import az.spendly.data.workingSnapshot
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
import org.junit.Assert.assertNotEquals
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

/**
 * Reading a file that is not quite right.
 *
 * The whole file used to be decoded in one go, so one row an older build had
 * written differently threw — and every other row this device had entered
 * offline was discarded with it, silently, on the next start. A row that
 * cannot be read is now the only thing lost.
 */
class DamagedSnapshotTest {

    @Test
    fun `keeps the rows it can read when one of them is broken`() {
        val text = """
            {
              "transactions": [
                {"id":"t1","date":"2026-08-05","type":"expense","category":"Ərzaq",
                 "description":"Bazarlıq","amount":40.0},
                {"id":"t2"},
                {"id":"t3","date":"2026-08-06","type":"expense","category":"Ərzaq",
                 "description":"Çörək","amount":5.0}
              ],
              "savingsPots": [{"id":"p1","name":"Ehtiyat fondu"}]
            }
        """.trimIndent()

        val read = decodeSnapshot(text)
        assertEquals(listOf("t1", "t3"), read.transactions.map { it.id })
        assertEquals(45.0, read.transactions.sumOf { it.amount }, 0.0)
        assertEquals(1, read.savingsPots.size)
    }

    @Test
    fun `a missing collection is an empty one, not a failure`() {
        val read = decodeSnapshot("""{"transactions": []}""")
        assertEquals(emptyData, read)
    }

    @Test
    fun `still refuses something that is not a snapshot at all`() {
        // Nothing to keep here, so the caller falling back to an empty account
        // is the right answer rather than a guess at what was meant.
        var threw = false
        try {
            decodeSnapshot("not json")
        } catch (cause: Exception) {
            threw = true
        }
        assertTrue(threw)
    }
}

/**
 * Which file a snapshot goes in.
 *
 * This used to be one file per install, which meant it was shared by every
 * account that ever signed in on it — and the sync treats whatever the file
 * holds as work this device has not sent yet. So signing in handed the
 * previous occupant's rows to the new account and uploaded them as its own.
 */
class SnapshotScopeTest {

    @Test
    fun `two accounts on one device do not share a snapshot`() {
        val one = workingSnapshot("11111111-1111-1111-1111-111111111111")
        val two = workingSnapshot("22222222-2222-2222-2222-222222222222")

        assertNotEquals(one, two)
        assertNotEquals(one, WORKING_SNAPSHOT)
        assertNotEquals(syncedSnapshot("11111111-1111-1111-1111-111111111111"), one)
    }

    @Test
    fun `an account's working and synced snapshots are different files`() {
        val user = "11111111-1111-1111-1111-111111111111"
        assertNotEquals(workingSnapshot(user), syncedSnapshot(user))
    }

    @Test
    fun `with no account the device keeps the plain names`() {
        // Local-storage mode has nobody to scope to, and an install that has
        // never signed in has to keep writing where it already writes.
        assertEquals(WORKING_SNAPSHOT, workingSnapshot(null))
        assertEquals(WORKING_SNAPSHOT, workingSnapshot(""))
        assertEquals(SYNCED_SNAPSHOT, syncedSnapshot(null))
    }
}
