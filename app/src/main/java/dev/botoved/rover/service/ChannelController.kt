package dev.botoved.rover.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.reticulum.Reticulum
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import java.net.Socket
import java.net.SocketTimeoutException

sealed class Channel(val label: String) {
    data object WiFi : Channel("WiFi")
    data object Mobile4G : Channel("4G")
    data object BLE : Channel("BLE")
    data object LoRa : Channel("LoRa")
}

class ChannelController(
    private val scope: CoroutineScope,
    context: Context,
    private val host: String,
    private val port: Int,
    private val onBleNeeded: suspend () -> Unit,
    private val onBleDetach: suspend () -> Unit,
    private val onChannelChanged: (Channel) -> Unit,
) {
    companion object {
        private const val TAG = "Rover"
        private const val PROBE_TIMEOUT_MS = 5_000L
        private const val PERIODIC_INTERVAL_MS = 60_000L
        private const val ONLINE_MONITOR_INTERVAL_MS = 5_000L
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    var currentChannel: Channel = Channel.LoRa
        private set
    private var tcpIface: TCPClientInterface? = null
    private var onlineMonitorJob: Job? = null
    private var periodicJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun probeTcp(): Boolean {
        return try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), PROBE_TIMEOUT_MS.toInt())
            socket.close()
            Log.i(TAG, "TCP probe OK: $host:$port")
            true
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "TCP probe timeout: $host:$port")
            false
        } catch (e: Exception) {
            Log.w(TAG, "TCP probe failed: ${e.message}")
            false
        }
    }

    private fun determineBestTcpChannel(): Channel {
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        return if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            Channel.WiFi
        } else {
            Channel.Mobile4G
        }
    }

    private fun detachAllInterfaces() {
        Transport.getInterfaces().toList().forEach { ifaceRef ->
            Transport.deregisterInterface(ifaceRef)
        }
        Log.i(TAG, "All interfaces detached from Transport")
    }

    private fun startTcpOnlineMonitor() {
        onlineMonitorJob?.cancel()
        onlineMonitorJob = scope.launch {
            while (isActive) {
                delay(ONLINE_MONITOR_INTERVAL_MS)
                val online = tcpIface?.online?.value == true
                if (!online && (currentChannel is Channel.WiFi || currentChannel is Channel.Mobile4G)) {
                    Log.w(TAG, "TCP interface offline, switching to BLE")
                    switchTo(Channel.BLE)
                }
            }
        }
    }

    fun switchTo(new: Channel) {
        if (new == currentChannel) return
        Log.i(TAG, "Switching: ${currentChannel.label} → ${new.label}")
        detachAllInterfaces()
        tcpIface = null
        onlineMonitorJob?.cancel()
        when (new) {
            is Channel.WiFi, is Channel.Mobile4G -> {
                val iface = TCPClientInterface("rover-tcp", host, port)
                tcpIface = iface
                iface.start()
                Reticulum.getInstance().addInterface(iface)
                Transport.registerInterface(iface.toRef())
                scope.launch { onBleDetach() }
                startTcpOnlineMonitor()
            }
            is Channel.BLE -> {
                scope.launch { onBleNeeded() }
            }
            is Channel.LoRa -> {
                scope.launch { onBleDetach() }
            }
        }
        currentChannel = new
        onChannelChanged(new)
    }

    fun startNetworkListener() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                        Log.i(TAG, "WiFi available")
                        if (currentChannel !is Channel.WiFi) {
                            scope.launch {
                                if (probeTcp()) {
                                    switchTo(determineBestTcpChannel())
                                }
                            }
                        }
                    }
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> {
                        Log.i(TAG, "Cellular available")
                        if (currentChannel !is Channel.WiFi && currentChannel !is Channel.Mobile4G) {
                            scope.launch {
                                if (probeTcp()) {
                                    switchTo(determineBestTcpChannel())
                                }
                            }
                        }
                    }
                }
            }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        networkCallback = callback
    }

    fun stopNetworkListener() {
        networkCallback?.let { cm.unregisterNetworkCallback(it) }
        networkCallback = null
    }

    fun startPeriodicProbe() {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (isActive) {
                delay(PERIODIC_INTERVAL_MS)
                if (currentChannel is Channel.BLE || currentChannel is Channel.LoRa) {
                    if (probeTcp()) {
                        switchTo(determineBestTcpChannel())
                    }
                }
            }
        }
    }

    fun stopPeriodicProbe() {
        periodicJob?.cancel()
        periodicJob = null
    }

    fun start() {
        Log.i(TAG, "ChannelController starting")
        startNetworkListener()
        startPeriodicProbe()
        if (probeTcp()) {
            switchTo(determineBestTcpChannel())
        } else {
            Log.i(TAG, "Initial TCP probe failed, staying on BLE")
        }
    }

    fun stop() {
        Log.i(TAG, "ChannelController stopping")
        stopPeriodicProbe()
        stopNetworkListener()
        onlineMonitorJob?.cancel()
        detachAllInterfaces()
        tcpIface = null
        currentChannel = Channel.LoRa
    }
}
