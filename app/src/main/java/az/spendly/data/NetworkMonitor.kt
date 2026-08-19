/**
 * Whether the device currently has a network at all.
 *
 * Used only as a nudge: the moment a network appears is the moment worth
 * retrying whatever is queued. It is never used to decide whether a write
 * should be attempted — "the system says there is a network" and "the server
 * can be reached" are different claims, and only the request itself settles
 * the second one.
 */
package az.spendly.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class NetworkMonitor(context: Context) {
    private val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Emits on every change, starting with the current state. */
    val online: Flow<Boolean> = callbackFlow {
        val connectivity = manager
        if (connectivity == null) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }

        trySend(isOnline())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(isOnline())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)

        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    fun isOnline(): Boolean {
        val connectivity = manager ?: return true
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
