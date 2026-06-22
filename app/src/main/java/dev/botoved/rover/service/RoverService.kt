package dev.botoved.rover.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import dev.botoved.rover.data.RoverRepository
import dev.botoved.rover.data.ServerPreferences
import dev.botoved.rover.rover.protocol.RoverCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import dev.botoved.rover.AppLogger
import org.koin.android.ext.android.inject

class RoverService : Service() {

    private val repository: RoverRepository by inject()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rnsManagerReady = CompletableDeferred<RnsManager>()
    private var rnsManager: RnsManager? = null

    @Volatile
    private var isServerOnline = false
    private var lastPongTime = 0L
    private var cachedServerDst: String? = null
    private var cachedServerPk: String? = null

    // Python transport state
    @Volatile
    private var usePyTransport = false
    private var pyBridge: PyRnsBridge? = null
    private var pyServerDst: String? = null
    @Volatile
    private var pyOnboardingDone = false
    private var pyOnboardingResponse: java.util.concurrent.CompletableFuture<Boolean>? = null
    private var pyWatchdogStarted = false
    // Saved during onboarding; completed by CONFIG handler whenever it arrives
    private var pendingRegisterDst: String? = null

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val fieldsJson = intent?.getStringExtra("fields") ?: return
            serviceScope.launch {
                if (usePyTransport) {
                    val dst = pyServerDst ?: return@launch
                    val bridge = pyBridge ?: return@launch
                    bridge.sendCmd(dst, fieldsJson)
                } else {
                    val manager = rnsManagerReady.await()
                    val prefs = ServerPreferences(this@RoverService)
                    val dst = prefs.serverDestHash.first() ?: return@launch
                    val pk = prefs.serverPk.first() ?: return@launch
                    try {
                        val obj = org.json.JSONObject(fieldsJson)
                        val fields = mutableMapOf<Int, Any>()
                        obj.keys().forEach { k ->
                            val intKey = k.toIntOrNull() ?: return@forEach
                            fields[intKey] = obj.get(k)
                        }
                        manager.sendCmd(dst, pk, fields)
                    } catch (e: Exception) {
                        Log.e(TAG, "ACTION_CMD parse failed: ${e.message}", e)
                    }
                }
            }
        }
    }

    private val registerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val dst = intent?.getStringExtra("dst") ?: return
            val pk = intent.getStringExtra("pk") ?: return
            val uid = intent.getStringExtra("uid") ?: ""
            serviceScope.launch {
                val manager = rnsManagerReady.await()
                manager.sendRegister(dst, pk, uid)
            }
        }
    }

    private val pyRegisterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val dst = intent?.getStringExtra("dst") ?: return
            val uid = intent.getStringExtra("uid") ?: ""
            val pk = intent.getStringExtra("pk") ?: ""
            val tcp = intent.getStringExtra("tcp")
            AppLogger.i(TAG, "Py onboarding: dst=$dst pk=$pk uid=$uid tcp=$tcp")
            if (tcp == null) {
                AppLogger.w(TAG, "Py onboarding: no tcp in QR")
                return
            }
            val parts = tcp.split(":")
            if (parts.size != 2) return
            val host = parts[0]
            val port = parts[1].toIntOrNull() ?: 4242
            serviceScope.launch {
                handlePyRegister(dst, pk, uid, host, port)
            }
        }
    }

    private fun setPyMessageHandler() {
        val bridge = pyBridge ?: return
        bridge.setMessageHandler { sourceHex, fields ->
            val tp = (fields[0] as? Number)?.toInt()
            if (tp == null) return@setMessageHandler
            AppLogger.i(TAG, "Py msg src=$sourceHex tp=$tp fields=$fields")
            when (tp) {
                2 -> { // STATUS — bulk device states
                    val states = fields[2] as? List<*> ?: return@setMessageHandler
                    val arr = org.json.JSONArray()
                    states.filterIsInstance<Map<*, *>>().forEach { s ->
                        val obj = org.json.JSONObject()
                        s.forEach { (k, v) -> if (v != null) obj.put(k.toString(), v) }
                        arr.put(obj)
                    }
                    val intent = Intent("dev.botoved.rover.ACTION_STATUS").apply {
                        putExtra("states", arr.toString())
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                }
                3 -> { // PUSH — single device state update
                    val obj = org.json.JSONObject()
                    fields.forEach { (k, v) -> if (v != null) obj.put(k.toString(), v) }
                    val intent = Intent("dev.botoved.rover.ACTION_PUSH").apply {
                        putExtra("fields", obj.toString())
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                }
                4 -> { // CONFIG
                    val section = fields[1] as? String
                    val hash = fields[2] as? String
                    if (section != null && hash != null) {
                        serviceScope.launch {
                            repository.saveSectionHash(section, hash)
                            when (section) {
                                "m" -> repository.saveMeta(fields)
                                "a" -> repository.saveAreas(fields)
                                "d" -> repository.saveDevices(fields)
                            }
                            repository.markConfigReceived()
                        }
                    }
                    // Complete service-side onboarding on first CONFIG, whenever it arrives
                    val pendingDst = pendingRegisterDst
                    if (pendingDst != null && !usePyTransport) {
                        pendingRegisterDst = null
                        pyServerDst = pendingDst
                        usePyTransport = true
                        AppLogger.i(TAG, "Py onboarding: CONFIG received, approved dst=$pendingDst")
                        startPyWatchdog()
                    }
                    val intent = Intent("dev.botoved.rover.ACTION_CONFIG_RECEIVED")
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                    val resp = pyOnboardingResponse
                    if (resp != null && !pyOnboardingDone) {
                        resp.complete(true)
                    }
                }
                6 -> { // PONG
                    lastPongTime = System.currentTimeMillis()
                    isServerOnline = true
                    val channel = pyBridge?.activeChannel() ?: "TCP"
                    val pongIntent = Intent("dev.botoved.rover.ACTION_PONG").apply {
                        putExtra("channel", channel)
                    }
                    LocalBroadcastManager.getInstance(this).sendBroadcast(pongIntent)
                    // Compare hashes → REQ for mismatches
                    val remoteHashes = fields[2] as? Map<*, *>
                    val dst = pyServerDst ?: pendingRegisterDst
                    if (remoteHashes != null && dst != null) {
                        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            for (section in listOf("m", "a", "d")) {
                                val local = repository.getSectionHash(section)
                                val remote = remoteHashes[section] as? String
                                if (remote != null && local != remote) {
                                    AppLogger.i(TAG, "Hash mismatch $section local=$local remote=$remote -> REQ")
                                    bridge.sendReq(dst, listOf(section))
                                }
                            }
                            if (remoteHashes.isEmpty() || repository.getSectionHashes().isEmpty()) {
                                AppLogger.i(TAG, "PONG: hashes empty, REQ all")
                                bridge.sendReq(dst, listOf("m", "u", "a", "d"))
                            }
                        }
                    } else if (dst != null) {
                        serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            AppLogger.i(TAG, "PONG: no hashes, REQ all")
                            bridge.sendReq(dst, listOf("m", "u", "a", "d"))
                        }
                    }
                }
                7 -> { // FORBIDDEN
                    AppLogger.w(TAG, "Py FORBIDDEN — resetting")
                    serviceScope.launch {
                        val prefs = ServerPreferences(this@RoverService)
                        prefs.clear()
                        repository.clearAll()
                        repository.resetConfigReceived()
                    }
                    val intent = Intent("dev.botoved.rover.ACTION_FORBIDDEN")
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                    val resp = pyOnboardingResponse
                    if (resp != null && !pyOnboardingDone) {
                        resp.complete(false)
                    }
                }
                else -> AppLogger.i(TAG, "Py msg tp=$tp unhandled")
            }
        }
    }

    private suspend fun handlePyRegister(dst: String, pk: String, uid: String, host: String, port: Int) {
        val bridge = pyBridge ?: PyRnsBridge(this).also { pyBridge = it }
        if (!bridge.init()) {
            AppLogger.e(TAG, "Py onboarding: Python init failed")
            return
        }
        val configDir = filesDir.absolutePath + "/rover_rns"
        val identity = bridge.start(configDir, host, port)
        if (identity == null) {
            AppLogger.e(TAG, "Py onboarding: start failed")
            return
        }
        AppLogger.i(TAG, "Py onboarding client identity=$identity")

        // Pre-compute server LXMF delivery destination from QR public key.
        // Path table is keyed by delivery hash, not identity hash — must set before sendRegister.
        if (pk.isNotEmpty()) {
            val pkSet = bridge.setServerPk(pk)
            AppLogger.i(TAG, "Py onboarding: setServerPk=$pkSet")
        }

        pendingRegisterDst = dst
        setPyMessageHandler()

        val sent = bridge.sendRegister(dst, uid)
        if (!sent) {
            AppLogger.e(TAG, "Py onboarding: sendRegister failed")
            pendingRegisterDst = null
            return
        }
        AppLogger.i(TAG, "Py onboarding: REGISTER sent, CONFIG will arrive asynchronously")
        // Service state (pyServerDst, usePyTransport, watchdog) is completed in
        // setPyMessageHandler() tp==4 handler when CONFIG is delivered by the server.
    }

    private fun startPyWatchdog() {
        val bridge = pyBridge ?: return
        if (pyWatchdogStarted) return
        pyWatchdogStarted = true
        serviceScope.launch {
            while (isActive) {
                delay(30_000)
                val elapsed = System.currentTimeMillis() - lastPongTime
                if (elapsed > 30_000 && isServerOnline) {
                    isServerOnline = false
                    AppLogger.w(TAG, "Py watchdog: no PONG ${elapsed}ms, offline")
                }
                val dst = pyServerDst
                if (dst == null) {
                    AppLogger.w(TAG, "Py watchdog: no dst, skip")
                    continue
                }
                if (!isServerOnline || !repository.isConfigReceived) {
                    AppLogger.i(TAG, "Py watchdog: REQ all sections")
                    serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        bridge.sendReq(dst, listOf("m", "u", "a", "d"))
                    }
                } else {
                    val hashes = repository.getSectionHashes()
                    AppLogger.i(TAG, "Py watchdog: PING hashes=$hashes")
                    serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        bridge.sendPing(dst, hashes)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val lbm = LocalBroadcastManager.getInstance(this)
        if (USE_LEGACY_RNS) {
            lbm.registerReceiver(registerReceiver, IntentFilter("dev.botoved.rover.ACTION_REGISTER"))
        }
        lbm.registerReceiver(cmdReceiver, IntentFilter("dev.botoved.rover.ACTION_CMD"))
        lbm.registerReceiver(pyRegisterReceiver, IntentFilter("dev.botoved.rover.ACTION_REGISTER"))
        lbm.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handleDebugSendReqArray()
            }
        }, IntentFilter("dev.botoved.rover.ACTION_DEBUG_SEND_REQ_ARRAY"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLogger.i(TAG, "RoverService starting")
        startForeground(NOTIFICATION_ID, buildNotification("Инициализация..."))

        if (USE_LEGACY_RNS) serviceScope.launch {
            try {
                val identity = RoverIdentity.getOrCreate(applicationContext)
                Log.i(TAG, "Identity ready: ${identity.hexHash}")

                val manager = RnsManager(applicationContext, serviceScope)
                manager.onConfigReceived = { fields ->
                    val section = fields?.get(1) as? String
                    val data = fields?.get(3)
                    val sectionHash = fields?.get(2) as? String
                    Log.i(TAG, "CONFIG section=$section dataType=${data?.javaClass?.simpleName}")
                    if (data != null) Log.i(TAG, "CONFIG data preview=${
                        if (data is List<*>) "list(${data.size})"
                        else if (data is Map<*, *>) "map(${data.size} keys)"
                        else data.toString().take(100)
                    }")
                    if (section != null && sectionHash != null) {
                        repository.saveSectionHash(section, sectionHash)
                        Log.i(TAG, "CONFIG saved hash section=$section hash=$sectionHash")
                    }
                    serviceScope.launch {
                        when (section) {
                            "m" -> repository.saveMeta(fields ?: emptyMap<Any, Any>())
                            "a" -> repository.saveAreas(fields ?: emptyMap<Any, Any>())
                            "d" -> {
                                repository.saveDevices(fields ?: emptyMap<Any, Any>())
                                repository.markConfigReceived()
                            }
                        }
                    }
                    if (section == "d") {
                        val cfgIntent = Intent("dev.botoved.rover.ACTION_CONFIG_RECEIVED")
                        LocalBroadcastManager.getInstance(this@RoverService)
                            .sendBroadcast(cfgIntent)
                    }
                }
                manager.onMessageReceived = { message ->
                    val f = message.fields as? Map<Any?, Any?>
                    val tp = (f?.get(0) as? Number)?.toInt()
                    when (tp) {
                        2 -> {
                            val states = f?.get(2) as? List<*>
                            if (states != null) {
                                if (states.isNotEmpty()) {
                                    val first = states.firstOrNull() as? Map<*, *>
                                    if (first != null) {
                                        val keyTypes = first.keys.joinToString { "${it}(${it?.javaClass?.simpleName})" }
                                        Log.i(TAG, "STATUS first device key types: $keyTypes")
                                    }
                                }
                                val intent = Intent("dev.botoved.rover.ACTION_STATUS").apply {
                                    val arr = org.json.JSONArray()
                                    states.filterIsInstance<Map<*, *>>().forEach { s ->
                                        val obj = org.json.JSONObject()
                                        s.forEach { (k, v) -> obj.put(k.toString(), v) }
                                        arr.put(obj)
                                    }
                                    putExtra("states", arr.toString())
                                }
                                LocalBroadcastManager.getInstance(this@RoverService).sendBroadcast(intent)
                                Log.i(TAG, "STATUS broadcast: ${states.size} devices")
                            }
                        }
                        3 -> {
                            val keyTypes = f?.keys?.joinToString { "${it}(${it?.javaClass?.simpleName})" }
                            Log.i(TAG, "PUSH keys: $keyTypes")
                            val intent = Intent("dev.botoved.rover.ACTION_PUSH").apply {
                                val obj = org.json.JSONObject()
                                f?.forEach { (k, v) -> obj.put(k.toString(), v) }
                                putExtra("fields", obj.toString())
                            }
                            LocalBroadcastManager.getInstance(this@RoverService).sendBroadcast(intent)
                            Log.i(TAG, "PUSH broadcast id=${f?.get(9)}")
                        }
                        7 -> {
                            Log.w(TAG, "FORBIDDEN received — access revoked, resetting registration")
                            serviceScope.launch {
                                val prefs = ServerPreferences(applicationContext)
                                prefs.clear()
                                repository.clearAll()
                                repository.resetConfigReceived()
                                Log.i(TAG, "DB cleared on forbidden")
                            }
                            val intent = Intent("dev.botoved.rover.ACTION_FORBIDDEN")
                            LocalBroadcastManager.getInstance(this@RoverService).sendBroadcast(intent)
                        }
                        6 -> {
                            Log.i(TAG, "PING/PONG received — link alive")
                            lastPongTime = System.currentTimeMillis()
                            isServerOnline = true
                            val channel = manager.getActiveChannel()
                            val pongIntent = Intent("dev.botoved.rover.ACTION_PONG").apply {
                                putExtra("channel", channel)
                            }
                            LocalBroadcastManager.getInstance(this@RoverService)
                                .sendBroadcast(pongIntent)

                            val pongHashes = RoverCodec.decodeHashes(f ?: emptyMap<Any, Any>())
                            if (pongHashes != null) {
                                val dst = cachedServerDst
                                val pk = cachedServerPk
                                if (dst != null && pk != null) {
                                    serviceScope.launch {
                                        for (section in listOf("m", "a", "d")) {
                                            val local = repository.getSectionHash(section)
                                            val remote = pongHashes[section]
                                            if (remote != null && local != remote) {
                                                Log.i(TAG, "Hash mismatch section=$section local=$local remote=$remote -> REQ")
                                                manager.sendReq(dst, pk, section)
                                            }
                                        }
                                        if (pongHashes.isEmpty() || repository.getSectionHashes().isEmpty()) {
                                            Log.i(TAG, "PONG: hashes empty, sending REQ for all sections")
                                            for (section in listOf("m", "a", "d")) {
                                                manager.sendReq(dst, pk, section)
                                                delay(200)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> Log.i(TAG, "Message tp=$tp — unhandled")
                    }
                }
                manager.start(identity)
                rnsManager = manager
                rnsManagerReady.complete(manager)

                val prefs = ServerPreferences(applicationContext)

                // Start ChannelController for automatic interface management
                val tcpPref: String? = prefs.serverTcp.first()
                if (tcpPref != null) {
                    val parts = tcpPref.split(":")
                    if (parts.size == 2) {
                        val tcpHost = parts[0]
                        val tcpPort = parts[1].toIntOrNull() ?: 4242
                        manager.startChannelController(tcpHost, tcpPort)
                        Log.i(TAG, "ChannelController started: $tcpHost:$tcpPort")
                    }
                }

                // Start watchdog early, before auto-reconnect loop
                serviceScope.launch {
                    while (isActive) {
                        delay(30_000)
                        if (usePyTransport) continue
                        val elapsed = System.currentTimeMillis() - lastPongTime
                        if (elapsed > 30_000 && isServerOnline) {
                            isServerOnline = false
                            Log.w(TAG, "Watchdog: no PONG for ${elapsed}ms, marking offline")
                        }
                        val dst = cachedServerDst
                        val pk = cachedServerPk
                        if (!isServerOnline || !repository.isConfigReceived) {
                            if (dst == null || pk == null) {
                                Log.w(TAG, "Watchdog: dst/pk not cached, skipping cycle")
                                continue
                            }
                            if (!repository.isConfigReceived) {
                                Log.i(TAG, "Watchdog: no config, sending REQ for all sections")
                                serviceScope.launch {
                                    manager.sendReq(dst, pk, "m")
                                }
                                serviceScope.launch {
                                    manager.sendReq(dst, pk, "a")
                                }
                                serviceScope.launch {
                                    manager.sendReq(dst, pk, "d")
                                }
                            }
                        } else {
                            if (dst != null && pk != null) {
                                Log.i(TAG, "Watchdog: sending PING")
                                manager.sendPing(dst, pk, repository.getSectionHashes())
                            } else {
                                Log.w(TAG, "Watchdog: dst/pk null in online branch, skipping")
                            }
                        }
                    }
                }

                // Auto-reconnect if already registered
                val reg = prefs.isRegistered.first()
                if (reg == "approved") {
                    cachedServerDst = prefs.serverDestHash.first()
                    cachedServerPk = prefs.serverPk.first()
                    Log.i(TAG, "Auto-reconnect: dst=$cachedServerDst pk=$cachedServerPk")
                    val srvDst = cachedServerDst
                    val srvPk = cachedServerPk
                    if (srvDst != null && srvPk != null) {
                        repository.resetConfigReceived()
                        repository.clearAll()
                        Log.i(TAG, "Auto-reconnect: DB cleared, sending REQ for all sections")
                        serviceScope.launch { manager.sendReq(srvDst, srvPk, "m") }
                        serviceScope.launch { manager.sendReq(srvDst, srvPk, "a") }
                        serviceScope.launch { manager.sendReq(srvDst, srvPk, "d") }
                        updateNotification("Подключено")
                    } else {
                        Log.w(TAG, "Auto-reconnect: dst or pk is null, cannot send REQ")
                    }
                } else {
                    updateNotification("Подключение...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "RNS init failed: ${e.message}", e)
                updateNotification("Ошибка подключения")
            }
        }

        if (!USE_LEGACY_RNS) serviceScope.launch {
            val prefs = ServerPreferences(applicationContext)
            val reg = prefs.isRegistered.first()
            if (reg != "approved") return@launch
            val dst = prefs.serverDestHash.first() ?: return@launch
            val pk = prefs.serverPk.first() ?: return@launch
            val tcp = prefs.serverTcp.first() ?: return@launch
            val parts = tcp.split(":")
            val host = parts.getOrNull(0) ?: return@launch
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 4242
            AppLogger.i(TAG, "Py auto-reconnect: dst=${dst.take(16)} tcp=$tcp")
            val bridge = pyBridge ?: PyRnsBridge(this@RoverService).also { pyBridge = it }
            if (!bridge.init()) { AppLogger.e(TAG, "Py auto-reconnect: init failed"); return@launch }
            val configDir = filesDir.absolutePath + "/rover_rns"
            val identity = bridge.start(configDir, host, port)
            if (identity == null) { AppLogger.e(TAG, "Py auto-reconnect: start failed"); return@launch }
            AppLogger.i(TAG, "Py auto-reconnect: identity=$identity")
            bridge.setServerPk(pk)
            pyServerDst = dst
            usePyTransport = true
            setPyMessageHandler()
            startPyWatchdog()
            // send_req blocks on path-wait internally — run on IO to not stall the coroutine
            serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                AppLogger.i(TAG, "Py auto-reconnect: sending initial REQ")
                val ok = bridge.sendReq(dst, listOf("m", "u", "a", "d"))
                AppLogger.i(TAG, "Py auto-reconnect: initial REQ ok=$ok")
            }
        }

        return START_STICKY
    }

    // Debug: send REQ with array of sections (m, a, d)
    private fun handleDebugSendReqArray() {
        AppLogger.i(TAG, "DEBUG: handling debugSendReqArray")
        if (PyRnsBridge.isBridgeAvailable()) {
            PyRnsBridge.debugSendReqArray()
        } else {
            AppLogger.e(TAG, "DEBUG: Python bridge not available")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AppLogger.i(TAG, "RoverService stopping")
        if (USE_LEGACY_RNS) {
            LocalBroadcastManager.getInstance(this).apply {
                unregisterReceiver(registerReceiver)
                unregisterReceiver(cmdReceiver)
            }
            rnsManager?.stop()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "rover_service"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Rover", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("Rover")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "Rover"
        const val NOTIFICATION_ID = 1
        // TODO: set true and remove this flag after Kotlin RNS stack is fully deleted
        const val USE_LEGACY_RNS = false
    }
}
