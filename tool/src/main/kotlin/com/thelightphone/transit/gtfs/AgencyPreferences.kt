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
 * The user's selected agency, persisted via the SDK's Preferences DataStore -- the same mechanism
 * LightPushManager uses elsewhere in the SDK for simple key-value settings. Doubles as both
 * "default" and "currently selected": HomeScreen goes straight into this agency's data when set,
 * and shows the AgencyPickerModal onboarding overlay when null (first launch, before anything's
 * ever been picked). Settings' own "Transit Agency" row is the only way to change it once set.
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

    /** Settings toggle, on by default, for a not-yet-implemented feature: merging a
     * [SecondaryGtfsFeed]'s stops into the same station group as co-located parent-agency stops
     * (e.g. Bustang's gates at RTD Denver's Union Station), the way MBTA's South Station already
     * groups its own platforms. Reading this flag has no effect anywhere yet; it exists so the
     * setting can be discussed and tested ahead of the actual merge logic. */
    val mergeFeedStationsEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[mergeFeedStationsEnabledKey] ?: true }

    suspend fun setMergeFeedStationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[mergeFeedStationsEnabledKey] = enabled }
    }
}
