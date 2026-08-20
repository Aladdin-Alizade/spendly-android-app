/**
 * One JSON snapshot in app storage.
 *
 * A snapshot is small — a year of daily spending is tens of kilobytes — and
 * the app already hands the repository a complete one on every change, so a
 * single file is both the simplest thing that works and the closest match to
 * what the web app keeps in localStorage. Writes go through a temporary file
 * so a process death mid-write cannot leave half a snapshot behind.
 */
package az.spendly.data

import android.content.Context
import az.spendly.domain.BudgetLine
import az.spendly.domain.CategoryDef
import az.spendly.domain.FinanceData
import az.spendly.domain.IncomePlan
import az.spendly.domain.SavingsEntry
import az.spendly.domain.SavingsPlan
import az.spendly.domain.SavingsPot
import az.spendly.domain.Transaction
import az.spendly.domain.normaliseData
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class SnapshotStore(context: Context, private val fileName: String) {
    private val directory = context.applicationContext.filesDir
    private val file = File(directory, fileName)

    /** Null when nothing has been written, or when what was written is unreadable. */
    fun read(): FinanceData? = try {
        if (file.exists()) decodeSnapshot(file.readText()) else null
    } catch (cause: Exception) {
        // Corrupt or unreadable storage must not brick the app.
        null
    }

    /**
     * True when the snapshot is on disk.
     *
     * A full disk is not a reason to throw: the edit is already on screen and
     * the caller has a server to try. It is a reason to say so, which is what
     * the return value is for — silently dropping the working copy would leave
     * the app promising an offline safety net it no longer has.
     */
    fun write(data: FinanceData): Boolean = try {
        val temporary = File(directory, "$fileName.tmp")
        temporary.writeText(snapshotJson.encodeToString(FinanceData.serializer(), data))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
        true
    } catch (cause: Exception) {
        false
    }

    fun clear() {
        file.delete()
    }
}

private val snapshotJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * A stored snapshot, one row at a time.
 *
 * Decoding the file as a whole meant a single unreadable row — a field an
 * older build wrote differently, a half-finished hand edit — threw, and
 * everything this device had entered offline was thrown away with it. A row
 * that cannot be read is skipped instead: the rest of the month is still the
 * person's own work, and is worth more than the tidiness of refusing all of
 * it.
 *
 * Still throws when the file is not a snapshot at all, which is the one case
 * where there is genuinely nothing to keep.
 */
fun decodeSnapshot(text: String): FinanceData {
    val root = snapshotJson.parseToJsonElement(text).jsonObject
    return normaliseData(
        FinanceData(
            transactions = root.rows("transactions", Transaction.serializer()),
            budgetLines = root.rows("budgetLines", BudgetLine.serializer()),
            incomePlans = root.rows("incomePlans", IncomePlan.serializer()),
            categories = root.rows("categories", CategoryDef.serializer()),
            savingsPots = root.rows("savingsPots", SavingsPot.serializer()),
            savingsEntries = root.rows("savingsEntries", SavingsEntry.serializer()),
            savingsPlans = root.rows("savingsPlans", SavingsPlan.serializer()),
        ),
    )
}

private fun <T> JsonObject.rows(key: String, serializer: KSerializer<T>): List<T> =
    (this[key] as? JsonArray)
        .orEmpty()
        .mapNotNull { row ->
            runCatching { snapshotJson.decodeFromJsonElement(serializer, row) }.getOrNull()
        }

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/**
 * The working copy — what the app reads and writes, online or not.
 *
 * This bare name belongs to the device rather than to any account: it is what
 * local-storage mode uses, and what an install held before it had an account.
 * A signed-in account gets its own file, from [workingSnapshot].
 */
const val WORKING_SNAPSHOT = "spendly.data.v1.json"

/** The last snapshot known to be on the server, used to tell this device's
 *  unsent work from rows it simply has not seen yet. */
const val SYNCED_SNAPSHOT = "spendly.synced.v1.json"

/**
 * One account, one file.
 *
 * These used to be one file per install, shared by every account that ever
 * signed in on it — and the sync treats whatever the file holds as work this
 * device has not sent yet. So signing in handed the previous occupant's rows
 * to the new account and uploaded them, and after that they belonged to it:
 * records its owner never entered, in their totals, on every device they own.
 *
 * The id is a UUID, so it is safe in a file name.
 */
fun workingSnapshot(userId: String?): String =
    if (userId.isNullOrBlank()) WORKING_SNAPSHOT else "spendly.data.v1.$userId.json"

fun syncedSnapshot(userId: String?): String =
    if (userId.isNullOrBlank()) SYNCED_SNAPSHOT else "spendly.synced.v1.$userId.json"
