package dev.botoved.rover.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.botoved.rover.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.UUID

private const val TAG = "Rover"
private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

@SuppressLint("MissingPermission")
class BtRnodeBridge(private val context: Context, private val scope: CoroutineScope) {

    var localPort: Int = 0
        private set

    private var serverSocket: ServerSocket? = null
    private var btSocket: android.bluetooth.BluetoothSocket? = null
    private var bridgeJob: Job? = null

    fun start(mac: String): Boolean {
        return try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: run { AppLogger.e(TAG, "BtRnode: no BT adapter"); return false }

            val device = adapter.getRemoteDevice(mac)
            AppLogger.i(TAG, "BtRnode: connecting to ${device.name} $mac")

            val bt = device.createRfcommSocketToServiceRecord(SPP_UUID)
            adapter.cancelDiscovery()
            bt.connect()
            btSocket = bt
            AppLogger.i(TAG, "BtRnode: BT connected")

            val server = ServerSocket(0).also { serverSocket = it }
            localPort = server.localPort
            AppLogger.i(TAG, "BtRnode: local TCP bridge on port $localPort")

            bridgeJob = scope.launch(Dispatchers.IO) {
                try {
                    val tcp = server.accept()
                    AppLogger.i(TAG, "BtRnode: RNS connected to bridge")
                    val j1 = launch { pipe(bt.inputStream, tcp.getOutputStream(), "BT→TCP") }
                    val j2 = launch { pipe(tcp.getInputStream(), bt.outputStream, "TCP→BT") }
                    j1.join()
                    j2.join()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "BtRnode bridge error: ${e.message}", e)
                } finally {
                    stop()
                }
            }
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "BtRnode start failed: ${e.message}", e)
            false
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, label: String) {
        try {
            val buf = ByteArray(4096)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "BtRnode pipe $label ended: ${e.message}")
        }
    }

    fun stop() {
        bridgeJob?.cancel()
        runCatching { serverSocket?.close() }
        runCatching { btSocket?.close() }
        serverSocket = null
        btSocket = null
        AppLogger.i(TAG, "BtRnode: stopped")
    }
}
