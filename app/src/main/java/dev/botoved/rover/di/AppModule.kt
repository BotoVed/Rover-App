package dev.botoved.rover.di

import androidx.room.Room
import dev.botoved.rover.data.RoverRepository
import dev.botoved.rover.data.ServerPreferences
import dev.botoved.rover.data.db.RoverDatabase
import dev.botoved.rover.ui.MainViewModel
import dev.botoved.rover.ui.dashboard.DashboardViewModel
import dev.botoved.rover.ui.onboarding.OnboardingViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    single { ServerPreferences(androidContext()) }

    single {
        Room.databaseBuilder(androidContext(), RoverDatabase::class.java, "rover.db")
            .fallbackToDestructiveMigration()
            .build()
    }
    single { RoverRepository(get()) }

    viewModel { OnboardingViewModel(get()) }
    viewModel { DashboardViewModel(get(), androidContext()) }
    viewModel { MainViewModel(get()) }
}
