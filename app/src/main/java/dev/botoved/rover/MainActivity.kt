package dev.botoved.rover

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import dev.botoved.rover.data.ServerPreferences
import dev.botoved.rover.service.RoverService
import dev.botoved.rover.ui.AppDestination
import dev.botoved.rover.ui.MainViewModel
import dev.botoved.rover.ui.dashboard.DashboardScreen
import dev.botoved.rover.ui.onboarding.OnboardingScreen
import dev.botoved.rover.ui.theme.RoverTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel as koinViewModel

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by koinViewModel()
    private val prefs: ServerPreferences by inject()

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

        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val notGranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, RoverService::class.java)
        )
        setContent {
            RoverTheme {
                val destination by mainViewModel.destination.collectAsState()
                when (destination) {
                    is AppDestination.Splash -> {
                        Box(modifier = Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.background))
                    }
                    is AppDestination.Onboarding -> {
                        OnboardingScreen(onRegistered = {
                            lifecycleScope.launch { prefs.setApproved() }
                        })
                    }
                    is AppDestination.Dashboard -> {
                        DashboardScreen()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "Rover"
    }
}
