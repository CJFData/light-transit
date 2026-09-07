package com.thelightphone.transit.gtfs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val showStopsBeforeBoardingKey = booleanPreferencesKey("SHOW_STOPS_BEFORE_BOARDING_ENABLED")

/**
 * Whether Trip Detail's stop list widens backward to the trip's very first stop (rather than
 * starting at the rider's own boarding stop) -- the earlier stops render greyed out, the same
 * treatment [SelectRunScreen] already uses, purely so the existing single live-vehicle marker
 * (see TripDetailState.Loaded.liveAtStopSequence's own doc) can render when the vehicle is still
 * approaching from before the boarding stop, instead of silently having nowhere to show up. Off
 * by default -- opt-in extra context, not the default trip view.
 */
class TripDetailPreferences(private val dataStore: DataStore<Preferences>) {

    val showStopsBeforeBoardingEnabledFlow: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[showStopsBeforeBoardingKey] ?: false }

    suspend fun setShowStopsBeforeBoardingEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[showStopsBeforeBoardingKey] = enabled }
    }
}
