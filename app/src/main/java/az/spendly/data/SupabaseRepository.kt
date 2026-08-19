/**
 * Supabase-backed persistence.
 *
 * The store still hands down a whole [FinanceData] snapshot on every change,
 * so this class works out what actually changed since the last write and sends
 * only that. A rename touches one row, not the entire history.
 *
 * Writes are serialised: two edits in quick succession queue rather than race,
 * which keeps the remote state consistent with what is on screen.
 */
package az.spendly.data

import az.spendly.domain.BudgetLine
import az.spendly.domain.CategoryDef
import az.spendly.domain.CategoryKind
import az.spendly.domain.FinanceData
import az.spendly.domain.IncomePlan
import az.spendly.domain.Transaction
import az.spendly.domain.TransactionType
import az.spendly.domain.categoriesFromData
import az.spendly.domain.emptyData
import az.spendly.domain.migrateCategory
import az.spendly.domain.migrateIncomePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Ids are minted on the device, so they are only unique to one person. They
 * were not even that once: accounts made while the app handed out a starting
 * set of categories and a plan template all carry the same ids for those rows,
 * and those accounts still exist. The tables are keyed on (user_id, id) for
 * that reason, and every upsert says so, so a write is only ever matched
 * against a row this account owns. Matched against somebody else's, it fails
 * as a row level security violation — the row it collided with is one the
 * policies hide — and no edit gets saved.
 */
private const val BY_OWNER = "user_id,id"

class SupabaseRepository(private val session: SupabaseSession) : FinanceRepository {
    private val rest = SupabaseRest(session)

    /** The last snapshot known to be persisted, used to diff the next one. */
    private var previous: FinanceData = emptyData

    /** Saves apply in the order they were made, never concurrently. */
    private val writeLock = Mutex()

    override suspend fun load(): FinanceData = withContext(Dispatchers.IO) {
        requireUser()

        val (transactions, budgetLines, incomePlans, categories) = coroutineScope {
            val a = async { rest.select("transactions") }
            val b = async { rest.select("budget_lines") }
            val c = async { rest.select("income_plans") }
            val d = async { rest.select("categories") }
            Rows(a.await(), b.await(), c.await(), d.await())
        }

        val loaded = FinanceData(
            transactions = transactions.map { toTransaction(it.jsonObject) },
            budgetLines = budgetLines.map { toBudgetLine(it.jsonObject) },
            incomePlans = incomePlans.map { toIncomePlan(it.jsonObject) },
            categories = categories.map { toCategory(it.jsonObject) },
        )

        // An account created before categories were stored has none of its own.
        // Its rows name the categories it used, so those come back and the next
        // save persists them. A new account has no rows either, and stays empty.
        val data = if (loaded.categories.isNotEmpty()) {
            loaded
        } else {
            loaded.copy(categories = categoriesFromData(loaded))
        }

        previous = data
        data
    }

