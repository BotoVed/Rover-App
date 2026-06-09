package dev.botoved.rover.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log

object WifiChecker {

    private const val TAG = "Rover"

    fun currentSsid(context: Context): String? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            val ssid = info.ssid?.removeSurrounding("\"")
            if (ssid == "<unknown ssid>" || ssid.isNullOrEmpty()) null else ssid
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get SSID: ${e.message}")
            null
        }
    }

    fun isOnSsid(context: Context, targetSsid: String): Boolean {
        val current = currentSsid(context)
        Log.i(TAG, "Current SSID: $current, target: $targetSsid")
        return current == targetSsid
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
