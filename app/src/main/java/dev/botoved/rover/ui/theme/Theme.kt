package dev.botoved.rover.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Gold = Color(0xFFC9A84C)
private val GoldLight = Color(0xFFD4B85A)
private val BgPrimary = Color(0xFF1E1E1E)
private val BgSecondary = Color(0xFF262626)
private val BgCard = Color(0xFF2A2A2A)

private val RoverColorScheme = darkColorScheme(
    primary = Gold,
    onPrimary = BgPrimary,
    secondary = GoldLight,
    background = BgPrimary,
    surface = BgCard,
    surfaceVariant = BgSecondary,
)

@Composable
fun RoverTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RoverColorScheme,
        content = content
    )
}
