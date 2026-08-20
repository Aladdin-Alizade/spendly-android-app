/**
 * Offline-first persistence.
 *
 * The device's own snapshot is the working copy: every change is written there
 * first and the write to Supabase follows. That ordering is the whole point —
 * an edit made on a train is saved before anything is asked of the network, so
 * closing the app cannot lose it.
 *
 * What the server holds is still the shared truth across devices. On every
 * load the two are brought together by [mergeFinanceData], and whatever this
 * device has not managed to send is sent then. A change that could not be sent
 * is not an error, it is work in the queue, and the UI says so in those terms.
 */
package az.spendly.data

import android.content.Context
import az.spendly.domain.FinanceData
import az.spendly.domain.emptyData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SyncStatus {
    /** Everything on this device is on the server. */
    SYNCED,

    /** There is work here the server has not taken yet. */
    PENDING,

    /** The server could not be reached at all. */
    OFFLINE,

    /** The server answered, and said no. This one needs a person. */
    FAILED,
}

data class SyncState(
    val status: SyncStatus = SyncStatus.SYNCED,
    /** Set for [SyncStatus.FAILED], where the reason is actionable. */
    val message: String? = null,
) {
    val pending: Boolean
        get() = status == SyncStatus.PENDING || status == SyncStatus.OFFLINE
}

class SyncingRepository(
    context: Context,
    private val remote: SupabaseRepository,
) : FinanceRepository {

    private val working = SnapshotStore(context, WORKING_SNAPSHOT)
    private val synced = SnapshotStore(context, SYNCED_SNAPSHOT)

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val lock = Mutex()

    /** False until a load has actually reached the server this session. */
    private var reconciled = false

    override suspend fun load(): FinanceData = withContext(Dispatchers.IO) {
        lock.withLock {
            val local = working.read() ?: emptyData
            reconcile(local) ?: local
        }
    }

    override suspend fun save(data: FinanceData): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            // The device first, always. Everything after this is delivery.
            working.write(data)

            try {
                if (reconciled) {
                    remote.save(data)
                    synced.write(data)
                    _state.value = SyncState(SyncStatus.SYNCED)
                } else {
                    // Nothing has been reconciled with the server yet this
                    // session, so pushing a diff would be against a baseline
                    // that may not be the server's. Go the long way round.
                    reconcile(data)
                }
            } catch (cause: Exception) {
                report(cause)
            }
        }
    }

    /** A write that failed is queued work when the network is the reason and
     *  a matter for a person when the server is. */
    private fun report(cause: Exception) {
        _state.value = if (cause is SupabaseOfflineException) {
            SyncState(SyncStatus.PENDING)
        } else {
            SyncState(SyncStatus.FAILED, describeError(cause))
        }
    }

    /**
     * Send whatever is waiting. Called when the network comes back and when
     * the app returns to the foreground; safe to call when there is nothing
     * to do.
     */
    suspend fun sync(): FinanceData? = withContext(Dispatchers.IO) {
        lock.withLock {
            val local = working.read() ?: return@withContext null
            reconcile(local)
        }
    }

    /**
     * Bring the device and the server together, and push whatever this device
     * is holding. Returns the merged snapshot, or null when the server could
     * not be reached — in which case the caller keeps using the local copy.
     */
    private suspend fun reconcile(local: FinanceData): FinanceData? {
        val remoteData = try {
            remote.load()
        } catch (offline: SupabaseOfflineException) {
            _state.value = SyncState(
                if (hasPendingWork(synced.read() ?: emptyData, local)) {
                    SyncStatus.PENDING
                } else {
                    SyncStatus.OFFLINE
                },
            )
            return null
        } catch (cause: Exception) {
            _state.value = SyncState(SyncStatus.FAILED, describeError(cause))
            return null
        }

        reconciled = true

        /* No baseline means this device has never synced: everything it holds
           is treated as unsent rather than as already-known, because the other
           reading loses work that was entered before the account existed. */
        val base = synced.read() ?: emptyData
        val merged = mergeFinanceData(base, local, remoteData)

        return try {
            if (merged != remoteData) remote.save(merged)
            working.write(merged)
            synced.write(merged)
            _state.value = SyncState(SyncStatus.SYNCED)
            merged
        } catch (cause: Exception) {
            // The read got through and the write did not. The merge is still
            // the best copy this device has — it holds everything the server
            // just handed over — so it is kept and the failure is reported
            // against it. Throwing it away would leave the account looking
            // empty on a device that had just been told otherwise.
            working.write(merged)
            report(cause)
            merged
        }
    }
}
