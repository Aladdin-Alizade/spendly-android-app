package az.spendly.data

import android.content.Context
import az.spendly.domain.FinanceData
import az.spendly.domain.seedData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The device on its own: one snapshot file, and nothing else involved.
 *
 * This is what runs when no Supabase project is configured — there is nobody
 * to sign in as, so there is nothing to sync with either.
 */
class LocalRepository(context: Context) : FinanceRepository {
    private val store = SnapshotStore(context, WORKING_SNAPSHOT)
    private val writeLock = Mutex()

    override suspend fun load(): FinanceData = withContext(Dispatchers.IO) {
        store.read() ?: seedData().also { store.write(it) }
    }

    override suspend fun save(data: FinanceData) = withContext(Dispatchers.IO) {
        writeLock.withLock { store.write(data) }
    }
}
