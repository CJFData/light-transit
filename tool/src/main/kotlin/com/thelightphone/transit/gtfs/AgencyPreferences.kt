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

    /** Settings toggle, on by default, gating [GtfsRepository.mergeFeedStations]: folding a
     * [MultiGtfsFeed]'s stops into the same station group as co-located parent-agency stops (e.g.
     * Bustang's gates at RTD Denver's Union Station), the way MBTA's South Station already groups
     * its own platforms. */
    val mergeFeedStationsEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs -> prefs[mergeFeedStationsEnabledKey] ?: true }

    suspend fun setMergeFeedStationsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[mergeFeedStationsEnabledKey] = enabled }
    }

    /**
     * Agencies a rider has opted into downloading *in addition to* [defaultAgencyFlow]'s own
     * primary one -- e.g. a rider whose primary agency is NYC Subway but who also transfers to a
     * specific bus borough or LIRR. The primary agency is always implicitly downloaded already
     * (existing behavior, unrelated to this flow); this only tracks the extras, so a picker UI
     * should show the primary agency's own row as always-on rather than a duplicate independent
     * selection. Unset entirely (empty set) for a rider who's never opted into any extras.
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
     * Swaps [agency] in as the new primary, atomically -- shared by
     * [ScheduleSelectionViewModel.makePrimary]'s tap+hold action and by boarding a trip from a
     * schedule that isn't already primary (a rider actively riding it is a stronger signal than
     * whatever was primary before, see TripDetailViewModel.board's own doc). The outgoing primary
     * isn't just dropped -- it's added to the "additional" set in [agency]'s place, so its
     * already-downloaded schedule stays reachable rather than silently disappearing the moment it
     * stops being primary. [agency] itself is removed from that set since a primary is already
     * implicitly downloaded, not tracked as an "extra" (see [additionalDownloadsFlow]'s own doc). A
     * no-op when [agency] is already primary.
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
