package dev.botoved.rover

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.botoved.rover.service.RoverService
import dev.botoved.rover.ui.onboarding.OnboardingScreen
import dev.botoved.rover.ui.theme.RoverTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bleGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
            permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        Log.i(TAG, "BLE permissions granted: $bleGranted, location: $locationGranted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, RoverService::class.java)
        )
        setContent {
            RoverTheme {
                OnboardingScreen(onRegistered = {
                    // TODO Task 4: переход на Dashboard
                })
            }
        }
    }

    companion object {
        private const val TAG = "Rover"
    }
}
