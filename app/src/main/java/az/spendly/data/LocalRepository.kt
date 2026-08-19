package az.spendly.data

import android.content.Context
import az.spendly.domain.FinanceData
import az.spendly.domain.emptyData
import az.spendly.domain.normaliseData
import az.spendly.domain.seedData
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The whole snapshot as one JSON file in app storage — the device counterpart
 * of the browser's localStorage entry, down to the key it is named after.
 *
 * A snapshot is small (a year of daily spending is tens of kilobytes) and the
 * app already hands the repository a complete one on every change, so a single
 * file is both the simplest thing that works and the closest match to what the
 * web app stores. Writes go through a temporary file so a process death
 * mid-write cannot leave a half-written snapshot behind.
 */
class LocalRepository(context: Context) : FinanceRepository {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val writeLock = Mutex()

    override suspend fun load(): FinanceData = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                val seeded = seedData()
                writeSnapshot(seeded)
                return@withContext seeded
            }
            normaliseData(json.decodeFromString(FinanceData.serializer(), file.readText()))
        } catch (cause: Exception) {
            // Corrupt or unreadable storage must not brick the app.
            emptyData
        }
    }

    override suspend fun save(data: FinanceData) = withContext(Dispatchers.IO) {
        writeLock.withLock { writeSnapshot(data) }
    }

    private fun writeSnapshot(data: FinanceData) {
        val temporary = File(appContext.filesDir, "$FILE_NAME.tmp")
        temporary.writeText(json.encodeToString(FinanceData.serializer(), data))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    private companion object {
        /** Named after the browser key it replaces, so the two are recognisably
         *  the same store. */
        const val FILE_NAME = "spendly.data.v1.json"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
