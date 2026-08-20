/**
 * The write diff. The store hands down a whole snapshot on every change, so
 * this is what keeps a rename from rewriting the entire history.
 */
package az.spendly

import az.spendly.data.changedRows
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun row(transaction: Transaction) = buildJsonObject {
    put("id", transaction.id)
    put("date", transaction.date)
    put("amount", transaction.amount)
    put("description", transaction.description)
    put("category", transaction.category)
}

private fun transaction(
    id: String,
    amount: Double = 10.0,
    category: String = "Ərzaq",
    date: String = "2026-08-01",
    description: String = "Test",
) = Transaction(id, date, TransactionType.EXPENSE, category, description, amount)

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

    @Test
    fun `leaves untouched rows alone when a sibling changes`() {
        val before = listOf(transaction("a"), transaction("b"), transaction("c"))
        val after = listOf(transaction("a"), transaction("b", amount = 99.0), transaction("c"))
        val changes = changedRows(before, after, { it.id }, ::row)
        assertEquals(1, changes.upserts.size)
        assertTrue(changes.upserts.first().toString().contains("\"id\":\"b\""))
        assertTrue(changes.removed.isEmpty())
    }

    @Test
    fun `handles an add, an edit and a delete in one snapshot`() {
        val before = listOf(transaction("a"), transaction("b"), transaction("c"))
        val after = listOf(
            transaction("a", description = "Renamed"),
            transaction("c"),
            transaction("d"),
        )
        val changes = changedRows(before, after, { it.id }, ::row)
        assertEquals(2, changes.upserts.size)
        assertEquals(listOf("b"), changes.removed)
    }

    @Test
    fun `detects a change in every persisted field`() {
        val edits = listOf(
            transaction("a", amount = 11.0),
            transaction("a", date = "2026-08-06"),
            transaction("a", description = "Other"),
            transaction("a", category = "Kirayə"),
        )
        for (edited in edits) {
            val changes = changedRows(listOf(transaction("a")), listOf(edited), { it.id }, ::row)
            assertEquals(1, changes.upserts.size)
        }
    }

    @Test
    fun `clears everything when the last row goes`() {
        val changes = changedRows(
            listOf(transaction("a"), transaction("b")),
            emptyList(),
            { it.id },
            ::row,
        )
        assertEquals(listOf("a", "b"), changes.removed.sorted())
        assertTrue(changes.upserts.isEmpty())
    }

    @Test
    fun `treats a first load into an empty baseline as all inserts`() {
        val changes = changedRows(
            emptyList(),
            listOf(transaction("a"), transaction("b")),
            { it.id },
            ::row,
        )
        assertEquals(2, changes.upserts.size)
        assertTrue(changes.removed.isEmpty())
    }

    @Test
    fun `is not confused by reordering`() {
        val changes = changedRows(
            listOf(transaction("a"), transaction("b")),
            listOf(transaction("b"), transaction("a")),
            { it.id },
            ::row,
        )
        assertTrue(changes.upserts.isEmpty())
        assertTrue(changes.removed.isEmpty())
    }
}
