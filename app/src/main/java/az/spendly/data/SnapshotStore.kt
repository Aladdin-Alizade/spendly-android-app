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
import az.spendly.domain.FinanceData
import az.spendly.domain.normaliseData
import java.io.File
import kotlinx.serialization.json.Json

class SnapshotStore(context: Context, private val fileName: String) {
    private val directory = context.applicationContext.filesDir
    private val file = File(directory, fileName)

    /** Null when nothing has been written, or when what was written is unreadable. */
    fun read(): FinanceData? = try {
        if (file.exists()) {
            normaliseData(json.decodeFromString(FinanceData.serializer(), file.readText()))
        } else {
            null
        }
    } catch (cause: Exception) {
        // Corrupt or unreadable storage must not brick the app.
        null
    }

    fun write(data: FinanceData) {
        val temporary = File(directory, "$fileName.tmp")
        temporary.writeText(json.encodeToString(FinanceData.serializer(), data))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    fun clear() {
        file.delete()
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

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
