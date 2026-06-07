package dev.botoved.rover.service

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import network.reticulum.Reticulum
import network.reticulum.android.ble.AndroidBLEDriver
import network.reticulum.identity.Identity
import network.reticulum.interfaces.ble.BLEInterface
import network.reticulum.lxmf.LXMessage
import network.reticulum.lxmf.LXMRouter

class RnsManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val TAG = "Rover"

    private var reticulum: Reticulum? = null
    private var bleDriver: AndroidBLEDriver? = null
    private var bleInterface: BLEInterface? = null
    var lxmRouter: LXMRouter? = null
        private set

    var onMessageReceived: ((LXMessage) -> Unit)? = null

    fun start(identity: Identity) {
        val configDir = context.filesDir.absolutePath

        Log.i(TAG, "Starting Reticulum, configDir=$configDir")
        val rns = Reticulum.start(
            configDir = configDir,
            enableTransport = false,
            shareInstance = false
        )
        reticulum = rns
        Log.i(TAG, "Reticulum started")

        // BLE driver
        val btManager = context.getSystemService(BluetoothManager::class.java)
        val driver = AndroidBLEDriver(
            context = context,
            bluetoothManager = btManager,
            scope = scope
        )
        bleDriver = driver

        // Слушаем обнаруженные пиры
        driver.discoveredPeers
            .onEach { peer ->
                Log.i(TAG, "BLE peer discovered: ${peer.address}")
            }
            .launchIn(scope)

        // BLE interface
        val iface = BLEInterface(
            name = "rover-ble",
            driver = driver,
            transportIdentity = byteArrayOf()
        )
        bleInterface = iface
        iface.start()
        rns.addInterface(iface)
        Log.i(TAG, "BLE interface started")

        // LXMF router
        val router = LXMRouter(
            identity = identity,
            storagePath = configDir,
            autopeer = false
        )
        lxmRouter = router

        val destination = router.registerDeliveryIdentity(identity)
        Log.i(TAG, "LXMF delivery destination: ${destination.hexHash}")

        router.registerDeliveryCallback { message ->
            Log.i(TAG, "LXMF message received, tp=${message.fields[0]}")
            onMessageReceived?.invoke(message)
        }

        router.start()
        Log.i(TAG, "LXMRouter started")
    }

    fun stop() {
        Log.i(TAG, "Stopping RNS stack")
        lxmRouter?.stop()
        bleInterface?.detach()
        bleDriver?.shutdown()
        Reticulum.stop()
        lxmRouter = null
        bleInterface = null
        bleDriver = null
        reticulum = null
    }
}
