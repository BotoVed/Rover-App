package dev.botoved.rover.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@AndroidEntryPoint
class RoverService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "RoverService starting")
        startForeground(NOTIFICATION_ID, buildNotification())
        // TODO Task 2: инициализация RNS + BLE
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "RoverService stopping")
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "rover_service"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Rover",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("Rover")
            .setContentText("Инициализация...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    companion object {
        private const val TAG = "Rover"
        const val NOTIFICATION_ID = 1
    }
}
