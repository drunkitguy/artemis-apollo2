package com.voidlink.android.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable

/**
 * Reachability of a host as last observed by a [HostStatusProvider].
 */
@Serializable
enum class HostReachability {
    /** Never probed, or the last probe is stale. */
    UNKNOWN,

    /** The host answered its most recent probe. */
    ONLINE,

    /** The host did not answer its most recent probe. */
    OFFLINE,
}

/**
 * A single host the user has seen or added, as persisted on device.
 *
 * This is a pure data record: it holds no sockets, certificates or live state. Liveness comes from
 * [HostStatusProvider] and is merged into the UI state by the view model, which keeps the stored
 * list stable across app restarts even when the network is down.
 *
 * @property uuid stable identity of the host; for discovered hosts this is the host's own UUID,
 *   for manually added ones a locally generated one.
 * @property name display name, editable by the user via "Rename".
 * @property addresses every address the host is known at, most recently successful first. A host
 *   commonly has both a LAN address and an IPv6 or external one.
 * @property macAddress the host's MAC, when known; required for Wake-on-LAN.
 * @property paired true once the pairing handshake has completed and a client certificate is
 *   trusted by the host.
 * @property settingsOverride per-host settings that win over the global ones, or `null` to inherit.
 * @property lastSeenEpochMillis wall-clock time of the last successful probe, or `0` if never.
 * @property manuallyAdded true when the user typed the address rather than discovery finding it.
 */
@Serializable
data class KnownHost(
    val uuid: String,
    val name: String,
    val addresses: List<String> = emptyList(),
    val macAddress: String? = null,
    val paired: Boolean = false,
    val settingsOverride: StreamSettings? = null,
    val lastSeenEpochMillis: Long = 0L,
    val manuallyAdded: Boolean = false,
) {
    /** The address a connection attempt should try first, or `null` when none is known. */
    val primaryAddress: String? get() = addresses.firstOrNull()

    /** True when Wake-on-LAN can actually be attempted for this host. */
    val canWakeOnLan: Boolean get() = !macAddress.isNullOrBlank()

    /**
     * Resolves which settings apply to this host.
     *
     * @param globalSettings the app-wide settings.
     * @return [settingsOverride] when present, otherwise [globalSettings].
     */
    fun effectiveSettings(globalSettings: StreamSettings): StreamSettings =
        settingsOverride ?: globalSettings

    /**
     * Returns a copy whose per-host override is [transform] applied to the settings this host
     * currently resolves to.
     *
     * The seed is [effectiveSettings], not the factory defaults: the first time a user changes one
     * row for one PC, every other setting must stay where the global panel had it. Anything else
     * turns "raise the bitrate for the living-room PC" into "silently reset that PC".
     *
     * @param globalSettings the app-wide settings this host would otherwise inherit.
     * @param transform the edit to apply.
     */
    fun withOverride(
        globalSettings: StreamSettings,
        transform: (StreamSettings) -> StreamSettings,
    ): KnownHost = copy(settingsOverride = transform(effectiveSettings(globalSettings)).coerced())

    /**
     * Returns a copy with [address] promoted to the front of [addresses], de-duplicated.
     *
     * Used after a successful probe so the address that worked is tried first next time.
     */
    fun withPreferredAddress(address: String): KnownHost {
        if (address.isBlank()) return this
        val reordered = buildList {
            add(address)
            addresses.forEach { if (!it.equals(address, ignoreCase = true)) add(it) }
        }
        return copy(addresses = reordered)
    }

    /**
     * Returns a copy carrying [macAddress] when the probe actually learned one.
     *
     * A MAC read back over HTTPS from a paired host is authoritative and replaces whatever
     * discovery advertised, but a probe that returns nothing must never erase a MAC we already
     * have — Sunshine withholds it over plaintext, so "no MAC in this answer" is the normal case,
     * not a correction.
     */
    fun withLearnedMac(macAddress: String?): KnownHost =
        if (macAddress.isNullOrBlank()) this else copy(macAddress = macAddress)

    /**
     * Returns a copy that records a successful sighting at [nowEpochMillis], optionally promoting
     * the address that answered.
     */
    fun markSeen(nowEpochMillis: Long, atAddress: String? = null): KnownHost {
        val promoted = if (atAddress.isNullOrBlank()) this else withPreferredAddress(atAddress)
        return promoted.copy(lastSeenEpochMillis = nowEpochMillis)
    }
}

/**
 * The result of probing a single host.
 *
 * @property reachability whether the host answered.
 * @property paired whether the host still trusts this client's certificate. Hosts forget clients,
 *   so this can flip back to false without the user doing anything locally.
 * @property runningAppId id of the app currently streaming on the host, or `null` when idle.
 * @property runningAppName human-readable name of [runningAppId], when the host supplied one.
 * @property hostName the host's self-reported name, used to keep a renamed PC in sync.
 * @property macAddress the host's MAC, when this probe learned one.
 *
 *   Sunshine returns the real MAC only over HTTPS to a client it already trusts, so this is
 *   populated on exactly the probes that can see it and null on the rest. It is the only route by
 *   which a manually added host — one discovery never advertised — can ever become wakeable, which
 *   is precisely the host a user most wants to wake.
 */
data class HostStatus(
    val reachability: HostReachability = HostReachability.UNKNOWN,
    val paired: Boolean = false,
    val runningAppId: String? = null,
    val runningAppName: String? = null,
    val hostName: String? = null,
    val macAddress: String? = null,
) {
    /** Convenience predicate matching the green "Online" state in the UI. */
    val isOnline: Boolean get() = reachability == HostReachability.ONLINE

    companion object {
        /** The status used before any probe has completed. */
        val Unknown: HostStatus = HostStatus()

        /** A definitively unreachable host. */
        val Offline: HostStatus = HostStatus(reachability = HostReachability.OFFLINE)
    }
}

/**
 * A host observed on the local network by discovery, before it is persisted as a [KnownHost].
 *
 * @property uuid the host's advertised unique id, used to merge with an existing [KnownHost].
 * @property name the host's advertised name.
 * @property address the address discovery saw it at.
 * @property macAddress advertised MAC, when the discovery mechanism exposes one.
 */
data class DiscoveredHost(
    val uuid: String,
    val name: String,
    val address: String,
    val macAddress: String? = null,
)

/**
 * Liveness source for hosts.
 *
 * Deliberately an interface owned by the data layer: the protocol/networking task implements it
 * later without [HostRepository] ever learning about sockets. [StubHostStatusProvider] keeps the
 * app runnable until then.
 */
interface HostStatusProvider {
    /**
     * Probes a single host.
     *
     * Implementations must be safe to call from a background dispatcher and must not throw for an
     * ordinary network failure — they return [HostStatus.Offline] instead.
     */
    suspend fun probe(host: KnownHost): HostStatus

    /**
     * Emits hosts found on the local network.
     *
     * The returned flow is cold: collecting it starts discovery, cancelling it stops discovery.
     * It may emit the same host repeatedly as its advertisement is re-seen.
     */
    fun discover(): Flow<DiscoveredHost>
}

/**
 * No-op [HostStatusProvider] used until the protocol layer lands.
 *
 * Every host reports as offline and discovery finds nothing; the app is fully navigable and the
 * settings UI is fully usable against it.
 */
object StubHostStatusProvider : HostStatusProvider {
    override suspend fun probe(host: KnownHost): HostStatus = HostStatus.Offline

    override fun discover(): Flow<DiscoveredHost> =
        emptyFlow()
}
