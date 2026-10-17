package com.spekterm.android.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.spekterm.android.service.Unreachability.Network
import com.spekterm.android.service.Unreachability.NetworkKind

/** The phone's network as `scan-connect-zh` D6 sees it: the active network's transports apart from a VPN's. */
object Networks {
    fun current(context: Context): Network {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return Network(NetworkKind.NONE, vpn = false)
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.MOBILE
            else -> NetworkKind.OTHER
        }
        return Network(kind, vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }
}
