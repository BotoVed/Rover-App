package dev.botoved.rover

import android.app.Application
import dev.botoved.rover.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class RoverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@RoverApp)
            modules(appModule)
        }
    }
}
