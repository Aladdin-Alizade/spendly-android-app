/**
 * When a network becomes usable.
 *
 * Deliberately not a state. The app has one question — "is now a moment worth
 * retrying?" — and tracking online/offline to answer it does not survive
 * contact with a real device: losing one interface while another is still up
 * reports "still online", so an outage can pass without ever being seen as
 * one, and the network coming back then looks like no change at all. Queued
 * work sat on the device until the app was next reopened.
 *
 * So this reports events, not a state, and the caller decides whether there is
 * anything to send. Whether the server can actually be reached is settled by
 * the request itself, never by this.
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
import kotlinx.coroutines.flow.conflate

class NetworkMonitor(context: Context) {
    private val manager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * Emits every time a network becomes available, including the ones already
     * up when this starts listening. Conflated: a reconnection that brings up
     * Wi-Fi and cellular a moment apart is one moment, not two.
     */
    val available: Flow<Unit> = callbackFlow {
        val connectivity = manager
        if (connectivity == null) {
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)

        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.conflate()

    fun isOnline(): Boolean {
        val connectivity = manager ?: return true
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
