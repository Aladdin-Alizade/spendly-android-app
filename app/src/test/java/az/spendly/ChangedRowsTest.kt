/**
 * The write diff. The store hands down a whole snapshot on every change, so
 * this is what keeps a rename from rewriting the entire history.
 */
package az.spendly

import az.spendly.data.changedRows
import az.spendly.data.setupHint
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun row(transaction: Transaction) = buildJsonObject {
    put("id", transaction.id)
    put("amount", transaction.amount)
    put("category", transaction.category)
}

private fun transaction(id: String, amount: Double = 10.0, category: String = "Ərzaq") =
    Transaction(id, "2026-08-01", TransactionType.EXPENSE, category, "Test", amount)

class ChangedRowsTest {

    @Test
    fun `sends nothing when nothing changed`() {
        val rows = listOf(transaction("a"), transaction("b"))
        val changes = changedRows(rows, rows, { it.id }, ::row)
        assertTrue(changes.upserts.isEmpty())
        assertTrue(changes.removed.isEmpty())
    }

    @Test
    fun `sends only the row that changed`() {
        val before = listOf(transaction("a"), transaction("b"))
        val after = listOf(transaction("a"), transaction("b", amount = 20.0))
        val changes = changedRows(before, after, { it.id }, ::row)
        assertEquals(1, changes.upserts.size)
        assertTrue(changes.upserts.first().toString().contains("20.0"))
    }

    @Test
    fun `reports an id that disappeared`() {
        val changes = changedRows(
            listOf(transaction("a"), transaction("b")),
            listOf(transaction("a")),
            { it.id },
            ::row,
        )
        assertEquals(listOf("b"), changes.removed)
    }

    @Test
    fun `sends a row that appeared`() {
        val changes = changedRows(
            listOf(transaction("a")),
            listOf(transaction("a"), transaction("c")),
            { it.id },
            ::row,
        )
        assertEquals(1, changes.upserts.size)
        assertTrue(changes.removed.isEmpty())
    }
}

class SetupHintTest {

    @Test
    fun `names the dashboard step when sign-up is switched off`() {
        val hint = setupHint("Signups not allowed for this instance")
        assertNotNull(hint)
        assertTrue(hint!!.contains("Sign In / Providers"))
    }

    @Test
    fun `says to sign in again when the session has gone`() {
        assertTrue(setupHint("Hesaba daxil olunmayıb")!!.contains("Yenidən daxil olun"))
    }

    @Test
    fun `points at the schema file for a missing table`() {
        assertTrue(
            setupHint("PGRST205: Could not find the table 'public.transactions'")!!
                .contains("schema.sql"),
        )
    }

    @Test
    fun `has nothing to add for an error it does not recognise`() {
        assertNull(setupHint("something else entirely"))
        assertNull(setupHint(null))
        assertNull(setupHint(""))
    }
}
