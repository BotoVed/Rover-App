package dev.botoved.rover

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class RoverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@RoverApp)
            modules(emptyList()) // TODO: добавлять модули по мере разработки
        }
    }
}
