package com.socialnetwork.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * Kotlin-owned connectivity lifecycle extracted from MainActivity.
 * The listener keeps Android/UI-specific behavior in the Activity while the
 * callback registration and compatibility checks live in Kotlin.
 */
class ConnectivityMonitor(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectivityChanged(online: Boolean)
        fun onConnectivityAvailable()
    }

    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun isNetworkAvailable(): Boolean {
        return try {
            val cm = manager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(network) ?: return false
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
                Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                info?.isConnected == true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun register() {
        val cm = manager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || callback != null) return

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val online = isNetworkAvailable()
                listener.onConnectivityChanged(online)
                if (online) listener.onConnectivityAvailable()
            }

            override fun onLost(network: Network) {
                listener.onConnectivityChanged(isNetworkAvailable())
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, networkCallback)
            }
        } catch (_: Exception) {
            return
        }
        callback = networkCallback
    }

    fun unregister() {
        val cm = manager ?: return
        val registered = callback ?: return
        try {
            cm.unregisterNetworkCallback(registered)
        } catch (_: Exception) {
            // Already unregistered or unavailable during teardown.
        }
        callback = null
    }
}
