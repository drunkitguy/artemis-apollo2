package com.voidlink.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.util.UUID

/** DataStore instance backing [HostRepository]. */
private val Context.knownHostsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "voidlink_hosts",
)

/**
 * Stores the user's list of known hosts.
 *
 * Strictly persistence and CRUD: this class performs no networking whatsoever. Liveness is the job
 * of [HostStatusProvider], which the protocol layer implements later; the view model joins the two.
 */
class HostRepository(private val dataStore: DataStore<Preferences>) {

    /** Every known host, ordered by display name, re-emitted on every change. */
    val hosts: Flow<List<KnownHost>> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> decode(preferences[KEY_HOSTS_JSON]) }

    /** Reads the current list once. */
    suspend fun snapshot(): List<KnownHost> = hosts.first()

    /**
     * Inserts [host], or replaces the existing entry with the same
     * [uuid][KnownHost.uuid].
     */
    suspend fun upsert(host: KnownHost) {
        mutate { current ->
            val index = current.indexOfFirst { it.uuid == host.uuid }
            if (index < 0) current + host else current.toMutableList().also { it[index] = host }
        }
    }

    /**
     * Applies [transform] to the host with [uuid], if it exists.
     *
     * @return true when a host was found and rewritten.
     */
    suspend fun updateHost(uuid: String, transform: (KnownHost) -> KnownHost): Boolean {
        var changed = false
        mutate { current ->
            current.map { host ->
                if (host.uuid == uuid) {
                    changed = true
                    transform(host)
                } else {
                    host
                }
            }
        }
        return changed
    }

    /** Removes the host with [uuid]. */
    suspend fun delete(uuid: String) {
        mutate { current -> current.filterNot { it.uuid == uuid } }
    }

    /** Renames the host with [uuid]; blank names are ignored. */
    suspend fun rename(uuid: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        updateHost(uuid) { it.copy(name = trimmed) }
    }

    /** Clears the paired flag so the host is offered a fresh PIN pairing. */
    suspend fun markUnpaired(uuid: String) {
        updateHost(uuid) { it.copy(paired = false) }
    }

    /** Sets the paired flag after a successful pairing handshake. */
    suspend fun markPaired(uuid: String) {
        updateHost(uuid) { it.copy(paired = true) }
    }

    /** Stores (or with `null`, clears) a per-host settings override. */
    suspend fun setSettingsOverride(uuid: String, override: StreamSettings?) {
        updateHost(uuid) { it.copy(settingsOverride = override?.coerced()) }
    }

    /**
     * Merges a discovery result into the stored list.
     *
     * An existing host keeps its user-chosen name and pairing state; only its address list and
     * last-seen timestamp are refreshed. A brand new host is appended.
     *
     * @param discovered the host as advertised on the network.
     * @param nowEpochMillis the sighting time to record.
     */
    suspend fun mergeDiscovered(discovered: DiscoveredHost, nowEpochMillis: Long) {
        mutate { current ->
            val index = current.indexOfFirst { it.uuid == discovered.uuid }
            if (index < 0) {
                current + KnownHost(
                    uuid = discovered.uuid,
                    name = discovered.name,
                    addresses = listOf(discovered.address),
                    macAddress = discovered.macAddress,
                    lastSeenEpochMillis = nowEpochMillis,
                )
            } else {
                val existing = current[index]
                val merged = existing
                    .withPreferredAddress(discovered.address)
                    .copy(
                        macAddress = discovered.macAddress ?: existing.macAddress,
                        lastSeenEpochMillis = nowEpochMillis,
                    )
                current.toMutableList().also { it[index] = merged }
            }
        }
    }

    /**
     * Creates and stores a host the user typed in by hand.
     *
     * @param address the hostname or IP the user entered.
     * @param name optional display name; defaults to [address].
     * @return the stored host, so callers can immediately act on it.
     */
    suspend fun addManualHost(address: String, name: String? = null): KnownHost {
        val trimmedAddress = address.trim()
        val host = KnownHost(
            uuid = UUID.randomUUID().toString(),
            name = name?.trim().takeUnless { it.isNullOrEmpty() } ?: trimmedAddress,
            addresses = listOf(trimmedAddress),
            manuallyAdded = true,
        )
        upsert(host)
        return host
    }

    private suspend fun mutate(transform: (List<KnownHost>) -> List<KnownHost>) {
        dataStore.edit { preferences ->
            val current = decode(preferences[KEY_HOSTS_JSON])
            val next = transform(current).sortedBy { it.name.lowercase() }
            preferences[KEY_HOSTS_JSON] = SettingsRepository.json.encodeToString(serializer, next)
        }
    }

    private fun decode(raw: String?): List<KnownHost> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { SettingsRepository.json.decodeFromString(serializer, raw) }
            .getOrDefault(emptyList())
    }

    companion object {
        private val KEY_HOSTS_JSON = stringPreferencesKey("known_hosts_json")
        private val serializer = ListSerializer(KnownHost.serializer())

        /** Builds the production repository bound to the app's DataStore file. */
        fun create(context: Context): HostRepository =
            HostRepository(context.applicationContext.knownHostsDataStore)
    }
}
