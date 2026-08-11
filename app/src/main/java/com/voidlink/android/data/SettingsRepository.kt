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
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException

/** DataStore instance backing [SettingsRepository]. */
private val Context.streamSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "voidlink_settings",
)

/**
 * Persists the app-wide [StreamSettings].
 *
 * The entire object is stored as one JSON string under a single preference key rather than as a
 * key per field. That keeps writes atomic (a settings change is never observed half-applied) and
 * makes schema evolution free: unknown keys in an older or newer blob are ignored and missing keys
 * fall back to the constructor defaults.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    /**
     * The current settings, re-emitted on every change.
     *
     * Read errors (a corrupt blob, an unreadable file) degrade to defaults rather than crashing —
     * a settings file is never worth losing the app over.
     */
    val settings: Flow<StreamSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> decode(preferences[KEY_SETTINGS_JSON]) }

    /**
     * Applies [transform] to the stored settings atomically.
     *
     * @param transform receives the current settings and returns the new ones.
     */
    suspend fun update(transform: (StreamSettings) -> StreamSettings) {
        dataStore.edit { preferences ->
            val current = decode(preferences[KEY_SETTINGS_JSON])
            preferences[KEY_SETTINGS_JSON] = json.encodeToString(StreamSettings.serializer(), transform(current).coerced())
        }
    }

    /** Replaces the stored settings wholesale. */
    suspend fun replace(settings: StreamSettings) {
        update { settings }
    }

    /** Restores every setting to its default. */
    suspend fun resetToDefaults() {
        update { StreamSettings() }
    }

    private fun decode(raw: String?): StreamSettings {
        if (raw.isNullOrBlank()) return StreamSettings()
        return runCatching { json.decodeFromString(StreamSettings.serializer(), raw) }
            .getOrDefault(StreamSettings())
            .coerced()
    }

    companion object {
        private val KEY_SETTINGS_JSON = stringPreferencesKey("stream_settings_json")

        /**
         * Shared JSON codec.
         *
         * `ignoreUnknownKeys` lets a downgrade read a blob written by a newer build;
         * `encodeDefaults` keeps the stored document self-describing.
         */
        internal val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }

        /** Builds the production repository bound to the app's DataStore file. */
        fun create(context: Context): SettingsRepository =
            SettingsRepository(context.applicationContext.streamSettingsDataStore)
    }
}
