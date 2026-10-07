package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val defaultAgencyKey = stringPreferencesKey("DEFAULT_AGENCY")
private val mergeFeedStationsEnabledKey = booleanPreferencesKey("MERGE_FEED_STATIONS_ENABLED")
private val additionalDownloadsKey = stringSetPreferencesKey("ADDITIONAL_DOWNLOADS")

/**
 * The selected agency, saved in DataStore. Home opens it when set and shows the agency picker when
 * it isn't; Settings changes it.
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

    /**
     * On by default. Groups an extra feed's stops into the same station as nearby stops of the main
     * agency (see [GtfsRepository.mergeFeedStations]).
     */
    val mergeFeedStationsEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[mergeFeedStationsEnabledKey] ?: true }

    suspend fun setMergeFeedStationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[mergeFeedStationsEnabledKey] = enabled }
    }

    /**
     * Agencies downloaded in addition to the primary, e.g. a bus borough or LIRR alongside NYC
     * Subway. The primary isn't in this set.
     */
    val additionalDownloadsFlow: Flow<Set<GtfsAgency>> = dataStore.data.map { prefs ->
        (prefs[additionalDownloadsKey] ?: emptySet())
            .mapNotNull { id -> GtfsAgency.entries.find { it.id == id } }
            .toSet()
    }

    suspend fun setAgencyDownloadEnabled(agency: GtfsAgency, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[additionalDownloadsKey] ?: emptySet()
            prefs[additionalDownloadsKey] = if (enabled) current + agency.id else current - agency.id
        }
    }

    /**
     * Makes [agency] the primary, from Additional Schedules' tap and hold or boarding one of its
     * trips. The old primary moves into the additional set so its schedule stays available. Does
     * nothing when [agency] is already primary.
     */
    suspend fun promoteToPrimary(agency: GtfsAgency) {
        dataStore.edit { prefs ->
            val outgoingPrimary = prefs[defaultAgencyKey]?.let { id -> GtfsAgency.entries.find { it.id == id } }
            if (outgoingPrimary == agency) return@edit
            val current = prefs[additionalDownloadsKey] ?: emptySet()
            var updated = current - agency.id
            if (outgoingPrimary != null) updated = updated + outgoingPrimary.id
            prefs[additionalDownloadsKey] = updated
            prefs[defaultAgencyKey] = agency.id
        }
    }
}
