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
    private var tcpInterface: TCPClientInterface? = null
    var isTcpAdded: Boolean = false
        private set
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
            val f2 = message.fields
            Log.i(TAG, "LXMF incoming: fields=${f2?.javaClass?.simpleName} size=${(f2 as? Map<*, *>)?.size}")
            Log.i(TAG, "LXMF incoming keys: ${(f2 as? Map<*, *>)?.keys?.map { "${it}(${it?.javaClass?.simpleName})" }}")
            val fields = message.fields
            val f = fields as? Map<Any?, Any?>
            val tp = (f?.get(0) as? Number)?.toInt()
            Log.i(TAG, "LXMF received tp=$tp")
            when (tp) {
                1, 4 -> {
                    Log.i(TAG, "CONFIG received — tp=$tp")
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
            val iface = TCPClientInterface(
                name = "rover-tcp",
                targetHost = host,
                targetPort = port,
            )
            tcpInterface = iface
            isTcpAdded = true
            iface.start()
            rns.addInterface(iface)
            Transport.registerInterface(iface.toRef())
            Log.i(TAG, "TCP interface added: $host:$port, waiting for online...")

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (iface.online.value) {
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

    fun getActiveChannel(context: Context): String {
        val tcpOnline = tcpInterface?.online?.value == true
        val bleOnline = bleInterface?.online?.value == true
        return when {
            tcpOnline && WifiChecker.isWifiConnected(context) -> "WiFi"
            tcpOnline -> "4G"
            bleOnline -> "BLE"
            else -> "LoRa"
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

            // Ждём установки пути к серверу перед отправкой REGISTER
            val destHashBytes = serverDest.hash
            if (destHashBytes != null) {
                if (!Transport.hasPath(destHashBytes)) {
                    Log.i(TAG, "No path to server yet, requesting...")
                    Transport.requestPath(destHashBytes)
                    val pathDeadline = System.currentTimeMillis() + 15_000
                    while (System.currentTimeMillis() < pathDeadline) {
                        if (Transport.hasPath(destHashBytes)) {
                            Log.i(TAG, "Path established, sending REGISTER")
                            break
                        }
                        delay(100)
                    }
                    if (!Transport.hasPath(destHashBytes)) {
                        Log.w(TAG, "Path not established after 15s, sending REGISTER anyway")
                    }
                } else {
                    Log.i(TAG, "Path to server already known, sending REGISTER")
                }
            }

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

    suspend fun sendCmd(destHash: String, serverPkBase64: String, fields: Map<Int, Any>) {
        val router = lxmRouter ?: run {
            Log.e(TAG, "sendCmd: LXMRouter not ready"); return
        }
        val sourceDest = deliveryDestination ?: run {
            Log.e(TAG, "sendCmd: no delivery destination"); return
        }
        try {
            val pubKeyBytes = Base64.decode(serverPkBase64, Base64.DEFAULT)
            val serverIdentity = Identity.fromPublicKey(pubKeyBytes, defaultCryptoProvider())
            val serverDest = Destination.create(
                identity = serverIdentity,
                direction = DestinationDirection.OUT,
                type = DestinationType.SINGLE,
                appName = "lxmf",
                aspects = arrayOf("delivery")
            )
            val message = LXMessage.create(
                destination = serverDest,
                source = sourceDest,
                title = "",
                content = "",
                fields = fields.toMutableMap(),
                desiredMethod = null
            )
            message.failedCallback = {
                Log.e(TAG, "CMD delivery failed fields=$fields")
            }
            router.handleOutbound(message)
            Log.i(TAG, "CMD sent fields=$fields")
        } catch (e: Exception) {
            Log.e(TAG, "sendCmd failed: ${e.message}", e)
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
        tcpInterface = null
        isTcpAdded = false
        reticulum = null
    }
}
