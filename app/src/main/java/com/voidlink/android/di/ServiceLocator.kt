package com.voidlink.android.di

import android.content.Context
import com.voidlink.android.data.AppCatalogProvider
import com.voidlink.android.data.HostRepository
import com.voidlink.android.data.HostStatusProvider
import com.voidlink.android.data.HostWaker
import com.voidlink.android.data.SettingsRepository
import com.voidlink.android.data.StubAppCatalogProvider
import com.voidlink.android.data.StubHostStatusProvider
import com.voidlink.android.data.StubHostWaker

/**
 * The app's one and only dependency graph.
 *
 * VoidLink is small enough that a hand-written locator beats a DI framework: there are three
 * singletons, they are all created from the application context, and swapping the stubbed
 * network-facing providers for the real ones is a single assignment once the protocol layer lands.
 *
 * [initialize] is called from `VoidLinkApplication.onCreate`; everything else fails fast with a
 * clear message if it is read too early.
 */
object ServiceLocator {

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var settingsRepositoryInstance: SettingsRepository? = null

    @Volatile
    private var hostRepositoryInstance: HostRepository? = null

    /**
     * Liveness source for hosts.
     *
     * Defaults to [StubHostStatusProvider]; the protocol layer replaces it during app start-up.
     */
    @Volatile
    var hostStatusProvider: HostStatusProvider = StubHostStatusProvider

    /**
     * Source of a host's app list.
     *
     * Defaults to [StubAppCatalogProvider]; the protocol layer replaces it during app start-up.
     */
    @Volatile
    var appCatalogProvider: AppCatalogProvider = StubAppCatalogProvider

    /**
     * Wake-on-LAN sender.
     *
     * Defaults to [StubHostWaker]; the protocol layer replaces it during app start-up.
     */
    @Volatile
    var hostWaker: HostWaker = StubHostWaker

    /** Wires the graph. Safe to call more than once; later calls are ignored. */
    fun initialize(context: Context) {
        if (applicationContext != null) return
        synchronized(this) {
            if (applicationContext != null) return
            val appContext = context.applicationContext
            applicationContext = appContext
            settingsRepositoryInstance = SettingsRepository.create(appContext)
            hostRepositoryInstance = HostRepository.create(appContext)
        }
    }

    /** The app-wide settings store. */
    val settingsRepository: SettingsRepository
        get() = settingsRepositoryInstance ?: error(NOT_INITIALIZED)

    /** The known-host store. */
    val hostRepository: HostRepository
        get() = hostRepositoryInstance ?: error(NOT_INITIALIZED)

    /**
     * Replaces every dependency with test doubles.
     *
     * Intended for instrumentation tests and for previews that need deterministic data.
     */
    fun overrideForTesting(
        settings: SettingsRepository? = null,
        hosts: HostRepository? = null,
        status: HostStatusProvider? = null,
        apps: AppCatalogProvider? = null,
        waker: HostWaker? = null,
    ) {
        settings?.let { settingsRepositoryInstance = it }
        hosts?.let { hostRepositoryInstance = it }
        status?.let { hostStatusProvider = it }
        apps?.let { appCatalogProvider = it }
        waker?.let { hostWaker = it }
    }

    private const val NOT_INITIALIZED =
        "ServiceLocator.initialize(context) must be called before use (see VoidLinkApplication)."
}
