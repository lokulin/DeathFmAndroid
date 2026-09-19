package com.terraeclectic.deathfm

import android.app.Application
import com.terraeclectic.deathfm.settings.SettingsStore

class DeathFmApp : Application() {
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
    }
}
