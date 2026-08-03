package com.glasspro.tracker

import android.app.Application
import com.glasspro.tracker.core.di.ServiceLocator

class GlassProApplication : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        serviceLocator = ServiceLocator(this)
    }
}
