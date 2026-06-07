package dev.botoved.rover

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat
import dev.botoved.rover.service.RoverService
import dev.botoved.rover.ui.theme.RoverTheme

class MainActivity : ComponentActivity() {

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        Log.i(TAG, "BLE permissions granted: $granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            )
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, RoverService::class.java)
        )
        setContent {
            RoverTheme {
                Text("Rover")
            }
        }
    }

    companion object {
        private const val TAG = "Rover"
    }
}
