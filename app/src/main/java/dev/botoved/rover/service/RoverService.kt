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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RoverService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rnsManagerReady = CompletableDeferred<RnsManager>()
    private var rnsManager: RnsManager? = null

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
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(registerReceiver,
                IntentFilter("dev.botoved.rover.ACTION_REGISTER"))
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
                    Log.i(TAG, "CONFIG received, notifying UI")
                    val cfgIntent = Intent("dev.botoved.rover.ACTION_CONFIG_RECEIVED")
                    LocalBroadcastManager.getInstance(this@RoverService)
                        .sendBroadcast(cfgIntent)
                }
                manager.onMessageReceived = { message ->
                    Log.i(TAG, "Message received: $message")
                }
                manager.start(identity)
                rnsManager = manager
                rnsManagerReady.complete(manager)

                updateNotification("Подключение...")
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
        LocalBroadcastManager.getInstance(this).unregisterReceiver(registerReceiver)
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
