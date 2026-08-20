package az.spendly.data

import android.content.Context
import az.spendly.domain.FinanceData
import az.spendly.domain.emptyData
import java.io.IOException
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
        // Nothing stored means nothing to show. A first run gets an empty
        // account, not a stranger's categories and plan.
        store.read() ?: emptyData
    }

    override suspend fun save(data: FinanceData) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            // There is no server behind this one, so a file that would not
            // write means the edit is nowhere. Saying nothing would leave it
            // on screen looking saved until the app is next opened.
            if (!store.write(data)) {
                throw IOException("Cihazın yaddaşı doludur.")
            }
        }
    }
}
