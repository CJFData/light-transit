package com.thelightphone.sdk

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

data class NetworkStatus(
    val isConnected: Boolean,
    val isWifi: Boolean,
    val isMetered: Boolean
)

class LightConnectivity(androidContext: Context) {
    private val connectivityManager by lazy { androidContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    val currentStatus: NetworkStatus
        @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
        get() {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            return NetworkStatus(
                isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                isMetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
            )
        }


    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun observeNetworkStatus(): Flow<NetworkStatus> {
        return callbackFlow {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(currentStatus)
                }

                override fun onLost(network: Network) {
                    trySend(currentStatus)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    trySend(currentStatus)
                }
            }

            trySend(currentStatus)

            connectivityManager.registerNetworkCallback(request, callback)

            awaitClose {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }
            .distinctUntilChanged()
            .conflate()
    }
}