    override suspend fun save(data: FinanceData) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val baseline = previous
            previous = data
            try {
                write(baseline, data)
            } catch (cause: Exception) {
                // Re-sync on the next load rather than leaving a wrong baseline.
                previous = baseline
                throw cause
            }
        }
    }

    private fun write(before: FinanceData, after: FinanceData) {
        val userId = requireUser()

        // --- transactions ---------------------------------------------------
        val transactions = changedRows(before.transactions, after.transactions, { it.id }) {
            buildJsonObject {
                put("id", it.id)
                put("user_id", userId)
                put("date", it.date)
                put("type", it.type.wire)
                put("category", it.category)
                put("description", it.description)
                put("amount", it.amount)
                if (it.note == null) put("note", JsonNull) else put("note", it.note)
            }
        }
        rest.upsert("transactions", transactions.upserts, onConflict = BY_OWNER)
        rest.deleteIn("transactions", "id", transactions.removed)

        // --- budget lines ----------------------------------------------------
        val lines = changedRows(before.budgetLines, after.budgetLines, { it.id }) {
            buildJsonObject {
                put("id", it.id)
                put("user_id", userId)
                put("month", it.month)
                put("description", it.description)
                put("category", it.category)
                put("planned", it.planned)
            }
        }
        rest.upsert("budget_lines", lines.upserts, onConflict = BY_OWNER)
        rest.deleteIn("budget_lines", "id", lines.removed)

        // --- categories -------------------------------------------------------
        val categories = changedRows(before.categories, after.categories, { it.id }) {
            buildJsonObject {
                put("id", it.id)
                put("user_id", userId)
                put("name", it.name)
                put("type", it.type.wire)
                val kind = it.kind
                if (kind == null) put("kind", JsonNull) else put("kind", kind.wire)
            }
        }
        rest.upsert("categories", categories.upserts, onConflict = BY_OWNER)
        rest.deleteIn("categories", "id", categories.removed)

        // --- income plans (keyed by month, not by a generated id) -------------
        val plans = changedRows(before.incomePlans, after.incomePlans, { it.month }) { plan ->
            buildJsonObject {
                put("user_id", userId)
                put("month", plan.month)
                put(
                    "amounts",
                    JsonObject(plan.amounts.mapValues { (_, amount) -> JsonPrimitive(amount) }),
                )
            }
        }
        rest.upsert("income_plans", plans.upserts, onConflict = "user_id,month")
        rest.deleteIn("income_plans", "month", plans.removed)
    }

    /**
     * The repository never signs anyone in. The app decides who is signed in
     * and only builds this once someone is, so reaching here signed out is a
     * bug rather than a state to recover from.
     */
    private fun requireUser(): String =
        session.userId ?: throw SupabaseException("Hesaba daxil olunmayıb")
}

private data class Rows(
    val transactions: JsonArray,
    val budgetLines: JsonArray,
    val incomePlans: JsonArray,
    val categories: JsonArray,
)

data class RowChanges(val upserts: List<JsonElement>, val removed: List<String>)

/**
 * Rows that appeared or changed, and ids that disappeared.
 * Comparison is on the serialised row, so an untouched record is not rewritten.
 */
fun <T> changedRows(
    before: List<T>,
    after: List<T>,
    idOf: (T) -> String,
    toRow: (T) -> JsonObject,
): RowChanges {
    val beforeById = before.associate { idOf(it) to toRow(it) }
    val upserts = mutableListOf<JsonElement>()

    for (item in after) {
        val row = toRow(item)
        val existing = beforeById[idOf(item)]
        if (existing == null || existing != row) upserts.add(row)
    }

    val afterIds = after.map(idOf).toSet()
    val removed = before.map(idOf).filter { !afterIds.contains(it) }

    return RowChanges(upserts, removed)
}

/* --- row mapping. Postgres numerics can arrive as strings. ----------- */

private fun JsonObject.text(key: String): String =
    this[key]?.jsonPrimitive?.content?.takeIf { it != "null" } ?: ""

private fun JsonObject.number(key: String): Double =
    this[key]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0

private fun toTransaction(row: JsonObject) = Transaction(
    id = row.text("id"),
    date = row.text("date"),
    type = TransactionType.of(row.text("type")),
    category = migrateCategory(row.text("category")),
    description = row.text("description"),
    amount = row.number("amount"),
    note = row.text("note").ifBlank { null },
)

private fun toBudgetLine(row: JsonObject) = BudgetLine(
    id = row.text("id"),
    month = row.text("month"),
    description = row.text("description"),
    category = migrateCategory(row.text("category")),
    planned = row.number("planned"),
)

private fun toCategory(row: JsonObject) = CategoryDef(
    id = row.text("id"),
    name = migrateCategory(row.text("name")),
    type = TransactionType.of(row.text("type")),
    // An unrecognised kind is dropped rather than trusted.
    kind = CategoryKind.of(row.text("kind").ifBlank { null }),
)

/**
 * Rows written before income categories were editable have `salary` and
 * `additional` columns and an empty `amounts`; the migration reads whichever
 * of the two shapes the row is in.
 */
private fun toIncomePlan(row: JsonObject): IncomePlan {
    val stored = (row["amounts"] as? JsonObject)
        ?.mapValues { (_, value) -> value.jsonPrimitive.content.toDoubleOrNull() ?: 0.0 }
        ?.takeIf { it.isNotEmpty() }

    return migrateIncomePlan(
        month = row.text("month"),
        amounts = stored,
        salary = row.number("salary"),
        additional = row.number("additional"),
    )
}
