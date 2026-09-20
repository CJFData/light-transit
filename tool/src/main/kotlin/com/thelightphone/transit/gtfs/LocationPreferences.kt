package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val locationEnabledKey = booleanPreferencesKey("LOCATION_ENABLED")

/**
 * Settings' "Use my location" toggle for Explore -- separate from (and layered on top of) the
 * OS/LightOS location permission grant itself, which this app has no way to revoke programmatically.
 * Turning this off tells NearbyStopsScreen not to touch GPS at all, regardless of whether the
 * permission is actually granted; turning it back on doesn't re-request the permission by itself,
 * that still happens the normal way (Home priming/prompt, or NearbyStopsScreen's own retry).
 */
class LocationPreferences(private val dataStore: DataStore<Preferences>) {

    /** On by default -- matches every other Explore-adjacent default in this app. */
    val locationEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[locationEnabledKey] ?: true }

    suspend fun setLocationEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[locationEnabledKey] = enabled }
    }
}
