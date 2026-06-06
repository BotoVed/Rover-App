package dev.botoved.rover

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dagger.hilt.android.AndroidEntryPoint
import dev.botoved.rover.ui.theme.RoverTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RoverTheme {
                Text("Rover")
            }
        }
    }
}
