package dev.petrov.ymplayer2

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.petrov.ymplayer2.core.InternetConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.io.IOException

/** Application lifetime observer: Wi-Fi, cellular, Ethernet and VPN use the same default route. */
class AndroidInternetConnection(context: Context, private val scope: CoroutineScope) : InternetConnection {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mutable = MutableStateFlow(false)
    override val available = mutable.asStateFlow()
    private var probe: Job? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { refresh() }
        override fun onUnavailable() { refresh() }
    }
    init { refresh(); manager.registerDefaultNetworkCallback(callback) }
    override fun refresh() {
        scope.launch(Dispatchers.Main.immediate) {
            probe?.cancel()
            val network = manager.activeNetwork
            val caps = manager.getNetworkCapabilities(network)
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
                mutable.value = false
            } else if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                mutable.value = true
            } else {
                mutable.value = false
                // System probes can be blocked independently of Yandex. No credentials or body.
                probe = scope.launch {
                    val reachable = withContext(Dispatchers.IO) { reachableService() }
                    if (manager.activeNetwork == network) mutable.value = reachable
                }
            }
        }
    }
    private fun reachableService(): Boolean {
        val request = URL("https://api.music.yandex.net/").openConnection() as HttpsURLConnection
        return try {
            request.requestMethod = "HEAD"
            request.instanceFollowRedirects = false
            request.connectTimeout = 1500; request.readTimeout = 1500
            // Even a service 404/500 proves TLS/HTTP connectivity, not account/API health.
            request.responseCode in 100..599
        } catch (_: IOException) { false }
        finally { request.disconnect() }
    }
}
