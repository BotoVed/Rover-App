package dev.botoved.rover.service

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import network.reticulum.Reticulum
import network.reticulum.android.ble.AndroidBLEDriver
import network.reticulum.common.DestinationDirection
import network.reticulum.common.DestinationType
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.interfaces.ble.BLEInterface
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.Transport
import network.reticulum.lxmf.LXMessage
import network.reticulum.lxmf.LXMRouter
import dev.botoved.rover.rover.protocol.RoverCodec

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
    var deliveryDestination: Destination? = null
        private set

    var onMessageReceived: ((LXMessage) -> Unit)? = null
    var onConfigReceived: ((Map<*, *>?) -> Unit)? = null

    fun start(identity: Identity) {
        val configDir = context.filesDir.absolutePath

        Log.i(TAG, "Starting Reticulum, configDir=$configDir")
        val rns = Reticulum.start(
            configDir = configDir,
            enableTransport = true,
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

        val sourceDest = router.registerDeliveryIdentity(identity)
        deliveryDestination = sourceDest
        Log.i(TAG, "LXMF delivery destination: ${sourceDest.hexHash}")

        router.registerDeliveryCallback { message ->
            val fields = message.fields
            val tp = (fields?.get(0) as? Number)?.toInt()
            Log.i(TAG, "LXMF received tp=$tp")
            when (tp) {
                4 -> {
                    Log.i(TAG, "CONFIG received — onboarding approved!")
                    onConfigReceived?.invoke(fields)
                }
                else -> {
                    Log.i(TAG, "Message tp=$tp — passing to handler")
                    onMessageReceived?.invoke(message)
                }
            }
        }

        router.start()
        Log.i(TAG, "LXMRouter started")
    }

    suspend fun addTcpInterfaceAndWait(
        host: String,
        port: Int,
        timeoutMs: Long = 15_000,
    ): Boolean {
        val rns = reticulum ?: return false
        return try {
            val tcpInterface = TCPClientInterface(
                name = "rover-tcp",
                targetHost = host,
                targetPort = port,
            )
            tcpInterface.start()
            rns.addInterface(tcpInterface)
            Transport.registerInterface(tcpInterface.toRef())
            Log.i(TAG, "TCP interface added: $host:$port, waiting for online...")

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (tcpInterface.online.value) {
                    val elapsed = timeoutMs - (deadline - System.currentTimeMillis())
                    Log.i(TAG, "TCP interface online after ${elapsed}ms")
                    deliveryDestination?.announce()
                    Log.i(TAG, "Delivery destination announced: ${deliveryDestination?.hexHash}")
                    return true
                }
                delay(200)
            }
            Log.w(TAG, "TCP interface timeout — not online after ${timeoutMs}ms")
            false
        } catch (e: Exception) {
            Log.e(TAG, "TCP interface failed: ${e.message}", e)
            false
        }
    }

    suspend fun sendRegister(destHash: String, serverPkBase64: String, uid: String) {
        val router = lxmRouter ?: run {
            Log.e(TAG, "LXMRouter not ready")
            return
        }
        val sourceDest = deliveryDestination ?: run {
            Log.e(TAG, "Delivery destination not ready")
            return
        }

        try {
            val pubKeyBytes = Base64.decode(serverPkBase64, Base64.DEFAULT)
            val serverIdentity = Identity.fromPublicKey(
                pubKeyBytes,
                defaultCryptoProvider()
            )

            Log.i(TAG, "Server identity hexHash: ${serverIdentity.hexHash}")
            Log.i(TAG, "Server identity hash bytes: ${serverIdentity.hash?.joinToString("") { "%02x".format(it) }}")
            Log.i(TAG, "Server getPublicKey(): ${serverIdentity.getPublicKey()?.joinToString("") { "%02x".format(it) }}")

            val appName = "lxmf"  // LXMRouter.APP_NAME
            val aspects = arrayOf("delivery")  // LXMRouter.DELIVERY_ASPECT
            val serverDest = Destination.create(
                identity = serverIdentity,
                direction = DestinationDirection.OUT,
                type = DestinationType.SINGLE,
                appName = appName,
                aspects = aspects
            )

            Log.i(TAG, "Server destination hash: ${serverDest.hexHash}")
            Log.i(TAG, "Expected hash from QR: $destHash")

            val fields: MutableMap<Int, Any> = RoverCodec.encodeRegister(uid).toMutableMap()

            val message = LXMessage.create(
                destination = serverDest,
                source = sourceDest,
                title = "",
                content = "",
                fields = fields,
                desiredMethod = null
            )

            message.deliveryCallback = { msg ->
                val dstHex = msg.destinationHash?.joinToString("") { "%02x".format(it) }
                Log.i(TAG, "REGISTER delivered to $dstHex")
            }
            message.failedCallback = {
                Log.e(TAG, "REGISTER delivery failed")
            }

            router.handleOutbound(message)
            Log.i(TAG, "REGISTER sent (tp=9)")
        } catch (e: Exception) {
            Log.e(TAG, "sendRegister failed: ${e.message}", e)
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping RNS stack")
        lxmRouter?.stop()
        bleInterface?.detach()
        bleDriver?.shutdown()
        Reticulum.stop()
        lxmRouter = null
        deliveryDestination = null
        bleInterface = null
        bleDriver = null
        reticulum = null
    }
}
