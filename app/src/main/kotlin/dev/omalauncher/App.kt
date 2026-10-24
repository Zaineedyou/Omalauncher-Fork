package dev.omalauncher

import android.app.Application

class App : Application() {
    val runtime by lazy { Runtime(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
