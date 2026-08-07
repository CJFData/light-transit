package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val defaultAgencyKey = stringPreferencesKey("DEFAULT_AGENCY")
private val mergeFeedStationsEnabledKey = booleanPreferencesKey("MERGE_FEED_STATIONS_ENABLED")

/**
 * The user's default agency (Settings screen), persisted via the SDK's Preferences DataStore --
 * the same mechanism LightPushManager uses elsewhere in the SDK for simple key-value settings.
 * When set, HomeScreen skips the manual agency-tap on launch and goes straight into that agency's
 * data.
 */
class AgencyPreferences(private val dataStore: DataStore<Preferences>) {

    val defaultAgencyFlow: Flow<GtfsAgency?> = dataStore.data.map { prefs ->
        prefs[defaultAgencyKey]?.let { id -> GtfsAgency.entries.find { it.id == id } }
    }

    suspend fun setDefaultAgency(agency: GtfsAgency?) {
        dataStore.edit { prefs ->
            if (agency == null) {
                prefs.remove(defaultAgencyKey)
            } else {
                prefs[defaultAgencyKey] = agency.id
            }
        }
    }

    /** Settings screen toggle (off by default), scaffolding for a not-yet-implemented feature: when
     * on, a [SecondaryGtfsFeed]'s own stops at the same physical station as one of the parent
     * agency's own (e.g. Bustang's gates at RTD Denver's Union Station) would be merged into that
     * station's group, the same way MBTA's South Station groups its own platforms. Reading this
     * flag currently has no effect anywhere -- added ahead of that feature so the setting exists
     * and can be discussed/tested independently of the merge logic itself. */
    val mergeFeedStationsEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[mergeFeedStationsEnabledKey] ?: false }

    suspend fun setMergeFeedStationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[mergeFeedStationsEnabledKey] = enabled }
    }
}
