package dev.botoved.rover.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.botoved.rover.data.ServerPreferences
import kotlinx.coroutines.flow.*

sealed class AppDestination {
    object Splash : AppDestination()
    object Onboarding : AppDestination()
    object Dashboard : AppDestination()
}

class MainViewModel(
    private val prefs: ServerPreferences
) : ViewModel() {

    val destination: StateFlow<AppDestination> = prefs.isRegistered
        .map { status ->
            when (status) {
                "approved" -> AppDestination.Dashboard
                else -> AppDestination.Onboarding
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppDestination.Splash
        )
}
