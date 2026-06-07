package dev.botoved.rover.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RoverService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var rnsManager: RnsManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "RoverService starting")
        startForeground(NOTIFICATION_ID, buildNotification("Инициализация..."))

        serviceScope.launch {
            try {
                val identity = RoverIdentity.getOrCreate(applicationContext)
                Log.i(TAG, "Identity ready: ${identity.hexHash}")

                val manager = RnsManager(applicationContext, serviceScope)
                manager.onMessageReceived = { message ->
                    // TODO Task 3: передавать в протокольный слой
                    Log.i(TAG, "Message received: $message")
                }
                manager.start(identity)
                rnsManager = manager

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
