package com.apps.naviai.core.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real, validated internet-connectivity checks via [ConnectivityManager] --
 * NOT just "connected to a network". [NetworkCapabilities.NET_CAPABILITY_VALIDATED]
 * is what actually confirms a working path to the internet; a Wi-Fi network
 * with no real uplink (captive portal, dead router) still reports as
 * "connected" without it.
 *
 * Used by [com.apps.naviai.audio.VoiceCommandManager] to fall back to
 * on-device speech recognition automatically whenever there's no real
 * connectivity, not only when Offline Mode is manually turned on in
 * Settings -- connectivity changes constantly for someone walking around
 * outdoors, unlike a settings toggle.
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Point-in-time check: is there a network right now with a confirmed working path to the internet? */
    fun hasInternet(): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private val _hasInternetFlow = MutableStateFlow(hasInternet())

    /**
     * Reactive mirror of [hasInternet] for Compose/[StateFlow] consumers --
     * e.g. the Settings screen's Offline Mode switch, which shows as ON
     * whenever this is false even if the user's own saved preference is
     * still off (see [com.apps.naviai.settings.SettingsViewModel]). Kept up
     * to date by this class's own permanent listener (registered once, in
     * [init] -- a `@Singleton` living for the whole process, so there's no
     * matching unregister call needed).
     */
    val hasInternetFlow: StateFlow<Boolean> = _hasInternetFlow.asStateFlow()

    init {
        registerListener { _hasInternetFlow.value = hasInternet() }
    }

    /**
     * Registers [onChanged] to fire whenever [hasInternet] actually flips
     * (not on every minor capability callback, e.g. a signal-strength
     * update on an already-connected network) -- callers should re-check
     * [hasInternet] themselves in response rather than trust anything about
     * the specific [Network] the callback fired for, since more than one
     * network can be active at once and only [ConnectivityManager.getActiveNetwork]
     * matters here. Returns a handle for [unregister]; returns null (and
     * registers nothing) if this device has no [ConnectivityManager] at all.
     */
    fun registerListener(onChanged: () -> Unit): ConnectivityManager.NetworkCallback? {
        val manager = connectivityManager ?: return null
        var lastKnown = hasInternet()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = checkForChange()
            override fun onLost(network: Network) = checkForChange()
            override fun onUnavailable() = checkForChange()

            private fun checkForChange() {
                val now = hasInternet()
                if (now != lastKnown) {
                    lastKnown = now
                    onChanged()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { manager.registerNetworkCallback(request, callback) }
        return callback
    }

    fun unregister(callback: ConnectivityManager.NetworkCallback?) {
        callback ?: return
        val manager = connectivityManager ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
    }
}
