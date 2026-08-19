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

/** The working copy — what the app reads and writes, online or not. */
const val WORKING_SNAPSHOT = "spendly.data.v1.json"

/** The last snapshot known to be on the server, used to tell this device's
 *  unsent work from rows it simply has not seen yet. */
const val SYNCED_SNAPSHOT = "spendly.synced.v1.json"
