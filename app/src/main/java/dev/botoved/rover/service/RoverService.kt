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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class RoverService : Service() {

    private val repository: RoverRepository by inject()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rnsManagerReady = CompletableDeferred<RnsManager>()
    private var rnsManager: RnsManager? = null

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val fieldsJson = intent?.getStringExtra("fields") ?: return
            serviceScope.launch {
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

    private val registerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val dst = intent?.getStringExtra("dst") ?: return
            val pk = intent.getStringExtra("pk") ?: return
            val tcp = intent.getStringExtra("tcp")
            val ssid = intent.getStringExtra("ssid")
            val uid = intent.getStringExtra("uid") ?: ""

            serviceScope.launch {
                val manager = rnsManagerReady.await()
                if (tcp != null && ssid != null) {
                    if (WifiChecker.isOnSsid(this@RoverService, ssid)) {
                        val parts = tcp.split(":")
                        if (parts.size == 2) {
                            val online = manager.addTcpInterfaceAndWait(
                                parts[0], parts[1].toIntOrNull() ?: 4242,
                            )
                            if (online != true) {
                                Log.w(TAG, "TCP not online, REGISTER may fail")
                            }
                        }
                    } else {
                        Log.i(TAG, "Not on target WiFi ($ssid), skipping TCP")
                    }
                }
                manager.sendRegister(dst, pk, uid)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(registerReceiver,
                IntentFilter("dev.botoved.rover.ACTION_REGISTER"))
            registerReceiver(cmdReceiver,
                IntentFilter("dev.botoved.rover.ACTION_CMD"))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "RoverService starting")
        startForeground(NOTIFICATION_ID, buildNotification("Инициализация..."))

        serviceScope.launch {
            try {
                val identity = RoverIdentity.getOrCreate(applicationContext)
                Log.i(TAG, "Identity ready: ${identity.hexHash}")

                val manager = RnsManager(applicationContext, serviceScope)
                manager.onConfigReceived = { fields ->
                    val section = fields?.get(1) as? String
                    val data = fields?.get(3)
                    Log.i(TAG, "CONFIG section=$section dataType=${data?.javaClass?.simpleName}")
                    if (data != null) Log.i(TAG, "CONFIG data preview=${
                        if (data is List<*>) "list(${data.size})"
                        else if (data is Map<*, *>) "map(${data.size} keys)"
                        else data.toString().take(100)
                    }")
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
                        }
                        else -> Log.i(TAG, "Message tp=$tp — unhandled")
                    }
                }
                manager.start(identity)
                rnsManager = manager
                rnsManagerReady.complete(manager)

                // Auto-reconnect if already registered
                val prefs = ServerPreferences(applicationContext)
                val reg = prefs.isRegistered.first()
                if (reg == "approved") {
                    val dst = prefs.serverDestHash.first()
                    val pk = prefs.serverPk.first()
                    val tcp = prefs.serverTcp.first()
                    val ssid = prefs.serverSsid.first()
                    val uid = prefs.uid.first()
                    Log.i(TAG, "Auto-reconnect: dst=$dst tcp=$tcp ssid=$ssid")
                    if (dst != null && pk != null) {
                        repository.resetConfigReceived()
                        repository.clearAll()
                        Log.i(TAG, "Auto-reconnect: DB cleared")
                        if (tcp != null && ssid != null) {
                            if (WifiChecker.isOnSsid(this@RoverService, ssid)) {
                                val parts = tcp.split(":")
                                if (parts.size == 2) {
                                    val online = manager.addTcpInterfaceAndWait(
                                        parts[0], parts[1].toIntOrNull() ?: 4242
                                    )
                                    Log.i(TAG, "Auto-reconnect TCP online=$online")
                                }
                            } else {
                                Log.i(TAG, "Auto-reconnect: not on WiFi $ssid, using BLE only")
                            }
                        }
                        manager.sendRegister(dst, pk, uid ?: "")
                        Log.i(TAG, "Auto-reconnect: REGISTER sent")
                        updateNotification("Подключено")
                    }
                } else {
                    updateNotification("Подключение...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "RNS init failed: ${e.message}", e)
                updateNotification("Ошибка подключения")
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "RoverService stopping")
        LocalBroadcastManager.getInstance(this).apply {
            unregisterReceiver(registerReceiver)
            unregisterReceiver(cmdReceiver)
        }
        rnsManager?.stop()
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
    }
}
