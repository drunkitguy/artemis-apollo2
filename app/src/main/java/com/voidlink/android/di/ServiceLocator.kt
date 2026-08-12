package com.voidlink.android.di

import android.content.Context
import com.voidlink.android.data.AppCatalogProvider
import com.voidlink.android.data.ConnectionTester
import com.voidlink.android.data.HostRepository
import com.voidlink.android.data.HostStatusProvider
import com.voidlink.android.data.HostWaker
import com.voidlink.android.data.SettingsRepository
import com.voidlink.android.data.StubAppCatalogProvider
import com.voidlink.android.data.StubConnectionTester
import com.voidlink.android.data.StubHostStatusProvider
import com.voidlink.android.data.StubHostWaker
import com.voidlink.android.protocol.bridge.HostEndpointResolver
import com.voidlink.android.protocol.bridge.HostPairingCoordinator
import com.voidlink.android.protocol.bridge.NvHttpAppCatalogProvider
import com.voidlink.android.protocol.bridge.NvHttpConnectionTester
import com.voidlink.android.protocol.bridge.NvHttpHostStatusProvider
import com.voidlink.android.protocol.bridge.WakeOnLanHostWaker
import com.voidlink.android.protocol.crypto.IdentityStore
import com.voidlink.android.protocol.discovery.MdnsDiscovery
import com.voidlink.android.protocol.http.BoxArtCache
import com.voidlink.android.protocol.http.HostTrustStore
import com.voidlink.android.protocol.http.NvHttpClient
import com.voidlink.android.protocol.pairing.PairingEngine

/**
 * The app's one and only dependency graph.
 *
 * VoidLink is small enough that a hand-written locator beats a DI framework: the singletons are all
 * built from the application context, and the network-facing providers the UI depends on are
 * ordinary `var`s that [initialize] swaps from their stubs to the real protocol implementations.
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

    @Volatile
    private var identityStoreInstance: IdentityStore? = null

    @Volatile
    private var trustStoreInstance: HostTrustStore? = null

    @Volatile
    private var httpClientInstance: NvHttpClient? = null

    @Volatile
    private var pairingCoordinatorInstance: HostPairingCoordinator? = null

    /**
     * Liveness source for hosts.
     *
     * Starts as [StubHostStatusProvider] and becomes the real mDNS + NVHTTP implementation in
     * [initialize]; the stub remains the value only if the graph was never wired, which keeps
     * previews and unit tests runnable.
     */
    @Volatile
    var hostStatusProvider: HostStatusProvider = StubHostStatusProvider

    /** Source of a host's app list; replaced by the real `/applist` + `/appasset` client. */
    @Volatile
    var appCatalogProvider: AppCatalogProvider = StubAppCatalogProvider

    /** Wake-on-LAN sender; replaced by the real magic-packet broadcaster. */
    @Volatile
    var hostWaker: HostWaker = StubHostWaker

    /** Measures the network path to a host; replaced by the real sampler and iperf3 client. */
    @Volatile
    var connectionTester: ConnectionTester = StubConnectionTester

    /**
     * Wires the graph. Safe to call more than once; later calls are ignored.
     *
     * Nothing here touches the network or generates a key — the client identity is created lazily
     * on first use, inside a coroutine, because RSA-2048 generation is seconds of CPU that must
     * never run on the main thread.
     */
    fun initialize(context: Context) {
        if (applicationContext != null) return
        synchronized(this) {
            if (applicationContext != null) return
            val appContext = context.applicationContext
            applicationContext = appContext

            settingsRepositoryInstance = SettingsRepository.create(appContext)
            hostRepositoryInstance = HostRepository.create(appContext)

            val identityStore = IdentityStore(appContext.filesDir)
            val trustStore = HostTrustStore(appContext.filesDir)
            val httpClient = NvHttpClient(identityStore, trustStore)
            val resolver = HostEndpointResolver(httpClient)
            val boxArtCache = BoxArtCache(appContext.cacheDir)
            val pairingEngine = PairingEngine(httpClient, identityStore, trustStore)

            identityStoreInstance = identityStore
            trustStoreInstance = trustStore
            httpClientInstance = httpClient
            pairingCoordinatorInstance = HostPairingCoordinator(resolver, pairingEngine)

            hostStatusProvider = NvHttpHostStatusProvider(
                client = httpClient,
                trustStore = trustStore,
                resolver = resolver,
                mdnsDiscovery = MdnsDiscovery(appContext),
            )
            appCatalogProvider = NvHttpAppCatalogProvider(
                client = httpClient,
                resolver = resolver,
                boxArtCache = boxArtCache,
            )
            hostWaker = WakeOnLanHostWaker()
            connectionTester = NvHttpConnectionTester(
                client = httpClient,
                resolver = resolver,
            )
        }
    }

    /** The app-wide settings store. */
    val settingsRepository: SettingsRepository
        get() = settingsRepositoryInstance ?: error(NOT_INITIALIZED)

    /** The known-host store. */
    val hostRepository: HostRepository
        get() = hostRepositoryInstance ?: error(NOT_INITIALIZED)

    /**
     * Drives the five-phase PIN pairing handshake for a saved host.
     *
     * Exposed as its own entry point rather than behind one of the `data` interfaces because
     * pairing is a progress-reporting flow, not a request/response call, and none of the existing
     * provider interfaces has a shape that fits it.
     */
    val pairingCoordinator: HostPairingCoordinator
        get() = pairingCoordinatorInstance ?: error(NOT_INITIALIZED)

    /** This installation's cryptographic identity. */
    val identityStore: IdentityStore
        get() = identityStoreInstance ?: error(NOT_INITIALIZED)

    /** Per-host pinned server certificates. */
    val hostTrustStore: HostTrustStore
        get() = trustStoreInstance ?: error(NOT_INITIALIZED)

    /** The NVHTTP client, for callers that need an endpoint the providers do not expose. */
    val nvHttpClient: NvHttpClient
        get() = httpClientInstance ?: error(NOT_INITIALIZED)

    /**
     * Replaces dependencies with test doubles.
     *
     * Intended for instrumentation tests and for previews that need deterministic data.
     */
    fun overrideForTesting(
        settings: SettingsRepository? = null,
        hosts: HostRepository? = null,
        status: HostStatusProvider? = null,
        apps: AppCatalogProvider? = null,
        waker: HostWaker? = null,
        pairing: HostPairingCoordinator? = null,
        tester: ConnectionTester? = null,
    ) {
        settings?.let { settingsRepositoryInstance = it }
        hosts?.let { hostRepositoryInstance = it }
        status?.let { hostStatusProvider = it }
        apps?.let { appCatalogProvider = it }
        waker?.let { hostWaker = it }
        pairing?.let { pairingCoordinatorInstance = it }
        tester?.let { connectionTester = it }
    }

    private const val NOT_INITIALIZED =
        "ServiceLocator.initialize(context) must be called before use (see VoidLinkApplication)."
}
