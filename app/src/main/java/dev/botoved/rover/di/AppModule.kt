package dev.botoved.rover.di

import dev.botoved.rover.data.ServerPreferences
import dev.botoved.rover.ui.onboarding.OnboardingViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { ServerPreferences(androidContext()) }
    viewModel { OnboardingViewModel(get()) }
}
