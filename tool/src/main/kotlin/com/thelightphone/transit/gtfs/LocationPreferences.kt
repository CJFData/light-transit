package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val locationEnabledKey = booleanPreferencesKey("LOCATION_ENABLED")

/**
 * Settings' "Use my location" toggle for Explore, on top of the system permission, which the app
 * can't revoke. Off, Explore doesn't use GPS at all. Turning it on doesn't request the permission
 * by itself.
 */
class LocationPreferences(private val dataStore: DataStore<Preferences>) {

    /** On by default. */
    val locationEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[locationEnabledKey] ?: true }

    suspend fun setLocationEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[locationEnabledKey] = enabled }
    }
}
