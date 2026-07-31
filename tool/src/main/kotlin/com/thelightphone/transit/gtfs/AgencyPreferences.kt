package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val defaultAgencyKey = stringPreferencesKey("DEFAULT_AGENCY")

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
}
