package com.voidlink.android

import android.app.Application
import com.voidlink.android.di.ServiceLocator

/**
 * Application entry point.
 *
 * Its only job is to build the dependency graph before any activity or view model can ask for it.
 */
class VoidLinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.initialize(this)
    }
}
