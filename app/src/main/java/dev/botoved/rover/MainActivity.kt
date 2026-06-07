package dev.botoved.rover

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.core.content.ContextCompat
import dev.botoved.rover.service.RoverService
import dev.botoved.rover.ui.theme.RoverTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}
