package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val locationEnabledKey = booleanPreferencesKey("LOCATION_ENABLED")
private val defaultLatKey = doublePreferencesKey("DEFAULT_LOCATION_LAT")
private val defaultLonKey = doublePreferencesKey("DEFAULT_LOCATION_LON")
private val defaultLabelKey = stringPreferencesKey("DEFAULT_LOCATION_LABEL")
private val defaultIsCurrentKey = booleanPreferencesKey("DEFAULT_LOCATION_IS_CURRENT")

/** A place saved on the phone, so it never has to be looked up again. */
data class SavedLocation(val lat: Double, val lon: Double, val label: String)

/** Where Explore opens: live GPS each time, or a saved place. */
sealed class DefaultLocation {
    object Current : DefaultLocation()
    data class Place(val location: SavedLocation) : DefaultLocation()
}

/**
 * Explore's location settings. "Use my location" sits on top of the system permission, which the
 * app can't revoke; off, Explore doesn't use GPS at all. The default location is where Explore
 * opens.
 */
class LocationPreferences(private val dataStore: DataStore<Preferences>) {

    /** Off by default; turned on from Explore or Settings. */
    val locationEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[locationEnabledKey] ?: false }

    suspend fun setLocationEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[locationEnabledKey] = enabled }
    }

    /** Where Explore opens; null when no default is set. */
    val defaultLocationFlow: Flow<DefaultLocation?> = dataStore.data.map { prefs ->
        val lat = prefs[defaultLatKey]
        val lon = prefs[defaultLonKey]
        when {
            prefs[defaultIsCurrentKey] == true -> DefaultLocation.Current
            lat != null && lon != null ->
                DefaultLocation.Place(SavedLocation(lat, lon, prefs[defaultLabelKey] ?: "Default location"))
            else -> null
        }
    }

    suspend fun setDefaultLocation(location: SavedLocation) {
        dataStore.edit { prefs ->
            prefs.remove(defaultIsCurrentKey)
            prefs[defaultLatKey] = location.lat
            prefs[defaultLonKey] = location.lon
            prefs[defaultLabelKey] = location.label
        }
    }

    suspend fun setDefaultToCurrentLocation() {
        dataStore.edit { prefs ->
            prefs.remove(defaultLatKey)
            prefs.remove(defaultLonKey)
            prefs.remove(defaultLabelKey)
            prefs[defaultIsCurrentKey] = true
        }
    }

    suspend fun clearDefaultLocation() {
        dataStore.edit { prefs ->
            prefs.remove(defaultIsCurrentKey)
            prefs.remove(defaultLatKey)
            prefs.remove(defaultLonKey)
            prefs.remove(defaultLabelKey)
        }
    }
}
