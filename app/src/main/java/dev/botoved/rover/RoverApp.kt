package dev.botoved.rover

import android.app.Application
import android.util.Log
import dev.botoved.rover.di.appModule
import dev.botoved.rover.service.PyRnsBridge
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class RoverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@RoverApp)
            modules(appModule)
        }
        AppLogger.init(filesDir)
        // SPIKE: disabled after stage 2 (onboarding via QR works, spike role complete)
    }

    companion object {
        private const val TAG = "Rover"
    }
}
