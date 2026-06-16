package dev.botoved.rover.service

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
    private var bleScope: CoroutineScope? = null
    private var channelController: ChannelController? = null
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

        val btManager = context.getSystemService(BluetoothManager::class.java)
        val bScope = CoroutineScope(scope.coroutineContext + Job())
        bleScope = bScope
        val driver = AndroidBLEDriver(
            context = context,
            bluetoothManager = btManager,
            scope = bScope
        )
        bleDriver = driver

        driver.discoveredPeers
            .onEach { peer ->
                Log.i(TAG, "BLE peer discovered: ${peer.address}")
            }
            .launchIn(bScope)

        val iface = BLEInterface(
            name = "rover-ble",
            driver = driver,
            transportIdentity = byteArrayOf()
        )
        bleInterface = iface
        iface.start()
        rns.addInterface(iface)
        Log.i(TAG, "BLE interface started")

        val ratchetDir = File(configDir, "lxmf_storage/lxmf/ratchets")
        if (ratchetDir.isDirectory) {
            ratchetDir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".ratchets") && f.exists() && f.length() == 0L) {
                    f.delete()
                    Log.w(TAG, "Removed 0-byte ratchet file: ${f.name}")
                }
            }
        }

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

    fun getActiveChannel(): String {
        return channelController?.currentChannel?.label ?: "LoRa"
    }

    fun startChannelController(host: String, port: Int) {
        channelController?.stop()
        val controller = ChannelController(
            scope = scope,
            context = context,
            host = host,
            port = port,
            onBleNeeded = {
                recreateBleInterface()
            },
            onBleDetach = {
                destroyBleInterface()
            },
            onChannelChanged = { channel ->
                Log.i(TAG, "Channel changed: ${channel.label}")
                val intent = android.content.Intent("dev.botoved.rover.ACTION_PONG")
                intent.putExtra("channel", channel.label)
                androidx.localbroadcastmanager.content.LocalBroadcastManager
                    .getInstance(context).sendBroadcast(intent)
            }
        )
        channelController = controller
        controller.start()
    }

    private suspend fun recreateBleInterface() {
        bleInterface?.let { old ->
            Transport.getInterfaces().toList().forEach { ref ->
                if (ref.name == old.name) Transport.deregisterInterface(ref)
            }
            old.detach()
        }
        bleScope?.cancel()
        val btManager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
        val bScope = CoroutineScope(scope.coroutineContext + Job())
        bleScope = bScope
        val driver = AndroidBLEDriver(context, btManager, bScope)
        bleDriver = driver
        val iface = BLEInterface("rover-ble", driver, transportIdentity = byteArrayOf())
        bleInterface = iface
        iface.start()
        Reticulum.getInstance().addInterface(iface)
        Transport.registerInterface(iface.toRef())
        Log.i(TAG, "BLE interface recreated")
    }

    private suspend fun destroyBleInterface() {
        bleInterface?.let { iface ->
            iface.detach()
            bleScope?.cancel()
            bleScope = null
            bleDriver = null
            bleInterface = null
            Log.i(TAG, "BLE interface destroyed")
        }
    }

    // Ждёт установки пути к destination. Если пути нет — запрашивает через все интерфейсы.
    // Возвращает true если путь установлен, false если истёк таймаут.
    private suspend fun awaitPath(destHashBytes: ByteArray, timeoutMs: Long = 15_000): Boolean {
        if (Transport.hasPath(destHashBytes)) {
            Log.i(TAG, "Path already known")
            return true
        }
        Log.i(TAG, "No path yet, requesting via all interfaces...")
        Transport.requestPath(destHashBytes)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Transport.hasPath(destHashBytes)) {
                Log.i(TAG, "Path established after ${timeoutMs - (deadline - System.currentTimeMillis())}ms")
                return true
            }
            delay(100)
        }
        Log.w(TAG, "Path not established after ${timeoutMs}ms — sending anyway")
        return false
    }

    // Создаёт serverDest из base64 публичного ключа
    private fun buildServerDest(serverPkBase64: String): Destination? {
        return try {
            val pubKeyBytes = Base64.decode(serverPkBase64, Base64.DEFAULT)
            val serverIdentity = Identity.fromPublicKey(pubKeyBytes, defaultCryptoProvider())
            Destination.create(
                identity = serverIdentity,
                direction = DestinationDirection.OUT,
                type = DestinationType.SINGLE,
                appName = "lxmf",
                aspects = arrayOf("delivery")
            )
        } catch (e: Exception) {
            Log.e(TAG, "buildServerDest failed: ${e.message}", e)
            null
        }
    }

    // Общий метод отправки сообщения. awaitPath=true для REQ/REGISTER/PING, false для CMD.
    private suspend fun sendMessage(
        serverPkBase64: String,
        fields: Map<Int, Any>,
        logTag: String,
        awaitPath: Boolean = true,
        onDelivered: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
    ) {
        val router = lxmRouter ?: run {
            Log.e(TAG, "$logTag: LXMRouter not ready"); return
        }
        val sourceDest = deliveryDestination ?: run {
            Log.e(TAG, "$logTag: no delivery destination"); return
        }
        val serverDest = buildServerDest(serverPkBase64) ?: return

        if (awaitPath) {
            val destHashBytes = serverDest.hash
            if (destHashBytes != null) {
                awaitPath(destHashBytes)
            }
        }

        try {
            val message = LXMessage.create(
                destination = serverDest,
                source = sourceDest,
                title = "",
                content = "",
                fields = fields.toMutableMap(),
                desiredMethod = null
            )
            onDelivered?.let { cb ->
                message.deliveryCallback = { cb() }
            }
            onFailed?.let { cb ->
                message.failedCallback = { cb() }
            }
            router.handleOutbound(message)
            Log.i(TAG, "$logTag sent fields=$fields")
        } catch (e: Exception) {
            Log.e(TAG, "$logTag failed: ${e.message}", e)
        }
    }

    suspend fun sendRegister(destHash: String, serverPkBase64: String, uid: String) {
        sendMessage(
            serverPkBase64 = serverPkBase64,
            fields = RoverCodec.encodeRegister(uid),
            logTag = "REGISTER",
            awaitPath = true,
            onDelivered = { Log.i(TAG, "REGISTER delivered") },
            onFailed = { Log.e(TAG, "REGISTER delivery failed") }
        )
    }

    suspend fun sendReq(destHash: String, serverPkBase64: String, section: String) {
        sendMessage(
            serverPkBase64 = serverPkBase64,
            fields = RoverCodec.encodeReq(section),
            logTag = "REQ(section=$section)",
            awaitPath = true
        )
    }

    suspend fun sendPing(destHash: String, serverPkBase64: String, hashes: Map<String, String>) {
        sendMessage(
            serverPkBase64 = serverPkBase64,
            fields = RoverCodec.encodePing(hashes),
            logTag = "PING",
            awaitPath = true
        )
    }

    suspend fun sendCmd(destHash: String, serverPkBase64: String, fields: Map<Int, Any>) {
        sendMessage(
            serverPkBase64 = serverPkBase64,
            fields = fields,
            logTag = "CMD",
            awaitPath = false,  // CMD отправляем сразу — интерактивное действие
            onFailed = { Log.e(TAG, "CMD delivery failed fields=$fields") }
        )
    }

    fun stop() {
        Log.i(TAG, "Stopping RNS stack")
        channelController?.stop()
        lxmRouter?.stop()
        bleInterface?.detach()
        bleScope?.cancel()
        bleScope = null
        bleDriver = null
        bleInterface = null
        Reticulum.stop()
        channelController = null
        lxmRouter = null
        deliveryDestination = null
        reticulum = null
    }
}
